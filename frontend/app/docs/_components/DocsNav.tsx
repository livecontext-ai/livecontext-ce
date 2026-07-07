'use client';

import { useMemo, useState } from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { Search } from 'lucide-react';
import { DOCS_NAV, cleanDocsPathname, isActiveDocPath } from '../_nav';

/**
 * Docs sidebar navigation: a client-side filter ("search") over the IA plus the
 * sectioned link list with active-state highlighting. Reused verbatim inside the
 * mobile drawer (`DocsMobileNav`). Locale-free: `usePathname` from
 * `next/navigation`, never `@/i18n/navigation`.
 */
export function DocsNav({ onNavigate }: { onNavigate?: () => void }) {
  // Normalized: at build time usePathname() is the internal /docs/... route,
  // in the browser it is the clean subdomain URL (see cleanDocsPathname).
  const pathname = cleanDocsPathname(usePathname());
  const [query, setQuery] = useState('');
  const q = query.trim().toLowerCase();

  const sections = useMemo(() => {
    if (!q) return DOCS_NAV;
    return DOCS_NAV.map((section) => ({
      ...section,
      items: section.items.filter(
        (item) => item.title.toLowerCase().includes(q) || section.title.toLowerCase().includes(q),
      ),
    })).filter((section) => section.items.length > 0);
  }, [q]);

  return (
    <nav aria-label="Documentation">
      <div className="relative mb-5">
        <Search
          className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none"
          style={{ color: 'var(--text-muted)' }}
          aria-hidden="true"
        />
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Filter..."
          className="docs-nav-search"
          aria-label="Filter documentation"
        />
      </div>

      <div className="flex flex-col gap-6">
        {sections.map((section) => {
          const Icon = section.icon;
          return (
            <div key={section.title}>
              <div className="docs-nav-section-label">
                <Icon className="w-3.5 h-3.5" aria-hidden="true" />
                {section.title}
              </div>
              <div className="flex flex-col gap-0.5">
                {section.items.map((item) =>
                  item.href ? (
                    <Link
                      key={item.title}
                      href={item.href}
                      onClick={onNavigate}
                      aria-current={isActiveDocPath(pathname, item.href) ? 'page' : undefined}
                      className={`docs-nav-link${isActiveDocPath(pathname, item.href) ? ' is-active' : ''}`}
                    >
                      {item.title}
                    </Link>
                  ) : (
                    <span key={item.title} className="docs-nav-disabled">
                      {item.title}
                      {item.badge ? <span className="docs-nav-badge">{item.badge}</span> : null}
                    </span>
                  ),
                )}
              </div>
            </div>
          );
        })}
        {sections.length === 0 ? (
          <p className="text-sm" style={{ color: 'var(--text-muted)', paddingLeft: '0.625rem' }}>
            No matches.
          </p>
        ) : null}
      </div>
    </nav>
  );
}
