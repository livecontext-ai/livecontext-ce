import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'fs';
import { join, relative } from 'path';

/**
 * Repo-wide guard: a conversation URL is built by `conversationRoute`, never by hand.
 *
 * <p><strong>Why this is a test and not just a rule.</strong> A conversation lives on one of two
 * surfaces and the kind decides which. Send a studio conversation to `/app/c/{id}` and the chat
 * page renders a blank thread - its renderer suppresses generation envelopes rather than showing
 * raw JSON - and the composer then sends the reader's next message to a CHAT model with those
 * envelopes as prior context. That is the one state the immutable `kind` exists to forbid, reached
 * by a URL alone, with no error and nothing in any log.
 *
 * <p>Three surfaces navigate to a conversation: the sidebar, the global search, and the chat page's
 * own redirect for a studio thread reached by an old link. Each was one hand-written template
 * literal, and mutation testing confirmed all three could be reverted to the chat route with the
 * entire suite still green (1120, 1861 and 446 tests respectively). Each lives inside a component
 * too large to mount for the sake of a ternary, which is exactly how they came to have no test
 * between them - and a fourth site added tomorrow would arrive the same way.
 *
 * <p>So the rule is enforced on the SHAPE instead: the moment a file BUILDS a conversation path -
 * by interpolation, by concatenation, or with the surface itself computed - it has taken the
 * decision locally and can take it wrongly. It enforces spellings, not intent, so it is a fence
 * and not a proof; the decision itself is proved by conversationRoute own suite. `conversationRoute` has its
 * own unit test for the decision itself; this scan is what keeps every caller inside it.
 */

const FRONTEND_ROOT = join(__dirname, '..');

/**
 * Files permitted to write a conversation path literally, each for a reason that is not routing.
 *
 * <p>Kept short deliberately. A new entry here is a new place the two surfaces can be confused, so
 * it needs a reason that says why the kind cannot matter at that site.
 */
const ALLOWED = new Set([
  // Declares the rule. Matching the declaration is the scan working, not a violation.
  join('lib', 'api', 'conversation.types.ts'),

  // Sites where the kind is known BY CONSTRUCTION, so there is no decision to get wrong.
  //
  // The studio's own two: one sends a conversation away that it has just determined is NOT a studio
  // one, the other opens a conversation it has just created AS a studio one.
  join('components', 'studio', 'StudioSurface.tsx'),
  // Syncs the URL after a conversation is created ON the chat surface by the chat composer.
  join('hooks', 'chat', 'useMessageHandlersV2.ts'),
  // Rewrites the chat route, locale prefix included. It moves a URL between shapes rather than
  // choosing a surface for a conversation.
  join('proxy.ts'),
  // Returns to the conversation this chat page already has open, from its own data-source panel.
  join('app', 'shared', 'components', 'ChatPageLayout.tsx'),

  // The one site that holds an id and NOTHING else: a shared link records a resource type and an
  // id, never a kind, so it cannot ask. It is safe because the chat page redirects a studio
  // conversation to the studio (see the effect in ChatPageV2), which is the fallback this rule
  // exists alongside rather than instead of.
  join('components', 'chat', 'NotificationBell.tsx'),

  // An agenda occurrence for an AGENT run. It holds a conversationId and no kind, but it cannot
  // get the kind wrong: the conversation of an agent run is always a CHAT one, and that is
  // enforced by the SERVER, not by convention. ConversationCommandService refuses
  // {agentId, kind != CHAT} outright ("A conversation cannot be both an agent conversation and a
  // 'studio' one"), gated on the agent id alone so a sub-agent or a memory-off row cannot slip
  // past it either. A studio thread therefore never carries an agentId, and an occurrence never
  // carries a studio conversation. If that refusal is ever relaxed, this entry becomes wrong and
  // the occurrence must start carrying the kind instead.
  join('components', 'agenda', 'agendaVisuals.ts'),
]);

/** Not production code: tests legitimately assert on the literal routes. */
const SKIP_DIRS = new Set([
  'node_modules', '.next', '__tests__', 'e2e', 'coverage', 'public', 'messages', 'test-results',
]);

const SOURCE_EXTENSIONS = ['.ts', '.tsx'];

/**
 * A conversation path being BUILT, in any of the spellings that build one.
 *
 * <p>Deliberately not a match on the bare prefix. `/app/c/` appears all over the app in pathname
 * MATCHING - `startsWith('/app/c/')`, side-panel scope globs, the auto-load path table - and none
 * of those choose a surface for a conversation, so a guard that failed on them would be switched
 * off within a day. What is dangerous is the shape that names ONE conversation, because that is
 * where the kind decides the destination.
 *
 * <p>Three spellings, because the first version matched only the template literal and was bypassed
 * in review by the most obvious alternative: `'/app/c/' + conversation.id` reverted the sidebar to
 * always routing to the chat surface with this guard still green. That is the argument the auth
 * token guard already lost once and stopped playing; this one stops at the three ways an id can be
 * joined to a prefix, and the last pattern also catches a computed SURFACE (`/app/${surface}/`),
 * which is the same decision taken one character further left.
 */
const BUILD_SPELLINGS = [
  // `/app/c/${id}` - interpolation
  /\/app\/(c|studio)\/\$\{/,
  // '/app/c/' + id, and '/app/c/'.concat(id)
  /['"`]\/app\/(c|studio)\/['"`]\s*(\+|\.concat)/,
  // `/app/${surface}/...` - the surface itself computed
  /\/app\/\$\{/,
];
const HAND_BUILT = { test: (source: string) => BUILD_SPELLINGS.some((re) => re.test(source)) };

function collectSourceFiles(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    if (SKIP_DIRS.has(entry)) continue;
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      collectSourceFiles(full, out);
    } else if (SOURCE_EXTENSIONS.some((ext) => entry.endsWith(ext)) && !entry.includes('.test.')) {
      out.push(full);
    }
  }
  return out;
}

describe('where a conversation opens', () => {
  it('is decided by conversationRoute everywhere outside the documented allow-list', () => {
    const offenders = collectSourceFiles(FRONTEND_ROOT)
      .filter((file) => HAND_BUILT.test(readFileSync(file, 'utf8')))
      .map((file) => relative(FRONTEND_ROOT, file))
      .filter((rel) => !ALLOWED.has(rel));

    expect(
      offenders,
      'These files build a conversation URL by hand, which means they decide locally which of the '
        + 'two surfaces a conversation belongs on. Get it wrong for a studio thread and the chat '
        + 'page renders it blank and then feeds its generation envelopes to a chat model. Use '
        + '`conversationRoute(conversation)` from lib/api/conversationApi. If a site genuinely '
        + 'cannot use it, add it to ALLOWED with the reason it cannot get the kind wrong. '
        + `Offenders:\n  ${offenders.join('\n  ')}`,
    ).toEqual([]);
  });

  it('catches both surfaces, so a wrong route in either direction is a failure', () => {
    // A studio conversation sent to the chat page is the expensive direction; a chat sent to the
    // studio is merely broken, bounced back by the studio's own guard. Both are the same mistake.
    expect(HAND_BUILT.test('safeNavigate(`/app/c/${conversation.id}`)')).toBe(true);
    expect(HAND_BUILT.test('router.replace(`/app/studio/${currentConversation.id}`)')).toBe(true);
    expect(HAND_BUILT.test('href: `/app/c/${conv.id}`')).toBe(true);
  });

  it('catches the spellings that are NOT a template literal', () => {
    // Each of these was verified to bypass the first version of this guard. Concatenation is the
    // one a reviewer actually used to revert the sidebar with the guard still green.
    expect(HAND_BUILT.test("safeNavigate('/app/c/' + conversation.id)")).toBe(true);
    expect(HAND_BUILT.test('safeNavigate("/app/c/" + conversation.id)')).toBe(true);
    expect(HAND_BUILT.test("const href = '/app/studio/'.concat(conv.id);")).toBe(true);
    expect(HAND_BUILT.test('router.push(`/app/${surface}/${conv.id}`)')).toBe(true);
  });

  it('leaves the id-less destinations alone, because they choose no surface', () => {
    // `/app/chat` and a bare `/app/studio` are fixed pages, not a conversation: there is no kind to
    // read and nothing to get wrong. A guard that failed on those would be turned off.
    expect(HAND_BUILT.test("router.push('/app/chat')")).toBe(false);
    expect(HAND_BUILT.test("<Link href='/app/studio'>")).toBe(false);
  });

  it('leaves pathname MATCHING alone, which is most of the mentions in the app', () => {
    // The header, the sidebar, the side-panel scopes and the auto-load table all READ the current
    // path. None of them chooses where a conversation opens, and failing on them is how a rule of
    // this shape gets deleted rather than obeyed.
    expect(HAND_BUILT.test("normalizedPathname?.startsWith('/app/c/')")).toBe(false);
    expect(HAND_BUILT.test("scope: ['/app/c/*', '/app/chat']")).toBe(false);
    expect(HAND_BUILT.test("chat: { load: true, paths: ['/app/chat', '/app/c/'] }")).toBe(false);
  });

  it('keeps the chat page WIRED to the redirect the allow-list depends on', () => {
    // Two exemptions above are justified by this call, not by their own safety.
    //
    // A shared link records a resource id and NO kind, so NotificationBell cannot ask what it is
    // routing; proxy.ts rewrites a legacy chat URL without loading anything. Both send a possibly
    // studio conversation to /app/c, and both are fine ONLY because the chat page then corrects it.
    // Delete that one line and the exemptions become false while this file still passes: the rule
    // above would be green over a hole it had itself declared safe.
    //
    // Asserted on the source because the effect lives in a component nobody mounts - that is the
    // whole reason the rule was extracted into a hook, and it is why deleting the CALL survived a
    // 2120-test run while deleting the hook's body did not.
    const page = readFileSync(join(FRONTEND_ROOT, 'components', 'chat', 'ChatPageV2', 'index.tsx'), 'utf8');

    expect(
      page.includes('useConversationSurfaceRedirect('),
      'The chat page no longer calls useConversationSurfaceRedirect. A studio conversation opened '
        + 'at /app/c renders a blank thread and its composer then sends the next message '
        + 'to a CHAT model with generation envelopes as context. If the correction has moved '
        + 'somewhere else, point this check at its new home and revisit the ALLOWED entries that '
        + 'name it as their reason.',
    ).toBe(true);
  });
});
