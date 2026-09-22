'use client';

import { useMemo } from 'react';
import { useTranslations } from 'next-intl';
import { Zap } from 'lucide-react';
import { BrandMark } from '@/components/integrations/BrandMark';
import { WELL_KNOWN_INTEGRATIONS } from '@/lib/integrations/wellKnownIntegrations';
import { useLandingTheme } from '@/components/landing/LandingThemeProvider';
import { useOnVisibleOnce } from '@/hooks/useOnVisibleOnce';
import StudioWires, { type Wire } from './StudioWires';
import { ScreenTile } from './studioParts';
import { buildPersonaInterfaceHtml } from './personaInterfaceHtml';
import {
  BUSINESS_DESTINATIONS,
  BUSINESS_PREVIEW_VIEWPORT,
  CREATOR_DESTINATION_SLUGS,
  CREATOR_EXAMPLES,
  HERO_EXAMPLE_KEYS,
  type BusinessExampleKey,
  type BusinessPersona,
  type CreatorExampleKey,
  type PersonaKey,
} from './personas';

/**
 * One persona's workflow, in miniature: what fires it, the screen it produces, and where the
 * approved result goes.
 *
 * <p>The screen alone was six screenshots in a row, and a screenshot does not say that the
 * product is a workflow. The WIRES are what says it, and they are the ones the persona pages
 * already draw: `StudioWires` measures the tiles and curves an edge between them the way the
 * builder curves its own, so every card reads as one run rather than as a picture with a
 * caption. Everything on the card is linked to everything else, which is the claim the
 * section is making.
 *
 * <p>Nothing here is invented for the home page. The screen is `buildPersonaInterfaceHtml`,
 * the same authored workspace the persona page shows in its own "what you can build"
 * section, on the example that page leads with (`HERO_EXAMPLE_KEYS`): a dashboard, a
 * vertical story, a cancellation desk, a quote, a launch planner, a CV. The trigger is that
 * example's own `triggerLabel`, and the destinations are its verified connector slugs, so a
 * card cannot advertise a tool the catalogue does not carry or a step the page does not run.
 * The approval is not drawn as a fourth tile because it is already IN the screen: every one
 * of them carries its own approve button.
 *
 * <p>Only this part is a client component: the card around it, its copy and its link are
 * rendered on the server, because those six links are the page's in-page route to
 * `/for/<persona>` and a crawler has to see them without running a thumbnail.
 *
 * <p><strong>It mounts on sight, not on load.</strong> Each screen is an iframe with its own
 * document; six mounted eagerly on the busiest page of the site is six extra documents
 * parsed before the visitor has scrolled to any of them. `useOnVisibleOnce` is the idiom
 * already used for exactly that, and the placeholder carries the same node id as the screen
 * so the wires attach to it and the composition does not jump when the screen arrives.
 */

/** Taller than this, and a screen is capped by its height rather than by its card's slot. */
const TALL_RATIO = 1.4;

function viewportFor(persona: PersonaKey) {
  if (persona !== 'creator') return BUSINESS_PREVIEW_VIEWPORT;
  const story = CREATOR_EXAMPLES[HERO_EXAMPLE_KEYS.creator];
  return { width: story.width, height: story.height };
}

/** Where the approved result lands, as verified connector slugs. Three fit a card. */
function destinationsFor(persona: PersonaKey) {
  const slugs = persona === 'creator'
    ? CREATOR_DESTINATION_SLUGS
    : BUSINESS_DESTINATIONS[HERO_EXAMPLE_KEYS[persona as BusinessPersona]];
  return slugs
    .slice(0, 3)
    .map((slug) => WELL_KNOWN_INTEGRATIONS.find((known) => known.slug === slug))
    .filter((known): known is (typeof WELL_KNOWN_INTEGRATIONS)[number] => Boolean(known));
}

export default function PersonaScreenStage({ persona }: { persona: PersonaKey }) {
  const t = useTranslations(`PersonaLanding.personas.${persona}.workflowShowcase`);
  const { theme } = useLandingTheme();
  const [ref, seen] = useOnVisibleOnce();
  const example = HERO_EXAMPLE_KEYS[persona];
  const viewport = viewportFor(persona);
  const destinations = useMemo(() => destinationsFor(persona), [persona]);

  // Creator's example is a piece of content rather than an event, so its own name is what
  // starts the run. Every other example names its trigger.
  const trigger = persona === 'creator' ? t(`examples.${example}.label`) : t(`examples.${example}.triggerLabel`);

  const wires = useMemo<Wire[]>(
    () => [{ from: 'trigger', to: 'screen' }, ...destinations.map((d) => ({ from: 'screen', to: `dest-${d.slug}` }))],
    [destinations],
  );

  const html = useMemo(
    () => (seen
      ? buildPersonaInterfaceHtml(
        persona,
        theme,
        t as unknown as (key: string) => string,
        persona === 'creator' ? (example as CreatorExampleKey) : 'product',
        persona === 'creator' ? undefined : (example as BusinessExampleKey),
      )
      : ''),
    [seen, persona, theme, t, example],
  );

  // The SHAPE, not the persona, decides how wide the screen may get: a story is 1.78 times as
  // tall as it is wide, so at a workspace's width it would be half again as tall and its
  // whole row would stretch to it. The threshold is a RATIO and not `height > width`, which
  // is the bug this replaced: the business workspace is 1020x1080, i.e. taller than it is
  // wide by six percent, so a bare comparison called all six screens portrait and shrank
  // five of them to a story's width.
  const shape = viewport.height / viewport.width >= TALL_RATIO ? 'portrait' : 'landscape';

  return (
    <div ref={ref} className="role-wire-stage">
      <StudioWires wires={wires} className="role-wire-row">
        <span data-node="trigger" className="role-wire-pill">
          <Zap className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
          {trigger}
        </span>

        <div className="role-stage-box" data-shape={shape}>
          {html
            ? <ScreenTile node="screen" html={html} width="100%" lift="0px" viewport={viewport} />
            : (
              <div
                data-node="screen"
                aria-hidden="true"
                className="role-stage-placeholder"
                style={{ aspectRatio: `${viewport.width} / ${viewport.height}` }}
              />
            )}
        </div>

        <div className="role-wire-dests">
          {destinations.map((integration) => (
            <span key={integration.slug} data-node={`dest-${integration.slug}`} className="role-wire-pill">
              <BrandMark iconSlug={integration.iconSlug} size={14} />
              {integration.name}
            </span>
          ))}
        </div>
      </StudioWires>
    </div>
  );
}
