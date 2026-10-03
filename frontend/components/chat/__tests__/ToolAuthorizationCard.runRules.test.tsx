// @vitest-environment jsdom
/**
 * The authorization card for the RUN rules, against the REAL dictionaries.
 *
 * <p>Regression: every run rule (workflow:execute, agent:execute, catalog:execute,
 * application:execute, restart_from_node, run_node) rendered the same bare "Run this action? /
 * The agent wants to run a sensitive action", which told the user neither WHAT would run nor
 * WHY they were asked. Each now names its target when the call carries one, says what the
 * click costs, and exposes the exact call (tool, action, arguments) behind a disclosure.
 *
 * <p>Assertions are on words a reader sees, never key paths: next-intl prints the key when a
 * message is missing and does not throw, so only real dictionaries prove the copy exists.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';
import frMessages from '@/messages/fr.json';
import type { PendingToolAuthorization } from '@/contexts/StreamingContext';

import { workflowService } from '@/lib/api/orchestrator/workflow.service';
import { agentService } from '@/lib/api/orchestrator/agent.service';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { ToolAuthorizationCard, formatArgsSummary } from '../ToolAuthorizationCard';

// The real marketplace card needs a mounted Next app router; the preview itself is not
// under test here, only that the card renders it instead of a raw id.
vi.mock('@/components/marketplace/PublicationCard', () => ({
  PublicationCard: ({ publication }: { publication: { title?: string } }) => (
    <div data-testid="publication-preview">{publication?.title}</div>
  ),
  PublicationCardSkeleton: () => <div data-testid="publication-skeleton" />,
}));

let getWorkflow: ReturnType<typeof vi.spyOn>;
let getAgent: ReturnType<typeof vi.spyOn>;
let getPublication: ReturnType<typeof vi.spyOn>;

function renderCard(locale: 'en' | 'fr', pending: Partial<PendingToolAuthorization> & { rule: string }) {
  return render(
    <NextIntlClientProvider locale={locale} messages={locale === 'en' ? enMessages : frMessages}>
      <ToolAuthorizationCard
        conversationId="conv-1"
        pendingAuthorization={{ timestamp: 1, ...pending } as PendingToolAuthorization}
      />
    </NextIntlClientProvider>,
  );
}

beforeEach(() => {
  getWorkflow = vi.spyOn(workflowService, 'getWorkflow').mockResolvedValue({ name: 'Invoice Demo' } as never);
  getAgent = vi.spyOn(agentService, 'getAgent').mockResolvedValue({ name: 'Research Bot' } as never);
  getPublication = vi.spyOn(publicationService, 'getPublicationByIdPublic').mockResolvedValue(null as never);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('workflow:execute', () => {
  it('en: names the fetched workflow and says what running it costs', async () => {
    renderCard('en', { rule: 'workflow:execute', subject: { kind: 'workflow', id: 'w-1' } });

    await waitFor(() => expect(screen.getByText('Run “Invoice Demo”?')).toBeInTheDocument());
    expect(screen.getByText(/uses credits and can act on your connected accounts/)).toBeInTheDocument();
    expect(getWorkflow).toHaveBeenCalledWith('w-1');
    expect(screen.queryByText(/sensitive action/)).not.toBeInTheDocument();
  });

  it('fr: French copy, never a key path', async () => {
    renderCard('fr', { rule: 'workflow:execute', subject: { kind: 'workflow', id: 'w-1' } });

    await waitFor(() => expect(screen.getByText('Exécuter « Invoice Demo » ?')).toBeInTheDocument());
    expect(screen.getByText(/consomme des crédits/)).toBeInTheDocument();
    expect(screen.queryByText(/toolAuthorization\./)).not.toBeInTheDocument();
  });

  it('without a subject (the loaded workflow) still says it is a workflow, not "an action"', () => {
    renderCard('en', { rule: 'workflow:execute' });

    expect(screen.getByText('Run this workflow?')).toBeInTheDocument();
    expect(getWorkflow).not.toHaveBeenCalled();
  });

  it('a failed name fetch falls back to the unnamed title and shows the id', async () => {
    getWorkflow.mockRejectedValueOnce(new Error('boom'));
    renderCard('en', { rule: 'workflow:execute', subject: { kind: 'workflow', id: 'w-404' } });

    await waitFor(() => expect(screen.getByTestId('tool-authorization-subject-id')).toHaveTextContent('w-404'));
    expect(screen.getByText('Run this workflow?')).toBeInTheDocument();
  });
});

describe('agent:execute', () => {
  it('names the sub-agent and says it spends AI credits', async () => {
    renderCard('en', { rule: 'agent:execute', subject: { kind: 'agent', id: 'a-1' } });

    await waitFor(() => expect(screen.getByText('Start “Research Bot”?')).toBeInTheDocument());
    expect(screen.getByText(/uses AI credits/)).toBeInTheDocument();
    expect(getAgent).toHaveBeenCalledWith('a-1');
  });

  it('a failed name fetch falls back to the unnamed title and shows the id', async () => {
    getAgent.mockRejectedValueOnce(new Error('boom'));
    renderCard('en', { rule: 'agent:execute', subject: { kind: 'agent', id: 'a-404' } });

    await waitFor(() => expect(screen.getByTestId('tool-authorization-subject-id')).toHaveTextContent('a-404'));
    expect(screen.getByText('Start another agent?')).toBeInTheDocument();
  });
});

describe('agent:disarm_tool_authorization', () => {
  it('names the agent and states the real risk (no more asking), not "spends credits"', async () => {
    renderCard('en', { rule: 'agent:disarm_tool_authorization', subject: { kind: 'agent', id: 'a-1' } });

    await waitFor(() => expect(screen.getByText('Stop “Research Bot” from asking you first?')).toBeInTheDocument());
    expect(screen.getByText(/without asking you/)).toBeInTheDocument();
    expect(screen.queryByText(/can spend credits or change things/)).not.toBeInTheDocument();
  });
});

describe('approval waits for the name on run rules', () => {
  it('Authorize stays disabled while the workflow name loads', async () => {
    let resolve: (v: unknown) => void = () => {};
    getWorkflow.mockReturnValueOnce(new Promise((r) => { resolve = r; }) as never);
    renderCard('en', { rule: 'workflow:execute', subject: { kind: 'workflow', id: 'w-1' } });

    expect(screen.getByRole('button', { name: /Loading/ })).toBeDisabled();
    resolve({ name: 'Invoice Demo' });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Authorize' })).toBeEnabled());
  });
});

describe('application:execute', () => {
  it('a private app whose preview 404s shows its id, never an empty box', async () => {
    getPublication.mockRejectedValueOnce(new Error('404'));
    const { container } = renderCard('en', { rule: 'application:execute', subject: { kind: 'application', id: 'p-9' } });

    await waitFor(() => expect(screen.getByTestId('tool-authorization-subject-id')).toHaveTextContent('p-9'));
    expect(container.querySelector('.rounded-2xl.bg-theme-primary.border.p-3')).toBeNull();
  });

  it('previews the application from its subject id', async () => {
    renderCard('en', { rule: 'application:execute', subject: { kind: 'application', id: 'p-1' } });

    expect(screen.getByText('Run this application?')).toBeInTheDocument();
    await waitFor(() => expect(getPublication).toHaveBeenCalledWith('p-1'));
  });

  it('a loaded app renders its marketplace preview and no raw id', async () => {
    getPublication.mockResolvedValueOnce({ id: 'p-1', title: 'Lead Enricher', name: 'Lead Enricher' } as never);
    renderCard('en', { rule: 'application:execute', subject: { kind: 'application', id: 'p-1' } });

    await waitFor(() => expect(screen.getByText('Lead Enricher')).toBeInTheDocument());
    expect(screen.queryByTestId('tool-authorization-subject-id')).not.toBeInTheDocument();
  });

  it('application:acquire whose preview fails also shows the id (it has no subject)', async () => {
    getPublication.mockRejectedValueOnce(new Error('404'));
    renderCard('en', { rule: 'application:acquire', applicationId: 'p-7' });

    await waitFor(() => expect(screen.getByTestId('tool-authorization-subject-id')).toHaveTextContent('p-7'));
  });
});

describe('catalog:execute / catalog:call', () => {
  it.each(['catalog:execute', 'catalog:call'])('%s says it calls a third-party service with the user account', (rule) => {
    renderCard('en', { rule, subject: { kind: 'api_tool', id: 'f0e1d2c3-uuid' } });

    expect(screen.getByText('Call an external service?')).toBeInTheDocument();
    expect(screen.getByText(/third-party API with your connected account/)).toBeInTheDocument();
    // A bare tool UUID is not shown as the subject line.
    expect(screen.queryByTestId('tool-authorization-subject-id')).not.toBeInTheDocument();
  });
});

describe('workflow:restart_from_node and workflow:run_node', () => {
  it('restart names the node that re-runs with everything after it', () => {
    renderCard('en', { rule: 'workflow:restart_from_node', subject: { kind: 'run_node', node: 'mcp:fetch_data' } });

    expect(screen.getByText('Re-run part of this run?')).toBeInTheDocument();
    expect(screen.getByText(/“mcp:fetch_data” and every step after it run again/)).toBeInTheDocument();
  });

  it('restart without a node keeps a complete sentence', () => {
    renderCard('en', { rule: 'workflow:restart_from_node' });

    expect(screen.getByText(/^A step and every step after it run again/)).toBeInTheDocument();
  });

  it('run_node names the node type', () => {
    renderCard('en', { rule: 'workflow:run_node', subject: { kind: 'node', type: 'send_email' } });

    expect(screen.getByText('Run this step now?')).toBeInTheDocument();
    expect(screen.getByText(/A “send_email” step runs right away/)).toBeInTheDocument();
  });
});

describe('mailbox:send', () => {
  it('names the recipient when the call carries one', () => {
    renderCard('en', { rule: 'mailbox:send', subject: { kind: 'mail', to: 'bob@x.io', subject: 'Hi' } });

    expect(screen.getByText(/send a message to bob@x\.io/)).toBeInTheDocument();
    expect(screen.getByTestId('tool-authorization-mail-subject')).toHaveTextContent('“Hi”');
  });

  it('lists cc and bcc recipients too, since they receive it as well', () => {
    renderCard('en', { rule: 'mailbox:send', subject: { kind: 'mail', to: 'bob@x.io', cc: 'eve@x.io', bcc: 'joe@x.io' } });

    expect(screen.getByText(/send a message to bob@x\.io, eve@x\.io, joe@x\.io from/)).toBeInTheDocument();
  });

  it('without a recipient keeps the unnamed sentence', () => {
    renderCard('en', { rule: 'mailbox:send' });

    expect(screen.getByText(/^The agent wants to send a message from the connected mailbox/)).toBeInTheDocument();
    expect(screen.queryByTestId('tool-authorization-mail-subject')).not.toBeInTheDocument();
  });
});

describe('fallback (wildcard or unknown rule)', () => {
  it('explains why it asks instead of "a sensitive action"', () => {
    renderCard('en', { rule: 'workflow:*', toolName: 'workflow', action: '*' });

    expect(screen.getByText('Run this action?')).toBeInTheDocument();
    expect(screen.getByText(/can spend credits or change things outside this chat/)).toBeInTheDocument();
  });
});

describe('details disclosure', () => {
  it('shows tool, action and the pretty-printed arguments, without repeating the action', () => {
    renderCard('en', {
      rule: 'workflow:execute',
      toolName: 'workflow',
      action: 'execute',
      argsSummary: '{"action":"execute","data_inputs":{"email":"a@b.c"}}',
    });

    const details = screen.getByTestId('tool-authorization-details');
    expect(details).toHaveTextContent('Show details');
    expect(details).toHaveTextContent('Tool: workflow');
    expect(details).toHaveTextContent('Action: execute');
    const params = screen.getByTestId('tool-authorization-params');
    expect(params.textContent).toContain('"email": "a@b.c"');
    expect(params.textContent).not.toContain('"action"');
  });

  it('falls back to the rule for tool/action and hides params when there are none', () => {
    renderCard('en', { rule: 'catalog:execute' });

    expect(screen.getByTestId('tool-authorization-details')).toHaveTextContent('Tool: catalog');
    expect(screen.queryByTestId('tool-authorization-params')).not.toBeInTheDocument();
  });
});

describe('formatArgsSummary', () => {
  it('keeps a summary the backend truncated (invalid JSON) as-is', () => {
    expect(formatArgsSummary('{"action":"execute","id":"w-1","data_inputs":{"x":"aaa...')).toBe(
      '{"action":"execute","id":"w-1","data_inputs":{"x":"aaa...',
    );
  });

  it('returns null when only the action was passed, or nothing at all', () => {
    expect(formatArgsSummary('{"action":"execute"}')).toBeNull();
    expect(formatArgsSummary(undefined)).toBeNull();
  });

  it('pretty-prints a non-object JSON value as-is', () => {
    expect(formatArgsSummary('[1,2]')).toBe('[\n  1,\n  2\n]');
    expect(formatArgsSummary('"x"')).toBe('"x"');
  });
});
