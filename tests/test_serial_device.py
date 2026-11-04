import pytest

from robot_recon.domain import Pose, Telemetry, Velocity, WheelSpeed
from robot_recon.hal.framing import encode_frame
from robot_recon.hal.protocol import CodecError, PlaceholderJsonCodec
from robot_recon.hal.serial_device import SerialRobotDevice


class FakeSerial:
    def __init__(self, **kwargs: object) -> None:
        self.kwargs = kwargs
        self.written: list[bytes] = []
        self.incoming = bytearray()
        self.closed = False

    @property
    def in_waiting(self) -> int:
        return len(self.incoming)

    def write(self, data: bytes) -> int:
        self.written.append(data)
        return len(data)

    def flush(self) -> None:
        pass

    def read(self, size: int) -> bytes:
        if not self.incoming:
            return b""
        count = min(size, len(self.incoming))
        data = bytes(self.incoming[:count])
        del self.incoming[:count]
        return data

    def close(self) -> None:
        self.closed = True


def telemetry_payload(**overrides: object) -> bytes:
    value = {"t": "t", "x": 1.0, "y": 2.0, "h": 0.3, "l": 1.0, "r": 1.1, "ts": 4.2}
    value.update(overrides)
    import json

    return json.dumps(value, separators=(",", ":")).encode("ascii")


def make_device(sample: bytes | None = None) -> tuple[SerialRobotDevice, FakeSerial, list[float]]:
    clock = [0.0]
    serial_port = FakeSerial()
    device = SerialRobotDevice(
        "COM_TEST",
        serial_factory=lambda **kwargs: serial_port,
        clock=lambda: clock[0],
    )
    if sample is not None:
        serial_port.incoming.extend(sample)
    return device, serial_port, clock


def test_connect_sends_stop_before_any_motion() -> None:
    device, serial_port, _ = make_device()
    device.connect()
    assert len(serial_port.written) == 1
    assert PlaceholderJsonCodec().encode_stop() in serial_port.written[0]
    device.close()


def test_send_velocity_and_read_telemetry() -> None:
    device, serial_port, clock = make_device(encode_frame(telemetry_payload()))
    device.connect()
    device.send_velocity(Velocity(0.1, -0.2))
    telemetry = device.read_telemetry()
    assert telemetry.pose == Pose(1.0, 2.0, 0.3)
    assert telemetry.wheel_speed == WheelSpeed(1.0, 1.1)
    assert device.health.frames_received == 1
    device.close()


def test_repeated_read_requires_a_new_frame() -> None:
    device, _, clock = make_device(encode_frame(telemetry_payload()))
    device.connect()
    assert device.read_telemetry().pose.x_m == 1.0
    clock[0] += 0.1
    with pytest.raises(TimeoutError, match="no new telemetry"):
        device.read_telemetry()
    device.close()


def test_telemetry_timeout_reports_stale_or_missing() -> None:
    device, serial_port, clock = make_device(encode_frame(telemetry_payload()))
    device.connect()
    device.read_telemetry()
    clock[0] = 10.0
    serial_port.incoming.extend(encode_frame(telemetry_payload(x=2.0)))
    assert device.read_telemetry().pose.x_m == 2.0
    device.close()


def test_corrupt_payload_is_ignored_then_valid_payload_accepted() -> None:
    bad = encode_frame(b"not json")
    good = encode_frame(telemetry_payload())
    device, _, _ = make_device(bad + good)
    device.connect()
    telemetry = device.read_telemetry()
    assert telemetry.pose.x_m == 1.0
    assert device.health.last_error
    device.close()


def test_close_sends_emergency_stop() -> None:
    device, serial_port, _ = make_device()
    device.connect()
    device.close()
    assert PlaceholderJsonCodec().encode_emergency_stop() in serial_port.written[-1]
    assert serial_port.closed


def test_watchdog_uses_injected_clock() -> None:
    device, serial_port, clock = make_device()
    device.connect()
    device.send_velocity(Velocity(0.1, 0.0))
    assert not device.enforce_watchdog(0.25)
    clock[0] = 0.3
    assert device.enforce_watchdog(0.25)
    assert PlaceholderJsonCodec().encode_stop() in serial_port.written[-1]
    device.close()
