import React from 'react';

interface ResponseDisplayProps {
  data: any;
  responseType?: string;
  title?: string;
  className?: string;
}

const ResponseDisplay: React.FC<ResponseDisplayProps> = ({
  data,
  responseType,
  title = "Received Data",
  className = ""
}) => {
  if (!data) return null;

  const renderContent = () => {
    // Handle differently based on response type
    if (responseType === 'html' && typeof data === 'string') {
      return (
        <div>
          <div className="mb-2">
            <strong>Type:</strong> HTML
          </div>
          <div className="bg-gray-800 text-green-400 p-2 rounded font-mono text-xs max-h-32 overflow-y-auto">
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
          <div className="bg-gray-800 text-green-400 p-2 rounded font-mono text-xs max-h-32 overflow-y-auto">
            {data}
          </div>
        </div>
      );
    } else if (responseType === 'csv' && typeof data === 'string') {
      return (
        <div>
          <div className="mb-2">
            <strong>Type:</strong> CSV
          </div>
          <div className="bg-gray-800 text-green-400 p-2 rounded font-mono text-xs max-h-32 overflow-y-auto">
            {data}
          </div>
        </div>
      );
    } else if (responseType === 'xml' && typeof data === 'string') {
      return (
        <div>
          <div className="mb-2">
            <strong>Type:</strong> XML
          </div>
          <div className="bg-gray-800 text-green-400 p-2 rounded font-mono text-xs max-h-32 overflow-y-auto">
            {data}
          </div>
        </div>
      );
    } else if (typeof data === 'string') {
      return data;
    } else if (data.message) {
      return data.message;
    } else {
      return (
        <div className="bg-gray-800 text-green-400 p-2 rounded font-mono text-xs max-h-32 overflow-y-auto">
          {JSON.stringify(data, null, 2)}
        </div>
      );
    }
  };

  return (
    <div className={`p-2 bg-green-100 border border-green-200 rounded ${className}`}>
      <div className="font-medium text-green-800 mb-1">{title}:</div>
      <div className="text-green-700 text-sm">
        {renderContent()}
      </div>
    </div>
  );
};

export default ResponseDisplay;




