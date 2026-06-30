'use client';

import React, { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { CheckCircle, XCircle, X } from 'lucide-react';

interface SimpleToastProps {
  type: 'success' | 'error';
  message: string;
  isVisible: boolean;
  onClose: () => void;
  duration?: number;
}

export function SimpleToast({
  type,
  message,
  isVisible,
  onClose,
  duration = 3000
}: SimpleToastProps) {
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  useEffect(() => {
    if (isVisible && duration > 0) {
      const timer = setTimeout(onClose, duration);
      return () => clearTimeout(timer);
    }
  }, [isVisible, duration, onClose]);

  if (!isVisible || !mounted) return null;

  const config = {
    success: {
      icon: CheckCircle,
      bgClass: 'bg-green-50 dark:bg-green-900/30 border-green-200 dark:border-green-800',
      iconClass: 'text-green-600 dark:text-green-400',
      textClass: 'text-green-800 dark:text-green-200'
    },
    error: {
      icon: XCircle,
      bgClass: 'bg-red-50 dark:bg-red-900/30 border-red-200 dark:border-red-800',
      iconClass: 'text-red-600 dark:text-red-400',
      textClass: 'text-red-800 dark:text-red-200'
    }
  }[type];

  const Icon = config.icon;

  return createPortal(
    <div className="fixed top-4 right-4 z-[9999] animate-in slide-in-from-top-full duration-300">
      <div className={`flex items-center gap-3 px-4 py-3 rounded-xl border ${config.bgClass} shadow-lg max-w-sm`}>
        <Icon className={`w-5 h-5 flex-shrink-0 ${config.iconClass}`} />
        <span className={`text-sm ${config.textClass}`}>{message}</span>
        <button
          onClick={onClose}
          className="flex-shrink-0 ml-2 text-gray-400 hover:text-gray-600 dark:hover:text-gray-300 transition-colors"
        >
          <X className="w-4 h-4" />
        </button>
      </div>
    </div>,
    document.body
  );
}

// Hook for managing simple toast
export function useSimpleToast() {
  const [toast, setToast] = useState<{
    type: 'success' | 'error';
    message: string;
  } | null>(null);

  const showToast = React.useCallback((type: 'success' | 'error', message: string) => {
    setToast({ type, message });
  }, []);

  const hideToast = React.useCallback(() => {
    setToast(null);
  }, []);

  return {
    toast,
    showToast,
    hideToast
  };
}
