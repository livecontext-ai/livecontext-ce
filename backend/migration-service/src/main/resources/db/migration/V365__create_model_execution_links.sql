-- Model execution links (CLOUD-only feature, inert in CE).
--
-- A "link" decouples a model's BILLING identity from its EXECUTION transport:
-- a model the user selects + is billed for (billed_provider/billed_model, e.g.
-- anthropic/claude-opus-4-8 → Anthropic price) is EXECUTED through a CLI bridge
-- (bridge_provider/bridge_model, e.g. codex). The billed identity is preserved
-- on the execution RESPONSE, so observability + credit consumption keep charging
-- the Anthropic price (see AgentNode.buildObservabilityRequest: the result's
-- provider/model are authoritative for billing). The mapping is intentionally
-- FREE: any billed pair may point at any bridge target - the real model output
-- then comes from the chosen CLI, while the bill stays the billed identity.
--
-- Centralised: ONE global row per billed (provider, model), resolved at a single
-- chokepoint (agent-service AgentRemoteExecutionService.executeAgent). Links apply
-- to the top-level agent execution path: workflow agent nodes AND chat (a non-bridge
-- billed model routes through that same path). Sub-agents, classify and guardrail
-- run their genuine billed model directly and ignore links - still correctly billed.
--
-- The table is created on every install for migration-history parity, but the
-- feature is GATED behind model-catalog.execution-links.enabled (default ON in
-- cloud, OFF in the CE monolith). In CE the table simply stays empty + unread.

CREATE TABLE IF NOT EXISTS agent.model_execution_links (
    id               BIGSERIAL    PRIMARY KEY,
    billed_provider  VARCHAR(50)  NOT NULL,
    billed_model     VARCHAR(150) NOT NULL,
    bridge_provider  VARCHAR(50)  NOT NULL,
    -- NULL = reuse billed_model verbatim as the bridge model id.
    bridge_model     VARCHAR(150),
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_model_execution_links_billed UNIQUE (billed_provider, billed_model)
);

-- Hot lookup at execution time: resolve by billed (provider, model). The UNIQUE
-- constraint already backs an index on (billed_provider, billed_model); the
-- partial index keeps the enabled-row scan tight when the table grows.
CREATE INDEX IF NOT EXISTS idx_model_execution_links_enabled
    ON agent.model_execution_links (billed_provider, billed_model)
    WHERE enabled = TRUE;
