import * as React from 'react';

/**
 * The studio's ground: one fixed ambient scene behind every studio layout.
 *
 * <p>It replaces the old "darkroom" switch. There is no choice to make and nothing to remember,
 * so the scene is the same on every load, in every layout (empty, narrow, with a thread), and it
 * is pure CSS: no state, no effect, nothing that can differ between the server markup and the
 * first client render.
 *
 * <p>The scene sits in its OWN layer rather than on the scroller. One studio layout is itself the
 * scroll container, and a layer placed inside a scroller covers only the first screenful and then
 * scrolls away with the content. `isolate` gives the layer a stacking context of its own, so its
 * `-z-10` stays behind the content here without dropping under the page around the studio.
 *
 * <p>Decorative only: `aria-hidden`, no pointer events, and every movement stops under
 * `prefers-reduced-motion`. See the `.studio-ambient` block in globals.css for the layers.
 */
export function StudioBackdrop({ children }: { children: React.ReactNode }) {
  return (
    <div className="relative isolate flex min-h-0 flex-1 flex-col">
      <div className="studio-ambient" aria-hidden="true" data-testid="studio-ambient">
        <span className="studio-ambient-orb studio-ambient-orb-a" />
        <span className="studio-ambient-orb studio-ambient-orb-b" />
        <span className="studio-ambient-orb studio-ambient-orb-c" />
        <span className="studio-ambient-grid" />
        <span className="studio-ambient-grain" />
      </div>
      {children}
    </div>
  );
}
