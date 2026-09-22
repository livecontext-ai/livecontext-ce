import type { ModelExecutionLinkScope } from "@/lib/api/model-config.service";

/**
 * The app surfaces a model execution link can be scoped to, in the order the
 * admin UI lists them. Mirrors the backend {@code ModelExecutionLinkScope} enum:
 * {@code ALL} is the wildcard (every surface) and the default; an exact surface
 * overrides {@code ALL} for just that surface.
 *
 * <p>Shared by the Execution links panel (the full editor) and the per-model
 * badge in the Models panel, so the two can never offer different surface sets.
 * {@code labelKey} is a key under the {@code aiProviders.executionLinks}
 * namespace.
 */
export const EXECUTION_LINK_SCOPES: { value: ModelExecutionLinkScope; labelKey: string }[] = [
  { value: "ALL", labelKey: "scopeAll" },
  { value: "CHAT", labelKey: "scopeChat" },
  { value: "WORKFLOW", labelKey: "scopeWorkflow" },
  { value: "WEBHOOK", labelKey: "scopeWebhook" },
  { value: "WIDGET", labelKey: "scopeWidget" },
  { value: "SCHEDULE", labelKey: "scopeSchedule" },
  { value: "TASK", labelKey: "scopeTask" },
  { value: "TASK_REVIEW", labelKey: "scopeTaskReview" },
];

/**
 * Label key for one scope value. A value outside the map yields `undefined`, so every
 * caller applies its own `?? "scopeAll"`: putting the fallback here would hide a scope
 * the backend accepts and no screen names.
 */
export const EXECUTION_LINK_SCOPE_LABEL_KEY: Record<string, string> = Object.fromEntries(
  EXECUTION_LINK_SCOPES.map((s) => [s.value, s.labelKey]),
);
