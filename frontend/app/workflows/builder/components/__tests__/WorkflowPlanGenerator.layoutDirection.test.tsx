// @vitest-environment jsdom
/**
 * The plan JSON dialog (export and paste-import) and the canvas reading direction.
 *
 * Export: the JSON a user copies out must carry the canvas direction, or it re-opens
 * elsewhere as a legacy horizontal plan. Import: a pasted plan is merged INTO this canvas,
 * so it can only be drawn in this canvas's direction; positions it carries that were
 * computed the other way must be re-laid out, not kept under the wrong handles.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@tanstack/react-query', () => ({ useQueryClient: () => ({}) }));
vi.mock('@/contexts/WorkflowLayoutDirectionContext', () => ({
  useWorkflowLayoutDirectionSafe: () => ({ direction: 'vertical' }),
}));
const generateWorkflowPlan = vi.fn((..._args: unknown[]) => ({ name: 'Plan' }));
vi.mock('../../utils/workflowPlanGenerator', () => ({
  generateWorkflowPlan: (...args: unknown[]) => generateWorkflowPlan(...args),
}));
const importPlan = vi.fn();
vi.mock('../../services/workflowPlanImporter/WorkflowPlanImporter', () => ({
  WorkflowPlanImporter: { importPlan: (...args: unknown[]) => importPlan(...args) },
}));
vi.mock('../../services/workflowPlanImporter/ToolDataService', () => ({
  ToolDataService: { setProgressCallback: vi.fn() },
}));

import { WorkflowPlanGenerator } from '../WorkflowPlanGenerator';

function renderGenerator() {
  return render(
    <WorkflowPlanGenerator nodes={[]} edges={[]} onNodesChange={vi.fn()} onEdgesChange={vi.fn()} />,
  );
}

beforeEach(() => {
  generateWorkflowPlan.mockClear();
  importPlan.mockReset();
  importPlan.mockResolvedValue({
    success: true, nodes: [], edges: [], validation: { isValid: true, errors: [], warnings: [] }, layoutDirection: 'vertical',
  });
});

afterEach(cleanup);

describe('WorkflowPlanGenerator - reading direction', () => {
  it('stamps the canvas direction into the exported plan', () => {
    renderGenerator();

    fireEvent.click(screen.getByTitle('generatePlan'));

    expect(generateWorkflowPlan).toHaveBeenCalledWith([], [], 'vertical');
  });

  it('draws a pasted plan in this canvas direction, re-laying positions computed the other way', async () => {
    renderGenerator();
    fireEvent.click(screen.getByTitle('importPlan'));
    fireEvent.change(screen.getByPlaceholderText('pastePlaceholder'), {
      target: { value: '{"triggers":[],"mcps":[],"edges":[]}' },
    });

    const importButtons = screen.getAllByText('importPlan');
    await act(async () => {
      fireEvent.click(importButtons[importButtons.length - 1]);
    });

    expect(importPlan).toHaveBeenCalledTimes(1);
    expect(importPlan.mock.calls[0][2]).toEqual({ fallbackDirection: 'vertical', forcedDirection: 'vertical' });
  });
});
