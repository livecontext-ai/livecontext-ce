import { getTranslations } from 'next-intl/server';
import BuildableAutomations from '@/app/[locale]/_landing/BuildableAutomations';
import { translatedAutomationExamples } from '@/app/[locale]/_landing/translatedAutomationExamples';
import type { PersonaKey } from './personas';

/**
 * The homepage's wall of buildable automations, on a persona page.
 *
 * <p>Same band, same cards, same rule: these are capability examples attributed to nobody,
 * every one buildable today with a connector the catalogue carries. What changes here is
 * the language and the order.
 *
 * <p>The LANGUAGE is not this component's job any more: `translatedAutomationExamples` does
 * it, because the home page shows the same band in the same six locales and the two were
 * translating it separately.
 *
 * <p>The ORDER, because a visitor who came for the support page should meet the support
 * automation first. The rest follow unchanged: the point of a wall is breadth, so nothing
 * is filtered out, only moved.
 */
const LEADS: Record<PersonaKey, readonly number[]> = {
  ops: [4, 8, 6],
  support: [3, 6, 9],
  sales: [5, 1, 11],
  marketing: [0, 7, 1],
  creator: [7, 0, 11],
  recruiting: [2, 4, 9],
};

export default async function PersonaBuildableBand({ persona, locale }: { persona: PersonaKey; locale: string }) {
  const translated = await translatedAutomationExamples(locale);
  const t = await getTranslations({ locale, namespace: 'PersonaLanding.buildable' });
  const lead = LEADS[persona];
  const examples = [...lead.map((index) => translated[index]), ...translated.filter((_, index) => !lead.includes(index))];
  return <BuildableAutomations examples={examples} rowLabels={[t('bandRow', { n: 1 }), t('bandRow', { n: 2 })]} />;
}
