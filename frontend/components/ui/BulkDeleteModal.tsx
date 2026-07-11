'use client';

import type { ReactNode } from 'react';
import { Button } from '@/components/ui/button';
import { Trash2 } from 'lucide-react';

interface BulkDeleteModalProps {
  isOpen: boolean;
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  onConfirm: () => void;
  onCancel: () => void;
  isConfirming?: boolean;
  /** Header icon - defaults to a trash can. Pass another icon for non-delete confirmations (e.g. cancel). */
  icon?: ReactNode;
}

export function BulkDeleteModal({
  isOpen,
  title,
  message,
  confirmLabel,
  cancelLabel,
  onConfirm,
  onCancel,
  isConfirming,
  icon,
}: BulkDeleteModalProps) {
  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={onCancel}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="text-center mb-6">
          <div className="w-16 h-16 bg-red-100 dark:bg-red-950/40 rounded-full flex items-center justify-center mx-auto mb-4">
            {icon ?? <Trash2 className="w-8 h-8 text-red-500" />}
          </div>
          <h3 className="text-2xl font-semibold text-theme-primary">{title}</h3>
        </div>
        <p className="text-theme-secondary text-center mb-8">{message}</p>
        <div className="flex gap-3">
          <Button variant="outline" onClick={onCancel} disabled={isConfirming} className="flex-1">
            {cancelLabel ?? 'Cancel'}
          </Button>
          <Button variant="destructive" onClick={onConfirm} disabled={isConfirming} className="flex-1">
            {confirmLabel ?? 'Delete'}
          </Button>
        </div>
      </div>
    </div>
  );
}
