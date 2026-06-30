'use client';

import React, { useState, useRef, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { MoreVertical, ExternalLink, Play, Trash2 } from 'lucide-react';

export interface ActionMenuItem {
  id: string;
  label: string;
  icon: React.ReactNode;
  onClick: () => void;
  variant?: 'default' | 'danger';
}

interface PreviewActionMenuProps {
  items: ActionMenuItem[];
  /**
   * Where to anchor the menu relative to the button. 'above' (default) places
   * it on top of the button - fine for footers / cards. 'below' is required
   * when the trigger sits at the top of the viewport (e.g. a sticky table
   * header), otherwise the dropdown overflows above the page.
   */
  placement?: 'above' | 'below';
  /** Optional override for the trigger button's content. Defaults to MoreVertical icon. */
  triggerIcon?: React.ReactNode;
  /** Optional override for the trigger button's title attribute. */
  triggerTitle?: string;
  /** Optional extra classes to merge into the trigger button. */
  triggerClassName?: string;
}

export function PreviewActionMenu({
  items,
  placement = 'above',
  triggerIcon,
  triggerTitle,
  triggerClassName,
}: PreviewActionMenuProps) {
  const [isOpen, setIsOpen] = useState(false);
  const [menuPosition, setMenuPosition] = useState({ top: 0, left: 0 });
  const [mounted, setMounted] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  // Update menu position when opening. 'below' anchors at the bottom of the
  // button (trigger near the top of the viewport, e.g. table headers). 'above'
  // keeps the legacy behaviour for cards/footers.
  useEffect(() => {
    if (isOpen && buttonRef.current) {
      const rect = buttonRef.current.getBoundingClientRect();
      setMenuPosition({
        top: placement === 'below' ? rect.bottom + 8 : rect.top - 8,
        left: rect.right - 176, // 176px = w-44 (11rem)
      });
    }
  }, [isOpen, placement]);

  // Close menu when clicking outside
  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (
        menuRef.current && !menuRef.current.contains(event.target as Node) &&
        buttonRef.current && !buttonRef.current.contains(event.target as Node)
      ) {
        setIsOpen(false);
      }
    }

    if (isOpen) {
      document.addEventListener('mousedown', handleClickOutside);
      return () => document.removeEventListener('mousedown', handleClickOutside);
    }
  }, [isOpen]);

  const menuContent = isOpen && mounted ? createPortal(
    <div
      ref={menuRef}
      className="fixed w-44 bg-theme-primary border border-gray-300/70 dark:border-gray-600/70 rounded-2xl p-2 shadow-lg"
      style={{
        top: `${menuPosition.top}px`,
        left: `${menuPosition.left}px`,
        transform: placement === 'below' ? 'none' : 'translateY(-100%)',
        zIndex: 9999,
      }}
    >
      <div className="space-y-1">
        {items.map((item) => (
          <button
            key={item.id}
            onClick={(e) => {
              e.stopPropagation();
              item.onClick();
              setIsOpen(false);
            }}
            className={`w-full flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors ${
              item.variant === 'danger'
                ? 'text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/30'
                : 'text-theme-primary hover:bg-gray-100 dark:hover:bg-gray-800'
            }`}
          >
            {item.icon}
            <span className="text-sm">{item.label}</span>
          </button>
        ))}
      </div>
    </div>,
    document.body
  ) : null;

  return (
    <div className="relative">
      <button
        ref={buttonRef}
        type="button"
        onClick={(e) => {
          e.stopPropagation();
          setIsOpen(!isOpen);
        }}
        title={triggerTitle}
        className={`flex items-center justify-center w-8 h-8 rounded-lg text-theme-secondary hover:text-theme-primary hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors ${triggerClassName ?? ''}`}
      >
        {triggerIcon ?? <MoreVertical className="h-4 w-4" />}
      </button>
      {menuContent}
    </div>
  );
}

// Pre-configured icons for common actions
export const ActionIcons = {
  open: <ExternalLink className="h-4 w-4" />,
  run: <Play className="h-4 w-4" />,
  delete: <Trash2 className="h-4 w-4" />,
};
