// @vitest-environment jsdom
/**
 * Opening a webhook trigger that has no standalone webhook yet auto-creates one. That is
 * a workspace write the backend refuses to a read-only VIEWER, so the form used to fire a
 * POST that could only 403 and then show "auto-create failed". A VIEWER now never
 * attempts it; a MEMBER still gets the webhook created.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';

const gate = vi.hoisted(() => ({ canMutate: true }));
const webhooks = vi.hoisted(() => ({ getAll: vi.fn(), getById: vi.fn(), create: vi.fn(), update: vi.fn() }));

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => gate.canMutate }));
vi.mock('@/lib/api/orchestrator', () => ({ webhookSettingsService: webhooks }));
vi.mock('@/hooks/useHomeStatus', () => ({ useRefreshHomeStatus: () => () => {} }));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => ({ isRunMode: false }) }));
vi.mock('@/components/webhook/CurlExamplePopover', () => ({ CurlExamplePopover: () => null }));
vi.mock('@/components/ui/info-popover', () => ({ InfoPopover: () => null }));
vi.mock('next/link', () => ({ default: ({ children }: { children: React.ReactNode }) => <a>{children}</a> }));

import { WebhookTriggerParametersForm } from '../WebhookTriggerParametersForm';

function renderForm(nodeId: string) {
  const data = { label: 'Webhook', type: 'trigger' } as never;
  render(<WebhookTriggerParametersForm node={{ id: nodeId, data, position: { x: 0, y: 0 } } as never}
    data={data} onUpdate={vi.fn()} />);
}

beforeEach(() => {
  gate.canMutate = true;
  webhooks.getAll.mockReset().mockResolvedValue([]);
  webhooks.create.mockReset().mockResolvedValue({ id: 'wh-1', webhookUrl: 'https://x/webhook/t', token: 't' });
});
afterEach(cleanup);

describe('WebhookTriggerParametersForm - auto-create VIEWER gate', () => {
  it('VIEWER: the list loads but no webhook is created', async () => {
    gate.canMutate = false;
    renderForm('node-viewer');

    await waitFor(() => expect(webhooks.getAll).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 20));
    expect(webhooks.create).not.toHaveBeenCalled();
  });

  it('MEMBER: the missing webhook is auto-created', async () => {
    renderForm('node-member');

    await waitFor(() => expect(webhooks.create).toHaveBeenCalledTimes(1));
  });
});
