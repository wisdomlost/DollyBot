"""DollyBot V1 wire format. All integers and the CRC trailer are little endian."""

from dataclasses import dataclass
import binascii
import struct

MAGIC = b"\xa5\x5a"
VERSION = 1
MAX_PAYLOAD = 256
HELLO, PING = 0x01, 0x02
MOTION, GIMBAL, STOP = 0x10, 0x11, 0x12
CALIBRATE, TEACH_BEGIN, TEACH_END, TAKE_POINT = 0x20, 0x30, 0x31, 0x32
TRAJECTORY_BEGIN, TRAJECTORY_DATA, TRAJECTORY_COMMIT = 0x40, 0x41, 0x42
CAPABILITIES, START, LEASE = 0x43, 0x44, 0x45
ACK, STATUS, POINT = 0x80, 0x90, 0x91
OK, BAD_STATE, NOT_CALIBRATED, BAD_ARGUMENT, FRAME_MISMATCH, UNSUPPORTED = range(6)
HEADER = struct.Struct("<BBHHI")
ACK_DATA = struct.Struct("<BBI")
STATUS_DATA = struct.Struct("<IIiihhhBBIIH")
POINT_DATA = struct.Struct("<IIIIiihhh")
TRAJECTORY_HEADER = struct.Struct("<BBII")
TRAJECTORY_OFFSET = struct.Struct("<I")
POSE_DATA = struct.Struct("<IIiihhh")


@dataclass(frozen=True)
class Packet:
    kind: int
    seq: int
    session: int
    payload: bytes = b""
    version: int = VERSION

    def encode(self) -> bytes:
        if len(self.payload) > MAX_PAYLOAD:
            raise ValueError("Payload exceeds 256 bytes")
        body = HEADER.pack(self.version, self.kind, self.seq, len(self.payload), self.session)
        body += self.payload
        return MAGIC + body + struct.pack("<H", crc16(body))


def crc16(data: bytes) -> int:
    return binascii.crc_hqx(data, 0xFFFF)


class Decoder:
    def __init__(self):
        self.buffer = bytearray()
        self.errors = 0

    def feed(self, data: bytes) -> list[Packet]:
        self.buffer.extend(data)
        packets = []
        while True:
            start = self.buffer.find(MAGIC)
            if start < 0:
                self.buffer[:] = self.buffer[-1:] if self.buffer.endswith(MAGIC[:1]) else b""
                break
            if start:
                del self.buffer[:start]
            if len(self.buffer) < 12:
                break
            version, kind, seq, length, session = HEADER.unpack_from(self.buffer, 2)
            if length > MAX_PAYLOAD:
                self.errors += 1
                del self.buffer[0]
                continue
            total = 14 + length
            if len(self.buffer) < total:
                break
            expected = struct.unpack_from("<H", self.buffer, total - 2)[0]
            if crc16(bytes(self.buffer[2:total - 2])) != expected:
                self.errors += 1
                del self.buffer[0]
                continue
            packets.append(Packet(kind, seq, session, bytes(self.buffer[12:total - 2]), version))
            del self.buffer[:total]
        return packets
