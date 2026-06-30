import React from 'react';
import { Plus, X, Info } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { ParameterListTabProps } from './types';

type ParamType = 'string' | 'number' | 'boolean' | 'array' | 'object';

interface BaseParameter {
  name: string;
  type?: ParamType;
  required?: boolean;
  description?: string;
  example?: string;
}

interface HeaderParameter {
  name: string;
  value?: string;
  required?: boolean;
}

interface BodyParameter {
  name: string;
  value?: string;
  type?: ParamType;
  description?: string;
}

const PARAM_TYPES: ParamType[] = ['string', 'number', 'boolean', 'array', 'object'];

const ParameterListTab: React.FC<ParameterListTabProps> = ({
  tool,
  toolIndex,
  onToolUpdate,
  parameterType,
  title,
  infoText,
  showTabInfo,
  onToggleTabInfo
}) => {
  const t = useTranslations('developers');
  const parameters = tool[parameterType] || [];
  const isHeader = parameterType === 'headers';
  const isBody = parameterType === 'bodyParams';

  const addParameter = () => {
    let newParam: BaseParameter | HeaderParameter | BodyParameter;

    if (isHeader) {
      newParam = { name: '', value: '', required: false };
    } else if (isBody) {
      newParam = { name: '', value: '', type: 'string' as ParamType, description: '' };
    } else {
      newParam = {
        name: '',
        type: 'string' as ParamType,
        required: parameterType === 'pathParameters',
        description: '',
        example: ''
      };
    }

    const updatedTool = {
      ...tool,
      [parameterType]: [...parameters, newParam]
    };
    onToolUpdate(toolIndex, updatedTool);
  };

  const removeParameter = (paramIndex: number) => {
    const updatedTool = {
      ...tool,
      [parameterType]: parameters.filter((_: unknown, i: number) => i !== paramIndex)
    };
    onToolUpdate(toolIndex, updatedTool);
  };

  const updateParameter = (paramIndex: number, field: string, value: string | boolean) => {
    const updatedTool = {
      ...tool,
      [parameterType]: parameters.map((p: BaseParameter | HeaderParameter | BodyParameter, i: number) =>
        i === paramIndex ? { ...p, [field]: value } : p
      )
    };
    onToolUpdate(toolIndex, updatedTool);
  };

  const renderParameterFields = (param: BaseParameter | HeaderParameter | BodyParameter, paramIndex: number) => {
    if (isHeader) {
      const headerParam = param as HeaderParameter;
      return (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div>
            <label className="block text-sm font-medium text-theme-primary mb-2">
              {t('parameterList.headerName')} *
            </label>
            <input
              type="text"
              value={headerParam.name}
              onChange={(e) => updateParameter(paramIndex, 'name', e.target.value)}
              className="w-full px-3 py-2 bg-theme-primary border border-theme rounded text-theme-primary text-sm focus:outline-none focus:ring-2 focus:ring-blue-500/50"
              placeholder={t('parameterList.headerNamePlaceholder')}
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-theme-primary mb-2">
              {t('parameterList.defaultValue')}
            </label>
            <input
              type="text"
              value={headerParam.value || ''}
              onChange={(e) => updateParameter(paramIndex, 'value', e.target.value)}
              className="w-full px-3 py-2 bg-theme-primary border border-theme rounded text-theme-primary text-sm focus:outline-none focus:ring-2 focus:ring-blue-500/50"
              placeholder={t('parameterList.headerValuePlaceholder')}
            />
          </div>
          <div className="flex items-end pb-2">
            <label className="flex items-center space-x-2 cursor-pointer">
              <input
                type="checkbox"
                checked={headerParam.required || false}
                onChange={(e) => updateParameter(paramIndex, 'required', e.target.checked)}
                className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
              />
              <span className="text-sm text-theme-primary">{t('parameterList.required')}</span>
            </label>
          </div>
        </div>
      );
    }

    if (isBody) {
      const bodyParam = param as BodyParameter;
      return (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div>
            <label className="block text-sm font-medium text-theme-primary mb-2">
              {t('parameterList.fieldName')} *
            </label>
            <input
              type="text"
              value={bodyParam.name}
              onChange={(e) => updateParameter(paramIndex, 'name', e.target.value)}
              className="w-full px-3 py-2 bg-theme-primary border border-theme rounded text-theme-primary text-sm focus:outline-none focus:ring-2 focus:ring-blue-500/50"
              placeholder={t('parameterList.fieldNamePlaceholder')}
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-theme-primary mb-2">
              {t('parameterList.type')} *
            </label>
            <select
              value={bodyParam.type || 'string'}
              onChange={(e) => updateParameter(paramIndex, 'type', e.target.value)}
              className="w-full px-3 py-2 bg-theme-primary border border-theme rounded text-theme-primary text-sm focus:outline-none focus:ring-2 focus:ring-blue-500/50"
            >
              {PARAM_TYPES.map(type => (
                <option key={type} value={type}>{type}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-sm font-medium text-theme-primary mb-2">
              {t('parameterList.defaultValue')}
            </label>
            <input
              type="text"
              value={bodyParam.value || ''}
              onChange={(e) => updateParameter(paramIndex, 'value', e.target.value)}
              className="w-full px-3 py-2 bg-theme-primary border border-theme rounded text-theme-primary text-sm focus:outline-none focus:ring-2 focus:ring-blue-500/50"
              placeholder={t('parameterList.bodyValuePlaceholder')}
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-theme-primary mb-2">
              {t('parameterList.description')}
            </label>
            <input
              type="text"
              value={bodyParam.description || ''}
              onChange={(e) => updateParameter(paramIndex, 'description', e.target.value)}
              className="w-full px-3 py-2 bg-theme-primary border border-theme rounded text-theme-primary text-sm focus:outline-none focus:ring-2 focus:ring-blue-500/50"
              placeholder={t('parameterList.bodyDescriptionPlaceholder')}
            />
          </div>
        </div>
      );
    }

    // Path or Query parameters
    const baseParam = param as BaseParameter;
    return (
      <>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mb-3">
          <div>
            <label className="block text-sm font-medium text-theme-primary mb-2">
              {t('parameterList.parameterName')} *
            </label>
            <input
              type="text"
              value={baseParam.name}
              onChange={(e) => updateParameter(paramIndex, 'name', e.target.value)}
              className="w-full px-3 py-2 bg-theme-primary border border-theme rounded text-theme-primary text-sm focus:outline-none focus:ring-2 focus:ring-blue-500/50"
              placeholder={parameterType === 'pathParameters' ? t('parameterList.pathParamPlaceholder') : t('parameterList.queryParamPlaceholder')}
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-theme-primary mb-2">
              {t('parameterList.type')} *
            </label>
            <select
              value={baseParam.type || 'string'}
              onChange={(e) => updateParameter(paramIndex, 'type', e.target.value)}
              className="w-full px-3 py-2 bg-theme-primary border border-theme rounded text-theme-primary text-sm focus:outline-none focus:ring-2 focus:ring-blue-500/50"
            >
              {PARAM_TYPES.map(type => (
                <option key={type} value={type}>{type}</option>
              ))}
            </select>
          </div>
        </div>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mb-3">
          <div>
            <label className="block text-sm font-medium text-theme-primary mb-2">
              {t('parameterList.description')}
            </label>
            <input
              type="text"
              value={baseParam.description || ''}
              onChange={(e) => updateParameter(paramIndex, 'description', e.target.value)}
              className="w-full px-3 py-2 bg-theme-primary border border-theme rounded text-theme-primary text-sm focus:outline-none focus:ring-2 focus:ring-blue-500/50"
              placeholder={t('parameterList.descriptionPlaceholder')}
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-theme-primary mb-2">
              {t('parameterList.example')}
            </label>
            <input
              type="text"
              value={baseParam.example || ''}
              onChange={(e) => updateParameter(paramIndex, 'example', e.target.value)}
              className="w-full px-3 py-2 bg-theme-primary border border-theme rounded text-theme-primary text-sm focus:outline-none focus:ring-2 focus:ring-blue-500/50"
              placeholder={parameterType === 'pathParameters' ? t('parameterList.pathExamplePlaceholder') : t('parameterList.queryExamplePlaceholder')}
            />
          </div>
        </div>
        {parameterType !== 'pathParameters' && (
          <div className="flex items-center">
            <label className="flex items-center space-x-2 cursor-pointer">
              <input
                type="checkbox"
                checked={baseParam.required || false}
                onChange={(e) => updateParameter(paramIndex, 'required', e.target.checked)}
                className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
              />
              <span className="text-sm text-theme-primary">{t('parameterList.required')}</span>
            </label>
          </div>
        )}
      </>
    );
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center space-x-2">
          {infoText && (
            <button
              type="button"
              onClick={onToggleTabInfo}
              className="p-1 rounded transition-colors duration-200 text-theme-muted hover:text-blue-500"
              title={t('parameterList.showInformation')}
            >
              <Info className="w-4 h-4" />
            </button>
          )}
          <label className="block text-sm font-medium text-theme-primary">
            {title}
          </label>
        </div>
        <Button
          type="button"
          onClick={addParameter}
          variant="ghost"
          size="icon"
          className="rounded-full w-10 h-10 min-w-[44px] min-h-[44px]"
          title={t('parameterList.addParameter')}
        >
          <Plus className="w-4 h-4" />
        </Button>
      </div>

      {showTabInfo && infoText && (
        <div className="mb-4 p-4 bg-blue-50 dark:bg-blue-900/20 rounded-lg border border-blue-200 dark:border-blue-700">
          <div className="flex items-start space-x-3">
            <Info className="w-4 h-4 text-blue-500 mt-0.5 flex-shrink-0" />
            <div className="text-sm text-blue-700 dark:text-blue-300">
              {infoText}
            </div>
          </div>
        </div>
      )}

      <div className="space-y-3">
        {parameters.length === 0 ? (
          <div className="text-center py-8 text-theme-muted">
            <p className="text-sm">{t('parameterList.noDefined', { type: title.toLowerCase() })}</p>
            <p className="text-xs mt-1">{t('parameterList.clickToAdd')}</p>
          </div>
        ) : (
          parameters.map((param: BaseParameter | HeaderParameter | BodyParameter, paramIndex: number) => (
            <div key={paramIndex} className="border border-theme rounded-lg bg-theme-primary/50 overflow-hidden">
              <div className="p-4">
                {renderParameterFields(param, paramIndex)}
              </div>
              <div className="px-4 py-2 bg-theme-primary/30 flex justify-end">
                <button
                  type="button"
                  onClick={() => removeParameter(paramIndex)}
                  className="p-1.5 text-theme-muted hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/10 rounded transition-colors duration-200"
                  title={t('parameterList.removeParameter')}
                >
                  <X className="w-4 h-4" />
                </button>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
};

export default ParameterListTab;
