'use client';

import { useState, useCallback, useEffect } from 'react';
import { McpTool } from '../../../types';

interface SectionsExpanded {
  step1: boolean;
  step2: boolean;
  step3: boolean;
  step4: boolean;
  validation: boolean;
}

interface UseExpansionStateParams {
  mcpTools: McpTool[];
}

export function useExpansionState({ mcpTools }: UseExpansionStateParams) {
  // Section expansion state
  const [sectionsExpanded, setSectionsExpanded] = useState<SectionsExpanded>({
    step1: true,
    step2: true,
    step3: true,
    step4: true,
    validation: true
  });

  // Tool and category expansion state
  const [toolsExpanded, setToolsExpanded] = useState<Record<string, boolean>>({});
  const [categoriesExpanded, setCategoriesExpanded] = useState<Record<string, boolean>>({});

  // Toggle handlers
  const toggleSection = useCallback((section: keyof SectionsExpanded) => {
    setSectionsExpanded(prev => ({
      ...prev,
      [section]: !prev[section]
    }));
  }, []);

  const toggleTool = useCallback((toolId: string) => {
    setToolsExpanded(prev => ({
      ...prev,
      [toolId]: !prev[toolId]
    }));
  }, []);

  const toggleCategory = useCallback((categoryName: string) => {
    setCategoriesExpanded(prev => ({
      ...prev,
      [categoryName]: !prev[categoryName]
    }));
  }, []);

  // Initialize expansion state for tools and categories
  useEffect(() => {
    const initialToolsState: Record<string, boolean> = {};
    const initialCategoriesState: Record<string, boolean> = {};
    const toolCategories = new Set<string>();

    mcpTools.forEach((tool, index) => {
      const toolKey = `${tool.name}-${index}`;
      if (toolsExpanded[toolKey] === undefined) {
        initialToolsState[toolKey] = false; // Tools collapsed by default
      }

      const toolCategory = tool.toolCategory || 'Other';
      toolCategories.add(toolCategory);
    });

    toolCategories.forEach(category => {
      if (categoriesExpanded[category] === undefined) {
        initialCategoriesState[category] = true; // Categories expanded by default
      }
    });

    if (Object.keys(initialToolsState).length > 0) {
      setToolsExpanded(prev => ({ ...prev, ...initialToolsState }));
    }
    if (Object.keys(initialCategoriesState).length > 0) {
      setCategoriesExpanded(prev => ({ ...prev, ...initialCategoriesState }));
    }
  }, [mcpTools]);

  return {
    sectionsExpanded,
    toolsExpanded,
    categoriesExpanded,
    toggleSection,
    toggleTool,
    toggleCategory
  };
}
