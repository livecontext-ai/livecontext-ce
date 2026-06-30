'use client';

import React, { createContext, useContext, useState, useCallback, useMemo, useEffect, ReactNode } from 'react';

// ============================================
// Types
// ============================================

interface SidebarState {
  isOpen: boolean;        // Mobile sidebar open state
  isCollapsed: boolean;   // Desktop sidebar collapsed state
}

interface SidebarContextType {
  isOpen: boolean;
  isCollapsed: boolean;
  setOpen: (open: boolean) => void;
  setCollapsed: (collapsed: boolean) => void;
  toggle: () => void;
  toggleCollapse: () => void;
}

const SidebarContext = createContext<SidebarContextType | null>(null);

// ============================================
// LocalStorage helpers
// ============================================

const SIDEBAR_COLLAPSED_KEY = 'app-sidebar-collapsed';

function getSavedSidebarCollapsed(): boolean {
  if (typeof window === 'undefined') return true;
  try {
    const saved = localStorage.getItem(SIDEBAR_COLLAPSED_KEY);
    return saved === 'true';
  } catch {
    return true;
  }
}

function saveSidebarCollapsed(collapsed: boolean): void {
  try {
    localStorage.setItem(SIDEBAR_COLLAPSED_KEY, String(collapsed));
  } catch {
    // localStorage not available
  }
}

// ============================================
// Provider
// ============================================

export function SidebarProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<SidebarState>({
    isOpen: false,
    isCollapsed: true, // Will be updated from localStorage on mount
  });

  // Load collapsed state from localStorage on mount
  useEffect(() => {
    setState(prev => ({
      ...prev,
      isCollapsed: getSavedSidebarCollapsed(),
    }));
  }, []);

  const setOpen = useCallback((open: boolean) => {
    setState(prev => ({ ...prev, isOpen: open }));
  }, []);

  const setCollapsed = useCallback((collapsed: boolean) => {
    setState(prev => ({ ...prev, isCollapsed: collapsed }));
    saveSidebarCollapsed(collapsed);
  }, []);

  const toggle = useCallback(() => {
    setState(prev => ({ ...prev, isOpen: !prev.isOpen }));
  }, []);

  const toggleCollapse = useCallback(() => {
    setState(prev => {
      const newCollapsed = !prev.isCollapsed;
      saveSidebarCollapsed(newCollapsed);
      return { ...prev, isCollapsed: newCollapsed };
    });
  }, []);

  const value = useMemo<SidebarContextType>(() => ({
    isOpen: state.isOpen,
    isCollapsed: state.isCollapsed,
    setOpen,
    setCollapsed,
    toggle,
    toggleCollapse,
  }), [state.isOpen, state.isCollapsed, setOpen, setCollapsed, toggle, toggleCollapse]);

  return (
    <SidebarContext.Provider value={value}>
      {children}
    </SidebarContext.Provider>
  );
}

// ============================================
// Hook
// ============================================

export function useSidebar(): SidebarContextType {
  const context = useContext(SidebarContext);
  if (!context) {
    throw new Error('useSidebar must be used within a SidebarProvider');
  }
  return context;
}

export function useSidebarSafe(): SidebarContextType | null {
  return useContext(SidebarContext);
}
