import React from 'react';
import { Info, CheckCircle, XCircle, Clock, TestTube } from 'lucide-react';
import { ActionButton, InfoBox } from './index';

interface TestStatusCardProps {
  status?: 'success' | 'error' | 'pending';
  totalTools: number;
  successCount: number;
  errorCount: number;
  pendingCount: number;
  onTestAll: () => void;
  showInfo: boolean;
  onToggleInfo: () => void;
}

const TestStatusCard: React.FC<TestStatusCardProps> = ({
  status,
  totalTools,
  successCount,
  errorCount,
  pendingCount,
  onTestAll,
  showInfo,
  onToggleInfo
}) => {
  const getStatusColor = () => {
    switch (status) {
      case 'success':
        return 'border-green-200 dark:border-green-700 bg-green-50 dark:bg-green-900/20';
      case 'error':
        return 'border-red-200 dark:border-red-700 bg-red-50 dark:bg-red-900/20';
      case 'pending':
        return 'border-yellow-200 dark:border-yellow-700 bg-yellow-50 dark:bg-yellow-900/20';
      default:
        return 'border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-gray-900/20';
    }
  };

  const getStatusIcon = () => {
    switch (status) {
      case 'success':
        return <CheckCircle className="w-5 h-5 text-green-500" />;
      case 'error':
        return <XCircle className="w-5 h-5 text-red-500" />;
      case 'pending':
        return <Clock className="w-5 h-5 text-yellow-500" />;
      default:
        return <Clock className="w-5 h-5 text-gray-400" />;
    }
  };

  const getStatusText = () => {
    switch (status) {
      case 'success':
                  return 'All tools are validated! You can now submit your API.';
      case 'error':
                  return 'Some tests have failed. Please fix the errors before continuing.';
      case 'pending':
                  return `${pendingCount} tool(s) have not been tested yet.`;
      default:
        return 'No tool tested.';
    }
  };

  if (totalTools === 0) {
    return null;
  }

  return (
    <div className={`p-4 rounded-lg border ${getStatusColor()}`}>
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center space-x-2">
          <h4 className="font-medium text-theme-primary">Test Summary</h4>
          <button
            type="button"
            onClick={onToggleInfo}
            className="text-theme-muted hover:text-theme-primary transition-colors"
          >
            <Info className="w-4 h-4" />
          </button>
        </div>
        
        <div className="text-right">
          <p className="text-sm text-theme-muted mb-2">
            {successCount} out of {totalTools} endpoints tested successfully
          </p>
          <button
            type="button"
            onClick={onTestAll}
            className="px-4 py-2 rounded-lg transition-colors duration-200 text-sm font-medium flex items-center space-x-2 bg-green-500 text-white hover:bg-green-600"
          >
            <TestTube className="w-4 h-4" />
            <span>Test all endpoints</span>
          </button>
        </div>
      </div>

      {/* Test information message */}
      {showInfo && (
        <InfoBox
          type="info"
          title="Mandatory validation"
          className="mb-4"
        >
          <p className="mb-2">
            <strong>Mandatory validation:</strong> All your endpoints must be tested successfully (code 200) before you can submit your API.
            Use the "Test" buttons to the right of each tool or "Test all endpoints" to validate your tools.
            The "Submit" button will only be available when all tools have a ✅ OK status.
          </p>
        </InfoBox>
      )}

      {/* Validation indicator */}
      <div className="flex items-center space-x-2">
        {getStatusIcon()}
        <span className="text-sm font-medium text-theme-primary">
          {getStatusText()}
        </span>
      </div>

      {/* Additional endpoint information */}
      <div className="mt-3 pt-3 border-t border-gray-200 dark:border-gray-600">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-xs">
          <div className="flex items-center space-x-2">
            <div className="w-3 h-3 bg-blue-500 rounded-full"></div>
            <span className="text-gray-600 dark:text-gray-400">
              {totalTools} tool(s) configured
            </span>
          </div>
          <div className="flex items-center space-x-2">
            <div className="w-3 h-3 bg-green-500 rounded-full"></div>
            <span className="text-gray-600 dark:text-gray-400">
              {successCount} tool(s) tested successfully
            </span>
          </div>
          {errorCount > 0 && (
            <div className="flex items-center space-x-2">
              <div className="w-3 h-3 bg-red-500 rounded-full"></div>
              <span className="text-gray-600 dark:text-gray-400">
                {errorCount} tool(s) with test error
              </span>
            </div>
          )}
          {pendingCount > 0 && (
            <div className="flex items-center space-x-2">
              <div className="w-3 h-3 bg-yellow-500 rounded-full"></div>
              <span className="text-gray-600 dark:text-gray-400">
                {pendingCount} tool(s) pending test
              </span>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default TestStatusCard;
