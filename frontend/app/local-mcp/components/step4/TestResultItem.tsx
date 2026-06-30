import React from 'react';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { CheckCircle, XCircle, Zap } from 'lucide-react';
import { LocalMcpTool } from '../../types';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';

interface TestResultItemProps {
  tool: LocalMcpTool;
  statusIcon: React.ReactNode;
  statusText: string;
  statusColor: string;
}

export const TestResultItem: React.FC<TestResultItemProps> = ({
  tool,
  statusIcon,
  statusText,
  statusColor
}) => (
  <div className="bg-theme-tertiary p-4 rounded-lg border border-theme">
    <div className="flex items-start gap-3">
      {statusIcon}
      <div className="flex-1">
        <div className="flex items-center gap-2 mb-2">
          <h4 className="font-medium text-theme-primary">{tool.name}</h4>
          <span className="inline-block px-2 py-1 text-xs bg-blue-500/20 text-blue-600 dark:text-blue-400 rounded-full">
            {tool.toolType.replace('LOCAL_', '')}
          </span>
        </div>

        <p className="text-sm text-theme-secondary mb-2">{tool.description}</p>

        <code className="text-xs bg-theme-primary/10 px-2 py-1 rounded text-theme-primary block mb-3">
          {tool.command}
        </code>

        <div className="flex items-center gap-4 text-sm">
          <span className={`font-medium ${statusColor}`}>{statusText}</span>

          {tool.testResponseTime && (
            <span className="text-theme-secondary">
              <Zap className="w-3 h-3 inline mr-1" />
              {tool.testResponseTime}ms
            </span>
          )}

          {tool.lastTestTime && (
            <span className="text-theme-secondary">
              Tested on {formatUtcDateTime(new Date(tool.lastTestTime))}
            </span>
          )}
        </div>

        {tool.testResult && tool.testStatus === 'ERROR' && (
          <Alert className="mt-3 border-red-200 bg-red-50 dark:bg-red-900/30">
            <XCircle className="h-4 w-4 text-red-600" />
            <AlertDescription className="text-red-800 dark:text-red-200">
              <div className="font-medium mb-1">Test error:</div>
              <pre className="text-xs whitespace-pre-wrap">{tool.testResult}</pre>
            </AlertDescription>
          </Alert>
        )}

        {tool.testStatus === 'SUCCESS' && (
          <Alert className="mt-3 border-green-200 bg-green-50 dark:bg-green-900/30">
            <CheckCircle className="h-4 w-4 text-green-600" />
            <AlertDescription className="text-green-800 dark:text-green-200">
              Test successful! The tool works correctly.
            </AlertDescription>
          </Alert>
        )}
      </div>
    </div>
  </div>
);

export default TestResultItem;
