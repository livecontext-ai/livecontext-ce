'use client';

import { useLocale, useTranslations } from 'next-intl';
import { PERSONA_KEYS, personaHref, type PersonaKey } from './personas';
import { PERSONA_ICONS } from './personaIcons';

/**
 * The persona pills floating on the hero demo card.
 *
 * <p>`current` marks the persona the hero is PLAYING, which on a /for/<persona> page is also
 * the page being read (`onPage`, so the pill claims aria-current="page") and on the home page
 * is not: there the pill links elsewhere, so it claims aria-current="true", the current item
 * of a set. Both get the same style, because a visitor reading either page is looking at the
 * same thing: operations, selected.
 *
 * <p>They used to be drawn inside the hero animation's iframe. That iframe is no
 * longer the hero visual, and these links are the ONLY in-page links to the
 * /for/<persona> pages (the sitemap aside), so they move out here rather than
 * disappear with it.
 *
 * <p>The label is hidden on narrow screens, where five labelled pills do not fit;
 * `aria-label` keeps each link's accessible name in that state.
 */
export default function PersonaHeroNav({ current, onPage = false }: { current?: PersonaKey; onPage?: boolean }) {
  const locale = useLocale();
  const t = useTranslations('PersonaLanding');
  return (
    <nav className="persona-hero-nav" aria-label={t('common.personaNavigation')}>
      {PERSONA_KEYS.map((persona) => {
        const Icon = PERSONA_ICONS[persona];
        const label = t(`personas.${persona}.name`);
        return (
          <a
            key={persona}
            href={personaHref(persona, locale)}
            data-persona={persona}
            aria-label={label}
            aria-current={persona === current ? (onPage ? 'page' : 'true') : undefined}
          >
            <Icon className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
            <span className="persona-hero-nav-label">{label}</span>
          </a>
        );
      })}
    </nav>
  );
}
