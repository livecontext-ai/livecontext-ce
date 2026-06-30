'use client';

import React, { useState, useMemo } from 'react';
import { ToggleGroup } from '@/components/ui/toggle-group';
import { Settings } from 'lucide-react';
import { DataSourceSuggestions } from './DataSourceSuggestions';
import { DataSourceMessage, isDataSourceMessage } from './DataSourceMessage';
import DataSourceTable from '@/components/DataSourceTable';
import { useAuthGuard } from '@/hooks/useAuthGuard';

interface DataSourceDisplayModeProps {
  content: string;
  messageId: string;
  onReplaceDataSource?: (messageId: string, dataSourceId: number) => void;
  onManualMode?: () => void;
  hideToggle?: boolean;
}

const DataSourceDisplayMode: React.FC<DataSourceDisplayModeProps> = ({ 
  content, 
  messageId,
  onReplaceDataSource,
  onManualMode,
  hideToggle = false
}) => {
  const [displayMode, setDisplayMode] = useState<'auto' | 'manual'>('auto');
  const { user } = useAuthGuard();
  const tenantId = user?.sub || user?.email || 'demo';

  // Check if datasource exists (dataSourceId !== null)
  const hasDataSource = useMemo(() => {
    if (!isDataSourceMessage(content)) return false;
    try {
      const parsed = JSON.parse(content);
      return parsed.dataSourceId !== null && parsed.dataSourceId !== undefined;
    } catch {
      return false;
    }
  }, [content]);

  // SVG Sparkles icon for auto mode
  const SparklesIcon = (
    <svg 
      xmlns="http://www.w3.org/2000/svg" 
      width="16" 
      height="16" 
      viewBox="0 0 24 24" 
      fill="none" 
      stroke="currentColor" 
      strokeWidth="2" 
      strokeLinecap="round" 
      strokeLinejoin="round" 
      className="lucide lucide-sparkles"
      aria-hidden="true"
    >
      <path d="M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z"></path>
      <path d="M20 2v4"></path>
      <path d="M22 4h-4"></path>
      <circle cx="4" cy="20" r="2"></circle>
    </svg>
  );

  const handleManualMode = () => {
    setDisplayMode('manual');
    if (onManualMode) {
      onManualMode();
    }
  };

  const handleSuggestionSelect = (messageId: string, suggestion: string) => {
    // For now, suggestions are just placeholders
    // In the future, this could trigger LLM to generate a datasource
    console.log('Suggestion selected:', suggestion, 'for message:', messageId);
  };

  // If datasource exists, show it directly without toggle or suggestions
  if (hasDataSource) {
    return (
      <div className="w-full">
        <div className="message-content w-full flex justify-center">
          <DataSourceMessage 
            content={content} 
            messageId={messageId}
          />
        </div>
      </div>
    );
  }

  // If datasource doesn't exist, show toggle and suggestions
  return (
    <div className="w-full">
      {/* Mode toggle group - hidden in panel */}
      {!hideToggle && (
        <div className="flex justify-end mb-2">
          <ToggleGroup
            value={displayMode}
            onValueChange={(value) => {
              setDisplayMode(value as 'auto' | 'manual');
              if (value === 'manual' && onManualMode) {
                onManualMode();
              }
            }}
            options={[
              {
                value: 'auto',
                label: 'Auto',
                icon: SparklesIcon,
              },
              {
                value: 'manual',
                label: 'Manual',
                icon: <Settings className="w-4 h-4" />,
              },
            ]}
            hasBorder={false}
            variant="pill"
            className="text-xs"
          />
        </div>
      )}

      {/* Auto mode: Show suggestions */}
      {displayMode === 'auto' && (
        <div className="message-content w-full flex justify-center">
          <DataSourceSuggestions
            messageId={messageId}
            onSelect={handleSuggestionSelect}
            onManualMode={handleManualMode}
          />
        </div>
      )}

      {/* Manual mode: Show datasource message if datasource exists */}
      {displayMode === 'manual' && (
        <div className="message-content w-full flex justify-center">
          {isDataSourceMessage(content) && (() => {
            try {
              const parsed = JSON.parse(content);
              return parsed.dataSourceId !== null;
            } catch {
              return false;
            }
          })() && (
            <DataSourceMessage 
              content={content} 
              messageId={messageId}
            />
          )}
        </div>
      )}
    </div>
  );
};

export default DataSourceDisplayMode;

