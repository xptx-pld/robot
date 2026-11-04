"""Closed-loop waypoint controller for a differential-drive robot."""

from __future__ import annotations

from dataclasses import dataclass
import math

from ..domain import Pose, RobotLimits, Velocity
from ..kinematics import wrap_angle


@dataclass(frozen=True, slots=True)
class Waypoint:
    x_m: float
    y_m: float


@dataclass(slots=True)
class ControllerGains:
    linear_kp: float = 0.9
    heading_kp: float = 1.8
    max_heading_error_rad: float = math.radians(100.0)
    approach_slowdown_m: float = 0.25
    min_linear_mps: float = 0.03
    turn_in_place_threshold_rad: float = math.radians(20.0)


class WaypointController:
    def __init__(self, limits: RobotLimits, gains: ControllerGains | None = None) -> None:
        self.limits = limits
        self.gains = gains or ControllerGains()

    def compute(self, pose: Pose, target: Waypoint) -> tuple[Velocity, bool]:
        """Return (velocity command, reached)."""
        dx = target.x_m - pose.x_m
        dy = target.y_m - pose.y_m
        distance = math.hypot(dx, dy)
        if distance <= self.limits.waypoint_tolerance_m:
            return Velocity(), True
        desired_heading = math.atan2(dy, dx)
        heading_error = wrap_angle(desired_heading - pose.heading_rad)
        if abs(heading_error) > self.gains.turn_in_place_threshold_rad:
            angular = max(-self.limits.max_angular_rps, min(self.limits.max_angular_rps, self.gains.heading_kp * heading_error))
            return Velocity(0.0, angular), False
        if abs(heading_error) > self.gains.max_heading_error_rad:
            return Velocity(), False
        linear = self.gains.linear_kp * distance
        linear *= max(0.0, min(1.0, distance / self.gains.approach_slowdown_m))
        linear *= max(0.0, math.cos(heading_error))
        if 0.0 < linear < self.gains.min_linear_mps:
            linear = self.gains.min_linear_mps
        angular = max(-self.limits.max_angular_rps, min(self.limits.max_angular_rps, self.gains.heading_kp * heading_error))
        linear = max(-self.limits.max_linear_mps, min(self.limits.max_linear_mps, linear))
        return Velocity(linear, angular), False
