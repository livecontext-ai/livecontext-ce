import React from 'react';
import { Settings, Code, FileJson, Shield, FileText, TestTube } from 'lucide-react';
import { ToolTabsProps, TAB_CONFIG } from './types';

const ICONS = {
  config: Settings,
  pathParams: Code,
  queryParams: FileJson,
  headers: Shield,
  body: FileJson,
  response: FileText,
  test: TestTube
} as const;

const ToolTabs: React.FC<ToolTabsProps> = ({ toolKey, activeTab, onTabChange }) => {
  const renderTabButton = (
    id: string,
    label: string,
    isActive: boolean,
    variant: 'desktop' | 'medium' | 'mobile'
  ) => {
    const Icon = ICONS[id as keyof typeof ICONS];
    const sizeClasses = {
      desktop: 'px-4 py-2 text-sm space-x-2',
      medium: 'px-3 py-2 text-xs space-x-1',
      mobile: 'px-2 py-2 text-xs space-x-1'
    };
    const iconSize = variant === 'desktop' ? 'w-4 h-4' : 'w-3 h-3';

    return (
      <button
        key={id}
        onClick={() => onTabChange(id)}
        className={`flex items-center ${sizeClasses[variant]} font-medium rounded-t-lg transition-colors duration-200 whitespace-nowrap ${variant !== 'desktop' ? 'flex-shrink-0' : ''} ${
          isActive
            ? 'bg-theme-primary text-theme-secondary border-b-2 border-blue-500'
            : 'text-theme-muted hover:text-theme-primary hover:bg-theme-primary/50'
        }`}
      >
        <Icon className={iconSize} />
        <span>{label}</span>
      </button>
    );
  };

  return (
    <div className="mb-6">
      {/* Large desktop tabs - full labels */}
      <div className="hidden lg:flex space-x-1 border-b border-theme">
        {TAB_CONFIG.desktop.map(({ id, label }) =>
          renderTabButton(id, label, activeTab === id, 'desktop')
        )}
      </div>

      {/* Medium screens tabs - medium labels with scroll */}
      <div className="hidden sm:flex lg:hidden border-b border-theme">
        <div className="overflow-x-auto medium-tabs-scroll">
          <div className="flex space-x-1 min-w-max pb-1">
            {TAB_CONFIG.medium.map(({ id, label }) =>
              renderTabButton(id, label, activeTab === id, 'medium')
            )}
          </div>
        </div>
      </div>

      {/* Mobile tabs with scroll */}
      <div className="sm:hidden border-b border-theme">
        <div className="overflow-x-auto mobile-tabs-scroll">
          <div className="flex space-x-1 min-w-max pb-1">
            {TAB_CONFIG.mobile.map(({ id, label }) =>
              renderTabButton(id, label, activeTab === id, 'mobile')
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

export default ToolTabs;
