"""
models.py
---------
Types shared by the self-correction state machine.
"""

from dataclasses import dataclass
from enum import Enum


class CorrectionState(Enum):
    DIRECT = "direct"      # score was good enough first try
    RETRY = "retry"        # score in the middle band, rewrite + retry
    REFUSED = "refused"    # score too low, or retry still didn't clear the bar

DIRECT_THRESHOLD = 0.75
RETRY_FLOOR = 0.40
MAX_RETRIES = 1


@dataclass
class CorrectionDecision:
    state: CorrectionState
    reason: str