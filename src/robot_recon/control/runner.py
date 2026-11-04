"""Deterministic mission runner shared by CLI and tests."""

from __future__ import annotations

from dataclasses import dataclass
import math

from ..domain import Pose, RobotLimits, SafetyViolation, Telemetry
from ..hal.base import RobotDevice
from .path import Waypoint, WaypointController
from .supervisor import SafetySupervisor


@dataclass(frozen=True, slots=True)
class RunResult:
    completed: bool
    reason: str
    steps: int
    elapsed_s: float
    final_pose: Pose | None
    visited: int


@dataclass(slots=True)
class MissionRunner:
    device: RobotDevice
    limits: RobotLimits
    controller: WaypointController

    def run(
        self,
        waypoints: list[Waypoint],
        duration_s: float,
        max_steps: int | None = None,
    ) -> RunResult:
        if not waypoints:
            raise ValueError("at least one waypoint is required")
        if not math.isfinite(duration_s) or duration_s <= 0:
            raise ValueError("duration_s must be positive and finite")
        step_s = self.limits.control_period_s
        duration_limited = max_steps is None
        max_steps = max_steps if max_steps is not None else int(math.ceil(duration_s / step_s))
        if max_steps <= 0:
            raise ValueError("max_steps must be positive")
        supervisor = SafetySupervisor(self.device, self.limits)
        index = 0
        steps = 0
        last_telemetry: Telemetry | None = None
        try:
            supervisor.connect()
            for steps in range(1, max_steps + 1):
                step_index = steps - 1
                if steps * step_s > duration_s + 1e-12:
                    return RunResult(False, "duration exceeded", steps - 1, (steps - 1) * step_s, last_telemetry.pose if last_telemetry else None, index)
                now_s = step_index * step_s
                telemetry = supervisor.tick(now_s=now_s)
                last_telemetry = telemetry
                if telemetry.fault:
                    return RunResult(False, f"fault: {telemetry.fault}", steps, steps * step_s, telemetry.pose, index)
                if telemetry.emergency_stop:
                    return RunResult(False, "emergency stop", steps, steps * step_s, telemetry.pose, index)
                velocity, reached = self.controller.compute(telemetry.pose, waypoints[index])
                if reached:
                    index += 1
                    if index == len(waypoints):
                        supervisor.device.stop()
                        return RunResult(True, "all waypoints reached", steps, steps * step_s, telemetry.pose, index)
                    velocity = self.controller.compute(telemetry.pose, waypoints[index])[0]
                supervisor.command(velocity, dt_s=step_s, now_s=now_s)
            reason = "duration exceeded" if duration_limited else "max steps exceeded"
            return RunResult(False, reason, steps, steps * step_s, last_telemetry.pose if last_telemetry else None, index)
        except SafetyViolation as exc:
            return RunResult(False, f"safety violation: {exc}", steps, steps * step_s, last_telemetry.pose if last_telemetry else None, index)
        finally:
            supervisor.close()
