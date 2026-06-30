/**
 * @vitest-environment jsdom
 *
 * Wiring test for the headline path: a SENT user message's image attachment renders a
 * clickable "enlarge" button that opens the lightbox in authenticated mode, sourced from
 * the protected attachment download URL.
 */
import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import * as React from 'react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

// Heavy children / hooks reduced to inert stubs so we render just the attachment + lightbox path.
vi.mock('@/hooks/useCurrentView', () => ({ useCurrentView: () => ({ view: 'chat' }) }));
vi.mock('@/lib/hooks/useIsomorphicLayoutEffect', () => ({ useIsomorphicLayoutEffect: () => {} }));
vi.mock('@/components/MarkdownRender', () => ({ default: ({ text }: { text: string }) => <div>{text}</div> }));
vi.mock('@/components/chat/ThinkingDots', () => ({
  getLastThinkingMessage: () => null,
  parseThinkingMarker: () => null,
}));
vi.mock('@/components/chat/workflowUtils', () => ({ isWorkflowMessage: () => false }));
vi.mock('@/components/chat/DataSourceMessage', () => ({ isDataSourceMessage: () => false }));
vi.mock('@/components/chat/WorkflowSuggestions', () => ({ WorkflowSuggestions: () => null }));
vi.mock('@/components/chat/MessageSkeleton', () => ({ MessageSkeleton: () => null }));
vi.mock('@/components/chat/DataSourceDisplayMode', () => ({ default: () => null }));
vi.mock('@/components/chat/ActivityFeed', () => ({ ActivityFeed: () => null }));
vi.mock('@/components/chat/MessageActions', () => ({ MessageActions: () => null }));
vi.mock('@/lib/utils/dateFormatters', () => ({
  formatUtcDateTime: () => '2026-06-12',
  formatUtcTime: () => '12:00',
  parseUtcAware: (s: string) => s,
}));

// The download URL the wiring must hand to the lightbox.
vi.mock('@/lib/api/attachmentApi', () => ({
  attachmentApi: { getDownloadUrl: (id: string) => `/api/proxy/v3/chat/attachments/${id}` },
}));

// Stub AuthenticatedImage so we can read the src the lightbox renders without a real fetch.
vi.mock('@/components/chat/AuthenticatedImage', () => ({
  AuthenticatedImage: ({ src, alt }: { src: string; alt: string }) => (
    <img data-testid="auth-image" src={src} alt={alt} />
  ),
}));

import { MessageHistory } from '../MessageHistory';

const imageMessage = {
  id: 'm1',
  role: 'user' as const,
  content: 'see this',
  timestamp: '2026-06-12T12:00:00Z',
  attachments: [
    { storageId: 'store-uuid-1', type: 'IMAGE' as const, fileName: 'diagram.png', mimeType: 'image/png' },
  ],
};

describe('MessageHistory image lightbox wiring', () => {
  it('opens the authenticated lightbox from a sent image attachment, sourced from the attachment download URL', () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    render(<MessageHistory messages={[imageMessage] as any} />);

    // No overlay before interaction.
    expect(screen.queryByRole('dialog')).toBeNull();

    // The inline image attachment is an "enlarge" button.
    fireEvent.click(screen.getByLabelText('chat.imageViewer.enlarge'));

    // Lightbox opens; its image (authenticated) points at the protected attachment URL.
    const dialog = screen.getByRole('dialog');
    const img = within(dialog).getByTestId('auth-image') as HTMLImageElement;
    expect(img.getAttribute('src')).toBe('/api/proxy/v3/chat/attachments/store-uuid-1');
    // Authenticated lightbox shows a Download button (re-download of the sent image).
    expect(within(dialog).getByTitle('download')).toBeTruthy();
  });
});
