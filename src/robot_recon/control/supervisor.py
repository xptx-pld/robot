"""Safety supervisor between mission logic and hardware.

The supervisor is the only component allowed to talk to a device. It enforces
finite limits, acceleration limits, a command watchdog, and a latched emergency
stop that cannot be cleared implicitly.
"""

from __future__ import annotations

from dataclasses import dataclass
import math
import time

from ..domain import RobotLimits, SafetyViolation, Telemetry, Velocity
from ..hal.base import RobotDevice


@dataclass(slots=True)
class SupervisorState:
    emergency_stop: bool = False
    last_command: Velocity = Velocity()
    last_update_s: float = 0.0
    rejection_reason: str | None = None


class SafetySupervisor:
    def __init__(
        self,
        device: RobotDevice,
        limits: RobotLimits,
        initial_time_s: float = 0.0,
    ) -> None:
        self.device = device
        self.limits = limits
        self.state = SupervisorState(last_update_s=initial_time_s)
        self._last_command_at_s = initial_time_s
        self._has_command = False

    @property
    def emergency_stop(self) -> bool:
        return self.state.emergency_stop

    def connect(self) -> None:
        self.device.connect()

    def close(self) -> None:
        try:
            self.device.stop()
        finally:
            self.device.close()

    def emergency_stop(self) -> None:
        self.device.emergency_stop()
        self.state.emergency_stop = True
        self.state.last_command = Velocity()
        self._has_command = False

    def clear_emergency_stop(self) -> None:
        """Explicitly clear a latched stop after the operator confirms safety."""
        self.device.clear_emergency_stop()
        self.state.emergency_stop = False
        self.state.last_command = Velocity()
        self._has_command = False

    def command(
        self,
        requested: Velocity,
        dt_s: float | None = None,
        now_s: float | None = None,
    ) -> Velocity:
        if self.state.emergency_stop:
            raise SafetyViolation("emergency stop is latched")
        now = time.monotonic() if now_s is None else now_s
        if not math.isfinite(now) or now < 0:
            raise SafetyViolation("clock must be finite and non-negative")
        dt = self.limits.control_period_s if dt_s is None else dt_s
        if not math.isfinite(dt) or dt <= 0:
            raise SafetyViolation("control period must be positive and finite")
        limited = requested.clamped(self.limits.max_linear_mps, self.limits.max_angular_rps)
        previous = self.state.last_command
        max_dl = self.limits.max_linear_accel_mps2 * dt
        max_da = self.limits.max_angular_accel_rps2 * dt
        dl = max(-max_dl, min(max_dl, limited.linear_mps - previous.linear_mps))
        da = max(-max_da, min(max_da, limited.angular_rps - previous.angular_rps))
        safe = Velocity(previous.linear_mps + dl, previous.angular_rps + da)
        try:
            self.device.send_velocity(safe)
        except Exception as exc:
            self.state.rejection_reason = str(exc)
            raise
        self.state.last_command = safe
        self.state.last_update_s = now
        self._last_command_at_s = now
        self._has_command = True
        return safe

    def tick(self, now_s: float | None = None) -> Telemetry:
        now = time.monotonic() if now_s is None else now_s
        if not math.isfinite(now) or now < 0:
            raise SafetyViolation("clock must be finite and non-negative")
        if self._has_command and now - self._last_command_at_s > self.limits.command_timeout_s:
            self.device.stop()
            self.state.last_command = Velocity()
            self._has_command = False
        telemetry = self.device.read_telemetry()
        if telemetry.emergency_stop and not self.state.emergency_stop:
            self.state.emergency_stop = True
            self.state.last_command = Velocity()
            self._has_command = False
        if telemetry.fault:
            self.state.rejection_reason = telemetry.fault
            if not self.state.emergency_stop:
                self.emergency_stop()
        self.state.last_update_s = now
        return telemetry
