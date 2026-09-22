import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

/**
 * Every mutation that changes an automation row asks for the bell's rows again.
 *
 * <p>The bell's Triggers tab, and the imminent-fire ring that pulses WITHOUT the bell ever
 * being opened, are drawn from the home-status payload. That payload polls once a minute and
 * NO mutation invalidates it: pin a workflow, pause a schedule, move an occurrence, then look
 * at the bell, and you read the world as it was before you acted. That is the defect this
 * guards, and it had eighteen call sites across ten files, plus two the pattern below cannot
 * see, none of which knew about each other.
 *
 * <p>Why a source-level test rather than a component test per site. Each site is covered by
 * its own suite where one exists, but the next call site would be born with the same
 * omission and every existing test would stay green. The rule is about the SET of call sites,
 * so nothing that renders a single component can hold it. Several of the files -
 * `WorkflowVersionHistory`, `AgendaView`, `ScheduleTabContent`, the two trigger parameter forms
 * and the public-access page - have no suite that reaches these handlers at all, so for those
 * this is the coverage rather than a supplement to it. That is also why the binding is checked
 * below and not just the call: in a file with no suite, replacing the hook with `() => {}`
 * leaves the ask in place, doing nothing, and every test green.
 */

const ROOT = process.cwd();

/**
 * Directories that hold no product source. Everything else under `frontend/` is scanned,
 * deliberately: an allow-list of roots is how a call site in a directory nobody thought of
 * (`contexts/`, say) gets born unguarded, which is the exact shape of the defect above.
 */
const NOT_SOURCE = new Set([
  'node_modules', '.next', '.turbo', 'coverage', 'public', 'messages', 'e2e', 'scripts',
  '__tests__', '__mocks__',
]);

/**
 * The mutations that decide whether a row EXISTS (pin, unpin, arm, disarm) or when it next
 * fires (move, run now, pause, resume).
 *
 * <p>Each is matched at its RECEIVER, and the receiver differs by design. A method reached
 * through an object takes the dot (`.pinVersion(`), which is also what keeps the declaration in
 * the service and a `{@code pinVersion(...)}` inside a doc comment out of the set. A plain
 * imported function takes the opposite rule (`setProductionResourcePaused`, preceded by neither
 * an identifier character, a dot, nor the word `function`, which is what keeps ITS declaration
 * out): written WITH a dot it matched nothing at all, silently, because both of its real call
 * sites are bare calls - the entry was decoration and the two sites behind it were unguarded. The agenda's three are qualified by
 * their service because `move` and `toggle` are ordinary words: unqualified, `move` matched a
 * folder move, a file with nothing to do with automations. Both schedule services are named,
 * not just the agenda's: `scheduleSettingsService.toggle` posts to the SAME endpoint, so
 * qualifying on one service alone left the settings page arming and disarming rows that the
 * bell went on listing - the reported defect, alive on another surface, under a suite whose
 * title said "every".
 *
 * <p>What is deliberately NOT here: `cancelWorkflow` / `reactivateWorkflow`. Those are the
 * run-execution primitives `setProductionResourcePaused` is built ON, and the surfaces calling
 * them directly (the run panel, the run manager) are about a RUN, not an automation. Pausing a
 * production run there does eventually reach the bell's `resourcePaused`, and the residual is
 * worth stating precisely: the Triggers TAB is covered anyway, because landing on it asks for
 * the rows, so only the imminent-fire ring, which draws without the bell being opened, can lag
 * until the next poll. The board is the one place that calls those primitives to MEAN "pause
 * this automation", and it refreshes - asserted below by name rather than by pattern, since a
 * pattern here would drag in every run surface.
 *
 * <p>Nor is DELETING a pinned resource, which also removes a row. It happens from nine places
 * (the tables, the side panel, the chat surfaces), none of them about automations, and the row
 * it removes is one the user has just destroyed on purpose rather than one they are about to
 * look at. Same residual as above, stated so the omission is a decision and not an oversight:
 * the Triggers tab is covered by the visit-ask, and the imminent ring can pulse for a deleted
 * workflow until the next poll.
 */
const MUTATION_CALL = new RegExp([
  /\.pinVersion\(/,
  /(?<![\w$.])(?<!function )setProductionResourcePaused\(/,
  /(?:agendaService|scheduleSettingsService)\.(?:runNow|toggle|move|delete|create|update)\(/,
  /webhookSettingsService\.(?:create|delete)\(/,
].map((part) => part.source).join('|'), 'g');

/**
 * A backstop on the window, not the thing that bounds it: the block end and the next call do the
 * real work below. It was 900 while that was not yet true, which left 22 characters of headroom
 * at the widest site - so adding one ordinary comment line inside an unrelated `try` block
 * failed this suite, telling the reader to add a refresh that was already three lines down.
 */
const REFRESH_LOOKAHEAD = 4000;

/**
 * Where a call's window ends: the first brace that closes the block the call sits IN.
 *
 * <p>This is the whole difficulty of a guard like this one. A plain character budget is porous
 * in both directions, and measurably so: with one, deleting the ask beside the board's unpin
 * left this suite green, because the window ran on into the sibling `else if` branch whose own
 * ask was 5 lines further down. A site must be covered by ITS ask, never by a neighbour's.
 *
 * <p>Counted on source with comments and string literals blanked out, because a brace inside
 * either is not a block. That is not tidiness: an unbalanced `{` in a nearby comment, say one
 * quoting a response shape, pushes the window PAST the block end and lets the next branch's ask
 * cover a site that has none. It fails open, which is the one way a guard must never fail.
 */
function enclosingBlockEnd(code: string, from: number): number {
  let depth = 0;
  for (let i = from; i < code.length; i += 1) {
    if (code[i] === '{') depth += 1;
    else if (code[i] === '}') {
      if (depth === 0) return i;
      depth -= 1;
    }
  }
  return code.length;
}

/**
 * Replaces the CONTENT of comments and string literals with spaces, keeping every offset intact
 * so indexes taken on the original source still line up. Deliberately small: it has to be right
 * about braces, not to parse TypeScript.
 */
function blankOutCommentsAndStrings(source: string): string {
  const out = source.split('');
  let i = 0;
  while (i < source.length) {
    const two = source.slice(i, i + 2);
    if (two === '//') {
      while (i < source.length && source[i] !== '\n') { out[i] = ' '; i += 1; }
      continue;
    }
    if (two === '/*') {
      while (i < source.length && source.slice(i, i + 2) !== '*/') { out[i] = ' '; i += 1; }
      for (let j = 0; j < 2 && i < source.length; j += 1, i += 1) out[i] = ' ';
      continue;
    }
    const quote = source[i];
    if (quote === "'" || quote === '"' || quote === '`') {
      out[i] = ' ';
      i += 1;
      while (i < source.length && source[i] !== quote) {
        if (source.charCodeAt(i) === 92) { out[i] = ' '; i += 1; } // an escape: skip the pair
        if (i < source.length) { out[i] = ' '; i += 1; }
      }
      if (i < source.length) { out[i] = ' '; i += 1; }
      continue;
    }
    i += 1;
  }
  return out.join('');
}

function sourceFiles(dir: string, out: string[] = []): string[] {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (NOT_SOURCE.has(entry.name)) continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      sourceFiles(full, out);
    } else if (/\.tsx?$/.test(entry.name) && !/\.(test|spec)\.tsx?$/.test(entry.name)) {
      out.push(full);
    }
  }
  return out;
}

interface MutationSite {
  file: string;
  /** The method name alone, for a readable per-site test name. */
  method: string;
  /** The source between this call and the next one, which is where its ask belongs. */
  window: string;
}

function sitesIn(file: string, rawSource: string): MutationSite[] {
  // EVERYTHING below reads blanked source, not just the brace counting. A call that is commented
  // out is not a call, and - the hole this closes - an ask that is commented out, or sitting
  // inside a string, is not an ask: judged on raw source, `// refreshAutomations();` passed a
  // site that had none.
  const source = blankOutCommentsAndStrings(rawSource);
  const calls = [...source.matchAll(MUTATION_CALL)].map((match) => ({
    at: match.index!,
    // A match can carry a leading dot or a service prefix; a bare call carries neither.
    method: match[0].replace(/\($/, '').replace(/^.*\./, ''),
  }));

  return calls.map((call, index) => {
    // Three bounds, whichever comes first: the end of the call's own block, the next mutation
    // call (two in one block must not share an ask), and the character budget.
    const nextCall = calls[index + 1]?.at ?? Number.MAX_SAFE_INTEGER;
    const end = Math.min(call.at + REFRESH_LOOKAHEAD, nextCall, enclosingBlockEnd(source, call.at));
    // `source` is already blanked, so the window carries no comment or string text.

    return { file, method: call.method, window: source.slice(call.at, end) };
  });
}

function everySourceFile(): string[] {
  const entries = fs.readdirSync(ROOT, { withFileTypes: true })
    .filter((entry) => !NOT_SOURCE.has(entry.name) && !entry.name.startsWith('.'));
  return entries.flatMap((entry) => {
    const full = path.join(ROOT, entry.name);
    if (entry.isDirectory()) return sourceFiles(full);
    // Root-level source too (`proxy.ts` and friends): the walk promised "the whole frontend",
    // and a directory-only filter quietly excluded every file sitting at the top.
    return /\.tsx?$/.test(entry.name) && !/\.(test|spec)\.tsx?$/.test(entry.name) ? [full] : [];
  });
}

const CALL_SITES: MutationSite[] = everySourceFile().flatMap((file) =>
  sitesIn(path.relative(ROOT, file).split(path.sep).join('/'), fs.readFileSync(file, 'utf8')),
);

/**
 * The ask, and specifically the UNBOUNDED form. A call site that passes `freshForMs` reads as
 * an ask and behaves as a no-op whenever the rows were answered moments ago - which, right
 * after the round trip the caller just awaited, is always. Matching the bare call is what makes
 * the difference visible: every one of these sites has just CHANGED the data, so how fresh the
 * copy is says nothing about whether it is still right.
 */
const asksForRows = (window: string) => /refreshAutomations\(\s*\)/.test(window);

/**
 * Fixtures for the windowing itself. The helpers below decide what counts as "this site's ask",
 * and nothing was testing them: neutering `enclosingBlockEnd` to `source.length` left the whole
 * suite green WITH a real missing ask injected, which is a guard that has stopped guarding and
 * says nothing. Each fixture is a shape that was, or could be, a live miss.
 */
const SIBLING_BRANCH = `
  async function move(target) {
    if (target === 'draft') {
      await versionService.pinVersion(id, null);
    } else if (target === 'paused') {
      await executionService.cancelWorkflow(runId);
      refreshAutomations();
    }
  }
`;

const BRACE_IN_COMMENT = `
  async function move(target) {
    if (target === 'draft') {
      await versionService.pinVersion(id, null);
      // the response is { success, pinnedVersion
    } else {
      refreshAutomations();
    }
  }
`;

const BRACE_IN_STRING = `
  async function move(target) {
    if (target === 'draft') {
      await versionService.pinVersion(id, null);
      log('closing brace in a string: }');
    } else {
      refreshAutomations();
    }
  }
`;

const TWO_CALLS_ONE_BLOCK = `
  async function both() {
    await versionService.pinVersion(a, null);
    await versionService.pinVersion(b, 2);
    refreshAutomations();
  }
`;

const DECOY_REFRESH = `
  async function one() {
    await versionService.pinVersion(id, null);
    refresh();
    refreshEverythingElse();
  }
`;

const BOUNDED_ASK = `
  async function one() {
    await versionService.pinVersion(id, null);
    refreshAutomations({ freshForMs: 60_000 });
  }
`;

const COMMENTED_OUT_ASK = `
  async function one() {
    await versionService.pinVersion(id, null);
    // refreshAutomations();
  }
`;

const ASK_INSIDE_A_STRING = `
  async function one() {
    await versionService.pinVersion(id, null);
    console.debug('refreshAutomations()');
  }
`;

const BRACE_IN_BLOCK_COMMENT = `
  async function move(target) {
    if (target === 'draft') {
      await versionService.pinVersion(id, null);
      /* the response is { success, pinnedVersion */
    } else {
      refreshAutomations();
    }
  }
`;

const BRACE_IN_TEMPLATE = `
  async function move(target) {
    if (target === 'draft') {
      await versionService.pinVersion(id, null);
      log(\`unclosed brace in a template: {\`);
    } else {
      refreshAutomations();
    }
  }
`;

const ESCAPED_QUOTE_THEN_BRACE = `
  async function move(target) {
    if (target === 'draft') {
      await versionService.pinVersion(id, null);
      log('it\\'s here: }');
      refreshAutomations();
    }
  }
`;

describe('the window a site is judged on', () => {
  const askedIn = (fixture: string) => sitesIn('fixture.ts', fixture).map((site) => asksForRows(site.window));

  it('stops at the end of the block, so a sibling branch ask cannot cover a site with none', () => {
    // The measured miss: with a plain character budget this read the `else if` branch's ask and
    // passed a site that had none.
    expect(askedIn(SIBLING_BRANCH)).toEqual([false]);
  });

  it('is not extended by an unbalanced brace inside a comment', () => {
    // A stray `{` in a comment inflates the brace depth, so the window runs past the block end
    // and borrows the next branch's ask. It fails OPEN, which is the one way a guard must not
    // fail - hence blanking comments before counting.
    expect(askedIn(BRACE_IN_COMMENT)).toEqual([false]);
  });

  it('is not extended by a brace inside a string literal', () => {
    expect(askedIn(BRACE_IN_STRING)).toEqual([false]);
  });

  it('stops at the next call, so two sites in one block cannot share a single ask', () => {
    expect(askedIn(TWO_CALLS_ONE_BLOCK)).toEqual([false, true]);
  });

  it('does not accept a different function whose name merely starts the same way', () => {
    expect(askedIn(DECOY_REFRESH)).toEqual([false]);
  });

  it('does not accept an ask that is commented out', () => {
    // The assertion reads blanked source for the same reason the brace counting does: raw, a
    // `// refreshAutomations();` left behind by a debugging session passes the site.
    expect(askedIn(COMMENTED_OUT_ASK)).toEqual([false]);
  });

  it('does not accept an ask that is only the text of a string', () => {
    expect(askedIn(ASK_INSIDE_A_STRING)).toEqual([false]);
  });

  it('is not extended by a brace inside a BLOCK comment', () => {
    expect(askedIn(BRACE_IN_BLOCK_COMMENT)).toEqual([false]);
  });

  it('is not extended by a brace inside a template literal', () => {
    expect(askedIn(BRACE_IN_TEMPLATE)).toEqual([false]);
  });

  it('is not thrown off by an ESCAPED quote inside a string', () => {
    // Without escape-pair skipping the string reads as closed at the escaped apostrophe, the
    // rest of the line reads as code, and the `}` in it closes the block early - cutting the
    // window short of an ask that IS there. This fixture has one, so a false here means the
    // blanker ended the string in the wrong place.
    expect(askedIn(ESCAPED_QUOTE_THEN_BRACE)).toEqual([true]);
  });

  it('does not accept a BOUNDED ask at a mutation site, which is a no-op wearing the right name', () => {
    // Right after the round trip the caller just awaited, a bounded ask is always inside its
    // own bound. Five sites could be turned into permanent no-ops this way with every test green.
    expect(askedIn(BOUNDED_ASK)).toEqual([false]);
  });
});

describe('every mutation that changes an automation row refreshes the bell rows', () => {
  it('found the call sites, so nothing below can pass over an empty list', () => {
    const files = [...new Set(CALL_SITES.map((site) => site.file))];
    expect(files).toEqual(
      expect.arrayContaining([
        'app/workflows/builder/components/nodes/TriggerNodePinButton.tsx',
        'components/applications/ApplicationActivationButton.tsx',
        'components/workflow/WorkflowVersionHistory.tsx',
        'components/workflow-board/useWorkflowBoard.ts',
        'components/views/AgendaView.tsx',
        'components/chat/TriggerRowActions.tsx',
        'app/[locale]/app/settings/public-access/components/ScheduleTabContent.tsx',
        'app/[locale]/app/settings/public-access/page.tsx',
        'app/workflows/builder/components/inspector/forms/ScheduleTriggerParametersForm.tsx',
        'app/workflows/builder/components/inspector/forms/WebhookTriggerParametersForm.tsx',
      ]),
    );
    // Several files hold more than one: that is the point of counting CALLS, not files.
    expect(CALL_SITES.length).toBeGreaterThanOrEqual(18);
  });

  it('scans the whole frontend, so a call site in a directory nobody listed cannot hide', () => {
    // The roots are derived from the tree, not hand-written. This asserts the derivation really
    // reaches past the two obvious directories, and that `contexts/` - 54 source files that an
    // allow-list would have missed - is inside the walk.
    const walked = everySourceFile().map((file) => path.relative(ROOT, file).split(path.sep).join('/'));
    const roots = new Set(walked.map((file) => file.split('/')[0]));
    expect([...roots]).toEqual(expect.arrayContaining(['app', 'components', 'contexts', 'hooks', 'lib']));
    // And the files sitting at the top of `frontend/`, which a directory-only walk drops.
    expect(walked).toEqual(expect.arrayContaining(['proxy.ts']));
  });

  it('keeps the board pausing and resuming an automation, which no pattern here can see', () => {
    // The board drags a card to "paused" / back to "production" by calling the run primitives
    // directly, so it is an automation mutation wearing an execution API. Asserted by name
    // because widening the pattern to those primitives would drag in every run surface.
    const board = fs.readFileSync(path.join(ROOT, 'components/workflow-board/useWorkflowBoard.ts'), 'utf8');
    for (const primitive of ['cancelWorkflow', 'reactivateWorkflow']) {
      const at = board.indexOf(`.${primitive}(`);
      expect(at, `the board no longer calls ${primitive} - if the pause path moved, move this check`).toBeGreaterThan(-1);
      expect(
        asksForRows(board.slice(at, Math.min(at + REFRESH_LOOKAHEAD, enclosingBlockEnd(board, at)))),
        `useWorkflowBoard: ${primitive} here pauses or resumes an automation, so the bell's rows `
          + `and its imminent-fire ring must be asked for again`,
      ).toBe(true);
    }
  });

  it.each([...new Set(CALL_SITES.map((site) => site.file))])(
    '%s binds the real refresh, not a stub',
    (file) => {
      const source = fs.readFileSync(path.join(ROOT, file), 'utf8');
      expect(
        /const refreshAutomations = useRefreshHomeStatus\(\)/.test(source),
        `${file}: the asks below are only asks if \`refreshAutomations\` IS the hook. Most of `
          + `these files have no suite of their own, so a stub here would be invisible.`,
      ).toBe(true);
    },
  );

  it.each(CALL_SITES.map((site, index) => [`${site.file} .${site.method}() #${index}`, index] as const))(
    '%s asks for the automation rows again',
    (_name, index) => {
      const site = CALL_SITES[index];
      expect(
        asksForRows(site.window),
        `${site.file}: this ${site.method} call changes which workflows the bell lists as armed, `
          + `or when they next fire, and nothing else invalidates that payload. Call the refresh `
          + `from useRefreshHomeStatus after it - bound to a local \`refreshAutomations\`, which is `
          + `the name this guard reads - or the bell and its imminent-fire ring show pre-action `
          + `state until the next 60s poll.`,
      ).toBe(true);
    },
  );
});
