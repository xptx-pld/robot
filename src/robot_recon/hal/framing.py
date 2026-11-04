"""Binary framing with CRC-16/CCITT-FALSE.

Wire format (big endian length, little endian CRC):

    0xAA 0x55 LEN PAYLOAD[LEN] CRC_LO CRC_HI

The frame is transport-neutral. It intentionally does not define robot semantics;
those belong to a codec implementation.
"""

from __future__ import annotations

from dataclasses import dataclass

START = b"\xAA\x55"
MAX_PAYLOAD = 255  # one-byte length field


def crc16_ccitt_false(data: bytes) -> int:
    crc = 0xFFFF
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def encode_frame(payload: bytes) -> bytes:
    if len(payload) > MAX_PAYLOAD:
        raise ValueError(f"payload exceeds {MAX_PAYLOAD} bytes")
    body = bytes((len(payload),)) + payload
    crc = crc16_ccitt_false(body)
    return START + body + bytes((crc & 0xFF, crc >> 8))


@dataclass(frozen=True, slots=True)
class DecodeStats:
    bad_frames: int
    discarded_bytes: int


class FrameDecoder:
    """Incremental byte-stream decoder that resynchronises after corruption."""

    def __init__(self) -> None:
        self._buffer = bytearray()
        self.bad_frames = 0
        self.discarded_bytes = 0

    @property
    def stats(self) -> DecodeStats:
        return DecodeStats(self.bad_frames, self.discarded_bytes)

    def feed(self, data: bytes) -> list[bytes]:
        if not data:
            return []
        self._buffer.extend(data)
        payloads: list[bytes] = []
        while True:
            start = self._buffer.find(START)
            if start < 0:
                # Keep a trailing 0xAA because it may be the first byte of a split header.
                keep = 1 if self._buffer[-1:] == START[:1] else 0
                self.discarded_bytes += len(self._buffer) - keep
                if keep:
                    del self._buffer[:-1]
                else:
                    self._buffer.clear()
                break
            if start:
                self.discarded_bytes += start
                del self._buffer[:start]
            if len(self._buffer) < 3:
                break
            length = self._buffer[2]
            total = 3 + length + 2
            if len(self._buffer) < total:
                break
            body = bytes(self._buffer[2 : 3 + length])
            expected = crc16_ccitt_false(body)
            actual = self._buffer[3 + length] | (self._buffer[4 + length] << 8)
            if expected != actual:
                self.bad_frames += 1
                self.discarded_bytes += 1
                del self._buffer[0]
                continue
            payloads.append(bytes(self._buffer[3 : 3 + length]))
            del self._buffer[:total]
        return payloads

    def reset(self) -> None:
        self._buffer.clear()
