-- V300: Seed marketplace categories into orchestrator.workflow_categories.
--
-- Context: the table (V1) was created but NEVER seeded, and POST /api/categories
-- is admin-token gated - so the share-as-application CategoryPicker
-- (frontend/components/marketplace/CategoryPicker.tsx → orchestratorApi.getCategories)
-- rendered an empty select everywhere (ShareWorkflowModal, PublishResourceModal,
-- PublishAgentModal). This seeds a curated marketplace taxonomy so categories are
-- selectable, the marketplace is filterable by category, and the onboarding
-- "suggested applications" engine can match published applications by category.
--
-- The slugs mirror the onboarding enums (interests / useCases / profession in
-- frontend/app/[locale]/onboarding/page.tsx) so OnboardingCategoryMapper can map
-- a user's onboarding choices straight onto these slugs.
--
-- FIXED UUIDs (deterministic across environments) keep ids stable for any code
-- that references a category by id; the companion backfill (V301) joins by slug,
-- so it works even if an environment already had these slugs under other ids
-- (ON CONFLICT DO NOTHING keeps the pre-existing rows). icon_slug is kebab-case
-- → resolved to a Lucide icon by getCategoryIcon().
-- Idempotent: ON CONFLICT (slug) DO NOTHING (slug is UNIQUE since V1).

INSERT INTO orchestrator.workflow_categories
    (id, slug, name, description, icon_slug, color, display_order, is_active, created_at, updated_at)
VALUES
    ('a0000000-0000-4000-8000-000000000001', 'automation',       'Automation',          'Automate repetitive tasks and connect your tools.',        'zap',            '#6366f1', 10, TRUE, now(), now()),
    ('a0000000-0000-4000-8000-000000000002', 'ai-automation',    'AI & Chatbots',       'AI assistants, chatbots and intelligent agents.',          'bot',            '#8b5cf6', 20, TRUE, now(), now()),
    ('a0000000-0000-4000-8000-000000000003', 'data-analytics',   'Data & Analytics',    'Reporting, dashboards and data pipelines.',                'bar-chart-3',    '#0ea5e9', 30, TRUE, now(), now()),
    ('a0000000-0000-4000-8000-000000000004', 'productivity',     'Productivity',        'Personal and team productivity tools.',                    'check-square',   '#10b981', 40, TRUE, now(), now()),
    ('a0000000-0000-4000-8000-000000000005', 'sales-crm',        'Sales & CRM',         'Lead generation, CRM and sales pipelines.',                'handshake',      '#f59e0b', 50, TRUE, now(), now()),
    ('a0000000-0000-4000-8000-000000000006', 'marketing',        'Marketing',           'Campaigns, email marketing and growth.',                   'megaphone',      '#ec4899', 60, TRUE, now(), now()),
    ('a0000000-0000-4000-8000-000000000007', 'ecommerce',        'E-commerce',          'Online stores, orders and product catalogs.',              'shopping-bag',   '#f43f5e', 70, TRUE, now(), now()),
    ('a0000000-0000-4000-8000-000000000008', 'customer-support', 'Customer Support',    'Helpdesks, ticketing and customer care.',                  'life-buoy',      '#14b8a6', 80, TRUE, now(), now()),
    ('a0000000-0000-4000-8000-000000000009', 'content',          'Content',             'Content generation, writing and media.',                   'pen-tool',       '#a855f7', 90, TRUE, now(), now()),
    ('a0000000-0000-4000-8000-00000000000a', 'monitoring',       'Monitoring & Alerts', 'Monitoring, alerts and notifications.',                     'bell',           '#ef4444', 100, TRUE, now(), now()),
    ('a0000000-0000-4000-8000-00000000000b', 'finance',          'Finance',             'Invoicing, accounting and expense management.',            'wallet',         '#22c55e', 110, TRUE, now(), now()),
    ('a0000000-0000-4000-8000-00000000000c', 'communication',    'Communication',       'Messaging, email and team communication.',                 'message-circle', '#3b82f6', 120, TRUE, now(), now())
ON CONFLICT (slug) DO NOTHING;
