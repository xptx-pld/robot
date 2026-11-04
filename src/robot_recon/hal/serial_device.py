"""Serial transport for :class:`RobotDevice`.

The transport owns framing, non-blocking telemetry draining, and the command
watchdog at the wire level. Robot-specific bytes come from a replaceable codec.
pyserial is optional and only imported when this adapter is instantiated.
"""

from __future__ import annotations

from dataclasses import dataclass
import time
from typing import Callable

from ..domain import Telemetry, Velocity
from .base import RobotDevice
from .framing import FrameDecoder, encode_frame
from .protocol import CodecError, PlaceholderJsonCodec, TelemetryCodec


@dataclass(slots=True)
class SerialHealth:
    frames_received: int = 0
    frames_sent: int = 0
    bad_frames: int = 0
    last_error: str | None = None
    last_telemetry_at_s: float | None = None


class SerialRobotDevice(RobotDevice):
    def __init__(
        self,
        port: str,
        baudrate: int = 115200,
        read_timeout_s: float = 0.05,
        codec: TelemetryCodec | None = None,
        serial_factory: Callable[..., object] | None = None,
        clock: Callable[[], float] = time.monotonic,
        telemetry_timeout_s: float = 0.5,
    ) -> None:
        self._port = port
        self._baudrate = baudrate
        self._read_timeout_s = read_timeout_s
        self._codec = codec or PlaceholderJsonCodec()
        self._serial_factory = serial_factory
        self._clock = clock
        self._telemetry_timeout_s = telemetry_timeout_s
        self._serial: object | None = None
        self._decoder = FrameDecoder()
        self._latest: Telemetry | None = None
        self._latest_is_fresh = False
        self._last_command_at_s: float | None = None
        self._sent_velocity = Velocity()
        self._connected = False
        self.health = SerialHealth()

    @property
    def connected(self) -> bool:
        return self._connected

    @property
    def latest_telemetry(self) -> Telemetry | None:
        return self._latest

    def connect(self) -> None:
        if self._connected:
            return
        factory = self._serial_factory
        if factory is None:
            try:
                import serial  # type: ignore[import-not-found]
            except ImportError as exc:
                raise RuntimeError('pyserial is required; install with: pip install -e ".[serial]"') from exc
            factory = serial.Serial
        try:
            self._serial = factory(
                port=self._port,
                baudrate=self._baudrate,
                timeout=self._read_timeout_s,
                write_timeout=0.2,
            )
            self._connected = True
            self._latest = None
            self._latest_is_fresh = False
            self._decoder.reset()
            self._send_raw(self._codec.encode_stop())
        except Exception:
            self._connected = False
            self._serial = None
            raise

    def close(self) -> None:
        if self._serial is not None:
            try:
                if self._connected:
                    self._send_raw(self._codec.encode_emergency_stop())
            except Exception as exc:
                self.health.last_error = str(exc)
            finally:
                try:
                    self._serial.close()  # type: ignore[attr-defined]
                finally:
                    self._serial = None
                    self._connected = False

    def _send_raw(self, payload: bytes) -> None:
        if self._serial is None:
            raise RuntimeError("serial device is not connected")
        frame = encode_frame(payload)
        try:
            self._serial.write(frame)  # type: ignore[attr-defined]
            self._serial.flush()  # type: ignore[attr-defined]
            self.health.frames_sent += 1
        except Exception as exc:
            self.health.last_error = str(exc)
            raise

    def send_velocity(self, velocity: Velocity) -> None:
        if not self._connected:
            raise RuntimeError("serial device is not connected")
        self._send_raw(self._codec.encode_velocity(velocity))
        self._sent_velocity = velocity
        self._last_command_at_s = self._clock()

    def stop(self) -> None:
        if not self._connected:
            return
        self._send_raw(self._codec.encode_stop())
        self._sent_velocity = Velocity()
        self._last_command_at_s = self._clock()

    def read_telemetry(self) -> Telemetry:
        if not self._connected:
            raise RuntimeError("serial device is not connected")
        serial_port = self._serial
        assert serial_port is not None
        try:
            waiting = int(getattr(serial_port, "in_waiting", 0))
            chunk = serial_port.read(max(waiting, 1))  # type: ignore[attr-defined]
        except Exception as exc:
            self.health.last_error = str(exc)
            raise
        if chunk:
            payloads = self._decoder.feed(chunk)
            for payload in payloads:
                try:
                    telemetry = self._codec.decode_telemetry(payload)
                except CodecError as exc:
                    self.health.last_error = str(exc)
                    continue
                self._latest = telemetry
                self._latest_is_fresh = True
                self.health.frames_received += 1
                self.health.last_telemetry_at_s = self._clock()
        self.health.bad_frames = self._decoder.stats.bad_frames
        if self._latest is None:
            raise TimeoutError("no telemetry received yet")
        if not self._latest_is_fresh:
            raise TimeoutError("no new telemetry frame")
        self._latest_is_fresh = False
        return self._latest

    def emergency_stop(self) -> None:
        self._sent_velocity = Velocity()
        self._last_command_at_s = None
        if self._connected:
            try:
                self._send_raw(self._codec.encode_emergency_stop())
            except Exception:
                # The caller is already handling a failure; preserve the original path.
                pass

    def clear_emergency_stop(self) -> None:
        if not self._connected:
            raise RuntimeError("serial device is not connected")
        self._send_raw(self._codec.encode_clear_emergency_stop())

    def enforce_watchdog(self, timeout_s: float) -> bool:
        """Send a stop if no velocity command was issued within ``timeout_s``.

        Returns True when this call emitted a stop.
        """
        if not self._connected or self._last_command_at_s is None:
            return False
        if self._sent_velocity.is_zero:
            return False
        if self._clock() - self._last_command_at_s > timeout_s:
            self.stop()
            return True
        return False
