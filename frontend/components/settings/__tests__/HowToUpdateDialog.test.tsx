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
  updateIntro: 'Get the latest release and restart your stack.',
  updateRunFrom: 'Run these from your cloned LiveContext folder:',
  copy: 'Copy',
  copied: 'Copied',
  releaseNotes: 'Release notes',
};
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => labels[key] ?? key,
}));

describe('HowToUpdateDialog', () => {
  it('renders the update commands with git pull first when open', () => {
    render(<HowToUpdateDialog open onOpenChange={() => {}} releaseUrl={null} />);

    expect(screen.getByText('How to update')).toBeTruthy();
    // The public compose pins the release image tag, so following the dialog
    // without git pull first would silently re-pull the OLD version.
    expect(
      screen.getByText(/git pull[\s\S]*docker compose pull[\s\S]*docker compose up -d/),
    ).toBeTruthy();
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
    // Order matters: git pull must precede docker compose pull (pinned image tag).
    expect(writeText.mock.calls[0][0]).toMatch(
      /^git pull\ndocker compose pull\ndocker compose up -d$/,
    );
  });

  it('does not render content when closed', () => {
    render(<HowToUpdateDialog open={false} onOpenChange={() => {}} releaseUrl={null} />);
    expect(screen.queryByText(/docker compose pull/)).toBeNull();
  });
});
