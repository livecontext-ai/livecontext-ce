// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { MemoryEditorModal } from '@/components/memory/MemoryEditorModal';
import type { Memory } from '@/lib/api/orchestrator/memory.service';

/**
 * The editor is where a person corrects what the agents believe, so the three
 * things pinned here are the ones that decide whether a correction survives.
 *
 * The backend refuses text that reads as an instruction and answers with the
 * sentence explaining how to rewrite it. If this form swallowed that message, the
 * person would see a save that did nothing and no way to find out why, which is
 * the worst possible outcome for a refusal designed to be actionable.
 */

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));

function entry(overrides: Partial<Memory> = {}): Memory {
  return {
    id: 'm-1',
    slug: 'release-cadence',
    title: 'Release cadence',
    summary: 'The team ships on Thursdays.',
    content: 'Long body about the release train.',
    type: 'project',
    tags: [],
    pinned: false,
    source: 'agent',
    scope: 'workspace',
    agentId: null,
    isActive: true,
    recallCount: 0,
    lastRecalledAt: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  } as Memory;
}

afterEach(cleanup);

describe('MemoryEditorModal', () => {
  it('shows the backend refusal verbatim instead of a generic failure, and keeps the form open', async () => {
    const refusal = new Error(
      "This text reads as an instruction rather than a fact. Rewrite it as something that is true, "
      + "for example 'the team prefers French' rather than 'always answer in French'.");
    const onSave = vi.fn().mockRejectedValue(refusal);
    const onClose = vi.fn();

    render(<MemoryEditorModal memory={entry()} onClose={onClose} onSave={onSave} />);
    fireEvent.click(screen.getByText('save'));

    await waitFor(() => expect(screen.getByText(refusal.message)).toBeInTheDocument());
    // Closing on a refusal would discard what the person typed along with the
    // explanation of why it was refused.
    expect(onClose).not.toHaveBeenCalled();
  });

  it('closes only once the save has actually succeeded', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    const onClose = vi.fn();

    render(<MemoryEditorModal memory={entry()} onClose={onClose} onSave={onSave} />);
    fireEvent.click(screen.getByText('save'));

    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
  });

  it('refuses to save without a title or a summary, because an entry with neither can never be recalled', () => {
    const onSave = vi.fn();

    render(<MemoryEditorModal memory={null} onClose={vi.fn()} onSave={onSave} />);

    // A new entry starts empty: the button has to be disabled, not merely fail on
    // the round trip. The summary is the line every agent carries on every run, so
    // an empty one costs the same as a good one and recalls nothing.
    const save = screen.getByText('save');
    expect(save).toBeDisabled();

    fireEvent.change(screen.getByLabelText('fieldTitle'), { target: { value: 'A title' } });
    expect(screen.getByText('save')).toBeDisabled();

    fireEvent.change(screen.getByLabelText('fieldSummary'), { target: { value: 'A summary' } });
    expect(screen.getByText('save')).not.toBeDisabled();
  });

  it('sends the pin state along with the rest of the form, not the pin alone', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);

    render(<MemoryEditorModal memory={entry()} onClose={vi.fn()} onSave={onSave} />);
    fireEvent.click(screen.getByRole('switch'));
    fireEvent.click(screen.getByText('save'));

    await waitFor(() => expect(onSave).toHaveBeenCalled());
    expect(onSave).toHaveBeenCalledWith(expect.objectContaining({
      pinned: true,
      title: 'Release cadence',
    }));
  });

  it('carries the body through unchanged when only the pin was touched, so an edit cannot blank it', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);

    render(<MemoryEditorModal memory={entry()} onClose={vi.fn()} onSave={onSave} />);
    fireEvent.click(screen.getByRole('switch'));
    fireEvent.click(screen.getByText('save'));

    await waitFor(() => expect(onSave).toHaveBeenCalled());
    // The form posts every field on every save, so the body has to be the stored
    // one. Sending an empty string here would erase an 8000-character entry for
    // someone who only wanted to pin it.
    expect(onSave.mock.calls[0][0].content).toBe('Long body about the release train.');
  });
});

describe('MemoryEditorModal - dialog behaviour', () => {
  afterEach(cleanup);

  it('names its close button in the reader\'s language rather than in English', () => {
    render(<MemoryEditorModal memory={null} onClose={() => {}} onSave={async () => {}} />);

    // The dialog ships `"Close"` written into the component, so the one control
    // on this surface with no visible label announced an English word to every
    // non-English reader. `t` is the identity function here, so the key itself
    // is the evidence that a translation is being passed at all.
    expect(screen.getByRole('button', { name: 'close' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Close' })).not.toBeInTheDocument();
  });

  it('describes the pin switch with the sentence that says what pinning costs', () => {
    render(<MemoryEditorModal memory={null} onClose={() => {}} onSave={async () => {}} />);

    // Pinning adds the full details to every run for every agent in the
    // workspace, and that hint is the reason to think twice about the toggle.
    // Left as text beside it, a screen reader reaches it only by chance.
    const hint = screen.getByText('fieldPinnedHint');
    expect(screen.getByRole('switch')).toHaveAttribute('aria-describedby', hint.id);
    expect(hint.id).toBeTruthy();
  });

  it('announces itself as a dialog named by its own heading, with the page behind it hidden', () => {
    // Something ELSE on the page, so "everything outside the dialog is hidden"
    // is a claim with a subject. With only the dialog's own containers in the
    // body the assertion below is satisfied by any implementation, including
    // the hand-rolled overlay that hid nothing.
    const behind = document.createElement('div');
    behind.textContent = 'the memory list';
    document.body.appendChild(behind);

    render(<MemoryEditorModal memory={null} onClose={() => {}} onSave={async () => {}} />);

    // Without this a screen reader announces the form as ordinary page content
    // and reads the memory list behind it as if it were still reachable.
    //
    // Asserted through the accessibility tree rather than through a hand-written
    // `aria-labelledby` id: the form now sits on the platform's `Dialog`, which
    // wires its own title and description ids, so pinning a literal id would pin
    // that component's internals instead of the property that matters. Modality
    // is likewise checked by its effect - `aria-hidden` on everything outside the
    // dialog's own portal - because that is how this Dialog achieves it, and it
    // is what a screen reader actually acts on.
    const dialog = screen.getByRole('dialog', { name: 'createTitle' });

    const portal = [...document.body.children].find((child) => child.contains(dialog));
    const exposedBehind = [...document.body.children]
      .filter((child) => child !== portal && child.getAttribute('aria-hidden') !== 'true')
      .map((child) => child.textContent);

    // Taken out before asserting: `cleanup` only removes what RTL rendered, so a
    // node left on the body would follow this file's remaining tests around.
    behind.remove();

    expect(exposedBehind).toEqual([]);
  });

  it('offers the type through the app\'s own Select, not the browser\'s', () => {
    render(<MemoryEditorModal memory={null} onClose={() => {}} onSave={async () => {}} />);

    // A native `<select>` and the app's `Select` are both exposed as a combobox,
    // so the role alone does not tell them apart - the element does. This is the
    // one control on the form that used to take its list, its arrow and its
    // highlight from the operating system, which is why it read as a different
    // product from the dialog around it.
    const type = screen.getByRole('combobox', { name: 'fieldType' });
    expect(type.tagName).toBe('BUTTON');
  });

  it('closes on Escape, so it is not dismissible only by finding its X', async () => {
    const onClose = vi.fn();
    render(<MemoryEditorModal memory={null} onClose={onClose} onSave={async () => {}} />);

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(onClose).toHaveBeenCalled();
  });

  it('ignores Escape while a save is in flight, so nobody is left unsure whether it landed', async () => {
    const onClose = vi.fn();
    // A save that never settles, which is what "in flight" means here.
    const onSave = vi.fn().mockReturnValue(new Promise(() => {}));
    render(<MemoryEditorModal memory={null} onClose={onClose} onSave={onSave} />);

    fireEvent.change(screen.getByLabelText('fieldTitle'), { target: { value: 'A title' } });
    fireEvent.change(screen.getByLabelText('fieldSummary'), { target: { value: 'A summary.' } });
    fireEvent.click(screen.getByText('save'));
    await waitFor(() => expect(onSave).toHaveBeenCalled());

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(onClose).not.toHaveBeenCalled();
  });

  it('does not close on a click beside the panel, because that would throw the body away', async () => {
    const onClose = vi.fn();
    render(<MemoryEditorModal memory={entry()} onClose={onClose} onSave={async () => {}} />);

    // The dialog arms its outside-pointer listener on a timeout, so that the
    // very click that opened it cannot immediately dismiss it. Firing before
    // that tick would hit no listener at all and pass whatever the component
    // does, which is the shape of a test that proves nothing.
    await new Promise((resolve) => setTimeout(resolve, 0));

    // The dialog this form now sits on dismisses on any outside pointerdown by
    // default. Here that default is switched OFF: the body can be 8000
    // characters a person spent minutes correcting, and a misclick beside the
    // panel would discard it with no confirmation and no undo. The overlay this
    // replaced had no dismiss handler at all, so leaving the default on would
    // have been a new way to lose work, introduced by a restyle.
    fireEvent.pointerDown(document.body, { button: 0, ctrlKey: false });
    fireEvent.mouseDown(document.body, { button: 0, ctrlKey: false });
    fireEvent.click(document.body);

    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('freezes Cancel and every field while a save is in flight', async () => {
    // A save that never settles, which is what "in flight" means here.
    const onSave = vi.fn().mockReturnValue(new Promise(() => {}));
    const onClose = vi.fn();
    render(<MemoryEditorModal memory={entry()} onClose={onClose} onSave={onSave} />);

    fireEvent.click(screen.getByText('save'));
    await waitFor(() => expect(onSave).toHaveBeenCalled());

    // Everything that could contradict the request already sent is inert: the
    // fields, so what is on screen still describes what was posted, and Cancel,
    // so the dialog cannot be torn down while the write is landing. Escape is
    // held for the same reason, one test below.
    expect(screen.getByText('cancel')).toBeDisabled();
    expect(screen.getByLabelText('fieldTitle')).toBeDisabled();
    expect(screen.getByLabelText('fieldSummary')).toBeDisabled();
    expect(screen.getByLabelText('fieldContent')).toBeDisabled();
    expect(screen.getByRole('switch')).toBeDisabled();
  });

  it('leaves no Escape handler on the document once it is closed', () => {
    const onClose = vi.fn();
    const { unmount } = render(
      <MemoryEditorModal memory={null} onClose={onClose} onSave={async () => {}} />);

    unmount();
    fireEvent.keyDown(document, { key: 'Escape' });

    // A document-level listener that outlives its component fires for every
    // Escape on the page afterwards, calling a callback whose owner is gone.
    //
    // The listener now belongs to `Dialog` rather than to this component, so
    // what this pins is the property (a closed editor swallows nothing) and not
    // who cleans up. The companion source scan is what keeps this form from
    // regressing: it fails the build if this file ever adds its own
    // `addEventListener('keydown')` back.
    expect(onClose).not.toHaveBeenCalled();
  });
});
