"""Command-line entry point for offline simulation and guarded serial bring-up."""

from __future__ import annotations

import argparse
import math
import sys
import time

from .config import load_config, load_default_config
from .control.path import Waypoint, WaypointController
from .control.runner import MissionRunner
from .domain import RobotLimits, Velocity
from .hal.simulator import SimulatedRobot


SCENARIOS: dict[str, list[Waypoint]] = {
    "straight": [Waypoint(1.0, 0.0)],
    "turn": [Waypoint(0.5, 0.5)],
    "square": [Waypoint(1.0, 0.0), Waypoint(1.0, 1.0), Waypoint(0.0, 1.0), Waypoint(0.0, 0.0)],
}


def _limits(args: argparse.Namespace) -> RobotLimits:
    if args.config:
        return load_config(args.config).limits
    return load_default_config().limits


def simulate(args: argparse.Namespace) -> int:
    limits = _limits(args)
    robot = SimulatedRobot(limits.wheel_base_m, limits.wheel_radius_m, step_s=limits.control_period_s)
    controller = WaypointController(limits)
    waypoints = SCENARIOS[args.scenario]

    result = MissionRunner(robot, limits, controller).run(waypoints, args.duration)
    print(f"scenario={args.scenario} completed={result.completed} reason={result.reason}")
    if result.final_pose is not None:
        pose = result.final_pose
        print(f"final_pose=({pose.x_m:.3f}, {pose.y_m:.3f}, {math.degrees(pose.heading_rad):.1f}deg)")
    print(f"steps={result.steps} elapsed={result.elapsed_s:.2f}s waypoints={result.visited}/{len(waypoints)}")
    return 0 if result.completed else 1


def selftest(args: argparse.Namespace) -> int:
    limits = _limits(args)
    robot = SimulatedRobot(limits.wheel_base_m, limits.wheel_radius_m, step_s=limits.control_period_s)
    robot.connect()
    posed = robot.read_telemetry()
    robot.close()
    print(f"ok robot_limits wheel_base={limits.wheel_base_m} max_linear={limits.max_linear_mps}")
    print(f"ok simulator timestamp={posed.timestamp_s:.2f}")
    return 0


def serial_check(args: argparse.Namespace) -> int:
    from .hal.serial_device import SerialRobotDevice
    from .control.supervisor import SafetySupervisor

    limits = _limits(args)
    robot = SerialRobotDevice(args.port, args.baudrate)
    supervisor = SafetySupervisor(robot, limits)
    supervisor.connect()
    try:
        telemetry = supervisor.tick()
        print(f"telemetry pose=({telemetry.pose.x_m:.3f},{telemetry.pose.y_m:.3f}) estop={telemetry.emergency_stop}")
        if args.allow_motion:
            print("WARNING: sending motion command; ensure the robot is elevated and physically safe")
            end = time.monotonic() + args.duration
            while time.monotonic() < end:
                supervisor.command(Velocity(args.linear, args.angular))
                supervisor.tick()
                time.sleep(limits.control_period_s)
        return 0
    finally:
        supervisor.close()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="robot-recon", description="Robot reconstruction skeleton")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("selftest", help="offline self test")
    p.add_argument("--config", help="path to a TOML robot configuration")
    p.set_defaults(func=selftest)

    p = sub.add_parser("simulate", help="run a mission in the deterministic simulator")
    p.add_argument("--scenario", choices=sorted(SCENARIOS), default="square")
    p.add_argument("--duration", type=float, default=30.0)
    p.add_argument("--config", help="path to a TOML robot configuration")
    p.set_defaults(func=simulate)

    p = sub.add_parser("serial", help="guarded serial bring-up")
    p.add_argument("--port", required=True)
    p.add_argument("--config", help="path to a TOML robot configuration")
    p.add_argument("--baudrate", type=int, default=115200)
    p.add_argument("--duration", type=float, default=2.0)
    p.add_argument("--linear", type=float, default=0.05)
    p.add_argument("--angular", type=float, default=0.0)
    p.add_argument("--allow-motion", action="store_true")
    p.set_defaults(func=serial_check)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return int(args.func(args))
    except KeyboardInterrupt:
        print("interrupted", file=sys.stderr)
        return 130
    except Exception as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
