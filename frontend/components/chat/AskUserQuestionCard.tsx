'use client';

import React, { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import { ArrowLeft, MessageCircleQuestion } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import type { PendingAskUserQuestion } from '@/contexts/StreamingContext';

/** One submitted answer, in the shape the backend and the agent read. */
export interface AskUserAnswer {
  header: string;
  selected: string[];
  freeText?: string;
}

export interface AskUserQuestionCardProps {
  conversationId: string;
  pendingQuestion: PendingAskUserQuestion;
  /**
   * Submit - receives the answers and the card's toolCallId so the caller clears THIS card.
   * Resolves to false when the answer could not be recorded, in which case the card returns
   * to its editable state so the person can submit again.
   */
  onSubmit?: (answers: AskUserAnswer[], toolCallId: string) => Promise<boolean> | boolean | void;
  /** Skip - resolves false on failure so the user can retry. */
  onDismiss?: (toolCallId: string) => Promise<boolean> | boolean | void;
  className?: string;
}

/** Sentinel for the always-present free-text row; never a label the agent can declare. */
const OTHER = '__other__';

type Draft = { selected: Set<string>; other: string };

const isComplete = (d: Draft): boolean =>
  Array.from(d.selected).some(v => v !== OTHER) || (d.selected.has(OTHER) && d.other.trim().length > 0);

/**
 * Inline card shown when the agent asks the user a multiple-choice question (ask_user tool).
 *
 * The questions of one call are walked one at a time, like a short wizard: the card shows
 * the current question, its options, and an "Other" row that reveals a free-text field.
 * Picking an option on a single-choice question moves straight to the next one, so a call
 * with several questions costs one click per question. The LAST question always waits
 * for Send: it is the only moment the answers can still be reviewed (Back walks them) or
 * a slip undone, and one click there is cheaper than an envelope sent by mistake and read
 * by the agent as final. Multi-choice questions and a typed "Other" need a Next/Send press
 * too, since the card cannot know when the person is done. Once the answer is recorded the
 * caller removes the card; the transcript's tool row shows what was chosen.
 *
 * Every pick is also reachable from the keyboard: a digit picks the option in that
 * position (the "Other" row is the position after the last option, so 1-5 at most; a
 * digit under Ctrl, Cmd or Alt is the browser's, never a pick), arrows move between
 * the options WITHOUT picking (a native radio group would pick on arrow, which here would
 * also turn the page), Space or Enter picks the focused option, and Enter in the free-text
 * field moves on (on the last question it hands the focus to Send instead: the envelope
 * never leaves on a keystroke).
 */
export function AskUserQuestionCard({
  pendingQuestion,
  onSubmit,
  onDismiss,
  className = '',
}: AskUserQuestionCardProps) {
  const t = useTranslations('askUser');
  const headingId = useId();
  const questions = useMemo(() => pendingQuestion.questions ?? [], [pendingQuestion.questions]);
  const [drafts, setDrafts] = useState<Draft[]>(() =>
    questions.map(() => ({ selected: new Set<string>(), other: '' })));
  const [step, setStep] = useState(0);
  const [pending, setPending] = useState(false);
  // Whether the keyboard is on the card: the digit hints only mean something then.
  const [hasFocus, setHasFocus] = useState(false);

  const total = questions.length;
  const last = step >= total - 1;
  const question = questions[step];
  const draft = drafts[step];
  const multi = question?.multiSelect === true;
  const groupName = `ask-user-${pendingQuestion.toolCallId}-${step}`;
  const stepComplete = draft ? isComplete(draft) : false;
  const allComplete = useMemo(() => drafts.every(isComplete), [drafts]);

  const buildAnswers = useCallback((source: Draft[]): AskUserAnswer[] => questions.map((q, i) => {
    const d = source[i];
    const selected = Array.from(d.selected).filter(v => v !== OTHER);
    const freeText = d.selected.has(OTHER) && d.other.trim() ? d.other.trim() : undefined;
    return { header: q.header, selected, ...(freeText ? { freeText } : {}) };
  }), [questions]);

  const submit = useCallback(async (source: Draft[]) => {
    if (pending || !source.every(isComplete)) return;
    setPending(true);
    let ok: boolean | void = true;
    try {
      ok = await onSubmit?.(buildAnswers(source), pendingQuestion.toolCallId);
    } catch {
      ok = false;
    }
    if (ok === false) {
      // Not recorded: keep the person's picks and let them try again. A recorded answer
      // unmounts the card (the caller clears it); the transcript row keeps what was chosen.
      setPending(false);
    }
  }, [pending, onSubmit, buildAnswers, pendingQuestion.toolCallId]);

  /**
   * Move on from the current question. Never called on the last one: sending is Send's
   * job alone, so no keystroke or pick can post the envelope by itself.
   */
  const advance = useCallback((source: Draft[]) => {
    if (last || !isComplete(source[step])) return;
    setStep(step + 1);
  }, [step, last]);

  /**
   * Whether a single pick on the current question may move on by itself. Never on the last
   * question: that step is where the answers can still be reviewed or a slip undone before
   * the envelope leaves, so it waits for Send.
   */
  const autoAdvance = !multi && !last;

  const toggle = (value: string) => {
    if (pending || !draft) return;
    const next = new Set(draft.selected);
    if (multi) {
      if (next.has(value)) next.delete(value); else next.add(value);
    } else {
      next.clear();
      next.add(value);
    }
    const updated = drafts.map((d, i) => (i === step ? { ...d, selected: next } : d));
    setDrafts(updated);
    // A single pick IS the answer: no second click to confirm it. "Other" still needs the
    // text typed, so it stays on the question.
    if (autoAdvance && value !== OTHER) advance(updated);
  };

  const setOther = (text: string) => {
    setDrafts(prev => prev.map((d, i) => (i === step ? { ...d, other: text } : d)));
  };

  const back = () => {
    if (pending || step === 0) return;
    setStep(step - 1);
  };

  const dismiss = async () => {
    if (pending) return;
    setPending(true);
    try {
      if (await onDismiss?.(pendingQuestion.toolCallId) === false) setPending(false);
    } catch {
      setPending(false);
    }
  };

  /**
   * Arrows move the focus between the options without picking. Left to the browser, an
   * arrow on a radio group CHECKS the neighbour, which here would answer the question and
   * turn the page before the person has read the options. Enter picks the focused option
   * (Space already does, natively).
   */
  const onOptionKeyDown = (e: React.KeyboardEvent<HTMLInputElement>, value: string) => {
    // A chord (Cmd+ArrowDown scrolls, Ctrl+1 switches tab) belongs to the browser.
    if (pending || e.ctrlKey || e.metaKey || e.altKey) return;
    const arrows: Record<string, number> = { ArrowDown: 1, ArrowRight: 1, ArrowUp: -1, ArrowLeft: -1 };
    const delta = arrows[e.key];
    if (delta !== undefined) {
      e.preventDefault();
      const group = e.currentTarget.closest('fieldset');
      const inputs = group ? Array.from(group.querySelectorAll('input')).filter(i => i.name === groupName) : [];
      const at = inputs.indexOf(e.currentTarget);
      if (inputs.length > 0 && at >= 0) {
        inputs[(at + delta + inputs.length) % inputs.length].focus();
      }
      return;
    }
    if (e.key === 'Enter') {
      e.preventDefault();
      toggle(value);
    }
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    // Ctrl+2 / Cmd+2 switches the browser tab; reading it as "pick option 2" would answer a
    // question nobody meant to answer, and turn the page on it.
    if (pending || !question || e.ctrlKey || e.metaKey || e.altKey) return;
    const typing = (e.target as HTMLElement).tagName === 'INPUT' && (e.target as HTMLInputElement).type === 'text';
    if (e.key === 'Enter' && typing) {
      e.preventDefault();
      if (last) {
        // The last question waits for Send whatever the input: hand it the focus, so a
        // second Enter sends deliberately, and a typo can still be fixed.
        submitRef.current?.focus();
      } else {
        advance(drafts);
      }
      return;
    }
    if (typing) return;
    if (/^[1-9]$/.test(e.key)) {
      const idx = Number(e.key) - 1;
      const labels = [...question.options.map(o => o.label), OTHER];
      if (idx < labels.length) {
        e.preventDefault();
        toggle(labels[idx]);
      }
    }
  };

  const submitRef = useRef<HTMLButtonElement>(null);

  // When the step change unmounted the focused option (a pick that turned the page), the
  // focus falls to the body and the digit shortcuts die with it: put it back on the card.
  // Only then. A button the person is on (Back, Next) keeps the focus so it can be pressed
  // again, and a step that just mounted its free-text field owns it (Back onto an "Other"
  // answer): taking it would turn the next typed digit into a pick. The same rule runs on
  // mount: a page that has nothing focused (a reload onto a persisted card) lands on the
  // card, while the composer or anything else the person is on keeps the focus.
  const cardRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const active = document.activeElement;
    const card = cardRef.current;
    if (!card) return;
    if (!active || active === document.body) {
      card.focus({ preventScroll: true });
    }
  }, [step]);

  if (!question || !draft) return null;

  const inputType = multi ? 'checkbox' : 'radio';
  const rowClass = (checked: boolean) =>
    `flex items-start gap-2 rounded-xl border px-3 py-2 cursor-pointer select-none transition-colors ${
      checked ? 'border-[var(--accent-primary)] bg-[var(--bg-secondary)]' : 'border-theme hover:bg-[var(--bg-secondary)]'
    }`;

  return (
    <div className={`my-4 ${className}`} data-testid="ask-user-card">
      <div
        ref={cardRef}
        tabIndex={-1}
        role="group"
        aria-labelledby={headingId}
        onKeyDown={onKeyDown}
        onFocus={() => setHasFocus(true)}
        onBlur={e => { if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setHasFocus(false); }}
        className="rounded-2xl border border-theme bg-gradient-to-br from-[var(--bg-secondary)] to-[var(--bg-tertiary)] p-4 shadow-sm outline-none focus-visible:ring-1 focus-visible:ring-[var(--accent-primary)]"
      >
        {/* Header */}
        <div className="flex items-center gap-3 mb-3">
          <div className="flex items-center justify-center w-8 h-8 rounded-xl bg-theme-primary border border-theme shrink-0">
            <MessageCircleQuestion className="w-4 h-4 text-theme-primary" />
          </div>
          <div className="min-w-0 flex-1">
            <h3 id={headingId} className="text-sm font-semibold text-theme-primary">{t('title')}</h3>
            <p className="text-xs text-theme-secondary">
              {total > 1 ? t('stepOf', { current: step + 1, total }) : t('subtitle')}
            </p>
            <p className="text-xs text-theme-muted mt-0.5" data-testid={pendingQuestion.blocking ? 'ask-user-waiting' : 'ask-user-resumes'}>
              {pendingQuestion.blocking ? t('waitingForYou') : t('answerResumes')}
            </p>
          </div>
          {total > 1 && (
            <div className="flex items-center gap-1 shrink-0" aria-hidden="true" data-testid="ask-user-progress">
              {questions.map((q, i) => (
                <span
                  key={i}
                  className={`h-1.5 w-4 rounded-full transition-colors ${
                    i === step
                      ? 'bg-[var(--accent-primary)]'
                      : isComplete(drafts[i]) ? 'bg-[var(--accent-primary)] opacity-40' : 'bg-[var(--border-color)]'
                  }`}
                />
              ))}
            </div>
          )}
        </div>

        {/* Announced on every step change: a live region has to exist BEFORE its content
            changes to be read out, so it lives here, stable and unkeyed, rather than on the
            fieldset that is remounted per step. A single question never changes, so it gets
            no region (one would only read the question twice). */}
        {total > 1 && (
          <p role="status" aria-live="polite" className="sr-only" data-testid="ask-user-announce">
            {`${t('stepOf', { current: step + 1, total })} ${question.question}`}
          </p>
        )}

        {/* Current question */}
        <fieldset key={step} className="rounded-2xl bg-theme-primary border border-theme p-3" data-testid="ask-user-question">
          <legend className="px-1 text-xs font-medium uppercase tracking-wide text-theme-muted">{question.header}</legend>
          <p className="text-sm text-theme-primary mb-2">{question.question}</p>
          <p className="text-xs text-theme-muted mb-2">{multi ? t('selectMultiple') : t('selectOne')}</p>
          <div className="space-y-1.5">
            {question.options.map((opt, oi) => {
              const checked = draft.selected.has(opt.label);
              return (
                <label key={opt.label} className={rowClass(checked)}>
                  <input
                    type={inputType}
                    name={groupName}
                    checked={checked}
                    onChange={() => toggle(opt.label)}
                    onKeyDown={e => onOptionKeyDown(e, opt.label)}
                    disabled={pending}
                    className="mt-0.5 h-3.5 w-3.5 accent-[var(--accent-primary)] cursor-pointer"
                    data-testid="ask-user-option"
                  />
                  <span className="min-w-0 flex-1">
                    <span className="block text-sm text-theme-primary">{opt.label}</span>
                    {opt.description && (
                      <span className="block text-xs text-theme-muted">{opt.description}</span>
                    )}
                  </span>
                  {hasFocus && (
                    <kbd aria-hidden="true" className="hidden sm:inline-block shrink-0 rounded border border-theme px-1 text-xs text-theme-muted font-mono">{oi + 1}</kbd>
                  )}
                </label>
              );
            })}
            <label className={rowClass(draft.selected.has(OTHER))}>
              <input
                type={inputType}
                name={groupName}
                checked={draft.selected.has(OTHER)}
                onChange={() => toggle(OTHER)}
                onKeyDown={e => onOptionKeyDown(e, OTHER)}
                disabled={pending}
                className="mt-0.5 h-3.5 w-3.5 accent-[var(--accent-primary)] cursor-pointer"
                data-testid="ask-user-other"
              />
              <span className="min-w-0 flex-1">
                <span className="block text-sm text-theme-primary">{t('otherOption')}</span>
                {draft.selected.has(OTHER) && (
                  <input
                    type="text"
                    value={draft.other}
                    onChange={e => setOther(e.target.value)}
                    placeholder={t('otherPlaceholder')}
                    maxLength={4000}
                    disabled={pending}
                    autoFocus
                    className="mt-1.5 w-full rounded-lg border border-theme bg-theme-primary px-2 py-1 text-sm text-theme-primary placeholder:text-theme-muted focus:outline-none focus:ring-1 focus:ring-[var(--accent-primary)]"
                    data-testid="ask-user-other-text"
                  />
                )}
              </span>
              {hasFocus && (
                <kbd aria-hidden="true" className="hidden sm:inline-block shrink-0 rounded border border-theme px-1 text-xs text-theme-muted font-mono">{question.options.length + 1}</kbd>
              )}
            </label>
          </div>
        </fieldset>

        {/* Footer */}
        <div className="mt-3 flex items-center gap-2">
          {step > 0 && (
            <Button type="button" variant="ghost" size="sm" onClick={back} disabled={pending} className="gap-1" data-testid="ask-user-back">
              <ArrowLeft className="h-3.5 w-3.5" />
              {t('back')}
            </Button>
          )}
          <div className="flex-1" />
          <Button type="button" variant="ghost" size="sm" onClick={dismiss} disabled={pending} data-testid="ask-user-skip">
            {t('skip')}
          </Button>
          {last ? (
            <Button
              ref={submitRef}
              type="button"
              variant="default"
              size="sm"
              onClick={() => submit(drafts)}
              disabled={!allComplete || pending}
              className="gap-2"
              data-testid="ask-user-submit"
            >
              {pending ? (
                <>
                  <LoadingSpinner size="xs" />
                  {t('submitting')}
                </>
              ) : (
                <>{t('submit')}</>
              )}
            </Button>
          ) : (
            <Button
              type="button"
              variant="default"
              size="sm"
              onClick={() => advance(drafts)}
              disabled={!stepComplete || pending}
              data-testid="ask-user-next"
            >
              {t('next')}
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}

export default AskUserQuestionCard;
