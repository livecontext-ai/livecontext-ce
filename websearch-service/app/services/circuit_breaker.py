"""Simple 3-state circuit breaker for external service calls.

States: CLOSED → OPEN → HALF_OPEN → CLOSED (on success) or OPEN (on failure).

Usage:
    cb = CircuitBreaker("browser", failure_threshold=5, recovery_timeout_s=30)

    if not cb.allow_request():
        raise RuntimeError("Circuit open - browser unavailable")

    try:
        result = await do_crawl(...)
        cb.record_success()
    except Exception:
        cb.record_failure()
        raise
"""

import time
import logging
from enum import Enum

logger = logging.getLogger(__name__)


class _State(Enum):
    CLOSED = "CLOSED"
    OPEN = "OPEN"
    HALF_OPEN = "HALF_OPEN"


class CircuitBreaker:
    def __init__(self, name: str, failure_threshold: int = 5, recovery_timeout_s: float = 30.0):
        self.name = name
        self.failure_threshold = failure_threshold
        self.recovery_timeout_s = recovery_timeout_s

        self._state = _State.CLOSED
        self._failure_count = 0
        self._last_failure_time = 0.0

    @property
    def state(self) -> str:
        # Check if OPEN should transition to HALF_OPEN
        if self._state == _State.OPEN:
            if time.monotonic() - self._last_failure_time >= self.recovery_timeout_s:
                self._state = _State.HALF_OPEN
                logger.info("Circuit breaker '%s': OPEN → HALF_OPEN (recovery probe)", self.name)
        return self._state.value

    def allow_request(self) -> bool:
        s = self.state  # triggers OPEN→HALF_OPEN transition check
        if s == _State.CLOSED.value:
            return True
        if s == _State.HALF_OPEN.value:
            return True  # Allow one probe request
        return False  # OPEN - fail fast

    def record_success(self):
        if self._state in (_State.HALF_OPEN, _State.OPEN):
            logger.info("Circuit breaker '%s': %s → CLOSED", self.name, self._state.value)
        self._state = _State.CLOSED
        self._failure_count = 0

    def record_failure(self):
        self._failure_count += 1
        self._last_failure_time = time.monotonic()

        if self._state == _State.HALF_OPEN:
            self._state = _State.OPEN
            logger.warning("Circuit breaker '%s': HALF_OPEN → OPEN (probe failed)", self.name)
        elif self._failure_count >= self.failure_threshold:
            self._state = _State.OPEN
            logger.warning(
                "Circuit breaker '%s': CLOSED → OPEN after %d consecutive failures",
                self.name, self._failure_count,
            )


# Singleton for browser/crawl operations
browser_breaker = CircuitBreaker("browser", failure_threshold=5, recovery_timeout_s=30.0)
