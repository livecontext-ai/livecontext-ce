'use client';

import * as React from 'react';

export type NavigationLevel = 'categories' | 'apis' | 'tools' | 'datasources' | 'tables' | 'triggers' | 'types' | 'ai' | 'logic' | 'core' | 'interfaces' | 'agents' | 'workflows';

export interface SharedNavigationState {
  // Main navigation level
  navigationLevel: NavigationLevel;
  setNavigationLevel: React.Dispatch<React.SetStateAction<NavigationLevel>>;
  
  // Search
  searchQuery: string;
  setSearchQuery: React.Dispatch<React.SetStateAction<string>>;
  
  // Category/Selection tracking
  selectedCategoryId: string | null;
  setSelectedCategoryId: React.Dispatch<React.SetStateAction<string | null>>;
  
  // API/Tool selection (for MCP)
  selectedApiId: string | null;
  setSelectedApiId: React.Dispatch<React.SetStateAction<string | null>>;
  
  // DataSource selection (for Tables trigger)
  selectedDataSourceId: number | null;
  setSelectedDataSourceId: React.Dispatch<React.SetStateAction<number | null>>;
  
  // Selected type (for triggers, AI, Core)
  selectedType: string | null;
  setSelectedType: React.Dispatch<React.SetStateAction<string | null>>;
  
  // Tool page for pagination
  toolPage: number;
  setToolPage: React.Dispatch<React.SetStateAction<number>>;
}

interface UseSharedNavigationOptions {
  initialLevel?: NavigationLevel;
  onLevelChange?: (level: NavigationLevel) => void;
  resetOnClose?: boolean;
  isOpen?: boolean;
}

export function useSharedNavigation(
  options: UseSharedNavigationOptions = {}
): SharedNavigationState {
  const {
    initialLevel = 'categories',
    onLevelChange,
    resetOnClose = true,
    isOpen = true,
  } = options;

  const [navigationLevel, setNavigationLevelState] = React.useState<NavigationLevel>(initialLevel);
  const [searchQuery, setSearchQuery] = React.useState('');
  const [selectedCategoryId, setSelectedCategoryId] = React.useState<string | null>(null);
  const [selectedApiId, setSelectedApiId] = React.useState<string | null>(null);
  const [selectedDataSourceId, setSelectedDataSourceId] = React.useState<number | null>(null);
  const [selectedType, setSelectedType] = React.useState<string | null>(null);
  const [toolPage, setToolPage] = React.useState(0);

  // Wrapper for setNavigationLevel to call onLevelChange callback
  const setNavigationLevel = React.useCallback(
    (value: NavigationLevel | ((prev: NavigationLevel) => NavigationLevel)) => {
      setNavigationLevelState((prev) => {
        const next = typeof value === 'function' ? value(prev) : value;
        if (next !== prev && onLevelChange) {
          onLevelChange(next);
        }
        return next;
      });
    },
    [onLevelChange]
  );

  // Reset navigation when panel closes
  React.useEffect(() => {
    if (!isOpen && resetOnClose) {
      setNavigationLevel(initialLevel);
      setSearchQuery('');
      setSelectedCategoryId(null);
      setSelectedApiId(null);
      setSelectedDataSourceId(null);
      setSelectedType(null);
      setToolPage(0);
    }
  }, [isOpen, resetOnClose, initialLevel, setNavigationLevel]);

  return {
    navigationLevel,
    setNavigationLevel,
    searchQuery,
    setSearchQuery,
    selectedCategoryId,
    setSelectedCategoryId,
    selectedApiId,
    setSelectedApiId,
    selectedDataSourceId,
    setSelectedDataSourceId,
    selectedType,
    setSelectedType,
    toolPage,
    setToolPage,
  };
}

// Helper function to navigate back
export function useNavigationBack(navigation: SharedNavigationState) {
  return React.useCallback(() => {
    const { navigationLevel, setNavigationLevel, selectedCategoryId, setSelectedCategoryId, setSelectedApiId, setSelectedDataSourceId, setSelectedType, setSearchQuery, setToolPage, selectedType } = navigation;

    console.log('[useNavigationBack] Called with:', {
      navigationLevel,
      selectedCategoryId,
      selectedType,
    });

    // Handle subcategory navigation first (but not if we're in types view - types is not a subcategory)
    if (selectedCategoryId && navigationLevel !== 'types') {
      console.log('[useNavigationBack] Going back from subcategory:', selectedCategoryId);
      setSelectedCategoryId(null);
      setSearchQuery('');
      return;
    }

    // Handle main navigation levels
    if (navigationLevel === 'tables') {
      console.log('[useNavigationBack] Going back from tables to datasources');
      setNavigationLevel('datasources');
      setSelectedDataSourceId(null);
    } else if (navigationLevel === 'datasources') {
      // If we came from types view (selectedType === 'triggers'), go back to types
      if (selectedType === 'triggers') {
        console.log('[useNavigationBack] Going back from datasources to types (triggers)');
        setNavigationLevel('types');
        setSelectedDataSourceId(null);
        // Keep selectedType='triggers'
      } else {
        console.log('[useNavigationBack] Going back from datasources to categories');
        setNavigationLevel('categories');
        setSelectedCategoryId(null);
        setSelectedDataSourceId(null);
        setSelectedType(null);
      }
    } else if (navigationLevel === 'tools') {
      console.log('[useNavigationBack] Going back from tools to apis');
      setNavigationLevel('apis');
      setSelectedApiId(null);
      setSelectedType(null);
      setToolPage(0);
    } else if (navigationLevel === 'interfaces') {
      console.log('[useNavigationBack] Going back from interfaces to categories');
      setNavigationLevel('categories');
      setSelectedCategoryId('core');
    } else if (navigationLevel === 'agents') {
      console.log('[useNavigationBack] Going back from agents to categories');
      setNavigationLevel('categories');
      setSelectedCategoryId('ai');
    } else if (navigationLevel === 'apis') {
      console.log('[useNavigationBack] Going back from apis to categories');
      setNavigationLevel('categories');
      setSelectedApiId(null);
      setSelectedType(null);
    } else if (navigationLevel === 'types') {
      console.log('[useNavigationBack] Going back from types to categories', { selectedType });
      // Go back to categories when coming from types view
      setNavigationLevel('categories');
      setSelectedType(null);
      setSelectedCategoryId(null); // Clear selected category to show all categories
    } else if (navigationLevel === 'triggers' || navigationLevel === 'ai' || navigationLevel === 'core' || navigationLevel === 'logic') {
      console.log('[useNavigationBack] Going back from', navigationLevel, 'to categories');
      setNavigationLevel('categories');
      setSelectedType(null);
    } else {
      console.log('[useNavigationBack] Unknown navigation level:', navigationLevel);
    }
    
    setSearchQuery('');
    console.log('[useNavigationBack] After navigation, new level will be:', navigationLevel === 'types' ? 'categories' : navigationLevel);
  }, [navigation]);
}

