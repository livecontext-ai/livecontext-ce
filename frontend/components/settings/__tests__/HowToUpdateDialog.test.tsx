/**
 * @vitest-environment jsdom
 *
 * Tests for {@code HowToUpdateDialog}: shows the copy-paste update commands and a
 * release-notes link (only when a URL is provided), and copies the commands.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import * as React from 'react';
import HowToUpdateDialog from '../HowToUpdateDialog';

const labels: Record<string, string> = {
  howToUpdate: 'How to update',
  updateIntro: 'Pull the latest image and restart your stack.',
  updateRunFrom: 'Run these from the folder with your Docker Compose file:',
  copy: 'Copy',
  copied: 'Copied',
  releaseNotes: 'Release notes',
};
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => labels[key] ?? key,
}));

describe('HowToUpdateDialog', () => {
  it('renders the docker compose update commands when open', () => {
    render(<HowToUpdateDialog open onOpenChange={() => {}} releaseUrl={null} />);

    expect(screen.getByText('How to update')).toBeTruthy();
    expect(screen.getByText(/docker compose pull/)).toBeTruthy();
    expect(screen.getByText(/docker compose up -d/)).toBeTruthy();
  });

  it('shows the release-notes link only when a URL is provided', () => {
    const { rerender } = render(
      <HowToUpdateDialog open onOpenChange={() => {}} releaseUrl={null} />,
    );
    expect(screen.queryByText('Release notes')).toBeNull();

    rerender(
      <HowToUpdateDialog open onOpenChange={() => {}} releaseUrl="https://example.test/notes" />,
    );
    const link = screen.getByText('Release notes').closest('a');
    expect(link).not.toBeNull();
    expect(link!.getAttribute('href')).toBe('https://example.test/notes');
  });

  it('copies the commands to the clipboard', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.assign(navigator, { clipboard: { writeText } });

    render(<HowToUpdateDialog open onOpenChange={() => {}} releaseUrl={null} />);
    fireEvent.click(screen.getByTitle('Copy'));

    await waitFor(() => expect(writeText).toHaveBeenCalledTimes(1));
    expect(writeText.mock.calls[0][0]).toContain('docker compose pull');
    expect(writeText.mock.calls[0][0]).toContain('docker compose up -d');
  });

  it('does not render content when closed', () => {
    render(<HowToUpdateDialog open={false} onOpenChange={() => {}} releaseUrl={null} />);
    expect(screen.queryByText(/docker compose pull/)).toBeNull();
  });
});
