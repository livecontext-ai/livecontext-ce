import type { LucideIcon } from 'lucide-react';
import { Sparkles, Workflow, Bot, Database, Users, Store, CreditCard, BookOpen } from 'lucide-react';

// Single source of truth for the docs information architecture.
// Consumed by: the sidebar (`DocsNav`), the mobile drawer, the in-page prev/next
// (`DocsPrevNext`), and `app/sitemap.ts`. Add or reorder pages HERE only.
//
// English-only: the whole `/docs` surface lives OUTSIDE `app/[locale]/`, so it has
// no next-intl context - never import `@/i18n/navigation` here or in any consumer.
//
// Hrefs are CLEAN (`/`, `/agents`, ...): the docs are the home of
// docs.livecontext.ai, where the proxy serves these clean paths off the `/docs`
// routes. On the apex, `/docs/*` 308-redirects to the subdomain, so these clean
// links only ever render there.

export interface DocsNavItem {
  title: string;
  /** Absolute app path. `undefined` for a roadmap stub (rendered muted, no link). */
  href?: string;
  /** Short label shown muted next to a not-yet-written page. */
  badge?: string;
  /** Extra search terms for the sidebar filter (topics the page covers that are not in its title). */
  keywords?: string[];
}

export interface DocsNavSection {
  title: string;
  icon: LucideIcon;
  items: DocsNavItem[];
}

export const DOCS_NAV: DocsNavSection[] = [
  {
    title: 'Get started',
    icon: Sparkles,
    items: [
      { title: 'Overview', href: '/', keywords: ['introduction', 'what is', 'cloud', 'community edition'] },
      { title: 'Getting started', href: '/getting-started', keywords: ['quickstart', 'first workflow', 'sign up', 'onboarding', 'tutorial'] },
      { title: 'Core concepts', href: '/concepts', keywords: ['mental model', 'run', 'node', 'credit', 'epoch'] },
      { title: 'Tour of the workspace', href: '/workspace', keywords: ['navigation', 'sidebar', 'projects', 'folders', 'favorites', 'messages', 'mobile'] },
      { title: 'Glossary', href: '/glossary', keywords: ['definitions', 'terms', 'vocabulary'] },
    ],
  },
  {
    title: 'Build',
    icon: Workflow,
    items: [
      { title: 'Chat', href: '/chat', keywords: ['assistant', 'conversation', 'attachments', 'composer', 'ask user'] },
      { title: 'Workflows', href: '/workflows', keywords: ['canvas', 'builder', 'fork', 'merge', 'loop', 'versions', 'pin'] },
      { title: 'Node reference', href: '/nodes', keywords: ['decision', 'switch', 'split', 'code', 'http request', 'sub-workflow', 'media', 'email', 'ssh', 'database'] },
      { title: 'Triggers', href: '/triggers', keywords: ['webhook', 'schedule', 'cron', 'form', 'manual', 'error trigger'] },
      { title: 'Interfaces & apps', href: '/interfaces', keywords: ['html', 'page', 'iframe', 'application', 'js_template'] },
      { title: 'Runs & execution', href: '/runs', keywords: ['history', 'epoch', 'step by step', 'approval', 'cancel', 're-run', 'debug'] },
    ],
  },
  {
    title: 'AI',
    icon: Bot,
    items: [
      { title: 'Agents', href: '/agents', keywords: ['tools', 'budget', 'memory', 'delegation', 'stop reason', 'claude code', 'codex'] },
      { title: 'Models & providers', href: '/models', keywords: ['llm', 'openai', 'anthropic', 'api key', 'own key', 'byok', 'reasoning'] },
      { title: 'Studio', href: '/studio', keywords: ['image', 'video', 'audio', 'music', 'voice', 'generate', 'media'] },
      { title: 'Browser Agent', href: '/browser-agent', keywords: ['web', 'scraping', 'navigation', 'computer use'] },
      { title: 'Skills', href: '/skills', keywords: ['instructions', 'playbook', 'reusable'] },
    ],
  },
  {
    title: 'Data',
    icon: Database,
    items: [
      { title: 'Tables & data', href: '/tables', keywords: ['spreadsheet', 'rows', 'crud', 'vector', 'semantic search', 'import', 'csv'] },
      { title: 'Integrations', href: '/integrations', keywords: ['api', 'oauth', 'credentials', 'connect', 'custom api', 'mcp'] },
      { title: 'Files & storage', href: '/files', keywords: ['upload', 'download', 'fileref', 'quota', 's3'] },
    ],
  },
  {
    title: 'Work & collaborate',
    icon: Users,
    items: [
      { title: 'Tasks & board', href: '/board', keywords: ['kanban', 'todo', 'assign'] },
      { title: 'Agenda', href: '/agenda', keywords: ['calendar', 'schedule', 'planning'] },
      { title: 'Notifications', href: '/notifications', keywords: ['bell', 'inbox', 'alerts', 'activity'] },
      { title: 'Chat channels', href: '/channels', keywords: ['telegram', 'slack', 'discord', 'whatsapp', 'teams'] },
    ],
  },
  {
    title: 'Share & host',
    icon: Store,
    items: [
      { title: 'Marketplace', href: '/marketplace', keywords: ['publish', 'fork', 'template', 'acquire'] },
      { title: 'Public access & sharing', href: '/public-access', keywords: ['share link', 'public url', 'endpoint', 'token', 'embed'] },
      { title: 'Organizations & roles', href: '/organizations', keywords: ['team', 'members', 'invite', 'rbac', 'sso', 'saml'] },
      { title: 'Self-hosting', href: '/self-host', keywords: ['community edition', 'ce', 'docker', 'install', 'cloud link', 'update'] },
      { title: 'Administration', href: '/admin', keywords: ['admin', 'instance', 'platform credentials', 'version'] },
    ],
  },
  {
    title: 'Account & billing',
    icon: CreditCard,
    items: [
      { title: 'Account & settings', href: '/account', keywords: ['profile', 'preferences', 'language', 'delete account', 'settings'] },
      { title: 'Plans & billing', href: '/billing', keywords: ['pricing', 'credits', 'subscription', 'free plan', 'top-up', 'invoice'] },
    ],
  },
  {
    title: 'Reference',
    icon: BookOpen,
    items: [
      { title: 'Expressions & variables', href: '/expressions', keywords: ['template', 'spel', 'functions', 'variables', 'secrets'] },
      { title: 'REST API & webhooks', href: '/rest-api', keywords: ['http', 'api key', 'endpoint', 'webhook signature'] },
      { title: 'MCP server', href: '/mcp-server', keywords: ['model context protocol', 'claude desktop', 'cursor', 'external client', 'lc_live'] },
    ],
  },
];

/**
 * Sidebar filter: keep the items whose title, section title, or keywords contain
 * the query (case-insensitive). An empty query returns the full IA. Pure helper,
 * unit-tested and used by `DocsNav`.
 */
export function filterDocsNav(query: string, nav: DocsNavSection[] = DOCS_NAV): DocsNavSection[] {
  const q = query.trim().toLowerCase();
  if (!q) return nav;
  return nav
    .map((section) => ({
      ...section,
      items: section.items.filter(
        (item) =>
          item.title.toLowerCase().includes(q) ||
          section.title.toLowerCase().includes(q) ||
          (item.keywords ?? []).some((k) => k.toLowerCase().includes(q)),
      ),
    }))
    .filter((section) => section.items.length > 0);
}

/** A page with a real route, plus the section it belongs to. */
export interface DocsPage {
  title: string;
  href: string;
  section: string;
}

/** Flat, ordered list of live docs pages (excludes roadmap stubs). */
export const DOCS_PAGES: DocsPage[] = DOCS_NAV.flatMap((section) =>
  section.items
    .filter((item): item is DocsNavItem & { href: string } => Boolean(item.href))
    .map((item) => ({ title: item.title, href: item.href, section: section.title })),
);

/** Previous / next page in reading order, for the in-page footer nav. */
export function getAdjacentPages(href: string): { prev: DocsPage | null; next: DocsPage | null } {
  const index = DOCS_PAGES.findIndex((page) => page.href === href);
  if (index === -1) return { prev: null, next: null };
  return {
    prev: index > 0 ? DOCS_PAGES[index - 1] : null,
    next: index < DOCS_PAGES.length - 1 ? DOCS_PAGES[index + 1] : null,
  };
}

/**
 * True when `href` is the active page for `pathname` (exact match, or a parent
 * of the current path). `/` (the Overview) matches ONLY exactly, so it does not
 * stay highlighted on every sub-page. Pure helper - unit-tested and reused by the
 * sidebar nav (`DocsNav`).
 */
export function isActiveDocPath(pathname: string | null | undefined, href: string): boolean {
  if (!pathname) return false;
  if (href === '/') return pathname === '/';
  return pathname === href || pathname.startsWith(href + '/');
}

/**
 * Normalize a pathname to the clean docs IA form used by `DOCS_PAGES` hrefs
 * (`/docs/agents` and `/agents` both become `/agents`; `/docs` becomes `/`).
 *
 * Needed because the docs routes live at `/docs/*` but are SERVED at clean
 * paths on the docs subdomain via a middleware rewrite: at build time (SSG)
 * `usePathname()` reports the internal `/docs/...` route, while in the browser
 * it reports the clean URL. Components that render pathname-dependent markup
 * (sidebar active state, prev/next) MUST normalize through this helper or the
 * server HTML and the client render disagree - a React #418 hydration mismatch
 * on every docs page.
 */
export function cleanDocsPathname(pathname: string | null | undefined): string {
  if (!pathname) return '/';
  if (pathname === '/docs') return '/';
  if (pathname.startsWith('/docs/')) return pathname.slice('/docs'.length);
  return pathname;
}
