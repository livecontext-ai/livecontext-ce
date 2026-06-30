/**
 * @vitest-environment jsdom
 *
 * Tests for the chat image lightbox: open/close affordances (X, backdrop, Escape),
 * the download button (local object URL vs authenticated fetch), and the
 * `downloadable` gate. next-intl is mocked so `t(key) => key`.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import * as React from 'react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

// Stub AuthenticatedImage so the authenticated branch doesn't fire a real fetch on render.
vi.mock('../AuthenticatedImage', () => ({
  AuthenticatedImage: ({ src, alt, className }: { src: string; alt: string; className?: string }) => (
    <img data-testid="auth-image" src={src} alt={alt} className={className} />
  ),
}));

const getTokenProvider = vi.fn(() => async () => 'tok-123');
vi.mock('@/lib/api/api-client', () => ({
  apiClient: { getTokenProvider: () => getTokenProvider() },
}));

vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgHeaderForRequest: () => ({ 'X-Active-Organization-ID': 'org-1' }),
}));

import { ImageLightbox } from '../ImageLightbox';

describe('ImageLightbox', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    // jsdom has no object-URL impl.
    (URL as unknown as { createObjectURL: unknown }).createObjectURL = vi.fn(() => 'blob:mock');
    (URL as unknown as { revokeObjectURL: unknown }).revokeObjectURL = vi.fn();
  });
  afterEach(() => {
    document.body.style.overflow = '';
  });

  it('renders nothing when closed', () => {
    const { container } = render(
      <ImageLightbox open={false} onClose={() => {}} src="blob:x" alt="pic" fileName="pic.png" />,
    );
    expect(container.firstChild).toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('shows the image (local object URL), filename caption and download button when open', () => {
    render(
      <ImageLightbox open onClose={() => {}} src="blob:local" alt="pic" fileName="shot.png" />,
    );
    expect(screen.getByRole('dialog')).toBeTruthy();
    // Non-authenticated source → plain <img>, not AuthenticatedImage.
    expect(screen.queryByTestId('auth-image')).toBeNull();
    const img = screen.getByAltText('pic') as HTMLImageElement;
    expect(img.getAttribute('src')).toBe('blob:local');
    expect(screen.getAllByText('shot.png').length).toBeGreaterThan(0);
    expect(screen.getByTitle('download')).toBeTruthy();
  });

  it('uses AuthenticatedImage when authenticated', () => {
    render(
      <ImageLightbox open authenticated onClose={() => {}} src="/api/proxy/v3/chat/attachments/abc" alt="pic" fileName="a.png" />,
    );
    const img = screen.getByTestId('auth-image') as HTMLImageElement;
    expect(img.getAttribute('src')).toBe('/api/proxy/v3/chat/attachments/abc');
  });

  it('closes on the X button', () => {
    const onClose = vi.fn();
    render(<ImageLightbox open onClose={onClose} src="blob:x" alt="p" fileName="p.png" />);
    fireEvent.click(screen.getByTitle('close'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('closes on a backdrop click but NOT when the image is clicked', () => {
    const onClose = vi.fn();
    render(<ImageLightbox open onClose={onClose} src="blob:x" alt="p" fileName="p.png" />);
    // Click the image - must not close.
    fireEvent.click(screen.getByAltText('p'));
    expect(onClose).not.toHaveBeenCalled();
    // Click the backdrop (the dialog root) - closes.
    fireEvent.click(screen.getByRole('dialog'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('closes on Escape', () => {
    const onClose = vi.fn();
    render(<ImageLightbox open onClose={onClose} src="blob:x" alt="p" fileName="p.png" />);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('hides the download button when downloadable=false', () => {
    render(<ImageLightbox open onClose={() => {}} src="blob:x" alt="p" fileName="p.png" downloadable={false} />);
    expect(screen.queryByTitle('download')).toBeNull();
  });

  it('downloads a local object URL directly (no fetch) via a transient anchor', () => {
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    const fetchSpy = vi.spyOn(globalThis, 'fetch' as never);
    render(<ImageLightbox open onClose={() => {}} src="blob:local" alt="p" fileName="p.png" />);
    fireEvent.click(screen.getByTitle('download'));
    expect(clickSpy).toHaveBeenCalledTimes(1);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('downloads an authenticated source by fetching with the Bearer + org headers, then saving the blob', async () => {
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    const fetchMock = vi.fn(async () => ({ ok: true, blob: async () => new Blob(['img']) }));
    vi.stubGlobal('fetch', fetchMock);

    render(
      <ImageLightbox open authenticated onClose={() => {}} src="/api/proxy/v3/chat/attachments/abc" alt="p" fileName="p.png" />,
    );
    fireEvent.click(screen.getByTitle('download'));

    // Let the async download settle.
    await vi.waitFor(() => expect(clickSpy).toHaveBeenCalledTimes(1));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, opts] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/api/proxy/v3/chat/attachments/abc');
    expect((opts.headers as Record<string, string>)['Authorization']).toBe('Bearer tok-123');
    expect((opts.headers as Record<string, string>)['X-Active-Organization-ID']).toBe('org-1');
  });

  it('does not throw and clears the spinner when an authenticated download fails (non-ok response)', async () => {
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const fetchMock = vi.fn(async () => ({ ok: false, status: 403, blob: async () => new Blob() }));
    vi.stubGlobal('fetch', fetchMock);

    render(
      <ImageLightbox open authenticated onClose={() => {}} src="/api/proxy/v3/chat/attachments/x" alt="p" fileName="p.png" />,
    );
    const btn = screen.getByTitle('download');
    fireEvent.click(btn);

    // The failure is swallowed (logged), the save anchor never fires, and the button re-enables.
    await vi.waitFor(() => expect(errSpy).toHaveBeenCalled());
    expect(clickSpy).not.toHaveBeenCalled();
    expect((btn as HTMLButtonElement).disabled).toBe(false);
  });

  it('locks background scroll while open and restores it on close', () => {
    const { rerender } = render(
      <ImageLightbox open onClose={() => {}} src="blob:x" alt="p" fileName="p.png" />,
    );
    expect(document.body.style.overflow).toBe('hidden');
    rerender(<ImageLightbox open={false} onClose={() => {}} src="blob:x" alt="p" fileName="p.png" />);
    expect(document.body.style.overflow).not.toBe('hidden');
  });
});
