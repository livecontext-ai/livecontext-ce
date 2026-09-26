/**
 * Wiring guard for the measured re-layout.
 *
 * The correction is worth nothing if nobody mounts it, and it is DANGEROUS if the
 * announcement is moved to, or added on, a path that did not just lay the graph out:
 * the whole reason it listens for an event rather than watching the nodes is that it
 * must never move positions an author placed by hand. Asserted at the source, since
 * rendering the full BuilderCanvas would need the entire builder context.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { join, basename } from 'node:path';

const read = (p: string) => readFileSync(join(process.cwd(), 'app/workflows/builder', p), 'utf8');

describe('MeasuredLayoutSync wiring', () => {
  it('is mounted by BuilderCanvas', () => {
    const canvas = read('components/BuilderCanvas.tsx');
    expect(canvas).toContain('<MeasuredLayoutSync');
    expect(canvas).toContain("import { MeasuredLayoutSync } from './MeasuredLayoutSync'");
  });

  it('regression: writes through the GUARDED change channel, and is told its workflow and its lock', () => {
    const canvas = read('components/BuilderCanvas.tsx');
    const mount = canvas.slice(canvas.indexOf('<MeasuredLayoutSync'));
    const tag = mount.slice(0, mount.indexOf('/>'));
    expect(tag).toContain('instance={instance}');
    expect(tag).toContain('direction={layoutDirection}');
    // The raw onNodesChange would be a second, ungated write channel next to the one
    // every other canvas write goes through.
    expect(tag).toContain('onNodesChange={guardedOnNodesChange}');
    // Both are what stop a sibling canvas (side panel, application tab, run view) from
    // answering an announcement that is not about it.
    expect(tag).toContain('workflowId={workflowId}');
    expect(tag).toContain('isLocked={isLocked}');
  });

  it('regression: the plan-sync announces only a graph laid out from scratch, and names the workflow', () => {
    // The replay recomputes EVERY node. Announced after a sync that kept the stored
    // positions (the common case: the agent added one node to a workflow the user laid
    // out), it moved the nodes the user had placed and saved.
    const listeners = read('hooks/useWorkflowEventListeners.ts');
    expect(listeners).not.toContain('applyDagreLayout(');
    expect(listeners).toMatch(
      /if \(importResult\.laidOutFromScratch\) \{\s*dispatchLayoutApplied\(workflowId\);\s*\}/,
    );
    // An id-less announcement is permissive by the scoping rule, so it would reach every
    // mounted canvas and move a sibling workflow's nodes.
    expect(listeners).toMatch(/dispatchLayoutApplied\(\s*workflowId\s*\)/);
  });

  it('regression: the plan-sync announces AFTER the nodes land, never before', () => {
    // Ordering, not presence. Announced before setNodes the listener reads the canvas as
    // it still IS: the node the agent just added is not in it, so the very node this
    // exists to place is the one left where its estimate put it. Comments are stripped first, so prose
    // about awaiting or returning cannot pass or fail this.
    const listeners = read('hooks/useWorkflowEventListeners.ts')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/^[ 	]*\/\/.*$/gm, '');

    const sites = [...listeners.matchAll(/dispatchLayoutApplied\(/g)].map((m) => m.index!);
    expect(sites.length).toBeGreaterThan(0);
    for (const at of sites) {
      const paintedAt = listeners.lastIndexOf('setNodes(', at);
      expect(paintedAt).toBeGreaterThan(-1);
      const between = listeners.slice(paintedAt, at);
      // Anything that defers the announcement puts it in a later tick, where the two
      // frames it schedules no longer bracket the paint they were counted against.
      expect(between).not.toMatch(
        /await|return|setTimeout\(|queueMicrotask\(|requestAnimationFrame\(|Promise\.resolve\(|\.then\(/,
      );
    }
  });

  it('regression: the LOAD path stays silent, whatever the plan carried', () => {
    // A load is laid out from estimates too, so announcing there is tempting and wrong:
    // the correction cannot land before the undo baseline (seeded on the commit that
    // flips workflowLoaded, useHistory) or the dirty baseline (locked two node commits
    // later, useDirtyState), so a workflow the user merely OPENED would come up with
    // Save armed, an undo entry and a navigate-away warning. The plan-sync runs long
    // after both, and its own node write already marks the canvas edited.
    const loader = read('hooks/useWorkflowLoader.ts');
    expect(loader).not.toMatch(/dispatchLayoutApplied|workflowLayoutApplied/);
  });

  it('regression: exactly ONE place announces a layout, and it is the plan-sync', () => {
    const hits: string[] = [];
    const walk = (dir: string) => {
      for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const full = join(dir, entry.name);
        if (entry.isDirectory()) {
          if (entry.name !== 'node_modules' && entry.name !== '__tests__') walk(full);
          continue;
        }
        if (!/\.tsx?$/.test(entry.name)) continue;
        if (entry.name === 'layoutAppliedEvent.ts') continue; // the helper itself
        const body = readFileSync(full, 'utf8');
        // Both the helper AND a hand-written raw dispatch, so spelling it out evades nothing.
        if (/dispatchLayoutApplied\(/.test(body)) hits.push(full);
        else if (/new CustomEvent\(\s*['"`]workflowLayoutApplied/.test(body)) hits.push(full);
      }
    };
    for (const root of ['app', 'components', 'lib', 'contexts', 'hooks']) {
      try {
        walk(join(process.cwd(), root));
      } catch {
        /* root absent in this tree */
      }
    }

    // A second site is how a canvas the user only opened, or only dragged in, would
    // start being re-centred and marked dirty. WorkflowPlanGenerator lays a pasted plan
    // out from estimates too and still must NOT announce: it MERGES into the existing
    // canvas, so the correction would run over the author's own nodes in the same
    // component - the one thing this whole mechanism exists to avoid.
    expect(hits.map((h) => basename(h)).sort()).toEqual(['useWorkflowEventListeners.ts']);
  });
});
