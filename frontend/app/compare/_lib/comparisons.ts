// Content source for the /compare/* SEO pages ("n8n alternative", "Zapier
// alternative", ...). Like every page outside the [locale] tree, this content
// is hardcoded English (see the LandingShell contract in
// components/landing/LandingShell.tsx).
//
// Editorial rules for this file:
//  - Claims about LiveContext must match the landing page and the docs.
//  - Claims about competitors stay factual, hedged and dated (lastUpdated);
//    when a competitor does something well, say so (the "honest" section).
//  - Answers are written to be quotable in isolation: search engines and LLMs
//    lift them verbatim, so each one must stand alone without the page around it.

export type CellState = 'yes' | 'partial' | 'no';

export interface CompareCell {
  state: CellState;
  note: string;
}

export interface CompareRow {
  feature: string;
  livecontext: CompareCell;
  competitor: CompareCell;
}

export interface FaqItem {
  question: string;
  answer: string;
}

export interface SwitchReason {
  title: string;
  description: string;
}

export interface Comparison {
  slug: string;
  competitor: string;
  metaTitle: string;
  metaDescription: string;
  h1: string;
  intro: string;
  verdictCompetitor: string;
  verdictLivecontext: string;
  rows: CompareRow[];
  reasons: SwitchReason[];
  honestTitle: string;
  honest: string[];
  migration: { title: string; description: string }[];
  faq: FaqItem[];
  lastUpdated: string;
}

const LAST_UPDATED = 'July 2026';

const INTEGRATIONS_ANSWER =
  'LiveContext ships 600+ built-in API integrations with more than 14,000 ready-to-call operations, plus a generic HTTP request node, a code node, and custom API definitions for anything not in the catalog.';

const SELF_HOST_ANSWER =
  'Yes. The LiveContext Community Edition is free and self-hosted: one docker compose up on your own server, with the code public on GitHub (livecontext-ai/livecontext-ce). The cloud edition at livecontext.ai adds managed hosting, SAML SSO, workspaces and platform credits.';

export const COMPARISONS: Comparison[] = [
  {
    slug: 'n8n-alternative',
    competitor: 'n8n',
    metaTitle: 'The AI-native n8n alternative',
    metaDescription:
      'The n8n alternative that builds workflows from chat, runs budgeted AI agents and ships them as apps. Cloud or self-hosted, with a free tier.',
    h1: 'The n8n alternative that builds the workflow for you',
    intro:
      'n8n is a solid workflow engine for technical teams who like wiring nodes by hand. LiveContext starts one step earlier: you describe the job in chat, the workflow assembles itself in front of you, AI agents run it under scoped access and hard credit budgets, and the result ships as an app your team can open. Cloud or self-hosted, both with a free option.',
    verdictCompetitor:
      'Choose n8n if you want a mature, code-friendly workflow engine, you are comfortable wiring nodes and writing JavaScript by hand, and AI is an add-on rather than the core of your automations.',
    verdictLivecontext:
      'Choose LiveContext if you want to describe a job in plain language and get a running automation, put AI agents in production with scoped tools and credit budgets they cannot exceed, and ship the result as an app for your team or customers.',
    rows: [
      {
        feature: 'Build automations by chat',
        livecontext: { state: 'yes', note: 'Describe the job; the workflow builds itself in front of you' },
        competitor: { state: 'partial', note: 'AI assistant helps, but node wiring stays manual' },
      },
      {
        feature: 'Visual workflow builder',
        livecontext: { state: 'yes', note: '60+ blocks: branches, loops, parallel fan-out, code, HTTP, files' },
        competitor: { state: 'yes', note: 'Mature node editor aimed at developers' },
      },
      {
        feature: 'AI agents in production',
        livecontext: { state: 'yes', note: 'Per-agent tool scoping, credit budget and full audit trail' },
        competitor: { state: 'partial', note: 'AI nodes exist; guardrails and spend limits are do-it-yourself' },
      },
      {
        feature: 'Ship workflows as apps',
        livecontext: { state: 'yes', note: 'Search pages, dashboards and approval screens on top of the workflow' },
        competitor: { state: 'no', note: 'Forms only; a separate front-end is needed' },
      },
      {
        feature: 'Built-in data tables',
        livecontext: { state: 'yes', note: 'Spreadsheet-style tables your automations create, find and update' },
        competitor: { state: 'no', note: 'Bring your own database' },
      },
      {
        feature: 'Browser-use agent',
        livecontext: { state: 'yes', note: 'An agent that opens real web pages, clicks and extracts' },
        competitor: { state: 'no', note: 'Not built in' },
      },
      {
        feature: 'MCP (Model Context Protocol)',
        livecontext: { state: 'yes', note: 'Use LiveContext as an MCP server from Claude, Cursor and other clients' },
        competitor: { state: 'partial', note: 'Community and beta options' },
      },
      {
        feature: 'Self-hosting',
        livecontext: { state: 'yes', note: 'Free Community Edition, one docker compose up' },
        competitor: { state: 'yes', note: 'Fair-code license, self-hosting supported' },
      },
      {
        feature: 'Marketplace',
        livecontext: { state: 'yes', note: 'Fork the whole stack: workflow, agents, pages and tables together' },
        competitor: { state: 'partial', note: 'Workflow templates' },
      },
    ],
    reasons: [
      {
        title: 'From idea to running automation in one message',
        description:
          'You type what you want done and watch the workflow assemble itself. No blank canvas, no documentation detour: the chat builder places the triggers, branches and integrations, and you refine on the canvas only if you want to.',
      },
      {
        title: 'Agents you can actually put in production',
        description:
          'Every agent gets a scoped set of tools, a credit budget it cannot exceed, and a full audit trail. The workflow feeds it exactly the context it needs, so the same job runs cheaper and nothing happens in a black box.',
      },
      {
        title: 'The whole product, not just the pipeline',
        description:
          'Workflows, AI agents, user-facing apps and data tables live in one platform. With n8n you assemble the rest of the stack yourself; with LiveContext the app your team opens is part of the automation.',
      },
      {
        title: 'Cloud when you want it, self-hosted when you need it',
        description:
          'Start on the managed cloud with SAML SSO, RBAC and workspaces, or run the free Community Edition on your own servers. Same builder, same workflows.',
      },
    ],
    honestTitle: 'Where n8n is the better fit',
    honest: [
      'You want to write and version raw JavaScript or TypeScript inside many nodes and treat automations as code.',
      'You depend on a specific community node that only exists in the n8n ecosystem.',
      'You already operate a large fleet of n8n workflows and the cost of change outweighs the benefits.',
    ],
    migration: [
      {
        title: 'List the jobs, not the nodes',
        description: 'For each n8n workflow, write one sentence: when X happens, do Y. That sentence is the migration plan.',
      },
      {
        title: 'Paste it into LiveContext chat',
        description: 'The builder assembles the workflow in front of you. Adjust nodes on the canvas where your process has special cases.',
      },
      {
        title: 'Run both side by side',
        description: 'Keep n8n live while the LiveContext version runs on real data, compare the runs, then switch the trigger over.',
      },
    ],
    faq: [
      {
        question: 'Is LiveContext a good n8n alternative?',
        answer:
          'Yes, for teams that want AI at the core of their automations. LiveContext builds workflows from a chat message, runs AI agents with scoped tools and hard credit budgets, ships workflows as apps, and can be self-hosted for free like n8n. n8n remains a strong choice for developers who prefer wiring nodes and code by hand.',
      },
      {
        question: 'Can I self-host LiveContext like n8n?',
        answer: SELF_HOST_ANSWER,
      },
      {
        question: 'Can I import my n8n workflows into LiveContext?',
        answer:
          'There is no one-click importer. Most teams rebuild by describing each workflow in chat, which typically takes minutes per workflow, then validate the new version side by side with n8n before switching the trigger.',
      },
      {
        question: 'How does LiveContext pricing compare to n8n?',
        answer:
          'LiveContext cloud has a free tier and credit-based paid plans where every agent gets a hard budget it cannot exceed. n8n cloud is priced per execution. Both offer a free self-hosted edition: n8n under its fair-code license, LiveContext as the Community Edition.',
      },
      {
        question: 'Does LiveContext have enough integrations to replace n8n?',
        answer: INTEGRATIONS_ANSWER,
      },
    ],
    lastUpdated: LAST_UPDATED,
  },
  {
    slug: 'zapier-alternative',
    competitor: 'Zapier',
    metaTitle: 'The AI-native Zapier alternative',
    metaDescription:
      'The Zapier alternative with real branching, budgeted AI agents, built-in apps and tables, and a free self-hosted edition. No per-task pricing.',
    h1: 'The Zapier alternative that does more than move data',
    intro:
      'Zapier made connecting two SaaS tools easy. But once you need branching logic, AI agents, a screen for your team, or predictable pricing at volume, stitching zaps and add-ons together gets expensive and hard to read. LiveContext builds the whole automation from one chat message, runs it with budgeted AI agents, and ships it as an app, in one product you can also self-host.',
    verdictCompetitor:
      'Choose Zapier if you mostly need simple trigger-action links between two SaaS tools, you want the largest possible connector catalog, and per-task pricing fits your volume.',
    verdictLivecontext:
      'Choose LiveContext if you want multi-step automations with real logic built from a chat message, AI agents with scoped tools and hard credit budgets, apps and data tables included rather than sold as add-ons, and the option to self-host.',
    rows: [
      {
        feature: 'Build automations by chat',
        livecontext: { state: 'yes', note: 'Describe the job; the workflow builds itself in front of you' },
        competitor: { state: 'partial', note: 'Copilot drafts zaps; editing stays step-by-step' },
      },
      {
        feature: 'Multi-branch visual workflows',
        livecontext: { state: 'yes', note: 'Branches, loops and parallel fan-out on one readable canvas' },
        competitor: { state: 'partial', note: 'Linear zaps with paths; complex flows get hard to follow' },
      },
      {
        feature: 'AI agents in production',
        livecontext: { state: 'yes', note: 'Per-agent tool scoping, credit budget and full audit trail' },
        competitor: { state: 'partial', note: 'Zapier Agents exist; fine-grained scoping and spend caps are limited' },
      },
      {
        feature: 'Ship workflows as apps',
        livecontext: { state: 'yes', note: 'Included: search pages, dashboards, approval screens' },
        competitor: { state: 'partial', note: 'Interfaces is a separate add-on product' },
      },
      {
        feature: 'Built-in data tables',
        livecontext: { state: 'yes', note: 'Included: tables your automations create, find and update' },
        competitor: { state: 'partial', note: 'Tables is a separate add-on product' },
      },
      {
        feature: 'Browser-use agent',
        livecontext: { state: 'yes', note: 'An agent that opens real web pages, clicks and extracts' },
        competitor: { state: 'no', note: 'Not built in' },
      },
      {
        feature: 'MCP (Model Context Protocol)',
        livecontext: { state: 'yes', note: 'Use LiveContext as an MCP server from Claude, Cursor and other clients' },
        competitor: { state: 'yes', note: 'Zapier MCP exposes its actions to AI clients' },
      },
      {
        feature: 'Self-hosting',
        livecontext: { state: 'yes', note: 'Free Community Edition, one docker compose up' },
        competitor: { state: 'no', note: 'SaaS only' },
      },
      {
        feature: 'Marketplace',
        livecontext: { state: 'yes', note: 'Fork the whole stack: workflow, agents, pages and tables together' },
        competitor: { state: 'partial', note: 'Zap templates' },
      },
    ],
    reasons: [
      {
        title: 'Stop paying per task',
        description:
          'Zapier meters every task, so a busy month means a surprise bill. LiveContext is credit-based with a free tier, and every agent gets a hard budget it cannot exceed, so the spend cap is a setting, not a hope.',
      },
      {
        title: 'One product instead of four add-ons',
        description:
          'Workflows, AI agents, user-facing apps and data tables are all included. The equivalent Zapier stack means zaps plus Interfaces plus Tables plus Agents, each priced and managed separately.',
      },
      {
        title: 'Your data can stay on your servers',
        description:
          'Zapier is SaaS only. LiveContext offers a free self-hosted Community Edition when compliance or data residency requires it, and a managed cloud with SAML SSO and workspaces when it does not.',
      },
      {
        title: 'Automations your team can read',
        description:
          'A 20-step process is one canvas with visible branches and loops, not a chain of zaps scattered across folders. New teammates understand the process by looking at it.',
      },
    ],
    honestTitle: 'Where Zapier is the better fit',
    honest: [
      'You need a long-tail SaaS connector that only Zapier has: its app catalog is the largest on the market.',
      'Non-technical staff only need one-step, trigger-action links between two tools.',
      'You want the vendor with the longest SaaS automation track record and ecosystem.',
    ],
    migration: [
      {
        title: 'Inventory your zaps as sentences',
        description: 'For each zap, write one sentence: when X happens, do Y. Group the ones that belong to the same business process.',
      },
      {
        title: 'Rebuild each process in chat',
        description: 'Paste the sentence into LiveContext. Several related zaps usually collapse into one workflow with branches.',
      },
      {
        title: 'Run both side by side',
        description: 'Keep the zap on while the LiveContext version runs on real data, compare results, then turn the zap off.',
      },
    ],
    faq: [
      {
        question: 'Is LiveContext a good Zapier alternative?',
        answer:
          'Yes, for teams that outgrew simple trigger-action zaps. LiveContext builds multi-step workflows from a chat message, runs AI agents with scoped tools and credit budgets, includes apps and data tables instead of selling them as add-ons, and can be self-hosted for free. Zapier remains a fine choice for one-step links between two SaaS tools.',
      },
      {
        question: 'Can I self-host LiveContext? Zapier cannot be self-hosted.',
        answer: SELF_HOST_ANSWER,
      },
      {
        question: 'Can I import my Zaps into LiveContext?',
        answer:
          'There is no one-click importer. Most teams rebuild by describing each zap in chat, which typically takes minutes, and several related zaps often merge into a single workflow with branches.',
      },
      {
        question: 'How does LiveContext pricing compare to Zapier?',
        answer:
          'Zapier charges per task, so cost grows with volume. LiveContext has a free tier and credit-based plans where every agent gets a hard budget it cannot exceed; the self-hosted Community Edition is free.',
      },
      {
        question: 'Does LiveContext connect to as many apps as Zapier?',
        answer:
          "Zapier's connector catalog is larger. " + INTEGRATIONS_ANSWER + ' In practice this covers most business stacks; check the integrations you need before switching.',
      },
    ],
    lastUpdated: LAST_UPDATED,
  },
  {
    slug: 'make-alternative',
    competitor: 'Make',
    metaTitle: 'The AI-native Make (Integromat) alternative',
    metaDescription:
      'The Make (Integromat) alternative that builds scenarios from chat, runs budgeted AI agents and ships them as apps. Free tier and free self-hosting.',
    h1: 'The Make alternative that builds the scenario for you',
    intro:
      'Make (formerly Integromat) gives power users a deep visual scenario builder, priced per operation. LiveContext starts from the other end: you describe the job in chat and the workflow assembles itself, AI agents run it under scoped access and hard credit budgets, and the result ships as an app. All in one product, cloud or self-hosted.',
    verdictCompetitor:
      'Choose Make if you enjoy assembling detailed scenarios module by module, per-operation pricing fits your volume, and you do not need self-hosting or apps on top of your automations.',
    verdictLivecontext:
      'Choose LiveContext if you want automations built from a plain-language description, AI agents with scoped tools and budgets they cannot exceed, user-facing apps and data tables included, and a free self-hosted edition.',
    rows: [
      {
        feature: 'Build automations by chat',
        livecontext: { state: 'yes', note: 'Describe the job; the workflow builds itself in front of you' },
        competitor: { state: 'partial', note: 'AI assistant helps; module wiring stays manual' },
      },
      {
        feature: 'Visual workflow builder',
        livecontext: { state: 'yes', note: '60+ blocks: branches, loops, parallel fan-out, code, HTTP, files' },
        competitor: { state: 'yes', note: 'Deep scenario builder with routers and iterators' },
      },
      {
        feature: 'AI agents in production',
        livecontext: { state: 'yes', note: 'Per-agent tool scoping, credit budget and full audit trail' },
        competitor: { state: 'partial', note: 'AI agents exist; fine-grained scoping and spend caps are limited' },
      },
      {
        feature: 'Ship workflows as apps',
        livecontext: { state: 'yes', note: 'Search pages, dashboards and approval screens on top of the workflow' },
        competitor: { state: 'no', note: 'Forms only; a separate front-end is needed' },
      },
      {
        feature: 'Built-in data tables',
        livecontext: { state: 'yes', note: 'Spreadsheet-style tables your automations create, find and update' },
        competitor: { state: 'partial', note: 'Data stores, with modest limits' },
      },
      {
        feature: 'Browser-use agent',
        livecontext: { state: 'yes', note: 'An agent that opens real web pages, clicks and extracts' },
        competitor: { state: 'no', note: 'Not built in' },
      },
      {
        feature: 'MCP (Model Context Protocol)',
        livecontext: { state: 'yes', note: 'Use LiveContext as an MCP server from Claude, Cursor and other clients' },
        competitor: { state: 'partial', note: 'Early options' },
      },
      {
        feature: 'Self-hosting',
        livecontext: { state: 'yes', note: 'Free Community Edition, one docker compose up' },
        competitor: { state: 'no', note: 'SaaS only' },
      },
      {
        feature: 'Marketplace',
        livecontext: { state: 'yes', note: 'Fork the whole stack: workflow, agents, pages and tables together' },
        competitor: { state: 'partial', note: 'Scenario templates' },
      },
    ],
    reasons: [
      {
        title: 'Describe it instead of assembling it',
        description:
          'A Make scenario is built module by module. In LiveContext you type what you want done and the workflow assembles itself; the canvas is for refining, not for starting from zero.',
      },
      {
        title: 'Predictable spend, capped per agent',
        description:
          'Per-operation pricing punishes chatty scenarios. LiveContext is credit-based with a free tier, and each agent has a hard budget it cannot exceed.',
      },
      {
        title: 'Apps and tables are part of the product',
        description:
          'The screen your team uses and the data your automation reads and writes live in the same platform as the workflow. No separate front-end, no external database for simple operational data.',
      },
      {
        title: 'Self-host when compliance asks for it',
        description:
          'Make is SaaS only. The LiveContext Community Edition runs on your own servers for free, with the same builder as the cloud.',
      },
    ],
    honestTitle: 'Where Make is the better fit',
    honest: [
      'You like building intricate scenarios by hand and the router/iterator model fits how you think.',
      'You rely on a specific Make app module that has no LiveContext equivalent.',
      'Your volumes are small and per-operation pricing stays cheap for you.',
    ],
    migration: [
      {
        title: 'Write each scenario as a sentence',
        description: 'For every Make scenario: when X happens, do Y. Routers become branches, iterators become loops or splits.',
      },
      {
        title: 'Rebuild it in chat',
        description: 'Paste the sentence into LiveContext and watch the workflow assemble. Fine-tune branching and error paths on the canvas.',
      },
      {
        title: 'Run both side by side',
        description: 'Keep the Make scenario on while the LiveContext version runs on real data, compare, then switch the trigger.',
      },
    ],
    faq: [
      {
        question: 'Is LiveContext a good Make (Integromat) alternative?',
        answer:
          'Yes, for teams that want AI-native automation. LiveContext builds workflows from a chat message, runs AI agents with scoped tools and hard credit budgets, ships workflows as apps with built-in tables, and offers a free self-hosted edition. Make remains a capable visual builder for hands-on scenario assembly.',
      },
      {
        question: 'Can I self-host LiveContext? Make cannot be self-hosted.',
        answer: SELF_HOST_ANSWER,
      },
      {
        question: 'Can I import my Make scenarios into LiveContext?',
        answer:
          'There is no one-click importer. Most teams rebuild by describing each scenario in chat, which typically takes minutes per scenario, then validate side by side before switching triggers.',
      },
      {
        question: 'How does LiveContext pricing compare to Make?',
        answer:
          'Make charges per operation, so busy scenarios cost more each month. LiveContext has a free tier and credit-based plans where every agent gets a hard budget it cannot exceed; the self-hosted Community Edition is free.',
      },
      {
        question: 'Does LiveContext have enough integrations to replace Make?',
        answer: INTEGRATIONS_ANSWER,
      },
    ],
    lastUpdated: LAST_UPDATED,
  },
];

export function getComparison(slug: string): Comparison | undefined {
  return COMPARISONS.find((c) => c.slug === slug);
}
