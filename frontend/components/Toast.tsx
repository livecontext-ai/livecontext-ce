import React, { useEffect } from 'react';
import { CheckCircle, XCircle, AlertCircle, Info, X } from 'lucide-react';

export interface ToastProps {
  id: string;
  type: 'success' | 'error' | 'warning' | 'info';
  title: string;
  message: string;
  duration?: number;
  onClose: (id: string) => void;
}

export default function Toast({ id, type, title, message, duration = 5000, onClose }: ToastProps) {
  useEffect(() => {
    if (duration > 0) {
      const timer = setTimeout(() => {
        onClose(id);
      }, duration);

      return () => clearTimeout(timer);
    }
  }, [id, duration, onClose]);

  const config = {
    success: {
      icon: CheckCircle,
      iconClass: 'text-green-600',
      bgClass: 'bg-green-50 border-green-200',
      iconBgClass: 'bg-green-100',
      titleClass: 'text-green-800',
      messageClass: 'text-green-700'
    },
    error: {
      icon: XCircle,
      iconClass: 'text-red-600',
      bgClass: 'bg-red-50 border-red-200',
      iconBgClass: 'bg-red-100',
      titleClass: 'text-red-800',
      messageClass: 'text-red-700'
    },
    warning: {
      icon: AlertCircle,
      iconClass: 'text-yellow-600',
      bgClass: 'bg-yellow-50 border-yellow-200',
      iconBgClass: 'bg-yellow-100',
      titleClass: 'text-yellow-800',
      messageClass: 'text-yellow-700'
    },
    info: {
      icon: Info,
      iconClass: 'text-blue-600',
      bgClass: 'bg-blue-50 border-blue-200',
      iconBgClass: 'bg-blue-100',
      titleClass: 'text-blue-800',
      messageClass: 'text-blue-700'
    }
  }[type];

  const Icon = config.icon;

  return (
    <div
      role={type === 'error' ? 'alert' : 'status'}
      className={`max-w-sm w-full ${config.bgClass} border rounded-lg shadow-lg p-4 animate-in slide-in-from-right-full duration-300`}
    >
      <div className="flex items-start">
        <div className={`flex-shrink-0 w-8 h-8 ${config.iconBgClass} rounded-full flex items-center justify-center mr-3`}>
          <Icon className={`w-5 h-5 ${config.iconClass}`} />
        </div>
        <div className="flex-1 min-w-0">
          <h4 className={`text-sm font-semibold ${config.titleClass}`}>
            {title}
          </h4>
          <p className={`text-sm ${config.messageClass} mt-1`}>
            {message}
          </p>
        </div>
        <button
          onClick={() => onClose(id)}
          className="flex-shrink-0 ml-2 text-gray-400 hover:text-gray-600 transition-colors"
        >
          <X className="w-4 h-4" />
        </button>
      </div>
    </div>
  );
}

// Hook pour gerer les toasts
export interface ToastData {
  id: string;
  type: 'success' | 'error' | 'warning' | 'info';
  title: string;
  message: string;
  duration?: number;
}

export function useToast() {
  const [toasts, setToasts] = React.useState<ToastData[]>([]);

  const addToast = React.useCallback((toast: Omit<ToastData, 'id'>) => {
    const id = Math.random().toString(36).substr(2, 9);
    setToasts(prev => [...prev, { ...toast, id }]);
  }, []);

  const removeToast = React.useCallback((id: string) => {
    setToasts(prev => prev.filter(toast => toast.id !== id));
  }, []);

  const clearAllToasts = React.useCallback(() => {
    setToasts([]);
  }, []);

  return {
    toasts,
    addToast,
    removeToast,
    clearAllToasts
  };
}
