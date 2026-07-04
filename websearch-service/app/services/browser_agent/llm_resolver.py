"""LLM client resolver for the browser-agent runner.

Maps a workflow-supplied `llm` block to the right `browser_use` chat
client. The block is forwarded verbatim from the orchestrator (see
`BrowserAgentNode.resolveParams`) and looks like:

    {
      "provider": <see accepted set below>,
      "provider_kind": "direct" | "bridge",
      "model": "gemini-2.5-flash" | "gpt-4o" | "claude-sonnet-4-6" | …,
      "credentials_ref": "user-key-…",          # opaque ref, NOT used here
      "api_key": "sk-…",                         # resolved upstream by Java
      "bridge_url": "http://lc-bridge:8093",     # optional, for provider_kind='bridge'
    }

Accepted `provider` values (per `_resolve_llm`):
  - direct, dedicated browser-use binding: google, anthropic, openai
  - direct, OpenAI-wire-compatible (routed via ChatOpenAI + base_url): see
    `OPENAI_COMPATIBLE_BASE_URLS` below - currently deepseek, mistral, xai,
    perplexity, cohere, zai, openrouter
  - bridge: anything when `provider_kind="bridge"` (provider name is a
    HINT only; the bridge resolves the actual upstream)

NOTE on `provider_kind='bridge'` (Audit L MAJOR doc-drift fix)
---------------------------------------------------------------
The orchestrator's automatic substitution path
(`BrowserAgentModule.applyDefaultLlmIfNeeded`) NO LONGER injects
`provider_kind='bridge'`. Bridges (Claude Code / codex / gemini-cli /
mistral-vibe) cannot serve `agent_browse` end-to-end because the
mcp/bridge service exposes only `POST /api/bridge/execute` (a full-CLI
agent session per call), NOT a per-step chat-completion endpoint -
browser-use needs the latter. Java therefore picks the highest-ranked
DIRECT-API model on substitution, and bridges remain the platform
default for chat / agent.create which DO work with full-session calls.

The `BridgeChatClient` + `provider_kind='bridge'` branch is RETAINED
intentionally for direct Python callers (test harnesses, future bridge
endpoints that DO expose `/v1/chat/completions`, manual workflow plans
that explicitly opt in). It is NOT reached via the orchestrator's
automatic substitution today.

CRITICAL - bridge billing rule
-------------------------------
Per the project memory entry "Bridges - never short-circuit billing":
bridges are admin-only routes; prices are display-only; the bridge is
the indirection layer. When `provider_kind == 'bridge'` IS explicitly
passed, the runner MUST forward chat completions to `lc-bridge:8093`,
NOT call Anthropic or OpenAI directly using a resolved upstream key.
Bypassing the bridge would burn the bridge owner's upstream credit
without recording it against the user's internal credit.

Tests in `test_browser_agent_runner.py` assert that:
  - `_resolve_llm` with `provider_kind='bridge'` returns a client that
    POSTs to `bridge_url` and NEVER imports/instantiates the direct
    Anthropic/OpenAI clients.
"""

from __future__ import annotations

import logging
from typing import Any

logger = logging.getLogger(__name__)

DEFAULT_BRIDGE_URL = "http://lc-bridge:8093"

# Providers that speak the OpenAI chat-completions wire format on their own
# host. browser-use ships ChatOpenAI which already accepts a `base_url` kwarg,
# so any provider here can be served by routing through ChatOpenAI with the
# matching base_url - no per-provider Python binding required.
#
# CONTRACT - must mirror the platform's configured LLM providers
# ----------------------------------------------------------------
# The platform admin decides which providers are exposed for browser_agent via
# the agent-service category sidecar (V156). The orchestrator forwards the
# admin's choice verbatim; this map is the runner's side of the contract - if
# the admin can enable provider X for browser_agent, X MUST be either a
# directly-bound provider above (google/anthropic/openai), a bridge, or listed
# here. Otherwise the runner crashes with LlmConfigError as soon as the agent
# tries to use it. The same prod incident (2026-05-07: "unknown llm provider
# 'deepseek'") replays for every missing entry.
#
# Drift guard: `tests/test_browser_agent_llm_resolver.py` parses the
# corresponding Java sources at test time and asserts every catalog provider
# name has a matching entry here, so a future Java-side addition that forgets
# this file fails CI rather than prod.
#
# Source of truth (each entry below maps to one Java provider):
#   Dedicated subclasses of AbstractLLMProvider in shared-agent-lib:
#     - DeepSeekProvider.java → https://api.deepseek.com/v1/chat/completions
#     - MistralProvider.java  → https://api.mistral.ai/v1/chat/completions
#   OpenAICompatibleProviderFactory.java (one entry per knownProviders.put):
#     - xai        → https://api.x.ai/v1/chat/completions
#     - perplexity → https://api.perplexity.ai/chat/completions
#     - cohere     → https://api.cohere.ai/compatibility/v1/chat/completions
#     - zai        → https://open.bigmodel.cn/api/paas/v4/chat/completions
#     - openrouter → https://openrouter.ai/api/v1/chat/completions
# We strip the `/chat/completions` suffix because browser-use's ChatOpenAI
# (via openai SDK's AsyncOpenAI(base_url=...)) appends it itself.
OPENAI_COMPATIBLE_BASE_URLS: dict[str, str] = {
    "deepseek":   "https://api.deepseek.com/v1",
    "mistral":    "https://api.mistral.ai/v1",
    "xai":        "https://api.x.ai/v1",
    "perplexity": "https://api.perplexity.ai",
    "cohere":     "https://api.cohere.ai/compatibility/v1",
    "zai":        "https://open.bigmodel.cn/api/paas/v4",
    "openrouter": "https://openrouter.ai/api/v1",
}


class LlmConfigError(Exception):
    """Raised when the `llm` block cannot be turned into a usable client.

    The runner catches this and surfaces stop_reason='LLM_FAILED' with
    `final_result=str(error)`.
    """


def _import_chat_google() -> Any:
    """Import `ChatGoogle` lazily so the module loads even when
    google-genai isn't installed (e.g. on a bridge-only host).
    """
    try:
        from browser_use import ChatGoogle  # type: ignore
        return ChatGoogle
    except ImportError as e:
        raise LlmConfigError(
            f"provider='google' requires the browser-use ChatGoogle binding "
            f"(install browser-use + google-genai): {e}"
        ) from e


def _import_chat_anthropic() -> Any:
    """Import `ChatAnthropic` lazily.

    Some hosts only have the Google + bridge stack installed; importing
    `ChatAnthropic` at module load would fail-closed for those hosts even
    when no Anthropic session is requested.
    """
    try:
        from browser_use import ChatAnthropic  # type: ignore
        return ChatAnthropic
    except ImportError as e:
        raise LlmConfigError(
            f"provider='anthropic' requires the browser-use ChatAnthropic "
            f"binding (install anthropic): {e}"
        ) from e


def _import_chat_openai() -> Any:
    """Import `ChatOpenAI` lazily."""
    try:
        from browser_use import ChatOpenAI  # type: ignore
        return ChatOpenAI
    except ImportError as e:
        raise LlmConfigError(
            f"provider='openai' requires the browser-use ChatOpenAI binding "
            f"(install openai): {e}"
        ) from e


class BridgeChatClient:
    """browser-use-compatible chat client that POSTs to lc-bridge:8093.

    Implements the minimum surface browser-use's Agent expects: the
    object must be picklable / awaitable for chat completions. We
    deliberately keep the surface small and forward to the bridge over
    HTTP - the bridge handles upstream provider routing AND internal
    credit accounting (see CLAUDE.md "Bridges - never short-circuit
    billing"). NEVER add a fast-path that imports anthropic/openai SDKs
    here; that would bypass the bridge's billing layer.

    The exact wire format is the OpenAI chat-completions shape that
    lc-bridge accepts:
        POST {bridge_url}/v1/chat/completions
        Body: {"model": "...", "messages": [...], ...}
        Headers: Authorization: Bearer <api_key>  (passed through)
    """

    def __init__(self, *, model: str, bridge_url: str, api_key: str | None,
                 provider: str | None, relay_secret: str | None = None):
        self.model = model
        # The bridge IS the upstream - never substitute direct provider
        # endpoints here under any circumstance.
        self.bridge_url = bridge_url.rstrip("/")
        self.api_key = api_key
        # Recorded for observability; the bridge resolves the actual
        # upstream from `model` itself.
        self.provider = provider
        # Optional shared secret for the CE browser-agent LLM shim. Sent as the
        # X-Browser-Agent-Relay-Secret header (NOT Authorization, which the CE monolith security
        # filter would reject as a non-JWT bearer). Blank/None => the shim is open on the internal
        # network (cloud-link gated).
        self.relay_secret = relay_secret
        # Cumulative token usage across every ainvoke on this client. browser-use's
        # TokenCost does not track a custom bridge client (and calculate_cost=False disables
        # it), so the runner reads these accumulators to keep the browser agent's token/cost
        # observability complete on the cloud-relay path (see runner._apply_bridge_relay_usage).
        self.relay_prompt_tokens = 0
        self.relay_completion_tokens = 0
        self.relay_calls = 0

    # browser-use's Agent reads `llm.model_name` (and sometimes `llm.name`)
    # for logging, cost-by-model attribution, and the per-step trace. The
    # direct ChatGoogle/ChatAnthropic/ChatOpenAI clients all expose these
    # as plain attributes - when they're missing the Agent loop crashes
    # with AttributeError before the first step executes (cf. e2e bug
    # "'BridgeChatClient' object has no attribute 'model_name'").
    @property
    def model_name(self) -> str:
        return self.model

    @property
    def name(self) -> str:
        return self.model

    def __repr__(self) -> str:
        # Avoid leaking the api_key in logs.
        return (
            f"BridgeChatClient(model={self.model!r}, bridge_url={self.bridge_url!r}, "
            f"provider={self.provider!r}, has_api_key={bool(self.api_key)})"
        )

    async def ainvoke(
        self,
        messages: list[Any],
        output_format: type[Any] | None = None,
        **kwargs: Any,
    ) -> Any:
        """Async chat completion compatible with browser-use 0.12.x's
        ``BaseChatModel.ainvoke(messages, output_format=None, **kwargs)``.

        browser-use calls this with `output_format` as the SECOND positional
        argument (a pydantic model class for structured output, or None for
        free-form text). The pre-fix signature had `output_format` swallowed
        by `**kwargs` only, so any positional call crashed with
        ``ainvoke() takes 2 positional arguments but 3 were given`` - observed
        end-to-end in browser_agent runs after the bridge default tagging
        landed (see commit history).

        Returns a :class:`browser_use.llm.views.ChatInvokeCompletion` instance
        - NOT raw OpenAI JSON. The Agent loop reads ``.completion`` /
        ``.usage`` / ``.thinking`` and would AttributeError on a dict.
        """
        # Lazy imports - runner.py is the only module that requires browser-use
        # to be installed; keeping these local lets the resolver unit-test
        # without the dependency.
        import httpx
        from browser_use.llm.views import ChatInvokeCompletion, ChatInvokeUsage

        # browser-use sends BaseMessage pydantic objects (UserMessage,
        # SystemMessage, …); the bridge speaks OpenAI chat-completions which
        # is a list of {role, content} dicts. Use model_dump() when available
        # (covers every subclass), fall back to manual dict for anything
        # already in dict form (test fixtures / legacy callers).
        wire_messages: list[dict[str, Any]] = []
        for m in messages:
            if isinstance(m, dict):
                wire_messages.append(m)
            elif hasattr(m, "model_dump"):
                # exclude_none avoids the bridge rejecting Pydantic-generated
                # `None` keys it doesn't understand (e.g. `tool_calls=None`).
                wire_messages.append(m.model_dump(exclude_none=True))
            else:
                # Last-resort manual extraction: every BaseMessage exposes
                # role + content. If neither is present we let the bridge
                # surface its own validation error rather than silently
                # dropping the message.
                wire_messages.append({
                    "role": getattr(m, "role", "user"),
                    "content": getattr(m, "content", str(m)),
                })

        url = f"{self.bridge_url}/v1/chat/completions"
        headers: dict[str, str] = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        # Forward the provider so the CE cloud-relay shim can bill/route the right provider while
        # keeping `model` clean (unqualified) for pricing + observability on the Java side.
        if self.provider:
            headers["X-LLM-Provider"] = str(self.provider)
        # Shared secret for the CE shim, as a non-Authorization header so the monolith security
        # filter passes it through (an Authorization bearer would be 401'd as a non-JWT).
        if self.relay_secret:
            headers["X-Browser-Agent-Relay-Secret"] = str(self.relay_secret)
        body: dict[str, Any] = {"model": self.model, "messages": wire_messages}

        # Structured output: if browser-use passed a pydantic model class,
        # request a JSON object back from the bridge so we can validate the
        # content against `output_format`. The OpenAI chat-completions API
        # accepts `response_format={"type": "json_object"}` - most upstream
        # providers route this through the bridge unchanged.
        if output_format is not None:
            body["response_format"] = {"type": "json_object"}

        # Forward any remaining kwargs (temperature, top_p, …) verbatim.
        for k, v in kwargs.items():
            if k not in body:
                body[k] = v

        async with httpx.AsyncClient(timeout=60.0) as client:
            resp = await client.post(url, json=body, headers=headers)
            resp.raise_for_status()
            data = resp.json()

        # Extract the assistant content + usage. The bridge MUST return an
        # OpenAI-compatible payload (it's the contract); anything else is a
        # bridge bug and we surface a precise error rather than silently
        # constructing an empty completion.
        try:
            content = data["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as e:
            raise RuntimeError(
                f"BridgeChatClient: malformed response from {self.bridge_url} "
                f"(missing choices[0].message.content): {data}"
            ) from e

        # Parse structured output when the caller asked for one.
        completion: Any = content
        if output_format is not None:
            import json
            try:
                parsed = output_format.model_validate(json.loads(content))
                completion = parsed
            except Exception as e:
                # Surface the parse failure with the raw content so the agent
                # log shows what the bridge actually returned. browser-use
                # treats this as an LLM_FAILED in the same way as direct providers.
                raise RuntimeError(
                    f"BridgeChatClient: bridge returned content that does not "
                    f"match output_format={output_format.__name__}: {content!r}"
                ) from e

        usage_raw = data.get("usage") or {}
        # Accumulate real usage for the runner's observability fallback (browser-use does not
        # track this custom client). The CE shim reports the cloud relay's token counts.
        self.relay_prompt_tokens += int(usage_raw.get("prompt_tokens") or 0)
        self.relay_completion_tokens += int(usage_raw.get("completion_tokens") or 0)
        self.relay_calls += 1
        usage = ChatInvokeUsage(
            prompt_tokens=usage_raw.get("prompt_tokens", 0),
            prompt_cached_tokens=usage_raw.get("prompt_cached_tokens"),
            prompt_cache_creation_tokens=usage_raw.get("prompt_cache_creation_tokens"),
            prompt_image_tokens=usage_raw.get("prompt_image_tokens"),
            completion_tokens=usage_raw.get("completion_tokens", 0),
            total_tokens=usage_raw.get("total_tokens", 0),
        )
        return ChatInvokeCompletion(completion=completion, usage=usage)


def _resolve_llm(llm_cfg: dict[str, Any]) -> Any:
    """Turn a workflow `llm` block into a ready-to-use chat client.

    Returns either a direct provider client (ChatGoogle/ChatAnthropic/
    ChatOpenAI from browser-use) OR a `BridgeChatClient` instance when
    `provider_kind == 'bridge'`.

    Raises `LlmConfigError` for missing/unknown provider, unparseable
    config, or import failure.

    Defaults:
      - `provider`: 'google'   (matches v1 host install)
      - `provider_kind`: 'direct'
      - `bridge_url`: 'http://lc-bridge:8093' when provider_kind='bridge'
    """
    if llm_cfg is None:
        llm_cfg = {}
    if not isinstance(llm_cfg, dict):
        raise LlmConfigError(
            f"llm config must be a dict, got {type(llm_cfg).__name__}"
        )

    provider = (llm_cfg.get("provider") or "google").lower().strip()
    provider_kind = (llm_cfg.get("provider_kind") or "direct").lower().strip()
    model = llm_cfg.get("model")
    api_key = llm_cfg.get("api_key")

    if not model or not isinstance(model, str):
        raise LlmConfigError("llm.model is required and must be a string")

    # ── Bridge path ───────────────────────────────────────────────────
    # See CLAUDE.md "Bridges - never short-circuit billing": when the
    # user picked a bridge model, we MUST forward to the bridge and
    # NEVER instantiate a direct provider client. The bridge handles
    # upstream routing + credit accounting.
    if provider_kind == "bridge":
        bridge_url = llm_cfg.get("bridge_url") or DEFAULT_BRIDGE_URL
        if not isinstance(bridge_url, str) or not bridge_url.startswith(("http://", "https://")):
            raise LlmConfigError(
                f"llm.bridge_url must be an http(s) URL, got {bridge_url!r}"
            )
        logger.info(
            "browser_agent llm: bridge route model=%s bridge_url=%s provider_hint=%s",
            model, bridge_url, provider,
        )
        return BridgeChatClient(
            model=model,
            bridge_url=bridge_url,
            api_key=api_key,
            provider=provider,
            relay_secret=llm_cfg.get("relay_secret"),
        )

    # ── Direct path ───────────────────────────────────────────────────
    if provider == "google":
        ChatGoogle = _import_chat_google()
        # ChatGoogle's constructor signature varies across browser-use
        # versions: some take `api_key=...`, others only read
        # `GOOGLE_API_KEY` from the environment. We try the explicit
        # parameter first and fall back to env-var injection - the
        # orchestrator's `BrowserAgentModule.injectLlmApiKey` resolves
        # the key from auth-service's `platform_credentials` and forwards
        # it in the block, so we MUST plumb it through one way or the
        # other; otherwise the runner crashes with "No API key was
        # provided" and 0 LLM calls are made.
        if api_key:
            try:
                return ChatGoogle(model=model, api_key=api_key)
            except TypeError:
                # Older browser-use ChatGoogle: no `api_key` kwarg.
                # Fall back to env-var injection. `setdefault` preserves
                # any admin-set GOOGLE_API_KEY in the host env so a
                # multi-tenant runner doesn't clobber the operator's
                # baseline key with whatever Java resolved for THIS job.
                # If the env var is unset (the dev/CE default), our
                # resolved api_key takes effect for this job.
                # Concurrency note: BrowserAgentModule pins host-level
                # parallelism to 1, but multiple Python paths share the
                # same process - setdefault is the right choice
                # regardless.
                import os
                os.environ.setdefault("GOOGLE_API_KEY", api_key)
        return ChatGoogle(model=model)

    if provider == "anthropic":
        ChatAnthropic = _import_chat_anthropic()
        if api_key:
            return ChatAnthropic(model=model, api_key=api_key)
        return ChatAnthropic(model=model)

    if provider == "openai":
        ChatOpenAI = _import_chat_openai()
        if api_key:
            return ChatOpenAI(model=model, api_key=api_key)
        return ChatOpenAI(model=model)

    # OpenAI-compatible third-party providers (deepseek, groq, xai, mistral, …):
    # route through ChatOpenAI with the provider's base_url. The wire format is
    # OpenAI chat-completions; only the host changes. base_url comes from
    # OPENAI_COMPATIBLE_BASE_URLS - see the module-level comment for the
    # admin/runner contract and how to add a provider.
    base_url = OPENAI_COMPATIBLE_BASE_URLS.get(provider)
    if base_url is not None:
        ChatOpenAI = _import_chat_openai()
        logger.info(
            "browser_agent llm: openai-compatible route provider=%s model=%s base_url=%s",
            provider, model, base_url,
        )
        if api_key:
            return ChatOpenAI(model=model, api_key=api_key, base_url=base_url)
        return ChatOpenAI(model=model, base_url=base_url)

    accepted = sorted({"google", "anthropic", "openai", *OPENAI_COMPATIBLE_BASE_URLS.keys()})
    raise LlmConfigError(
        f"unknown llm provider {provider!r} (expected one of: "
        f"{', '.join(accepted)}, or provider_kind='bridge')"
    )
