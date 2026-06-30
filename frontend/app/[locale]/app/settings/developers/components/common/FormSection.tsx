import React, { useState } from 'react';
import { ChevronDown, ChevronRight, LucideIcon } from 'lucide-react';
import { TYPOGRAPHY } from './index';

interface FormSectionProps {
  title: string;
  description?: string;
  icon?: LucideIcon;
  iconColor?: string;
  children: React.ReactNode;
  collapsible?: boolean;
  isExpanded?: boolean;
  onToggle?: () => void;
  className?: string;
  actionButton?: React.ReactNode;
}

const FormSection: React.FC<FormSectionProps> = ({
  title,
  description,
  icon: Icon,
  iconColor = 'text-theme-primary',
  children,
  collapsible = false,
  isExpanded = true,
  onToggle,
  className = '',
  actionButton
}) => {
  const [internalExpanded, setInternalExpanded] = useState(isExpanded);

  const handleToggle = () => {
    if (onToggle) {
      onToggle();
    } else {
      setInternalExpanded(!internalExpanded);
    }
  };

  const expanded = onToggle ? isExpanded : internalExpanded;

  return (
    <div className={`mb-8 ${className}`}>
      {/* Section header */}
      <div
        className={`bg-transparent mb-4 ${collapsible ? 'cursor-pointer' : ''
          }`}
        onClick={collapsible ? handleToggle : undefined}
      >
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            {Icon && (
              <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
                <Icon className={`w-5 h-5 ${iconColor}`} />
              </div>
            )}
            <div>
              <h3 className="text-lg font-semibold text-theme-primary">{title}</h3>
              {description && (
                <p className="text-sm text-theme-secondary">{description}</p>
              )}
            </div>
          </div>

          <div className="flex items-center space-x-2">
            {actionButton}
            {collapsible && (
              <>
                {expanded ? (
                  <ChevronDown className="w-5 h-5 text-theme-muted" />
                ) : (
                  <ChevronRight className="w-5 h-5 text-theme-muted" />
                )}
              </>
            )}
          </div>
        </div>
      </div>

      {/* Section content */}
      {expanded && (
        <div className="pl-0 sm:pl-2">
          {children}
        </div>
      )}
    </div>

  );
};

export default FormSection;
