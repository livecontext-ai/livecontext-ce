/**
 * Typed analytics event names for LiveContext product analytics.
 *
 * Full taxonomy & rationale: `the project docs`.
 * Naming convention: snake_case, `object_action`, past tense.
 *
 * This union is CLOSED on purpose: an event name that is not listed here does
 * not compile, so the taxonomy in the doc and the code cannot drift apart.
 * Backend agent/workflow events (`agent_run_stopped`, `api_call_completed`,
 * `workflow_epoch_completed`, ...) are emitted server-side and are NOT part of
 * this client enum; the server also emits the reliable copy of the identity /
 * persona events (`auth_registered`, `onboarding_completed`, plan changes).
 */

export type AnalyticsEvent =
  // - Consent (the gate itself: only an accept can ever be observed)
  | 'consent_accepted'
  // - Landing: intent signals (WHY a visitor is interested)
  | 'landing_section_viewed'
  | 'landing_persona_selected'
  | 'landing_cta_clicked'
  | 'landing_plan_clicked'
  | 'landing_pricing_cycle_toggled'
  | 'landing_faq_opened'
  // - Auth / onboarding (activation)
  | 'auth_login_succeeded'
  | 'auth_login_failed'
  | 'auth_registered'
  | 'auth_logged_out'
  // onboarding_step_completed is emitted by the backend ONLY (single producer)
  | 'onboarding_completed'
  | 'onboarding_skipped'
  // - App navigation
  | 'nav_item_clicked'
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
  // - Workflow builder
  | 'workflow_created'
  | 'workflow_saved'
  | 'workflow_validated'
  | 'workflow_run_triggered'
  | 'workflow_run_trigger_failed'
  | 'workflow_node_added'
  | 'workflow_node_deleted'
  | 'workflow_version_pinned'
  // - Publication / sharing
  | 'publication_modal_opened'
  | 'publication_submitted'
  | 'publication_result'
  // - Chat
  | 'chat_conversation_created'
  | 'chat_message_sent'
  | 'chat_tool_auth_shown'
  | 'chat_tool_auth_resolved'
  | 'chat_service_approval_resolved'
  | 'chat_model_changed'
  | 'chat_config_updated'
  // - Agents / tables / interfaces / applications
  | 'agent_created'
  | 'agent_opened'
  | 'table_created'
  | 'interface_created'
  | 'application_opened'
  | 'application_run_started'
  // - Credentials / AI providers
  | 'credential_wizard_opened'
  | 'credential_saved'
  | 'credential_oauth_result'
  | 'credential_deleted'
  | 'ai_provider_key_saved'
  | 'ai_provider_key_deleted'
  // - Own LLM keys (a cloud user's key, Settings > AI providers > Your keys)
  | 'own_llm_key_saved'
  | 'own_llm_key_deleted'
  | 'own_llm_key_mode_changed'
  // - Pricing / billing (conversion)
  | 'pricing_page_viewed'
  | 'pricing_plan_clicked'
  | 'plan_comparison_opened'
  | 'checkout_started'
  | 'checkout_returned'
  | 'upgrade_confirmed'
  | 'credit_topup_started'
  // - Files
  | 'file_uploaded'
  | 'file_downloaded'
  // - Friction
  | 'api_request_failed';

/**
 * Event properties. UUIDs / enums / counts only - NEVER PII (email, name) or
 * user content (prompts, messages, search text, labels, file names). See the
 * plan's §6.
 */
export type AnalyticsProps = Record<string, string | number | boolean | null | undefined>;
