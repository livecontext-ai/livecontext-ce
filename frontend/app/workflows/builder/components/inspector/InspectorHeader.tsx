import * as React from 'react';
import clsx from 'clsx';
import { Copy, Maximize, Maximize2, Minimize2, Trash2, X } from 'lucide-react';
import Image from 'next/image';

import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useTheme } from '@/components/ThemeProvider';

interface InspectorHeaderProps {
  Icon: React.ComponentType<{ className?: string; strokeWidth?: number }>;
  iconBg: string;
  badgeText: string;
  title: string;
  titleReadOnly?: boolean;
  onTitleChange: (value: string) => void;
  onDuplicate?: () => void;
  onDelete?: () => void;
  onToggleAdvanced?: () => void;
  onToggleFullscreen?: () => void;
  onClose?: () => void;
  isAdvanced: boolean;
  isFullscreen: boolean;
  disableAdvancedToggle?: boolean;
  hideActionsOnDesktop?: boolean;
  iconSlug?: string;
}

export function InspectorHeader({
  Icon,
  iconBg,
  badgeText,
  title,
  titleReadOnly = false,
  onTitleChange,
  onDuplicate,
  onDelete,
  onToggleAdvanced,
  onToggleFullscreen,
  onClose,
  isAdvanced,
  isFullscreen,
  disableAdvancedToggle,
  hideActionsOnDesktop,
  iconSlug,
}: InspectorHeaderProps) {
  const t = useTranslations('workflowBuilder.inspector');
  const { theme } = useTheme();
  const isDark = theme === 'dark';

  return (
    <div className="flex items-center gap-3 px-5 pt-5 pb-3 flex-shrink-0 relative group/header">
      <div className={clsx('flex h-11 w-11 items-center justify-center rounded-2xl relative flex-shrink-0', iconSlug ? '' : iconBg)}>
        {iconSlug ? (
          <Image
            src={`/icons/services/${iconSlug}.svg`}
            alt={title}
            width={28}
            height={28}
            className="w-7 h-7"
            onError={(e) => {
              const target = e.target as HTMLImageElement;
              target.src = isDark ? "/mcp.png" : "/mcp_black.png";
              target.className = "w-7 h-7";
            }}
          />
        ) : (
          <Icon className="h-5 w-5 text-black opacity-100" strokeWidth={1.6} />
        )}
      </div>
      <div className="flex-1 min-w-0">
        <p className="text-xs font-semibold uppercase tracking-[0.3em] text-slate-400 mb-1">{badgeText}</p>
        <Input
          className="w-full text-lg font-semibold text-slate-900 bg-transparent border-none outline-none focus:outline-none focus:ring-0 p-0 shadow-none truncate"
          value={title}
          maxLength={50}
          onChange={(event) => onTitleChange(event.target.value)}
          onClick={(e) => e.stopPropagation()}
          readOnly={titleReadOnly}
        />
      </div>
      <div className={clsx('flex items-center gap-2 flex-shrink-0', hideActionsOnDesktop ? 'lg:hidden' : undefined)}>
        {onDuplicate && (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="h-8 w-8 text-[var(--text-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)]"
            onClick={onDuplicate}
            title={t('duplicateNode')}
          >
            <Copy className="h-4 w-4" />
          </Button>
        )}
        {onDelete && (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="h-8 w-8 text-[var(--text-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)]"
            onClick={onDelete}
            title={t('deleteNode')}
          >
            <Trash2 className="h-4 w-4" />
          </Button>
        )}
        {onToggleAdvanced && !disableAdvancedToggle && (
          <>
            {!isFullscreen && (
              <Button
                type="button"
                variant="ghost"
                size="sm"
                className="h-8 w-8 p-0"
                onClick={(e) => {
                  e.stopPropagation();
                  onToggleAdvanced();
                }}
                title={isAdvanced ? t('minimize') : t('expand')}
              >
                {isAdvanced ? <Minimize2 className="h-4 w-4" /> : <Maximize2 className="h-4 w-4" />}
              </Button>
            )}
            {isAdvanced && onToggleFullscreen && (
              <Button
                type="button"
                variant="ghost"
                size="sm"
                className="h-8 w-8 p-0"
                onClick={(e) => {
                  e.stopPropagation();
                  onToggleFullscreen();
                }}
                title={isFullscreen ? t('exitFullscreen') : t('fullscreen')}
              >
                {isFullscreen ? <Minimize2 className="h-4 w-4" /> : <Maximize className="h-4 w-4" />}
              </Button>
            )}
          </>
        )}
        {onClose && (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className={clsx('h-8 w-8 text-[var(--text-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)]', hideActionsOnDesktop ? undefined : 'lg:hidden')}
            onClick={(e) => {
              e.stopPropagation();
              onClose();
            }}
            title={t('close')}
          >
            <X className="h-4 w-4" />
          </Button>
        )}
      </div>
    </div>
  );
}
