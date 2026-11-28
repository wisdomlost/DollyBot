"""Single-threaded virtual car, teaching, and trajectory playback."""

from bisect import bisect_right
from collections import OrderedDict
from dataclasses import dataclass, field, replace
import math
import struct
from typing import Optional

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


@dataclass(frozen=True)
class UploadedTrajectory:
    name: str
    kind: int
    incomplete: bool
    points: tuple[Pose, ...]
    repeats: int = 1


@dataclass
class TrajectoryTransfer:
    kind: int
    incomplete: bool
    name_length: int
    point_count: int
    repeats: int = 1
    data: bytearray = field(default_factory=bytearray)

    @property
    def size(self):
        return self.name_length + self.point_count * p.POSE_DATA.size


def yaw_delta(start: int, end: int) -> int:
    return (end - start + 18000) % 36000 - 18000


def waypoint_time_ms(start: Pose, end: Pose) -> int:
    distance = math.hypot(end.x - start.x, end.y - start.y)
    turn = abs(yaw_delta(start.yaw, end.yaw))
    return math.ceil(max(distance / 200, turn / 9000, 0.2) * 1000)


class TrajectoryPlayback:
    def __init__(self, trajectory: UploadedTrajectory, origin: Pose, now: float):
        first = trajectory.points[0]
        origin = replace(origin, t_ms=first.t_ms, frame_id=first.frame_id)
        at_start = replace(first, yaw=origin.yaw)
        self.points = (origin, at_start) + trajectory.points
        self.started_at = now
        self.return_ms = math.ceil(math.hypot(first.x-origin.x, first.y-origin.y)*10)
        align_ms = math.ceil(abs(yaw_delta(origin.yaw, first.yaw))/4.5)
        self.aligned_ms = self.return_ms + align_ms
        self.times = [0, self.return_ms, self.aligned_ms]
        for index in range(3, len(self.points)):
            start, end = self.points[index - 1], self.points[index]
            duration = (
                (end.t_ms - start.t_ms) & 0xFFFFFFFF
                if trajectory.kind == 1 else waypoint_time_ms(start, end)
            )
            self.times.append(self.times[-1] + duration)

    def segment_at(self, now: float) -> Optional[tuple[Pose, Pose, float]]:
        elapsed = (now - self.started_at) * 1000
        index = bisect_right(self.times, elapsed) - 1
        if index == len(self.times) - 1:
            return None
        fraction = (elapsed - self.times[index]) / (self.times[index + 1] - self.times[index])
        return self.points[index], self.points[index + 1], fraction


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
        self.trajectory_transfer = None
        self.uploaded_trajectory = None
        self.playback = None
        self.playback_state = None
        self.run_token = 0
        self.lease_deadline = 0.0
        self.loops = 0
        self.lease_seq = 0

    def advance(self, now: float):
        if now < self.now:
            raise ValueError("Simulation time must be monotonic")
        link_deadline = self.last_contact + 3.0
        if self.playback is not None:
            self._advance_playback(self.playback, min(now, link_deadline, self.lease_deadline))
            if self.playback and now >= self.lease_deadline:
                self._stop_playback()
                self.motion_timeout = True
        if self.mode == 3:
            # An external push is independent of motor commands and sampling frequency.
            until = min(now, link_deadline) if self.connected else now
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
        motion_end = min(now, self.last_motion + 0.5)
        remaining = max(0.0, motion_end - self.now)
        strafe, forward, rotation = self.motion
        while remaining > 1e-9 and (strafe or forward or rotation):
            dt = min(remaining, 0.02)
            theta = math.radians(self.yaw + rotation / 1000 * 90 * dt / 2)
            self.x += (strafe * math.cos(theta) + forward * math.sin(theta)) * 0.2 * dt
            self.y += (-strafe * math.sin(theta) + forward * math.cos(theta)) * 0.2 * dt
            self.yaw = (self.yaw + rotation / 1000 * 90 * dt + 180) % 360 - 180
            remaining -= dt
        self.now = now
        if now - self.last_motion >= 0.5 and any(self.motion):
            self.motion = (0, 0, 0)
            self.motion_timeout = True
        if self.connected and now >= link_deadline:
            self.disconnect()

    def disconnect(self):
        self.interrupted = self.interrupted or self.mode != 0
        self.mode = 0
        self.motion = (0, 0, 0)
        self.connected = False
        self.trajectory_transfer = None
        self._stop_playback()

    def _stop_playback(self):
        if self.playback is not None:
            self.playback = None
            self.playback_state = "stopped"

    def _advance_playback(self, playback: TrajectoryPlayback, now: float):
        segment = playback.segment_at(now)
        if segment is None:
            end = playback.points[-1]
            self.x, self.y, self.yaw = end.x, end.y, end.yaw / 100
            self.gimbal_yaw, self.gimbal_pitch = end.gimbal_yaw, end.gimbal_pitch
            self.loops -= 1
            self.playback = TrajectoryPlayback(self.uploaded_trajectory, self.pose(), now) if self.loops else None
            self.playback_state = "playing" if self.loops else "completed"
            return

        start, end, fraction = segment
        self.x = start.x + (end.x - start.x) * fraction
        self.y = start.y + (end.y - start.y) * fraction
        yaw = start.yaw + yaw_delta(start.yaw, end.yaw) * fraction
        self.yaw = ((yaw + 18000) % 36000 - 18000) / 100
        self.gimbal_yaw = round(start.gimbal_yaw + (end.gimbal_yaw - start.gimbal_yaw) * fraction)
        self.gimbal_pitch = round(start.gimbal_pitch + (end.gimbal_pitch - start.gimbal_pitch) * fraction)

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
        phase = 4 if self.playback_state == 'completed' else 0
        if self.playback:
            elapsed = (self.now-self.playback.started_at)*1000
            phase = 1 if elapsed < self.playback.return_ms else 2 if elapsed < self.playback.aligned_ms else 3
        flags |= phase << 5
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
        if request.kind not in (p.MOTION, p.GIMBAL, p.LEASE):
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
        if kind == p.CAPABILITIES:
            return [self._ack(request, value=1 | (2 | self.uploaded_trajectory.repeats << 8 if self.uploaded_trajectory else 0))]
        if kind == p.START:
            frame, loops, confirm = struct.unpack("<IBB", data)
            if not self.calibrated or not self.uploaded_trajectory or self.mode or self.playback or self.trajectory_transfer or any(self.motion):
                return [self._ack(request, p.BAD_STATE)]
            if not 1 <= loops <= 20 or confirm > 1:
                return [self._ack(request, p.BAD_ARGUMENT)]
            if frame != self.frame_id or (self.uploaded_trajectory.points[0].frame_id != frame and not confirm):
                return [self._ack(request, p.FRAME_MISMATCH)]
            self.loops = loops
            self.playback = TrajectoryPlayback(self.uploaded_trajectory, self.pose(), self.now)
            self.playback_state = "playing"
            self.run_token = request.seq << 16 | 1
            self.lease_seq = request.seq
            self.lease_deadline = self.now + .5
            self.motion_timeout = False
            return [self._ack(request, value=self.run_token)]
        if kind == p.LEASE:
            token, = struct.unpack("<I", data)
            if self.playback and token == self.run_token and 0 < (request.seq-self.lease_seq) % 65536 < 32768:
                self.lease_deadline = self.now + .5
                self.lease_seq = request.seq
            return []
        if kind == p.MOTION:
            motion = struct.unpack("<hhh", data)
            if any(abs(value) > 1000 for value in motion):
                raise ValueError("Motion out of range")
            if self.mode == 3:
                return [self._ack(request, p.BAD_STATE)]
            if self.playback:
                return [self._ack(request, p.BAD_STATE)] if any(motion) else []
            self.motion = motion
            self.last_motion = self.now
            self.last_motion_seq = request.seq
            self.motion_timeout = False
            return []
        if kind == p.GIMBAL:
            yaw, pitch = struct.unpack("<hh", data)
            if abs(yaw) > 18000 or abs(pitch) > 18000:
                raise ValueError("Gimbal out of range")
            if self.playback:
                return [self._ack(request, p.BAD_STATE)]
            self.gimbal_yaw, self.gimbal_pitch = yaw, pitch
            if self.mode == 3:
                self.push_gimbal_manual = True
            return []
        if kind == p.STOP:
            self.motion = (0, 0, 0)
            self.motion_timeout = False
            self._stop_playback()
            self.trajectory_transfer = None
            return [self._ack(request)]
        if kind == p.CALIBRATE:
            if self.mode or self.playback is not None or any(self.motion):
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
            if self.mode or self.playback is not None:
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
        if kind == p.TRAJECTORY_BEGIN:
            trajectory_kind, flags, name_length, point_count = p.TRAJECTORY_HEADER.unpack(data[:10])
            repeats = data[10] if len(data)==11 else 1
            if len(data) not in (10,11) or not 1 <= repeats <= 20:
                raise ValueError('Invalid playback settings')
            transfer = TrajectoryTransfer(trajectory_kind, bool(flags & 1), name_length, point_count, repeats)
            if self.playback or self.mode or any(self.motion):
                return [self._ack(request, p.BAD_STATE)]
            if trajectory_kind not in (1, 2) or flags or not 1 <= name_length <= 512 or not 1 <= point_count <= 4096:
                raise ValueError("Invalid trajectory header")
            self.trajectory_transfer = transfer
            return [self._ack(request)]
        if kind in (p.TRAJECTORY_DATA, p.TRAJECTORY_COMMIT):
            return [self._receive_trajectory(request)]
        return [self._ack(request, p.UNSUPPORTED)]

    def _receive_trajectory(self, request: p.Packet) -> p.Packet:
        transfer = self.trajectory_transfer
        if transfer is None:
            return self._ack(request, p.BAD_STATE)
        data = request.payload
        if request.kind == p.TRAJECTORY_DATA:
            offset, = p.TRAJECTORY_OFFSET.unpack_from(data)
            chunk = data[p.TRAJECTORY_OFFSET.size:]
            if not chunk or offset != len(transfer.data) or offset + len(chunk) > transfer.size:
                raise ValueError("Invalid trajectory chunk")
            transfer.data.extend(chunk)
            return self._ack(request)

        if data or len(transfer.data) != transfer.size:
            raise ValueError("Incomplete trajectory")
        if self.mode:
            return self._ack(request, p.BAD_STATE)
        name = transfer.data[:transfer.name_length].decode("utf-8")
        if not name.strip():
            raise ValueError("Empty trajectory name")
        pose_bytes = memoryview(transfer.data)[transfer.name_length:]
        points = tuple(Pose(*values) for values in p.POSE_DATA.iter_unpack(pose_bytes))
        if any(not q.frame_id or q.frame_id != points[0].frame_id or abs(q.x)>100000 or abs(q.y)>100000
               or any(abs(v)>18000 for v in (q.yaw,q.gimbal_yaw,q.gimbal_pitch)) for q in points):
            raise ValueError("Invalid pose")
        if transfer.kind == 1 and any(not 0 < (b.t_ms-a.t_ms) % 2**32 <= 600000 for a,b in zip(points,points[1:])):
            raise ValueError("Invalid timing")
        uploaded = UploadedTrajectory(name, transfer.kind, transfer.incomplete, points, transfer.repeats)
        self.playback = None
        self.playback_state = "saved"
        self.motion = (0, 0, 0)
        self.motion_timeout = False
        self.uploaded_trajectory = uploaded
        self.trajectory_transfer = None
        return self._ack(request, value=len(points))
