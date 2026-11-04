import math

import pytest

from robot_recon.domain import Pose, SafetyViolation, Velocity, WheelSpeed
from robot_recon.kinematics import DifferentialDriveKinematics, distance, wrap_angle


@pytest.fixture
def kinematics() -> DifferentialDriveKinematics:
    return DifferentialDriveKinematics(wheel_base_m=0.24, wheel_radius_m=0.065)


def test_round_trip_wheel_velocities(kinematics: DifferentialDriveKinematics) -> None:
    original = Velocity(0.3, -0.7)
    wheels = kinematics.velocities_to_wheels(original)
    recovered = kinematics.wheels_to_velocities(wheels)
    assert recovered.linear_mps == pytest.approx(original.linear_mps)
    assert recovered.angular_rps == pytest.approx(original.angular_rps)


def test_straight_integration(kinematics: DifferentialDriveKinematics) -> None:
    pose = kinematics.integrate(Pose(), Velocity(0.2, 0.0), 2.0)
    assert pose.x_m == pytest.approx(0.4)
    assert pose.y_m == pytest.approx(0.0)
    assert pose.heading_rad == pytest.approx(0.0)


def test_arc_heading_and_chord(kinematics: DifferentialDriveKinematics) -> None:
    start = Pose(0.2, -0.3, 0.7)
    end = kinematics.integrate(start, Velocity(0.25, 0.5), 1.3)
    travelled = distance(start, end)
    arc_length = 0.25 * 1.3
    # A curved path's chord is shorter than its arc length.
    assert 0.0 < travelled < arc_length
    assert end.heading_rad == pytest.approx(wrap_angle(0.7 + 0.5 * 1.3))


def test_stationary_rotation_changes_heading_only(kinematics: DifferentialDriveKinematics) -> None:
    end = kinematics.integrate(Pose(1.0, 2.0, 0.0), Velocity(0.0, 1.0), 1.0)
    assert end.x_m == pytest.approx(1.0)
    assert end.y_m == pytest.approx(2.0)
    assert end.heading_rad == pytest.approx(1.0)


@pytest.mark.parametrize("bad_dt", [0, -0.1, math.inf, math.nan])
def test_invalid_dt_rejected(kinematics: DifferentialDriveKinematics, bad_dt: float) -> None:
    with pytest.raises(ValueError):
        kinematics.integrate(Pose(), Velocity(), bad_dt)


def test_non_finite_velocity_rejected() -> None:
    with pytest.raises(SafetyViolation):
        Velocity(math.nan, 0.0)
    with pytest.raises(SafetyViolation):
        WheelSpeed(0.0, math.inf)
