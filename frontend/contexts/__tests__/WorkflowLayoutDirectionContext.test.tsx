/**
 * @vitest-environment jsdom
 *
 * WorkflowLayoutDirectionContext holds the (org-aware) reading direction of the
 * workflow builder canvas. These tests pin: the deliberate 'horizontal' default (so
 * existing left-to-right workflows are never silently re-read), localStorage
 * persistence, PER-ORG isolation, rejection of junk stored values, survival when
 * storage throws, and the safe hook's outside-provider fallback.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import {
  WorkflowLayoutDirectionProvider,
  useWorkflowLayoutDirection,
  useWorkflowLayoutDirectionSafe,
  isWorkflowLayoutDirection,
  DEFAULT_WORKFLOW_LAYOUT_DIRECTION,
} from '../WorkflowLayoutDirectionContext';

// The org drives the storage key, so it is the one dependency worth faking.
let currentOrgId: string | null = null;
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrg: () => ({ currentOrgId }),
}));

function Probe() {
  const { direction, setDirection } = useWorkflowLayoutDirection();
  return (
    <div>
      <span data-testid="direction">{direction}</span>
      <button onClick={() => setDirection('vertical')}>vertical</button>
      <button onClick={() => setDirection('horizontal')}>horizontal</button>
    </div>
  );
}

function SafeProbe() {
  const { direction, setDirection } = useWorkflowLayoutDirectionSafe();
  return (
    <div>
      <span data-testid="safe-direction">{direction}</span>
      <button onClick={() => setDirection('vertical')}>set</button>
    </div>
  );
}

const KEY = (org: string) => `lc.workflow.layoutDirection:${org}`;

describe('WorkflowLayoutDirectionContext', () => {
  beforeEach(() => {
    window.localStorage.clear();
    currentOrgId = null;
  });

  afterEach(cleanup);

  it('defaults to horizontal so existing left-to-right workflows are not re-read', () => {
    // Deliberate: every stored workflow was authored horizontally, so vertical must
    // be opt-in rather than silently flipping every canvas the user knows.
    expect(DEFAULT_WORKFLOW_LAYOUT_DIRECTION).toBe('horizontal');
    render(
      <WorkflowLayoutDirectionProvider>
        <Probe />
      </WorkflowLayoutDirectionProvider>,
    );
    expect(screen.getByTestId('direction')).toHaveTextContent('horizontal');
  });

  it('persists the choice under the personal-workspace key', () => {
    render(
      <WorkflowLayoutDirectionProvider>
        <Probe />
      </WorkflowLayoutDirectionProvider>,
    );
    act(() => screen.getByText('vertical').click());
    expect(screen.getByTestId('direction')).toHaveTextContent('vertical');
    expect(window.localStorage.getItem(KEY('personal'))).toBe('vertical');
  });

  it('restores a stored choice on mount', () => {
    window.localStorage.setItem(KEY('personal'), 'vertical');
    render(
      <WorkflowLayoutDirectionProvider>
        <Probe />
      </WorkflowLayoutDirectionProvider>,
    );
    expect(screen.getByTestId('direction')).toHaveTextContent('vertical');
  });

  it('scopes the choice per workspace', () => {
    // An org reading top-down must not flip the user's personal canvases.
    window.localStorage.setItem(KEY('personal'), 'horizontal');
    window.localStorage.setItem(KEY('org-1'), 'vertical');

    currentOrgId = 'org-1';
    const { unmount } = render(
      <WorkflowLayoutDirectionProvider>
        <Probe />
      </WorkflowLayoutDirectionProvider>,
    );
    expect(screen.getByTestId('direction')).toHaveTextContent('vertical');
    unmount();

    currentOrgId = null;
    render(
      <WorkflowLayoutDirectionProvider>
        <Probe />
      </WorkflowLayoutDirectionProvider>,
    );
    expect(screen.getByTestId('direction')).toHaveTextContent('horizontal');
  });

  it('falls back to the default when the stored value is not a direction', () => {
    window.localStorage.setItem(KEY('personal'), 'diagonal');
    render(
      <WorkflowLayoutDirectionProvider>
        <Probe />
      </WorkflowLayoutDirectionProvider>,
    );
    expect(screen.getByTestId('direction')).toHaveTextContent('horizontal');
  });

  it('keeps the in-memory choice when storage throws (private mode)', () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError');
    });
    render(
      <WorkflowLayoutDirectionProvider>
        <Probe />
      </WorkflowLayoutDirectionProvider>,
    );
    // The canvas must still flip even if the preference cannot be remembered.
    act(() => screen.getByText('vertical').click());
    expect(screen.getByTestId('direction')).toHaveTextContent('vertical');
    setItem.mockRestore();
  });

  it('throws outside the provider, so canvas code cannot silently lose the direction', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(() => render(<Probe />)).toThrow(/WorkflowLayoutDirectionProvider/);
    spy.mockRestore();
  });

  it('degrades to the default outside the provider via the Safe hook', () => {
    // Nodes render on provider-less surfaces (marketplace preview, snapshots): a
    // missing direction must never crash the page.
    render(<SafeProbe />);
    expect(screen.getByTestId('safe-direction')).toHaveTextContent('horizontal');
    // And its setter is an inert no-op rather than an exception.
    expect(() => act(() => screen.getByText('set').click())).not.toThrow();
    expect(screen.getByTestId('safe-direction')).toHaveTextContent('horizontal');
  });

  describe('isWorkflowLayoutDirection', () => {
    it('accepts only the two real directions', () => {
      expect(isWorkflowLayoutDirection('horizontal')).toBe(true);
      expect(isWorkflowLayoutDirection('vertical')).toBe(true);
      expect(isWorkflowLayoutDirection('TB')).toBe(false);
      expect(isWorkflowLayoutDirection(null)).toBe(false);
      expect(isWorkflowLayoutDirection(undefined)).toBe(false);
    });
  });
});
