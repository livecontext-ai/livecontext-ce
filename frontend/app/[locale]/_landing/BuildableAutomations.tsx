import { BrandMark } from '@/components/integrations/BrandMark';
import { WELL_KNOWN_INTEGRATIONS } from '@/lib/integrations/wellKnownIntegrations';
import { AUTOMATION_EXAMPLES, type AutomationExample } from './automationExamples';
import { TESTIMONIALS, testimonialsReady, type Testimonial } from './socialProof';

/**
 * The band under the agenda: what people built, scrolling in two opposite rows.
 *
 * <p><strong>It renders one of two things, and which one is not a style choice.</strong>
 * When `socialProof.ts` holds real, authorized customer quotes it is a testimonial wall:
 * avatar, name, role, and the person's own words. Until then it is the same wall built from
 * capability examples, which describe what the product does and are attributed to nobody.
 *
 * <p>The fallback exists because the alternative was to invent the customers. A quote signed
 * with a name and a face is a statement that a real person said it; writing a plausible one
 * is a fabricated testimonial, which is a prohibited commercial practice in the EU and the
 * first thing an audience checks. The switch means the landing never carries a hole while
 * the quotes are being collected, and never carries an invented person either. Adding one
 * real entry to `TESTIMONIALS` flips the whole section over, with no other edit.
 *
 * <p>Two rows moving in opposite directions, which is what makes a static list read as a
 * wall in motion without any of it being live data. Each row duplicates its cards once and
 * translates by exactly -50%; the duplicate is `aria-hidden` so a screen reader hears each
 * entry once.
 *
 * <p><strong>The spacing lives on the CARD, not on the row, and that is load-bearing.</strong>
 * With `gap` on the flex row, `2N` children are separated by `2N-1` gaps, so a row is
 * `2N*W + (2N-1)*G` wide while one pass is `N*W + N*G`. Translating by -50% then lands half a
 * gap short of the seam and the loop visibly snaps on every cycle. A `margin-right` on each
 * card makes one pass exactly half the row, which is what -50% assumes.
 *
 * <p>No JavaScript: a CSS animation on a server-rendered list. The band costs the landing no
 * client bundle and cannot produce a hydration mismatch, unlike a JS carousel. Hover pauses
 * it so a card can be read.
 *
 * <p><strong>It moves in one of three regimes, not two.</strong> Above 768px it is the marquee
 * described above. Below it the row HOLDS STILL and becomes a snap scroller the visitor swipes,
 * because a phone fits one 300px card and a moving band there is two half cards with their
 * sentences cut on both sides. `prefers-reduced-motion` reaches the same stopped, snapping,
 * unfaded state at any width. The two stopped regimes are kept identical on purpose and a test
 * compares them, since every argument for one is an argument for the other.
 */

/** The brand marks for one card, resolved from the verified list rather than hand-written. */
function integrationMarks(slugs: readonly string[] = []) {
  return slugs
    .map((slug) => WELL_KNOWN_INTEGRATIONS.find((integration) => integration.slug === slug))
    .filter((integration): integration is (typeof WELL_KNOWN_INTEGRATIONS)[number] => Boolean(integration));
}

/** Two initials, for a real customer who authorized their name but not a photograph. */
function initialsOf(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('');
}

function CardShell({ children }: { children: React.ReactNode }) {
  return (
    <article
      className="build-card shrink-0 w-[300px] md:w-[340px] p-5 rounded-2xl flex flex-col"
      style={{
        background: 'var(--bg-tertiary)',
        border: '1px solid var(--border-color)',
        boxShadow: 'var(--landing-card-shadow)',
      }}
    >
      {children}
    </article>
  );
}

/** A real customer, in their own words. Only ever rendered from authorized entries. */
function TestimonialCard({ testimonial }: { testimonial: Testimonial }) {
  const marks = integrationMarks(testimonial.integrations);

  return (
    <CardShell>
      <div className="flex items-center gap-3">
        {testimonial.avatar ? (
          <img
            src={testimonial.avatar}
            alt=""
            width={40}
            height={40}
            loading="lazy"
            decoding="async"
            className="rounded-full object-cover"
            style={{ width: 40, height: 40 }}
          />
        ) : (
          // A name without a photograph is a normal outcome: the two permissions are asked
          // separately and a customer may grant one. Initials keep the card's shape.
          <span
            aria-hidden="true"
            className="rounded-full flex items-center justify-center text-sm font-semibold shrink-0"
            style={{ width: 40, height: 40, background: 'var(--bg-secondary)', color: 'var(--text-secondary)' }}
          >
            {initialsOf(testimonial.name)}
          </span>
        )}
        <div className="min-w-0">
          <p className="text-sm font-semibold truncate" style={{ color: 'var(--text-primary)' }}>
            {testimonial.name}
          </p>
          <p className="text-xs truncate" style={{ color: 'var(--text-muted)' }}>
            {testimonial.role}
            {testimonial.company ? `, ${testimonial.company}` : ''}
          </p>
        </div>
      </div>

      <blockquote className="mt-4 text-sm leading-relaxed" style={{ color: 'var(--text-primary)' }}>
        {testimonial.quote}
      </blockquote>

      {marks.length > 0 && (
        <div className="mt-4 pt-3 flex items-center gap-1.5" style={{ borderTop: '1px solid var(--border-color)' }}>
          {marks.map((integration) => (
            <BrandMark key={integration.slug} iconSlug={integration.iconSlug} size={18} />
          ))}
        </div>
      )}
    </CardShell>
  );
}

/** What the product can do, attributed to nobody. The shape the band has until quotes land. */
function AutomationCard({ automation }: { automation: AutomationExample }) {
  return (
    <CardShell>
      <div className="flex items-center gap-1.5">
        {integrationMarks(automation.integrations).map((integration) => (
          <BrandMark key={integration.slug} iconSlug={integration.iconSlug} size={20} />
        ))}
        <span className="ml-1 text-xs font-medium" style={{ color: 'var(--text-muted)' }}>
          {automation.role}
        </span>
      </div>
      <p className="mt-3 text-sm leading-relaxed" style={{ color: 'var(--text-primary)' }}>
        {automation.automation}
      </p>
    </CardShell>
  );
}

/**
 * One scrolling row. `cards` is rendered twice: the visible pass and an `aria-hidden` copy
 * that the -50% translation lands on, which is what makes the loop seamless.
 *
 * <p>`tabIndex` and the label are unconditional, so the row is a tab stop in all three regimes.
 * In the two stopped ones (below 768px, and reduced motion at any width) it has to be: the row
 * becomes a horizontal scroller whose cards hold no focusable control of their own, so past the
 * fold they would otherwise be reachable by pointer only. While the animation runs it is not
 * inert either, and that is deliberate rather than tolerated: focusing the row pauses it (see
 * the `:focus` selector in the landing styles), so a keyboard visitor can stop a moving band
 * and read it, which a pointer visitor gets from hover.
 */
/**
 * What one card occupies in a moving row at >=768px: `w-[340px]` plus the 20px margin that
 * stands in for a flex gap (see the seam note above).
 */
const CARD_PITCH = 360;

/**
 * How wide one pass has to be before the loop can never show bare track.
 *
 * <p>The animation slides the row from 0 to -50%, so the content's right edge ends the cycle
 * at exactly one pass width. Anything wider than that is blank for the tail of every cycle.
 * While the band was clipped to the 1104px content box this was unreachable; making it
 * full-bleed made the viewport the limit, and six cards a row is 2160px, so a 2560px monitor
 * got a white band cycling in for the last seconds of every loop.
 *
 * <p>4320px covers a 4K screen at 100% with room over, and the repeat count is DERIVED rather
 * than hardcoded, so trimming the example list cannot silently reintroduce the gap.
 */
const WIDEST_SUPPORTED_ROW = 4320;

/** How many times a row must repeat its cards to fill {@link WIDEST_SUPPORTED_ROW}. */
export function passRepeats(cardsInRow: number): number {
  if (cardsInRow <= 0) return 1;
  return Math.max(1, Math.ceil(WIDEST_SUPPORTED_ROW / (cardsInRow * CARD_PITCH)));
}

function Row({
  cards,
  reverse,
  label,
  render,
}: {
  cards: readonly unknown[];
  reverse?: boolean;
  label: string;
  render: (card: never, index: number) => React.ReactNode;
}) {
  const once = cards.map((card, index) => render(card as never, index));
  // One pass is the card set repeated enough times to outrun the widest screen; the repeats
  // past the first are `aria-hidden`, so a screen reader still hears each entry once.
  const fills = Array.from({ length: passRepeats(cards.length) - 1 }, (_, index) => (
    <div key={`fill-${index}`} aria-hidden="true" className="build-row-echo flex">{once}</div>
  ));
  const pass = <>{once}{fills}</>;

  return (
    <div className="build-row-viewport" tabIndex={0} role="group" aria-label={label}>
      {/* The row is the pass TWICE, so -50% lands exactly on the seam. The echo is one
          wrapper around the whole pass rather than one per card: same width, and it keeps the
          halves obviously symmetrical. */}
      <div className={`build-row flex${reverse ? ' build-row-reverse' : ''}`}>
        {pass}
        <div aria-hidden="true" className="build-row-echo flex">{pass}</div>
      </div>
    </div>
  );
}

/** True when the band is showing real customers rather than capability examples. */
export function showingTestimonials(): boolean {
  return testimonialsReady(TESTIMONIALS);
}

/**
 * Below this, the two-row marquee is the wrong shape and a static grid is used instead.
 *
 * <p>A 1440px row shows about four cards, so a row needs roughly eight to read as a wall in
 * motion rather than as a short list sliding past. Verified with three entries: the first row
 * carried two cards and the second carried one, the loop was mostly empty track, and the
 * section rendered as a half-empty band. Collecting the first few real quotes is exactly when
 * that happens, so the grid is not a degraded mode, it is the right layout for that stage.
 */
const MARQUEE_MIN_CARDS = 8;

/**
 * The grid keeps its own content box because the band is rendered full-bleed (the `bleed`
 * slot on Section): the marquee WANTS the page's width, a three-column grid of 340px cards
 * stranded across 1920px does not, and would no longer line up with the heading above it.
 */
function Grid({ children }: { children: React.ReactNode }) {
  return (
    <div className="mt-12 max-w-6xl mx-auto px-6 grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 md:gap-6 justify-items-center">
      {children}
    </div>
  );
}

function Marquee({
  cards,
  render,
  rowLabels,
}: {
  cards: readonly unknown[];
  render: (card: never, index: number) => React.ReactNode;
  rowLabels: readonly [string, string];
}) {
  const half = Math.ceil(cards.length / 2);
  return (
    <div className="mt-12 space-y-4 md:space-y-5">
      <Row cards={cards.slice(0, half)} label={rowLabels[0]} render={render} />
      <Row cards={cards.slice(half)} reverse label={rowLabels[1]} render={render} />
    </div>
  );
}

/**
 * @param examples the band's own cards. Every caller passes its TRANSLATED set (the constant
 * below is English and every surface that shows this band is served in six languages), which
 * also means the testimonial switch does not apply to them: the quotes are English, and a
 * French page carrying them would be worse than carrying none.
 * @param rowLabels the accessible name of each row. The rows are focusable scrollers, so
 * these ARE user-facing text: they were English literals on all six locales, in the one
 * component the translation pass was rebuilding.
 */
export default function BuildableAutomations({ examples, rowLabels = DEFAULT_ROW_LABELS }: {
  examples?: readonly AutomationExample[];
  rowLabels?: readonly [string, string];
} = {}) {
  if (!examples && showingTestimonials()) {
    const card = (t: Testimonial) => <TestimonialCard key={t.name + t.quote.slice(0, 24)} testimonial={t} />;
    return TESTIMONIALS.length >= MARQUEE_MIN_CARDS ? (
      <Marquee cards={TESTIMONIALS} render={card} rowLabels={rowLabels} />
    ) : (
      <Grid>{TESTIMONIALS.map((t) => card(t))}</Grid>
    );
  }

  const card = (a: AutomationExample) => <AutomationCard key={a.role} automation={a} />;
  return <Marquee cards={examples ?? AUTOMATION_EXAMPLES} render={card} rowLabels={rowLabels} />;
}

/** Only reached by a caller that passes neither examples nor labels, i.e. a test. */
const DEFAULT_ROW_LABELS = ['Example automations, first row', 'Example automations, second row'] as const;
