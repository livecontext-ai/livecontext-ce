import React from 'react';
import { AlertTriangle, Trash2, X } from 'lucide-react';
import { McpTool } from '../types';

interface DeleteToolModalProps {
  isOpen: boolean;
  tool: McpTool | null;
  onConfirm: () => void;
  onCancel: () => void;
  loading?: boolean;
}

export default function DeleteToolModal({
  isOpen,
  tool,
  onConfirm,
  onCancel,
  loading = false
}: DeleteToolModalProps) {
  if (!isOpen || !tool) return null;

  return (
    <div className="fixed inset-0 bg-black/20 backdrop-blur-sm flex items-center justify-center p-4 z-[9999]" onClick={onCancel}>
      <div className="max-w-md w-full max-h-[90vh] bg-theme-primary rounded-3xl shadow-2xl p-8 text-center animate-in fade-in-0 zoom-in-95 duration-300 border border-theme overflow-y-auto" onClick={(e) => e.stopPropagation()}>
        {/* Warning icon */}
        <div className="w-16 h-16 bg-red-100 rounded-full flex items-center justify-center mx-auto mb-4">
          <AlertTriangle className="w-8 h-8 text-red-600" />
        </div>

        {/* Title */}
        <h1 className="text-2xl font-semibold text-theme-primary mb-4">
          Delete Tool
        </h1>

        {/* Tool information */}
        <div className="bg-theme-tertiary rounded-2xl p-4 mb-6 border border-theme">
          <div className="text-lg font-medium text-theme-primary mb-2">
            {tool.name}
          </div>
          <div className="text-sm text-theme-secondary mb-2">
            Category: {tool.toolCategory}
          </div>
          {tool.description && (
            <div className="text-sm text-theme-secondary">
              {tool.description.length > 100
                ? `${tool.description.substring(0, 100)}...`
                : tool.description
              }
            </div>
          )}
        </div>

        {/* Action buttons */}
        <div className="flex gap-4">
          <button
            onClick={onCancel}
            disabled={loading}
            className="flex-1 bg-theme-secondary text-theme-primary py-3 px-6 rounded-xl font-semibold hover:bg-theme-secondary/80 transition-colors duration-200 border border-theme flex items-center justify-center gap-2"
          >
            <X className="w-4 h-4" />
            Cancel
          </button>

          <button
            onClick={onConfirm}
            disabled={loading}
            className="flex-1 bg-red-500 text-white py-3 px-6 rounded-xl font-semibold hover:bg-red-600 transition-colors duration-200 border border-red-500 flex items-center justify-center gap-2"
          >
            {loading ? (
              <div className="flex items-center justify-center">
                <div className="h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent" />
                <span className="ml-2">Deleting...</span>
              </div>
            ) : (
              <>
                <Trash2 className="w-4 h-4" />
                Delete Tool
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  );
}
