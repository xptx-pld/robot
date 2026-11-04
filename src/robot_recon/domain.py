"""Domain values shared by the control and hardware layers."""

from __future__ import annotations

from dataclasses import dataclass, replace
import math


class ConfigurationError(ValueError):
    """Raised when a robot parameter is invalid."""


class SafetyViolation(RuntimeError):
    """Raised when a safety-critical command cannot be accepted."""


@dataclass(frozen=True, slots=True)
class Velocity:
    """Body-frame velocity: forward metres/second and CCW radians/second."""

    linear_mps: float = 0.0
    angular_rps: float = 0.0

    def __post_init__(self) -> None:
        if not math.isfinite(self.linear_mps) or not math.isfinite(self.angular_rps):
            raise SafetyViolation("velocity components must be finite")

    @property
    def is_zero(self) -> bool:
        return self.linear_mps == 0.0 and self.angular_rps == 0.0

    def clamped(self, max_linear: float, max_angular: float) -> "Velocity":
        return Velocity(
            max(-max_linear, min(max_linear, self.linear_mps)),
            max(-max_angular, min(max_angular, self.angular_rps)),
        )

    def __add__(self, other: "Velocity") -> "Velocity":
        return Velocity(self.linear_mps + other.linear_mps, self.angular_rps + other.angular_rps)

    def scaled(self, factor: float) -> "Velocity":
        return Velocity(self.linear_mps * factor, self.angular_rps * factor)


@dataclass(frozen=True, slots=True)
class WheelSpeed:
    """Signed wheel angular speeds in radians/second."""

    left_rps: float
    right_rps: float

    def __post_init__(self) -> None:
        if not math.isfinite(self.left_rps) or not math.isfinite(self.right_rps):
            raise SafetyViolation("wheel speeds must be finite")

    @property
    def is_zero(self) -> bool:
        return self.left_rps == 0.0 and self.right_rps == 0.0


@dataclass(frozen=True, slots=True)
class Pose:
    """Planar pose in metres and radians."""

    x_m: float = 0.0
    y_m: float = 0.0
    heading_rad: float = 0.0

    def __post_init__(self) -> None:
        if not all(math.isfinite(v) for v in (self.x_m, self.y_m, self.heading_rad)):
            raise ValueError("pose values must be finite")

    def translated(self, dx: float, dy: float, dh: float) -> "Pose":
        return Pose(self.x_m + dx, self.y_m + dy, self.heading_rad + dh)


@dataclass(frozen=True, slots=True)
class Telemetry:
    """One coherent sample returned by a hardware adapter."""

    timestamp_s: float
    pose: Pose
    wheel_speed: WheelSpeed
    emergency_stop: bool = False
    fault: str | None = None

    def __post_init__(self) -> None:
        if not math.isfinite(self.timestamp_s):
            raise ValueError("telemetry timestamp must be finite")


@dataclass(frozen=True, slots=True)
class RobotLimits:
    """Validated physical and safety limits."""

    wheel_base_m: float
    wheel_radius_m: float
    max_linear_mps: float
    max_angular_rps: float
    max_linear_accel_mps2: float
    max_angular_accel_rps2: float
    command_timeout_s: float
    waypoint_tolerance_m: float = 0.05
    control_period_s: float = 0.02

    def __post_init__(self) -> None:
        positive = {
            "wheel_base_m": self.wheel_base_m,
            "wheel_radius_m": self.wheel_radius_m,
            "max_linear_mps": self.max_linear_mps,
            "max_angular_rps": self.max_angular_rps,
            "max_linear_accel_mps2": self.max_linear_accel_mps2,
            "max_angular_accel_rps2": self.max_angular_accel_rps2,
            "command_timeout_s": self.command_timeout_s,
            "control_period_s": self.control_period_s,
        }
        invalid = [name for name, value in positive.items() if not math.isfinite(value) or value <= 0]
        if invalid:
            raise ConfigurationError(f"limits must be positive and finite: {', '.join(invalid)}")
        if not math.isfinite(self.waypoint_tolerance_m) or self.waypoint_tolerance_m < 0:
            raise ConfigurationError("waypoint_tolerance_m must be finite and non-negative")
        if self.control_period_s >= self.command_timeout_s:
            raise ConfigurationError("control period must be shorter than the command timeout")

    @property
    def max_wheel_rps(self) -> float:
        return (self.max_linear_mps + self.max_angular_rps * self.wheel_base_m / 2.0) / self.wheel_radius_m
