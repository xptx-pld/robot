import math

import pytest

from robot_recon.control.supervisor import SafetySupervisor
from robot_recon.domain import RobotLimits, SafetyViolation, Velocity
from robot_recon.hal.simulator import SimulatedRobot


def limits(**overrides: float) -> RobotLimits:
    values = dict(
        wheel_base_m=0.24,
        wheel_radius_m=0.065,
        max_linear_mps=0.2,
        max_angular_rps=1.0,
        max_linear_accel_mps2=1.0,
        max_angular_accel_rps2=2.0,
        command_timeout_s=0.3,
        waypoint_tolerance_m=0.05,
        control_period_s=0.02,
    )
    values.update(overrides)
    return RobotLimits(**values)


def make_supervisor() -> tuple[SimulatedRobot, SafetySupervisor]:
    robot = SimulatedRobot(step_s=0.02)
    supervisor = SafetySupervisor(robot, limits())
    supervisor.connect()
    return robot, supervisor


def test_linear_and_angular_limits_are_enforced() -> None:
    robot, supervisor = make_supervisor()
    sent = supervisor.command(Velocity(99.0, -99.0), dt_s=10.0, now_s=0.0)
    assert sent.linear_mps == pytest.approx(0.2)
    assert sent.angular_rps == pytest.approx(-1.0)
    assert abs(robot.velocity.linear_mps) <= 0.2
    supervisor.close()


def test_acceleration_limit_is_enforced() -> None:
    _, supervisor = make_supervisor()
    sent = supervisor.command(Velocity(0.2, 0.0), dt_s=0.05, now_s=0.0)
    assert sent.linear_mps == pytest.approx(0.05)
    supervisor.close()


def test_watchdog_stops_after_timeout() -> None:
    robot, supervisor = make_supervisor()
    supervisor.command(Velocity(0.1, 0.0), dt_s=1.0, now_s=0.0)
    assert robot.velocity.linear_mps == pytest.approx(0.1)
    supervisor.tick(now_s=0.1)
    assert robot.velocity.linear_mps > 0
    supervisor.tick(now_s=0.31)
    assert robot.velocity.is_zero
    supervisor.close()


def test_emergency_stop_is_latched() -> None:
    robot, supervisor = make_supervisor()
    supervisor.command(Velocity(0.1, 0.0), dt_s=1.0, now_s=0.0)
    supervisor.emergency_stop()
    assert robot.velocity.is_zero
    with pytest.raises(SafetyViolation):
        supervisor.command(Velocity(0.1, 0.0), now_s=0.1)
    supervisor.clear_emergency_stop()
    supervisor.command(Velocity(0.1, 0.0), dt_s=1.0, now_s=0.2)
    assert not robot.velocity.is_zero
    supervisor.close()


def test_device_fault_latches_emergency_stop() -> None:
    robot, supervisor = make_supervisor()
    robot.inject_fault("encoder lost")
    telemetry = supervisor.tick(now_s=0.0)
    assert telemetry.fault == "encoder lost"
    assert supervisor.emergency_stop
    supervisor.close()


def test_non_finite_velocity_never_reaches_device() -> None:
    _, supervisor = make_supervisor()
    with pytest.raises(SafetyViolation):
        supervisor.command(Velocity(math.nan, 0.0), now_s=0.0)
    supervisor.close()


def test_tick_observes_device_estop() -> None:
    robot, supervisor = make_supervisor()
    robot.emergency_stop()
    assert supervisor.tick(now_s=0.0).emergency_stop
    assert supervisor.emergency_stop
    supervisor.close()
