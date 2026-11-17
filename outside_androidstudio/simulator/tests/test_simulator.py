import struct
import unittest
from unittest.mock import patch
import queue
import time
import math

from simulator import protocol as p
from simulator.model import Simulator, StatusSchedule
from simulator.app import SerialWorker


class ProtocolTests(unittest.TestCase):
    def test_crc_and_approved_packet(self):
        self.assertEqual(0x29B1, p.crc16(b"123456789"))
        packet = p.Packet(p.MOTION, 4, 0x12345678, struct.pack("<hhh", 500, 300, 0))
        self.assertEqual(
            "a55a01100400060078563412f4012c01000073d5", packet.encode().hex()
        )

    def test_fragmented_concatenated_frames_and_corrupt_frame(self):
        first = p.Packet(p.PING, 1, 42)
        second = p.Packet(p.STOP, 2, 42)
        corrupt = bytearray(first.encode())
        corrupt[-1] ^= 1
        wire = b"garbage" + bytes(corrupt) + first.encode() + second.encode()
        decoder = p.Decoder()
        result = []
        for byte in wire:
            result.extend(decoder.feed(bytes([byte])))
        self.assertEqual([first, second], result)
        self.assertGreater(decoder.errors, 0)

    def test_bad_length_resynchronizes(self):
        invalid = b"\xa5\x5a" + struct.pack("<BBHHI", 1, 1, 1, 65535, 42)
        packet = p.Packet(p.PING, 2, 42)
        self.assertEqual([packet], p.Decoder().feed(invalid + packet.encode()))

    def test_struct_sizes(self):
        self.assertEqual(34, p.STATUS_DATA.size)
        self.assertEqual(30, p.POINT_DATA.size)

    def test_push_vectors_match_android(self):
        begin = p.Packet(p.TEACH_BEGIN, 6, 0x12345678, struct.pack("<BI", 3, 1))
        self.assertEqual("a55a01300600050078563412030100000000e5", begin.encode().hex())
        status = p.Packet(p.STATUS, 100, 0x12345678, p.STATUS_DATA.pack(
            1500, 1, 120, 300, 9000, 3000, -1500, 3, 1, 7, 4, 4
        ))
        self.assertEqual(
            "a55a01906400220078563412dc05000001000000780000002c0100002823b80b24fa03010700000004000000040026c5",
            status.encode().hex()
        )


class ScheduleTests(unittest.TestCase):
    def test_configurable_deadlines_and_late_ticks_skip_missed_periods(self):
        for interval in (20, 50, 100, 1000):
            with self.subTest(interval=interval):
                schedule = StatusSchedule(interval)
                schedule.reset(10)
                self.assertFalse(schedule.due(10 + interval / 2000))
                self.assertTrue(schedule.due(10 + interval / 1000))
                self.assertFalse(schedule.due(10 + interval / 1000))
                self.assertTrue(schedule.due(15.123))
                self.assertFalse(schedule.due(15.123))
                self.assertGreater(schedule.next_due, 15.123)

    def test_invalid_periods_are_rejected_before_starting_a_worker(self):
        for interval in (0, 19, 1001, -50, 50.5, "50"):
            with self.subTest(interval=interval):
                with self.assertRaises(ValueError):
                    SerialWorker("TEST", interval)


class SimulatorTests(unittest.TestCase):
    def setUp(self):
        self.car = Simulator()
        self.seq = 0
        self.send(p.HELLO)
        self.send(p.CALIBRATE)

    def send(self, kind, data=b"", now=0.0):
        self.seq += 1
        return self.car.handle(p.Packet(kind, self.seq, 123, data), now)

    def test_calibration_retry_is_idempotent_and_new_session_rejects_old_frames(self):
        request = p.Packet(p.CALIBRATE, 50, 123)
        first = self.car.handle(request, 0)
        self.assertEqual(first, self.car.handle(request, 0))
        self.assertEqual(2, self.car.frame_id)
        self.car.handle(p.Packet(p.HELLO, 1, 456), 0)
        self.assertEqual([], self.car.handle(p.Packet(p.MOTION, 2, 123, struct.pack("<hhh", 1000, 0, 0)), 0))
        self.assertEqual((0, 0, 0), self.car.motion)

    def test_motion_deadman_and_gimbal_hold(self):
        self.send(p.MOTION, struct.pack("<hhh", 500, 1000, 0))
        self.send(p.GIMBAL, struct.pack("<hh", 3000, -1500))
        self.car.advance(0.2)
        self.assertAlmostEqual(20, self.car.x)
        self.assertAlmostEqual(40, self.car.y)
        self.car.advance(1.0)
        self.assertAlmostEqual(30, self.car.x)
        self.assertAlmostEqual(60, self.car.y)
        self.assertEqual((0, 0, 0), self.car.motion)
        self.assertEqual((3000, -1500), (self.car.gimbal_yaw, self.car.gimbal_pitch))

    def test_rotated_body_coordinates(self):
        self.car.yaw = 90
        self.send(p.MOTION, struct.pack("<hhh", 0, 1000, 0))
        self.car.advance(0.1)
        self.assertAlmostEqual(20, self.car.x)
        self.assertAlmostEqual(0, self.car.y)

    def test_final_sample_precedes_end_ack_and_counts_match(self):
        self.send(p.TEACH_BEGIN, struct.pack("<BI", 1, self.car.frame_id))
        self.car.advance(0.05)
        self.car.status()
        replies = self.send(p.TEACH_END, struct.pack("<I", self.car.teach_id), now=0.1)
        self.assertEqual([p.STATUS, p.ACK], [frame.kind for frame in replies])
        self.assertEqual(3, p.ACK_DATA.unpack(replies[-1].payload)[2])
        self.assertEqual(3, len(self.car.histories[1]))
        self.assertEqual(0, self.car.mode)


    def test_capture_retry_returns_same_snapshot(self):
        self.send(p.TEACH_BEGIN, struct.pack("<BI", 2, self.car.frame_id))
        request = p.Packet(p.TAKE_POINT, 30, 123, struct.pack("<I", self.car.teach_id))
        first = self.car.handle(request, 0.1)
        self.car.x = 100
        self.assertEqual(first, self.car.handle(request, 0.2))
        self.assertEqual(1, len(self.car.histories[2]))
        self.assertEqual(0, p.POINT_DATA.unpack(first[0].payload)[4])

    def test_calibration_and_teaching_state_errors(self):
        self.send(p.MOTION, struct.pack("<hhh", 1, 0, 0))
        self.assertEqual(p.BAD_STATE, p.ACK_DATA.unpack(self.send(p.CALIBRATE)[0].payload)[1])
        self.send(p.STOP)
        reply = self.send(p.TEACH_BEGIN, struct.pack("<BI", 1, 999))[0]
        self.assertEqual(p.FRAME_MISMATCH, p.ACK_DATA.unpack(reply.payload)[1])

    def test_disconnect_interrupts_teaching(self):
        self.send(p.TEACH_BEGIN, struct.pack("<BI", 1, self.car.frame_id))
        self.car.advance(3.1)
        self.assertFalse(self.car.connected)
        self.assertTrue(self.car.interrupted)
        self.assertEqual(0, self.car.mode)

    def test_push_begin_and_end_have_full_samples_and_idempotent_replies(self):
        begin = p.Packet(p.TEACH_BEGIN, 30, 123, struct.pack("<BI", 3, self.car.frame_id))
        replies = self.car.handle(begin, 0)
        self.assertEqual([p.ACK, p.STATUS], [reply.kind for reply in replies])
        self.assertEqual(replies, self.car.handle(begin, 0.05))
        self.assertEqual(1, self.car.index)
        self.car.advance(0.1)
        sample = p.STATUS_DATA.unpack(self.car.status().payload)
        self.assertEqual((100, self.car.frame_id), sample[:2])
        self.assertEqual((3, 1, self.car.teach_id, 2), sample[7:11])
        end = p.Packet(p.TEACH_END, 31, 123, struct.pack("<I", self.car.teach_id))
        replies = self.car.handle(end, 0.2)
        self.assertEqual([p.STATUS, p.ACK], [reply.kind for reply in replies])
        self.assertEqual(3, p.ACK_DATA.unpack(replies[-1].payload)[2])
        self.assertEqual(3, len(self.car.histories[3]))
        self.assertEqual(replies, self.car.handle(end, 0.3))
        self.assertEqual(3, len(self.car.histories[3]))
        frozen = self.car.pose()
        self.car.advance(0.5)
        self.assertEqual((frozen.x, frozen.y, frozen.yaw), (self.car.pose().x, self.car.pose().y, self.car.pose().yaw))

    def test_push_trajectory_uses_origin_and_elapsed_time_not_sample_count(self):
        self.car.x, self.car.y, self.car.yaw = 100, -200, 90
        self.send(p.TEACH_BEGIN, struct.pack("<BI", 3, self.car.frame_id))
        self.car.advance(1.0)
        theta = math.radians(20)
        self.assertAlmostEqual(100 + 500 * math.sin(theta), self.car.x)
        self.assertAlmostEqual(-200 - 500 * (1 - math.cos(theta)), self.car.y)
        self.assertAlmostEqual(110, self.car.yaw)
        before = self.car.pose()
        for _ in range(10):
            self.car.status()
        self.assertEqual(before, self.car.pose())

    def test_push_rejects_motors_stop_does_not_stop_external_push_and_gimbal_can_take_over(self):
        self.send(p.TEACH_BEGIN, struct.pack("<BI", 3, self.car.frame_id))
        rejected = self.send(p.MOTION, struct.pack("<hhh", 500, 0, 0), now=0.5)
        self.assertEqual(p.BAD_STATE, p.ACK_DATA.unpack(rejected[0].payload)[1])
        self.send(p.STOP, now=0.7)
        initial = self.car.pose()
        self.send(p.GIMBAL, struct.pack("<hh", 4500, -2000), now=1.0)
        self.car.advance(2.0)
        self.assertGreater(self.car.x, initial.x)
        self.assertEqual((4500, -2000), (self.car.gimbal_yaw, self.car.gimbal_pitch))
        self.assertEqual((0, 0, 0), self.car.motion)

    def test_push_default_gimbal_wave_is_bounded_and_disconnect_freezes_motion(self):
        self.send(p.GIMBAL, struct.pack("<hh", 17000, -17000))
        self.send(p.TEACH_BEGIN, struct.pack("<BI", 3, self.car.frame_id))
        self.car.advance(2.5)
        self.assertEqual((18000, -15500), (self.car.gimbal_yaw, self.car.gimbal_pitch))
        self.car.advance(3.1)
        frozen = self.car.pose()
        self.car.advance(5)
        self.assertEqual((frozen.x, frozen.y, frozen.yaw), (self.car.pose().x, self.car.pose().y, self.car.pose().yaw))
        self.assertFalse(self.car.connected)
        self.assertTrue(self.car.interrupted)


class SerialWorkerTests(unittest.TestCase):
    def test_worker_handshake_record_and_final_count_over_byte_stream(self):
        self.record_over_byte_stream(1, 50)

    def test_push_at_50ms_over_byte_stream(self):
        self.record_over_byte_stream(3, 50)

    def test_push_at_100ms_over_byte_stream(self):
        self.record_over_byte_stream(3, 100)

    def record_over_byte_stream(self, mode, interval_ms):
        class Port:
            in_waiting = 0

            def __init__(self):
                self.incoming = queue.Queue()
                self.outgoing = queue.Queue()

            def __enter__(self):
                return self

            def __exit__(self, *args):
                pass

            def reset_input_buffer(self):
                pass

            def read(self, length):
                try:
                    return self.incoming.get(timeout=0.01)
                except queue.Empty:
                    return b""

            def write(self, data):
                self.outgoing.put(data)
                return len(data)

        port = Port()
        decoder = p.Decoder()
        received = []

        def wait_for(kind, seq):
            end = time.monotonic() + 2
            while time.monotonic() < end:
                try:
                    received.extend(decoder.feed(port.outgoing.get(timeout=0.1)))
                except queue.Empty:
                    continue
                match = next((item for item in received if item.kind == kind and item.seq == seq), None)
                if match:
                    return match
            self.fail(f"Missing reply {kind:02x}/{seq}")

        with patch("simulator.app.serial.Serial", return_value=port):
            worker = SerialWorker("TEST", interval_ms)
            try:
                port.incoming.put(p.Packet(p.HELLO, 1, 99).encode())
                wait_for(p.ACK, 1)
                port.incoming.put(p.Packet(p.CALIBRATE, 2, 99).encode())
                frame_id = p.ACK_DATA.unpack(wait_for(p.ACK, 2).payload)[2]
                port.incoming.put(p.Packet(p.TEACH_BEGIN, 3, 99, struct.pack("<BI", mode, frame_id)).encode())
                teach_id = p.ACK_DATA.unpack(wait_for(p.ACK, 3).payload)[2]
                port.incoming.put(p.Packet(p.MOTION, 4, 99, struct.pack("<hhh", 500, 0, 0)).encode())
                time.sleep(0.25)
                port.incoming.put(p.Packet(p.TEACH_END, 5, 99, struct.pack("<I", teach_id)).encode())
                count = p.ACK_DATA.unpack(wait_for(p.ACK, 5).payload)[2]
                samples = [p.STATUS_DATA.unpack(item.payload) for item in received if item.kind == p.STATUS]
                samples = [item for item in samples if item[7] == mode and item[9] == teach_id]
                self.assertEqual(count, len(samples))
                self.assertGreater(samples[-1][2], samples[0][2])
                self.assertEqual(list(range(1, count + 1)), [item[10] for item in samples])
                self.assertGreaterEqual(count, 3)
                if mode == 3:
                    self.assertGreater(samples[-1][4], samples[0][4])
                    self.assertNotEqual(samples[-1][5:7], samples[0][5:7])
            finally:
                worker.close()
                worker.thread.join(timeout=1)
                self.assertFalse(worker.thread.is_alive())


if __name__ == "__main__":
    unittest.main()
