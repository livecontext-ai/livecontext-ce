/**
 * Contracts this feature depends on that live OUTSIDE any component's render
 * output, and are therefore invisible to every rendering test.
 *
 * Both were found by mutation: renaming the gold custom properties in
 * globals.css, and re-introducing a second allowance derivation on the quota
 * page, each left the whole suite green while breaking the feature's headline
 * behaviour for real users.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const root = join(__dirname, '..', '..', '..');
const read = (rel: string) => readFileSync(join(root, rel), 'utf-8');

describe('the gold gauge palette is actually defined', () => {
  const css = read('app/globals.css');
  // BalanceBreakdown joined this list when the surplus sentence moved onto the
  // wallet card: it now inks that sentence with `--credit-gold-ink`, so leaving
  // it out would let the card hardcode a hex and keep every assertion here
  // green while light-mode readers got the dark-theme metal on white.
  const source =
    read('components/billing/CreditBalance.tsx') +
    read('components/billing/SidebarCreditBalance.tsx') +
    read('components/billing/BalanceBreakdown.tsx');

  // Derived from the components, NOT hardcoded here: a NEW gold variable is
  // covered the moment it is used, without anyone remembering to add it.
  //
  // The name pattern is deliberately PERMISSIVE (any identifier character, not
  // just lowercase). A stricter class silently fails to match a mistyped name,
  // which drops it out of this list instead of failing on it - so renaming
  // `--credit-gold-arc-track` to something globals.css does not define made the
  // reference disappear and the suite stay green, which is the exact hole this
  // file was written to close.
  const referenced = [...new Set([...source.matchAll(/var\((--credit-gold-[A-Za-z0-9_-]+)\)/g)].map((m) => m[1]))];

  // Pinned BY NAME, not by a count. A floor of "at least 5" over 6 variables
  // buys exactly one free deletion, and the set-equality below is blind to a
  // SYMMETRIC one: delete `--credit-gold-ink` from globals.css and replace its
  // use site with a literal, and both halves shrink together while every
  // assertion stays green. That variable is the colour of the ring's "+" -
  // which, since the labelled bar was removed from the user menu, is the ONLY
  // non-colour signal any rendered surface gives for "over your plan" versus
  // "plan exhausted". It exists precisely because the bright metal fails
  // contrast on white, so losing it silently is the worst case this file has
  // to prevent.
  const REQUIRED = [
    '--credit-gold-ink',
    '--credit-gold-arc',
    '--credit-gold-arc-track',
    '--credit-gold-metal-1',
    '--credit-gold-metal-2',
    '--credit-gold-metal-3',
  ];

  it.each(REQUIRED)('still uses %s somewhere in the credit components', (name) => {
    expect(referenced).toContain(name);
  });

  it('inks the gold in BOTH places it is now written, not just one', () => {
    // Two use sites: the ring's "+" and the wallet card's surplus sentence.
    // Replacing either with a literal leaves the other reference alive, so the
    // set membership above still passes - which is exactly how a light-mode
    // contrast failure ships unnoticed.
    const inkUses = [...source.matchAll(/var\(--credit-gold-ink\)/g)].length;
    expect(inkUses).toBeGreaterThanOrEqual(2);
  });

  it('keeps the metallic sweep at THREE stops, not one flattened colour', () => {
    // Membership is not enough for the sweep: the whole reason it is a gradient
    // is that no single hue bright enough to look like metal clears 3:1 on
    // white, so a "simplification" down to one or two stops is a legibility
    // regression the set check above cannot see (the names would still all be
    // referenced if two stops shared one variable).
    const stops = [...source.matchAll(/stopColor="var\(--credit-gold-metal-[123]\)"/g)];
    expect(stops).toHaveLength(3);
    expect(new Set(stops.map((m) => m[0])).size).toBe(3);
  });

  it('never hardcodes a gold hex beside a `gold` branch', () => {
    // The shape the mutation above takes: a literal where the token belongs.
    // Every gold colour must come from a custom property, or the light and dark
    // palettes stop being able to differ at all.
    const goldHexes = [...source.matchAll(/gold\s*\?\s*'#[0-9a-fA-F]{3,8}'/g)];
    expect(goldHexes).toEqual([]);
  });

  it('names exactly the palette globals.css declares, with nothing orphaned either way', () => {
    // Bidirectional. The per-name checks below only prove source -> CSS; this
    // also catches a variable left declared in CSS after its last use, and it
    // fails on a rename from EITHER side rather than quietly resizing the list.
    const declared = [...new Set([...css.matchAll(/(--credit-gold-[A-Za-z0-9_-]+)\s*:/g)].map((m) => m[1]))];
    expect([...referenced].sort()).toEqual([...declared].sort());
  });

  // Each theme is checked against its OWN block. Searching the whole file
  // instead is an always-green assertion: the .dark declarations alone satisfy
  // it, so deleting the entire :root palette left this suite passing while every
  // light-mode user got an invalid-at-computed-value-time stroke and background.
  const rootBlock = css.slice(css.indexOf(':root {'), css.indexOf('.dark {'));
  const darkBlock = css.slice(css.indexOf('.dark {'));

  it('found both theme blocks to slice', () => {
    // Without this, a renamed selector would make both slices empty or identical
    // and turn every per-name check below into a vacuous pass.
    expect(rootBlock.length).toBeGreaterThan(0);
    expect(darkBlock.length).toBeGreaterThan(0);
    expect(rootBlock).not.toContain('.dark {');
  });

  it.each(referenced)('defines %s on :root, for the light theme', (name) => {
    expect(rootBlock).toContain(`${name}:`);
  });

  it.each(referenced)('redefines %s under .dark', (name) => {
    // A variable declared only on :root renders the light-theme gold on a dark
    // ground: legible, but wrong, and no rendering test can see it.
    expect(darkBlock).toContain(`${name}:`);
  });

  it('draws the over-allowance ring on a gold track, not the neutral one', () => {
    // The gold state deliberately drops the neutral track class, so if
    // --credit-gold-arc-track disappears the arc loses the faint full circle
    // behind it and floats unanchored. That is the specific mutation this kills.
    expect(referenced).toContain('--credit-gold-arc-track');
  });

  it('declares no gold the credit surfaces stopped using', () => {
    // The bar that carried --credit-gold-fill-from/-to/-track was removed from
    // the user menu, and those three declarations went with it. Named here
    // rather than left to the set-equality above, because that check is
    // satisfied the moment BOTH sides shrink together - including by a
    // re-introduced bar that quietly brings the palette back with it.
    const gone = ['--credit-gold-fill-from', '--credit-gold-fill-to', '--credit-gold-track'];
    for (const name of gone) {
      expect(css).not.toContain(`${name}:`);
      expect(referenced).not.toContain(name);
    }
  });
});

describe("the avatar ring's sweep survives a browser, not just jsdom", () => {
  // jsdom never paints, so a rendering test cannot tell one requestAnimationFrame
  // from two - and the difference is the whole animation. A CSS transition runs
  // only when the browser has already painted the property at its starting value;
  // arm the sweep from a single frame and the offset changes in the same frame it
  // was first set, so the ring appears at its final position with no travel. The
  // suite stays green either way, which is exactly the shape this file is for.
  // Comments STRIPPED, via the same helper the allowance block below uses and
  // for the same reason its doc block gives: these read raw source, so a note
  // that merely mentions `matchMedia` - for instance one explaining why the
  // shared hook is used INSTEAD of it - would fail CI with no behavioural
  // change. (`code` is hoisted, so using it above its definition is fine.)
  const source = code(read('components/billing/CreditBalance.tsx'));

  it('arms the sweep from a SECOND frame, so the empty ring is painted first', () => {
    expect(source).toMatch(
      /requestAnimationFrame\(\(\)\s*=>\s*\{[\s\S]{0,120}requestAnimationFrame\([\s\S]{0,80}setPhase\('opening'\)/,
    );
  });

  it('cancels BOTH frames on unmount, so a torn-down ring cannot set state', () => {
    // The inner handle is assigned inside the outer callback, so the naive
    // cleanup cancels only the outer one - and a component unmounted between the
    // two frames then calls setState on a dead tree.
    const cleanup = source.slice(source.indexOf('const first = requestAnimationFrame'));
    expect(cleanup).toContain('cancelAnimationFrame(first)');
    expect(cleanup).toContain('cancelAnimationFrame(second)');
  });

  it('asks the shared hook for the motion setting rather than reading the query again', () => {
    // A local matchMedia copy is how the app ends up with two answers to one
    // question, and the effect-based shape of it paints a frame with the wrong
    // one. The hook reads it during render and stays subscribed.
    expect(source).toContain("from '@/hooks/usePrefersReducedMotion'");
    expect(source).not.toContain('matchMedia');
  });

  it('leaves the once-per-visit memory OUTSIDE the ring, where a remount cannot reach it', () => {
    // The ring is mounted from both arms of AppSidebar's collapsed/expanded
    // ternary, so React destroys its state on every toggle. Moving the flag back
    // inside - the obvious tidy-up, since it is only read there - restores the
    // bug where the whole reveal replays each time the sidebar is collapsed, and
    // it does so in a way no single-render test can see.
    const ring = code(read('components/billing/CreditBalance.tsx'));
    const sidebar = code(read('components/billing/SidebarCreditBalance.tsx'));

    // The prop is READ, not merely mentioned: `animate` also appears inside
    // `requestAnimationFrame`, so a bare substring check is nearly vacuous.
    expect(ring).toMatch(/animate\s*\?\s*'hidden'\s*:\s*'settled'/);
    expect(ring).not.toContain('credit-ring-reveal');
    expect(sidebar).toContain("from '@/lib/billing/credit-ring-reveal'");
    expect(sidebar).toMatch(/animate=\{animate\}/);
  });
});

/**
 * Comments stripped before matching. These assertions read raw source, so a
 * future note that merely MENTIONS `CREDIT_TIERS` on the quota page would fail
 * CI with no behavioural change - the mirror image of a mutation that lands on
 * a comment instead of the code it looks like.
 */
function code(text: string): string {
  return text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
}

describe('the monthly allowance has exactly one derivation', () => {
  const quotaPage = code(read('app/[locale]/app/settings/quota/page.tsx'));

  it('has the quota page CALL the shared wallet hook', () => {
    // `toContain('useCreditWallet')` is satisfied by a comment mentioning it.
    // Match the call, and the destructure that takes the allowance out of it.
    expect(quotaPage).toMatch(/useCreditWallet\s*\(\s*\)/);
    expect(quotaPage).toMatch(/allowance\s*,[\s\S]{0,80}=\s*useCreditWallet/);
  });

  it('feeds the wallet card FROM that allowance, not from a constant', () => {
    // Without this, replacing the derivation with `const monthlyPlan = undefined`
    // left the ENTIRE suite green - and with it the migration's whole
    // user-visible payoff, a FREE cloud account finally seeing its 1,000-credit
    // monthly grant on the page the header dial links to.
    //
    // Pinned unchanged: `allowance` comes from the destructured hook value and the else
    // branch is `undefined` rather than a fabricated plan.
    expect(quotaPage).toMatch(
      /monthlyPlan\s*=\s*allowance\s*!==\s*null\s*\?\s*\{[^;{}]*\}\s*:\s*undefined/,
    );
  });

  it('passes the RENEWAL through the same object, from the same hook read', () => {
    // Widening the assertion above to admit extra fields made deleting them a green
    // mutation: `{ allowance }` matches it just as happily as `{ allowance, renewsAt,
    // periodEndsAt }`, so reverting this line would silently remove the renewal sentence
    // from the wallet card with the whole suite passing. That is the exact mutation class
    // this file's header says it exists to close, so each field is named.
    expect(quotaPage).toMatch(/monthlyPlan\s*=[^;]*\brenewsAt\b/);
    expect(quotaPage).toMatch(/monthlyPlan\s*=[^;]*\bperiodEndsAt\b/);
    // The suppression travels with them. Without it the card promises the CURRENT tier's
    // grant on a date when a scheduled downgrade will deliver a different one, which is the
    // one thing the Billing page's equivalent row already refuses to do.
    expect(quotaPage).toMatch(/monthlyPlan\s*=[^;]*\bhasScheduledChange\b/);
    // Destructured from the hook, not a literal. Naming the field alone would be satisfied by
    // `hasScheduledChange: false`, which is the un-suppressed promise wearing the guard's name.
    expect(quotaPage).toMatch(/\{\s*hasScheduledChange\s*\}\s*=\s*useScheduledPlanChange\s*\(/);
    // And they are the hook's, not re-read from somewhere else on the page.
    expect(quotaPage).toMatch(/renewsAt\s*,[\s\S]{0,80}=\s*useCreditWallet/);
    expect(quotaPage).toMatch(/periodEndsAt\s*,[\s\S]{0,80}=\s*useCreditWallet/);
  });

  it('re-derives the allowance from NOTHING else on the page', () => {
    // The claim is "exactly one derivation", so name every other way to reach
    // one: the tier table, and the resolver the hook itself calls. Checking a
    // single constant let the page reach the same number by another route.
    expect(quotaPage).not.toContain('CREDIT_TIERS');
    expect(quotaPage).not.toContain('pricing-constants');
    expect(quotaPage).not.toContain('resolveMonthlyAllowance');
    expect(quotaPage).not.toContain('FREE_MONTHLY_CREDITS');
  });
});

/**
 * The Billing page is the ONE surface that must NOT go through the wallet hook, and the
 * reason is easy to lose in a refactor that is trying to be tidy.
 *
 * <p>`useCreditWallet` answers null for everything whenever the wallet on screen belongs to
 * somebody else, which under owner-pays is any member inside a workspace they do not own.
 * That guard is right for the WALLET: the balance shown there is the owner's, so our tier is
 * the wrong denominator for it. It is wrong here, because this page reads `/billing/me`,
 * which answers for the signed-in user's OWN subscription whatever workspace is active.
 * Routing this page through the hook would blank its credit amount for every member of
 * somebody else's workspace, on a page about their own plan, and no rendering test that
 * mounts the page in a personal context could see it.
 */
describe('the Billing page reads its own plan, not the payer wallet', () => {
  const billingPage = code(read('app/[locale]/app/settings/billing/page.tsx'));

  it('resolves the cycle grant from the shared resolver, not from the tier table', () => {
    // Still ONE derivation of the number: the shared function, never CREDIT_TIERS directly.
    expect(billingPage).toMatch(/resolveMonthlyAllowance\s*\(/);
    expect(billingPage).not.toContain('CREDIT_TIERS');
    expect(billingPage).not.toContain('FREE_MONTHLY_CREDITS');
  });

  it('does not reach for the wallet hook, whose owner-pays guard does not apply here', () => {
    expect(billingPage).not.toContain('useCreditWallet');
  });

  it('shows the credit date the backend named, never the invoice date in its place', () => {
    // currentPeriodEnd is the WRONG date on a yearly plan, which is the whole reason the
    // row exists. Pinning the field name stops a future "simplification" from reusing the
    // date already in scope.
    expect(billingPage).toMatch(/nextCreditGrantAt/);
  });
});
