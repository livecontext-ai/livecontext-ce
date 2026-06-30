import React from 'react';
import { LucideIcon } from 'lucide-react';

interface ActionButtonProps {
  variant?: 'primary' | 'secondary' | 'success' | 'danger' | 'warning';
  size?: 'sm' | 'md' | 'lg';
  icon?: LucideIcon;
  children?: React.ReactNode;
  onClick?: () => void;
  disabled?: boolean;
  type?: 'button' | 'submit' | 'reset';
  className?: string;
}

const ActionButton: React.FC<ActionButtonProps> = ({
  variant = 'primary',
  size = 'md',
  icon: Icon,
  children,
  onClick,
  disabled = false,
  type = 'button',
  className = ''
}) => {
  const getVariantClasses = () => {
    switch (variant) {
      case 'secondary':
        return 'text-theme-primary hover:bg-theme-secondary';
      case 'success':
        return 'bg-theme-primary text-theme-secondary hover:bg-theme-primary/90';
      case 'danger':
        return 'bg-red-500 dark:bg-red-600 text-white hover:bg-red-600 dark:hover:bg-red-500';
      case 'warning':
        return 'bg-yellow-500 dark:bg-yellow-600 text-white hover:bg-yellow-600 dark:hover:bg-yellow-500';
      default:
        return 'bg-theme-primary text-theme-secondary hover:bg-theme-primary/90';
    }
  };

  const getSizeClasses = () => {
    switch (size) {
      case 'sm':
        return 'px-3 py-2 text-sm';
      case 'lg':
        return 'px-6 py-3 text-lg';
      default:
        return 'px-4 py-2 text-sm';
    }
  };

  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled}
      className={`flex items-center space-x-2 font-medium rounded-lg transition-colors duration-200 disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer ${getVariantClasses()} ${getSizeClasses()} ${className}`}
    >
      {Icon && <Icon className="w-4 h-4" />}
      {children && <span>{children}</span>}
    </button>
  );
};

export default ActionButton;
