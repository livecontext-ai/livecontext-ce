import { beforeEach, describe, expect, it, vi } from 'vitest';
import { agentService } from '../agent.service';
import { executionService } from '../execution.service';
import {
  canControlProductionResource,
  productionResourceKind,
  setProductionResourcePaused,
} from '../resource-control';

vi.mock('../agent.service', () => ({
  agentService: { updateAgent: vi.fn() },
}));

vi.mock('../execution.service', () => ({
  executionService: { cancelWorkflow: vi.fn(), reactivateWorkflow: vi.fn() },
}));

describe('production resource control', () => {
  beforeEach(() => vi.clearAllMocks());

  it('names every production resource explicitly for action labels', () => {
    expect(productionResourceKind('WORKFLOW')).toBe('workflow');
    expect(productionResourceKind('AGENT')).toBe('agent');
    expect(productionResourceKind('APPLICATION')).toBe('interface');
  });

  it('pauses and resumes an agent through its active flag', async () => {
    const agent = {
      resourceType: 'AGENT' as const,
      resourceId: 'agent-1',
      name: 'Agenda agent',
    };

    await setProductionResourcePaused(agent, true);
    await setProductionResourcePaused(agent, false);

    expect(agentService.updateAgent).toHaveBeenNthCalledWith(1, 'agent-1', {
      name: 'Agenda agent',
      isActive: false,
    });
    expect(agentService.updateAgent).toHaveBeenNthCalledWith(2, 'agent-1', {
      name: 'Agenda agent',
      isActive: true,
    });
  });

  it('refuses an agent control without the required update name', async () => {
    await expect(setProductionResourcePaused({
      resourceType: 'AGENT',
      resourceId: 'agent-1',
    }, true)).rejects.toThrow('Agent name is unavailable');
    expect(agentService.updateAgent).not.toHaveBeenCalled();
  });

  it.each(['WORKFLOW', 'APPLICATION'] as const)(
    'pauses and resumes a %s through its production run',
    async (resourceType) => {
      const resource = { resourceType, resourceId: 'resource-1', productionRunIdPublic: 'run-public-1' };

      await setProductionResourcePaused(resource, true);
      await setProductionResourcePaused(resource, false);

      expect(executionService.cancelWorkflow).toHaveBeenCalledWith('run-public-1');
      expect(executionService.reactivateWorkflow).toHaveBeenCalledWith('run-public-1');
    },
  );

  it('accepts the Agenda run id field for workflow controls', async () => {
    const resource = {
      resourceType: 'WORKFLOW' as const,
      resourceId: 'workflow-1',
      runIdPublic: 'run-from-agenda',
    };

    expect(canControlProductionResource(resource)).toBe(true);
    await setProductionResourcePaused(resource, true);

    expect(executionService.cancelWorkflow).toHaveBeenCalledWith('run-from-agenda');
  });

  it('only exposes workflow controls when a production run exists', () => {
    expect(canControlProductionResource({ resourceType: 'AGENT', resourceId: 'agent-1' })).toBe(false);
    expect(canControlProductionResource({
      resourceType: 'AGENT',
      resourceId: 'agent-1',
      name: 'Agenda agent',
    })).toBe(true);
    expect(canControlProductionResource({ resourceType: 'WORKFLOW', resourceId: 'workflow-1' })).toBe(false);
    expect(canControlProductionResource({
      resourceType: 'WORKFLOW',
      resourceId: 'workflow-1',
      productionRunIdPublic: 'run-public-1',
    })).toBe(true);
    expect(canControlProductionResource({
      resourceType: 'WORKFLOW',
      resourceId: 'workflow-1',
      runIdPublic: 'run-agenda-1',
    })).toBe(true);
  });
});
