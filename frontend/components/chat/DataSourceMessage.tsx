'use client';

import React, { useMemo } from 'react';
import { ChatTableView } from './ChatTableView';

interface DataSourceMessageProps {
  content: string;
  messageId?: string;
  hideExpandButton?: boolean;
  hideBreadcrumb?: boolean; // Hide breadcrumb when in expanded data view
}

// Helper function to check if content is a datasource message
export function isDataSourceMessage(content: string): boolean {
  try {
    const parsed = JSON.parse(content);
    return (parsed.type === '__DATASOURCE__' || parsed.type === 'DATASOURCE') && 
           (parsed.dataSourceId !== undefined);
  } catch {
    return false;
  }
}

// Helper function to extract datasource data
function extractDataSourceData(content: string): { dataSourceId: number | null } | null {
  try {
    const parsed = JSON.parse(content);
    if ((parsed.type === '__DATASOURCE__' || parsed.type === 'DATASOURCE') && 
        parsed.dataSourceId !== undefined) {
      // Convert dataSourceId to number (handles string from URL params)
      const dataSourceId = typeof parsed.dataSourceId === 'string' 
        ? (parsed.dataSourceId === 'new' ? null : parseInt(parsed.dataSourceId, 10))
        : parsed.dataSourceId;
      
      // Return null if conversion failed or if it's 'new'
      if (dataSourceId === null || (typeof dataSourceId === 'number' && isNaN(dataSourceId))) {
        return { dataSourceId: null };
      }
      
      return {
        dataSourceId: dataSourceId as number,
      };
    }
  } catch {
    // Not a datasource message
  }
  return null;
}

export const DataSourceMessage: React.FC<DataSourceMessageProps> = ({
  content,
  messageId,
  hideExpandButton = false,
  hideBreadcrumb = false
}) => {
  const datasourceData = useMemo(() => extractDataSourceData(content), [content]);

  if (!datasourceData || datasourceData.dataSourceId === null) {
    return null;
  }

  return (
    <div className="w-full">
      <ChatTableView
        dataSourceId={datasourceData.dataSourceId}
        maxRows={10}
        className="w-full"
      />
    </div>
  );
};

