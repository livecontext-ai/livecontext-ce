/**
 * Typed analytics event names for LiveContext product analytics.
 *
 * Full taxonomy & rationale: `the project docs`.
 * Naming convention: snake_case, `object_action`, past tense.
 *
 * Phase 1 (this file) covers the activation / acquisition / publication
 * funnels captured from the frontend. Backend agent/workflow events
 * (`agent_run_stopped`, `tool_call_completed`, …) are emitted server-side and
 * are NOT part of this client enum.
 */

export type AnalyticsEvent =
  // - Auth / onboarding (activation)
  | 'auth_login_succeeded'
  | 'auth_login_failed'
  | 'auth_registered'
  | 'onboarding_step_completed'
  | 'onboarding_completed'
  | 'onboarding_skipped'
  // - Marketplace / discovery
  | 'marketplace_viewed'
  | 'marketplace_searched'
  | 'marketplace_filtered'
  | 'publication_card_clicked'
  | 'publication_detail_viewed'
  // - Install / acquisition funnel
  | 'app_install_started'
  | 'app_install_succeeded'
  | 'app_install_failed'
  | 'app_post_install_opened'
  // - Publication / sharing
  | 'publication_modal_opened'
  | 'publication_submitted'
  | 'publication_result';

/**
 * Event properties. UUIDs / enums / counts only - NEVER PII (email, name) or
 * user content (prompts, messages, file contents). See the plan's §6.
 */
export type AnalyticsProps = Record<string, string | number | boolean | null | undefined>;
