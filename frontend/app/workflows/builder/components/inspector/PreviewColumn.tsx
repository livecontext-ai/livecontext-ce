import * as React from 'react';
import { useTranslations } from 'next-intl';
import { InspectorColumn } from './InspectorColumn';
import type { BuilderNodeData } from '../../types';
import { InterfacePreview } from '@/components/InterfacePreview';
import { useInterfaceById } from '../../hooks/useInterfaces';

interface PreviewColumnProps {
  node: { data: BuilderNodeData; id: string } | null;
  allNodes?: any[];
  edges?: any[];
  embedded?: boolean;
}

export const PreviewColumn = ({ node, embedded = false }: PreviewColumnProps) => {
  const t = useTranslations('workflowBuilder.inspector');
  // Get interface HTML template from node data
  const interfaceData = (node?.data as any)?.interfaceData || {};
  const interfaceId = interfaceData.interfaceId || null;
  const editorExpression = interfaceData.editorExpression || '';

  // Fetch interface details from DB (cssTemplate may not be in node data yet)
  const { data: interfaceDetails } = useInterfaceById(interfaceId);

  // Fallback chain: node data → DB
  const cssTemplate = interfaceData.cssTemplate || interfaceDetails?.cssTemplate || '';
  // The interface's own shape: this is the surface where the author WRITES the HTML, so it must
  // show the frame that HTML is being written for.
  const format = interfaceDetails?.format;

  // If embedded (inside output column flex container), use flex-1 to fill available space
  if (embedded) {
    if (!editorExpression || editorExpression.trim() === '') {
      return (
        <div className="flex-1 flex items-center justify-center text-center text-slate-400 dark:text-slate-500 text-sm p-3">
          {t('noTemplatePreview')}
        </div>
      );
    }
    return (
      <div className="flex-1 flex flex-col min-h-0 p-3">
        <div className="flex-1 bg-white dark:bg-slate-900 rounded-xl overflow-hidden flex flex-col min-h-0">
          <InterfacePreview
            htmlTemplate={editorExpression}
            cssTemplate={cssTemplate || undefined}
            className="w-full flex-1 min-h-0"
            autoFit={false}
            format={format}
          />
        </div>
      </div>
    );
  }

  // Non-embedded: standalone column with scroll
  const previewContent = (
    <div className="flex flex-col h-full space-y-2 pt-2">
      {!editorExpression || editorExpression.trim() === '' ? (
        <div className="text-center py-8 text-slate-400 dark:text-slate-500 text-sm">
          {t('noTemplatePreview')}
        </div>
      ) : (
        <div className="flex-1 flex flex-col">
          <div className="flex-1 bg-white dark:bg-slate-900 rounded-xl overflow-hidden">
            <InterfacePreview
              htmlTemplate={editorExpression}
              cssTemplate={cssTemplate || undefined}
              format={format}
              className="w-full h-full"
              autoFit={false}
            />
          </div>
        </div>
      )}
    </div>
  );

  return (
    <InspectorColumn
      title={t('preview')}
      showRightBorder={false}
    >
      {previewContent}
    </InspectorColumn>
  );
};
