import React from 'react';

interface ErrorDisplayProps {
  data: any;
  responseType?: string;
  title?: string;
  className?: string;
}

const ErrorDisplay: React.FC<ErrorDisplayProps> = ({
  data,
  responseType,
  title = "Details",
  className = ""
}) => {
  if (!data) return null;

  const renderContent = () => {
    // Handle differently based on error response type
    if (responseType === 'html' && typeof data === 'string') {
      return (
        <div>
          <div className="mb-2">
            <strong>Type:</strong> HTML
          </div>
          <div className="bg-gray-800 text-blue-400 p-2 rounded font-mono text-xs max-h-32 overflow-y-auto">
            {data}
          </div>
        </div>
      );
    } else if (responseType === 'text' && typeof data === 'string') {
      return (
        <div>
          <div className="mb-2">
            <strong>Type:</strong> Text
          </div>
          <div className="bg-gray-800 text-blue-400 p-2 rounded font-mono text-xs max-h-32 overflow-y-auto">
            {data}
          </div>
        </div>
      );
    } else if (typeof data === 'string') {
      return data;
    } else {
      return (
        <div className="bg-gray-800 text-blue-400 p-2 rounded font-mono text-xs max-h-32 overflow-y-auto">
          {JSON.stringify(data, null, 2)}
        </div>
      );
    }
  };

  return (
    <div className={`p-2 bg-blue-100 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800 rounded ${className}`}>
      <div className="font-medium text-blue-800 dark:text-blue-300 mb-1">{title}:</div>
      <div className="text-blue-700 dark:text-blue-400 text-sm">
        {renderContent()}
      </div>
    </div>
  );
};

export default ErrorDisplay;




