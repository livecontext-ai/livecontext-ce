import { getTranslations } from 'next-intl/server';
import { PERSONA_KEYS, type PersonaKey } from '@/components/landing/personas/personas';
import type { ShellLabels } from '@/components/landing/LandingShell';

/**
 * The header and footer copy for one locale, ready to hand to `LandingHeader`/`LandingFooter`.
 *
 * <p><strong>Why the shell needs this at all.</strong> `LandingShell` renders on TWO kinds of
 * page: the localised ones under `app/[locale]` (the home page and the six `/for/<persona>`
 * pages) and the plain public ones outside that tree (/docs, /legal, /marketplace, /about),
 * where there is no intl context and `useTranslations` throws. So the shell cannot translate
 * itself; it takes an optional `labels` and defaults to the English it always had. This helper
 * is the other half: the localised pages call it and pass the result, which is why /fr no
 * longer showed a French page inside an English chrome.
 *
 * <p>The persona names are keyed `persona<Key>` in the messages (`personaOps`, ...) rather than
 * nested, because next-intl flattens a namespace lookup and a six-entry record read one key at
 * a time is the same number of lookups with one fewer level to keep in parity. They are mapped
 * back onto `PERSONA_KEYS` here, so the record cannot gain or lose an entry independently of
 * that list. Note this is NOT a compile-time guarantee: the map is dynamic and the result is
 * asserted to the record type, so a seventh persona added without its `personaX` message
 * compiles, and at runtime next-intl renders the RAW KEY PATH into the footer
 * ("LandingShell.personaFinance"), which is worse than English because it reads as breakage.
 * `shellLabels.test.ts` is what catches that, by translating with a throwing `onError`.
 */
export async function shellLabels(locale: string): Promise<ShellLabels> {
  const t = await getTranslations({ locale, namespace: 'LandingShell' });
  const capitalize = (persona: PersonaKey) => persona.charAt(0).toUpperCase() + persona.slice(1);
  return {
    pricing: t('pricing'), selfHosted: t('selfHosted'), signIn: t('signIn'),
    getStarted: t('getStarted'), docs: t('docs'),
    product: t('product'), models: t('models'), resources: t('resources'), compare: t('compare'),
    company: t('company'), legal: t('legal'),
    // The message key stays `useCases`; only the FIELD is renamed, because a member matching
    // /^use[A-Z]/ is read as a hook by react-hooks/hooks at every call site.
    personasHeading: t('useCases'), marketplace: t('marketplace'),
    // Not a translation: the persona links need the prefix so a French page's French anchor
    // text does not point at the English page.
    locale,
    workflows: t('workflows'), agents: t('agents'), interfaces: t('interfaces'),
    tables: t('tables'), integrations: t('integrations'),
    videos: t('videos'), status: t('status'), allIntegrations: t('allIntegrations'),
    language: t('language'), toLightTheme: t('toLightTheme'), toDarkTheme: t('toDarkTheme'),
    about: t('about'), careers: t('careers'), soon: t('soon'), contact: t('contact'),
    privacy: t('privacy'), terms: t('terms'), notice: t('notice'),
    // The brand is a placeholder rather than a concatenation: "Zapier alternative" inverts in
    // French and Spanish ("Alternative a Zapier"), which a suffix cannot express.
    alternativeTo: (brand: string) => t('alternativeTo', { brand }),
    tagline: t('tagline'),
    rights: t('rights'),
    personas: Object.fromEntries(
      PERSONA_KEYS.map((persona) => [persona, t(`persona${capitalize(persona)}`)]),
    ) as Record<PersonaKey, string>,
  };
}
