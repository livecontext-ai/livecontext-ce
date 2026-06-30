import React from 'react';
import { Info, AlertTriangle, CheckCircle, XCircle, LucideIcon } from 'lucide-react';

interface InfoBoxProps {
  type: 'info' | 'warning' | 'success' | 'error';
  title?: string;
  children: React.ReactNode;
  className?: string;
}

const InfoBox: React.FC<InfoBoxProps> = ({
  type,
  title,
  children,
  className = ''
}) => {
  const getIcon = (): LucideIcon => {
    switch (type) {
      case 'info':
        return Info;
      case 'warning':
        return AlertTriangle;
      case 'success':
        return CheckCircle;
      case 'error':
        return XCircle;
      default:
        return Info;
    }
  };

  const getIconColor = () => {
    switch (type) {
      case 'info':
        return 'text-blue-500 dark:text-blue-400';
      case 'warning':
        return 'text-yellow-500 dark:text-yellow-400';
      case 'success':
        return 'text-theme-primary';
      case 'error':
        return 'text-red-500 dark:text-red-400';
      default:
        return 'text-theme-primary';
    }
  };

  const getBackgroundColor = () => {
    switch (type) {
      case 'info':
        return 'bg-blue-50 dark:bg-blue-900/20 border-blue-200 dark:border-blue-700';
      case 'warning':
        return 'bg-yellow-50 dark:bg-yellow-900/20 border-yellow-200 dark:border-yellow-700';
      case 'success':
        return 'bg-theme-primary/10 border-theme-primary/30';
      case 'error':
        return 'bg-red-50 dark:bg-red-900/20 border-red-200 dark:border-red-700';
      default:
        return 'bg-theme-primary/10 border-theme-primary/30';
    }
  };

  const getTextColor = () => {
    switch (type) {
      case 'info':
        return 'text-blue-700 dark:text-blue-300';
      case 'warning':
        return 'text-yellow-700 dark:text-yellow-300';
      case 'success':
        return 'text-theme-primary';
      case 'error':
        return 'text-red-700 dark:text-red-300';
      default:
        return 'text-theme-primary';
    }
  };

  const getTitleColor = () => {
    switch (type) {
      case 'info':
        return 'text-blue-800 dark:text-blue-200';
      case 'warning':
        return 'text-yellow-800 dark:text-yellow-200';
      case 'success':
        return 'text-theme-primary';
      case 'error':
        return 'text-red-800 dark:text-red-200';
      default:
        return 'text-theme-primary';
    }
  };

  const IconComponent = getIcon();

  return (
    <div className={`border rounded-lg p-4 ${getBackgroundColor()} ${className}`}>
      <div className="flex items-start space-x-3">
        <IconComponent className={`w-5 h-5 ${getIconColor()} mt-0.5 flex-shrink-0`} />
        <div className="flex-1">
          {title && (
            <h4 className={`text-sm font-medium mb-1 ${getTitleColor()}`}>
              {title}
            </h4>
          )}
          <div className={`text-sm ${getTextColor()}`}>
            {children}
          </div>
        </div>
      </div>
    </div>
  );
};

export default InfoBox;
