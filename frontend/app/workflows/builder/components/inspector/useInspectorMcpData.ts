import * as React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { useMcpApis, useMcpApiTools } from '../../hooks/useMcpData';

interface UseInspectorMcpDataParams {
  node: Node<BuilderNodeData> | null;
  isMcpNode: boolean;
  isApiNode: boolean;
  isToolNode: boolean;
  isMcpGenericNode: boolean;
}

export interface InspectorMcpData {
  mcpNavigationLevel: 'apis' | 'tools';
  setMcpNavigationLevel: React.Dispatch<React.SetStateAction<'apis' | 'tools'>>;
  mcpSelectedApiSlug: string | null;
  setMcpSelectedApiSlug: React.Dispatch<React.SetStateAction<string | null>>;
  mcpSearchQuery: string;
  setMcpSearchQuery: React.Dispatch<React.SetStateAction<string>>;
  mcpApis: any[];
  mcpApiTools: any[];
  mcpLoadingApis: boolean;
  mcpLoadingTools: boolean;
  apiInitialLoading: boolean;
  toolInitialLoading: boolean;
  apiHasMore: boolean;
  toolHasMore: boolean;
  apiLoadMoreRef: React.RefObject<HTMLDivElement>;
  toolLoadMoreRef: React.RefObject<HTMLDivElement>;
  shouldLoadApis: boolean;
  setToolPage: React.Dispatch<React.SetStateAction<number>>;
}

const PAGE_SIZE = 20;

export function useInspectorMcpData({
  node,
  isMcpNode,
  isApiNode,
  isToolNode,
  isMcpGenericNode,
}: UseInspectorMcpDataParams): InspectorMcpData {
  const [mcpNavigationLevel, setMcpNavigationLevel] = React.useState<'apis' | 'tools'>('apis');
  const [mcpSelectedApiSlug, setMcpSelectedApiSlug] = React.useState<string | null>(null);
  const [mcpSearchQuery, setMcpSearchQuery] = React.useState('');
  const [toolPage, setToolPage] = React.useState(0);

  const apiNodeApiSlug = React.useMemo(() => {
    if (isApiNode && node?.data) {
      const apiData = (node.data as any)?.apiData;
      return apiData?.apiSlug || apiData?.apiId || null;
    }
    return null;
  }, [isApiNode, node?.id, (node?.data as any)?.apiData?.apiSlug, (node?.data as any)?.apiData?.apiId]);

  const toolNodeApiSlug = React.useMemo(() => {
    if (isToolNode && node?.data) {
      const toolData = (node.data as any)?.toolData;
      return toolData?.apiSlug || toolData?.apiId || null;
    }
    return null;
  }, [isToolNode, node?.id, (node?.data as any)?.toolData?.apiSlug, (node?.data as any)?.toolData?.apiId]);

  const shouldLoadApis = isMcpGenericNode || (isMcpNode && !isApiNode && !isToolNode);
  const {
    data: apisData,
    fetchNextPage: fetchNextApisPage,
    hasNextPage: hasNextApisPage,
    isFetching: isFetchingApis,
    isLoading: isLoadingApisInitial,
    isError: isErrorApis,
  } = useMcpApis(shouldLoadApis);

  const mcpApis = React.useMemo(() => {
    if (!shouldLoadApis || !apisData) return [];
    const allApis = apisData.pages.flatMap((page: any) => page.content || []);
    const unique = new Map<string, any>();
    allApis.forEach((api: any) => {
      if (api.slug && !unique.has(api.slug)) unique.set(api.slug, api);
    });
    return Array.from(unique.values());
  }, [shouldLoadApis, apisData]);

  const apiSlugToLoad = apiNodeApiSlug || toolNodeApiSlug || mcpSelectedApiSlug;
  const {
    data: allTools,
    isLoading: isLoadingTools,
    isFetching: isFetchingTools,
  } = useMcpApiTools(apiSlugToLoad);

  const mcpApiTools = React.useMemo(() => {
    if (!allTools) return [];
    const endIndex = (toolPage + 1) * PAGE_SIZE;
    return allTools.slice(0, endIndex);
  }, [allTools, toolPage]);

  const toolHasMore = allTools ? (toolPage + 1) * PAGE_SIZE < allTools.length : false;
  const apiHasMore = !!hasNextApisPage;

  const apiLoadMoreRef = React.useRef<HTMLDivElement>(null);
  const toolLoadMoreRef = React.useRef<HTMLDivElement>(null);

  const apiObserverStateRef = React.useRef({
    hasNextApisPage: false,
    isFetchingApis: false,
    isErrorApis: false,
    apiInitialLoading: false,
    mcpApisLength: 0,
    fetchNextApisPage: () => {},
  });

  const toolObserverStateRef = React.useRef({
    toolHasMore: false,
    mcpLoadingTools: false,
    toolInitialLoading: false,
    mcpApiToolsLength: 0,
    setToolPage: (fn: (prev: number) => number) => {},
  });

  React.useEffect(() => {
    apiObserverStateRef.current = {
      hasNextApisPage,
      isFetchingApis,
      isErrorApis,
      apiInitialLoading: isLoadingApisInitial,
      mcpApisLength: mcpApis.length,
      fetchNextApisPage,
    };
  }, [hasNextApisPage, isFetchingApis, isErrorApis, isLoadingApisInitial, mcpApis.length, fetchNextApisPage]);

  React.useEffect(() => {
    toolObserverStateRef.current = {
      toolHasMore,
      mcpLoadingTools: isFetchingTools,
      toolInitialLoading: isLoadingTools,
      mcpApiToolsLength: mcpApiTools.length,
      setToolPage,
    };
  }, [toolHasMore, isFetchingTools, isLoadingTools, mcpApiTools.length]);

  React.useEffect(() => {
    if (!shouldLoadApis || mcpNavigationLevel !== 'apis') {
      return;
    }

    const observer = new IntersectionObserver(
      (entries) => {
        const [entry] = entries;
        const state = apiObserverStateRef.current;
        if (
          entry.isIntersecting &&
          state.hasNextApisPage &&
          !state.isFetchingApis &&
          !state.apiInitialLoading &&
          state.mcpApisLength > 0
        ) {
          state.fetchNextApisPage();
        }
      },
      { threshold: 0.1, rootMargin: '100px' },
    );

    const currentRef = apiLoadMoreRef.current;
    if (currentRef) {
      const timeoutId = setTimeout(() => observer.observe(currentRef), 100);
      return () => {
        clearTimeout(timeoutId);
        observer.unobserve(currentRef);
      };
    }

    return () => {
      if (currentRef) observer.unobserve(currentRef);
    };
    // Re-run when sentinel or pagination state changes so the observer attaches
  }, [
    shouldLoadApis,
    mcpNavigationLevel,
    apiHasMore,
    isLoadingApisInitial,
    isFetchingApis,
    mcpApis.length,
  ]);

  React.useEffect(() => {
    const apiSlug = apiNodeApiSlug || toolNodeApiSlug || mcpSelectedApiSlug;
    if (!apiSlug || mcpNavigationLevel !== 'tools') {
      return;
    }

    const observer = new IntersectionObserver(
      (entries) => {
        const [entry] = entries;
        const state = toolObserverStateRef.current;
        if (
          entry.isIntersecting &&
          state.toolHasMore &&
          !state.mcpLoadingTools &&
          !state.toolInitialLoading &&
          state.mcpApiToolsLength > 0
        ) {
          state.setToolPage((prev) => prev + 1);
        }
      },
      { threshold: 0.1, rootMargin: '100px' },
    );

    const currentRef = toolLoadMoreRef.current;
    if (currentRef) {
      const timeoutId = setTimeout(() => observer.observe(currentRef), 100);
      return () => {
        clearTimeout(timeoutId);
        observer.unobserve(currentRef);
      };
    }

    return () => {
      if (currentRef) observer.unobserve(currentRef);
    };
    // Re-run when sentinel or pagination state changes so the observer attaches
  }, [
    apiNodeApiSlug,
    toolNodeApiSlug,
    mcpSelectedApiSlug,
    mcpNavigationLevel,
    toolHasMore,
    isLoadingTools,
    isFetchingTools,
    mcpApiTools.length,
  ]);

  React.useEffect(() => {
    const apiSlug = apiNodeApiSlug || toolNodeApiSlug || mcpSelectedApiSlug;
    if (apiSlug) {
      setToolPage(0);
    }
  }, [apiNodeApiSlug, toolNodeApiSlug, mcpSelectedApiSlug]);

  return {
    mcpNavigationLevel,
    setMcpNavigationLevel,
    mcpSelectedApiSlug,
    setMcpSelectedApiSlug,
    mcpSearchQuery,
    setMcpSearchQuery,
    mcpApis,
    mcpApiTools,
    mcpLoadingApis: isFetchingApis,
    mcpLoadingTools: isFetchingTools,
    apiInitialLoading: isLoadingApisInitial,
    toolInitialLoading: isLoadingTools,
    apiHasMore,
    toolHasMore,
    apiLoadMoreRef,
    toolLoadMoreRef,
    shouldLoadApis,
    setToolPage,
  };
}
