'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Plus, ArrowUp, Square, ChevronDown, Sparkles } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { WelcomeTitle } from '@/app/shared/components';
import { TRIGGER_TYPES } from './inspector/nodeTypes';
import type { WorkflowSuggestion } from '../constants/workflowSuggestions';
import type { PaletteDragItem } from '../types';
import type { XYPosition } from 'reactflow';

interface EmptyCanvasChatProps {
  chatInput: string;
  onChatInputChange: (e: React.ChangeEvent<HTMLTextAreaElement>) => void;
  onSendMessage: () => void;
  onStopStream: () => void;
  isStreaming: boolean;
  displayedSuggestions: WorkflowSuggestion[];
  typingSuggestionId: string | null;
  onSuggestionClick: (suggestion: WorkflowSuggestion) => void;
  onCreateNode: (item: PaletteDragItem, position: XYPosition) => void;
  onGetViewportCenter?: () => XYPosition | null;
  isMobile?: boolean;
}

export function EmptyCanvasChat({
  chatInput,
  onChatInputChange,
  onSendMessage,
  onStopStream,
  isStreaming,
  displayedSuggestions,
  typingSuggestionId,
  onSuggestionClick,
  onCreateNode,
  onGetViewportCenter,
  isMobile = false,
}: EmptyCanvasChatProps) {
  const t = useTranslations('workflowBuilder.canvas');
  const textareaRef = React.useRef<HTMLTextAreaElement>(null);
  const [showTriggerDropdown, setShowTriggerDropdown] = React.useState(false);
  const triggerDropdownRef = React.useRef<HTMLDivElement>(null);

  // Auto-resize textarea when content changes
  React.useEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;
    textarea.style.height = '120px';
    const scrollHeight = textarea.scrollHeight;
    textarea.style.height = `${Math.min(scrollHeight, 600)}px`;
  }, [chatInput]);

  // Close trigger dropdown when clicking outside
  React.useEffect(() => {
    if (!showTriggerDropdown) return;

    const handleClickOutside = (event: MouseEvent) => {
      if (triggerDropdownRef.current && !triggerDropdownRef.current.contains(event.target as Element)) {
        setShowTriggerDropdown(false);
      }
    };

    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [showTriggerDropdown]);

  const handleTriggerSelect = React.useCallback((triggerType: typeof TRIGGER_TYPES[0]) => {
    setShowTriggerDropdown(false);
    const center = onGetViewportCenter?.() || { x: 400, y: 200 };

    const paletteItem: PaletteDragItem = {
      id: triggerType.id,
      label: triggerType.name,
      description: triggerType.description,
      kind: 'entry',
      nodeType: 'flowNode',
    };

    onCreateNode(paletteItem, center);
  }, [onCreateNode, onGetViewportCenter]);

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      onSendMessage();
    }
  };

  const textSize = isMobile ? 'text-xs' : 'text-sm';
  const iconSize = isMobile ? 'h-3.5 w-3.5' : 'h-4 w-4';

  return (
    <div className="w-full px-4 mx-auto" style={{ maxWidth: 'min(896px, 100%)' }}>
      <div className={cn(
        "relative basis-auto flex-col shrink flex flex-col justify-end",
        !isMobile && "sm:min-h-[52svh]"
      )}>
        {/* Re-enable pointer events only on the visible composer/title column - the
            wrapping Panel is pointer-events:none so the empty regions around it
            (e.g. the top-right "Add node" toolbox button) stay clickable. */}
        <div className="flex flex-col items-center" style={{ pointerEvents: 'auto' }}>
          {!isMobile && (
            <div className="text-center max-w-md mx-auto mb-8">
              <WelcomeTitle>{t('emptyTitle')}</WelcomeTitle>
            </div>
          )}

          <div className="w-full relative">
            {!isMobile && (
              <div className="absolute inset-x-4 -top-4 h-4 rounded-t-[32px] bg-gradient-to-r from-transparent via-white/80 to-transparent blur-xl opacity-40 dark:from-transparent dark:via-white/10 dark:to-transparent" />
            )}
            <div className="relative flex items-end justify-center">
              <div className="w-full mx-auto" style={{ maxWidth: 'min(768px, 100%)' }}>
                <div className="p-4">
                  <div
                    className="bg-theme-primary border border-gray-300/70 dark:border-gray-600/70 rounded-2xl shadow-lg hover:shadow-xl transition-all duration-300 overflow-hidden relative"
                    style={{ borderRadius: '28px', boxShadow: 'rgba(0, 0, 0, 0.05) 0px 2px 4px -1px, rgba(0, 0, 0, 0.03) 0px 1px 2px -1px' }}
                  >
                    <div className="grid gap-2 p-2.5 items-end" style={{ gridTemplateAreas: '"leading primary trailing"', gridTemplateColumns: 'auto 1fr auto' }}>
                      <div className="flex items-center gap-1" style={{ gridArea: 'leading' }}>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="w-8 h-8 rounded-full"
                          title={t('toolsAttachments')}
                        >
                          <Plus className="w-4 h-4" />
                        </Button>
                      </div>
                      <div style={{ gridArea: 'primary' }}>
                        <div className="relative flex items-center">
                          <textarea
                            ref={textareaRef}
                            placeholder={t('messagePlaceholder')}
                            value={chatInput}
                            onChange={onChatInputChange}
                            onKeyDown={handleKeyDown}
                            className="composer-textarea w-full min-h-[120px] max-h-[600px] py-2 bg-transparent text-theme-primary placeholder-theme-muted/80 dark:placeholder-theme-muted/90 focus:outline-none resize-none break-words overflow-wrap-anywhere transition-all duration-200"
                            rows={1}
                            style={{ minHeight: '120px', maxHeight: '600px', scrollbarWidth: 'thin' }}
                          />
                        </div>
                      </div>
                      <div className="flex items-center gap-2" style={{ gridArea: 'trailing' }}>
                        <Button
                          type="button"
                          variant={isStreaming ? 'destructive' : 'contrast'}
                          size="icon"
                          onClick={isStreaming ? onStopStream : onSendMessage}
                          disabled={!isStreaming && !chatInput.trim()}
                          className="h-9 w-9 flex-shrink-0 shadow-none hover:shadow-none"
                          title={isStreaming ? t('stop') : t('sendMessage')}
                        >
                          {isStreaming ? <Square className="w-5 h-5" /> : <ArrowUp className="w-5 h-5" />}
                        </Button>
                      </div>
                    </div>
                  </div>
                  {/* Suggestion buttons and trigger selector below composer */}
                  <div className="flex flex-wrap gap-2 justify-center items-center px-4 pb-4 pt-2">
                    {/* Trigger selector dropdown */}
                    <div className="relative" ref={triggerDropdownRef}>
                      <button
                        onClick={() => setShowTriggerDropdown(!showTriggerDropdown)}
                        className={cn(
                          "flex items-center gap-1.5 px-3 py-1.5 rounded-full",
                          "bg-white/80 dark:bg-gray-800/80 backdrop-blur",
                          "border border-slate-200/80 dark:border-slate-700/80",
                          `${textSize} font-medium text-slate-600 dark:text-slate-400`,
                          "hover:bg-slate-50 dark:hover:bg-gray-700/80",
                          "hover:border-slate-300 dark:hover:border-slate-600",
                          "transition-all duration-200"
                        )}
                      >
                        <Plus className={iconSize} />
                        <span>{t('trigger')}</span>
                        <ChevronDown className={cn("h-3 w-3 transition-transform", showTriggerDropdown && "rotate-180")} />
                      </button>
                      {/* Dropdown menu */}
                      {showTriggerDropdown && (
                        <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 w-48 py-1 bg-white dark:bg-gray-800 rounded-xl shadow-lg border border-slate-200 dark:border-slate-700 z-50">
                          {TRIGGER_TYPES.map((trigger) => {
                            const TriggerIcon = trigger.icon;
                            return (
                              <button
                                key={trigger.id}
                                onClick={() => handleTriggerSelect(trigger)}
                                className="w-full flex items-center gap-2 px-3 py-2 text-sm text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
                              >
                                <TriggerIcon className="h-4 w-4 text-slate-600 dark:text-slate-400" />
                                <span>{t(`triggerTypes.${trigger.id}`)}</span>
                              </button>
                            );
                          })}
                        </div>
                      )}
                    </div>
                    {/* Divider */}
                    <div className="h-4 w-px bg-slate-200 dark:bg-slate-700" />
                    {/* Suggestion buttons */}
                    {displayedSuggestions.map((suggestion) => {
                      const Icon = suggestion.icon;
                      const isActive = typingSuggestionId === suggestion.id;
                      return (
                        <button
                          key={suggestion.id}
                          onClick={() => onSuggestionClick(suggestion)}
                          className={cn(
                            "relative overflow-hidden flex items-center gap-1.5 px-3 py-1.5 rounded-full",
                            "bg-white/80 dark:bg-gray-800/80 backdrop-blur",
                            "border border-slate-200/80 dark:border-slate-700/80",
                            `${textSize} font-medium text-slate-600 dark:text-slate-400`,
                            "hover:bg-slate-50 dark:hover:bg-gray-700/80",
                            "hover:border-slate-300 dark:hover:border-slate-600",
                            "transition-all duration-200",
                            isActive && "ring-2 ring-slate-400 ring-offset-1 dark:ring-offset-gray-900"
                          )}
                        >
                          {/* Shimmer effect when active */}
                          {isActive && (
                            <div
                              className="absolute inset-0 pointer-events-none rounded-full"
                              style={{
                                background: 'linear-gradient(90deg, transparent 0%, rgba(148, 163, 184, 0.4) 50%, transparent 100%)',
                                backgroundSize: '200% 100%',
                                animation: 'shimmer-scan 2.5s ease-in-out infinite',
                              }}
                            />
                          )}
                          <Icon className={cn(iconSize, "relative z-10")} />
                          <span className="relative z-10">{suggestion.label}</span>
                          {isActive && (
                            <Sparkles className="h-3 w-3 text-slate-500 animate-pulse relative z-10" />
                          )}
                        </button>
                      );
                    })}
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
