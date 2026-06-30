import { useState, useCallback } from 'react';
import { useSimpleToast } from '@/components/chat/SimpleToast';

interface UseDeleteFlowOptions {
  deleteFn: () => Promise<void>;
  successMessage: string;
  errorMessage: string;
  onDeleted?: () => void;
}

export function useDeleteFlow({ deleteFn, successMessage, errorMessage, onDeleted }: UseDeleteFlowOptions) {
  const [isDeleted, setIsDeleted] = useState(false);
  const [showDeleteModal, setShowDeleteModal] = useState(false);
  const [isDeleting, setIsDeleting] = useState(false);
  const { toast, showToast, hideToast } = useSimpleToast();

  const handleDeleteClick = useCallback(() => setShowDeleteModal(true), []);
  const handleCancelDelete = useCallback(() => setShowDeleteModal(false), []);

  const handleConfirmDelete = useCallback(async () => {
    try {
      setIsDeleting(true);
      await deleteFn();
      setShowDeleteModal(false);
      showToast('success', successMessage);
      setTimeout(() => {
        setIsDeleted(true);
        onDeleted?.();
      }, 1000);
    } catch (err) {
      console.error('Delete failed:', err);
      showToast('error', errorMessage);
      setShowDeleteModal(false);
    } finally {
      setIsDeleting(false);
    }
  }, [deleteFn, successMessage, errorMessage, onDeleted, showToast]);

  return {
    isDeleted,
    showDeleteModal,
    isDeleting,
    toast,
    hideToast,
    handleDeleteClick,
    handleConfirmDelete,
    handleCancelDelete,
  };
}
