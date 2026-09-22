/**
 * The colour of a persona page, and the rhythm of its sections.
 *
 * <p>Each persona owns ONE hue, declared as `--persona-tint` (an RGB triplet, so it can be
 * mixed at several strengths). Everything else is derived from it: the two section grounds
 * and the diagonal wash that runs over the whole page, header and footer included, because
 * stopping the wash at the sections left a coloured stripe between two neutral bands.
 *
 * <p>LIGHT mode carries the colour. It was a whisper before (3% of a hue on white, which is
 * whiter than most screens can show) and the six pages were indistinguishable; the tints
 * below are several times that. DARK mode keeps the quieter treatment: on a near-black
 * ground the same strength reads as a cast over the whole screen rather than as an accent.
 *
 * <p>The sections then ALTERNATE ground and band. They did not before: two grey sections ran
 * into each other and four white ones followed, so the page read as one long block with an
 * arbitrary seam. Each section now names its own half in the markup rather than being
 * counted here, because `main` also carries nodes that are not sections. The wash still
 * needs `!important`: a section paints its ground with the inline `background` SHORTHAND,
 * which resets `background-image` to none, and an inline declaration wins over a stylesheet
 * without it. No backticks in this comment: the whole file is one template literal, and a
 * stray one closes the string.
 */
import { PERSONA_KEYS, PERSONA_TINTS } from './personas';

/** The six tint declarations, one per persona, in PERSONA_KEYS order. */
const PERSONA_TINT_RULES = PERSONA_KEYS.map(
  (persona) => `  .persona-page.persona-${persona} { --persona-tint: ${PERSONA_TINTS[persona]}; }`,
).join('\n');

export const personaStyles = `
  .persona-page .persona-hero-demo { scroll-margin-top: 100px; }
  .persona-page a:focus-visible { outline: 2px solid var(--accent-primary); outline-offset: 4px; }

  /* One hue per persona, generated from PERSONA_TINTS rather than written again here: the
     home page's role cards paint themselves in the same hues, and a second copy of the six
     triplets is a card promising a page a colour that page no longer uses. */
${PERSONA_TINT_RULES}

  /* The two grounds the sections alternate between, and the wash over both. In light mode
     the band is tint on a grey, so it is both a shade darker AND coloured; the ground is the
     same hue on white, so the pale half of the rhythm is not plain white either. The numbers
     were walked in from both ends: 11% read as a coloured stripe across the page, 2.5% was
     so faint the pages looked identical again. The band carries most of the step through the
     neutral it sits on, and enough of the hue that the page says which persona it is at a
     glance, without the alternation becoming the thing you look at.
     That neutral is #eef1f4 and NOT var(--bg-secondary), which is #f5f6f8: at the landing
     palette's own grey the alternation was invisible next to a 3% tint on white, which is
     the state this replaced. So it is one deliberate step darker, and the consequence is
     that it does not follow the palette: a change to --bg-secondary leaves these bands
     where they are, and they have to be re-walked by eye. Dark mode has no such problem and
     delegates to the token. */
  /* The plain neutrals first, then the tinted ones only where color-mix() is understood.
     The guard is not decoration: a custom property holds ANY token at parse time, so on a
     browser without color-mix() the variable is defined and unusable, and the section that
     substitutes it becomes invalid at computed-value time. That does NOT fall back to the
     var() default, it falls back to the property's initial value, which for background is
     transparent: every section on all six pages would lose its ground at once. Declared in
     this order, such a browser keeps the untinted rhythm and everything else still works. */
  .persona-page {
    --persona-ground: #ffffff;
    --persona-band: #eef1f4;
    --persona-background: linear-gradient(125deg, rgba(var(--persona-tint), .04), rgba(var(--persona-tint), .018) 52%, rgba(var(--persona-tint), .045));
  }
  @supports (background: color-mix(in srgb, red 3%, white)) {
    .persona-page {
      --persona-ground: color-mix(in srgb, rgb(var(--persona-tint)) 3%, #ffffff);
      --persona-band: color-mix(in srgb, rgb(var(--persona-tint)) 4.5%, #eef1f4);
    }
  }
  .persona-page.dark {
    --persona-ground: var(--bg-primary);
    --persona-band: var(--bg-secondary);
    --persona-background: linear-gradient(125deg, rgba(var(--persona-tint), .075), rgba(var(--persona-tint), .04) 52%, rgba(var(--persona-tint), .08));
  }

  /* Ground, band, ground, band, all the way down. Each section names its own half in the
     markup (see PersonaLanding); only the integrations strip is styled from here, because
     it is shared with the home page and paints --bg-secondary inline, which a rule without
     !important loses to. Position counting was tried and rejected: two dev-only <script>
     nodes sit among main's children, so nth-child inverts the whole rhythm between the dev
     server and a production build. */
  .persona-page .persona-integrations > section { background-color: var(--persona-band) !important; }

  .persona-page > main > section,
  .persona-page .persona-integrations > section,
  .persona-page > header,
  .persona-page > footer {
    background-image: var(--persona-background) !important;
  }

  /* The three product shots on the page (the hero's app window, the agents roster, the
     agenda) float on one backdrop panel, and that panel now carries the persona's hue too:
     a neutral grey frame around a coloured page read as a hole in it.
     The tint is its own layer rather than a change to the two that were there. The panel's
     ::before holds a photographic texture under an opaque veil and a saturate(.1) filter
     that keeps it quiet: colour placed inside that layer is drained by the filter, and
     thinning the veil to let a tinted ground through instead let the texture's own blue-grey
     diagonal out, which fought the warm personas. So the hue goes on ::after, above the
     texture and below the content, which sits at z-index 1.
     (No backticks in this comment: it lives INSIDE the template literal, and one closes the
     string. The header says so, and it caught me here anyway.) */
  .persona-page .landing-demo-panel::after {
    content: "";
    position: absolute;
    inset: 0;
    z-index: 0;
    pointer-events: none;
    background: linear-gradient(135deg, rgba(var(--persona-tint), .10), rgba(var(--persona-tint), .04) 58%, rgba(var(--persona-tint), .09));
  }
  .persona-page.dark .landing-demo-panel::after {
    background: linear-gradient(135deg, rgba(var(--persona-tint), .09), rgba(var(--persona-tint), .035) 58%, rgba(var(--persona-tint), .08));
  }

  /* The cards of the buildable band take the same wash, so the wall belongs to the page
     rather than sitting on it as a grey block. !important because the card paints its
     ground with the background SHORTHAND inline, which resets background-image to none,
     and an inline declaration wins over a stylesheet without it. */
  .persona-page .build-card {
    background-image: linear-gradient(135deg, rgba(var(--persona-tint), .10), rgba(var(--persona-tint), .04) 55%, rgba(var(--persona-tint), .09)) !important;
    border-color: color-mix(in srgb, rgb(var(--persona-tint)) 16%, var(--border-color)) !important;
  }
`;
