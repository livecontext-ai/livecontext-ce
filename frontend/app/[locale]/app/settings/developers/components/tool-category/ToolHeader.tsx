import React from 'react';
import { ChevronRight, X, Save, SquarePen, Trash2, TestTube } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { ToolHeaderProps } from './types';

const ToolHeader: React.FC<ToolHeaderProps> = ({
  tool,
  toolKey,
  isCollapsed,
  onToggleCollapse,
  onTestEndpoint,
  onDeleteTool,
  isEditingName,
  editingNameValue,
  onStartEditingName,
  onSaveToolName,
  onCancelEditingName,
  onNameValueChange,
  onNameKeyPress
}) => {
  const t = useTranslations('developers');

  const renderTestButton = (isMobile = false) => (
    <button
      type="button"
      onClick={onTestEndpoint}
      disabled={tool.testStatus === 'pending' || !tool.endpoint || tool.endpoint.trim() === ''}
      className={`${isMobile ? 'w-full flex items-center justify-center space-x-2 p-3' : 'p-2'} rounded transition-colors duration-200 ${
        tool.testStatus === 'pending'
          ? 'text-blue-500 cursor-wait'
          : (!tool.endpoint || tool.endpoint.trim() === '')
            ? 'text-theme-muted cursor-not-allowed'
            : 'text-theme-muted hover:text-green-500'
      }`}
      title={tool.testStatus === 'pending' ? t('toolHeader.testing') : t('toolHeader.testEndpoint')}
    >
      <TestTube className="w-4 h-4 flex-shrink-0" />
      {isMobile && <span className="text-sm font-medium">{t('toolHeader.testEndpoint')}</span>}
    </button>
  );

  const renderTestStatus = (isMobile = false) => {
    if (!tool.testStatus) return null;

    return (
      <div className={`${isMobile ? 'w-full flex justify-center' : ''}`}>
        <div className={`px-${isMobile ? '3' : '2'} py-${isMobile ? '2' : '1'} rounded-full text-${isMobile ? 'sm' : 'xs'} font-medium ${
          tool.testStatus === 'success'
            ? 'bg-green-100 text-green-800'
            : tool.testStatus === 'error'
              ? 'bg-red-100 text-red-800'
              : 'bg-yellow-100 text-yellow-800'
        }`}>
          {tool.testStatus === 'success' ? t('toolHeader.success') :
            tool.testStatus === 'error' ? t('toolHeader.error') :
              tool.testStatus === 'pending' ? t('toolHeader.testing') : t('toolHeader.test')}
        </div>
      </div>
    );
  };

  const renderToolName = (isMobile = false) => {
    if (isEditingName) {
      return (
        <div className="flex items-center space-x-2 flex-1">
          <input
            type="text"
            value={editingNameValue}
            onChange={(e) => onNameValueChange(e.target.value)}
            onKeyDown={onNameKeyPress}
            onBlur={onSaveToolName}
            className={`${isMobile ? 'text-base' : 'text-lg'} font-medium text-theme-primary bg-transparent border-b border-theme-primary focus:outline-none focus:border-blue-500 px-1 py-0.5 ${isMobile ? 'flex-1' : 'min-w-0 flex-1'}`}
            autoFocus
          />
          <button
            type="button"
            onClick={onSaveToolName}
            className="p-1 text-green-600 hover:text-green-700 hover:bg-green-50 dark:hover:bg-green-900/10 rounded transition-colors duration-200"
            title={t('toolHeader.saveChanges')}
          >
            <Save className="w-4 h-4" />
          </button>
          <button
            type="button"
            onClick={onCancelEditingName}
            className="p-1 text-gray-500 hover:text-gray-700 hover:bg-gray-50 dark:hover:bg-gray-900/10 rounded transition-colors duration-200"
            title={t('toolHeader.cancelEditing')}
          >
            <X className="w-4 h-4" />
          </button>
        </div>
      );
    }

    return (
      <div className="flex items-center space-x-2 group flex-1">
        <h4 className={`${isMobile ? 'text-base truncate flex-1' : 'text-lg'} font-medium text-theme-primary`}>
          {tool.name}
        </h4>
        {tool.isCustomToolName && (
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              onStartEditingName();
            }}
            className="p-1 text-gray-400 hover:text-blue-600 hover:bg-blue-50 dark:hover:bg-blue-900/10 rounded transition-colors duration-200 opacity-0 group-hover:opacity-100"
            title={t('toolHeader.editToolName')}
          >
            <SquarePen className="w-4 h-4" />
          </button>
        )}
      </div>
    );
  };

  const renderDeleteButton = (isMobile = false) => (
    <button
      type="button"
      onClick={(e) => {
        e.stopPropagation();
        onDeleteTool();
      }}
      className={`${isMobile
        ? 'w-full flex items-center space-x-2 p-3'
        : 'p-1.5'} text-theme-muted hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/10 rounded-md transition-all duration-200 group`}
      title={t('toolHeader.deleteTool')}
    >
      <Trash2 className="w-4 h-4 transition-transform duration-200 group-hover:scale-110 flex-shrink-0" />
      {isMobile && <span className="text-sm font-medium">{t('toolHeader.deleteTool')}</span>}
    </button>
  );

  const formatEndpoint = (endpoint: string) => {
    if (!endpoint || endpoint === 'No endpoint') return t('toolHeader.noEndpoint');
    if (endpoint.length <= 35) return endpoint;

    const parts = endpoint.split('/');
    const pathParams = parts.filter(part => part.startsWith('{') && part.endsWith('}'));

    if (pathParams.length > 0) {
      const basePath = parts.slice(0, 3).filter(p => p);
      const lastPart = parts[parts.length - 1];
      let result = '/' + basePath.join('/');

      if (pathParams.length <= 2) {
        result += '/' + pathParams.join('/');
      } else {
        result += '/' + pathParams.slice(0, 2).join('/') + '/...';
      }

      if (lastPart && !lastPart.startsWith('{')) {
        result += '/' + lastPart;
      }
      return result;
    } else if (parts.length > 4) {
      return '/' + parts.slice(1, 3).join('/') + '/...' + (parts[parts.length - 1] ? '/' + parts[parts.length - 1] : '');
    }
    return endpoint;
  };

  return (
    <div className="p-4 bg-theme-primary/30 cursor-pointer hover:bg-theme-primary/50 transition-colors duration-200">
      {/* Desktop layout */}
      <div className="hidden sm:flex items-center justify-between" onClick={onToggleCollapse}>
        <div className="flex items-center space-x-3">
          <ChevronRight
            className={`w-4 h-4 text-theme-muted transition-transform duration-200 ${isCollapsed ? '' : 'rotate-90'}`}
          />
          {renderToolName()}
          {renderDeleteButton()}
          <span className="text-sm text-theme-muted bg-theme-primary px-2 py-1 rounded break-all">
            {tool.method} {tool.endpoint || t('toolHeader.noEndpoint')}
          </span>
        </div>

        <div className="flex items-center space-x-3" onClick={(e) => e.stopPropagation()}>
          {renderTestButton()}
          {renderTestStatus()}
        </div>
      </div>

      {/* Mobile layout */}
      <div className="sm:hidden flex flex-col space-y-4">
        {/* First line: Collapse icon and name */}
        <div
          className="w-full flex items-center space-x-2 py-2"
          onClick={() => !isEditingName && onToggleCollapse()}
        >
          <ChevronRight
            className={`w-4 h-4 text-theme-muted transition-transform duration-200 flex-shrink-0 ${isCollapsed ? '' : 'rotate-90'}`}
          />
          {renderToolName(true)}
        </div>

        {/* Second line: Delete button */}
        <div className="w-full flex justify-start py-2">
          {renderDeleteButton(true)}
        </div>

        {/* Third line: Test button and status */}
        <div className="w-full flex flex-col space-y-2 py-2">
          <div className="w-full flex justify-start" onClick={(e) => e.stopPropagation()}>
            {renderTestButton(true)}
          </div>
          {renderTestStatus(true)}
        </div>

        {/* Fourth line: HTTP Method */}
        <div className="w-full flex items-center space-x-2 py-2">
          <span className="text-sm text-theme-muted font-medium">{t('toolHeader.method')}</span>
          <span className="font-medium text-sm bg-theme-primary px-3 py-2 rounded border">
            {tool.method}
          </span>
        </div>

        {/* Fifth line: Endpoint */}
        <div className="w-full flex flex-col space-y-2 py-2">
          <span className="text-sm text-theme-muted font-medium">{t('toolHeader.endpoint')}</span>
          <div className="w-full bg-theme-primary px-3 py-2 rounded border break-all">
            <span className="text-sm opacity-75 break-words leading-tight">
              {formatEndpoint(tool.endpoint || t('toolHeader.noEndpoint'))}
            </span>
          </div>
        </div>
      </div>
    </div>
  );
};

export default ToolHeader;
