/**
 * Every interactive "i" in the app goes through `components/ui/info-popover`, and opens on CLICK.
 *
 * <p>The app used to have five ways of showing an info icon's explanation: a Radix tooltip on
 * hover, a CSS `group-hover` box, a stock Radix popover copied into ~50 inspector forms, a
 * hand-rolled portal with a z-[9998] backdrop (which opened BEHIND the share modal), and an
 * inline toggle banner. A reader could not tell which gesture an icon wanted, and the hover
 * ones were unreachable on a touch screen. `InfoPopover` replaced all of them.
 *
 * <p>This guard is what keeps it that way: it fails on any info glyph that sits inside an
 * interactive trigger (a button, a role="button" element, or a Tooltip / Popover / HoverCard /
 * DropdownMenu / Collapsible trigger) or next to a `group-hover` box, anywhere outside the
 * component itself and the exact allowances listed below. A decorative "i" (the icon at the
 * start of a banner, a toast, a badge) is not a trigger and is not flagged.
 *
 * <p>What it does NOT catch: an explanation opened from a non-info glyph, and a trigger built
 * more than ~1500 characters away from its icon. It reads source text, not the rendered tree.
 */
import { describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const FRONTEND = path.resolve(fileURLToPath(new URL('.', import.meta.url)), '..');

/** The glyphs that read as "more information". */
// With or without attributes, self-closing or not, and lazily to the FIRST `/>` or closing
// tag so an attribute holding `>` (`className={a > b ? ...}`) does not end the match early.
const INFO_GLYPH = /<(Info|InfoIcon|HelpCircle|CircleHelp)\b[\s\S]*?(?:\/>|<\/\1>)/g;
// Every element that turns what it wraps into something to press or hover. `<button` and the
// design-system `<Button` are both listed: the match is case-sensitive.
const TRIGGERS = [
  'button',
  'Button',
  'TooltipTrigger',
  'PopoverTrigger',
  'HoverCardTrigger',
  'DropdownMenuTrigger',
  'CollapsibleTrigger',
] as const;
// A `<div role="button" onClick>` (or span) is a hand-rolled button: the tag is unknown in
// advance, so it is found by the attribute and closed by its own tag name.
const ROLE_BUTTON = /<([A-Za-z]+)\b[^>]*role=["']button["']/g;

/**
 * Files allowed to put an info glyph inside a trigger, and why. Each entry is re-checked
 * below: an allowance that no longer applies is an error, so the list cannot rot.
 */
const ALLOW_LIST: Record<string, { reason: string; hits: number; mustImportInfoPopover: boolean }> = {
  'components/ai/ModelInfo.tsx': {
    reason: 'builds its own trigger (keep-open tag for the composer menu) and passes it to InfoPopover',
    hits: 1,
    mustImportInfoPopover: true,
  },
  'components/resource-info/ResourceInfoPopover.tsx': {
    reason: 'builds its own trigger (card / breadcrumb variants) and passes it to InfoPopover',
    hits: 1,
    mustImportInfoPopover: true,
  },
  'app/workflows/builder/components/BuilderCanvas.tsx': {
    reason: 'the validation badge brings its own trigger (count in a native title) and passes it to InfoPopover',
    hits: 1,
    mustImportInfoPopover: true,
  },
  'components/marketplace/PublicationInfoPanel.tsx': {
    reason: 'the "i" toggles the publication side panel (a surface with actions), not an explanation',
    hits: 1,
    mustImportInfoPopover: false,
  },
};

/** Line numbers of info glyphs that sit inside an interactive trigger or a hover box. */
export function findInteractiveInfoIcons(source: string): number[] {
  const lines: number[] = [];
  for (const match of source.matchAll(INFO_GLYPH)) {
    const at = match.index ?? 0;
    const before = source.slice(Math.max(0, at - 1500), at);
    let insideTrigger = false;
    for (const tag of TRIGGERS) {
      // `<Button` must not be read as `<ButtonGroup`, nor `<button` as `<buttons`.
      const opens = [...before.matchAll(new RegExp(`<${tag}(?=[\\s>/])`, 'g'))];
      const open = opens.length ? opens[opens.length - 1].index ?? -1 : -1;
      if (open !== -1 && open > before.lastIndexOf(`</${tag}>`)) {
        insideTrigger = true;
      }
    }
    const roleButtons = [...before.matchAll(ROLE_BUTTON)];
    const roleButton = roleButtons[roleButtons.length - 1];
    if (roleButton && (roleButton.index ?? -1) > before.lastIndexOf(`</${roleButton[1]}>`)) {
      insideTrigger = true;
    }
    const hoverBox = /group-hover:/.test(source.slice(at, at + 400));
    if (insideTrigger || hoverBox) {
      lines.push(source.slice(0, at).split('\n').length);
    }
  }
  return lines;
}

function sourceFiles(dir: string, out: string[] = []): string[] {
  for (const entry of fs.readdirSync(path.join(FRONTEND, dir), { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name === '.next' || entry.name === '__tests__') continue;
    const rel = dir ? `${dir}/${entry.name}` : entry.name;
    if (entry.isDirectory()) sourceFiles(rel, out);
    else if (entry.name.endsWith('.tsx') && !entry.name.includes('.test.')) out.push(rel);
  }
  return out;
}

const read = (file: string) => fs.readFileSync(path.join(FRONTEND, file), 'utf8');
// Every directory that holds .tsx source, not only the two where the "i" lives today.
const ROOTS = ['app', 'components', 'hooks', 'contexts', 'lib', 'i18n'].filter((dir) =>
  fs.existsSync(path.join(FRONTEND, dir)),
);

describe('the scanner itself', () => {
  it('flags a hover tooltip "i"', () => {
    const src = `<Tooltip>\n  <TooltipTrigger asChild>\n    <Info className="h-3.5 w-3.5 cursor-help" />\n  </TooltipTrigger>\n</Tooltip>`;
    expect(findInteractiveInfoIcons(src)).toEqual([3]);
  });

  it('flags a CSS group-hover "i"', () => {
    const src = `<div className="relative group">\n  <Info className="w-3.5 h-3.5" />\n  <div className="hidden group-hover:block">text</div>\n</div>`;
    expect(findInteractiveInfoIcons(src)).toEqual([2]);
  });

  it('flags a hand-rolled click "i" (a popover copy or a portal toggle)', () => {
    const popover = `<Popover>\n  <PopoverTrigger asChild>\n    <button type="button">\n      <Info className="h-3 w-3" />\n    </button>\n  </PopoverTrigger>\n</Popover>`;
    const portal = `<button ref={ref} onClick={() => setOpen(!open)}>\n  <HelpCircle className="h-3 w-3" />\n</button>`;
    expect(findInteractiveInfoIcons(popover)).toEqual([4]);
    expect(findInteractiveInfoIcons(portal)).toEqual([2]);
  });

  it('flags an "i" inside the design-system <Button>, a bare <Info/>, and one with a ">" in an attribute', () => {
    const button = `<Button variant="ghost" onClick={toggle}>
  <Info className="h-4 w-4" />
</Button>`;
    const bare = `<button onClick={toggle}>
  <Info/>
</button>`;
    const arrow = `<button onClick={toggle}>
  <Info className={n > 1 ? 'h-4 w-4' : 'h-3 w-3'} />
</button>`;
    expect(findInteractiveInfoIcons(button)).toEqual([2]);
    expect(findInteractiveInfoIcons(bare)).toEqual([2]);
    expect(findInteractiveInfoIcons(arrow)).toEqual([2]);
  });

  it('flags an "i" inside a hover card, a dropdown or collapsible trigger, or a role="button" div', () => {
    const hoverCard = `<HoverCard>\n  <HoverCardTrigger asChild>\n    <Info className="h-4 w-4" />\n  </HoverCardTrigger>\n</HoverCard>`;
    const dropdown = `<DropdownMenuTrigger>\n  <HelpCircle className="h-4 w-4" />\n</DropdownMenuTrigger>`;
    const collapsible = `<CollapsibleTrigger>\n  <Info className="h-4 w-4" />\n</CollapsibleTrigger>`;
    const roleButton = `<div role="button" tabIndex={0} onClick={toggle}>\n  <Info className="h-4 w-4" />\n</div>`;
    expect(findInteractiveInfoIcons(hoverCard)).toEqual([3]);
    expect(findInteractiveInfoIcons(dropdown)).toEqual([2]);
    expect(findInteractiveInfoIcons(collapsible)).toEqual([2]);
    expect(findInteractiveInfoIcons(roleButton)).toEqual([2]);
  });

  it('flags a glyph written with a closing tag, and leaves a closed role="button" div alone', () => {
    const openClose = `<button onClick={toggle}>\n  <Info className="h-4 w-4"></Info>\n</button>`;
    const closedRole = `<div role="button" onClick={go}>Go</div>\n<p>\n  <Info className="h-4 w-4" />\n</p>`;
    expect(findInteractiveInfoIcons(openClose)).toEqual([2]);
    expect(findInteractiveInfoIcons(closedRole)).toEqual([]);
  });

  it('does not read <ButtonGroup> as a <Button> trigger', () => {
    const src = `<ButtonGroup>
  <span>x</span>
</ButtonGroup>
<div>
  <Info className="h-4 w-4" />
</div>`;
    expect(findInteractiveInfoIcons(src)).toEqual([]);
  });

  it('leaves a decorative "i" alone (banner, toast, a closed button before it)', () => {
    const src = `<button type="button">Save</button>\n<div className="flex gap-2 rounded-lg bg-blue-50 p-3">\n  <Info className="h-4 w-4 text-blue-500" />\n  <span>Heads up.</span>\n</div>`;
    expect(findInteractiveInfoIcons(src)).toEqual([]);
  });
});

describe('every interactive "i" in the app is the shared click InfoPopover', () => {
  const files = ROOTS.flatMap((root) => sourceFiles(root));

  it('scans a real tree (a scan over nothing would pass vacuously)', () => {
    expect(files.length).toBeGreaterThan(500);
    expect(files).toContain('components/ui/info-popover.tsx');
  });

  it('finds no hover "i", no hand-rolled click "i", outside the allow-list', () => {
    const offenders = files
      .filter((file) => !(file in ALLOW_LIST))
      .flatMap((file) => findInteractiveInfoIcons(read(file)).map((line) => `${file}:${line}`));
    expect(
      offenders,
      'Use <InfoPopover label=...>explanation</InfoPopover> from @/components/ui/info-popover: '
        + 'it opens on click, sits above every modal, and keeps clicks off the host.',
    ).toEqual([]);
  });

  it('keeps every allow-list entry justified', () => {
    for (const [file, { reason, hits, mustImportInfoPopover }] of Object.entries(ALLOW_LIST)) {
      expect(files, `${file} is allow-listed (${reason}) but no longer exists`).toContain(file);
      const source = read(file);
      // An EXACT count, not "at least one": an allowance covers one known trigger, never the
      // whole file, so a second hover "i" added to an allow-listed file still fails here.
      expect(
        findInteractiveInfoIcons(source).length,
        `${file} is allow-listed for ${hits} interactive "i" (${reason}); a new one must use InfoPopover, a removed one must leave the list`,
      ).toBe(hits);
      if (mustImportInfoPopover) {
        expect(source, `${file} is allow-listed because it hands its trigger to InfoPopover`).toMatch(
          /from ['"]@\/components\/ui\/info-popover['"]/,
        );
      }
    }
  });
});
