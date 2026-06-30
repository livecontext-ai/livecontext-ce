-- V301: Backfill categories onto already-published marketplace applications.
--
-- Context: companion to V300 (which seeds orchestrator.workflow_categories).
-- publication-service stores the category DENORMALIZED on each publication
-- (category_id + category_slug + category_name + category_icon_slug +
-- category_color - set at publish time from orchestrator's category, see
-- WorkflowPublicationService.publishWorkflow). Existing applications published
-- before V300 have all five columns NULL. This assigns a sensible category to
-- the uncategorized application publications so they are filterable in the
-- marketplace and surface in onboarding suggestions.
--
-- "Application" = the same predicate /app/applications uses
-- (PublicationListQueryService.findByScope, applicationOnly): a WORKFLOW
-- publication backed by a showcase interface. We do NOT restrict on
-- status/visibility here so DRAFT / UNLISTED apps also get a category for when
-- they go public.
--
-- Matching is by keyword over title + description (no hardcoded prod ids/titles
-- → safe in dev & prod). Categories run most-specific first; the
-- `category_id IS NULL` guard makes it first-match-wins AND idempotent (re-runs
-- are a no-op). Apps matching no keyword stay NULL - the publisher classifies
-- them via the now-working CategoryPicker.
--
-- Prod tuning note: before deploy, inspect the real published applications
-- (read-only) and adjust the keyword arrays below if needed.

-- Sales & CRM -------------------------------------------------------------
UPDATE publication.workflow_publications p
   SET category_id = c.id, category_slug = c.slug, category_name = c.name,
       category_icon_slug = c.icon_slug, category_color = c.color
  FROM orchestrator.workflow_categories c
 WHERE c.slug = 'sales-crm'
   AND p.publication_type = 'WORKFLOW' AND p.showcase_interface_id IS NOT NULL
   AND p.category_id IS NULL
   AND (LOWER(p.title) LIKE ANY (ARRAY['%salesforce%','%hubspot%','%pipedrive%','%crm%','%lead%','%prospect%','%pipeline%','%deal%','%sales%'])
     OR LOWER(COALESCE(p.description,'')) LIKE ANY (ARRAY['%salesforce%','%hubspot%','%pipedrive%','%crm%','%lead%','%prospect%','%sales pipeline%','%deal%']));

-- E-commerce --------------------------------------------------------------
UPDATE publication.workflow_publications p
   SET category_id = c.id, category_slug = c.slug, category_name = c.name,
       category_icon_slug = c.icon_slug, category_color = c.color
  FROM orchestrator.workflow_categories c
 WHERE c.slug = 'ecommerce'
   AND p.publication_type = 'WORKFLOW' AND p.showcase_interface_id IS NOT NULL
   AND p.category_id IS NULL
   AND (LOWER(p.title) LIKE ANY (ARRAY['%shopify%','%woocommerce%','%ecommerce%','%e-commerce%','%store%','%product catalog%','%order%','%cart%','%checkout%','%inventory%'])
     OR LOWER(COALESCE(p.description,'')) LIKE ANY (ARRAY['%shopify%','%woocommerce%','%ecommerce%','%e-commerce%','%online store%','%product catalog%','%checkout%','%inventory%']));

-- Finance -----------------------------------------------------------------
UPDATE publication.workflow_publications p
   SET category_id = c.id, category_slug = c.slug, category_name = c.name,
       category_icon_slug = c.icon_slug, category_color = c.color
  FROM orchestrator.workflow_categories c
 WHERE c.slug = 'finance'
   AND p.publication_type = 'WORKFLOW' AND p.showcase_interface_id IS NOT NULL
   AND p.category_id IS NULL
   AND (LOWER(p.title) LIKE ANY (ARRAY['%invoice%','%invoicing%','%accounting%','%expense%','%payroll%','%budget%','%quickbooks%','%billing%','%finance%'])
     OR LOWER(COALESCE(p.description,'')) LIKE ANY (ARRAY['%invoice%','%accounting%','%expense report%','%payroll%','%budget%','%billing%']));

-- Customer Support --------------------------------------------------------
UPDATE publication.workflow_publications p
   SET category_id = c.id, category_slug = c.slug, category_name = c.name,
       category_icon_slug = c.icon_slug, category_color = c.color
  FROM orchestrator.workflow_categories c
 WHERE c.slug = 'customer-support'
   AND p.publication_type = 'WORKFLOW' AND p.showcase_interface_id IS NOT NULL
   AND p.category_id IS NULL
   AND (LOWER(p.title) LIKE ANY (ARRAY['%support%','%ticket%','%helpdesk%','%zendesk%','%intercom%','%customer service%','%faq%','%complaint%'])
     OR LOWER(COALESCE(p.description,'')) LIKE ANY (ARRAY['%support ticket%','%helpdesk%','%zendesk%','%intercom%','%customer service%','%faq%']));

-- Marketing ---------------------------------------------------------------
UPDATE publication.workflow_publications p
   SET category_id = c.id, category_slug = c.slug, category_name = c.name,
       category_icon_slug = c.icon_slug, category_color = c.color
  FROM orchestrator.workflow_categories c
 WHERE c.slug = 'marketing'
   AND p.publication_type = 'WORKFLOW' AND p.showcase_interface_id IS NOT NULL
   AND p.category_id IS NULL
   AND (LOWER(p.title) LIKE ANY (ARRAY['%marketing%','%campaign%','%newsletter%','%mailchimp%','%brevo%','%sendgrid%','%klaviyo%','%seo%','%ads%','%growth%'])
     OR LOWER(COALESCE(p.description,'')) LIKE ANY (ARRAY['%marketing%','%campaign%','%newsletter%','%mailchimp%','%seo%','%advertis%','%growth%']));

-- Monitoring & Alerts -----------------------------------------------------
UPDATE publication.workflow_publications p
   SET category_id = c.id, category_slug = c.slug, category_name = c.name,
       category_icon_slug = c.icon_slug, category_color = c.color
  FROM orchestrator.workflow_categories c
 WHERE c.slug = 'monitoring'
   AND p.publication_type = 'WORKFLOW' AND p.showcase_interface_id IS NOT NULL
   AND p.category_id IS NULL
   AND (LOWER(p.title) LIKE ANY (ARRAY['%monitor%','%monitoring%','%alert%','%uptime%','%status page%','%incident%','%watcher%'])
     OR LOWER(COALESCE(p.description,'')) LIKE ANY (ARRAY['%monitoring%','%alert%','%uptime%','%incident%','%status page%']));

-- Content -----------------------------------------------------------------
UPDATE publication.workflow_publications p
   SET category_id = c.id, category_slug = c.slug, category_name = c.name,
       category_icon_slug = c.icon_slug, category_color = c.color
  FROM orchestrator.workflow_categories c
 WHERE c.slug = 'content'
   AND p.publication_type = 'WORKFLOW' AND p.showcase_interface_id IS NOT NULL
   AND p.category_id IS NULL
   AND (LOWER(p.title) LIKE ANY (ARRAY['%content%','%blog%','%article%','%copywrit%','%writer%','%video%','%image generat%','%caption%','%transcri%'])
     OR LOWER(COALESCE(p.description,'')) LIKE ANY (ARRAY['%content%','%blog post%','%article%','%copywrit%','%video%','%image generat%','%transcri%']));

-- AI & Chatbots -----------------------------------------------------------
UPDATE publication.workflow_publications p
   SET category_id = c.id, category_slug = c.slug, category_name = c.name,
       category_icon_slug = c.icon_slug, category_color = c.color
  FROM orchestrator.workflow_categories c
 WHERE c.slug = 'ai-automation'
   AND p.publication_type = 'WORKFLOW' AND p.showcase_interface_id IS NOT NULL
   AND p.category_id IS NULL
   AND (LOWER(p.title) LIKE ANY (ARRAY['%chatbot%','%chat bot%','%assistant%','%ai agent%','%gpt%','%llm%','%openai%','%anthropic%','%summari%','%classif%'])
     OR LOWER(COALESCE(p.description,'')) LIKE ANY (ARRAY['%chatbot%','%ai assistant%','%ai agent%','%gpt%','%summari%','%classif%']));

-- Data & Analytics --------------------------------------------------------
UPDATE publication.workflow_publications p
   SET category_id = c.id, category_slug = c.slug, category_name = c.name,
       category_icon_slug = c.icon_slug, category_color = c.color
  FROM orchestrator.workflow_categories c
 WHERE c.slug = 'data-analytics'
   AND p.publication_type = 'WORKFLOW' AND p.showcase_interface_id IS NOT NULL
   AND p.category_id IS NULL
   AND (LOWER(p.title) LIKE ANY (ARRAY['%analytics%','%dashboard%','%report%','%kpi%','%metric%','%bigquery%','%etl%','%chart%','%insight%'])
     OR LOWER(COALESCE(p.description,'')) LIKE ANY (ARRAY['%analytics%','%dashboard%','%report%','%kpi%','%data pipeline%','%chart%','%insight%']));

-- Communication -----------------------------------------------------------
UPDATE publication.workflow_publications p
   SET category_id = c.id, category_slug = c.slug, category_name = c.name,
       category_icon_slug = c.icon_slug, category_color = c.color
  FROM orchestrator.workflow_categories c
 WHERE c.slug = 'communication'
   AND p.publication_type = 'WORKFLOW' AND p.showcase_interface_id IS NOT NULL
   AND p.category_id IS NULL
   AND (LOWER(p.title) LIKE ANY (ARRAY['%slack%','%discord%','%telegram%','%whatsapp%','%sms%','%microsoft teams%','%notification%'])
     OR LOWER(COALESCE(p.description,'')) LIKE ANY (ARRAY['%slack%','%discord%','%telegram%','%whatsapp%','%send sms%','%notification%']));

-- Productivity ------------------------------------------------------------
UPDATE publication.workflow_publications p
   SET category_id = c.id, category_slug = c.slug, category_name = c.name,
       category_icon_slug = c.icon_slug, category_color = c.color
  FROM orchestrator.workflow_categories c
 WHERE c.slug = 'productivity'
   AND p.publication_type = 'WORKFLOW' AND p.showcase_interface_id IS NOT NULL
   AND p.category_id IS NULL
   AND (LOWER(p.title) LIKE ANY (ARRAY['%notion%','%calendar%','%todo%','%task manager%','%schedul%','%meeting%','%note%','%document%','%spreadsheet%','%productivity%'])
     OR LOWER(COALESCE(p.description,'')) LIKE ANY (ARRAY['%calendar%','%task manager%','%schedul%','%meeting%','%spreadsheet%','%productivity%']));

-- Automation (generic catch-all - runs last) ------------------------------
UPDATE publication.workflow_publications p
   SET category_id = c.id, category_slug = c.slug, category_name = c.name,
       category_icon_slug = c.icon_slug, category_color = c.color
  FROM orchestrator.workflow_categories c
 WHERE c.slug = 'automation'
   AND p.publication_type = 'WORKFLOW' AND p.showcase_interface_id IS NOT NULL
   AND p.category_id IS NULL
   AND (LOWER(p.title) LIKE ANY (ARRAY['%automat%','%workflow%','%sync%','%integrat%','%webhook%','%zapier%'])
     OR LOWER(COALESCE(p.description,'')) LIKE ANY (ARRAY['%automat%','%workflow%','%sync%','%integrat%','%webhook%']));
