'use client';

import { useEffect, useState } from 'react';
import { Menu, X } from 'lucide-react';
import { DocsNav } from './DocsNav';

/** Mobile (< lg) docs menu: a button that opens the sidebar nav as a drawer. */
export function DocsMobileNav() {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open]);

  return (
    <div className="docs-mobilebar">
      <button
        type="button"
        className="docs-mobile-toggle"
        onClick={() => setOpen(true)}
        aria-label="Open documentation menu"
        aria-expanded={open}
      >
        <Menu className="w-4 h-4" /> Menu
      </button>

      {open ? (
        <>
          <div className="docs-drawer-backdrop" onClick={() => setOpen(false)} aria-hidden="true" />
          <div className="docs-drawer" role="dialog" aria-modal="true" aria-label="Documentation menu">
            <div className="flex justify-end mb-2">
              <button
                type="button"
                onClick={() => setOpen(false)}
                aria-label="Close menu"
                style={{ color: 'var(--text-muted)' }}
              >
                <X className="w-5 h-5" />
              </button>
            </div>
            <DocsNav onNavigate={() => setOpen(false)} />
          </div>
        </>
      ) : null}
    </div>
  );
}
