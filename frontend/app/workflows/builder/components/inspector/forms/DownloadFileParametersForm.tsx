'use client';

import * as React from 'react';
import { Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { OptionalSection } from '../OptionalSection';
import { useTranslations } from 'next-intl';
import type { BuilderNodeData } from '../../../types';
import type { ConnectionProps } from '../ExpressionField';

interface DownloadFileParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

/**
 * Form component for Download File node parameters.
 * Downloads a file from URL and stores it for later access.
 */
export function DownloadFileParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: DownloadFileParametersFormProps) {
  const t = useTranslations('workflowBuilder.forms');
  const [showOptionalParams, setShowOptionalParams] = React.useState(false);

  // Get values from node data
  const urlExpression: string = (data as any).downloadUrl ?? '';
  const filenameExpression: string = (data as any).downloadFilename ?? '';

  const handleUrlChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      downloadUrl: value,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleFilenameChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      downloadFilename: value,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  // Count optional params that have values
  const optionalCount = filenameExpression ? 1 : 0;

  return (
    <div className="space-y-4 pt-2">
      {/* URL input (required) */}
      <div className="flex flex-col gap-1.5">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-1.5">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('downloadFile.url')}</span>
            <Popover>
              <PopoverTrigger asChild>
                <button
                  type="button"
                  className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5"
                >
                  <Info className="h-3 w-3 text-slate-400 dark:text-slate-500" />
                </button>
              </PopoverTrigger>
              <PopoverContent className="w-72 p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                <div className="space-y-2 text-sm text-slate-600 dark:text-slate-300">
                  <p className="font-semibold text-slate-900 dark:text-slate-100">{t('downloadFile.title')}</p>
                  <p>{t('downloadFile.description')}</p>
                  <div className="space-y-1.5 text-xs">
                    <p className="font-medium">{t('downloadFile.supportedFormats')}</p>
                    <ul className="list-disc list-inside space-y-0.5 text-slate-500 dark:text-slate-400">
                      <li><span className="font-medium text-slate-600 dark:text-slate-300">{t('downloadFile.images')}</span> PNG, JPG, GIF, WebP, SVG, BMP, TIFF</li>
                      <li><span className="font-medium text-slate-600 dark:text-slate-300">{t('downloadFile.documents')}</span> PDF, Word, Excel, PowerPoint</li>
                      <li><span className="font-medium text-slate-600 dark:text-slate-300">{t('downloadFile.media')}</span> MP4, WebM, MP3, WAV, OGG</li>
                      <li><span className="font-medium text-slate-600 dark:text-slate-300">{t('downloadFile.data')}</span> JSON, CSV, XML, TXT</li>
                      <li><span className="font-medium text-slate-600 dark:text-slate-300">{t('downloadFile.archives')}</span> ZIP, TAR, GZ, RAR, 7Z</li>
                    </ul>
                  </div>
                </div>
              </PopoverContent>
            </Popover>
          </div>
          <span className="text-sm text-slate-500 dark:text-slate-400">{t('required')}</span>
        </div>
        <ExpressionEditor
          value={urlExpression}
          onChange={handleUrlChange}
          placeholder={t('downloadFile.urlPlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ url: urlExpression })}
          handleId={`download-url-${node.id}`}
          connections={connectionProps.connections}
          onHandleClick={connectionProps.handleHandleClick}
          draggingFromHandle={connectionProps.draggingFromHandle}
          onHandleMouseDown={connectionProps.handleHandleMouseDown}
          onHandleMouseUp={connectionProps.handleHandleMouseUp}
          hoveredTargetHandle={connectionProps.hoveredTargetHandle}
          onSetHandleRef={connectionProps.handleSetHandleRef}
          isRequired={true}
          readOnly={isRunMode}
        />
      </div>

      {/* Optional parameters */}
      <OptionalSection
        isOpen={showOptionalParams}
        onToggle={() => setShowOptionalParams(!showOptionalParams)}
        count={optionalCount}
      >
        <div className="flex flex-col gap-1.5">
          <div className="flex items-center justify-between">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('downloadFile.filename')}</span>
            <span className="text-sm text-slate-400 dark:text-slate-500">{t('optional')}</span>
          </div>
          <ExpressionEditor
            value={filenameExpression}
            onChange={handleFilenameChange}
            placeholder={t('downloadFile.autoDetect')}
            className="w-full"
            unknownVariables={findUnknownVariables({ filename: filenameExpression })}
            handleId={`download-filename-${node.id}`}
            connections={connectionProps.connections}
            onHandleClick={connectionProps.handleHandleClick}
            draggingFromHandle={connectionProps.draggingFromHandle}
            onHandleMouseDown={connectionProps.handleHandleMouseDown}
            onHandleMouseUp={connectionProps.handleHandleMouseUp}
            hoveredTargetHandle={connectionProps.hoveredTargetHandle}
            onSetHandleRef={connectionProps.handleSetHandleRef}
            isRequired={false}
            readOnly={isRunMode}
          />
        </div>
      </OptionalSection>
    </div>
  );
}
