-- Running agents on the tenant's OWN LLM provider key starts at PRO on shared cloud.
--
-- Own-key is a shared-cloud pricing decision: the provider bills the user the tokens and
-- the platform charges a flat fee per turn (V506), so it is offered from the paid plan
-- that carries the other cloud capabilities (vector search V467, browser agent V469).
-- Self-hosted and dedicated cloud are never gated (OwnKeyFeatureGate short-circuits on
-- the edition before any plan lookup). The bar lives in this table so an admin can move
-- it without a deploy; deleting the row (or setting FREE) opens it to every plan.

INSERT INTO auth.plan_feature_requirement (feature_key, min_plan, label, updated_by)
VALUES ('feature:own_llm_key', 'PRO', 'Own LLM API key', 'seed:V507')
ON CONFLICT (feature_key) DO NOTHING;
