'use client';

import { useEffect, useId, useRef, useState } from 'react';
import { Menu, X } from 'lucide-react';
import { DocsNav } from './DocsNav';
import { DOCS_ARTICLE_ID } from './docsIds';

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Mobile (< lg) docs menu: a button that opens the sidebar nav as a modal drawer.
 *
 * Behaves as a real modal dialog: focus moves into the drawer on open, Tab and
 * Shift+Tab stay inside it, Escape closes it, and focus returns to the toggle
 * button on close (WCAG 2.4.3 focus order, 2.1.2 no keyboard trap outside).
 * When the drawer closes because a link was followed, focus goes to the article
 * of the new page instead, so keyboard users land on the content they asked for.
 */
export function DocsMobileNav() {
  const [open, setOpen] = useState(false);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const drawerRef = useRef<HTMLDivElement>(null);
  const wasOpen = useRef(false);
  const navigated = useRef(false);
  const drawerId = useId();

  useEffect(() => {
    if (!open) {
      // Only restore focus after a real close, never on the first render.
      if (wasOpen.current) {
        const article = navigated.current ? document.getElementById(DOCS_ARTICLE_ID) : null;
        (article ?? toggleRef.current)?.focus();
      }
      wasOpen.current = false;
      navigated.current = false;
      return;
    }
    wasOpen.current = true;
    const drawer = drawerRef.current;
    drawer?.querySelector<HTMLElement>(FOCUSABLE)?.focus();

    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false);
        return;
      }
      if (e.key !== 'Tab' || !drawer) return;
      const items = Array.from(drawer.querySelectorAll<HTMLElement>(FOCUSABLE));
      if (items.length === 0) return;
      const first = items[0];
      const last = items[items.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKey);
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = previousOverflow;
    };
  }, [open]);

  return (
    <div className="docs-mobilebar">
      <button
        ref={toggleRef}
        type="button"
        className="docs-mobile-toggle"
        onClick={() => setOpen(true)}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? drawerId : undefined}
      >
        <Menu className="w-4 h-4" aria-hidden="true" /> Menu
      </button>

      {open ? (
        <>
          <div className="docs-drawer-backdrop" onClick={() => setOpen(false)} aria-hidden="true" />
          <div
            ref={drawerRef}
            id={drawerId}
            className="docs-drawer"
            role="dialog"
            aria-modal="true"
            aria-label="Documentation menu"
          >
            <div className="flex justify-end mb-2">
              <button
                type="button"
                className="docs-drawer-close"
                onClick={() => setOpen(false)}
                aria-label="Close menu"
              >
                <X className="w-5 h-5" aria-hidden="true" />
              </button>
            </div>
            <DocsNav
              onNavigate={() => {
                navigated.current = true;
                setOpen(false);
              }}
            />
          </div>
        </>
      ) : null}
    </div>
  );
}
