/**
 * @vitest-environment jsdom
 *
 * Render test for the agent-error modal (edition-agnostic). The chat renders no error
 * banner, so this modal is the only error surface: it stays closed until its
 * `agentError` event fires, then explains the failure according to its kind (with the
 * REAL en.json copy, for every kind), keeps the raw failure text under "Technical
 * details", and closes on its button, the close icon, the backdrop and Escape.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, cleanup, act } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import messages from '../../../messages/en.json';

import AgentErrorModal, { showAgentErrorModal, AGENT_ERROR_EVENT } from '../AgentErrorModal';

const copy = (messages as { modals: { agentError: { kinds: Record<string, { title: string; description: string }> } } })
  .modals.agentError.kinds;

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
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('an event without detail opens the generic copy with no technical details', () => {
    renderModal();
    fireEvent(window, new CustomEvent(AGENT_ERROR_EVENT));

    expect(screen.getByRole('dialog', { name: copy.unknown.title })).toBeTruthy();
    expect(screen.getByText(copy.unknown.description)).toBeTruthy();
    expect(screen.queryByText('Technical details')).toBeNull();
  });

  it.each([
    ["HTTP 400: Role 'function' is not supported.", undefined, 'providerRejected'],
    ['HTTP 404 - This model models/gemini-2.0-flash-lite is no longer available.', undefined, 'modelUnavailable'],
    ['Your credit balance is too low to access the Anthropic API.', undefined, 'providerBilling'],
    ['Rate limit exceeded', undefined, 'rateLimit'],
    ['prompt is too long: 210000 tokens', undefined, 'contextTooLong'],
    ['blocked by the safety filters', undefined, 'contentBlocked'],
    ['anthropic API error: 529 - Overloaded', undefined, 'providerUnavailable'],
    ['Connection reset', undefined, 'network'],
    ['Service restarting - partial response saved', 'INTERRUPTED', 'interrupted'],
    ['HTTP 500: Internal Server Error', 'SEND_FAILED', 'sendFailed'],
    ['Something odd happened', undefined, 'unknown'],
  ])('%s renders the %s copy for its kind', (message, code, kind) => {
    renderModal();
    act(() => { showAgentErrorModal({ message, code }); });

    const dialog = screen.getByRole('dialog', { name: copy[kind as string].title });
    expect(dialog.getAttribute('aria-describedby')).toBe('agent-error-description');
    expect(screen.getByText(copy[kind as string].description)).toBeTruthy();
    expect(screen.getByTestId('agent-error-detail').textContent).toBe(message);
  });

  it('only offers a dismiss button (it does not pretend to retry) and focuses it', () => {
    renderModal();
    act(() => { showAgentErrorModal({ message: 'Connection reset' }); });

    expect(screen.queryByRole('button', { name: /try again/i })).toBeNull();
    const dismiss = screen.getByRole('button', { name: 'Got it' });
    expect(document.activeElement).toBe(dismiss);
  });

  it('gives focus back to where the user was (the composer) when it closes', () => {
    renderModal();
    const composer = document.createElement('textarea');
    document.body.appendChild(composer);
    composer.focus();

    act(() => { showAgentErrorModal({ message: 'Connection reset' }); });
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Got it' }));
    fireEvent.keyDown(window, { key: 'Escape' });

    expect(document.activeElement).toBe(composer);
    composer.remove();
  });

  it('keeps Tab inside the dialog', () => {
    renderModal();
    act(() => { showAgentErrorModal({ message: 'Connection reset' }); });
    const dismiss = screen.getByRole('button', { name: 'Got it' });
    const closeIcon = screen.getByRole('button', { name: 'Close' });

    fireEvent.keyDown(window, { key: 'Tab' });
    expect(document.activeElement).toBe(closeIcon);
    fireEvent.keyDown(window, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(dismiss);
  });

  it('a very long provider body is truncated in the technical details', () => {
    renderModal();
    act(() => { showAgentErrorModal({ message: `HTTP 400: ${'x'.repeat(2000)}` }); });

    const detail = screen.getByTestId('agent-error-detail').textContent ?? '';
    expect(detail.length).toBe(403);
    expect(detail.endsWith('...')).toBe(true);
  });

  it('a second error replaces the first one', () => {
    renderModal();
    act(() => { showAgentErrorModal({ message: 'Rate limit exceeded' }); });
    act(() => { showAgentErrorModal({ message: 'HTTP 404 - This model is no longer available' }); });

    expect(screen.queryByText(copy.rateLimit.title)).toBeNull();
    expect(screen.getByText(copy.modelUnavailable.title)).toBeTruthy();
  });

  it('closes on its button, on the close icon, on the backdrop and on Escape', () => {
    renderModal();
    act(() => { showAgentErrorModal({ message: 'Connection reset' }); });
    fireEvent.click(screen.getByRole('button', { name: 'Got it' }));
    expect(screen.queryByRole('dialog')).toBeNull();

    act(() => { showAgentErrorModal({ message: 'Connection reset' }); });
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog')).toBeNull();

    act(() => { showAgentErrorModal({ message: 'Connection reset' }); });
    fireEvent.click(screen.getByTestId('agent-error-backdrop'));
    expect(screen.queryByRole('dialog')).toBeNull();

    act(() => { showAgentErrorModal({ message: 'Connection reset' }); });
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
