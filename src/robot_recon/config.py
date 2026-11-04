"""TOML configuration loading without a third-party dependency."""

from __future__ import annotations

from dataclasses import dataclass
from importlib import resources
from pathlib import Path
import tomllib

from .domain import ConfigurationError, RobotLimits


@dataclass(frozen=True, slots=True)
class SerialSettings:
    port: str = "COM3"
    baudrate: int = 115200
    read_timeout_s: float = 0.05

    def __post_init__(self) -> None:
        if not self.port:
            raise ConfigurationError("serial port must not be empty")
        if not 1 <= self.baudrate <= 4_000_000:
            raise ConfigurationError("serial baudrate is out of range")
        if self.read_timeout_s <= 0:
            raise ConfigurationError("read_timeout_s must be positive")


@dataclass(frozen=True, slots=True)
class AppConfig:
    limits: RobotLimits
    serial: SerialSettings


def _required(table: dict[str, object], key: str) -> object:
    if key not in table:
        raise ConfigurationError(f"missing configuration key: {key}")
    return table[key]


def _number(table: dict[str, object], key: str) -> float:
    value = _required(table, key)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ConfigurationError(f"{key} must be a number")
    return float(value)


def load_config(path: str | Path) -> AppConfig:
    with Path(path).open("rb") as handle:
        raw = tomllib.load(handle)
    return _from_raw(raw)


def load_default_config() -> AppConfig:
    """Load the packaged defaults without requiring a writable working tree."""
    resource = resources.files("robot_recon").joinpath("defaults.toml")
    with resource.open("rb") as handle:
        raw = tomllib.load(handle)
    return _from_raw(raw)


def _from_raw(raw: dict[str, object]) -> AppConfig:
    robot = raw.get("robot")
    serial = raw.get("serial", {})
    if not isinstance(robot, dict) or not isinstance(serial, dict):
        raise ConfigurationError("configuration must contain [robot] and optional [serial] tables")
    limits = RobotLimits(
        wheel_base_m=_number(robot, "wheel_base_m"),
        wheel_radius_m=_number(robot, "wheel_radius_m"),
        max_linear_mps=_number(robot, "max_linear_mps"),
        max_angular_rps=_number(robot, "max_angular_rps"),
        max_linear_accel_mps2=_number(robot, "max_linear_accel_mps2"),
        max_angular_accel_rps2=_number(robot, "max_angular_accel_rps2"),
        command_timeout_s=_number(robot, "command_timeout_s"),
        waypoint_tolerance_m=float(robot.get("waypoint_tolerance_m", 0.05)),
        control_period_s=float(robot.get("control_period_s", 0.02)),
    )
    settings = SerialSettings(
        port=str(serial.get("port", "COM3")),
        baudrate=int(serial.get("baudrate", 115200)),
        read_timeout_s=float(serial.get("read_timeout_s", 0.05)),
    )
    return AppConfig(limits=limits, serial=settings)
