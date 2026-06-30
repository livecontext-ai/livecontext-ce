import React, { useState } from 'react';
import { Info, CheckCircle, AlertCircle } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { ResponseTabProps } from './types';

const ResponseTab: React.FC<ResponseTabProps> = ({
  tool,
  toolIndex,
  onToolUpdate
}) => {
  const t = useTranslations('developers');
  const [showInfo, setShowInfo] = useState(false);

  const handleDescriptionChange = (description: string) => {
    const updatedTool = {
      ...tool,
      response: {
        ...(tool.response || { success: {}, error: {}, description: '' }),
        description
      }
    };
    onToolUpdate(toolIndex, updatedTool);
  };

  const formatTestResult = () => {
    if (!tool.testResult) {
      return t('responseTab.responsePlaceholder');
    }

    const responseData = {
      status: tool.testResult.status,
      responseTime: `${tool.testResult.responseTime}ms`
    };

    if (tool.testStatus === 'success') {
      const responseType = tool.response?.type;

      if (responseType === 'html' && typeof tool.testResult.data === 'string') {
        return `Status: ${responseData.status}\nResponse Time: ${responseData.responseTime}\n\nHTML Response:\n${tool.testResult.data}`;
      } else if (responseType === 'text' && typeof tool.testResult.data === 'string') {
        return `Status: ${responseData.status}\nResponse Time: ${responseData.responseTime}\n\nText Response:\n${tool.testResult.data}`;
      } else if (responseType === 'csv' && typeof tool.testResult.data === 'string') {
        return `Status: ${responseData.status}\nResponse Time: ${responseData.responseTime}\n\nCSV Response:\n${tool.testResult.data}`;
      } else if (responseType === 'xml' && typeof tool.testResult.data === 'string') {
        return `Status: ${responseData.status}\nResponse Time: ${responseData.responseTime}\n\nXML Response:\n${tool.testResult.data}`;
      } else {
        return JSON.stringify({
          ...responseData,
          data: tool.testResult.data
        }, null, 2);
      }
    } else if (tool.testStatus === 'error') {
      const errorData = {
        status: tool.testResult.status,
        responseTime: `${tool.testResult.responseTime}ms`,
        error: tool.testResult.error
      };
      const responseType = tool.response?.type;

      if (responseType === 'html' && typeof tool.testResult.data?.html === 'string') {
        return `Status: ${errorData.status}\nResponse Time: ${errorData.responseTime}\nError: ${errorData.error}\n\nHTML Response:\n${tool.testResult.data.html}`;
      } else if (responseType === 'text' && typeof tool.testResult.data?.text === 'string') {
        return `Status: ${errorData.status}\nResponse Time: ${errorData.responseTime}\nError: ${errorData.error}\n\nText Response:\n${tool.testResult.data.text}`;
      } else {
        return JSON.stringify({
          ...errorData,
          data: tool.testResult.data
        }, null, 2);
      }
    }

    return t('responseTab.responsePlaceholder');
  };

  const getTextareaClassName = () => {
    const baseClasses = 'w-full px-4 py-3 border rounded-lg transition-all duration-300 resize-none font-mono text-sm cursor-default';

    if (tool.testResult && tool.testStatus === 'success') {
      return `${baseClasses} bg-green-50 dark:bg-green-900/20 border-green-200 dark:border-green-700 text-green-800 dark:text-green-200`;
    } else if (tool.testResult && tool.testStatus === 'error') {
      return `${baseClasses} bg-red-50 dark:bg-red-900/20 border-red-200 dark:border-red-700 text-red-800 dark:text-red-200`;
    }
    return `${baseClasses} bg-gray-100 dark:bg-gray-800 border-gray-300 dark:border-gray-600 text-gray-500 dark:text-gray-400`;
  };

  return (
    <div>
      <h5 className="text-md font-medium text-theme-primary mb-3">{t('responseTab.title')}</h5>

      {/* Response Description */}
      <div className="mb-4">
        <label className="block text-sm font-medium text-theme-primary mb-2">
          {t('responseTab.responseDescription')}
        </label>
        <textarea
          value={tool.response?.description || ''}
          onChange={(e) => handleDescriptionChange(e.target.value)}
          rows={3}
          className="w-full px-4 py-3 bg-theme-primary border border-theme rounded-lg text-theme-primary placeholder-theme-muted focus:outline-none focus:ring-2 focus:ring-blue-500/50 transition-all duration-300 resize-none"
          placeholder={t('responseTab.descriptionPlaceholder')}
        />
      </div>

      {/* Success Response */}
      <div className="mb-4">
        <div className="flex items-center justify-between mb-2">
          <div className="flex items-center space-x-2">
            <button
              type="button"
              onClick={() => setShowInfo(!showInfo)}
              className="text-theme-muted hover:text-theme-primary transition-colors duration-200"
            >
              <Info className="w-4 h-4" />
            </button>
            <label className="block text-sm font-medium text-theme-primary">
              {t('responseTab.successResponse')}
            </label>
          </div>
        </div>

        {showInfo && (
          <div className="mb-3 p-3 bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-700 rounded-lg">
            <div className="flex items-start space-x-3">
              <Info className="w-4 h-4 text-blue-500 mt-0.5 flex-shrink-0" />
              <div className="text-sm text-blue-700 dark:text-blue-300">
                <p className="mb-1">
                  <strong>{t('responseTab.autoResponseLabel')}</strong> {t('responseTab.autoResponseText')}
                </p>
                <p>
                  {t('responseTab.formatDependsOnType')}
                </p>
              </div>
            </div>
          </div>
        )}

        <textarea
          value={formatTestResult()}
          readOnly
          rows={4}
          className={getTextareaClassName()}
          placeholder={t('responseTab.responsePlaceholder')}
        />

        {tool.testResult && tool.testStatus === 'success' && (
          <p className="mt-2 text-sm text-green-600 dark:text-green-400 flex items-center space-x-1">
            <CheckCircle className="w-3 h-3" />
            <span>{t('responseTab.successTestResult')}</span>
          </p>
        )}

        {tool.testResult && tool.testStatus === 'error' && (
          <p className="mt-2 text-sm text-red-600 dark:text-red-400 flex items-center space-x-1">
            <AlertCircle className="w-3 h-3" />
            <span>{t('responseTab.errorTestResult')}</span>
          </p>
        )}
      </div>
    </div>
  );
};

export default ResponseTab;
