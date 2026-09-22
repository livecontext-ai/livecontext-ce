// @vitest-environment jsdom
/**
 * The reasoning feed against the REAL dictionaries, in all six languages.
 *
 * <p>The feed's own suites stub the translator (t(key) returns the key), so they
 * certify that a key is ASKED for, never that it EXISTS or that its ICU message
 * parses. `previousSteps` is a plural message: a malformed one renders as the raw
 * key, or throws, and no stubbed suite can see either. next-intl prints the key
 * path for a missing message without throwing, so the assertions here are WORDS a
 * reader would recognise, plus the key path as an explicit negative.
 *
 * <p>It also pins the removal: "Show full history" is gone from the header, and
 * must not come back in any dictionary.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

// next-intl itself is deliberately NOT mocked - it is what is under test here.
// These children are: GroupedToolCard's transitive imports reach next/navigation,
// which has no router in jsdom, and none of them render on a _thinking row.
vi.mock('../GroupedToolCard', () => ({ GroupedToolCard: () => null }));
vi.mock('@/components/MarkdownRender', () => ({ default: () => null }));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));
vi.mock('@/lib/api', () => ({ apiClient: { get: async () => ({ content: '' }) } }));
vi.mock('@/lib/hooks/useResourceQuery', () => ({
  useResourceQuery: () => ({ data: undefined, isLoading: false, error: null }),
}));
vi.mock('next/image', () => ({ default: () => null }));

import enMessages from '@/messages/en.json';
import frMessages from '@/messages/fr.json';
import deMessages from '@/messages/de.json';
import esMessages from '@/messages/es.json';
import ptMessages from '@/messages/pt.json';
import zhMessages from '@/messages/zh.json';
import { ActivityFeed, type ToolActivity } from '../ActivityFeed';

const dictionaries = { en: enMessages, fr: frMessages, de: deMessages, es: esMessages, pt: ptMessages, zh: zhMessages };
type Locale = keyof typeof dictionaries;

// _thinking rows are never grouped, so each activity is one step of the cap.
const step = (i: number): ToolActivity => ({
  id: `step-${i}`, toolId: `step-${i}`, toolName: '_thinking', status: 'success',
  timestamp: i, thinkingMessage: `Reasoning step ${i}`,
});

function renderIn(locale: Locale, count: number) {
  return render(
    <NextIntlClientProvider locale={locale} messages={dictionaries[locale]}>
      <ActivityFeed activities={Array.from({ length: count }, (_, i) => step(i))} isStreaming />
    </NextIntlClientProvider>,
  );
}

// jsdom reports 0 for every layout box, so the reasoning clamp would never
// consider itself overflowing and its two controls would never render.
beforeEach(() => {
  vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(900);
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(96);
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe('ActivityFeed with the real dictionaries', () => {
  it.each(Object.keys(dictionaries) as Locale[])('%s: the plural step count resolves to a real message', locale => {
    renderIn(locale, 9);

    // The raw key path is what next-intl prints for a missing or broken message.
    const label = screen.getByTestId('step-history-toggle').textContent ?? '';
    expect(label).not.toMatch(/activityFeed|previousSteps/);
    expect(label).toMatch(/5/);
  });

  it('en: counts the hidden steps in words, and the header offers no history control', () => {
    renderIn('en', 9);

    expect(screen.getByText('Show 5 previous steps')).toBeTruthy();
    expect(screen.queryByText(/Show full history|Compact view/)).toBeNull();

    fireEvent.click(screen.getByText('Show 5 previous steps'));
    expect(screen.getByText('Hide previous steps')).toBeTruthy();
    expect(screen.getByText('Reasoning step 0')).toBeTruthy();
  });

  it('en: uses the singular when exactly one step is hidden', () => {
    // The plural branch is the half a stubbed translator can never reach.
    renderIn('en', 5);

    expect(screen.getByText('Show 1 previous step')).toBeTruthy();
  });

  it('en: names the reasoning clamp controls in words', () => {
    renderIn('en', 1);

    fireEvent.click(screen.getByText('Show more'));
    expect(screen.getByText('Show less')).toBeTruthy();
  });

  it('fr: translates the step controls rather than falling back to English', () => {
    renderIn('fr', 9);

    expect(screen.getByText('Voir 5 étapes précédentes')).toBeTruthy();
    expect(screen.queryByText(/Show \d+ previous/)).toBeNull();

    fireEvent.click(screen.getByText('Voir 5 étapes précédentes'));
    expect(screen.getByText('Masquer les étapes précédentes')).toBeTruthy();
  });

  it('fr: translates the reasoning clamp controls', () => {
    renderIn('fr', 1);

    fireEvent.click(screen.getByText('Voir plus'));
    expect(screen.getByText('Voir moins')).toBeTruthy();
    expect(screen.queryByText(/Show (more|less)/)).toBeNull();
  });
});
