/**
 * "The epoch the user picked" is a module-global value keyed by run id, shared by
 * every surface showing that run - the canvas, the side-panel Run tab and the
 * application tab. Recording it makes the other surfaces adopt that epoch when
 * they mount empty, permanently for that run.
 *
 * So it must be set by a CLICK and nothing else. The application tab calls its
 * epoch handler from three places, two of them automatic (its own mount, the
 * jump when a new epoch closes); marking from that shared handler let a panel
 * merely being rendered count as a choice, and froze the run's epoch everywhere.
 */
import { afterEach, describe, expect, it } from 'vitest';
import {
  getPickedEpoch,
  markEpochPickedByUser,
  resetEpochSelectionState,
} from '@/components/workflow/run-panel/useDefaultEpochSelection';

afterEach(() => resetEpochSelectionState());

describe('epoch pick attribution', () => {
  it('a run nobody has touched has no pick, so it stays on the default view', () => {
    expect(getPickedEpoch('run-1')).toBeUndefined();
  });

  it('a pick records WHICH epoch, and "All epochs" is one of them', () => {
    markEpochPickedByUser('run-1', 3);
    expect(getPickedEpoch('run-1')).toBe(3);

    markEpochPickedByUser('run-1', null);
    expect(getPickedEpoch('run-1'), 'null is a choice, not an absence').toBeNull();
  });

  it('the pick reaches every surface of the run, which is why only a click may set it', () => {
    // The point of the global: the canvas and the panel must not fight. The cost:
    // one surface marking automatically would silence the others too.
    markEpochPickedByUser('run-1', 2);
    expect(getPickedEpoch('run-1')).toBe(2);
    // ...and never leaks to a different run.
    expect(getPickedEpoch('run-2')).toBeUndefined();
  });
});

describe('who is allowed to record a pick', () => {
  it('only a click handler, or the explicit back-to-all-epochs helper', async () => {
    // A structural assertion, deliberately: the failure mode is a CALL SITE.
    // An effect that marks (a mount, a mode toggle, a run change) silences the
    // other surfaces and evicts real picks from the remembered set, which is
    // exactly what this rule exists to prevent.
    const fs = await import('fs');
    const path = await import('path');
    // The WHOLE app, not a hand-kept list: a new file marking a pick is exactly
    // the regression this guards, and a list cannot see one. Resolved from this
    // file, so the walk does not depend on where the runner was started.
    const root = path.resolve(__dirname, '../../../..');
    const files: string[] = [];
    const walk = (dir: string) => {
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) {
          if (entry.name === 'node_modules' || entry.name === '__tests__' || entry.name === '.next') continue;
          walk(full);
        } else if (/\.tsx?$/.test(entry.name)) {
          files.push(path.relative(root, full).replace(/\\/g, '/'));
        }
      }
    };
    for (const dir of ['app', 'components', 'contexts', 'hooks', 'lib']) walk(path.join(root, dir));
    // The helper module declares the function, so only a CALL counts there.
    const isCaller = (f: string) => {
      const source = fs.readFileSync(path.join(root, f), 'utf8');
      const calls = source.match(/markEpochPickedByUser\(/g) ?? [];
      const declarations = source.match(/function markEpochPickedByUser\(/g) ?? [];
      return calls.length > declarations.length;
    };
    expect(files.filter(isCaller).sort(), 'the only places allowed to record a pick').toEqual([
      // The epoch dropdown of the application tab.
      'components/chat/ApplicationTabContent.tsx',
      // Clicking one PAST occurrence on the calendar: the user pointed at a single
      // fire, so the run opens on that fire rather than on all of them. Reached only
      // through the occurrence menu - never from an effect. (It used to be reachable
      // from `handleSelect` too, which navigated straight there; every chip opens its
      // menu now, because leaving the page is the one click here with no undo.)
      'components/views/AgendaView.tsx',
      // "Show on the canvas" in the Analysis tab's epoch comparison: a button the user
      // clicks to open ONE epoch they are looking at. Never from an effect.
      'components/workflow/run-panel/RunAnalysisPanelContent.tsx',
      // The epoch selector of the Run panel.
      'components/workflow/run-panel/RunPanelContent.tsx',
      // `selectAllEpochs`, called from the three "fire from here" controls.
      'components/workflow/run-panel/useDefaultEpochSelection.ts',
    ]);
  });

  it('nobody clears the epoch behind the helper\'s back', async () => {
    // The mirror of the rule above, and the one that catches the real defect:
    // a bare `setViewingEpoch(null)` is indistinguishable from a surface that
    // just mounted, so the remembered pick is restored and the user is snapped
    // back onto the epoch they just left. Every clear is either a deliberate
    // return to all epochs (through `selectAllEpochs`) or one of the two
    // listed resets, which are NOT user choices.
    const fs = await import('fs');
    const path = await import('path');
    const root = path.resolve(__dirname, '../../../..');
    const files: string[] = [];
    const walk = (dir: string) => {
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) {
          if (entry.name === 'node_modules' || entry.name === '__tests__' || entry.name === '.next') continue;
          walk(full);
        } else if (/\.tsx?$/.test(entry.name)) {
          files.push(path.relative(root, full).replace(/\\/g, '/'));
        }
      }
    };
    for (const dir of ['app', 'components', 'contexts', 'hooks', 'lib']) walk(path.join(root, dir));

    const clears = files.filter(f =>
      /(setViewingEpoch|onSelectEpoch|handleViewEpoch|handleEpochPickedByUser)\(\s*null\s*\)/.test(
        fs.readFileSync(path.join(root, f), 'utf8'),
      ),
    );
    expect(clears.sort(), 'every other clear must go through selectAllEpochs').toEqual([
      // The application tab's own "All epochs" row, routed to the handler that
      // records the choice.
      'components/chat/ApplicationTabContent.tsx',
      // The Run panel's "All epochs" row, likewise recorded by its owner.
      'components/workflow/run-panel/EpochSelector.tsx',
      // The edit/run mode reset: not a user choice, deliberately not recorded.
      'components/workflow/WorkflowModeToggle.tsx',
      // The helper itself.
      'components/workflow/run-panel/useDefaultEpochSelection.ts',
    ].sort());
  });

  it('the fire-from-a-focused-epoch controls clear through the helper, keyed on the canvas run', async () => {
    // They return the user to all epochs BEFORE firing, and that is a choice:
    // recorded, or the surfaces would restore the epoch just left. It has to be
    // recorded under the run the CANVAS is bound to, which is the id the
    // restoring hook reads back - not always the provider's own.
    const fs = await import('fs');
    const path = await import('path');
    const root = path.resolve(__dirname, '../../../..');
    for (const file of [
      'app/workflows/builder/components/CanvasRunTriggerButton.tsx',
      'app/workflows/builder/components/nodes/FlowNode.tsx',
      'components/workflow/StepRowActions.tsx',
    ]) {
      const source = fs.readFileSync(path.join(root, file), 'utf8');
      expect(source, `${file} must clear through the helper`).toMatch(/selectAllEpochs\(\s*boundRunId\(/);
    }
  });
});

describe('the application tab marks picks only on a click', () => {
  it('routes the dropdown through the marking handler and nothing else through it', async () => {
    // A structural assertion, deliberately: the failure mode is a CALL SITE, not
    // a value. `handleViewEpoch` is invoked by the new-epoch auto-jump effect and
    // by the dropdown; only the dropdown may mark.
    const path = await import('path');
    const source = await import('fs').then(fs =>
      fs.readFileSync(path.resolve(__dirname, '../../../../components/chat/ApplicationTabContent.tsx'), 'utf8'));

    const markCalls = source.match(/markEpochPickedByUser\(/g) ?? [];
    expect(markCalls, 'exactly one call site - the click handler').toHaveLength(1);

    // It lives in the click-only wrapper, not in the shared handler the effects use.
    const wrapper = source.slice(source.indexOf('const handleEpochPickedByUser'));
    expect(wrapper.slice(0, 300)).toContain('markEpochPickedByUser(runId, epoch)');

    const sharedHandler = source.slice(
      source.indexOf('const handleViewEpoch'),
      source.indexOf('const handleEpochPickedByUser'),
    );
    expect(sharedHandler, 'the shared handler must stay attribution-free').not.toContain('markEpochPickedByUser');
  });

  it('keeps its one auto-jump conditional on the user already sitting on an epoch', async () => {
    // The tab must never leave the cumulative view on its own. (That it selects
    // nothing when it opens is pinned by rendering it, in
    // components/chat/__tests__/ApplicationTabContent.runContext.test.tsx.)
    const path = await import('path');
    const source = await import('fs').then(fs =>
      fs.readFileSync(path.resolve(__dirname, '../../../../components/chat/ApplicationTabContent.tsx'), 'utf8'));

    const jumpBlock = source.slice(source.indexOf('const newEpochAppeared'));
    expect(jumpBlock.slice(0, 400)).toContain('if (viewingEpoch != null)');
  });
});
