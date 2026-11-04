"""Hardware abstraction boundary.

Everything above this layer works with velocities and telemetry. A new robot only
requires another implementation of :class:`RobotDevice`; control and mission code
remain unchanged.
"""

from __future__ import annotations

from typing import Protocol, runtime_checkable

from ..domain import Telemetry, Velocity


@runtime_checkable
class RobotDevice(Protocol):
    """Minimal device contract used by the safety supervisor."""

    @property
    def connected(self) -> bool: ...

    def connect(self) -> None: ...

    def close(self) -> None: ...

    def send_velocity(self, velocity: Velocity) -> None: ...

    def stop(self) -> None: ...

    def read_telemetry(self) -> Telemetry: ...

    def emergency_stop(self) -> None: ...

    def clear_emergency_stop(self) -> None: ...
