/**
 * @vitest-environment jsdom
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ToolAuthorizationCard } from '../ToolAuthorizationCard';

// next-intl returns the key so we can assert on the chosen copy without loading messages.
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

// The card now embeds the marketplace PublicationCard preview + fetches the publication;
// stub both so the test stays focused on the card's copy/buttons (no network, no heavy deps).
vi.mock('@/components/marketplace/PublicationCard', () => ({
  PublicationCard: ({ publication }: { publication: { title?: string } }) => (
    <div data-testid="publication-preview">{publication?.title}</div>
  ),
  PublicationCardSkeleton: () => <div data-testid="publication-skeleton" />,
}));

vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getPublicationByIdPublic: vi.fn().mockResolvedValue({ id: 'pub-1', title: 'Demo App' }) },
}));

const baseAuth = {
  toolName: 'application',
  action: 'execute',
  toolCallId: 'call-1',
  argsSummary: '{"action":"execute"}',
  timestamp: 1,
};

describe('ToolAuthorizationCard', () => {
  it('reads as a RUN with neutral (non-warning) styling for execute', () => {
    const { container } = render(
      <ToolAuthorizationCard conversationId="c1" pendingAuthorization={{ ...baseAuth, rule: 'application:execute' }} />,
    );

    expect(screen.getByText('runTitle')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'approve' })).toBeInTheDocument();
    // No amber "warning" styling anywhere.
    expect(container.innerHTML).not.toContain('amber');
  });

  it('reads as an INSTALL for application:acquire', () => {
    render(
      <ToolAuthorizationCard
        conversationId="c1"
        pendingAuthorization={{ ...baseAuth, action: 'acquire', rule: 'application:acquire' }}
      />,
    );

    expect(screen.getByText('installTitle')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'install' })).toBeInTheDocument();
  });

  it('passes the blanket flag from the "don\'t ask again" checkbox to onApproved', () => {
    const onApproved = vi.fn();
    render(
      <ToolAuthorizationCard
        conversationId="c1"
        pendingAuthorization={{ ...baseAuth, rule: 'application:execute' }}
        onApproved={onApproved}
      />,
    );

    fireEvent.click(screen.getByTestId('tool-authorization-dont-ask'));
    fireEvent.click(screen.getByRole('button', { name: 'approve' }));

    // The card forwards its toolCallId so the caller can clear THIS specific card (F16).
    expect(onApproved).toHaveBeenCalledWith('application:execute', true, 'call-1');
  });

  it('defaults blanket to false when the checkbox is untouched', () => {
    const onApproved = vi.fn();
    render(
      <ToolAuthorizationCard
        conversationId="c1"
        pendingAuthorization={{ ...baseAuth, rule: 'application:execute' }}
        onApproved={onApproved}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'approve' }));

    expect(onApproved).toHaveBeenCalledWith('application:execute', false, 'call-1');
  });
});
