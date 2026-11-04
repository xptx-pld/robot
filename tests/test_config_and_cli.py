from pathlib import Path

import pytest

from robot_recon.config import load_config
from robot_recon.domain import ConfigurationError, RobotLimits


def test_example_config_loads() -> None:
    config = load_config(Path(__file__).parents[1] / "config" / "robot.example.toml")
    assert config.limits.wheel_base_m == pytest.approx(0.24)
    assert config.serial.baudrate == 115200


def test_control_period_must_be_shorter_than_watchdog() -> None:
    with pytest.raises(ConfigurationError):
        RobotLimits(
            wheel_base_m=0.2,
            wheel_radius_m=0.05,
            max_linear_mps=1.0,
            max_angular_rps=1.0,
            max_linear_accel_mps2=1.0,
            max_angular_accel_rps2=1.0,
            command_timeout_s=0.02,
            control_period_s=0.02,
        )


def test_non_positive_physical_parameter_rejected() -> None:
    with pytest.raises(ConfigurationError):
        RobotLimits(
            wheel_base_m=0.0,
            wheel_radius_m=0.05,
            max_linear_mps=1.0,
            max_angular_rps=1.0,
            max_linear_accel_mps2=1.0,
            max_angular_accel_rps2=1.0,
            command_timeout_s=0.25,
            control_period_s=0.02,
        )
