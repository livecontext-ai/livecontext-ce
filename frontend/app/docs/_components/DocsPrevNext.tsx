'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { ArrowLeft, ArrowRight } from 'lucide-react';
import { getAdjacentPages } from '../_nav';

/** Previous / next page links derived from the docs IA and the current path. */
export function DocsPrevNext() {
  const pathname = usePathname() || '/docs';
  const { prev, next } = getAdjacentPages(pathname);
  if (!prev && !next) return null;

  return (
    <nav className="docs-prevnext" aria-label="Pagination">
      {prev ? (
        <Link href={prev.href} className="docs-prevnext-link">
          <span className="docs-prevnext-dir">
            <ArrowLeft className="w-3 h-3 inline -mt-0.5 mr-1" />
            Previous
          </span>
          <span className="docs-prevnext-title">{prev.title}</span>
        </Link>
      ) : (
        <span />
      )}
      {next ? (
        <Link href={next.href} className="docs-prevnext-link is-next">
          <span className="docs-prevnext-dir">
            Next
            <ArrowRight className="w-3 h-3 inline -mt-0.5 ml-1" />
          </span>
          <span className="docs-prevnext-title">{next.title}</span>
        </Link>
      ) : (
        <span />
      )}
    </nav>
  );
}
