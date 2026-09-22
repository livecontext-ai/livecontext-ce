/**
 * The plan-gate key for running agents on your own provider key.
 *
 * Its own module because both ends of the feature read it and neither should import the
 * other: the settings panel that saves the key, and the cost basis that prices a turn on it.
 * It must stay equal to `PlanFeatureKeys.OWN_LLM_KEY` on the backend (which is what both
 * `OwnKeyFeatureGate` and the price gate in `LlmCostEstimateService` read) and to the row V507
 * seeds, or the lock never matches the gate. Pinned from the Java side by
 * `LlmCostEstimateServiceOwnKeyTest`, which reads this file: a language boundary is the one
 * place a shared constant cannot be shared.
 */
export const OWN_LLM_KEY_FEATURE = 'feature:own_llm_key';
