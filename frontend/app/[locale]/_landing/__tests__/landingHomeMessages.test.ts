import { describe, expect, it } from 'vitest';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';
import { locales } from '@/i18n/routing';
import { AUTOMATION_EXAMPLES } from '../automationExamples';
import { AUTOMATION_EXAMPLE_KEYS } from '../translatedAutomationExamples';
import { DEMO_AGENT_IDS, DEMO_SCHEDULE_IDS } from '../demoRosterIds';
import { DEMO_AGENTS } from '../AgentsShowcase';
import { SCHEDULES } from '../AgendaShowcase';

const messages: Record<string, Record<string, unknown>> = { en, fr, de, es, pt, zh };

function flatten(value: unknown, prefix = ''): string[] {
  if (value === null || typeof value !== 'object') return [prefix];
  return Object.entries(value as Record<string, unknown>)
    .flatMap(([key, child]) => flatten(child, prefix ? `${prefix}.${key}` : key));
}

const home = (locale: string) => (messages[locale] as { LandingHome: unknown }).LandingHome;
const read = (source: unknown, path: string) => path.split('.').reduce<unknown>((node, key) => (node as Record<string, unknown>)[key], source);

/**
 * The home page used to be hardcoded English on every locale URL while the components
 * embedded in it (the hero demo, the persona pills) translated themselves, so `/fr` served a
 * French demo inside an English page. These tests pin the two halves of the fix: the copy
 * exists in all six languages, and the DATA the live product replicas render is keyed to the
 * ids the code actually iterates.
 */
describe('LandingHome messages', () => {
  it('exists in every supported locale', () => {
    for (const locale of locales) expect(home(locale), locale).toBeTruthy();
  });

  it.each(locales.filter((locale) => locale !== 'en'))('has strict key parity with English in %s', (locale) => {
    expect(flatten(home(locale)).sort()).toEqual(flatten(home('en')).sort());
  });

  it.each(locales.filter((locale) => locale !== 'en'))('is actually translated in %s, not English copied over', (locale) => {
    // Not every string can differ (a brand name is a brand name), but a locale that matched
    // English on the long prose keys would be an untranslated file passing a parity check.
    const prose = [
      'hero.lead', 'roles.lead', 'marketplace.lead', 'agenda.lead', 'pricing.lead', 'finalCta.lead',
      'faq.what.answer', 'faq.pricing.answer', 'jsonLd.softwareDescription',
    ];
    for (const key of prose) expect(read(home(locale), key), `${locale}.${key}`).not.toBe(read(home('en'), key));
  });

  it('keeps the hero underline as a tag each language can place in its own sentence', () => {
    for (const locale of locales) {
      const hero = home(locale) as { hero: { titleLineOne: string; titleLineTwo: string } };
      expect(hero.hero.titleLineOne, locale).toMatch(/<u>.+<\/u>/);
      expect(hero.hero.titleLineTwo, locale).toMatch(/<u>.+<\/u>/);
    }
  });

  it('keeps every ICU placeholder the code substitutes, in every locale', () => {
    // A dropped placeholder does not fail: next-intl renders the sentence without the number,
    // so the page quietly claims nothing about how many integrations there are.
    const required: [string, RegExp][] = [
      ['roles.cta', /\{role\}/],
      ['faq.what.answer', /\{integrations\}/], ['jsonLd.softwareDescription', /\{integrations\}/],
    ];
    for (const locale of locales) {
      for (const [key, pattern] of required) expect(String(read(home(locale), key)), `${locale}.${key}`).toMatch(pattern);
      expect(String(read(messages[locale], 'PersonaLanding.buildable.bandRow')), locale).toMatch(/\{n\}/);
    }
  });

  it('carries no em-dash or en-dash, in any locale', () => {
    for (const locale of locales) expect(JSON.stringify(home(locale)), locale).not.toMatch(/[--]/);
  });
});

describe('the demo id lists', () => {
  // The ids are read by a SERVER component and the two replicas are client components, so
  // they cannot live in the replicas: a client module's exports reach a server module as
  // client references, and the array is not an array at render time. Typecheck and vitest
  // both see the real module, so only opening the page catches it. These two assertions are
  // what keeps the extracted list honest.
  it('matches the roster the agents showcase actually draws, in order', () => {
    expect(DEMO_AGENTS.map((agent) => agent.id)).toEqual([...DEMO_AGENT_IDS]);
  });

  it('matches the calendar the agenda showcase actually draws, in order', () => {
    expect(SCHEDULES.map((schedule) => schedule.id)).toEqual([...DEMO_SCHEDULE_IDS]);
  });
});

describe('the data the home page replicas render', () => {
  // These lists are joined to the components' own arrays BY ID at render time. A rename on
  // one side makes next-intl print the literal key path into the roster or the calendar; it
  // does not fail. Reading the ids from the components is what keeps the two in step.
  it.each(locales)('names every demo agent in %s, keyed to the showcase own ids', (locale) => {
    const team = (home(locale) as { agentTeam: Record<string, { name: string; description: string }> }).agentTeam;
    expect(Object.keys(team)).toEqual([...DEMO_AGENT_IDS]);
    for (const id of DEMO_AGENT_IDS) {
      expect(team[id].name.trim(), `${locale}.${id}`).not.toBe('');
      expect(team[id].description.trim(), `${locale}.${id}`).not.toBe('');
    }
  });

  it.each(locales)('names every demo schedule in %s, keyed to the calendar own ids', (locale) => {
    const schedules = (home(locale) as { agendaSchedules: Record<string, string> }).agendaSchedules;
    expect(Object.keys(schedules)).toEqual([...DEMO_SCHEDULE_IDS]);
    for (const id of DEMO_SCHEDULE_IDS) expect(schedules[id].trim(), `${locale}.${id}`).not.toBe('');
  });
});

describe('the buildable band card list', () => {
  it('pairs each message key with the example at the SAME INDEX', () => {
    // Joined by index, so a permutation puts the wrong brand marks on the wrong sentence,
    // which is the one failure here that looks perfectly fine on screen. A set comparison
    // cannot see that, which is what this assertion replaced.
    expect(AUTOMATION_EXAMPLE_KEYS).toHaveLength(AUTOMATION_EXAMPLES.length);
    const examples = en.PersonaLanding.buildable.examples as Record<string, { role: string }>;
    AUTOMATION_EXAMPLE_KEYS.forEach((key, index) => {
      expect(examples[key].role, `index ${index}`).toBe(AUTOMATION_EXAMPLES[index].role);
    });
  });

  it('has the same twelve keys in every locale', () => {
    for (const locale of locales) {
      const examples = (messages[locale] as { PersonaLanding: { buildable: { examples: Record<string, unknown> } } }).PersonaLanding.buildable.examples;
      expect(Object.keys(examples).sort(), locale).toEqual([...AUTOMATION_EXAMPLE_KEYS].sort());
    }
  });
});
