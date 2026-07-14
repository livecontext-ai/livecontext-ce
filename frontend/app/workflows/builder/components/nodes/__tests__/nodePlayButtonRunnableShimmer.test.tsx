// @vitest-environment jsdom
/**
 * Runnable-shimmer colour: every ready-to-run bottom play button (triggers AND
 * the step-by-step non-trigger play) now carries the single blue
 * RUNNABLE_SHIMMER (rgba(59, 130, 246, 0.35)). The former per-trigger palette
 * (amber / cyan / emerald / indigo / fuchsia / orange / red) and the green
 * non-trigger shimmer (rgba(34, 197, 94, ...)) are gone.
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (k: string) => k,
}));

import { NodePlayButton } from '../../NodePlayButton';

const BLUE = 'rgba(59, 130, 246, 0.35)';
const html = (variant: any) =>
  render(
    <NodePlayButton
      nodeId="n1"
      status="ready"
      canExecute
      onExecute={() => {}}
      variant={variant}
      position="bottom-center"
      borderColor="#000"
    />
  ).container.innerHTML;

// Triggers that fire immediately + those that open a tab + the plain play button.
const readyVariants = ['play', 'lightning', 'schedule', 'workflow', 'table', 'error', 'webhook', 'message', 'form'];

describe('NodePlayButton runnable shimmer colour', () => {
  it.each(readyVariants)('paints the blue runnable shimmer for a ready %s button', (variant) => {
    const out = html(variant);
    expect(out).toContain(BLUE);
    // green step-by-step shimmer and the old per-trigger palette must be gone
    expect(out).not.toContain('34, 197, 94'); // green
    expect(out).not.toContain('245, 158, 11'); // amber
    expect(out).not.toContain('6, 182, 212');  // cyan
    expect(out).not.toContain('217, 70, 239'); // fuchsia
  });
});
