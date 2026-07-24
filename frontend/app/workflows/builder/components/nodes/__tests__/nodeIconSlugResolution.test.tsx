// @vitest-environment jsdom
/**
 * NodeIcon static-icon resolution.
 *
 * Two ways a real brand logo silently degraded into a generic glyph, both of
 * which reached the canvas AND every marketplace card (WorkflowNodeIcons
 * spreads persisted props straight into NodeIcon):
 *
 *  1. the catalog's `mcp` sentinel - `COALESCE(a.icon_slug, 'mcp')` - is a
 *     TRUTHY placeholder, so it was accepted as a real slug and rendered
 *     /icons/services/mcp.svg (a generic "API" circle) on the SUCCESS path.
 *     Because that request succeeds, `onError` never fired and the MCP-logo
 *     fallback was unreachable too;
 *  2. a slug whose separators do not match the file on disk 404s, with no
 *     second chance. Most files are separator-free ("googlesheets.svg") but
 *     four are genuinely hyphenated ("claude-code.svg", "gemini-cli.svg",
 *     "mistral-vibe.svg", "audit-tracking.svg"), so the collapse has to be an
 *     onError RETRY rather than the primary lookup - normalizing up front would
 *     just move the missing logo from one set of nodes to another.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/components/ThemeProvider', () => ({ useTheme: () => ({ theme: 'light' }) }));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => ({ mode: 'edit' }) }));
vi.mock('@/hooks/useAuthedObjectUrl', () => ({ useAuthedObjectUrl: () => ({ url: null, error: false }) }));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => <div data-testid="avatar" /> }));
vi.mock('next/image', () => ({
  default: ({ src, alt, onError }: { src: string; alt: string; onError?: () => void }) => (
    // eslint-disable-next-line @next/next/no-img-element
    <img data-testid="node-icon-img" src={src} alt={alt} onError={onError} />
  ),
}));

import { NodeIcon } from '../shared';

function iconSrc(): string {
  return screen.getByTestId('node-icon-img').getAttribute('src') ?? '';
}

describe('NodeIcon - static icon slug resolution', () => {
  beforeEach(() => { cleanup(); });

  it('Regression - the catalog "mcp" sentinel never renders as a service icon', () => {
    render(<NodeIcon iconSlug="mcp" isMcp nodeId="mcp-slack" />);
    // Falls through to the MCP logo instead of the generic /icons/services/mcp.svg.
    expect(iconSrc()).not.toContain('/icons/services/');
    expect(iconSrc()).toContain('mcp_black.png');
  });

  // The separator collapse must be a RETRY, never the primary lookup: these
  // four files are genuinely hyphenated on disk, and getProviderIconSlug feeds
  // the hyphenated slug verbatim to every Classify/Guardrail node. Normalizing
  // up front would 404 all of them - swapping one missing-logo class for
  // another.
  it.each(['claude-code', 'gemini-cli', 'mistral-vibe', 'audit-tracking'])(
    'Uses the hyphenated slug %s verbatim (its file really is hyphenated)',
    (slug) => {
      render(<NodeIcon iconSlug={slug} nodeId="classify" />);
      expect(iconSrc()).toBe(`/icons/services/${slug}.svg`);
    },
  );

  it('Falls back to the normalized slug only after the verbatim one 404s', () => {
    render(<NodeIcon iconSlug="google-sheets" isMcp nodeId="mcp-google" />);
    // First attempt is verbatim...
    expect(iconSrc()).toBe('/icons/services/google-sheets.svg');

    fireEvent.error(screen.getByTestId('node-icon-img'));

    // ...then the separator-free retry, which is what exists on disk.
    expect(iconSrc()).toBe('/icons/services/googlesheets.svg');
  });

  it('Gives up to the MCP logo when both the verbatim and normalized slugs 404', () => {
    render(<NodeIcon iconSlug="no-such-brand" isMcp nodeId="mcp-x" />);

    fireEvent.error(screen.getByTestId('node-icon-img')); // verbatim 404
    fireEvent.error(screen.getByTestId('node-icon-img')); // normalized 404

    expect(iconSrc()).toContain('mcp_black.png');
  });

  it('A slug with no normalized variant gives up after a single 404', () => {
    // "unknownbrand" normalizes to itself, so there is no second attempt to
    // make - one error must land straight on the MCP logo rather than
    // re-requesting the same 404 forever.
    render(<NodeIcon iconSlug="unknownbrand" isMcp nodeId="mcp-x" />);

    fireEvent.error(screen.getByTestId('node-icon-img'));

    expect(iconSrc()).toContain('mcp_black.png');
  });

  it('A real canonical slug is used as-is', () => {
    render(<NodeIcon iconSlug="slack" isMcp nodeId="mcp-slack" />);
    expect(iconSrc()).toBe('/icons/services/slack.svg');
  });

  it('A slug that merely contains "mcp" still resolves its own icon', () => {
    render(<NodeIcon iconSlug="mcpserver" isMcp nodeId="mcp-x" />);
    expect(iconSrc()).toBe('/icons/services/mcpserver.svg');
  });

  it('An MCP node with no slug at all keeps the MCP logo fallback', () => {
    render(<NodeIcon isMcp nodeId="mcp-unknown" />);
    expect(iconSrc()).toContain('mcp_black.png');
  });
});

describe('NodeIcon - the attempt ladder resets when the slug changes', () => {
  it('a new slug is tried verbatim even after the previous one exhausted the ladder', () => {
    const { rerender } = render(<NodeIcon iconSlug="no-such-brand" isMcp nodeId="mcp-x" />);

    fireEvent.error(screen.getByTestId('node-icon-img')); // verbatim 404
    fireEvent.error(screen.getByTestId('node-icon-img')); // normalized 404
    expect(iconSrc()).toContain('mcp_black.png');

    // Reset happens during render, so there is no frame showing the previous
    // node's exhausted state over a slug that resolves fine.
    rerender(<NodeIcon iconSlug="slack" isMcp nodeId="mcp-slack" />);
    expect(iconSrc()).toBe('/icons/services/slack.svg');
  });

  it('a slug change mid-ladder restarts from verbatim, not from the normalized retry', () => {
    const { rerender } = render(<NodeIcon iconSlug="google-sheets" isMcp nodeId="mcp-g" />);
    fireEvent.error(screen.getByTestId('node-icon-img'));
    expect(iconSrc()).toBe('/icons/services/googlesheets.svg'); // on attempt 1

    rerender(<NodeIcon iconSlug="claude-code" nodeId="classify" />);
    // Attempt 0 again: verbatim, hyphens intact.
    expect(iconSrc()).toBe('/icons/services/claude-code.svg');
  });
});
