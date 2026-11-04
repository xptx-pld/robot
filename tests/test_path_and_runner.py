import pytest

from robot_recon.control.path import Waypoint, WaypointController
from robot_recon.control.runner import MissionRunner
from robot_recon.domain import Pose, RobotLimits, Velocity
from robot_recon.hal.simulator import SimulatedRobot


def limits() -> RobotLimits:
    return RobotLimits(
        wheel_base_m=0.24,
        wheel_radius_m=0.065,
        max_linear_mps=0.35,
        max_angular_rps=1.2,
        max_linear_accel_mps2=0.8,
        max_angular_accel_rps2=2.0,
        command_timeout_s=0.25,
        waypoint_tolerance_m=0.05,
        control_period_s=0.02,
    )


def test_controller_stops_at_waypoint() -> None:
    controller = WaypointController(limits())
    command, reached = controller.compute(Pose(0.49, 0.0, 0.0), Waypoint(0.5, 0.0))
    assert reached
    assert command.is_zero


def test_controller_turns_toward_behind_target() -> None:
    controller = WaypointController(limits())
    command, reached = controller.compute(Pose(0.0, 0.0, 0.0), Waypoint(-1.0, 0.0))
    assert not reached
    assert command.linear_mps == 0.0
    assert abs(command.angular_rps) > 0


def test_controller_reduces_speed_near_target() -> None:
    controller = WaypointController(limits())
    far, _ = controller.compute(Pose(0.0, 0.0, 0.0), Waypoint(2.0, 0.0))
    near, _ = controller.compute(Pose(0.0, 0.0, 0.0), Waypoint(0.1, 0.0))
    assert near.linear_mps < far.linear_mps


@pytest.mark.parametrize("scenario", ["straight", "turn", "square"])
def test_simulated_missions_complete(scenario: str) -> None:
    scenarios = {
        "straight": ([Waypoint(1.0, 0.0)], 10.0),
        "turn": ([Waypoint(0.5, 0.5)], 15.0),
        "square": ([Waypoint(1.0, 0.0), Waypoint(1.0, 1.0), Waypoint(0.0, 1.0), Waypoint(0.0, 0.0)], 40.0),
    }
    waypoints, duration = scenarios[scenario]
    lim = limits()
    robot = SimulatedRobot(lim.wheel_base_m, lim.wheel_radius_m, step_s=lim.control_period_s)
    result = MissionRunner(robot, lim, WaypointController(lim)).run(waypoints, duration)
    assert result.completed, result.reason
    assert result.visited == len(waypoints)


def test_runner_times_out_instead_of_hanging() -> None:
    lim = limits()
    robot = SimulatedRobot(lim.wheel_base_m, lim.wheel_radius_m, step_s=lim.control_period_s)
    far = [Waypoint(1000.0, 0.0)]
    result = MissionRunner(robot, lim, WaypointController(lim)).run(far, 0.2)
    assert not result.completed
    assert result.reason == "duration exceeded"
    assert result.steps > 0
