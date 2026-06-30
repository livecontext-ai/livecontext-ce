import { useId, useMemo } from 'react';

/**
 * React.useId, sanitized for SVG IRI fragment references - `url(#…)` chokes
 * on the ":" delimiters in raw useId output.
 *
 * Used to give each ReactFlow <Background> a per-instance pattern id: without
 * one, every canvas mounted without an explicit ReactFlow id shares
 * `pattern-1`, and the first mounted instance's viewport transform paints
 * every other canvas's dots grid (sub-pixel radius after its fitView → the
 * grid visually disappears on the active canvas).
 */
export function useSvgSafeId(): string {
  const raw = useId();
  return useMemo(() => raw.replace(/[^a-zA-Z0-9_-]/g, ''), [raw]);
}
