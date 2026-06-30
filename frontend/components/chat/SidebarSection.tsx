'use client';

import React from 'react';
import { useTranslations } from 'next-intl';
import { ChevronDown, ChevronRight, LucideIcon, Plus } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';

interface EmptyAction {
  onClick: () => void;
  label: string;
  icon?: LucideIcon;
}

interface SidebarSectionProps<T> {
  title: string;
  collapsed: boolean;
  onToggleCollapse: () => void;
  items: T[];
  loading: boolean;
  renderItem: (item: T, index: number, sidebarCollapsed: boolean) => React.ReactNode;
  emptyMessage: string;
  sidebarCollapsed?: boolean;
  actions?: React.ReactNode;
  isAuthenticated?: boolean;
  scrollContainerRef?: React.RefObject<HTMLDivElement>;
  customContent?: React.ReactNode;
  onScroll?: () => void;
  isLoadingMore?: boolean;
  error?: string | null;
  icon?: LucideIcon;
  iconClassName?: string;
  titleClassName?: string;
  chevronClassName?: string;
  onTitleClick?: () => void; // Optional click handler for title navigation
  emptyAction?: EmptyAction; // Action button to show when list is empty
}

export function SidebarSection<T>({
  title,
  collapsed,
  onToggleCollapse,
  items,
  loading,
  renderItem,
  emptyMessage,
  sidebarCollapsed = false,
  actions,
  isAuthenticated = true,
  scrollContainerRef,
  customContent,
  isLoadingMore,
  error,
  icon: Icon,
  iconClassName,
  titleClassName,
  chevronClassName,
  onTitleClick,
  emptyAction,
}: SidebarSectionProps<T>) {
  const t = useTranslations('chat.sidebar');

  if (!isAuthenticated) return null;

  const handleTitleClick = (e: React.MouseEvent) => {
    // Single click: toggle collapse (normal behavior)
    onToggleCollapse();
  };


  return (
    <>
      {/* Section Title */}
      <div
        className={`${sidebarCollapsed ? 'px-2' : ''} group`}
      >
        <div className={`flex items-center ${sidebarCollapsed ? 'px-0' : 'px-4'}`}>
          <div
            className="flex items-center justify-between flex-1 rounded-lg px-1 py-2 transition-all duration-200 cursor-pointer"
            onClick={handleTitleClick}
            title={collapsed ? t('expand') : t('collapse')}
          >
            <div className="flex items-center flex-1">
              {Icon && (
                <Icon className={`w-4 h-4 mr-2 transition-colors ${iconClassName || 'text-theme-primary group-hover:text-theme-primary'}`} />
              )}
              <h2 className={`text-sm font-normal transition-colors ${titleClassName || 'text-theme-primary group-hover:text-theme-primary'}`}>{title}</h2>
              {collapsed ? (
                <ChevronRight className={`w-4 h-4 ml-1 transition-all ${chevronClassName || 'text-theme-primary group-hover:text-theme-primary'}`} />
              ) : (
                <ChevronDown className={`w-4 h-4 ml-1 transition-all ${chevronClassName || 'text-theme-primary group-hover:text-theme-primary'}`} />
              )}
            </div>
            {actions && (
              <div className="flex items-center space-x-1 ml-auto opacity-100">
                {actions}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Section Content */}
      {!collapsed && (
        <>
          {customContent ? (
            customContent
          ) : (
            <>
              {scrollContainerRef ? (
                <div
                  ref={scrollContainerRef}
                  className="flex-1 min-h-0 sidebar-scroll"
                >
                  <div className={`space-y-0.5 ${sidebarCollapsed ? 'px-2' : 'px-4'}`}>
                    {loading ? (
                      <div className="p-2 text-center">
                        <LoadingSpinner size="sm" className="text-theme-secondary" />
                      </div>
                    ) : error ? (
                      <div className="p-4 text-center text-red-500">
                        <p>{error}</p>
                        <button
                          onClick={() => window.location.reload()}
                          className="mt-2 text-sm text-blue-600 hover:text-blue-800"
                        >
                          {t('retry')}
                        </button>
                      </div>
                    ) : items.length > 0 ? (
                      <>
                        {items.map((item, index) => renderItem(item, index, sidebarCollapsed))}
                        {isLoadingMore && (
                          <div className="p-2 text-center">
                            <LoadingSpinner size="sm" text="Loading more..." className="text-theme-secondary" />
                          </div>
                        )}
                      </>
                    ) : null}
                  </div>
                </div>
              ) : (
                <div className={`space-y-0.5 ${sidebarCollapsed ? 'px-2' : 'px-4'} max-h-[200px] overflow-y-auto`}>
                  {loading ? (
                    <div className="p-2 text-center">
                      <LoadingSpinner size="sm" className="text-theme-secondary" />
                    </div>
                  ) : error ? (
                    <div className="p-4 text-center text-red-500">
                      <p>{error}</p>
                      <button
                        onClick={() => window.location.reload()}
                        className="mt-2 text-sm text-blue-600 hover:text-blue-800"
                      >
                        {t('retry')}
                      </button>
                    </div>
                  ) : items.length > 0 ? (
                    items.map((item, index) => renderItem(item, index, sidebarCollapsed))
                  ) : emptyMessage ? (
                    <div className={`px-4 py-2 ${sidebarCollapsed ? 'text-sm' : 'text-base'} text-theme-secondary`}>
                      {emptyMessage}
                    </div>
                  ) : null}
                </div>
              )}
            </>
          )}
        </>
      )}
    </>
  );
}

