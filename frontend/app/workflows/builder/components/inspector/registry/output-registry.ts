/**
 * Output Registry - Central configuration for all inspector outputs.
 *
 * Each node type registers how to extract and render its output data.
 * Most nodes use LazyStructureTree by default - only special cases
 * define custom renderContent functions.
 *
 * Categories: triggers, agents, cores, tools, tables, notes, interface
 */

import React from 'react';
import { Node } from 'reactflow';
import { BuilderNodeData } from '../../../types';
import { OutputDefinition, InspectorNodeType, ExecutionData } from '../core/types';

/**
 * Default output definition - used for most nodes.
 * Extracts data from stepResults and renders with LazyStructureTree.
 */
export const defaultOutputDefinition: OutputDefinition = {
  extractData: (node: Node<BuilderNodeData>, executionData: ExecutionData) => {
    const nodeId = node.id;
    const result = executionData.stepResults?.[nodeId];
    return result?.output ?? null;
  },
  emptyMessage: 'No output data available. Run the workflow to see results.',
};

/**
 * Output registry - maps node types to output definitions.
 */
export const outputRegistry: Record<InspectorNodeType, OutputDefinition> = {
  // ============================================================================
  // TRIGGERS
  // ============================================================================
  'manual-trigger': {
    ...defaultOutputDefinition,
    title: 'Trigger Data',
    emptyMessage: 'Manual trigger activated. Input data shown here.',
  },
  'chat-trigger': {
    ...defaultOutputDefinition,
    title: 'Chat Input',
    emptyMessage: 'Chat message will appear here when received.',
  },
  'webhook-trigger': {
    ...defaultOutputDefinition,
    title: 'Webhook Payload',
    emptyMessage: 'Webhook payload will appear here when received.',
  },
  'schedule-trigger': {
    ...defaultOutputDefinition,
    title: 'Schedule Data',
    emptyMessage: 'Schedule trigger data will appear here.',
  },
  'form-trigger': {
    ...defaultOutputDefinition,
    title: 'Form Data',
    emptyMessage: 'Form submission data will appear here.',
  },
  'tables-trigger': {
    ...defaultOutputDefinition,
    title: 'Table Data',
    emptyMessage: 'Table row data will appear here.',
  },
  'workflows-trigger': {
    ...defaultOutputDefinition,
    title: 'Workflow Input',
    emptyMessage: 'Workflow input data will appear here.',
  },
  'error-trigger': {
    ...defaultOutputDefinition,
    title: 'Parent Failure Payload',
    emptyMessage: 'Parent workflow failure data will appear here (parentRunId, errorMessage, status, …).',
  },

  // ============================================================================
  // AGENTS
  // ============================================================================
  'agent': {
    ...defaultOutputDefinition,
    title: 'Agent Response',
    emptyMessage: 'Agent response will appear here after execution.',
  },
  'summarize': {
    ...defaultOutputDefinition,
    title: 'Summary',
    emptyMessage: 'Summary will appear here after execution.',
  },
  'guardrail': {
    ...defaultOutputDefinition,
    title: 'Guardrail Result',
    emptyMessage: 'Guardrail check result will appear here.',
  },
  'classify': {
    ...defaultOutputDefinition,
    title: 'Classification',
    emptyMessage: 'Classification result will appear here.',
  },
  'browser_agent': {
    ...defaultOutputDefinition,
    title: 'Browser Agent Run',
    emptyMessage: 'Browser session output will appear here once the run completes.',
  },

  // ============================================================================
  // CORES (control flow)
  // ============================================================================
  'decision': {
    extractData: (node, executionData) => {
      const result = executionData.stepResults?.[node.id];
      const decisionData = executionData.decisionData?.[node.id];
      return {
        output: result?.output,
        selectedBranch: decisionData?.selectedBranch ?? result?.output?.selectedBranch,
        evaluations: decisionData?.evaluations ?? result?.output?.evaluations,
        skippedBranches: decisionData?.skippedBranches ?? result?.output?.skippedBranches,
      };
    },
    title: 'Decision Result',
    emptyMessage: 'Decision evaluation will appear here.',
  },

  'switch': {
    extractData: (node, executionData) => {
      const result = executionData.stepResults?.[node.id];
      return {
        output: result?.output,
        selectedCase: result?.output?.selectedCase,
        evaluations: result?.output?.evaluations,
      };
    },
    title: 'Switch Result',
    emptyMessage: 'Switch evaluation will appear here.',
  },

  'loop': {
    extractData: (node, executionData) => {
      const result = executionData.stepResults?.[node.id];
      const loopData = executionData.loopData?.[node.id];
      return {
        output: result?.output,
        currentIteration: loopData?.currentIteration ?? result?.output?.currentIteration,
        totalIterations: loopData?.totalIterations ?? result?.output?.totalIterations,
        processedItems: loopData?.processedItems,
      };
    },
    title: 'Loop Progress',
    emptyMessage: 'Loop iteration data will appear here.',
  },

  'split': {
    extractData: (node, executionData) => {
      const result = executionData.stepResults?.[node.id];
      return {
        output: result?.output,
        processedCount: result?.output?.processedCount,
        items: result?.output?.items,
      };
    },
    title: 'Split Results',
    emptyMessage: 'Split iteration results will appear here.',
  },

  'transform': {
    ...defaultOutputDefinition,
    title: 'Transformed Data',
    emptyMessage: 'Transformed data will appear here.',
  },

  'merge': {
    extractData: (node, executionData) => {
      const result = executionData.stepResults?.[node.id];
      return {
        output: result?.output,
        mergedFrom: result?.output?.mergedFrom,
        mergeCount: result?.output?.mergeCount,
      };
    },
    title: 'Merged Data',
    emptyMessage: 'Merged data from branches will appear here.',
  },

  'wait': {
    ...defaultOutputDefinition,
    title: 'Wait Result',
    emptyMessage: 'Wait node completed.',
  },

  'fork': {
    ...defaultOutputDefinition,
    title: 'Fork Result',
    emptyMessage: 'Fork branches started.',
  },

  'download_file': {
    ...defaultOutputDefinition,
    title: 'Download Result',
    emptyMessage: 'File download result will appear here.',
  },

  'aggregate': {
    ...defaultOutputDefinition,
    title: 'Aggregated Data',
    emptyMessage: 'Aggregated data will appear here.',
  },

  'http_request': {
    ...defaultOutputDefinition,
    title: 'HTTP Response',
    emptyMessage: 'HTTP response will appear here after execution.',
  },
  'data_input': {
    ...defaultOutputDefinition,
    title: 'Data Input',
    emptyMessage: 'Input data will be available after execution.',
  },
  'response': {
    ...defaultOutputDefinition,
    title: 'Response',
    emptyMessage: 'Response data will appear here after execution.',
  },
  'exit': {
    ...defaultOutputDefinition,
    title: 'Exit',
    emptyMessage: 'Exit node terminates the workflow.',
  },

  // ============================================================================
  // TOOLS
  // ============================================================================
  'tool': {
    ...defaultOutputDefinition,
    title: 'Tool Output',
    emptyMessage: 'Tool output will appear here after execution.',
  },

  // ============================================================================
  // TABLES (CRUD)
  // ============================================================================
  'create-row': {
    ...defaultOutputDefinition,
    title: 'Created Row',
    emptyMessage: 'Created row data will appear here.',
  },
  'read-row': {
    ...defaultOutputDefinition,
    title: 'Row Data',
    emptyMessage: 'Row data will appear here.',
  },
  'update-row': {
    ...defaultOutputDefinition,
    title: 'Updated Row',
    emptyMessage: 'Updated row data will appear here.',
  },
  'delete-row': {
    ...defaultOutputDefinition,
    title: 'Delete Result',
    emptyMessage: 'Delete result will appear here.',
  },
  'find-row': {
    extractData: (node, executionData) => {
      const result = executionData.stepResults?.[node.id];
      return {
        output: result?.output,
        processedCount: result?.output?.processedCount ?? result?.output?.item_count,
        items: result?.output?.items ?? result?.output?.rows,
      };
    },
    title: 'Found Rows',
    emptyMessage: 'Found rows will appear here after execution.',
  },
  'list-rows': {
    ...defaultOutputDefinition,
    title: 'Rows',
    emptyMessage: 'Listed rows will appear here.',
  },

  // ============================================================================
  // NOTES
  // ============================================================================
  'note': {
    extractData: () => null, // Notes don't have output
    title: 'Note',
    emptyMessage: 'Notes are for documentation only.',
  },

  // ============================================================================
  // INTERFACE
  // ============================================================================
  'interface': {
    ...defaultOutputDefinition,
    title: 'Interface Output',
    emptyMessage: 'Interface output will appear here.',
  },

  // ============================================================================
  // WHILE (LOOP)
  // ============================================================================
  'while-group': {
    extractData: (node, executionData) => {
      const result = executionData.stepResults?.[node.id];
      const loopData = executionData.loopData?.[node.id];
      return {
        output: result?.output,
        currentIteration: loopData?.currentIteration ?? result?.output?.currentIteration ?? result?.output?.iteration,
        totalIterations: loopData?.totalIterations ?? result?.output?.maxIterations,
        processedItems: loopData?.processedItems,
        terminated: result?.output?.terminated,
        reason: result?.output?.reason,
      };
    },
    title: 'Loop Progress',
    emptyMessage: 'Loop iteration data will appear here.',
  },

  // ============================================================================
  // TRANSFORM NODES
  // ============================================================================
  'filter': {
    ...defaultOutputDefinition,
    title: 'Filter Result',
    emptyMessage: 'Filter result will appear here after execution.',
  },
  'sort': {
    ...defaultOutputDefinition,
    title: 'Sorted Data',
    emptyMessage: 'Sorted data will appear here after execution.',
  },
  'limit': {
    ...defaultOutputDefinition,
    title: 'Limited Data',
    emptyMessage: 'Limited data will appear here after execution.',
  },
  'remove_duplicates': {
    ...defaultOutputDefinition,
    title: 'Deduplicated Data',
    emptyMessage: 'Deduplicated data will appear here after execution.',
  },
  'summarize_data': {
    ...defaultOutputDefinition,
    title: 'Data Summary',
    emptyMessage: 'Data summary will appear here after execution.',
  },
  'compare_datasets': {
    ...defaultOutputDefinition,
    title: 'Comparison Result',
    emptyMessage: 'Dataset comparison result will appear here after execution.',
  },

  // ============================================================================
  // UTILITY NODES
  // ============================================================================
  'date_time': {
    ...defaultOutputDefinition,
    title: 'Date/Time Result',
    emptyMessage: 'Date/time result will appear here after execution.',
  },
  'crypto_jwt': {
    ...defaultOutputDefinition,
    title: 'Crypto/JWT Result',
    emptyMessage: 'Crypto/JWT result will appear here after execution.',
  },
  'xml': {
    ...defaultOutputDefinition,
    title: 'XML Result',
    emptyMessage: 'XML processing result will appear here after execution.',
  },
  'compression': {
    ...defaultOutputDefinition,
    title: 'Compression Result',
    emptyMessage: 'Compression result will appear here after execution.',
  },
  'rss': {
    ...defaultOutputDefinition,
    title: 'RSS Feed',
    emptyMessage: 'RSS feed data will appear here after execution.',
  },
  'convert_to_file': {
    ...defaultOutputDefinition,
    title: 'File Conversion Result',
    emptyMessage: 'File conversion result will appear here after execution.',
  },
  'extract_from_file': {
    ...defaultOutputDefinition,
    title: 'Extracted Data',
    emptyMessage: 'Extracted data will appear here after execution.',
  },
  'set': {
    ...defaultOutputDefinition,
    title: 'Set Result',
    emptyMessage: 'Set node result will appear here after execution.',
  },
  'html_extract': {
    ...defaultOutputDefinition,
    title: 'HTML Extract Result',
    emptyMessage: 'HTML extraction result will appear here after execution.',
  },
  'task': {
    ...defaultOutputDefinition,
    title: 'Task Result',
    emptyMessage: 'Task operation result will appear here after execution.',
  },
  'code': {
    ...defaultOutputDefinition,
    title: 'Code Result',
    emptyMessage: 'Code execution result will appear here after execution.',
  },
  'send_email': {
    ...defaultOutputDefinition,
    title: 'Email Result',
    emptyMessage: 'Email send result will appear here after execution.',
  },
  'email_inbox': {
    ...defaultOutputDefinition,
    title: 'Inbox Result',
    emptyMessage: 'Mailbox messages or action result will appear here after execution.',
  },
  'sub_workflow': {
    ...defaultOutputDefinition,
    title: 'Sub-Workflow Result',
    emptyMessage: 'Sub-workflow result will appear here after execution.',
  },
  'respond_to_webhook': {
    ...defaultOutputDefinition,
    title: 'Webhook Response',
    emptyMessage: 'Webhook response will appear here after execution.',
  },
  'stop_on_error': {
    ...defaultOutputDefinition,
    title: 'Stop on Error',
    emptyMessage: 'This node will fail the workflow when executed.',
  },
  'ssh': {
    ...defaultOutputDefinition,
    title: 'SSH Output',
    emptyMessage: 'SSH command output will appear here after execution.',
  },
  'sftp': {
    ...defaultOutputDefinition,
    title: 'SFTP Result',
    emptyMessage: 'SFTP operation result will appear here after execution.',
  },
  'database': {
    ...defaultOutputDefinition,
    title: 'Database Result',
    emptyMessage: 'Database query result will appear here after execution.',
  },

  // ============================================================================
  // UNKNOWN
  // ============================================================================
  'unknown': {
    ...defaultOutputDefinition,
    title: 'Output',
    emptyMessage: 'Unknown node type.',
  },
};

/**
 * Get output definition for a node type.
 */
export function getOutputDefinition(nodeType: InspectorNodeType): OutputDefinition {
  return outputRegistry[nodeType];
}
