'use client';

import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';

/**
 * The wires between the tiles of a studio card.
 *
 * <p>The section shows what a run produced: a photo in, several files out. Laid out as a
 * grid of pictures that reads as a moodboard, which is the opposite of the claim. The wires
 * are what make it read as ONE workflow: they are drawn with the same curve the builder
 * draws its edges with, from the tile that went in to each tile that came out.
 *
 * <p>Measured rather than hard-coded. The tiles wrap, scale and reflow between a phone and
 * a desktop, and a fixed SVG would be wrong at every width but one. Children mark
 * themselves with `data-node="<id>"`; this measures them against the stage and redraws on
 * resize. Before the first measurement, and with JavaScript off, the SVG is simply empty:
 * the tiles are the content, the wires are the commentary.
 */
export type Wire = { from: string; to: string };

type Path = { id: string; d: string; x1: number; y1: number; x2: number; y2: number };

export default function StudioWires({ wires, children, className = '' }: { wires: readonly Wire[]; children: ReactNode; className?: string }) {
  const stageRef = useRef<HTMLDivElement>(null);
  const [paths, setPaths] = useState<Path[]>([]);
  const [box, setBox] = useState({ width: 0, height: 0 });

  const measure = useCallback(() => {
    const stage = stageRef.current;
    if (!stage) return;
    const origin = stage.getBoundingClientRect();
    setBox({ width: origin.width, height: origin.height });
    const rectOf = (id: string) => {
      const node = stage.querySelector(`[data-node="${id}"]`);
      if (!node) return null;
      const rect = node.getBoundingClientRect();
      return { left: rect.left - origin.left, top: rect.top - origin.top, width: rect.width, height: rect.height };
    };
    const next: Path[] = [];
    for (const wire of wires) {
      const a = rectOf(wire.from);
      const b = rectOf(wire.to);
      if (!a || !b) continue;
      // Leave from the side that faces the target: sideways when the target is clearly to
      // the right, downwards when it sits underneath. A wire that leaves the wrong edge
      // crosses its own tile and reads as a mistake.
      const sideways = b.left > a.left + a.width - 4;
      const x1 = sideways ? a.left + a.width : a.left + a.width / 2;
      const y1 = sideways ? a.top + a.height / 2 : a.top + a.height;
      const x2 = sideways ? b.left : b.left + b.width / 2;
      const y2 = sideways ? b.top + b.height / 2 : b.top;
      const bend = sideways ? Math.max(24, (x2 - x1) / 2) : Math.max(18, (y2 - y1) / 2);
      const d = sideways
        ? `M ${x1} ${y1} C ${x1 + bend} ${y1}, ${x2 - bend} ${y2}, ${x2} ${y2}`
        : `M ${x1} ${y1} C ${x1} ${y1 + bend}, ${x2} ${y2 - bend}, ${x2} ${y2}`;
      next.push({ id: `${wire.from}-${wire.to}`, d, x1, y1, x2, y2 });
    }
    setPaths(next);
  }, [wires]);

  useEffect(() => {
    measure();
    const stage = stageRef.current;
    if (!stage || typeof ResizeObserver === 'undefined') return;
    // Every tile carries an image, so the layout settles only once they have loaded.
    const observer = new ResizeObserver(measure);
    observer.observe(stage);
    for (const node of stage.querySelectorAll('[data-node]')) observer.observe(node);
    return () => observer.disconnect();
  }, [measure]);

  return (
    <>
    <style>{WIRE_STYLE}</style>
    {/* The links are also written to the DOM: they are the contract of the card (this came
        from that), they survive with JavaScript off, and a test can check that every wire
        has both ends without rendering an SVG. */}
    <div ref={stageRef} data-wires={JSON.stringify(wires)} className={`relative ${className}`}>
      <svg
        className="pointer-events-none absolute inset-0 z-0"
        width={box.width || undefined}
        height={box.height || undefined}
        viewBox={box.width ? `0 0 ${box.width} ${box.height}` : undefined}
        aria-hidden="true"
      >
        {paths.map((path) => (
          <g key={path.id} className="studio-wire">
            <path d={path.d} fill="none" />
            <circle cx={path.x1} cy={path.y1} r="3.5" />
            <circle cx={path.x2} cy={path.y2} r="3.5" />
          </g>
        ))}
      </svg>
      {children}
    </div>
    </>
  );
}

/**
 * Light, but visible. At 38% on a tinted stage the wire disappeared and the tiles read as a
 * collage of unrelated pictures, which is the one thing these sections must not say.
 */
const WIRE_STYLE = `
.studio-wire path{stroke:color-mix(in srgb,var(--text-muted) 72%,transparent);stroke-width:1.75px}
.studio-wire circle{fill:color-mix(in srgb,var(--text-muted) 85%,transparent)}
`;
