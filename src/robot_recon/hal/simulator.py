"""Deterministic simulated differential-drive robot.

The simulator is deliberately free of wall-clock dependencies. Each telemetry read
advances physics by a fixed step, making mission and safety tests reproducible.
"""

from __future__ import annotations

import math

from ..domain import Pose, Telemetry, Velocity
from ..kinematics import DifferentialDriveKinematics
from .base import RobotDevice


class SimulatedRobot(RobotDevice):
    def __init__(
        self,
        wheel_base_m: float = 0.24,
        wheel_radius_m: float = 0.065,
        step_s: float = 0.02,
        initial_pose: Pose | None = None,
        initial_emergency_stop: bool = False,
        max_linear_mps: float | None = None,
        max_angular_rps: float | None = None,
    ) -> None:
        if step_s <= 0:
            raise ValueError("step_s must be positive")
        self._kinematics = DifferentialDriveKinematics(wheel_base_m, wheel_radius_m)
        self._step_s = step_s
        self._pose = initial_pose or Pose()
        self._velocity = Velocity()
        self._emergency_stop = initial_emergency_stop
        self._connected = False
        self._sim_time_s = 0.0
        self._fault: str | None = None
        self._max_linear = max_linear_mps
        self._max_angular = max_angular_rps

    @property
    def connected(self) -> bool:
        return self._connected

    @property
    def pose(self) -> Pose:
        return self._pose

    @property
    def velocity(self) -> Velocity:
        return self._velocity

    def connect(self) -> None:
        self._connected = True

    def close(self) -> None:
        self._emergency_stop = True
        self._velocity = Velocity()
        self._connected = False

    def send_velocity(self, velocity: Velocity) -> None:
        if not self._connected:
            raise RuntimeError("simulator is not connected")
        if self._emergency_stop:
            raise RuntimeError("emergency stop is latched")
        if self._fault:
            raise RuntimeError(f"simulator fault: {self._fault}")
        if self._max_linear is not None and abs(velocity.linear_mps) > self._max_linear + 1e-12:
            raise ValueError("linear velocity exceeds simulator limit")
        if self._max_angular is not None and abs(velocity.angular_rps) > self._max_angular + 1e-12:
            raise ValueError("angular velocity exceeds simulator limit")
        self._velocity = velocity

    def stop(self) -> None:
        self._velocity = Velocity()

    def read_telemetry(self) -> Telemetry:
        if not self._connected:
            raise RuntimeError("simulator is not connected")
        if not self._emergency_stop and self._fault is None:
            self._pose = self._kinematics.integrate(self._pose, self._velocity, self._step_s)
        self._sim_time_s += self._step_s
        wheels = self._kinematics.velocities_to_wheels(self._velocity)
        return Telemetry(
            timestamp_s=self._sim_time_s,
            pose=self._pose,
            wheel_speed=wheels,
            emergency_stop=self._emergency_stop,
            fault=self._fault,
        )

    def emergency_stop(self) -> None:
        self._velocity = Velocity()
        self._emergency_stop = True

    def clear_emergency_stop(self) -> None:
        if self._fault is not None:
            raise RuntimeError("cannot clear emergency stop while a fault is latched")
        self._emergency_stop = False

    def inject_fault(self, message: str) -> None:
        if not message:
            raise ValueError("fault message must not be empty")
        self._fault = message
        self._velocity = Velocity()

    def advance(self, seconds: float) -> None:
        """Optional test helper for advancing physics without reading telemetry."""
        if not math.isfinite(seconds) or seconds <= 0:
            raise ValueError("seconds must be positive and finite")
        steps = int(round(seconds / self._step_s))
        for _ in range(steps):
            self.read_telemetry()
