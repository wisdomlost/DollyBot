"""Single-threaded virtual car and teaching state machine."""

from collections import OrderedDict
from dataclasses import dataclass
import math
import struct

from . import protocol as p


@dataclass(frozen=True)
class Pose:
    t_ms: int
    frame_id: int
    x: int
    y: int
    yaw: int
    gimbal_yaw: int
    gimbal_pitch: int


class StatusSchedule:
    """Monotonic periodic deadlines; a delayed tick skips missed samples."""

    def __init__(self, interval_ms=50):
        if not isinstance(interval_ms, int) or not 20 <= interval_ms <= 1000:
            raise ValueError("上报周期必须为 20–1000 ms 的整数")
        self.interval = interval_ms / 1000.0
        self.next_due = 0.0

    def reset(self, now):
        self.next_due = now + self.interval

    def due(self, now):
        if now < self.next_due:
            return False
        skipped = math.floor((now - self.next_due) / self.interval) + 1
        self.next_due += skipped * self.interval
        if self.next_due <= now:  # Float rounding must not cause a second sample at the same tick.
            self.next_due = now + self.interval
        return True


class Simulator:
    def __init__(self):
        self.session = 0
        self.started_at = 0.0
        self.now = 0.0
        self.last_contact = 0.0
        self.last_motion = 0.0
        self.motion = (0, 0, 0)
        self.last_motion_seq = 0
        self.motion_timeout = False
        self.connected = False
        self.calibrated = False
        self.frame_id = 0
        self.x = self.y = self.yaw = 0.0
        self.gimbal_yaw = self.gimbal_pitch = 0
        self.mode = 0
        self.teach_id = 0
        self.index = 0
        self.histories = {1: [], 2: [], 3: []}
        self.push_started_at = 0.0
        self.push_origin = (0.0, 0.0, 0.0, 0, 0)
        self.push_gimbal_manual = False
        self.interrupted = False
        self.status_seq = 0
        self.cache = OrderedDict()

    def advance(self, now: float):
        if now < self.now:
            raise ValueError("Simulation time must be monotonic")
        if self.mode == 3:
            # An external push is independent of motor commands and sampling frequency.
            until = min(now, self.last_contact + 3.0) if self.connected else now
            elapsed = max(0.0, until - self.push_started_at)
            x, y, yaw, gimbal_yaw, gimbal_pitch = self.push_origin
            theta, origin_yaw = math.radians(elapsed * 20), math.radians(yaw)
            local_x, local_y = 500 * (1 - math.cos(theta)), 500 * math.sin(theta)
            self.x = x + local_x * math.cos(origin_yaw) + local_y * math.sin(origin_yaw)
            self.y = y - local_x * math.sin(origin_yaw) + local_y * math.cos(origin_yaw)
            self.yaw = (yaw + elapsed * 20 + 180) % 360 - 180
            if not self.push_gimbal_manual:
                wave = math.sin(2 * math.pi * elapsed / 10)
                self.gimbal_yaw = round(max(-18000, min(18000, gimbal_yaw + 3000 * wave)))
                self.gimbal_pitch = round(max(-18000, min(18000, gimbal_pitch + 1500 * wave)))
        motion_end = min(now, self.last_motion + 0.3)
        remaining = max(0.0, motion_end - self.now)
        strafe, forward, rotation = self.motion
        while remaining > 1e-9:
            dt = min(remaining, 0.02)
            theta = math.radians(self.yaw + rotation / 1000 * 90 * dt / 2)
            self.x += (strafe * math.cos(theta) + forward * math.sin(theta)) * 0.2 * dt
            self.y += (-strafe * math.sin(theta) + forward * math.cos(theta)) * 0.2 * dt
            self.yaw = (self.yaw + rotation / 1000 * 90 * dt + 180) % 360 - 180
            remaining -= dt
        self.now = now
        if now - self.last_motion >= 0.3 and any(self.motion):
            self.motion = (0, 0, 0)
            self.motion_timeout = True
        if self.connected and now - self.last_contact >= 3.0:
            self.disconnect()

    def disconnect(self):
        self.interrupted = self.interrupted or self.mode != 0
        self.mode = 0
        self.motion = (0, 0, 0)
        self.connected = False

    def pose(self) -> Pose:
        return Pose(
            int(max(0, self.now - self.started_at) * 1000) & 0xFFFFFFFF,
            self.frame_id,
            round(self.x), round(self.y), round(self.yaw * 100),
            self.gimbal_yaw, self.gimbal_pitch
        )

    def status(self, sample=True) -> p.Packet:
        pose = self.pose()
        if self.mode in (1, 3) and sample:
            self.index += 1
            self.histories[self.mode].append(pose)
        self.status_seq = (self.status_seq + 1) & 0xFFFF
        flags = int(self.calibrated) | (int(self.motion_timeout) << 1)
        data = p.STATUS_DATA.pack(
            pose.t_ms, pose.frame_id, pose.x, pose.y, pose.yaw,
            pose.gimbal_yaw, pose.gimbal_pitch,
            self.mode, flags, self.teach_id, self.index, self.last_motion_seq
        )
        return p.Packet(p.STATUS, self.status_seq, self.session, data)

    def _ack(self, request: p.Packet, result=p.OK, value=0) -> p.Packet:
        return p.Packet(p.ACK, request.seq, request.session, p.ACK_DATA.pack(request.kind, result, value))

    def handle(self, request: p.Packet, now: float) -> list[p.Packet]:
        self.advance(now)
        if request.kind == p.HELLO:
            if request.version != p.VERSION:
                return [self._ack(request, p.UNSUPPORTED)]
            if request.payload or not request.session:
                return [self._ack(request, p.BAD_ARGUMENT)]
            if request.session != self.session:
                self.disconnect()
                self.session = request.session
                self.started_at = now
                self.cache.clear()
            self.connected = True
        elif request.session != self.session or not self.connected:
            return []
        self.last_contact = now
        if request.version != p.VERSION:
            return [self._ack(request, p.UNSUPPORTED)]

        key = (request.kind, request.seq)
        if key in self.cache:
            payload, result = self.cache[key]
            return result if payload == request.payload else [self._ack(request, p.BAD_ARGUMENT)]
        try:
            replies = self._execute(request)
        except (ValueError, struct.error):
            replies = [self._ack(request, p.BAD_ARGUMENT)]
        if request.kind not in (p.MOTION, p.GIMBAL):
            self.cache[key] = (request.payload, replies)
            if len(self.cache) > 256:
                self.cache.popitem(last=False)
        return replies

    def _execute(self, request: p.Packet) -> list[p.Packet]:
        kind, data = request.kind, request.payload
        if kind in (p.HELLO, p.PING, p.STOP, p.CALIBRATE) and data:
            raise ValueError("Expected empty payload")
        if kind == p.HELLO:
            return [self._ack(request), self.status(sample=False)]
        if kind == p.PING:
            return [self._ack(request)]
        if kind == p.MOTION:
            motion = struct.unpack("<hhh", data)
            if any(abs(value) > 1000 for value in motion):
                raise ValueError("Motion out of range")
            if self.mode == 3:
                return [self._ack(request, p.BAD_STATE)]
            self.motion = motion
            self.last_motion = self.now
            self.last_motion_seq = request.seq
            self.motion_timeout = False
            return []
        if kind == p.GIMBAL:
            yaw, pitch = struct.unpack("<hh", data)
            if abs(yaw) > 18000 or abs(pitch) > 18000:
                raise ValueError("Gimbal out of range")
            self.gimbal_yaw, self.gimbal_pitch = yaw, pitch
            if self.mode == 3:
                self.push_gimbal_manual = True
            return []
        if kind == p.STOP:
            self.motion = (0, 0, 0)
            self.motion_timeout = False
            return [self._ack(request)]
        if kind == p.CALIBRATE:
            if self.mode or any(self.motion):
                return [self._ack(request, p.BAD_STATE)]
            self.x = self.y = self.yaw = 0.0
            self.frame_id = (self.frame_id + 1) & 0xFFFFFFFF or 1
            self.calibrated = True
            self.histories = {1: [], 2: [], 3: []}
            return [self._ack(request, value=self.frame_id), self.status(sample=False)]
        if kind == p.TEACH_BEGIN:
            mode, frame_id = struct.unpack("<BI", data)
            if mode not in (1, 2, 3):
                raise ValueError("Unknown mode")
            if not self.calibrated:
                return [self._ack(request, p.NOT_CALIBRATED)]
            if frame_id != self.frame_id:
                return [self._ack(request, p.FRAME_MISMATCH)]
            if self.mode:
                return [self._ack(request, p.BAD_STATE)]
            self.mode = mode
            self.teach_id = (self.teach_id + 1) & 0xFFFFFFFF or 1
            self.index = 0
            self.interrupted = False
            self.histories[mode] = []
            if mode == 3:
                self.motion = (0, 0, 0)
                self.motion_timeout = False
                self.push_started_at = self.now
                self.push_origin = (self.x, self.y, self.yaw, self.gimbal_yaw, self.gimbal_pitch)
                self.push_gimbal_manual = False
            return [self._ack(request, value=self.teach_id), self.status()]
        if kind in (p.TEACH_END, p.TAKE_POINT):
            teach_id, = struct.unpack("<I", data)
            if not self.mode or teach_id != self.teach_id:
                return [self._ack(request, p.BAD_STATE)]
            if kind == p.TEACH_END:
                replies = [self.status()] if self.mode in (1, 3) else []
                self.mode = 0
                return replies + [self._ack(request, value=self.index)]
            if self.mode != 2:
                return [self._ack(request, p.BAD_STATE)]
            self.index += 1
            pose = self.pose()
            self.histories[2].append(pose)
            payload = p.POINT_DATA.pack(
                self.teach_id, self.index, pose.t_ms, pose.frame_id, pose.x, pose.y,
                pose.yaw, pose.gimbal_yaw, pose.gimbal_pitch
            )
            return [p.Packet(p.POINT, request.seq, self.session, payload)]
        return [self._ack(request, p.UNSUPPORTED)]
