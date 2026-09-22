/**
 * The two panel GEOMETRY gates, and why neither may use the tabbed-layout flag.
 *
 * `shouldUseTabbedLayout` folds in "the PANEL measured narrow". That signal is
 * correct for deciding what goes INSIDE the panel, and wrong for deciding the
 * panel's own shape: one of these gates produced a user-visible no-op because of
 * it, and the other would feed its own condition.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import {
  shouldConstrainPanelToContainer,
  shouldForceCompactPanel,
  shouldRenderMinimizedPill,
  shouldUseTabbedLayout,
} from '../useInspectorLayout';

describe('shouldRenderMinimizedPill', () => {
  it('shows the pill for a desktop user who asked to minimize', () => {
    expect(
      shouldRenderMinimizedPill({ isMinimized: true, isWindowMobile: false, isDocked: false }),
    ).toBe(true);
  });

  it('still shows the pill when the PANEL is narrow, which is the bug this fixes', () => {
    // Regression: the gate used to read the tabbed-layout flag, which is true for
    // an advanced-mode user whose panel measured narrow. Clicking minimize then
    // fell through to the full panel and nothing happened.
    const narrowAdvancedPanel = shouldUseTabbedLayout({
      isWindowMobile: false,
      isNarrowPanel: true,
      isAdvanced: true,
      isFullscreen: false,
    });
    expect(narrowAdvancedPanel, 'the flag that used to gate this branch').toBe(true);

    expect(
      shouldRenderMinimizedPill({ isMinimized: true, isWindowMobile: false, isDocked: false }),
      'minimize must work regardless of how wide the panel happened to measure',
    ).toBe(true);
  });

  it('does not show the pill on a mobile window, where the panel is full-screen', () => {
    expect(
      shouldRenderMinimizedPill({ isMinimized: true, isWindowMobile: true, isDocked: false }),
    ).toBe(false);
  });

  it('does not show the pill when docked: the dock owns the panel size', () => {
    expect(
      shouldRenderMinimizedPill({ isMinimized: true, isWindowMobile: false, isDocked: true }),
    ).toBe(false);
  });

  it('shows nothing when the user has not minimized', () => {
    expect(
      shouldRenderMinimizedPill({ isMinimized: false, isWindowMobile: false, isDocked: false }),
    ).toBe(false);
  });
});

describe('shouldConstrainPanelToContainer', () => {
  it('caps a floating desktop panel to its container', () => {
    expect(
      shouldConstrainPanelToContainer({ isFullscreen: false, isDocked: false, isWindowMobile: false }),
    ).toBe(true);
  });

  it.each([
    ['fullscreen', { isFullscreen: true, isDocked: false, isWindowMobile: false }],
    ['docked', { isFullscreen: false, isDocked: true, isWindowMobile: false }],
    ['a mobile window', { isFullscreen: false, isDocked: false, isWindowMobile: true }],
  ])('applies no cap in %s, where something else owns the size', (_case, input) => {
    expect(shouldConstrainPanelToContainer(input)).toBe(false);
  });

});

/**
 * The predicates above are only half the guarantee: the bug lived at the CALL
 * SITE, which passed the tabbed-layout flag where the window flag belongs. Both
 * predicates would still be correct with a wrong argument, and no unit test of a
 * pure function can see that - so the invariant is asserted against the source,
 * the way `JsonbWritesCallsiteInvariantTest` guards its own call sites.
 */
describe('InspectorPanel gate call sites', () => {
  const source = readFileSync(
    join(__dirname, '..', '..', 'InspectorPanel.tsx'),
    'utf8',
  );

  it('passes the WINDOW flag to both geometry gates, never the tabbed-layout flag', () => {
    // `isMobile` in this component is `shouldUseTabbedLayout(...)`, which folds in
    // "the PANEL measured narrow". Feeding it to either gate is the regression:
    // minimize became a no-op for an advanced-mode user with a narrow panel, and
    // the maxWidth cap would feed its own condition.
    const pillCall = source.match(/shouldRenderMinimizedPill\(\{[^}]*\}\)/)?.[0];
    const constrainCall = source.match(/shouldConstrainPanelToContainer\(\{[^}]*\}\)/)?.[0];

    expect(pillCall, 'the minimized-pill gate must be called from InspectorPanel').toBeTruthy();
    expect(constrainCall, 'the panel-size gate must be called from InspectorPanel').toBeTruthy();

    for (const [name, call] of [
      ['shouldRenderMinimizedPill', pillCall!],
      ['shouldConstrainPanelToContainer', constrainCall!],
    ] as const) {
      expect(call, `${name} must be given isWindowMobile`).toContain('isWindowMobile');
      expect(
        /\bisMobile\b/.test(call),
        `${name} must NOT be given isMobile: that is shouldUseTabbedLayout, which knows about the panel width`,
      ).toBe(false);
    }
  });
});

/**
 * The compact-panel gate, pinned at its CALL SITE for the same reason the two
 * above are: the pure function cannot see what it is given, and this particular
 * bug was entirely in what it was given.
 *
 * The gate means "this node still has to be pointed at something, and the picker
 * that does the pointing owns the panel". Every family whose picker has since
 * been deleted was still listed, and the listing matched on `data.id` prefixes,
 * which a plan round-trip does not preserve. Re-adding any of those terms here -
 * or any id test at all - restores the bug while every unit test of the pure
 * function still passes.
 */
describe('the compact-panel gate call site', () => {
  const source = readFileSync(join(__dirname, '..', '..', 'InspectorPanel.tsx'), 'utf8');
  const callStart = source.indexOf('shouldForceCompactPanel({');
  const call = callStart < 0 ? '' : source.slice(callStart, source.indexOf('})', callStart) + 2);

  it('computes shouldForceSmallMode through the gate, not inline', () => {
    expect(call, 'InspectorPanel must call shouldForceCompactPanel').toBeTruthy();
    expect(source).toContain('const shouldForceSmallMode = shouldForceCompactPanel(');
  });

  it('passes it only the MCP-picker signals', () => {
    for (const flag of ['isApiNode', 'isMcpGenericNode', 'isToolNode']) {
      expect(call, `the gate needs ${flag}`).toContain(flag);
    }
  });

  it.each(['isCoreNode', 'isAiGenericNode', 'isTriggerNode', 'hasNavigation', 'hasTriggerNavigation'])(
    'is not handed %s again: that family has no picker left, and the term only took the panel away',
    (term) => {
      expect(call).not.toContain(term);
    },
  );

  it('keeps the gate free of any id test', () => {
    // `data.id` is the graph node id a plan re-imported, i.e. whatever created the
    // canvas node - a template literal, a plan key, a uuid. It is not an identity.
    expect(call).not.toContain('startsWith');
    expect(call).not.toContain('data.id');
    expect(call).not.toContain('nodeId');
  });

  it('is the only thing deciding the panel is compact', () => {
    const assignments = source.match(/const shouldForceSmallMode = [^;]+;/g) ?? [];
    expect(assignments).toHaveLength(1);
  });
});

describe('shouldForceCompactPanel', () => {
  it('pins an MCP node that has not chosen its tool', () => {
    expect(shouldForceCompactPanel({ isApiNode: true, isMcpGenericNode: false, isToolNode: false })).toBe(true);
    expect(shouldForceCompactPanel({ isApiNode: false, isMcpGenericNode: true, isToolNode: false })).toBe(true);
  });

  it('releases it once it is a tool, which has parameters and columns', () => {
    expect(shouldForceCompactPanel({ isApiNode: true, isMcpGenericNode: true, isToolNode: true })).toBe(false);
  });

  it('pins nothing else', () => {
    expect(shouldForceCompactPanel({ isApiNode: false, isMcpGenericNode: false, isToolNode: false })).toBe(false);
  });
});
