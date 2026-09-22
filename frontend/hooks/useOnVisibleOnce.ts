import * as React from 'react';

/**
 * Latches true the first time the returned ref's element enters the viewport.
 *
 * The trigger behind anything that must not fetch until it is looked at: the palette's lazy
 * sections, and the data table's media cells (a column of videos would otherwise download every
 * file in the page). What has never been scrolled to fetches nothing, and once revealed it must
 * not un-reveal on the next scroll (that would refetch on every pass and, worse, unmount a list
 * the user is reading). Hence "once" - the latch never flips back.
 *
 * Distinct from `useLazyLoadObserver`, which fires repeatedly to page an already
 * visible list. This one answers "has it been seen at all", that one answers "does it
 * need the next page".
 */
export function useOnVisibleOnce(enabled: boolean = true): [React.RefObject<HTMLDivElement>, boolean] {
  const ref = React.useRef<HTMLDivElement>(null);
  const [seen, setSeen] = React.useState(false);

  React.useEffect(() => {
    if (!enabled || seen) return;
    const element = ref.current;
    if (!element) return;

    // No IntersectionObserver (older embedded webviews, some test environments):
    // reveal immediately rather than leaving the section permanently blank.
    if (typeof IntersectionObserver === 'undefined') {
      setSeen(true);
      return;
    }

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setSeen(true);
          observer.disconnect();
        }
      },
      // Same margin as the pager's observer, so the section starts loading just
      // before it is scrolled into view instead of visibly popping in.
      { threshold: 0, rootMargin: '100px' },
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, [enabled, seen]);

  return [ref, seen];
}

export default useOnVisibleOnce;
