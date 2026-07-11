'use client';

import React from 'react';
import { Workflow, Zap, Database, Globe, FileText } from 'lucide-react';
import { Button } from '@/components/ui/button';

export interface WorkflowTemplate {
  id: string;
  name: string;
  description: string;
  icon: React.ReactNode;
  workflow: {
    type: '__WORKFLOW__';
    nodes: any[];
    edges: any[];
  };
}

const workflowTemplates: WorkflowTemplate[] = [
  {
    id: 'simple-pipeline',
    name: 'Simple Pipeline',
    description: 'A basic linear workflow',
    icon: <Workflow className="w-5 h-5" />,
    workflow: {
      type: '__WORKFLOW__',
      nodes: [
        {
          id: '1',
          type: 'automationNode',
          position: { x: 0, y: 0 },
          data: {
            step: {
              id: '1',
              name: 'Data Source',
              description: 'strategy: single',
              type: 'trigger',
              status: 'pending',
            },
            meta: {
              strategy: 'single',
              tool: 'datasource',
            },
            derivedStatus: 'pending',
            layoutDirection: 'horizontal',
          },
        },
        {
          id: 'processor',
          type: 'automationNode',
          position: { x: 380, y: 0 },
          data: {
            step: {
              id: 'processor',
              name: 'Data Processor',
              description: 'processor',
              type: 'action',
              status: 'pending',
            },
            meta: {
              strategy: 'single',
              tool: 'processor',
            },
            derivedStatus: 'pending',
            layoutDirection: 'horizontal',
          },
        },
        {
          id: 'output',
          type: 'automationNode',
          position: { x: 760, y: 0 },
          data: {
            step: {
              id: 'output',
              name: 'Output',
              description: 'output',
              type: 'action',
              status: 'pending',
            },
            meta: {
              strategy: 'single',
              tool: 'output',
            },
            derivedStatus: 'pending',
            layoutDirection: 'horizontal',
          },
        },
      ],
      edges: [
        {
          id: 'edge-1-processor-std-0',
          source: '1',
          target: 'processor',
          type: 'stableStatus',
          label: 'PENDING',
          style: { stroke: '#d1d5db', strokeWidth: 2 },
        },
        {
          id: 'edge-processor-output-std-1',
          source: 'processor',
          target: 'output',
          type: 'stableStatus',
          label: 'PENDING',
          style: { stroke: '#d1d5db', strokeWidth: 2 },
        },
      ],
    },
  },
  {
    id: 'loop-workflow',
    name: 'Loop Workflow',
    description: 'Workflow with a while loop',
    icon: <Zap className="w-5 h-5" />,
    workflow: {
      type: '__WORKFLOW__',
      nodes: [
        {
          id: '1',
          type: 'automationNode',
          position: { x: 0, y: 0 },
          data: {
            step: {
              id: '1',
              name: 'Data Source 1',
              description: 'strategy: single',
              type: 'trigger',
              status: 'pending',
            },
            meta: {
              strategy: 'single',
              tool: 'datasource',
            },
            derivedStatus: 'pending',
            layoutDirection: 'horizontal',
          },
        },
        {
          id: 'data_processor',
          type: 'automationNode',
          position: { x: 380, y: 0 },
          data: {
            step: {
              id: 'data_processor',
              name: 'data_processor',
              description: 'processor',
              type: 'action',
              status: 'pending',
            },
            meta: {
              strategy: 'single',
              tool: 'processor',
            },
            derivedStatus: 'pending',
            layoutDirection: 'horizontal',
          },
        },
        {
          id: 'counter',
          type: 'automationNode',
          position: { x: 760, y: 0 },
          data: {
            step: {
              id: 'counter',
              name: 'counter',
              description: 'counter',
              type: 'action',
              status: 'pending',
            },
            meta: {
              strategy: 'single',
              tool: 'counter',
            },
            derivedStatus: 'pending',
            layoutDirection: 'horizontal',
          },
        },
        {
          id: 'core:while_step_counter',
          type: 'whileGroup',
          position: { x: 1140, y: 0 },
          data: {
            step: {
              id: 'core:while_step_counter',
              name: 'while',
              status: 'pending',
            },
            loopLabel: 'while',
            layoutDirection: 'horizontal',
            autoSize: { w: 268, h: 344 },
            derivedStatus: 'pending',
          },
        },
        {
          id: 'core:while_step_counter::whileStep#1',
          type: 'automationNode',
          position: { x: 1164, y: 40 },
          data: {
            step: {
              id: 'core:while_step_counter::whileStep#1',
              name: 'whileStep',
              description: 'while-step',
              type: 'action',
              status: 'pending',
            },
            meta: {
              strategy: 'single',
              tool: 'while-step',
            },
            derivedStatus: 'pending',
            layoutDirection: 'horizontal',
            isInnerWhileNode: true,
          },
        },
        {
          id: 'core:while_step_counter::condition_checker#2',
          type: 'automationNode',
          position: { x: 1164, y: 210 },
          data: {
            step: {
              id: 'core:while_step_counter::condition_checker#2',
              name: 'condition_checker',
              description: 'condition-checker',
              type: 'action',
              status: 'pending',
            },
            meta: {
              strategy: 'single',
              tool: 'condition-checker',
            },
            derivedStatus: 'pending',
            layoutDirection: 'horizontal',
            isInnerWhileNode: true,
          },
        },
        {
          id: 'final_merger',
          type: 'automationNode',
          position: { x: 1568, y: 0 },
          data: {
            step: {
              id: 'final_merger',
              name: 'final_merger',
              description: 'merger',
              type: 'action',
              status: 'pending',
            },
            meta: {
              strategy: 'single',
              tool: 'merger',
            },
            derivedStatus: 'pending',
            layoutDirection: 'horizontal',
          },
        },
      ],
      edges: [
        {
          id: 'edge-1-data_processor-std-0',
          source: '1',
          target: 'data_processor',
          type: 'stableStatus',
          label: 'PENDING',
          style: { stroke: '#d1d5db', strokeWidth: 2 },
        },
        {
          id: 'edge-data_processor-counter-std-1',
          source: 'data_processor',
          target: 'counter',
          type: 'stableStatus',
          label: 'PENDING',
          style: { stroke: '#d1d5db', strokeWidth: 2 },
        },
        {
          id: 'edge-counter-loop:while_step_counter-while-2',
          source: 'counter',
          target: 'core:while_step_counter',
          type: 'stableStatus',
          label: 'WHILE',
          style: { stroke: '#eab308', strokeWidth: 1.5 },
        },
        {
          id: 'edge-while_step_counter-whileStep-0-to-condition_checker-1-loop-internal',
          source: 'core:while_step_counter::whileStep#1',
          target: 'core:while_step_counter::condition_checker#2',
          type: 'stableStatus',
          label: 'PENDING',
          style: { stroke: '#d1d5db', strokeWidth: 2 },
        },
        {
          id: 'edge-loop:while_step_counter-final_merger-break-2-0',
          source: 'core:while_step_counter',
          target: 'final_merger',
          type: 'stableStatus',
          label: 'POST ACTIONS',
          style: { stroke: '#eab308', strokeWidth: 1.5 },
        },
      ],
    },
  },
  {
    id: 'database-workflow',
    name: 'Database Workflow',
    description: 'Workflow for database operations',
    icon: <Database className="w-5 h-5" />,
    workflow: {
      type: '__WORKFLOW__',
      nodes: [
        {
          id: 'db-query',
          type: 'automationNode',
          position: { x: 0, y: 0 },
          data: {
            step: {
              id: 'db-query',
              name: 'Database Query',
              description: 'Query database',
              type: 'trigger',
              status: 'pending',
            },
            meta: {
              strategy: 'single',
              tool: 'database',
            },
            derivedStatus: 'pending',
            layoutDirection: 'horizontal',
          },
        },
        {
          id: 'data-transform',
          type: 'automationNode',
          position: { x: 380, y: 0 },
          data: {
            step: {
              id: 'data-transform',
              name: 'Transform Data',
              description: 'transform',
              type: 'action',
              status: 'pending',
            },
            meta: {
              strategy: 'single',
              tool: 'transformer',
            },
            derivedStatus: 'pending',
            layoutDirection: 'horizontal',
          },
        },
        {
          id: 'db-save',
          type: 'automationNode',
          position: { x: 760, y: 0 },
          data: {
            step: {
              id: 'db-save',
              name: 'Save Results',
              description: 'save',
              type: 'action',
              status: 'pending',
            },
            meta: {
              strategy: 'single',
              tool: 'database',
            },
            derivedStatus: 'pending',
            layoutDirection: 'horizontal',
          },
        },
      ],
      edges: [
        {
          id: 'edge-db-query-transform-std-0',
          source: 'db-query',
          target: 'data-transform',
          type: 'stableStatus',
          label: 'PENDING',
          style: { stroke: '#d1d5db', strokeWidth: 2 },
        },
        {
          id: 'edge-transform-save-std-1',
          source: 'data-transform',
          target: 'db-save',
          type: 'stableStatus',
          label: 'PENDING',
          style: { stroke: '#d1d5db', strokeWidth: 2 },
        },
      ],
    },
  },
  {
    id: 'api-workflow',
    name: 'API Workflow',
    description: 'Workflow for API integrations',
    icon: <Globe className="w-5 h-5" />,
    workflow: {
      type: '__WORKFLOW__',
      nodes: [
        {
          id: 'api-trigger',
          type: 'automationNode',
          position: { x: 0, y: 0 },
          data: {
            step: {
              id: 'api-trigger',
              name: 'API Trigger',
              description: 'webhook',
              type: 'trigger',
              status: 'pending',
            },
            meta: {
              strategy: 'single',
              tool: 'webhook',
            },
            derivedStatus: 'pending',
            layoutDirection: 'horizontal',
          },
        },
        {
          id: 'api-process',
          type: 'automationNode',
          position: { x: 380, y: 0 },
          data: {
            step: {
              id: 'api-process',
              name: 'Process Request',
              description: 'processor',
              type: 'action',
              status: 'pending',
            },
            meta: {
              strategy: 'single',
              tool: 'processor',
            },
            derivedStatus: 'pending',
            layoutDirection: 'horizontal',
          },
        },
        {
          id: 'api-response',
          type: 'automationNode',
          position: { x: 760, y: 0 },
          data: {
            step: {
              id: 'api-response',
              name: 'Send Response',
              description: 'response',
              type: 'action',
              status: 'pending',
            },
            meta: {
              strategy: 'single',
              tool: 'api',
            },
            derivedStatus: 'pending',
            layoutDirection: 'horizontal',
          },
        },
      ],
      edges: [
        {
          id: 'edge-api-trigger-process-std-0',
          source: 'api-trigger',
          target: 'api-process',
          type: 'stableStatus',
          label: 'PENDING',
          style: { stroke: '#d1d5db', strokeWidth: 2 },
        },
        {
          id: 'edge-process-response-std-1',
          source: 'api-process',
          target: 'api-response',
          type: 'stableStatus',
          label: 'PENDING',
          style: { stroke: '#d1d5db', strokeWidth: 2 },
        },
      ],
    },
  },
  {
    id: 'file-workflow',
    name: 'File Processing',
    description: 'Workflow for file operations',
    icon: <FileText className="w-5 h-5" />,
    workflow: {
      type: '__WORKFLOW__',
      nodes: [
        {
          id: 'file-read',
          type: 'automationNode',
          position: { x: 0, y: 0 },
          data: {
            step: {
              id: 'file-read',
              name: 'Read File',
              description: 'file-reader',
              type: 'trigger',
              status: 'pending',
            },
            meta: {
              strategy: 'single',
              tool: 'file',
            },
            derivedStatus: 'pending',
            layoutDirection: 'horizontal',
          },
        },
        {
          id: 'file-parse',
          type: 'automationNode',
          position: { x: 380, y: 0 },
          data: {
            step: {
              id: 'file-parse',
              name: 'Parse Content',
              description: 'parser',
              type: 'action',
              status: 'pending',
            },
            meta: {
              strategy: 'single',
              tool: 'parser',
            },
            derivedStatus: 'pending',
            layoutDirection: 'horizontal',
          },
        },
        {
          id: 'file-write',
          type: 'automationNode',
          position: { x: 760, y: 0 },
          data: {
            step: {
              id: 'file-write',
              name: 'Write Result',
              description: 'file-writer',
              type: 'action',
              status: 'pending',
            },
            meta: {
              strategy: 'single',
              tool: 'file',
            },
            derivedStatus: 'pending',
            layoutDirection: 'horizontal',
          },
        },
      ],
      edges: [
        {
          id: 'edge-file-read-parse-std-0',
          source: 'file-read',
          target: 'file-parse',
          type: 'stableStatus',
          label: 'PENDING',
          style: { stroke: '#d1d5db', strokeWidth: 2 },
        },
        {
          id: 'edge-parse-write-std-1',
          source: 'file-parse',
          target: 'file-write',
          type: 'stableStatus',
          label: 'PENDING',
          style: { stroke: '#d1d5db', strokeWidth: 2 },
        },
      ],
    },
  },
];

interface WorkflowSelectorProps {
  isOpen: boolean;
  onClose: () => void;
  onSelect: (workflow: WorkflowTemplate) => void;
}

export const WorkflowSelector: React.FC<WorkflowSelectorProps> = ({
  isOpen,
  onClose,
  onSelect,
}) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={onClose}>
      <div
        className="bg-white dark:bg-gray-900 rounded-2xl shadow-[0_16px_48px_rgba(0,0,0,0.16)] max-w-4xl w-full mx-4 max-h-[80vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="p-6">
          <div className="flex items-center justify-between mb-6">
            <h2 className="text-xl font-semibold text-theme-primary">Select a Workflow Template</h2>
            <Button
              variant="ghost"
              size="icon"
              onClick={onClose}
              className="w-8 h-8"
            >
              <span className="text-xl">×</span>
            </Button>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {workflowTemplates.map((template) => (
              <button
                key={template.id}
                onClick={() => {
                  onSelect(template);
                  onClose();
                }}
                className="p-4 border border-gray-200 dark:border-gray-800 rounded-lg hover:bg-theme-tertiary transition-all duration-200 text-left group"
              >
                <div className="flex items-center space-x-3 mb-2">
                  <div className="text-theme-primary group-hover:text-theme-primary/80 transition-colors">
                    {template.icon}
                  </div>
                  <h3 className="font-semibold text-theme-primary">{template.name}</h3>
                </div>
                <p className="text-sm text-theme-secondary">{template.description}</p>
              </button>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
};

export { workflowTemplates };

