/**
 * @vitest-environment jsdom
 *
 * The question card is the person's only way to answer an ask_user call. It walks the
 * questions one at a time: a single pick moves straight on, the last question always waits
 * for Send, a multi pick or a typed "Other" needs Next/Send, Back keeps the earlier pick, and
 * the answers are handed back keyed by the question header (how the agent reads them).
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { AskUserQuestionCard } from '../AskUserQuestionCard';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, params?: Record<string, unknown>) =>
    params ? `${key}:${Object.values(params).join('/')}` : key,
}));

const pending = {
  toolCallId: 'call-7',
  blocking: true,
  gateKey: 'call-7:ask',
  timestamp: 1,
  questions: [
    {
      header: 'Tone',
      question: 'Which tone?',
      options: [{ label: 'Friendly', description: 'Warm' }, { label: 'Formal' }],
      multiSelect: false,
    },
    {
      header: 'Channels',
      question: 'Where?',
      options: [{ label: 'X' }, { label: 'LinkedIn' }, { label: 'Mail' }],
      multiSelect: true,
    },
  ],
};

const singleOnly = {
  ...pending,
  toolCallId: 'call-8',
  questions: [pending.questions[0]],
};

function renderCard(onSubmit = vi.fn(), onDismiss = vi.fn(), question = pending) {
  render(<AskUserQuestionCard conversationId="conv-1" pendingQuestion={question} onSubmit={onSubmit} onDismiss={onDismiss} />);
  return { onSubmit, onDismiss };
}

describe('AskUserQuestionCard', () => {
  it('shows ONE question at a time with its options, an Other row, the step counter, and says the assistant is waiting', () => {
    renderCard();

    expect(screen.getAllByTestId('ask-user-question')).toHaveLength(1);
    expect(screen.getByText('Which tone?')).toBeInTheDocument();
    expect(screen.getByText('Warm')).toBeInTheDocument();
    expect(screen.queryByText('Where?')).not.toBeInTheDocument();
    expect(screen.getAllByTestId('ask-user-other')).toHaveLength(1);
    expect(screen.getByTestId('ask-user-waiting')).toBeInTheDocument();
    expect(screen.getByText('stepOf:1/2')).toBeInTheDocument();
    expect(screen.getByTestId('ask-user-progress')).toBeInTheDocument();
    // Nothing to go back to yet, and no Send before the last question.
    expect(screen.queryByTestId('ask-user-back')).not.toBeInTheDocument();
    expect(screen.queryByTestId('ask-user-submit')).not.toBeInTheDocument();
    expect(screen.getByTestId('ask-user-next')).toBeDisabled();
  });

  it('a card whose turn is over says the answer will start the assistant again, not that it is waiting', () => {
    renderCard(vi.fn(), vi.fn(), { ...pending, blocking: false, gateKey: undefined });

    expect(screen.queryByTestId('ask-user-waiting')).not.toBeInTheDocument();
    expect(screen.getByTestId('ask-user-resumes')).toHaveTextContent('answerResumes');
  });

  it('a single pick moves straight to the next question, and Send stays disabled until it is answered too', () => {
    const { onSubmit } = renderCard();

    fireEvent.click(screen.getByText('Friendly'));

    expect(screen.getByText('Where?')).toBeInTheDocument();
    expect(screen.getByText('stepOf:2/2')).toBeInTheDocument();
    const submit = screen.getByTestId('ask-user-submit');
    expect(submit).toBeDisabled();

    fireEvent.click(screen.getByText('X'));
    expect(submit).toBeEnabled();
    expect(onSubmit).not.toHaveBeenCalled();

    fireEvent.click(submit);
    expect(onSubmit).toHaveBeenCalledWith(
      [
        { header: 'Tone', selected: ['Friendly'] },
        { header: 'Channels', selected: ['X'] },
      ],
      'call-7',
    );
  });

  it('a single-question call shows no step chrome, and waits for Send so a slip can be undone', () => {
    const { onSubmit } = renderCard(vi.fn(), vi.fn(), singleOnly);

    expect(screen.getByText('subtitle')).toBeInTheDocument();
    expect(screen.queryByText(/stepOf/)).not.toBeInTheDocument();
    expect(screen.queryByTestId('ask-user-progress')).not.toBeInTheDocument();
    expect(screen.queryByTestId('ask-user-back')).not.toBeInTheDocument();
    expect(screen.getByTestId('ask-user-submit')).toBeDisabled();

    fireEvent.click(screen.getByText('Formal'));
    expect(onSubmit).not.toHaveBeenCalled();
    // The slip: the person meant Friendly. Still fixable.
    fireEvent.click(screen.getByText('Friendly'));
    fireEvent.click(screen.getByTestId('ask-user-submit'));

    expect(onSubmit).toHaveBeenCalledWith([{ header: 'Tone', selected: ['Friendly'] }], 'call-8');
  });

  it('arrows move between the options without picking, Enter picks the focused one', () => {
    const { onSubmit } = renderCard();
    const [friendly, formal] = screen.getAllByTestId('ask-user-option') as HTMLInputElement[];
    friendly.focus();

    fireEvent.keyDown(friendly, { key: 'ArrowDown' });

    // A native radio group would have CHECKED Formal here, and the card would have turned
    // the page on it. Focus moved, nothing picked, same question.
    expect(document.activeElement).toBe(formal);
    expect(formal.checked).toBe(false);
    expect(screen.getByText('Which tone?')).toBeInTheDocument();

    fireEvent.keyDown(formal, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(screen.getByTestId('ask-user-other'));
    fireEvent.keyDown(screen.getByTestId('ask-user-other'), { key: 'ArrowDown' });
    expect(document.activeElement).toBe(friendly);

    fireEvent.keyDown(friendly, { key: 'ArrowUp' });
    expect(document.activeElement).toBe(screen.getByTestId('ask-user-other'));
    fireEvent.keyDown(screen.getByTestId('ask-user-other'), { key: 'ArrowUp' });
    fireEvent.keyDown(formal, { key: 'Enter' });

    expect(screen.getByText('Where?')).toBeInTheDocument();
    fireEvent.click(screen.getByText('X'));
    fireEvent.click(screen.getByTestId('ask-user-submit'));
    expect(onSubmit.mock.calls[0][0][0]).toEqual({ header: 'Tone', selected: ['Formal'] });
  });

  it('while a submit is in flight, digits and Enter change nothing', async () => {
    let release: (ok: boolean) => void = () => {};
    const onSubmit = vi.fn(() => new Promise<boolean>(resolve => { release = resolve; }));
    renderCard(onSubmit);
    fireEvent.click(screen.getByText('Friendly'));
    fireEvent.click(screen.getByText('X'));
    fireEvent.click(screen.getByTestId('ask-user-submit'));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));

    fireEvent.keyDown(screen.getByText('Where?'), { key: '2' });
    fireEvent.keyDown(screen.getByTestId('ask-user-back'), { key: 'Enter' });

    expect((screen.getAllByTestId('ask-user-option')[1] as HTMLInputElement).checked).toBe(false);
    expect(screen.getByText('Where?')).toBeInTheDocument();
    expect(onSubmit).toHaveBeenCalledTimes(1);
    release(true);
  });

  it('the last question of a multi-question call waits for Send, so the whole set can still be reviewed', () => {
    const lastIsSingle = {
      ...pending,
      toolCallId: 'call-9',
      questions: [pending.questions[1], pending.questions[0]],
    };
    const { onSubmit } = renderCard(vi.fn(), vi.fn(), lastIsSingle);
    fireEvent.click(screen.getByText('X'));
    fireEvent.click(screen.getByTestId('ask-user-next'));
    expect(screen.getByText('Which tone?')).toBeInTheDocument();

    fireEvent.click(screen.getByText('Formal'));

    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByTestId('ask-user-back')).toBeEnabled();
    fireEvent.click(screen.getByTestId('ask-user-submit'));
    expect(onSubmit.mock.calls[0][0]).toEqual([
      { header: 'Channels', selected: ['X'] },
      { header: 'Tone', selected: ['Formal'] },
    ]);
  });

  it('Enter in the free-text field of the LAST question hands the focus to Send instead of sending', () => {
    const { onSubmit } = renderCard(vi.fn(), vi.fn(), singleOnly);
    fireEvent.click(screen.getByTestId('ask-user-other'));
    const other = screen.getByTestId('ask-user-other-text');
    fireEvent.change(other, { target: { value: 'Playful' } });

    fireEvent.keyDown(other, { key: 'Enter' });

    // The envelope never leaves on a keystroke: a typo can still be fixed, and a second
    // Enter on the focused Send is a deliberate send.
    expect(onSubmit).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(screen.getByTestId('ask-user-submit'));
    fireEvent.click(screen.getByTestId('ask-user-submit'));
    expect(onSubmit.mock.calls[0][0]).toEqual([{ header: 'Tone', selected: [], freeText: 'Playful' }]);
  });

  it('Next keeps the focus after it is pressed, so it can be pressed again from the keyboard', () => {
    const three = {
      ...pending,
      toolCallId: 'call-10',
      questions: [pending.questions[1], pending.questions[0], { ...pending.questions[1], header: 'Later' }],
    };
    renderCard(vi.fn(), vi.fn(), three);
    fireEvent.click(screen.getByText('X'));
    const next = screen.getByTestId('ask-user-next');
    next.focus();

    fireEvent.click(next);

    // Step 2 of 3: Next is still on screen, and still under the finger. Pulling the focus
    // to the card here would force a re-tab before every further press.
    expect(screen.getByText('Which tone?')).toBeInTheDocument();
    expect(document.activeElement).toBe(screen.getByTestId('ask-user-next'));
  });

  it('a pick that turns the page hands the orphaned focus back to the card, so digits keep working', () => {
    renderCard();
    const friendly = screen.getAllByTestId('ask-user-option')[0] as HTMLInputElement;
    friendly.focus();

    fireEvent.click(friendly);

    // The focused radio was unmounted with its step; the card takes over rather than the body.
    expect(screen.getByText('Where?')).toBeInTheDocument();
    const cardGroup = screen.getByRole('group', { name: 'title' });
    expect(document.activeElement).toBe(cardGroup);
    fireEvent.keyDown(cardGroup, { key: '2' });
    expect((screen.getAllByTestId('ask-user-option')[1] as HTMLInputElement).checked).toBe(true);
  });

  it('announces the step and the question in a stable live region, and shows digit hints only while focused', () => {
    renderCard();
    const announce = screen.getByTestId('ask-user-announce');
    expect(announce).toHaveAttribute('aria-live', 'polite');
    expect(announce).toHaveTextContent('stepOf:1/2 Which tone?');

    // Nothing else had the focus in this document, so the card took it on mount (the same
    // rescue a page turn does); hand it back to the page and the hints go with it.
    const cardGroup = screen.getByRole('group', { name: 'title' });
    fireEvent.blur(cardGroup, { relatedTarget: null });
    expect(screen.queryByText('1', { selector: 'kbd' })).not.toBeInTheDocument();
    fireEvent.focus(cardGroup);
    expect(screen.getByText('1', { selector: 'kbd' })).toBeInTheDocument();

    // Focus moving INSIDE the card (an option to Next) is not a blur of the card: the hints stay.
    fireEvent.blur(cardGroup, { relatedTarget: screen.getByTestId('ask-user-next') });
    expect(screen.getByText('1', { selector: 'kbd' })).toBeInTheDocument();

    fireEvent.click(screen.getByText('Friendly'));
    // Same node, new text: that is what makes a screen reader read it.
    expect(screen.getByTestId('ask-user-announce')).toBe(announce);
    expect(announce).toHaveTextContent('stepOf:2/2 Where?');
  });

  it('a single question gets no live region: nothing on it ever changes, and the question is read once', () => {
    renderCard(vi.fn(), vi.fn(), singleOnly);
    expect(screen.queryByTestId('ask-user-announce')).not.toBeInTheDocument();
    expect(screen.getAllByText('Which tone?')).toHaveLength(1);
  });

  it('a digit under Ctrl, Cmd or Alt is the browser\'s chord, never a pick', () => {
    const { onSubmit } = renderCard();
    const group = screen.getByRole('group', { name: 'title' });

    fireEvent.keyDown(group, { key: '2', ctrlKey: true });
    fireEvent.keyDown(group, { key: '2', metaKey: true });
    fireEvent.keyDown(group, { key: '2', altKey: true });

    expect(screen.getByText('Which tone?')).toBeInTheDocument();
    expect((screen.getAllByTestId('ask-user-option')[1] as HTMLInputElement).checked).toBe(false);
    expect(onSubmit).not.toHaveBeenCalled();

    // Shift is NOT a chord here: on AZERTY and QWERTZ a digit needs Shift to be typed at all.
    fireEvent.keyDown(group, { key: '2', shiftKey: true });
    expect(screen.getByText('Where?')).toBeInTheDocument();

    // The same chords on an option do not move the focus either (Cmd+ArrowDown scrolls).
    fireEvent.click(screen.getByTestId('ask-user-back'));
    const [friendly, formal] = screen.getAllByTestId('ask-user-option') as HTMLInputElement[];
    friendly.focus();
    fireEvent.keyDown(friendly, { key: 'ArrowDown', metaKey: true });
    expect(document.activeElement).toBe(friendly);
    fireEvent.keyDown(friendly, { key: 'Enter', ctrlKey: true });
    expect(friendly.checked).toBe(false);
    // The digit hints are visual only: they must not join the options' accessible names.
    fireEvent.focus(group);
    for (const hint of document.querySelectorAll('kbd')) {
      expect(hint).toHaveAttribute('aria-hidden', 'true');
    }
  });

  it('an element focused outside the card keeps the focus when the card mounts', () => {
    const outside = document.createElement('button');
    document.body.appendChild(outside);
    outside.focus();
    try {
      renderCard();
      expect(document.activeElement).toBe(outside);
    } finally {
      outside.remove();
    }
  });

  it('digits typed into the free-text field are text, never a pick', () => {
    const { onSubmit } = renderCard();
    fireEvent.click(screen.getByTestId('ask-user-other'));
    const other = screen.getByTestId('ask-user-other-text');

    fireEvent.keyDown(other, { key: '2' });
    fireEvent.change(other, { target: { value: '2 tones' } });

    // Still on the first question, "Other" still the pick, Formal (option 2) untouched.
    expect(screen.getByText('Which tone?')).toBeInTheDocument();
    expect((screen.getByTestId('ask-user-other') as HTMLInputElement).checked).toBe(true);
    expect((screen.getAllByTestId('ask-user-option')[1] as HTMLInputElement).checked).toBe(false);
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('Back onto an Other answer leaves the focus in its text field, so typing keeps typing', () => {
    renderCard();
    fireEvent.click(screen.getByTestId('ask-user-other'));
    const other = screen.getByTestId('ask-user-other-text');
    fireEvent.change(other, { target: { value: 'Playful' } });
    fireEvent.keyDown(other, { key: 'Enter' });
    expect(screen.getByText('Where?')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('ask-user-back'));

    const field = screen.getByTestId('ask-user-other-text') as HTMLInputElement;
    expect(field.value).toBe('Playful');
    expect(document.activeElement).toBe(field);
    // A digit here is text: the card must not have taken the focus and read it as a pick.
    fireEvent.keyDown(field, { key: '1' });
    expect((screen.getByTestId('ask-user-other') as HTMLInputElement).checked).toBe(true);
    expect((screen.getAllByTestId('ask-user-option')[0] as HTMLInputElement).checked).toBe(false);
  });

  it('Skip mid-wizard dismisses the whole call, not just the current question', () => {
    const { onDismiss, onSubmit } = renderCard();
    fireEvent.click(screen.getByText('Friendly'));
    expect(screen.getByText('Where?')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('ask-user-skip'));

    expect(onDismiss).toHaveBeenCalledWith('call-7');
    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByTestId('ask-user-submit')).toBeDisabled();
    expect(screen.getByTestId('ask-user-back')).toBeDisabled();
  });

  it('multi-select accumulates and never advances on its own', () => {
    const { onSubmit } = renderCard();
    fireEvent.click(screen.getByText('Friendly'));

    fireEvent.click(screen.getByText('X'));
    fireEvent.click(screen.getByText('LinkedIn'));
    expect(screen.getByText('Where?')).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('ask-user-submit'));
    expect(onSubmit.mock.calls[0][0]).toEqual([
      { header: 'Tone', selected: ['Friendly'] },
      { header: 'Channels', selected: ['X', 'LinkedIn'] },
    ]);
  });

  it('Back returns to the previous question with its pick kept, and a new pick replaces it', () => {
    const { onSubmit } = renderCard();
    fireEvent.click(screen.getByText('Friendly'));
    expect(screen.getByText('Where?')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('ask-user-back'));
    expect(screen.getByText('Which tone?')).toBeInTheDocument();
    expect(screen.getByText('stepOf:1/2')).toBeInTheDocument();
    expect((screen.getAllByTestId('ask-user-option')[0] as HTMLInputElement).checked).toBe(true);
    // The earlier answer is enough for Next; the person may also just re-pick.
    expect(screen.getByTestId('ask-user-next')).toBeEnabled();

    fireEvent.click(screen.getByText('Formal'));
    fireEvent.click(screen.getByText('Mail'));
    fireEvent.click(screen.getByTestId('ask-user-submit'));

    expect(onSubmit.mock.calls[0][0]).toEqual([
      { header: 'Tone', selected: ['Formal'] },
      { header: 'Channels', selected: ['Mail'] },
    ]);
  });

  it('Other stays on the question until text is typed, then Enter moves on and the text is sent as freeText with no selection', () => {
    const { onSubmit } = renderCard();

    fireEvent.click(screen.getByTestId('ask-user-other'));
    expect(screen.getByText('Which tone?')).toBeInTheDocument();
    expect(screen.getByTestId('ask-user-next')).toBeDisabled();

    const other = screen.getByTestId('ask-user-other-text');
    fireEvent.change(other, { target: { value: 'Playful' } });
    expect(screen.getByTestId('ask-user-next')).toBeEnabled();
    fireEvent.keyDown(other, { key: 'Enter' });

    expect(screen.getByText('Where?')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Mail'));
    fireEvent.click(screen.getByTestId('ask-user-submit'));

    expect(onSubmit.mock.calls[0][0][0]).toEqual({ header: 'Tone', selected: [], freeText: 'Playful' });
  });

  it('digit keys pick the option in that position', () => {
    const { onSubmit } = renderCard();

    fireEvent.keyDown(screen.getByText('Which tone?'), { key: '2' });
    expect(screen.getByText('Where?')).toBeInTheDocument();

    fireEvent.keyDown(screen.getByText('Where?'), { key: '1' });
    fireEvent.keyDown(screen.getByText('Where?'), { key: '3' });
    fireEvent.click(screen.getByTestId('ask-user-submit'));

    expect(onSubmit.mock.calls[0][0]).toEqual([
      { header: 'Tone', selected: ['Formal'] },
      { header: 'Channels', selected: ['X', 'Mail'] },
    ]);
  });

  it('a submit the caller could not record puts the form back with the picks kept', async () => {
    const onSubmit = vi.fn().mockResolvedValueOnce(false).mockResolvedValueOnce(true);
    renderCard(onSubmit);
    fireEvent.click(screen.getByText('Formal'));
    fireEvent.click(screen.getByText('Mail'));
    fireEvent.click(screen.getByTestId('ask-user-submit'));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    // Still the editable card, with the same picks, and Send usable again.
    expect(screen.getByTestId('ask-user-card')).toBeInTheDocument();
    expect(screen.getByTestId('ask-user-submit')).toBeEnabled();

    fireEvent.click(screen.getByTestId('ask-user-submit'));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(2));
    expect(onSubmit.mock.calls[1][0]).toEqual([
      { header: 'Tone', selected: ['Formal'] },
      { header: 'Channels', selected: ['Mail'] },
    ]);
  });

  it.each(['false', 'reject'])('a failed Skip (%s) unlocks the real card for another attempt', async (failure) => {
    const onDismiss = vi.fn();
    if (failure === 'false') onDismiss.mockResolvedValueOnce(false);
    else onDismiss.mockRejectedValueOnce(new Error('network'));
    onDismiss.mockResolvedValueOnce(true);
    render(<AskUserQuestionCard conversationId="conversation-1" pendingQuestion={pending} onDismiss={onDismiss} />);

    fireEvent.click(screen.getByTestId('ask-user-skip'));

    await waitFor(() => expect(screen.getByTestId('ask-user-skip')).toBeEnabled());
    fireEvent.click(screen.getByTestId('ask-user-skip'));
    expect(onDismiss).toHaveBeenCalledTimes(2);
  });

  it('Skip dismisses with the toolCallId and locks the form while the caller removes the card', () => {
    const { onDismiss, onSubmit } = renderCard();

    fireEvent.click(screen.getByTestId('ask-user-skip'));

    expect(onDismiss).toHaveBeenCalledWith('call-7');
    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByTestId('ask-user-next')).toBeDisabled();
    expect(screen.getAllByTestId('ask-user-option')[0]).toBeDisabled();
  });

  it('the free-text field is capped at the server limit so a long paste is refused before it is sent', () => {
    renderCard();
    fireEvent.click(screen.getByTestId('ask-user-other'));
    expect(screen.getByTestId('ask-user-other-text')).toHaveAttribute('maxLength', '4000');
  });
});
