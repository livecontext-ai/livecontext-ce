// @vitest-environment jsdom

// The agenda replica reads the local calendar, so the zone is pinned for the same reason
// AgendaShowcase.test.tsx pins it: a floating TZ makes the assertions machine-dependent.
const ORIGINAL_TZ = process.env.TZ;
process.env.TZ = 'UTC';

import '@testing-library/jest-dom/vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { afterAll, afterEach, describe, expect, it } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import AgendaShowcase from '../AgendaShowcase';
import AgentsShowcase from '../AgentsShowcase';

const NOW = '2026-09-15T12:00:00.000Z';
const messages = { en, fr };

afterEach(cleanup);
afterAll(() => { process.env.TZ = ORIGINAL_TZ; });

function renderIn(locale: 'en' | 'fr', node: React.ReactNode) {
  return render(
    <NextIntlClientProvider locale={locale} messages={messages[locale]} timeZone="UTC">{node}</NextIntlClientProvider>,
  );
}

const homeTeam = (locale: 'en' | 'fr') =>
  (['nova', 'atlas', 'scout'] as const).map((id) => ({
    name: messages[locale].LandingHome.agentTeam[id].name,
    description: messages[locale].LandingHome.agentTeam[id].description,
  }));

/**
 * The two live product replicas on the home page render DATA, not just chrome, and that data
 * used to be the component's own English constants: `/fr` showed a French page whose agent
 * roster and calendar were still in English. Both now take the page's language, and these
 * are the regressions for that.
 *
 * <p>Their unlocalized branches are NOT covered here. Both components keep English defaults
 * for a caller that passes neither a locale nor a persona, and the existing
 * `AgendaShowcase.test.tsx` / `AgentsShowcase.test.tsx` suites drive that path throughout;
 * asserting it again here would only certify that the default still exists, which is not
 * what this file is about.
 */
describe('the home page agenda replica', () => {
  it('names its schedules in the page language when given a locale', () => {
    renderIn('fr', <AgendaShowcase nowIso={NOW} locale="fr" />);
    expect(screen.getAllByTitle(new RegExp(fr.LandingHome.agendaSchedules.triage)).length).toBeGreaterThan(0);
    expect(screen.queryAllByTitle(/Inbox triage/)).toHaveLength(0);
  });

});

describe('the home page agents replica', () => {
  it('shows the roster in the page language', () => {
    renderIn('fr', <AgentsShowcase team={homeTeam('fr')} locale="fr" />);
    expect(screen.getByText(fr.LandingHome.agentTeam.nova.name)).toBeInTheDocument();
    expect(screen.getByText(fr.LandingHome.agentTeam.atlas.description)).toBeInTheDocument();
    expect(screen.queryByText(en.LandingHome.agentTeam.nova.name)).not.toBeInTheDocument();
  });
});
