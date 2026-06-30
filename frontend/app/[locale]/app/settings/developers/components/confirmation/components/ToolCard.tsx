'use client';

import React from 'react';
import { useTranslations } from 'next-intl';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { McpTool } from '../../../types';
import { RichTextarea } from '../../common';
import { ParameterBadge } from './ParameterBadge';
import { formatEndpointForMobile, RESPONSE_TYPE_COLORS } from '../utils/textHelpers';

interface ToolCardProps {
  tool: McpTool;
  toolKey: string;
  isExpanded: boolean;
  onToggle: () => void;
}

export function ToolCard({ tool, toolKey, isExpanded, onToggle }: ToolCardProps) {
  const t = useTranslations('developers');

  return (
    <div className="border border-theme rounded-xl bg-theme-tertiary overflow-hidden">
      {/* Tool header */}
      <div
        className="flex items-center p-4 cursor-pointer hover:bg-theme-background transition-colors"
        onClick={onToggle}
      >
        <div className="flex items-center space-x-3">
          {isExpanded ? (
            <ChevronDown className="w-5 h-5 text-theme-muted" />
          ) : (
            <ChevronRight className="w-5 h-5 text-theme-muted" />
          )}
          <h4 className="font-medium text-theme-primary text-base">{tool.name}</h4>
        </div>
      </div>

      {/* Tool content */}
      {isExpanded && (
        <div className="px-4 pb-4">
          <div className="space-y-4">
            {/* Description */}
            <div>
              <span className="text-theme-muted text-sm">{t('toolCard.description')}:</span>
              <div className="mt-2">
                <RichTextarea
                  value={tool.description || t('toolCard.noDescription')}
                  onChange={() => {}}
                  forcePreview={true}
                  resizable={false}
                  rows={3}
                />
              </div>
            </div>

            {/* Endpoint */}
            <div>
              <span className="text-theme-muted text-sm">{t('toolCard.endpoint')}:</span>
              <div className="font-mono bg-theme-background p-2 rounded mt-1 text-xs break-all">
                <span className="hidden sm:inline">{tool.method} {tool.endpoint}</span>
                <span className="sm:hidden">
                  <div className="flex items-center space-x-1">
                    <span className="font-medium">{tool.method}</span>
                    <span className="text-gray-400">•</span>
                  </div>
                  <div className="mt-0.5">
                    <span className="opacity-75 break-words leading-tight">
                      {formatEndpointForMobile(tool.endpoint || '')}
                    </span>
                  </div>
                </span>
              </div>
            </div>
          </div>

          {/* Path Parameters */}
          {tool.pathParameters && tool.pathParameters.length > 0 && (
            <ParameterSection title={t('toolCard.pathParameters')} parameters={tool.pathParameters} />
          )}

          {/* Query Parameters */}
          {tool.queryParameters && tool.queryParameters.length > 0 && (
            <ParameterSection
              title={t('toolCard.queryParameters')}
              parameters={tool.queryParameters}
              showDefaults
              showAllowedValues
            />
          )}

          {/* Headers */}
          {tool.headers && tool.headers.length > 0 && (
            <HeadersSection headers={tool.headers} />
          )}

          {/* Body Parameters */}
          {tool.bodyParams && tool.bodyParams.length > 0 && (
            <BodyParamsSection bodyParams={tool.bodyParams} />
          )}

          {/* Response Section */}
          {(tool.response.description || tool.bodySchema || tool.response.success) && (
            <ResponseSection tool={tool} />
          )}

          {/* Test Results */}
          {tool.testResult && (
            <TestResultSection testResult={tool.testResult} />
          )}
        </div>
      )}
    </div>
  );
}

// Sub-components for ToolCard

interface ParameterSectionProps {
  title: string;
  parameters: Array<{
    name: string;
    type: string;
    required?: boolean;
    description?: string;
    example?: string;
    defaultValue?: string;
    allowedValues?: string[];
  }>;
  showDefaults?: boolean;
  showAllowedValues?: boolean;
}

function ParameterSection({ title, parameters, showDefaults, showAllowedValues }: ParameterSectionProps) {
  const t = useTranslations('developers');
  return (
    <div className="mt-4">
      <h5 className="font-medium text-theme-primary mb-2">{title}</h5>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
        {parameters.map((param, paramIndex) => (
          <div key={paramIndex} className="p-2 bg-theme-background rounded text-xs">
            <div className="flex items-center justify-between mb-1">
              <span className="font-medium text-theme-primary">{param.name}</span>
              <div className="flex items-center space-x-2">
                <ParameterBadge type={param.type} />
                {param.required && (
                  <span className="px-1 py-0.5 bg-red-100 text-red-800 rounded text-xs">{t('toolCard.required')}</span>
                )}
              </div>
            </div>
            {param.description && (
              <div className="text-theme-muted text-xs">{param.description}</div>
            )}
            {param.example && (
              <div className="text-theme-muted text-xs mt-1">
                {t('toolCard.exampleLabel')}: <span className="font-mono">{param.example}</span>
              </div>
            )}
            {showDefaults && param.defaultValue && (
              <div className="text-theme-muted text-xs mt-1">
                {t('toolCard.defaultLabel')}: <span className="font-mono">{param.defaultValue}</span>
              </div>
            )}
            {showAllowedValues && param.allowedValues && param.allowedValues.length > 0 && (
              <div className="text-theme-muted text-xs mt-1">
                {t('toolCard.allowedValues')}: <span className="font-mono">{param.allowedValues.join(', ')}</span>
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

interface HeadersSectionProps {
  headers: Array<{
    name: string;
    value: string;
    required?: boolean;
    description?: string;
  }>;
}

function HeadersSection({ headers }: HeadersSectionProps) {
  const t = useTranslations('developers');
  return (
    <div className="mt-4">
      <h5 className="font-medium text-theme-primary mb-2">{t('toolCard.headers')}</h5>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
        {headers.map((header, headerIndex) => (
          <div key={headerIndex} className="p-2 bg-theme-background rounded text-xs">
            <div className="flex items-center justify-between mb-1">
              <span className="font-medium text-theme-primary">{header.name}</span>
              {header.required && (
                <span className="px-1 py-0.5 bg-red-100 text-red-800 rounded text-xs">{t('toolCard.required')}</span>
              )}
            </div>
            <div className="text-theme-muted text-xs">{header.value}</div>
            {header.description && (
              <div className="text-theme-muted text-xs mt-1">{header.description}</div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

interface BodyParamsSectionProps {
  bodyParams: Array<{
    name: string;
    type: string;
    required?: boolean;
    description?: string;
    value?: string;
  }>;
}

function BodyParamsSection({ bodyParams }: BodyParamsSectionProps) {
  const t = useTranslations('developers');
  return (
    <div className="mt-4">
      <h5 className="font-medium text-theme-primary mb-2">{t('toolCard.bodyParameters')}</h5>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
        {bodyParams.map((bodyParam, bodyIndex) => (
          <div key={bodyIndex} className="p-2 bg-theme-background rounded text-xs">
            <div className="flex items-center justify-between mb-1">
              <span className="font-medium text-theme-primary">{bodyParam.name}</span>
              <div className="flex items-center space-x-2">
                <span className={`px-2 py-0.5 rounded text-xs ${
                  bodyParam.type === 'file' ? 'bg-blue-100 text-blue-800' : 'bg-green-100 text-green-800'
                }`}>
                  {bodyParam.type}
                </span>
                {bodyParam.required && (
                  <span className="px-1 py-0.5 bg-red-100 text-red-800 rounded text-xs">{t('toolCard.required')}</span>
                )}
              </div>
            </div>
            {bodyParam.description && (
              <div className="text-theme-muted text-xs">{bodyParam.description}</div>
            )}
            {bodyParam.value && (
              <div className="mt-1">
                <span className="text-theme-muted text-xs">{t('toolCard.value')}:</span>
                <div className="font-mono bg-theme-secondary p-1 rounded mt-1 text-xs break-all">
                  {bodyParam.value}
                </div>
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

interface ResponseSectionProps {
  tool: McpTool;
}

function ResponseSection({ tool }: ResponseSectionProps) {
  const t = useTranslations('developers');
  const responseTypeColor = RESPONSE_TYPE_COLORS[tool.response.type || 'json'] || 'bg-gray-100 text-gray-800';

  return (
    <div className="mt-4">
      <h5 className="font-medium text-theme-primary mb-3">{t('toolCard.response')}</h5>
      <div className="space-y-3">
        {/* Response Type */}
        <div className="flex items-center space-x-2">
          <span className="text-theme-muted text-sm">{t('toolCard.type')}:</span>
          <span className={`px-2 py-1 rounded text-xs font-medium ${responseTypeColor}`}>
            {tool.response.type?.toUpperCase() || 'JSON'}
          </span>
        </div>

        {/* Response Description */}
        {tool.response.description && (
          <div className="flex items-start space-x-2">
            <span className="text-theme-muted text-sm">{t('toolCard.description')}:</span>
            <span className="text-theme-primary text-sm">{tool.response.description}</span>
          </div>
        )}

        {/* Response Schema */}
        {tool.bodySchema && (
          <div className="flex items-start space-x-2">
            <span className="text-theme-muted text-sm">{t('toolCard.schema')}:</span>
            <div className="flex-1">
              <div className="font-mono bg-theme-secondary p-2 rounded text-xs overflow-auto max-h-32">
                <pre className="whitespace-pre-wrap break-words">
                  {(() => {
                    try {
                      const parsed = JSON.parse(tool.bodySchema);
                      return JSON.stringify(parsed, null, 2);
                    } catch {
                      return tool.bodySchema;
                    }
                  })()}
                </pre>
              </div>
            </div>
          </div>
        )}

        {/* Response Example */}
        {tool.response.success && (
          <div className="flex items-start space-x-2">
            <span className="text-theme-muted text-sm">{t('toolCard.exampleLabel')}:</span>
            <div className="flex-1">
              <div className="font-mono bg-theme-secondary p-2 rounded text-xs overflow-auto max-h-32">
                <ResponsePreview type={tool.response.type} success={tool.response.success} />
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

interface ResponsePreviewProps {
  type?: string;
  success: any;
}

function ResponsePreview({ type, success }: ResponsePreviewProps) {
  const t = useTranslations('developers');
  if (type === 'json') {
    return (
      <pre className="whitespace-pre-wrap break-words">
        {JSON.stringify(success, null, 2)}
      </pre>
    );
  }

  if (type === 'xml' || type === 'csv' || type === 'text') {
    return (
      <pre className="whitespace-pre-wrap break-words">
        {typeof success === 'string' ? success : JSON.stringify(success, null, 2)}
      </pre>
    );
  }

  if (type === 'html') {
    return (
      <div className="border border-gray-300 rounded p-2 bg-white text-black">
        <div className="text-xs text-gray-500 mb-2">{t('toolCard.htmlPreview')}:</div>
        <div dangerouslySetInnerHTML={{ __html: typeof success === 'string' ? success : JSON.stringify(success, null, 2) }} />
      </div>
    );
  }

  if (type === 'binary') {
    return (
      <div className="text-center py-4">
        <div className="text-lg mb-2">📁</div>
        <div className="text-theme-muted">
          {typeof success === 'object' && success.size
            ? t('toolCard.binaryFileSize', { size: success.size })
            : t('toolCard.binaryFileReceived')
          }
        </div>
        {typeof success === 'object' && success.type && (
          <div className="text-xs text-theme-muted mt-1">
            {t('toolCard.type')}: {success.type}
          </div>
        )}
      </div>
    );
  }

  return (
    <pre className="whitespace-pre-wrap break-words">
      {JSON.stringify(success, null, 2)}
    </pre>
  );
}

interface TestResultSectionProps {
  testResult: {
    status: number;
    responseTime: number;
    error?: string;
  };
}

function TestResultSection({ testResult }: TestResultSectionProps) {
  const t = useTranslations('developers');
  return (
    <div className="mt-4">
      <h5 className="font-medium text-theme-primary mb-2">{t('toolCard.lastTest')}</h5>
      <div className="p-2 bg-theme-background rounded text-xs">
        <div className="flex items-center space-x-4">
          <div className={`px-2 py-1 rounded ${
            testResult.status === 200 ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'
          }`}>
            {t('toolCard.status')}: {testResult.status}
          </div>
          <div className="text-theme-muted">
            {t('toolCard.responseTime')}: {testResult.responseTime}ms
          </div>
        </div>
        {testResult.error && (
          <div className="text-red-600 mt-1">{testResult.error}</div>
        )}
      </div>
    </div>
  );
}
