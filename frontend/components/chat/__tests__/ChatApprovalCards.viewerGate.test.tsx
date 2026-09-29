/**
 * @vitest-environment jsdom
 */
/**
 * VIEWER gate on the chat approval cards. Approving a held tool action (or connecting the
 * service it needs) runs it with the workspace credentials, which the backend now refuses
 * to a read-only VIEWER with 403. Before this gate the cards still offered Approve / Connect
 * (and the connect card even auto-approved when the credential existed), so a VIEWER got a
 * button that could only fail. Now they are offered Deny only, with the read-only sentence.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ToolAuthorizationCard } from '../ToolAuthorizationCard';
import { ServiceApprovalCard } from '../ServiceApprovalCard';
import { SharedConversationProvider } from '@/contexts/SharedConversationContext';

const gate = vi.hoisted(() => ({ canMutate: true, hasCredential: vi.fn() }));

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => gate.canMutate,
}));
vi.mock('@/components/marketplace/PublicationCard', () => ({
  PublicationCard: () => <div data-testid="publication-preview" />,
  PublicationCardSkeleton: () => <div data-testid="publication-skeleton" />,
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getPublicationByIdPublic: vi.fn().mockResolvedValue({ id: 'pub-1' }) },
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
    hasCredential: gate.hasCredential,
    refetch: vi.fn(),
    isLoading: false,
    credentials: [],
  }),
}));
vi.mock('@/components/Toast', () => ({
  useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }),
}));
vi.mock('@/components/ToastContainer', () => ({ default: () => null }));
vi.mock('@/components/credentials', () => ({ CredentialWizard: () => null }));
vi.mock('@/lib/credentials/useByokCapability', () => ({
  useByokCapability: () => ({ byokOnlyScopes: [], platformScopes: [], byokOffered: false }),
}));

const auth = {
  toolName: 'application',
  action: 'execute',
  rule: 'application:execute',
  toolCallId: 'call-1',
  argsSummary: '{"action":"execute"}',
  timestamp: 1,
};
const services = [{ serviceType: 'gmail', serviceName: 'Gmail', iconSlug: 'gmail', toolName: 'Send Email' }];

beforeEach(() => {
  gate.canMutate = true;
  gate.hasCredential.mockReset().mockReturnValue(false);
});

describe('ToolAuthorizationCard - VIEWER gate', () => {
  it('VIEWER: no Approve and no "don\'t ask again", the read-only sentence instead; Deny still answers', () => {
    gate.canMutate = false;
    const onDenied = vi.fn();
    render(<ToolAuthorizationCard conversationId="c1" pendingAuthorization={auth} onDenied={onDenied} />);

    expect(screen.queryByRole('button', { name: 'approve' })).not.toBeInTheDocument();
    expect(screen.queryByTestId('tool-authorization-dont-ask')).not.toBeInTheDocument();
    expect(screen.getByTestId('tool-authorization-viewer-read-only')).toHaveTextContent('viewerReadOnly');

    fireEvent.click(screen.getByRole('button', { name: 'deny' }));
    expect(onDenied).toHaveBeenCalledWith('application:execute', 'call-1');
  });

  it('MEMBER: Approve is offered, no read-only sentence', () => {
    render(<ToolAuthorizationCard conversationId="c1" pendingAuthorization={auth} />);

    expect(screen.getByRole('button', { name: 'approve' })).toBeInTheDocument();
    expect(screen.queryByTestId('tool-authorization-viewer-read-only')).not.toBeInTheDocument();
  });
});

describe('ServiceApprovalCard - VIEWER gate', () => {
  it('VIEWER: no Connect, the read-only sentence, Deny still answers', () => {
    gate.canMutate = false;
    const onDenied = vi.fn();
    render(
      <ServiceApprovalCard conversationId="c1" onDenied={onDenied}
        pendingApproval={{ services, needsAttention: false, timestamp: 1 }} />,
    );

    expect(screen.queryByRole('button', { name: 'approveAll' })).not.toBeInTheDocument();
    expect(screen.getByTestId('service-approval-viewer-read-only')).toHaveTextContent('viewerReadOnly');
    fireEvent.click(screen.getByRole('button', { name: 'deny' }));
    expect(onDenied).toHaveBeenCalledWith(['Gmail']);
  });

  it('VIEWER: no Retry in needs-attention mode', () => {
    gate.canMutate = false;
    gate.hasCredential.mockReturnValue(true);
    render(
      <ServiceApprovalCard conversationId="c1"
        pendingApproval={{ services, needsAttention: true, timestamp: 1 }} />,
    );

    expect(screen.queryByRole('button', { name: 'retry' })).not.toBeInTheDocument();
  });

  it('VIEWER: an existing credential does NOT auto-approve the held call', () => {
    gate.canMutate = false;
    gate.hasCredential.mockReturnValue(true);
    const onApproved = vi.fn();
    render(
      <ServiceApprovalCard conversationId="c1" onApproved={onApproved}
        pendingApproval={{ services, needsAttention: false, timestamp: 1 }} />,
    );

    expect(onApproved).not.toHaveBeenCalled();
  });

  it('MEMBER: Connect is offered, and an existing credential auto-approves as before', () => {
    render(
      <ServiceApprovalCard conversationId="c1"
        pendingApproval={{ services, needsAttention: false, timestamp: 1 }} />,
    );
    expect(screen.getByRole('button', { name: 'approveAll' })).toBeInTheDocument();

    gate.hasCredential.mockReturnValue(true);
    const onApproved = vi.fn();
    render(
      <ServiceApprovalCard conversationId="c2" onApproved={onApproved}
        pendingApproval={{ services, needsAttention: false, timestamp: 1 }} />,
    );
    expect(onApproved).toHaveBeenCalledWith(['Gmail']);
  });
});

describe('approval cards inside a public share page', () => {
  // The workflow panel AI chat tab also mounts in /s/[token]. There the share link decides
  // (the backend sees no role), so a logged-in visitor whose persisted workspace is VIEWER
  // must still be offered Approve / Connect.
  it('persisted VIEWER workspace + share context: Approve and Connect are offered', () => {
    gate.canMutate = false;
    render(
      <SharedConversationProvider token="sl_abc">
        <ToolAuthorizationCard conversationId="c1" pendingAuthorization={auth} />
        <ServiceApprovalCard conversationId="c1"
          pendingApproval={{ services, needsAttention: false, timestamp: 1 }} />
      </SharedConversationProvider>,
    );

    expect(screen.getByRole('button', { name: 'approve' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'approveAll' })).toBeInTheDocument();
    expect(screen.queryByTestId('tool-authorization-viewer-read-only')).not.toBeInTheDocument();
  });
});
