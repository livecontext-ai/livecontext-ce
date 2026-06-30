import { useState, useRef, useEffect } from 'react';

/**
 * Hook to calculate popover position relative to a trigger button.
 * Calculates position when popover opens, positioning it below the button
 * with a right-aligned width.
 *
 * @param isOpen - Whether the popover is currently open
 * @param width - Width of the popover in pixels (e.g., 200, 280, 420)
 *
 * @returns Object with:
 *   - buttonRef: Ref to attach to the trigger button
 *   - popoverPosition: { top, left } coordinates for the popover
 *
 * @example
 * ```tsx
 * const [isOpen, setIsOpen] = useState(false);
 * const { buttonRef, popoverPosition } = usePopoverPosition(isOpen, 280);
 *
 * return (
 *   <>
 *     <button ref={buttonRef} onClick={() => setIsOpen(!isOpen)}>
 *       Info
 *     </button>
 *     {isOpen && ReactDOM.createPortal(
 *       <div style={{ top: popoverPosition.top, left: popoverPosition.left }}>
 *         Popover content
 *       </div>,
 *       document.body
 *     )}
 *   </>
 * );
 * ```
 */
export function usePopoverPosition(isOpen: boolean, width: number = 200) {
  const buttonRef = useRef<HTMLButtonElement>(null);
  const [popoverPosition, setPopoverPosition] = useState({ top: 0, left: 0 });

  useEffect(() => {
    if (isOpen && buttonRef.current) {
      const rect = buttonRef.current.getBoundingClientRect();
      setPopoverPosition({
        top: rect.bottom + 4,
        left: Math.max(8, rect.right - width),
      });
    }
  }, [isOpen, width]);

  return { buttonRef, popoverPosition };
}
