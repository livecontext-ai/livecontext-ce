// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, cleanup, waitFor } from '@testing-library/react';

// ---------------------------------------------------------------------------
// Mocks - keep the form renderable without dragging in real i18n/router/query.
// ---------------------------------------------------------------------------
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('next/navigation', () => ({
  usePathname: () => '/en/app/workflow/1bffde93-accd-48a3-a11a-ee053cdd8e3d',
}));
vi.mock('next/link', () => ({ default: ({ children }: any) => <a>{children}</a> }));
vi.mock('@tanstack/react-query', () => ({
  useQuery: () => ({ data: null, isLoading: false }),
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
}));
vi.mock('@/lib/api', () => ({ apiClient: { get: vi.fn(), post: vi.fn() } }));
vi.mock('@/lib/utils/locale', () => ({ getClientLocale: () => 'en' }));
vi.mock('../../../hooks/ui/usePopoverPosition', () => ({
  usePopoverPosition: () => ({ buttonRef: { current: null }, popoverPosition: { top: 0, left: 0 } }),
}));
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: any) => <div>{children}</div>,
  SelectContent: ({ children }: any) => <div>{children}</div>,
  SelectGroup: ({ children }: any) => <div>{children}</div>,
  SelectItem: ({ children }: any) => <div>{children}</div>,
  SelectLabel: ({ children }: any) => <div>{children}</div>,
  SelectTrigger: ({ children }: any) => <div>{children}</div>,
  SelectValue: () => null,
}));
vi.mock('@/components/ui/input', () => ({ Input: (p: any) => <input {...p} /> }));
vi.mock('@/components/ui/button', () => ({ Button: ({ children }: any) => <button>{children}</button> }));
vi.mock('../OptionalSection', () => ({ OptionalSection: ({ children }: any) => <div>{children}</div> }));

// scheduleSettingsService is the crux: assert whether `create` is called.
const getAll = vi.fn();
const getConfig = vi.fn().mockResolvedValue(null);
const create = vi.fn();
const update = vi.fn();
const validateCron = vi.fn().mockResolvedValue({ valid: true });
vi.mock('@/lib/api/orchestrator', () => ({
  scheduleSettingsService: {
    getAll: (...a: any[]) => getAll(...a),
    getConfig: (...a: any[]) => getConfig(...a),
    create: (...a: any[]) => create(...a),
    update: (...a: any[]) => update(...a),
    validateCron: (...a: any[]) => validateCron(...a),
  },
}));

import { ScheduleTriggerParametersForm } from '../ScheduleTriggerParametersForm';

const WORKFLOW_ID = '1bffde93-accd-48a3-a11a-ee053cdd8e3d';
const TRIGGER_ID = 'trigger:poll_inbox';

const attachedRow = {
  id: 'attached-id',
  name: 'poll inbox',
  workflowId: WORKFLOW_ID,
  workflowName: 'Pro Mail Filter',
  triggerId: TRIGGER_ID,
  cronExpression: '*/30 * * * *',
  timezone: 'Europe/Paris',
  enabled: true,
  executionCount: 13,
  createdAt: '2026-07-14T00:00:00Z',
  isActive: true,
  sourceNodeId: null,
};

function renderForm(nodeId: string, onUpdate = vi.fn()) {
  const data: any = { scheduleTriggerData: { cronExpression: '*/30 * * * *', timezone: 'Europe/Paris', maxExecutions: null } };
  render(
    <ScheduleTriggerParametersForm
      node={{ id: nodeId, data } as any}
      data={data}
      onUpdate={onUpdate}
      triggerId={TRIGGER_ID}
    />,
  );
  return onUpdate;
}

describe('ScheduleTriggerParametersForm - adopt existing schedule, do not mint a duplicate', () => {
  beforeEach(() => { vi.clearAllMocks(); getConfig.mockResolvedValue(null); validateCron.mockResolvedValue({ valid: true }); });
  afterEach(() => cleanup());

  it('adopts the existing attached schedule instead of calling create - the reported bug', async () => {
    getAll.mockResolvedValue([attachedRow]);
    const onUpdate = renderForm('node-adopt-1');

    await waitFor(() => {
      expect(onUpdate).toHaveBeenCalledWith(
        expect.objectContaining({ standaloneScheduleId: 'attached-id' }),
      );
    });
    // The core regression assertion: no SECOND (standalone) schedule was minted.
    expect(create).not.toHaveBeenCalled();
  });

  it('auto-creates a schedule only when none exists for the node/trigger', async () => {
    getAll.mockResolvedValue([]);
    create.mockResolvedValue({ ...attachedRow, id: 'fresh-id', sourceNodeId: 'schedule-node-create-1' });
    const onUpdate = renderForm('node-create-1');

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    await waitFor(() => {
      expect(onUpdate).toHaveBeenCalledWith(
        expect.objectContaining({ standaloneScheduleId: 'fresh-id' }),
      );
    });
  });
});
