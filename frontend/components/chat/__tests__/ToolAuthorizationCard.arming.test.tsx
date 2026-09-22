// @vitest-environment jsdom
/**
 * The authorization card for the three ARMING rules, against the REAL dictionaries.
 *
 * <p>These rules exist so a person is asked before something starts running on its own: a
 * workflow going live (its schedules and webhooks begin firing), coming off the air, or an
 * agent being given a cron. The whole value is in the card NAMING what that something is -
 * "Run this action?" about a pin is a question nobody can answer. So this suite asserts
 * WORDS a reader would recognise, in two languages, never a key path: next-intl prints the
 * key when a message is missing and does not throw, so a stubbed translator would certify
 * that a key is asked for and never that it exists.
 *
 * <p>It also pins the degradations, which are the states most likely to reach a user: the
 * workflow name has to be FETCHED (agent-service has no orchestrator client and cannot send
 * it), and that fetch can fail or return nothing.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';
import frMessages from '@/messages/fr.json';
import type { PendingToolAuthorization } from '@/contexts/StreamingContext';

import { workflowService } from '@/lib/api/orchestrator/workflow.service';
import { agentService } from '@/lib/api/orchestrator/agent.service';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { ToolAuthorizationCard } from '../ToolAuthorizationCard';

// Spied on the real instances rather than vi.mock'ed: replacing either module wholesale
// breaks lib/api/orchestrator/index.ts, which binds every method of these services at
// import time and is pulled in transitively by the card's publication preview.
let getWorkflow: ReturnType<typeof vi.spyOn>;
let getAgent: ReturnType<typeof vi.spyOn>;

function renderCard(
  locale: 'en' | 'fr',
  pending: Partial<PendingToolAuthorization> & { rule: string },
  handlers: { onApproved?: (rule: string, blanket: boolean, toolCallId?: string) => void } = {},
) {
  return render(
    <NextIntlClientProvider locale={locale} messages={locale === 'en' ? enMessages : frMessages}>
      <ToolAuthorizationCard
        conversationId="conv-1"
        pendingAuthorization={{ timestamp: 1, ...pending } as PendingToolAuthorization}
        onApproved={handlers.onApproved}
      />
    </NextIntlClientProvider>,
  );
}

beforeEach(() => {
  getWorkflow = vi.spyOn(workflowService, 'getWorkflow').mockResolvedValue({ name: 'Invoice Demo' } as never);
  getAgent = vi.spyOn(agentService, 'getAgent').mockResolvedValue({ name: 'Inbox Watcher' } as never);
  // The install card's own fetch is irrelevant here and must not reach the network.
  vi.spyOn(publicationService, 'getPublicationByIdPublic').mockResolvedValue(null as never);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('workflow:pin', () => {
  it('en: names the workflow it fetched and the version taking over', async () => {
    renderCard('en', {
      rule: 'workflow:pin',
      subject: { kind: 'workflow', id: 'w-1', version: 12 },
    });

    await waitFor(() => expect(screen.getByText(/Invoice Demo/)).toBeInTheDocument());
    expect(screen.getByText(/Version 12 takes over/)).toBeInTheDocument();
    // The consequence has to be stated, not implied: this is what the user is agreeing to.
    expect(screen.getByText(/schedules and webhooks will start firing/)).toBeInTheDocument();
    expect(getWorkflow).toHaveBeenCalledWith('w-1');
  });

  it('fr: every string is French, never a key path', async () => {
    renderCard('fr', {
      rule: 'workflow:pin',
      subject: { kind: 'workflow', id: 'w-1', version: 12 },
    });

    await waitFor(() => expect(screen.getByText(/Invoice Demo/)).toBeInTheDocument());
    expect(screen.getByText(/en production/)).toBeInTheDocument();
    expect(screen.getByText(/La version 12 prendra la main/)).toBeInTheDocument();
    expect(screen.queryByText(/toolAuthorization\./)).not.toBeInTheDocument();
  });

  it('asks without naming a workflow when the fetch fails', async () => {
    // A card that cannot resolve a name must still be answerable. Rendering the NAMED copy
    // with an empty placeholder would show: Put "" live?
    getWorkflow.mockRejectedValue(new Error('offline'));
    renderCard('en', { rule: 'workflow:pin', subject: { kind: 'workflow', id: 'w-1', version: 12 } });

    await waitFor(() => expect(screen.getByText('Put this workflow live?')).toBeInTheDocument());
    expect(screen.getByText(/Version 12 takes over/)).toBeInTheDocument();
  });

  it('asks without naming a version when the call carried none', () => {
    renderCard('en', { rule: 'workflow:pin', subject: { kind: 'workflow', id: 'w-1' } });

    expect(screen.getByText(/schedules and webhooks will start firing/)).toBeInTheDocument();
    expect(screen.queryByText(/Version/)).not.toBeInTheDocument();
  });

  it('renders at all when the backend sent no subject', () => {
    // An older agent-service, or a replayed card written before the field existed. The card
    // must degrade to generic copy rather than crash or show a blank question.
    renderCard('en', { rule: 'workflow:pin' });

    expect(screen.getByText('Put this workflow live?')).toBeInTheDocument();
    expect(getWorkflow).not.toHaveBeenCalled();
  });
});

describe('workflow:unpin', () => {
  it('en: says what stops, not what starts', async () => {
    renderCard('en', { rule: 'workflow:unpin', subject: { kind: 'workflow', id: 'w-1' } });

    await waitFor(() => expect(screen.getByText(/Invoice Demo/)).toBeInTheDocument());
    expect(screen.getByText(/stop firing until a version is live again/)).toBeInTheDocument();
  });

  it('never announces a version, even if one rides along in the subject', async () => {
    // Unpin promotes nothing. "Version 12 takes over" on an unpin would be the opposite of
    // what happens.
    renderCard('en', { rule: 'workflow:unpin', subject: { kind: 'workflow', id: 'w-1', version: 12 } });

    await waitFor(() => expect(screen.getByText(/Invoice Demo/)).toBeInTheDocument());
    expect(screen.queryByText(/takes over/)).not.toBeInTheDocument();
  });
});

describe('agent:schedule', () => {
  it('en: names the agent, the cron and the zone it fires in', () => {
    renderCard('en', {
      rule: 'agent:schedule',
      subject: { kind: 'agent', name: 'Inbox Watcher', cron: '0 9 * * *', timezone: 'Europe/Paris' },
    });

    expect(screen.getByText(/Inbox Watcher/)).toBeInTheDocument();
    // The zone matters: without it the user reads the cron in their own and is wrong by hours.
    expect(screen.getByText(/0 9 \* \* \* \(Europe\/Paris\)/)).toBeInTheDocument();
    expect(screen.getByText(/run without you/)).toBeInTheDocument();
    // An agent card must not go looking for a workflow.
    expect(getWorkflow).not.toHaveBeenCalled();
  });

  it('fr: every string is French, never a key path', () => {
    renderCard('fr', {
      rule: 'agent:schedule',
      subject: { kind: 'agent', name: 'Inbox Watcher', cron: '0 9 * * *', timezone: 'UTC' },
    });

    expect(screen.getByText(/planification/)).toBeInTheDocument();
    expect(screen.getByText(/sans vous/)).toBeInTheDocument();
    expect(screen.queryByText(/toolAuthorization\./)).not.toBeInTheDocument();
  });

  it('never renders an empty cron, whatever the subject is missing', () => {
    // A replayed row written by an older agent-service arrives with no subject at all, and the
    // subtitle names a cron. Without its own bare variant the card reads:
    // "It will start itself on  (UTC) and run without you."
    renderCard('en', { rule: 'agent:schedule' });

    expect(screen.getByText('Let this agent run on a schedule?')).toBeInTheDocument();
    expect(screen.getByText('It will start itself on a schedule and run without you.')).toBeInTheDocument();
    expect(screen.queryByText(/\(UTC\)/)).not.toBeInTheDocument();
  });

  it('names the agent on an update, which carries an id rather than a name', async () => {
    // Half the newly gated agent surface is update. Without this fetch the card asks
    // "Let this agent run on a schedule?" about an agent it never identifies.
    renderCard('en', {
      rule: 'agent:schedule',
      subject: { kind: 'agent', id: 'a-1', cron: '*/10 * * * *', timezone: 'UTC' },
    });

    await waitFor(() => expect(screen.getByText(/Inbox Watcher/)).toBeInTheDocument());
    expect(getAgent).toHaveBeenCalledWith('a-1');
    expect(getWorkflow).not.toHaveBeenCalled();
    expect(screen.getByText(/\*\/10 \* \* \* \* \(UTC\)/)).toBeInTheDocument();
  });
});

describe('rules that predate the subject', () => {
  it('workflow:execute still shows the copy it always showed', () => {
    renderCard('en', { rule: 'workflow:execute' });

    expect(screen.getByText('Run this action?')).toBeInTheDocument();
    expect(screen.getByText('The agent wants to run a sensitive action')).toBeInTheDocument();
  });

  it('application:acquire still shows the install copy', () => {
    renderCard('en', { rule: 'application:acquire' });

    expect(screen.getByText('Install this application?')).toBeInTheDocument();
  });
});

describe('while the name is still resolving', () => {
  it('cannot be approved before the workflow is named, but can be declined', async () => {
    // Approving a pin before its workflow has been named is exactly the outcome this card
    // exists to prevent, and a slow orchestrator is enough to cause it. Declining needs no
    // identification, so it stays available.
    let resolveFetch: (w: unknown) => void = () => {};
    getWorkflow.mockReturnValue(new Promise((resolve) => { resolveFetch = resolve; }) as never);
    const onApproved = vi.fn();

    renderCard('en', {
      rule: 'workflow:pin',
      subject: { kind: 'workflow', id: 'w-1', version: 12 },
    }, { onApproved });

    const approve = screen.getByRole('button', { name: /Loading|Authorize/i });
    expect(approve).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Decline' })).toBeEnabled();

    await act(async () => { resolveFetch({ name: 'Invoice Demo' }); });

    await waitFor(() => expect(screen.getByRole('button', { name: 'Authorize' })).toBeEnabled());
    expect(screen.getByText(/Invoice Demo/)).toBeInTheDocument();
    expect(onApproved).not.toHaveBeenCalled();
  });

  it('stops waiting after a few seconds so a hanging fetch cannot outlast the held call', async () => {
    // apiClient's own timeout is 30 s and a park on the CLI-bridge route can be capped at 25,
    // so a hanging orchestrator would leave the user unable to answer until the hold expired
    // by itself - the card would have caused the very outcome it exists to prevent.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      getWorkflow.mockReturnValue(new Promise(() => {}) as never); // never settles

      renderCard('en', { rule: 'workflow:pin', subject: { kind: 'workflow', id: 'w-7', version: 4 } });

      expect(screen.getByRole('button', { name: /Loading|Authorize/i })).toBeDisabled();

      await act(async () => { vi.advanceTimersByTime(4500); });

      await waitFor(() => expect(screen.getByRole('button', { name: 'Authorize' })).toBeEnabled());
      // And it identifies the workflow by id, since no name ever arrived.
      expect(screen.getByTestId('tool-authorization-subject-id')).toHaveTextContent('w-7');
    } finally {
      vi.useRealTimers();
    }
  });

  it('becomes answerable again when the fetch fails, showing the id instead', async () => {
    // A card stuck disabled would be worse than an unnamed one: the held call would run out
    // its park with no way to answer.
    getWorkflow.mockRejectedValue(new Error('offline'));

    renderCard('en', { rule: 'workflow:unpin', subject: { kind: 'workflow', id: 'w-42' } });

    await waitFor(() => expect(screen.getByRole('button', { name: 'Authorize' })).toBeEnabled());
    // unpin has no version to fall back on, so without the id the card identifies nothing.
    expect(screen.getByTestId('tool-authorization-subject-id')).toHaveTextContent('w-42');
  });

  it('shows no id once a name is resolved', async () => {
    renderCard('en', { rule: 'workflow:pin', subject: { kind: 'workflow', id: 'w-1', version: 12 } });

    await waitFor(() => expect(screen.getByText(/Invoice Demo/)).toBeInTheDocument());
    expect(screen.queryByTestId('tool-authorization-subject-id')).not.toBeInTheDocument();
  });
});
