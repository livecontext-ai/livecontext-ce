'use client';

import { useId, useMemo, useState } from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { Search } from 'lucide-react';
import { cleanDocsPathname, filterDocsNav, isActiveDocPath } from '../_nav';

/**
 * Docs sidebar navigation: a client-side filter ("search") over the IA plus the
 * sectioned link list with active-state highlighting. Reused verbatim inside the
 * mobile drawer (`DocsMobileNav`). Locale-free: `usePathname` from
 * `next/navigation`, never `@/i18n/navigation`.
 *
 * The filter matches page titles, section titles AND each page's keywords (so
 * "webhook" finds Triggers and REST API), and announces the match count through
 * a polite live region.
 */
export function DocsNav({ onNavigate }: { onNavigate?: () => void }) {
  // Normalized: at build time usePathname() is the internal /docs/... route,
  // in the browser it is the clean subdomain URL (see cleanDocsPathname).
  const pathname = cleanDocsPathname(usePathname());
  // DocsNav renders twice while the mobile drawer is open (hidden sidebar + drawer),
  // so every id is scoped with useId() to stay unique (WCAG 4.1.1) and keep each
  // aria reference pointing at its own copy.
  const uid = useId();
  const statusId = `${uid}-filter-status`;
  const [query, setQuery] = useState('');
  const q = query.trim();

  const sections = useMemo(() => filterDocsNav(q), [q]);
  const matchCount = sections.reduce((n, s) => n + s.items.length, 0);

  return (
    <nav aria-label="Documentation">
      <div className="relative mb-5" role="search">
        <Search
          className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none"
          style={{ color: 'var(--text-muted)' }}
          aria-hidden="true"
        />
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Filter pages..."
          className="docs-nav-search"
          aria-label="Filter documentation"
          aria-describedby={statusId}
        />
      </div>
      <p id={statusId} className="sr-only" role="status" aria-live="polite">
        {q ? `${matchCount} ${matchCount === 1 ? 'page matches' : 'pages match'}` : ''}
      </p>

      <div className="flex flex-col gap-6">
        {sections.map((section) => {
          const Icon = section.icon;
          const labelId = `${uid}-${section.title.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`;
          return (
            <div key={section.title}>
              <p id={labelId} className="docs-nav-section-label">
                <Icon className="w-3.5 h-3.5" aria-hidden="true" />
                {section.title}
              </p>
              <ul className="flex flex-col gap-0.5" aria-labelledby={labelId}>
                {section.items.map((item) => (
                  <li key={item.title}>
                    {item.href ? (
                      <Link
                        href={item.href}
                        onClick={onNavigate}
                        aria-current={isActiveDocPath(pathname, item.href) ? 'page' : undefined}
                        className={`docs-nav-link${isActiveDocPath(pathname, item.href) ? ' is-active' : ''}`}
                      >
                        {item.title}
                      </Link>
                    ) : (
                      <span className="docs-nav-disabled">
                        {item.title}
                        {item.badge ? <span className="docs-nav-badge">{item.badge}</span> : null}
                      </span>
                    )}
                  </li>
                ))}
              </ul>
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
