// @vitest-environment jsdom
/**
 * Contract for {@code prefillValues} - the seed the application's "load the example
 * values" action hands the trigger panel.
 *
 * Three properties are load-bearing:
 *
 *  1. The seed fills the inputs and NOTHING else. It must never submit: the user
 *     is being shown what the app expects, not made to run it.
 *  2. A file field is skipped. A file input cannot be populated from JS anyway,
 *     and a template's file value is a FileRef owned by the PUBLISHER's tenant -
 *     seeding it would hand the user a reference they cannot read.
 *  3. Re-seeding keys on object IDENTITY, not content. Asking twice must work
 *     even with identical values, and nothing may overwrite the user's own
 *     typing in between.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, screen, cleanup, waitFor, fireEvent, act } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));

vi.mock('@/lib/api/conversationApi', () => ({
  conversationApi: {
    findWorkflowConversation: vi.fn().mockResolvedValue(null),
    createWorkflowConversation: vi.fn(),
    getRecentMessagesAsc: vi.fn().mockResolvedValue([]),
    addMessage: vi.fn(),
  },
}));

vi.mock('@/lib/api', () => ({ orchestratorApi: { getWorkflow: vi.fn(), triggerSpecific: vi.fn() } }));
vi.mock('@/lib/api/orchestrator/file.service', () => ({ fileService: { uploadFile: vi.fn() } }));

vi.mock('@/components/chat/MessageComposer', () => ({
  MessageComposer: ({ inputValue, onSendMessage }: { inputValue?: string; onSendMessage: (c?: string) => void }) => (
    <div>
      <span data-testid="composer-value">{inputValue}</span>
      <button type="button" data-testid="composer-send" onClick={() => onSendMessage()}>send</button>
    </div>
  ),
}));

import { TriggerPanel, type TriggerPanelConfig } from '../TriggerPanel';

const formTrigger: TriggerPanelConfig = {
  triggerId: 'trigger:my_form',
  triggerLabel: 'My Form',
  type: 'form',
  submitButtonText: 'Go',
  fields: [
    { id: 'f1', name: 'city', label: 'City', type: 'text' },
    { id: 'f2', name: 'notes', label: 'Notes', type: 'textarea' },
    { id: 'f3', name: 'newsletter', label: 'Newsletter', type: 'checkbox' },
    { id: 'f4', name: 'attachment', label: 'Attachment', type: 'file' },
  ],
};

const webhookTrigger: TriggerPanelConfig = {
  triggerId: 'trigger:hook',
  triggerLabel: 'Hook',
  type: 'webhook',
  webhookMethod: 'POST',
  webhookDefaultHeaders: '{"Content-Type":"application/json"}',
  webhookDefaultBody: '{}',
};

const chatTrigger: TriggerPanelConfig = {
  triggerId: 'trigger:my_chat',
  triggerLabel: 'My Chat',
  type: 'chat',
};

function renderPanel(props: Partial<React.ComponentProps<typeof TriggerPanel>> = {}) {
  return render(
    <TriggerPanel
      isOpen
      onClose={() => {}}
      runId="run-1"
      workflowId="wf-1"
      triggerConfigs={[formTrigger]}
      onExecuteTrigger={vi.fn(async () => [])}
      {...props}
    />,
  );
}

/** The panel renders each field with {@code id={field.id}} and no name attribute. */
const fieldById = (id: string) =>
  document.getElementById(id) as HTMLInputElement | HTMLTextAreaElement | null;

beforeEach(() => {
  vi.clearAllMocks();
  (Element.prototype as unknown as { scrollIntoView: () => void }).scrollIntoView = vi.fn();
  // jsdom ships no ResizeObserver; the panel observes its anchor element with one.
  vi.stubGlobal('ResizeObserver', class {
    observe() { /* no-op */ }
    unobserve() { /* no-op */ }
    disconnect() { /* no-op */ }
  });
});
afterEach(() => cleanup());

describe('TriggerPanel - prefill from the template values', () => {
  it('fills the declared text fields with the template values', async () => {
    renderPanel({
      prefillValues: { 'trigger:my_form': { city: 'Lyon', notes: 'a note' } },
    });

    await waitFor(() => expect(fieldById('f1')?.value).toBe('Lyon'));
    expect(fieldById('f2')?.value).toBe('a note');
  });

  it('coerces a checkbox value instead of writing the string "true" into it', async () => {
    const { container } = renderPanel({
      prefillValues: { 'trigger:my_form': { newsletter: 'true' } },
    });

    await waitFor(() => {
      const checkbox = container.querySelector('[role="checkbox"], input[type="checkbox"]');
      expect(checkbox?.getAttribute('data-state') ?? checkbox?.getAttribute('aria-checked'))
        .toMatch(/checked|true/);
    });
  });

  it('keeps a file field out of the submitted payload: its template value is a FileRef in the PUBLISHER\'s tenant', async () => {
    const onExecuteTrigger = vi.fn(
      async (_triggerId: string, _type: 'chat' | 'form' | 'webhook', _payload: Record<string, unknown>) =>
        [] as string[]);
    renderPanel({
      onExecuteTrigger,
      prefillValues: {
        'trigger:my_form': {
          city: 'Lyon',
          attachment: { _type: 'file', path: 'publisher/abc.png', name: 'abc.png' },
        },
      },
    });

    await waitFor(() => expect(fieldById('f1')?.value).toBe('Lyon'));
    fireEvent.click(screen.getByText('Go'));

    await waitFor(() => expect(onExecuteTrigger).toHaveBeenCalled());
    const payload = onExecuteTrigger.mock.calls[0][2];
    expect(payload.city).toBe('Lyon');
    // The field keeps the empty value the panel initialises it with. Seeding it
    // would submit a storage reference this user cannot read.
    expect(payload.attachment).toBe('');
  });

  it('keeps a value whose field the trigger does not declare out of the payload', async () => {
    const onExecuteTrigger = vi.fn(
      async (_triggerId: string, _type: 'chat' | 'form' | 'webhook', _payload: Record<string, unknown>) =>
        [] as string[]);
    renderPanel({
      onExecuteTrigger,
      prefillValues: { 'trigger:my_form': { city: 'Lyon', ghost: 'nope' } },
    });

    await waitFor(() => expect(fieldById('f1')?.value).toBe('Lyon'));
    fireEvent.click(screen.getByText('Go'));

    await waitFor(() => expect(onExecuteTrigger).toHaveBeenCalled());
    expect(onExecuteTrigger.mock.calls[0][2]).not.toHaveProperty('ghost');
  });

  it('never submits on its own - the user still presses the button', async () => {
    const onExecuteTrigger = vi.fn(
      async (_triggerId: string, _type: 'chat' | 'form' | 'webhook', _payload: Record<string, unknown>) =>
        [] as string[]);
    renderPanel({
      prefillValues: { 'trigger:my_form': { city: 'Lyon' } },
      onExecuteTrigger,
    });

    await waitFor(() => expect(fieldById('f1')?.value).toBe('Lyon'));
    expect(onExecuteTrigger).not.toHaveBeenCalled();
  });

  it('does not clobber what the user typed after the seed', async () => {
    const values = { 'trigger:my_form': { city: 'Lyon' } };
    const { rerender } = renderPanel({ prefillValues: values });

    await waitFor(() => expect(fieldById('f1')?.value).toBe('Lyon'));
    fireEvent.change(fieldById('f1')!, { target: { value: 'Paris' } });
    expect(fieldById('f1')?.value).toBe('Paris');

    // Same object identity on re-render (a parent re-render, not a new request):
    // the seed must not fire again and overwrite the edit.
    rerender(
      <TriggerPanel
        isOpen
        onClose={() => {}}
        runId="run-1"
        workflowId="wf-1"
        triggerConfigs={[formTrigger]}
        onExecuteTrigger={vi.fn(async () => [])}
        prefillValues={values}
      />,
    );

    expect(fieldById('f1')?.value).toBe('Paris');
  });

  it('re-seeds when the caller asks again, even with identical values (fresh object identity)', async () => {
    const { rerender } = renderPanel({ prefillValues: { 'trigger:my_form': { city: 'Lyon' } } });

    await waitFor(() => expect(fieldById('f1')?.value).toBe('Lyon'));
    fireEvent.change(fieldById('f1')!, { target: { value: 'Paris' } });

    rerender(
      <TriggerPanel
        isOpen
        onClose={() => {}}
        runId="run-1"
        workflowId="wf-1"
        triggerConfigs={[formTrigger]}
        onExecuteTrigger={vi.fn(async () => [])}
        // New object, same content - this is what a second click hands down.
        prefillValues={{ 'trigger:my_form': { city: 'Lyon' } }}
      />,
    );

    await waitFor(() => expect(fieldById('f1')?.value).toBe('Lyon'));
  });

  it('fills the form on the FIRST request when its fields arrive after the seed (no second click needed)', async () => {
    // The application page rebuilds the configs from the canvas: a form trigger can
    // arrive with no fields and get them a moment later. Pre-fix the seed ran once,
    // against the empty field list, and the user had to ask a second time.
    const values = { 'trigger:my_form': { city: 'Lyon' } };
    const { rerender } = renderPanel({
      triggerConfigs: [{ ...formTrigger, fields: [] }],
      prefillValues: values,
    });

    rerender(
      <TriggerPanel
        isOpen
        onClose={() => {}}
        runId="run-1"
        workflowId="wf-1"
        triggerConfigs={[{ ...formTrigger }]}
        onExecuteTrigger={vi.fn(async () => [])}
        prefillValues={values}
      />,
    );

    await waitFor(() => expect(fieldById('f1')?.value).toBe('Lyon'));
  });

  it('does not re-seed over the user\'s typing when the configs are rebuilt with the SAME fields', async () => {
    // A rebuilt config (new object, same shape) is a parent re-render, not a new
    // shape: the user's edit must survive it.
    const values = { 'trigger:my_form': { city: 'Lyon' } };
    const { rerender } = renderPanel({ prefillValues: values });

    await waitFor(() => expect(fieldById('f1')?.value).toBe('Lyon'));
    fireEvent.change(fieldById('f1')!, { target: { value: 'Paris' } });

    rerender(
      <TriggerPanel
        isOpen
        onClose={() => {}}
        runId="run-1"
        workflowId="wf-1"
        triggerConfigs={[{ ...formTrigger, fields: formTrigger.fields!.map(f => ({ ...f })) }]}
        onExecuteTrigger={vi.fn(async () => [])}
        prefillValues={values}
      />,
    );

    expect(fieldById('f1')?.value).toBe('Paris');
  });

  it('seeds the form when the panel is OPENED by the same click that loads the values', async () => {
    // The real sequence: the panel is mounted closed, and one click both opens it and
    // hands down the values.
    const values = { 'trigger:my_form': { city: 'Lyon' } };
    const { rerender } = renderPanel({ isOpen: false, prefillValues: null });

    rerender(
      <TriggerPanel
        isOpen
        onClose={() => {}}
        runId="run-1"
        workflowId="wf-1"
        triggerConfigs={[formTrigger]}
        onExecuteTrigger={vi.fn(async () => [])}
        prefillValues={values}
      />,
    );

    await waitFor(() => expect(fieldById('f1')?.value).toBe('Lyon'));
  });

  it('seeds a chat trigger\'s message box', async () => {
    renderPanel({
      triggerConfigs: [chatTrigger],
      prefillValues: { 'trigger:my_chat': { message: 'summarise this' } },
    });

    await waitFor(() =>
      expect(screen.getByTestId('composer-value').textContent).toBe('summarise this'));
  });

  it('seeds a webhook trigger\'s body with the template payload as JSON', async () => {
    renderPanel({
      triggerConfigs: [webhookTrigger],
      prefillValues: { 'trigger:hook': { orderId: 7, status: 'paid' } },
    });

    await waitFor(() => {
      const body = Array.from(document.querySelectorAll('textarea'))
        .map(t => (t as HTMLTextAreaElement).value)
        .find(v => v.includes('orderId'));
      expect(body).toBeDefined();
      expect(JSON.parse(body!)).toEqual({ orderId: 7, status: 'paid' });
    });
  });

  it('seeds a multi-choice field from a single template value instead of dropping it', async () => {
    const onExecuteTrigger = vi.fn(
      async (_triggerId: string, _type: 'chat' | 'form' | 'webhook', _payload: Record<string, unknown>) =>
        [] as string[]);
    renderPanel({
      onExecuteTrigger,
      triggerConfigs: [{
        ...formTrigger,
        fields: [{ id: 'm1', name: 'tags', label: 'Tags', type: 'multiselect', options: [
          { label: 'A', value: 'a' }, { label: 'B', value: 'b' },
        ] }],
      }],
      // The publisher picked ONE option, so the stored value is a scalar. Requiring an
      // array here would silently drop the seed for every single-choice example.
      prefillValues: { 'trigger:my_form': { tags: 'a' } },
    });

    await act(async () => { await Promise.resolve(); });
    fireEvent.click(screen.getByText('Go'));

    await waitFor(() => expect(onExecuteTrigger).toHaveBeenCalled());
    expect(onExecuteTrigger.mock.calls[0][2].tags).toEqual(['a']);
  });

  it('seeds only the webhook BODY, leaving the method the user chose alone', async () => {
    const { rerender } = renderPanel({ triggerConfigs: [webhookTrigger] });

    await act(async () => { await Promise.resolve(); });
    // Asserted, not guarded: if the panel ever swaps the native control for a pure Radix
    // one, this test must fail loudly rather than quietly stop asserting anything.
    const methodSelect = document.querySelector('select') as HTMLSelectElement | null;
    expect(methodSelect).not.toBeNull();
    fireEvent.change(methodSelect!, { target: { value: 'PUT' } });

    rerender(
      <TriggerPanel
        isOpen
        onClose={() => {}}
        runId="run-1"
        workflowId="wf-1"
        triggerConfigs={[webhookTrigger]}
        onExecuteTrigger={vi.fn(async () => [])}
        prefillValues={{ 'trigger:hook': { orderId: 7 } }}
      />,
    );

    await waitFor(() => {
      const body = Array.from(document.querySelectorAll('textarea'))
        .map(t => (t as HTMLTextAreaElement).value)
        .find(v => v.includes('orderId'));
      expect(body).toBeDefined();
    });
    // The method and headers are the user's own request setup, not template content.
    // Re-queried from the live DOM so a re-render cannot leave a stale node under test.
    expect((document.querySelector('select') as HTMLSelectElement).value).toBe('PUT');
  });

  it('is a no-op when no template values are supplied', async () => {
    renderPanel({ prefillValues: null });

    await waitFor(() => expect(fieldById('f1')).not.toBeNull());
    expect(fieldById('f1')?.value).toBe('');
  });

  it('ignores values addressed to a trigger that is not on the panel', async () => {
    renderPanel({
      prefillValues: { 'trigger:some_other_form': { city: 'Lyon' } },
    });

    await waitFor(() => expect(fieldById('f1')).not.toBeNull());
    expect(fieldById('f1')?.value).toBe('');
  });
});
