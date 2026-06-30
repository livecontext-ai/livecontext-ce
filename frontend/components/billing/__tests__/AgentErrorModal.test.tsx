/**
 * @vitest-environment jsdom
 *
 * Render test for the generic agent-error modal (edition-agnostic). It stays closed
 * until its `agentError` event fires, then renders the REAL i18n strings (en.json) and
 * closes again on the retry/dismiss action and on a backdrop click. Also covers the
 * `showAgentErrorModal()` helper dispatching the event.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, cleanup, act } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import messages from '../../../messages/en.json';

import AgentErrorModal, { showAgentErrorModal, AGENT_ERROR_EVENT } from '../AgentErrorModal';

const renderModal = () =>
  render(
    <NextIntlClientProvider locale="en" messages={messages as Record<string, unknown>}>
      <AgentErrorModal />
    </NextIntlClientProvider>,
  );

describe('AgentErrorModal', () => {
  beforeEach(() => cleanup());
  afterEach(() => cleanup());

  it('stays closed until the agentError event fires', () => {
    renderModal();
    expect(screen.queryByText('Something went wrong')).toBeNull();
  });

  it('opens on the agentError event and renders the real i18n copy', () => {
    renderModal();
    fireEvent(window, new CustomEvent(AGENT_ERROR_EVENT));

    expect(screen.getByText('Something went wrong')).toBeTruthy();
    expect(
      screen.getByText(/An error occurred while running this\. Please try again in a moment\./i),
    ).toBeTruthy();
    expect(screen.getByRole('button', { name: /try again/i })).toBeTruthy();
  });

  it('the showAgentErrorModal() helper opens it', () => {
    renderModal();
    act(() => { showAgentErrorModal(); });
    expect(screen.getByText('Something went wrong')).toBeTruthy();
  });

  it('closes on the retry/dismiss button', () => {
    renderModal();
    fireEvent(window, new CustomEvent(AGENT_ERROR_EVENT));
    expect(screen.getByText('Something went wrong')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /try again/i }));
    expect(screen.queryByText('Something went wrong')).toBeNull();
  });
});
