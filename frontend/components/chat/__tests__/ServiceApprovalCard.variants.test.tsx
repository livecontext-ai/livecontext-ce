/**
 * @vitest-environment jsdom
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ServiceApprovalCard } from '../ServiceApprovalCard';

const mocks = vi.hoisted(() => ({
  hasCredential: vi.fn(),
  refetchCredentials: vi.fn(),
  wizardProps: vi.fn(),
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, params?: Record<string, string>) => {
    const values: Record<string, string> = {
      title: 'Connect credentials',
      subtitle: 'The agent needs credentials',
      needsAttentionTitle: 'Credential needs attention',
      needsAttentionSubtitle: 'Reconnect or retry this credential',
      approveAll: 'Connect',
      retry: 'Retry',
      deny: 'Deny',
      manageCredentials: 'Manage credentials',
      reason: 'Reason',
      approving: 'Approving',
      serviceNeeded: `${params?.service} for ${params?.tool}`,
    };
    return values[key] ?? key;
  },
}));

vi.mock('next/navigation', () => ({
  useSearchParams: () => ({ get: () => null }),
  useRouter: () => ({ replace: vi.fn() }),
  usePathname: () => '/app/chat',
}));

vi.mock('next/image', () => ({
  default: (props: React.ImgHTMLAttributes<HTMLImageElement>) => <img {...props} />,
}));

vi.mock('@/hooks/useCredentialCheck', () => ({
  useCredentialCheck: () => ({
    hasCredential: mocks.hasCredential,
    refetch: mocks.refetchCredentials,
    isLoading: false,
    credentials: [],
  }),
}));

vi.mock('@/components/Toast', () => ({
  useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }),
}));

vi.mock('@/components/ToastContainer', () => ({
  default: () => null,
}));

vi.mock('@/components/credentials', () => ({
  CredentialWizard: (props: any) => {
    mocks.wizardProps(props);
    return props.open ? (
      <div data-testid="credential-wizard" data-count={props.requirements.length} />
    ) : null;
  },
}));

const services = [
  { serviceType: 'gmail', serviceName: 'Gmail', iconSlug: 'gmail', toolName: 'Send Email' },
  { serviceType: 'slack', serviceName: 'Slack', iconSlug: 'slack', toolName: 'Post Message' },
];

describe('ServiceApprovalCard variants', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders normal credential approvals as neutral and opens one multi-credential wizard', () => {
    mocks.hasCredential.mockReturnValue(false);

    render(
      <ServiceApprovalCard
        conversationId="conv-1"
        pendingApproval={{ services, reason: 'Need both services', needsAttention: false, timestamp: 1 }}
      />,
    );

    expect(document.querySelector('[data-approval-variant="neutral"]')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Connect' }));

    expect(screen.getByTestId('credential-wizard')).toHaveAttribute('data-count', '2');
    expect(mocks.wizardProps).toHaveBeenLastCalledWith(
      expect.objectContaining({
        open: true,
        requirements: [
          expect.objectContaining({ iconSlug: 'gmail', serviceName: 'Gmail' }),
          expect.objectContaining({ iconSlug: 'slack', serviceName: 'Slack' }),
        ],
      }),
    );
  });

  it('renders forced reconnect approvals as warning with retry', () => {
    mocks.hasCredential.mockReturnValue(true);

    render(
      <ServiceApprovalCard
        conversationId="conv-1"
        pendingApproval={{ services: [services[0]], reason: 'Token expired', needsAttention: true, timestamp: 1 }}
      />,
    );

    expect(document.querySelector('[data-approval-variant="warning"]')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Connect' })).not.toBeInTheDocument();
  });
});
