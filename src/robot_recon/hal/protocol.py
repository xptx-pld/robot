"""Placeholder JSON codec for the reconstructed serial link.

WARNING: this is not a recovered protocol. It is an explicit seam for hardware
bring-up. Replace this codec with the real robot protocol after G2 is answered.
"""

from __future__ import annotations

from typing import Protocol
import json

from ..domain import Pose, Telemetry, Velocity, WheelSpeed


class CodecError(ValueError):
    """Raised for malformed or unsupported protocol payloads."""


class TelemetryCodec(Protocol):
    def encode_velocity(self, velocity: Velocity) -> bytes: ...

    def encode_stop(self) -> bytes: ...

    def encode_emergency_stop(self) -> bytes: ...

    def encode_clear_emergency_stop(self) -> bytes: ...

    def decode_telemetry(self, payload: bytes) -> Telemetry: ...


class PlaceholderJsonCodec:
    """JSON-over-CRC-frame codec used only for bring-up and integration tests."""

    def _encode(self, obj: dict[str, object]) -> bytes:
        return json.dumps(obj, separators=(",", ":"), ensure_ascii=True).encode("ascii")

    def encode_velocity(self, velocity: Velocity) -> bytes:
        return self._encode({"t": "v", "l": velocity.linear_mps, "a": velocity.angular_rps})

    def encode_stop(self) -> bytes:
        return self._encode({"t": "s"})

    def encode_emergency_stop(self) -> bytes:
        return self._encode({"t": "e"})

    def encode_clear_emergency_stop(self) -> bytes:
        return self._encode({"t": "c"})

    def decode_telemetry(self, payload: bytes) -> Telemetry:
        try:
            value = json.loads(payload.decode("ascii"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise CodecError("telemetry payload is not valid ASCII JSON") from exc
        if not isinstance(value, dict) or value.get("t") != "t":
            raise CodecError("unsupported telemetry payload")
        try:
            pose = Pose(float(value["x"]), float(value["y"]), float(value["h"]))
            wheels = WheelSpeed(float(value["l"]), float(value["r"]))
            timestamp = float(value["ts"])
            estop = bool(value.get("e", False))
            fault_raw = value.get("f")
            fault = str(fault_raw) if fault_raw else None
        except (KeyError, TypeError, ValueError) as exc:
            raise CodecError("telemetry payload is missing required fields") from exc
        return Telemetry(timestamp_s=timestamp, pose=pose, wheel_speed=wheels, emergency_stop=estop, fault=fault)
