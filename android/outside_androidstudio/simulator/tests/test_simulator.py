from contextlib import contextmanager, redirect_stdout
import io
import struct
import unittest
from unittest.mock import patch
import queue
import time
import math

from simulator import protocol as p
from simulator.model import Pose, Simulator, StatusSchedule
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

    def start_replay(self):
        ack = self.send(p.START, struct.pack("<IBB", self.car.frame_id, 1, 1), self.car.now)[0]
        self.assertEqual(p.OK, p.ACK_DATA.unpack(ack.payload)[1])

    def advance_replay(self, when):
        while self.car.now + .2 < when:
            self.send(p.LEASE,struct.pack("<I",self.car.run_token),self.car.now+.2)
        self.car.advance(when)

    def stage_trajectory(self, points, kind=1):
        name = b"route"
        poses = b"".join(
            p.POSE_DATA.pack(point.t_ms, point.frame_id, point.x, point.y, point.yaw,
                             point.gimbal_yaw, point.gimbal_pitch)
            for point in points
        )
        self.send(p.TRAJECTORY_BEGIN, p.TRAJECTORY_HEADER.pack(kind, 0, len(name), len(points)),
                  now=self.car.now)
        self.send(p.TRAJECTORY_DATA, p.TRAJECTORY_OFFSET.pack(0) + name + poses, now=self.car.now)

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
        self.send(p.PING, now=0.2)
        self.assertAlmostEqual(20, self.car.x)
        self.assertAlmostEqual(40, self.car.y)
        self.car.advance(1.0)
        self.assertAlmostEqual(50, self.car.x)
        self.assertAlmostEqual(100, self.car.y)
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

    def test_trajectory_is_committed_only_after_all_bytes_and_retries_are_safe(self):
        name = "A" + "轨" * 86
        name_bytes = name.encode()
        pose = bytes.fromhex("ff ff ff ff 98 ba dc fe 88 ff ff ff 2c 01 00 00 28 23 b8 0b 24 fa")
        begin = bytes.fromhex("02 00 03 01 00 00 0c 00 00 00")
        self.assertEqual((p.TRAJECTORY_BEGIN, p.OK, 0), p.ACK_DATA.unpack(
            self.send(p.TRAJECTORY_BEGIN, begin)[0].payload
        ))
        self.send(p.TRAJECTORY_DATA, p.TRAJECTORY_OFFSET.pack(0) + name_bytes[:252])
        data = p.Packet(p.TRAJECTORY_DATA, 50, 123, p.TRAJECTORY_OFFSET.pack(252) + name_bytes[252:])
        first = self.car.handle(data, 0)
        self.assertEqual((p.TRAJECTORY_DATA, p.OK, 0), p.ACK_DATA.unpack(first[0].payload))
        self.assertEqual(first, self.car.handle(data, 0))
        self.send(p.TRAJECTORY_DATA, p.TRAJECTORY_OFFSET.pack(259) + pose * 11)
        self.assertEqual(p.BAD_ARGUMENT, p.ACK_DATA.unpack(self.send(p.TRAJECTORY_COMMIT)[0].payload)[1])
        self.assertIsNone(self.car.uploaded_trajectory)
        self.assertIsNone(self.car.playback)
        self.send(p.TRAJECTORY_DATA, p.TRAJECTORY_OFFSET.pack(501) + pose)
        commit = p.Packet(p.TRAJECTORY_COMMIT, 51, 123)
        reply = self.car.handle(commit, 0)
        self.assertEqual((p.TRAJECTORY_COMMIT, p.OK, 12), p.ACK_DATA.unpack(reply[0].payload))
        self.assertEqual(reply, self.car.handle(commit, 0))
        uploaded = self.car.uploaded_trajectory
        self.assertEqual((name, 2, False, 12), (uploaded.name, uploaded.kind, uploaded.incomplete, len(uploaded.points)))
        self.assertEqual(Pose(0xFFFFFFFF, 0xFEDCBA98, -120, 300, 9000, 3000, -1500), uploaded.points[0])
        self.assertEqual((0, 0, 0), self.car.motion)

    def test_trajectory_new_session_discards_partial_data_and_keeps_last_complete_upload(self):
        name = b"route"
        begin = p.TRAJECTORY_HEADER.pack(1, 0, len(name), 1)
        self.send(p.TRAJECTORY_BEGIN, begin)
        self.send(p.TRAJECTORY_DATA, p.TRAJECTORY_OFFSET.pack(0) + name + bytes(p.POSE_DATA.size))
        self.send(p.TRAJECTORY_COMMIT)
        completed = self.car.uploaded_trajectory
        self.send(p.TRAJECTORY_BEGIN, begin)
        reply = self.send(p.TRAJECTORY_DATA, p.TRAJECTORY_OFFSET.pack(1) + name)[0]
        self.assertEqual(p.BAD_ARGUMENT, p.ACK_DATA.unpack(reply.payload)[1])
        self.car.handle(p.Packet(p.HELLO, 1, 456), 0)
        reply = self.car.handle(p.Packet(p.TRAJECTORY_COMMIT, 2, 456), 0)[0]
        self.assertEqual(p.BAD_STATE, p.ACK_DATA.unpack(reply.payload)[1])
        self.assertIs(completed, self.car.uploaded_trajectory)

    def test_continuous_playback_reaches_first_pose_then_uses_recorded_time_and_shortest_turn(self):
        self.car.yaw = 170
        first = Pose(0xFFFFFF00, 54, 100, 0, 17000, 2000, -1000)
        last = Pose(744, 54, 300, 100, -17000, 6000, 1000)
        self.stage_trajectory((first, last))
        commit = p.Packet(p.TRAJECTORY_COMMIT, 50, 123)
        replies = self.car.handle(commit, 0)
        self.assertIsNone(self.car.playback)
        self.start_replay()
        self.assertEqual(replies, self.car.handle(commit, 0.25))
        self.assertEqual(Pose(250, self.car.frame_id, 25, 0, 17000, 500, -250), self.car.pose())
        self.advance_replay(1.0)
        self.assertEqual(Pose(1000, self.car.frame_id, 100, 0, 17000, 2000, -1000), self.car.pose())
        self.advance_replay(1.5)
        self.assertEqual(Pose(1500, self.car.frame_id, 200, 50, -18000, 4000, 0), self.car.pose())
        self.assertEqual("playing", self.car.playback_state)
        self.advance_replay(2.0)
        expected = Pose(2000, self.car.frame_id, 300, 100, -17000, 6000, 1000)
        self.assertEqual(expected, self.car.pose())
        self.assertEqual("completed", self.car.playback_state)
        self.car.advance(2.0)
        self.assertEqual((300, 100, -170.0, 6000, 1000),
                         (self.car.x, self.car.y, self.car.yaw, self.car.gimbal_yaw, self.car.gimbal_pitch))

    def test_waypoints_use_distance_and_turn_instead_of_recording_intervals(self):
        first = self.car.pose()
        middle = Pose(100, first.frame_id, 200, 0, 0, 6000, 1000)
        last = Pose(101, first.frame_id, 200, 0, 9000, 9000, 2000)
        self.stage_trajectory((first, middle, last), kind=2)
        self.send(p.TRAJECTORY_COMMIT)
        self.start_replay()
        self.advance_replay(0.5)
        self.assertEqual(Pose(500, first.frame_id, 100, 0, 0, 3000, 500), self.car.pose())
        self.advance_replay(1.5)
        self.assertEqual(Pose(1500, first.frame_id, 200, 0, 4500, 7500, 1500), self.car.pose())
        self.advance_replay(2)
        self.assertEqual(Pose(2000, first.frame_id, 200, 0, 9000, 9000, 2000), self.car.pose())
        self.assertEqual("completed", self.car.playback_state)

    def test_stop_discards_pending_transfer_and_freezes_playback(self):
        first = self.car.pose()
        points = (first, Pose(5000, first.frame_id, 1000, 0, 0, 0, 0))
        self.stage_trajectory(points)
        self.send(p.TRAJECTORY_COMMIT)
        self.start_replay()
        self.advance_replay(0.5)
        self.stage_trajectory(points)
        self.send(p.STOP, now=0.5)
        self.assertEqual("stopped", self.car.playback_state)
        self.assertEqual(p.BAD_STATE, p.ACK_DATA.unpack(self.send(p.TRAJECTORY_COMMIT, now=0.5)[0].payload)[1])
        self.car.advance(2)
        self.assertEqual(100, self.car.pose().x)

    def test_manual_commands_and_connection_loss_stop_playback(self):
        cases = (
            (p.MOTION, struct.pack("<hhh", 0, 200, 0), 123, 0.5, 100),
            (p.GIMBAL, struct.pack("<hh", 2500, -1500), 123, 0.5, 100),
            (p.HELLO, b"", 456, 0.5, 100),
            (p.PING, b"", 123, 3.5, 100),
        )
        for kind, payload, session, now, expected_x in cases:
            with self.subTest(kind=kind):
                self.setUp()
                first = self.car.pose()
                self.stage_trajectory((first, Pose(5000, first.frame_id, 1000, 0, 0, 0, 0)))
                self.send(p.TRAJECTORY_COMMIT)
                self.start_replay()
                self.car.handle(p.Packet(kind, 50, session, payload), now)
                self.assertEqual("stopped", self.car.playback_state)
                self.car.advance(now + 0.5)
                self.assertEqual(expected_x, self.car.pose().x)

    def test_recording_and_calibration_do_not_overlap_playback(self):
        first = self.car.pose()
        self.stage_trajectory((first, Pose(5000, first.frame_id, 1000, 0, 0, 0, 0)))
        begin_teaching = struct.pack("<BI", 1, first.frame_id)
        self.send(p.TEACH_BEGIN, begin_teaching)
        self.assertEqual(p.BAD_STATE, p.ACK_DATA.unpack(self.send(p.TRAJECTORY_COMMIT)[0].payload)[1])
        self.assertIsNone(self.car.playback)
        self.send(p.TEACH_END, struct.pack("<I", self.car.teach_id))
        self.send(p.TRAJECTORY_COMMIT)
        self.start_replay()
        self.assertEqual(p.BAD_STATE, p.ACK_DATA.unpack(self.send(p.CALIBRATE)[0].payload)[1])
        self.assertEqual(p.BAD_STATE, p.ACK_DATA.unpack(self.send(p.TEACH_BEGIN, begin_teaching)[0].payload)[1])

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

    @contextmanager
    def open_worker(self, interval_ms):
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

        def wait_for(kind, seq=None, accept=lambda item: True):
            end = time.monotonic() + 2
            while time.monotonic() < end:
                match = next((item for item in received if item.kind == kind and
                              (seq is None or item.seq == seq) and accept(item)), None)
                if match:
                    return match
                try:
                    received.extend(decoder.feed(port.outgoing.get(timeout=0.1)))
                except queue.Empty:
                    continue
            self.fail(f"Missing reply {kind:02x}/{seq}")

        terminal = io.StringIO()
        with patch("simulator.app.serial.Serial", return_value=port), redirect_stdout(terminal):
            worker = SerialWorker("TEST", interval_ms)
            try:
                yield port, wait_for, received, terminal
            finally:
                worker.close()
                worker.thread.join(timeout=1)
                self.assertFalse(worker.thread.is_alive())

    def record_over_byte_stream(self, mode, interval_ms):
        with self.open_worker(interval_ms) as (port, wait_for, received, _):
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

    def test_worker_logs_received_frames_and_reports_uploaded_final_pose(self):
        name = b"route"
        target = (40, 20, 9000, 3000, -1500)
        poses = p.POSE_DATA.pack(0, 1, 0, 0, 0, 0, 0) + p.POSE_DATA.pack(200, 1, *target)
        packets = (
            p.Packet(p.HELLO, 1, 99),
            p.Packet(p.TRAJECTORY_BEGIN, 2, 99, p.TRAJECTORY_HEADER.pack(1, 0, len(name), 2)),
            p.Packet(p.TRAJECTORY_DATA, 3, 99, p.TRAJECTORY_OFFSET.pack(0) + name),
            p.Packet(p.TRAJECTORY_DATA, 4, 99, p.TRAJECTORY_OFFSET.pack(len(name)) + poses),
            p.Packet(p.TRAJECTORY_COMMIT, 5, 99),
            p.Packet(p.CALIBRATE, 6, 99),
            p.Packet(p.START, 7, 99, struct.pack('<IBB',1,1,1)),
        )
        with self.open_worker(50) as (port, wait_for, _, terminal):
            for packet in packets:
                frame = packet.encode()
                port.incoming.put(frame[:7])
                port.incoming.put(frame[7:])
                self.assertEqual(p.OK, p.ACK_DATA.unpack(wait_for(p.ACK, packet.seq).payload)[1])
            ack = p.ACK_DATA.unpack(wait_for(p.ACK, 5).payload)
            self.assertEqual((p.TRAJECTORY_COMMIT, p.OK, 2), ack)
            wait_for(p.STATUS, accept=lambda item: p.STATUS_DATA.unpack(item.payload)[2:7] == target)
            log = terminal.getvalue().splitlines()
            self.assertEqual(len(packets), len(log))
            for packet, line in zip(packets, log):
                self.assertIn(f"type=0x{packet.kind:02X} seq={packet.seq} session=0x00000063", line)
                self.assertTrue(line.endswith(packet.encode().hex(" ").upper()))


if __name__ == "__main__":
    unittest.main()
