// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, cleanup } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  getFileUrlById: (id: string) => `/raw/${id}`,
}));
vi.mock('@/components/MarkdownRender', () => ({ default: () => null }));

import { useAuthedObjectUrl } from '@/hooks/useAuthedObjectUrl';

vi.mock('@/hooks/useAuthedObjectUrl', () => ({ useAuthedObjectUrl: vi.fn() }));
const mockUseAuthed = vi.mocked(useAuthedObjectUrl);
mockUseAuthed.mockImplementation((src) => ({ url: src ? `blob:${src}` : null, loading: false, error: false }));

import { FileDetailView } from '../FileDetailView';

afterEach(() => cleanup());

const props = {
  entryId: 'file-1',
  sizeBytes: 1024,
  createdAt: '2026-06-01T00:00:00Z',
  onBack: () => {},
};

/**
 * The full file view is one of the four surfaces that put stored bytes in an {@code <iframe>},
 * and the only one where a person deliberately opens a document. A blob URL inherits this app's
 * origin, and a page is classified by NAME as well as by type, so a row stored as text/html under
 * a `.pdf` name would run its own script here with the session in reach. The type is forced.
 */
describe('FileDetailView - a framed page', () => {
  it('forces the blob type of a PDF, whatever the row says it is', () => {
    render(<FileDetailView {...props} fileName="invoice.pdf" mimeType="text/html" />);

    expect(mockUseAuthed).toHaveBeenLastCalledWith('/raw/file-1', 'text/html', 'application/pdf');
  });

  it('only hints the type of media, which decodes by content and executes nothing', () => {
    // Forcing here would break honest files: a clip stored as video/webm under an `.mp4` name
    // would stop decoding.
    render(<FileDetailView {...props} fileName="clip.mp4" mimeType="video/webm" />);

    expect(mockUseAuthed).toHaveBeenLastCalledWith('/raw/file-1', 'video/webm', undefined);
  });
});
