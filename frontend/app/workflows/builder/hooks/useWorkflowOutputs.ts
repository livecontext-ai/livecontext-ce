import { useQuery } from '@tanstack/react-query';
import { orchestratorApi } from '@/lib/api';

export interface WorkflowOutput {
  field: string;
  label: string;
  type: string;
  required?: boolean;
}

export interface WorkflowInput {
  field: string;
  label: string;
  type: string;
  required?: boolean;
}

/**
 * Hook to fetch workflow inputs and outputs
 */
export function useWorkflowInputsOutputs(workflowId: string | null) {
  return useQuery({
    queryKey: ['workflow-inputs-outputs', workflowId],
    queryFn: async () => {
      if (!workflowId) return null;

      // Fetch workflow details to get the plan
      const workflow = await orchestratorApi.getWorkflow(workflowId);

      if (!workflow?.plan) {
        return { inputs: [], outputs: [] };
      }

      const plan = workflow.plan;

      // Extract inputs from trigger nodes
      const inputs: WorkflowInput[] = [];
      if (plan.triggers && Array.isArray(plan.triggers)) {
        plan.triggers.forEach((trigger: any) => {
          // Manual trigger inputs
          if (trigger.type === 'manual' && trigger.inputs && Array.isArray(trigger.inputs)) {
            trigger.inputs.forEach((input: any) => {
              inputs.push({
                field: input.name || input.field,
                label: input.label || input.name || input.field,
                type: input.type || 'text',
                required: input.required !== false,
              });
            });
          }

          // Chat trigger (has message input)
          if (trigger.type === 'chat') {
            inputs.push({
              field: 'message',
              label: 'Message',
              type: 'text',
              required: true,
            });
          }

          // Webhook trigger (has payload)
          if (trigger.type === 'webhook') {
            inputs.push({
              field: 'payload',
              label: 'Webhook Payload',
              type: 'object',
              required: false,
            });
          }

          // Tables trigger - use datasource columns as inputs
          if ((trigger.type === 'datasource' || trigger.type === 'table') && trigger.dataSourceData) {
            const columnExpressions = trigger.dataSourceData.columnExpressions || {};
            const columnLabels = trigger.dataSourceData.columnLabels || {};

            Object.keys(columnExpressions).forEach((field) => {
              inputs.push({
                field,
                label: columnLabels[field] || field,
                type: 'text',
                required: false,
              });
            });
          }
        });
      }

      // If no specific inputs found, create generic workflow inputs
      if (inputs.length === 0) {
        inputs.push({
          field: 'trigger_data',
          label: 'Trigger Data',
          type: 'object',
          required: false,
        });
      }

      // Extract outputs from last nodes in the workflow
      const outputs: WorkflowOutput[] = [];

      // Find terminal nodes (nodes with no outgoing edges)
      const allNodeIds = new Set<string>();
      const nodesWithOutgoingEdges = new Set<string>();

      // Collect all node IDs
      if (plan.triggers) {
        plan.triggers.forEach((t: any) => allNodeIds.add(t.id));
      }
      if (plan.mcps) {
        plan.mcps.forEach((s: any) => allNodeIds.add(s.id));
      }
      if (plan.agents) {
        plan.agents.forEach((a: any) => allNodeIds.add(a.id));
      }

      // Find nodes with outgoing edges
      if (plan.edges && Array.isArray(plan.edges)) {
        plan.edges.forEach((edge: any) => {
          if (edge.from) nodesWithOutgoingEdges.add(edge.from);
        });
      }

      // Terminal nodes are those without outgoing edges
      const terminalNodeIds = Array.from(allNodeIds).filter(id => !nodesWithOutgoingEdges.has(id));

      // For now, return a generic result structure
      // In a real scenario, you'd analyze the last nodes to determine their output structure
      outputs.push({
        field: 'result',
        label: 'Workflow Result',
        type: 'object',
        required: false,
      });

      outputs.push({
        field: 'status',
        label: 'Execution Status',
        type: 'text',
        required: true,
      });

      return { inputs, outputs };
    },
    enabled: !!workflowId,
    staleTime: 5 * 60 * 1000, // 5 minutes
    gcTime: 10 * 60 * 1000, // 10 minutes
  });
}
