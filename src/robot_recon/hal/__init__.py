"""Hardware adapters."""

from .base import RobotDevice
from .simulator import SimulatedRobot

__all__ = ["RobotDevice", "SimulatedRobot"]
