// @vitest-environment jsdom
/**
 * RBAC hardening (2026-07-02): the edit-mode trigger launcher (Auto /
 * Step-by-step) starts a run, which auto-saves the plan - the backend 403s
 * org VIEWERs, so the launcher renders nothing for them. MEMBER keeps it and
 * the Auto entry still dispatches `workflowViewStart` with the node id.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const orgMutationGate = vi.hoisted(() => ({ canMutate: true }));

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => orgMutationGate.canMutate,
}));

import { TriggerEditLaunchButton } from '../TriggerEditLaunchButton';

beforeEach(() => {
  orgMutationGate.canMutate = true;
});
afterEach(cleanup);

describe('TriggerEditLaunchButton - VIEWER gate', () => {
  it('MEMBER: renders the launcher; Auto dispatches workflowViewStart with the node id', () => {
    const dispatched: CustomEvent[] = [];
    const listener = (e: Event) => dispatched.push(e as CustomEvent);
    window.addEventListener('workflowViewStart', listener);

    render(<TriggerEditLaunchButton nodeId="n1" variant="play" borderColor="#000" />);
    const trigger = screen.getByTitle('runWorkflow');
    fireEvent.click(trigger);
    fireEvent.click(screen.getByText('runAuto'));

    expect(dispatched).toHaveLength(1);
    expect(dispatched[0].detail).toMatchObject({ startFromNode: 'n1' });
    window.removeEventListener('workflowViewStart', listener);
  });

  it('VIEWER: renders nothing (run would 403 server-side)', () => {
    orgMutationGate.canMutate = false;
    const { container } = render(
      <TriggerEditLaunchButton nodeId="n1" variant="play" borderColor="#000" />,
    );
    expect(screen.queryByTitle('runWorkflow')).not.toBeInTheDocument();
    expect(container).toBeEmptyDOMElement();
  });
});
