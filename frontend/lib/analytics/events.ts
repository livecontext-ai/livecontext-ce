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
  // Left onboarding by signing out, from a state that offers no other way out.
  // `signed_out_from`: 'email_verification' (the email-code step), 'form_error'
  // (a save or skip that keeps failing on steps 1 to 3), 'error' (the error card).
  // Emitted BEFORE logout(), which resets analytics, so it counts the attempt:
  // a redirect the identity provider refuses still counts, and that user may go
  // on to finish onboarding. Overcounts only while an IdP is failing.
  | 'onboarding_signed_out'
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
  | 'api_request_failed'
  // - Setup checklist (first-run guidance)
  | 'setup_checklist_opened'
  | 'setup_checklist_task_clicked'
  | 'setup_checklist_completed'
  // - Notification bell (inbox / triggers / activity)
  | 'notification_bell_opened'
  | 'notification_tab_changed'
  | 'notification_row_clicked'
  // - Announcements (welcome plan gift, "What's new")
  | 'welcome_plan_shown'
  | 'welcome_plan_dismissed'
  | 'changelog_shown'
  | 'changelog_closed'
  | 'mfa_nudge_shown'
  | 'mfa_nudge_clicked'
  | 'mfa_nudge_dismissed'
  // - Human-in-the-loop (ask_user cards, run blockers)
  | 'ask_user_answered'
  | 'ask_user_dismissed'
  | 'run_blocker_resolved'
  // - Chat channels (Telegram / Slack / ...). connect / disconnect / default are
  // emitted by the backend ONLY (single producer).
  | 'channel_discovery_run'
  | 'channel_assistant_help_clicked'
  | 'agent_channel_configured'
  | 'approval_channel_configured'
  // - Trophies (badge_unlocked is backend-only)
  | 'trophy_viewed'
  // - BYOK (own OAuth clients / keys)
  | 'byok_upgrade_clicked'
  | 'oauth_scopes_chosen'
  // - Studio (generations)
  | 'studio_model_selected'
  | 'studio_generation_submitted'
  // - Agenda
  | 'agenda_slot_opened'
  | 'agenda_schedule_paused'
  | 'agenda_run_now'
  | 'agenda_schedule_moved'
  // - Chat model defaults
  | 'chat_model_auto_switched'
  // - Enterprise SSO
  | 'sso_lookup_submitted';

/**
 * Event properties. UUIDs / enums / counts only - NEVER PII (email, name) or
 * user content (prompts, messages, search text, labels, file names). See the
 * plan's §6.
 */
export type AnalyticsProps = Record<string, string | number | boolean | null | undefined>;
