// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, cleanup } from '@testing-library/react';
import { FileDetailView } from '../FileDetailView';

// next-intl → identity so the metadata labels render as their keys and the
// metadata VALUES (size / mime) render verbatim, which is what we assert.
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

// Deterministic media URL so the <img> renders without the real api client.
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  getFileUrlById: (id: string, opts?: { inline?: boolean }) => `/raw/${id}?inline=${opts?.inline ?? false}`,
}));

// A non-null token so mediaUrl is built (the <img> renders instead of the spinner).
vi.mock('@/hooks/useAuthToken', () => ({ useAuthToken: () => 'tok' }));
vi.mock('@/lib/stores/current-org-store', () => ({ getActiveOrgHeaderForRequest: () => ({}) }));
vi.mock('@/lib/api/api-client', () => ({ apiClient: { getTokenProvider: () => null } }));
// Text-preview-only deps - never rendered on the image path, mocked to keep the
// module graph light.
vi.mock('@/components/MarkdownRender', () => ({ default: () => null }));

afterEach(() => cleanup());

const baseProps = {
  entryId: 'file-1',
  fileName: 'pic.png',
  mimeType: 'image/png',
  sizeBytes: 1024,
  createdAt: '2026-06-01T00:00:00Z',
  onBack: () => {},
};

describe('FileDetailView - chromeless full-page viewer', () => {
  it('shows the file metadata under the media by default (no info toggle needed)', () => {
    const { getByText, container } = render(<FileDetailView chromeless {...baseProps} />);
    // The size / type metadata is visible WITHOUT passing showMetadata - the
    // header info "i" toggle is gone, so the text under the photo is permanent.
    expect(getByText('1.0 KB')).toBeTruthy();
    expect(getByText('image/png')).toBeTruthy();
    expect(container.querySelector('img')).toBeTruthy();
  });

  it('bounds the viewer to the viewport and lets the image shrink to fit so the metadata needs no scroll', () => {
    const { container } = render(<FileDetailView chromeless {...baseProps} />);
    // Outer wrapper is height-capped to the space under the app header + page
    // padding (≈ 100vh − 8.5rem), so the column never overflows the viewport.
    const root = container.firstElementChild as HTMLElement;
    expect(root.className).toContain('h-[calc(100vh-8.5rem)]');
    // The image fills its flex region (max-h-full) and keeps its aspect ratio
    // (object-contain) - i.e. it is resized down rather than forcing a scroll.
    const img = container.querySelector('img')!;
    expect(img.className).toContain('object-contain');
    expect(img.className).toContain('max-h-full');
  });

  it('still honours showMetadata=false for non-Files callers (side panel / visualize cards)', () => {
    const { queryByText } = render(<FileDetailView chromeless showMetadata={false} {...baseProps} />);
    expect(queryByText('1.0 KB')).toBeNull();
    expect(queryByText('image/png')).toBeNull();
  });
});
