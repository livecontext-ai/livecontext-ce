'use client';

import React from 'react';
import { Trash2, Pencil, Globe2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { useTranslations } from 'next-intl';
import type { CustomApiSummary } from '@/lib/api/orchestrator';
import { useAuthedObjectUrl } from '@/hooks/useAuthedObjectUrl';

interface CustomApiCardProps {
  api: CustomApiSummary;
  onEdit: (api: CustomApiSummary) => void;
  onDelete: (api: CustomApiSummary) => void;
}

export function CustomApiCard({ api, onEdit, onDelete }: CustomApiCardProps) {
  const t = useTranslations('customApis');
  // Icon loaded via a header-authenticated fetch → blob: URL (no token in the URL).
  const { url: authSrc, error: imgError } = useAuthedObjectUrl(api.iconUrl || null);

  const toolLabel =
    api.toolCount === 0
      ? t('card.noTools')
      : api.toolCount === 1
        ? t('card.tool')
        : t('card.tools', { count: api.toolCount });

  return (
    <div className="border border-theme rounded-lg p-4 hover:border-accent transition-colors">
      <div className="flex items-start justify-between gap-4">
        <div className="flex items-start gap-3 min-w-0 flex-1">
          {/* API Icon */}
          <div className="shrink-0 h-9 w-9 rounded-lg bg-muted flex items-center justify-center overflow-hidden">
            {authSrc && !imgError ? (
              <img
                src={authSrc}
                alt={api.name}
                className="h-9 w-9 object-contain"
              />
            ) : (
              <Globe2 className="h-4 w-4 text-theme-secondary" />
            )}
          </div>
          <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 mb-1">
            <h3 className="text-sm font-semibold text-theme-primary truncate">
              {api.name}
            </h3>
            <Badge variant="outline" className="text-xs shrink-0">
              {toolLabel}
            </Badge>
          </div>
          {api.description && (
            <p className="text-sm text-theme-secondary line-clamp-2 mb-1">
              {api.description}
            </p>
          )}
          <p className="text-xs text-theme-secondary truncate">{api.baseUrl}</p>
          </div>
        </div>
        <div className="flex items-center gap-1 shrink-0">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => onEdit(api)}
            className="h-8 w-8 p-0"
            aria-label={`${t('edit')}: ${api.name}`}
          >
            <Pencil className="h-3.5 w-3.5" />
          </Button>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => onDelete(api)}
            className="h-8 w-8 p-0 text-red-500 hover:text-red-600 hover:bg-red-50 dark:hover:bg-red-950"
            aria-label={`${t('delete')}: ${api.name}`}
          >
            <Trash2 className="h-3.5 w-3.5" />
          </Button>
        </div>
      </div>
    </div>
  );
}
