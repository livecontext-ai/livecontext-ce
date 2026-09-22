import { ArrowRight } from 'lucide-react';
import { getTranslations } from 'next-intl/server';
import { PERSONA_ICONS } from './personaIcons';
import PersonaScreenStage from './PersonaScreenStage';
import { HERO_EXAMPLE_KEYS, PERSONA_KEYS, PERSONA_TINTS, personaHref, type PersonaKey } from './personas';

/**
 * The six roles on the home page, each showing the screen its own page is built around.
 *
 * <p>This section replaced a grid of six text cards listing three bullet points per role.
 * The copy was accurate and it showed nothing: the product's whole claim is that you end up
 * with something you can open, and six bullet lists are the one shape that cannot make that
 * claim. Every card now carries the persona's REAL artefact, rendered by
 * `buildPersonaInterfaceHtml` from the same authored workspace its `/for/<persona>` page
 * shows in its own "what you can build" section, so the six are six different documents:
 * a dashboard, a vertical story, a cancellation desk, a quote, a launch planner, a CV.
 *
 * <p><strong>The layout is asymmetric on purpose, and it is the studios' own rhythm.</strong>
 * Each row is five columns holding a 3 and a 2, and the wide card swaps sides from row to
 * row, exactly as `OpsBuildStudio` lays its four cards out. Six equal tiles read as a spec
 * sheet; alternating widths read as a page. It also puts each screen in the slot its shape
 * wants: the landscape workspaces take the wide half, and Creator's portrait story takes a
 * narrow one.
 *
 * <p>The pairs are `PERSONA_KEYS` taken two at a time, so the DOM order IS the site's
 * ranking of the roles and the alternation is expressed by WHICH of the two is wide. That
 * is why there is no `order` anywhere: a keyboard, a screen reader and the eye all get the
 * same sequence, which is what an `order` override would have broken.
 *
 * <p>Copy and colour are read from what already exists rather than restated: the title and
 * summary are the persona's own `workflowShowcase.examples.<hero example>` keys (translated
 * in all six locales for the persona pages), and the hue is `PERSONA_TINTS`, the same
 * triplet the persona page paints itself with. A card therefore cannot promise a screen, a
 * sentence or a colour that the page behind its button does not deliver.
 */

/** Which half of each pair takes the wide (3 of 5) slot. Alternates down the page. */
const WIDE: Record<PersonaKey, boolean> = {
  ops: true, creator: false,
  support: false, sales: true,
  marketing: true, recruiting: false,
};

/** `PERSONA_KEYS` two at a time: one row per pair, in the ranked order. */
export const ROLE_CARD_ROWS: readonly (readonly PersonaKey[])[] = [
  PERSONA_KEYS.slice(0, 2), PERSONA_KEYS.slice(2, 4), PERSONA_KEYS.slice(4, 6),
];

export default async function PersonaRoleCards({ locale }: { locale: string }) {
  const t = await getTranslations({ locale, namespace: 'PersonaLanding.personas' });
  const home = await getTranslations({ locale, namespace: 'LandingHome.roles' });

  const card = (persona: PersonaKey) => {
    const Icon = PERSONA_ICONS[persona];
    const name = t(`${persona}.name`);
    const example = `${persona}.workflowShowcase.examples.${HERO_EXAMPLE_KEYS[persona]}`;
    return (
      <article
        key={persona}
        className="role-card"
        data-persona={persona}
        data-span={WIDE[persona] ? 'wide' : 'narrow'}
        // The tint is the only thing that changes between the six cards, so it is one custom
        // property and every colour in the stylesheet is mixed from it.
        style={{ ['--role-tint' as string]: PERSONA_TINTS[persona] }}
        aria-labelledby={`role-card-${persona}-title`}
      >
        <div className="role-card-stage">
          <PersonaScreenStage persona={persona} />
        </div>

        <div className="role-card-body">
          <span className="role-card-label">
            <Icon className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
            {name}
          </span>
          <h3 id={`role-card-${persona}-title`} className="role-card-title">{t(`${example}.title`)}</h3>
          <p className="role-card-summary">{t(`${example}.summary`)}</p>

          {/* The card is not itself a link: the accessible name of a link wrapping a whole
              card is the card read aloud. One button, named for the role it opens, so the
              six are distinguishable in a list of links. */}
          <a className="role-card-cta" href={personaHref(persona, locale)}>
            {home('cta', { role: name })}
            <ArrowRight className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
          </a>
        </div>
      </article>
    );
  };

  return (
    <div className="mt-12 flex flex-col gap-5 md:gap-6">
      {ROLE_CARD_ROWS.map((row) => (
        <div key={row.join('-')} className="role-row">{row.map(card)}</div>
      ))}
    </div>
  );
}
