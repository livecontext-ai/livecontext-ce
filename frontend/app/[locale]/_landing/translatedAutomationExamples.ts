import { getTranslations } from 'next-intl/server';
import { AUTOMATION_EXAMPLES, type AutomationExample } from './automationExamples';

/**
 * The buildable band's cards, in the language of the page.
 *
 * <p>`automationExamples.ts` is English, and every surface that shows the band is served in
 * six locales: the home page and the six persona pages. The COPY therefore lives in messages
 * and the connector SLUGS stay in code, because a connector name is not translated and a
 * verified slug list must not be restated in six files where five of them could drift.
 *
 * <p>The two lists are joined BY POSITION, so `KEYS` is the same order as
 * `AUTOMATION_EXAMPLES` and a test pins that: a shifted list would put the wrong brand marks
 * on the wrong sentence, which is the one failure here that looks fine on screen.
 */
export const AUTOMATION_EXAMPLE_KEYS = [
  'agency', 'founder', 'recruiter', 'support', 'ops', 'sales',
  'ecommerce', 'content', 'finance', 'product', 'developer', 'consultant',
] as const;

export async function translatedAutomationExamples(locale: string): Promise<AutomationExample[]> {
  const t = await getTranslations({ locale, namespace: 'PersonaLanding.buildable' });
  return AUTOMATION_EXAMPLES.map((example, index) => ({
    role: t(`examples.${AUTOMATION_EXAMPLE_KEYS[index]}.role`),
    automation: t(`examples.${AUTOMATION_EXAMPLE_KEYS[index]}.automation`),
    integrations: example.integrations,
  }));
}
