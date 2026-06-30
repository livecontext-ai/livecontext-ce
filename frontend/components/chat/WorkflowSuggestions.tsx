'use client';

import React from 'react';
import { Button } from '@/components/ui/button';
import { WorkflowTemplate, workflowTemplates } from './WorkflowSelector';

interface WorkflowSuggestionsProps {
  messageId: string;
  onSelect: (messageId: string, template: WorkflowTemplate) => void;
}

const workflowSuggestions: Array<{ id: string; name: string; description: string; templateId: string }> = [
  {
    id: 'influence-marketing',
    name: 'Run an influence marketing campaign',
    description: 'Execute an influencer marketing campaign',
    templateId: 'simple-pipeline',
  },
  {
    id: 'fetch-posts-stories',
    name: 'Fetch latest posts/stories',
    description: 'Retrieve the latest posts and stories',
    templateId: 'simple-pipeline',
  },
  {
    id: 'find-cheap-flights',
    name: 'Find cheaper flights',
    description: 'Search for cheaper flight tickets',
    templateId: 'simple-pipeline',
  },
  {
    id: 'build-rag',
    name: 'Build a RAG',
    description: 'Create a RAG system for document search',
    templateId: 'simple-pipeline',
  },
  {
    id: 'find-leads',
    name: 'Find leads',
    description: 'Search and qualify prospects',
    templateId: 'simple-pipeline',
  },
  {
    id: 'monitor-competitors',
    name: 'Monitor competitors',
    description: 'Track competitor activities and pricing',
    templateId: 'loop-workflow',
  },
  {
    id: 'content-scheduling',
    name: 'Schedule content',
    description: 'Automate social media content scheduling',
    templateId: 'simple-pipeline',
  },
  {
    id: 'price-tracking',
    name: 'Track prices',
    description: 'Monitor product prices and get alerts',
    templateId: 'loop-workflow',
  },
  {
    id: 'email-automation',
    name: 'Automate emails',
    description: 'Set up automated email workflows',
    templateId: 'simple-pipeline',
  },
  {
    id: 'data-extraction',
    name: 'Extract data',
    description: 'Scrape and extract data from websites',
    templateId: 'simple-pipeline',
  },
  {
    id: 'report-generation',
    name: 'Generate reports',
    description: 'Automatically generate and send reports',
    templateId: 'simple-pipeline',
  },
  {
    id: 'social-media-monitoring',
    name: 'Monitor social media',
    description: 'Track mentions and engagement across platforms',
    templateId: 'loop-workflow',
  },
  {
    id: 'customer-support',
    name: 'Automate customer support',
    description: 'Handle customer inquiries and tickets',
    templateId: 'simple-pipeline',
  },
  {
    id: 'inventory-management',
    name: 'Manage inventory',
    description: 'Track stock levels and reorder automatically',
    templateId: 'loop-workflow',
  },
  {
    id: 'news-aggregation',
    name: 'Aggregate news',
    description: 'Collect and summarize news from multiple sources',
    templateId: 'simple-pipeline',
  },
  {
    id: 'web-scraping',
    name: 'Scrape websites',
    description: 'Extract structured data from web pages',
    templateId: 'simple-pipeline',
  },
  {
    id: 'api-integration',
    name: 'Integrate APIs',
    description: 'Connect and sync data between services',
    templateId: 'simple-pipeline',
  },
  {
    id: 'data-validation',
    name: 'Validate data',
    description: 'Check and clean data quality',
    templateId: 'loop-workflow',
  },
  {
    id: 'notification-system',
    name: 'Send notifications',
    description: 'Deliver alerts and updates via multiple channels',
    templateId: 'simple-pipeline',
  },
  {
    id: 'backup-automation',
    name: 'Automate backups',
    description: 'Schedule and manage data backups',
    templateId: 'loop-workflow',
  },
];

export const WorkflowSuggestions: React.FC<WorkflowSuggestionsProps> = ({
  messageId,
  onSelect,
}) => {
  const getWorkflowTemplate = (suggestionId: string): WorkflowTemplate | null => {
    const suggestion = workflowSuggestions.find(s => s.id === suggestionId);
    if (!suggestion) return null;
    
    const baseTemplate = workflowTemplates.find((t: WorkflowTemplate) => t.id === suggestion.templateId);
    if (!baseTemplate) return null;
    
    // Return template with custom name/description
    return {
      ...baseTemplate,
      id: suggestionId,
      name: suggestion.name,
      description: suggestion.description,
    };
  };

  const handleOtherClick = () => {
    // Create an empty workflow template for "other"
    const emptyTemplate: WorkflowTemplate = {
      id: 'other',
      name: 'Other',
      description: 'Create a custom workflow',
      icon: null,
      workflow: {
        type: '__WORKFLOW__',
        nodes: [
          {
            id: 'empty-placeholder',
            type: 'emptyWorkflowNode',
            position: { x: 0, y: 0 },
            data: {},
          },
        ],
        edges: [],
      },
    };
    onSelect(messageId, emptyTemplate);
  };

  return (
    <div className="mt-4 pt-4">
      <div className="mb-4 text-center">
        <h2 className="text-lg text-theme-primary font-medium mb-8">
          How can I help you with your automation?
        </h2>
      </div>
      <div className="flex flex-wrap gap-2">
        {workflowSuggestions.map((suggestion) => (
          <Button
            key={suggestion.id}
            variant="outline"
            size="sm"
            onClick={() => {
              const template = getWorkflowTemplate(suggestion.id);
              if (template) {
                onSelect(messageId, template);
              }
            }}
            className="text-xs border-gray-300/70 dark:border-gray-600/70"
          >
            {suggestion.name}
          </Button>
        ))}
        <Button
          key="other"
          variant="outline"
          size="sm"
          onClick={handleOtherClick}
          className="text-xs border-gray-300/70 dark:border-gray-600/70"
        >
          Other
        </Button>
      </div>
    </div>
  );
};

