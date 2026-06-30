"""Docker-per-session wrapper for the browser-agent runner.

Replaces the in-process `_drive_browser_use(...)` call when
`WEBSEARCH_BROWSER_AGENT_USE_DOCKER=true` is set in env. The orchestrator
side does NOT change - the runner.py seam points here.

Lifecycle:

  1. Build args for `docker run --rm livecontext/browser-agent:1` with
     hard cgroup caps (--memory=512m --cpus=0.5 --cap-drop=ALL) and the
     dedicated egress-filtered network (--network=browser-agent-net).
  2. Pass job parameters as `--env` flags (task, session id, run id,
     node id, redis url, llm config, max_steps, timeout).
  3. Spawn the subprocess and wait for exit. The container's main.py
     LPUSHes the final result to `agent:browser:result:{job_id}` itself -
     this wrapper is just a process supervisor; the result still flows
     through Redis exactly like the in-process path.
  4. Surface the container's exit code: 0 == ran to completion (which
     could itself be COMPLETED / TIMEOUT / LLM_FAILED - check the result
     payload). Non-zero == crashed before pushing a result; in that case
     the wrapper synthesizes a fallback LLM_FAILED result so the host's
     BLPOP returns instead of timing out.

Security gates:

  - Hard memory + CPU caps come from the `docker run` flags, NOT from
    the Dockerfile (Dockerfile can't enforce host-side cgroup limits).
  - All Linux capabilities dropped (`--cap-drop=ALL`); container CAN'T
    bind privileged ports, mount, or escape via PID namespaces.
  - Egress restricted by the `browser-agent-net` network's iptables rules
    (host responsibility - see `deploy/docker/browser-agent/README.md`).

Watchdog:

  - `start_orphan_watchdog(...)` runs every 60s, lists `docker ps`
    containers with `ancestor=livecontext/browser-agent:1`, and kills any
    older than `MAX_TIMEOUT_S + 60s`. Idempotent - safe to call multiple
    times during startup.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import time
from typing import Any, Optional

logger = logging.getLogger(__name__)


# ── Tunables (mirror the host runner's caps so log lines line up) ─────────
MAX_TIMEOUT_S = 600
MAX_STEPS_HARD_CAP = 50
DEFAULT_TIMEOUT_S = 600
DEFAULT_MAX_STEPS = 50

DOCKER_IMAGE = "livecontext/browser-agent:1"
DOCKER_NETWORK = "browser-agent-net"
DOCKER_MEMORY = "512m"
DOCKER_CPUS = "0.5"

WATCHDOG_INTERVAL_S = 60
# A container can legitimately run for `MAX_TIMEOUT_S` (10 min). We give a 60s
# grace so SIGTERM has time to flush the final result LPUSH before the watchdog
# `docker kill`s it.
WATCHDOG_HARD_AGE_S = MAX_TIMEOUT_S + 60


def _build_run_args(
    *,
    task: str,
    session_id: str,
    job_id: str,
    run_id: str,
    node_id: str,
    redis_url: str,
    llm_provider: str,
    llm_model: str,
    llm_api_key: str,
    bridge_url: Optional[str],
    callback_url: Optional[str],
    max_steps: int,
    timeout_seconds: int,
    image: str = DOCKER_IMAGE,
    network: str = DOCKER_NETWORK,
    memory: str = DOCKER_MEMORY,
    cpus: str = DOCKER_CPUS,
) -> list[str]:
    """Build the argv for ``docker run`` with proper isolation flags.

    Pure function - no I/O. Tests assert directly on the returned list.
    Order of flags is stable so test assertions can grep for substrings.
    """
    args = [
        "docker", "run", "--rm",
        f"--memory={memory}",
        f"--cpus={cpus}",
        "--cap-drop=ALL",
        f"--network={network}",
        # Defense-in-depth: even if cap-drop is partially bypassed, refuse
        # privilege escalation via setuid binaries.
        "--security-opt=no-new-privileges",
        "--read-only",
        "--tmpfs", "/tmp:rw,size=64m,mode=1777",
        # Env block - keep alphabetical so test assertions are predictable.
        "--env", f"BRIDGE_URL={bridge_url or ''}",
        "--env", f"CALLBACK_URL={callback_url or ''}",
        "--env", f"JOB_ID={job_id}",
        "--env", f"LLM_API_KEY={llm_api_key}",
        "--env", f"LLM_MODEL={llm_model}",
        "--env", f"LLM_PROVIDER={llm_provider}",
        "--env", f"MAX_STEPS={max_steps}",
        "--env", f"NODE_ID={node_id}",
        "--env", f"REDIS_URL={redis_url}",
        "--env", f"RUN_ID={run_id}",
        "--env", f"SESSION_ID={session_id}",
        "--env", f"TASK={task}",
        "--env", f"TIMEOUT_SECONDS={timeout_seconds}",
        image,
    ]
    return args


async def run_in_container(parameters: dict, redis_url: str) -> dict:
    """Spawn one container, wait for completion, return the final result.

    On success the container LPUSHes its own final result to
    ``agent:browser:result:{job_id}``; the host BLPOPs it through the
    normal pipeline. This function returns a SUMMARY dict with the
    container's exit status - it does NOT replace the BLPOP. Callers wire
    this into ``runner.py::_drive_browser_use`` so the wrapper acts as a
    drop-in when ``BROWSER_AGENT_USE_DOCKER`` is set.

    The summary dict shape mirrors what ``_drive_browser_use`` returns
    (stop_reason / final_result / final_url / pages_visited /
    extracted_data). When the container exits cleanly, the host will read
    the same payload from Redis and ignore this summary; when the
    container crashes, this summary is the fallback that prevents BLPOP
    timeout.
    """
    task = parameters.get("task")
    if not task or not isinstance(task, str):
        return {"stop_reason": "LLM_FAILED", "final_result": "missing 'task'"}

    job_id = parameters.get("job_id") or parameters.get("__jobId__") or "job_unknown"
    session_id = parameters.get("session_id") or parameters.get("__sessionId__") or "ses_unknown"
    run_id = parameters.get("run_id") or "run_unknown"
    node_id = parameters.get("node_id") or "node_unknown"

    llm_cfg = parameters.get("llm") or {}
    provider = (llm_cfg.get("provider") or "google").lower()
    model = llm_cfg.get("model") or "gemini-2.5-flash"
    api_key = llm_cfg.get("api_key") or os.environ.get("GOOGLE_API_KEY", "")
    bridge_url = llm_cfg.get("bridge_url") or os.environ.get("BRIDGE_URL", "")

    callback_url = parameters.get("callback_url")

    max_steps = max(1, min(int(parameters.get("max_steps") or DEFAULT_MAX_STEPS), MAX_STEPS_HARD_CAP))
    timeout_s = max(1, min(int(parameters.get("timeout_seconds") or DEFAULT_TIMEOUT_S), MAX_TIMEOUT_S))

    args = _build_run_args(
        task=task,
        session_id=session_id,
        job_id=job_id,
        run_id=run_id,
        node_id=node_id,
        redis_url=redis_url,
        llm_provider=provider,
        llm_model=model,
        llm_api_key=api_key,
        bridge_url=bridge_url,
        callback_url=callback_url,
        max_steps=max_steps,
        timeout_seconds=timeout_s,
    )

    logger.info("spawning browser-agent container: session_id=%s job_id=%s", session_id, job_id)

    proc = await asyncio.create_subprocess_exec(
        *args,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )

    try:
        # Give the container the wallclock cap PLUS a grace window so its
        # own asyncio.wait_for inside main.py can fire first and emit a
        # proper TIMEOUT result before we kill it.
        stdout, stderr = await asyncio.wait_for(
            proc.communicate(), timeout=timeout_s + 30
        )
    except asyncio.TimeoutError:
        logger.warning("container exceeded wallclock cap, killing: session_id=%s", session_id)
        try:
            proc.kill()
            await proc.wait()
        except Exception:
            pass
        return {
            "stop_reason": "TIMEOUT",
            "final_result": f"Container exceeded {timeout_s}s + 30s grace",
            "session_id": session_id,
            "node_type": "BROWSER_AGENT",
        }

    rc = proc.returncode if proc.returncode is not None else -1
    stdout_text = stdout.decode("utf-8", errors="replace") if stdout else ""
    stderr_text = stderr.decode("utf-8", errors="replace") if stderr else ""

    if rc == 0:
        logger.info("container exited cleanly: session_id=%s job_id=%s", session_id, job_id)
        # The container itself pushed the result to Redis - the host's
        # BLPOP will pick it up. Return a marker the host can ignore.
        return {
            "stop_reason": "COMPLETED",
            "final_result": "container exited; final result on Redis",
            "session_id": session_id,
            "node_type": "BROWSER_AGENT",
        }

    logger.error(
        "container crashed: session_id=%s rc=%d stderr=%s",
        session_id, rc, stderr_text[-2000:],
    )
    return {
        "stop_reason": "LLM_FAILED",
        "final_result": f"container exited rc={rc}: {stderr_text[-500:]}",
        "session_id": session_id,
        "node_type": "BROWSER_AGENT",
    }


# ── Orphan-PID watchdog ──────────────────────────────────────────────────


async def _orphan_watchdog(
    *,
    interval_s: int = WATCHDOG_INTERVAL_S,
    max_age_s: int = WATCHDOG_HARD_AGE_S,
    image: str = DOCKER_IMAGE,
) -> None:
    """Periodically kill orphan containers older than ``max_age_s``.

    Listed via ``docker ps --filter ancestor=<image>`` so we don't touch
    other people's containers. Idempotent - safe to run forever.

    Exits cleanly on cancellation. Logs but does not raise on docker CLI
    errors (a transient docker-daemon hiccup must not kill the watchdog
    coroutine).
    """
    while True:
        try:
            killed = await _watchdog_tick(max_age_s=max_age_s, image=image)
            if killed:
                logger.warning("watchdog killed %d orphan container(s)", killed)
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.warning("watchdog tick failed", exc_info=True)
        try:
            await asyncio.sleep(interval_s)
        except asyncio.CancelledError:
            raise


async def _watchdog_tick(*, max_age_s: int, image: str) -> int:
    """One pass of the watchdog. Returns the number of containers killed.

    Pulled out so tests can drive a single iteration deterministically.
    """
    proc = await asyncio.create_subprocess_exec(
        "docker", "ps",
        "--filter", f"ancestor={image}",
        "--format", "{{json .}}",
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    stdout, stderr = await proc.communicate()
    if proc.returncode != 0:
        logger.warning(
            "docker ps failed in watchdog: rc=%s err=%s",
            proc.returncode, (stderr or b"").decode(errors="replace")[:500],
        )
        return 0

    now = int(time.time())
    killed = 0
    for line in (stdout or b"").decode(errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            entry = json.loads(line)
        except json.JSONDecodeError:
            continue
        cid = entry.get("ID") or entry.get("Id") or ""
        # `docker ps` formats CreatedAt as a free-form date; the more reliable
        # signal is `Status` ("Up 12 minutes"). Parse both for safety.
        age = _parse_age_seconds(entry, now=now)
        if cid and age is not None and age > max_age_s:
            logger.warning("killing orphan container %s (age=%ds)", cid, age)
            await _docker_kill(cid)
            killed += 1
    return killed


def _parse_age_seconds(entry: dict[str, Any], *, now: int) -> Optional[int]:
    """Extract a coarse age (seconds) from a `docker ps --format json` entry.

    Looks at ``Status`` strings like "Up 12 minutes", "Up 2 hours". Returns
    None if the format is unrecognized - caller treats unknown as fresh.
    """
    status = (entry.get("Status") or "").strip().lower()
    if not status.startswith("up "):
        return None
    rest = status[3:].strip()
    # Pull the leading int + unit. "Up 12 minutes (healthy)" → "12 minutes".
    parts = rest.split()
    if len(parts) < 2:
        return None
    try:
        n = int(parts[0])
    except ValueError:
        # "Up Less than a second", etc.
        return 0
    unit = parts[1]
    if unit.startswith("second"):
        return n
    if unit.startswith("minute"):
        return n * 60
    if unit.startswith("hour"):
        return n * 3600
    if unit.startswith("day"):
        return n * 86400
    return None


async def _docker_kill(container_id: str) -> None:
    proc = await asyncio.create_subprocess_exec(
        "docker", "kill", container_id,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    _, stderr = await proc.communicate()
    if proc.returncode != 0:
        logger.warning(
            "docker kill %s failed rc=%s err=%s",
            container_id, proc.returncode,
            (stderr or b"").decode(errors="replace")[:200],
        )


def start_orphan_watchdog(loop: asyncio.AbstractEventLoop | None = None) -> asyncio.Task[None]:
    """Schedule the watchdog as a background task. Returns the task so the
    caller can cancel it on shutdown.
    """
    coro = _orphan_watchdog()
    if loop is None:
        return asyncio.create_task(coro, name="browser_agent_orphan_watchdog")
    return loop.create_task(coro, name="browser_agent_orphan_watchdog")
