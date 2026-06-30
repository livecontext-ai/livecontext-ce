import { useState, useCallback, useRef, MutableRefObject } from 'react';
import { useNavigationGuard } from '@/contexts/NavigationGuardContext';

/**
 * Hook to manage unsaved changes state and modal interactions.
 *
 * This hook provides a complete solution for handling unsaved changes:
 * - Tracks dirty state
 * - Shows confirmation modal on navigation/refresh
 * - Handles save/discard/cancel actions
 * - Integrates with NavigationGuardContext
 *
 * @param saveCallback - Optional async function to save changes. If not provided,
 *                       you can pass a saveRef that will be populated later.
 *
 * @returns Object containing:
 *   - isDirty: Whether there are unsaved changes
 *   - setIsDirty: Function to mark as dirty/clean
 *   - showModal: Whether the unsaved changes modal should be shown
 *   - isSaving: Whether save is in progress
 *   - handleSave: Save action for modal
 *   - handleDiscard: Discard action for modal
 *   - handleCancel: Cancel action for modal
 *   - handleDirtyChange: Callback to pass to child components
 *   - handleRefreshBlocked: Callback for refresh blocking
 *   - saveRef: Ref to save function (can be set by child components)
 *
 * @example
 * ```tsx
 * function WorkflowEditor() {
 *   const unsavedChanges = useUnsavedChanges();
 *
 *   return (
 *     <div>
 *       <WorkflowBuilder
 *         onDirtyChange={unsavedChanges.handleDirtyChange}
 *         onRefreshBlocked={unsavedChanges.handleRefreshBlocked}
 *         saveRef={unsavedChanges.saveRef}
 *       />
 *       <UnsavedChangesModal
 *         isOpen={unsavedChanges.showModal}
 *         onSave={unsavedChanges.handleSave}
 *         onDiscard={unsavedChanges.handleDiscard}
 *         onCancel={unsavedChanges.handleCancel}
 *         isSaving={unsavedChanges.isSaving}
 *       />
 *     </div>
 *   );
 * }
 * ```
 *
 * @example
 * ```tsx
 * // With direct save callback
 * const unsavedChanges = useUnsavedChanges(async () => {
 *   await api.saveWorkflow(workflowData);
 * });
 * ```
 */
export function useUnsavedChanges(saveCallback?: () => Promise<void>) {
  // State
  const [isDirty, setIsDirty] = useState(false);
  const [showModal, setShowModal] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [isRefreshBlocked, setIsRefreshBlocked] = useState(false);

  // Ref for save function (can be set by child components)
  const saveRef = useRef<(() => Promise<void>) | null>(saveCallback || null);

  // Callback to update dirty state from child components
  const handleDirtyChange = useCallback((dirty: boolean) => {
    setIsDirty(dirty);
  }, []);

  // Callback for refresh blocking (F5/Ctrl+R)
  const handleRefreshBlocked = useCallback(() => {
    setIsRefreshBlocked(true);
    setShowModal(true);
  }, []);

  // Handle navigation block - show modal
  const handleNavigationBlock = useCallback((destination: string) => {
    setIsRefreshBlocked(false);
    setShowModal(true);
  }, []);

  // Navigation guard hook - registers this component as a guard
  const { confirmNavigation, cancelNavigation } = useNavigationGuard({
    isDirty,
    onBlock: handleNavigationBlock,
  });

  // Modal actions
  const handleSave = useCallback(async () => {
    const saveFn = saveRef.current;
    if (!saveFn) {
      console.warn('[useUnsavedChanges] No save function provided');
      return;
    }

    setIsSaving(true);
    try {
      await saveFn();
      setShowModal(false);
      setIsDirty(false);

      if (isRefreshBlocked) {
        // After saving, reload the page
        window.location.reload();
      } else {
        confirmNavigation();
      }
    } catch (error) {
      console.error('[useUnsavedChanges] Failed to save:', error);
      // Keep modal open on error so user can retry
    } finally {
      setIsSaving(false);
    }
  }, [confirmNavigation, isRefreshBlocked]);

  const handleDiscard = useCallback(() => {
    setShowModal(false);
    setIsDirty(false);

    if (isRefreshBlocked) {
      // Discard and reload the page
      window.location.reload();
    } else {
      confirmNavigation();
    }
  }, [confirmNavigation, isRefreshBlocked]);

  const handleCancel = useCallback(() => {
    setShowModal(false);
    setIsRefreshBlocked(false);
    cancelNavigation();
  }, [cancelNavigation]);

  return {
    // State
    isDirty,
    setIsDirty,
    showModal,
    isSaving,

    // Actions
    handleSave,
    handleDiscard,
    handleCancel,

    // Callbacks for child components
    handleDirtyChange,
    handleRefreshBlocked,

    // Save ref (can be set by child components)
    saveRef: saveRef as MutableRefObject<(() => Promise<void>) | null>,
  };
}
