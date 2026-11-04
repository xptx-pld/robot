"""Differential-drive kinematics with exact planar integration."""

from __future__ import annotations

import math

from .domain import Pose, Velocity, WheelSpeed


class DifferentialDriveKinematics:
    def __init__(self, wheel_base_m: float, wheel_radius_m: float) -> None:
        if wheel_base_m <= 0 or wheel_radius_m <= 0:
            raise ValueError("wheel base and radius must be positive")
        self.wheel_base_m = wheel_base_m
        self.wheel_radius_m = wheel_radius_m

    def velocities_to_wheels(self, velocity: Velocity) -> WheelSpeed:
        half_track = self.wheel_base_m / 2.0
        left = (velocity.linear_mps - velocity.angular_rps * half_track) / self.wheel_radius_m
        right = (velocity.linear_mps + velocity.angular_rps * half_track) / self.wheel_radius_m
        return WheelSpeed(left, right)

    def wheels_to_velocities(self, wheels: WheelSpeed) -> Velocity:
        left_mps = wheels.left_rps * self.wheel_radius_m
        right_mps = wheels.right_rps * self.wheel_radius_m
        return Velocity(
            linear_mps=(left_mps + right_mps) / 2.0,
            angular_rps=(right_mps - left_mps) / self.wheel_base_m,
        )

    def integrate(self, pose: Pose, velocity: Velocity, dt_s: float) -> Pose:
        """Integrate a constant body velocity for ``dt_s`` seconds.

        The arc form avoids Euler drift when turning. The small-angle branch uses
        the exact limit, so straight-line motion is not subject to division by 0.
        """
        if not math.isfinite(dt_s) or dt_s <= 0:
            raise ValueError("dt_s must be positive and finite")
        v = velocity.linear_mps
        w = velocity.angular_rps
        heading = pose.heading_rad
        if abs(w) < 1e-9:
            dx = v * math.cos(heading) * dt_s
            dy = v * math.sin(heading) * dt_s
            dh = 0.0
        else:
            next_heading = heading + w * dt_s
            radius = v / w
            dx = radius * (math.sin(next_heading) - math.sin(heading))
            dy = radius * (-math.cos(next_heading) + math.cos(heading))
            dh = w * dt_s
        return Pose(pose.x_m + dx, pose.y_m + dy, wrap_angle(heading + dh))


def wrap_angle(angle_rad: float) -> float:
    return (angle_rad + math.pi) % (2.0 * math.pi) - math.pi


def distance(a: Pose, b: Pose) -> float:
    return math.hypot(b.x_m - a.x_m, b.y_m - a.y_m)
