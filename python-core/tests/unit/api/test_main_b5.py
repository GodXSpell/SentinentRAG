"""
test_main_b5.py
---------------
Regression test for B-5: Deprecated @app.on_event("startup") in FastAPI.
Ensures lifespan context manager is used instead of deprecated event handlers.
"""

from unittest.mock import MagicMock
import pytest
from app import main


def test_main_uses_lifespan_instead_of_on_event() -> None:
    """
    B-5 Regression Test:
    Verify that FastAPI application does not register deprecated on_event("startup")
    handlers, and instead configures a modern lifespan context manager.
    """
    # 1. Deprecated on_startup handlers list must be empty
    startup_handlers = getattr(main.app.router, "on_startup", [])
    assert len(startup_handlers) == 0, (
        f"Found {len(startup_handlers)} deprecated on_startup handlers in app.router"
    )

    # 2. Modern lifespan must be configured on app and router
    lifespan = getattr(main, "lifespan", None)
    assert lifespan is not None, "app.main must define a lifespan contextmanager"
    assert main.app.router.lifespan_context is not None, (
        "FastAPI app router must have lifespan_context configured"
    )


@pytest.mark.anyio
async def test_lifespan_invokes_ensure_collection(monkeypatch: pytest.MonkeyPatch) -> None:
    """
    Verify that entering the lifespan context executes ensure_collection().
    """
    mock_ensure = MagicMock()
    monkeypatch.setattr(main, "ensure_collection", mock_ensure)

    lifespan = getattr(main, "lifespan", None)
    assert lifespan is not None, "lifespan context manager missing"

    async with lifespan(main.app):
        mock_ensure.assert_called_once()
