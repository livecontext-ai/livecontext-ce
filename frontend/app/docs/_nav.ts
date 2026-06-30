import type { LucideIcon } from 'lucide-react';
import { Sparkles, Workflow, Bot, Database, Store, BookOpen } from 'lucide-react';

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
      { title: 'Overview', href: '/' },
      { title: 'Getting started', href: '/getting-started' },
      { title: 'Core concepts', href: '/concepts' },
    ],
  },
  {
    title: 'Build',
    icon: Workflow,
    items: [
      { title: 'Chat', href: '/chat' },
      { title: 'Workflows', href: '/workflows' },
      { title: 'Node reference', href: '/nodes' },
      { title: 'Triggers', href: '/triggers' },
      { title: 'Interfaces & apps', href: '/interfaces' },
    ],
  },
  {
    title: 'AI',
    icon: Bot,
    items: [
      { title: 'Agents', href: '/agents' },
    ],
  },
  {
    title: 'Data',
    icon: Database,
    items: [
      { title: 'Tables & data', href: '/tables' },
      { title: 'Integrations', href: '/integrations' },
    ],
  },
  {
    title: 'Share & host',
    icon: Store,
    items: [
      { title: 'Marketplace', href: '/marketplace' },
      { title: 'Self-hosting', href: '/self-host' },
    ],
  },
  {
    title: 'Reference',
    icon: BookOpen,
    items: [
      { title: 'Access, roles & SSO', badge: 'Soon' },
      { title: 'Expressions & variables', badge: 'Soon' },
      { title: 'REST API & webhooks', badge: 'Soon' },
    ],
  },
];

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
