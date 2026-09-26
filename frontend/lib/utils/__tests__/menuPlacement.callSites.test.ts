import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Every menu that anchors its own edge to a trigger rect goes through the one clamp.
 *
 * The helper being correct is not the fix; the fix is that these call sites use
 * it. Each of them could be reverted to its old arithmetic with every other test
 * in the suite still green, which is what the first assertion is for.
 *
 * The second is the anti-duplication half. Before this pass there were four
 * private clamps (`usePopoverPosition`'s lower bound, `ModelSelector`'s inline
 * min/max, `CurlExamplePopover`'s if-chain, `CanvasContextMenu`'s x-axis), each
 * with its own idea of the gutter and of which width to measure. The third
 * assertion is what catches the FIFTH: measuring a trigger rect and the window
 * width in the same file is the signature of placing something by hand, and any
 * file doing that which is not on the list below has to justify itself here.
 */
const FRONTEND = path.resolve(fileURLToPath(new URL('.', import.meta.url)), '..', '..', '..');

/** file -> the clamp it must use. */
const CALL_SITES: Record<string, 'clampMenuLeft' | 'clampMenuCenter'> = {
  // Left- or right-aligned menus.
  'components/chat/GroupedToolCard.tsx': 'clampMenuLeft',
  'components/chat/PreviewActionMenu.tsx': 'clampMenuLeft',
  'components/chat/ModelSelectorDropdown.tsx': 'clampMenuLeft',
  'components/chat/AttachmentHandler.tsx': 'clampMenuLeft',
  'components/app/AppSidebar.tsx': 'clampMenuLeft',
  'components/ui/expression-editor.tsx': 'clampMenuLeft',
  'components/webhook/CurlExamplePopover.tsx': 'clampMenuLeft',
  'app/workflows/builder/components/CanvasContextMenu.tsx': 'clampMenuLeft',
  // Centred on its trigger (translateX(-50%)), so the midpoint is what moves.
  'app/workflows/builder/hooks/usePortalMenu.ts': 'clampMenuCenter',
};

/**
 * Files allowed to read the window width beside a trigger rect, and why.
 *
 * Only the last one places a floating box; the rest ask the window a question
 * the clamp does not answer.
 */
const HAND_MEASURED_ALLOW_LIST: Record<string, string> = {
  'app/workflows/builder/components/BuilderCanvas.tsx': 'a <1024px breakpoint check, not a placement',
  'app/workflows/builder/hooks/useHoverPopover.ts': 'a desktop breakpoint check, not a placement',
  'app/workflows/builder/components/TriggerPanel.tsx': 'sizes a panel, and already reads clientWidth first',
  'components/data-table/JsonPreviewPopover.tsx':
    'FLIPS the card left/right of a cell before falling back to a gutter, which is a placement algorithm the clamp does not have; its boxes carry their own viewport cap',
  'app/workflows/builder/components/inspector/useInspectorLayout.ts':
    'reads the window for a 1024px breakpoint and to size the fullscreen panel, and measures the rect of the PANEL ITSELF (not a trigger) to tell a narrow dock from a narrow window. It places no floating box, so there is no edge for the clamp to hold',
};

const read = (file: string) => fs.readFileSync(path.join(FRONTEND, file), 'utf8');

function sourceFiles(dir: string, out: string[] = []): string[] {
  for (const entry of fs.readdirSync(path.join(FRONTEND, dir), { withFileTypes: true })) {
    const rel = `${dir}/${entry.name}`;
    if (entry.isDirectory()) {
      if (['node_modules', '.next', '__tests__', 'e2e'].includes(entry.name)) continue;
      sourceFiles(rel, out);
    } else if (/\.tsx?$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name)) {
      out.push(rel);
    }
  }
  return out;
}

describe.each(Object.entries(CALL_SITES))('%s', (file, clamp) => {
  it(`positions its menu through ${clamp}`, () => {
    const source = read(file);
    expect(source).toContain(`import { ${clamp} } from '@/lib/utils/menuPlacement'`);
    // Imported AND called: an unused import would satisfy the line above.
    expect(source).toContain(`${clamp}(`);
  });
});

describe('nobody rolls their own viewport clamp', () => {
  it('leaves no window-width arithmetic in the files that use the shared clamp', () => {
    const offenders = Object.keys(CALL_SITES).filter((file) => read(file).includes('window.innerWidth'));
    expect(offenders).toEqual([]);
  });

  it('finds no NEW file measuring a trigger rect against the viewport width', () => {
    // Both spellings: `window.innerWidth` is how the old private clamps were
    // written, and `documentElement.clientWidth` is the one this codebase
    // actually prefers - so banning only the first would wave the next hand-
    // rolled clamp straight through.
    const suspects = ['components', 'app', 'hooks', 'lib', 'contexts', 'utils']
      .filter((dir) => fs.existsSync(path.join(FRONTEND, dir)))
      .flatMap((dir) => sourceFiles(dir))
      .filter((file) => {
        const source = read(file);
        const readsViewport =
          source.includes('window.innerWidth') || source.includes('documentElement.clientWidth');
        return readsViewport && source.includes('getBoundingClientRect');
      })
      .filter((file) => !(file in HAND_MEASURED_ALLOW_LIST));

    expect(suspects).toEqual([]);
  });
});
