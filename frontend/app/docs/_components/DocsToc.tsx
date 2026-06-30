'use client';

import { useEffect, useState } from 'react';
import { usePathname } from 'next/navigation';

interface Heading {
  id: string;
  text: string;
  level: 2 | 3;
}

export function slugify(text: string): string {
  return (
    text
      .toLowerCase()
      .replace(/[^\w\s-]/g, '')
      .trim()
      .replace(/\s+/g, '-')
      .slice(0, 60) || 'section'
  );
}

/**
 * In-page "On this page" table of contents. Scans `.docs-prose h2, h3` after the
 * page mounts, assigns ids to headings that lack one, and scroll-spies the active
 * section. Re-scans on route change (the docs layout - and this component -
 * persists across client navigations).
 */
export function DocsToc() {
  const pathname = usePathname();
  const [headings, setHeadings] = useState<Heading[]>([]);
  const [activeId, setActiveId] = useState<string>('');

  useEffect(() => {
    const root = document.querySelector('.docs-prose');
    if (!root) {
      setHeadings([]);
      return;
    }

    const els = Array.from(root.querySelectorAll('h2, h3')) as HTMLElement[];
    const used = new Set<string>();
    const list: Heading[] = els.map((el) => {
      let id = el.id;
      if (!id) {
        const base = slugify(el.textContent || '');
        let unique = base;
        let n = 1;
        while (used.has(unique)) unique = `${base}-${n++}`;
        id = unique;
        el.id = id;
      }
      used.add(id);
      return { id, text: el.textContent || '', level: el.tagName === 'H3' ? 3 : 2 };
    });
    setHeadings(list);

    if (list.length === 0) return;

    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries
          .filter((e) => e.isIntersecting)
          .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
        if (visible[0]) setActiveId((visible[0].target as HTMLElement).id);
      },
      { rootMargin: '-80px 0px -70% 0px', threshold: 0 },
    );
    els.forEach((el) => observer.observe(el));
    return () => observer.disconnect();
  }, [pathname]);

  if (headings.length < 2) return null;

  return (
    <nav aria-label="On this page">
      <p className="docs-toc-label">On this page</p>
      <div className="flex flex-col">
        {headings.map((h) => (
          <a
            key={h.id}
            href={`#${h.id}`}
            className={`docs-toc-link${h.level === 3 ? ' is-sub' : ''}${activeId === h.id ? ' is-active' : ''}`}
          >
            {h.text}
          </a>
        ))}
      </div>
    </nav>
  );
}
