"""Browser-agent (browser-use driven) runner package.

Public API:
- `run_browser_agent_session` - async entry point invoked by the /jobs
  dispatcher when `action='agent_browse'`.
- `BrowserAgentSession` - tracks live state for control-plane
  endpoints (browse_status / browse_intervene / browse_abort).
- `get_session_state` / `register_session` - module-level registry.

Docker isolation lives in `docker_session.py` and is opt-in via the
`BROWSER_AGENT_USE_DOCKER` env (default off in v1; v2 mandates it for
prod).
"""

from .runner import run_browser_agent_session
from .session_state import (
    BrowserAgentSession,
    get_session_state,
    list_active_sessions,
    register_session,
    unregister_session,
)

__all__ = [
    "run_browser_agent_session",
    "BrowserAgentSession",
    "get_session_state",
    "list_active_sessions",
    "register_session",
    "unregister_session",
]
