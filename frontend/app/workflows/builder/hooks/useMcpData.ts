import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { apiClient } from '@/lib/api';

export interface ApiSystem {
  slug: string;
  apiName: string;
  description: string;
  toolsCount?: number;
  status?: string;
  isActive?: boolean;
  iconSlug?: string;
  iconUrl?: string;
}

export interface ApiTool {
  slug: string;
  name: string;
  description: string;
  method: string;
  isActive?: boolean;
  status?: string;
  apiSlug?: string;
  iconSlug?: string;
  iconUrl?: string;
  /**
   * Stable UUID of the underlying api_tool row. Stored on the node so the
   * platform-credential pricing toggle can resolve per-endpoint rates.
   */
  toolId?: string;
}

interface ApisResponse {
  content: ApiSystem[];
  totalElements: number;
  totalPages: number;
  number: number; // current page
  size: number;
}

// Helper to handle the mixed response type (array or object)
// Uses apiClient to go through the Gateway (not direct localhost calls)
export const fetchApis = async ({ pageParam = 0, searchQuery }: { pageParam?: number; searchQuery?: string }) => {
  // Ensure pageParam is a valid number
  const page = typeof pageParam === 'number' && !isNaN(pageParam) ? pageParam : 0;
  const params: Record<string, string | number> = {
    page,
    size: 20,
  };
  if (searchQuery && searchQuery.trim()) {
    params.name = searchQuery.trim();
  }
  const data = await apiClient.get<any>('/workflow-inspector/apis', { params });

  if (Array.isArray(data)) {
    return {
      content: data,
      totalElements: data.length,
      totalPages: 1,
      number: 0,
      size: data.length,
      last: true
    };
  }

  return data as ApisResponse & { last?: boolean };
};

export const useMcpApis = (enabled: boolean = true, searchQuery?: string) => {
  return useInfiniteQuery({
    queryKey: ['mcp-apis', searchQuery],
    queryFn: ({ pageParam }) => fetchApis({ pageParam, searchQuery }),
    enabled,
    initialPageParam: 0,
    getNextPageParam: (lastPage, allPages) => {
        // If lastPage has a 'last' flag, use it
        if (lastPage.last === true) {
            return undefined;
        }
        
        // Check if we have pagination info
        const currentPage = typeof lastPage.number === 'number' && !isNaN(lastPage.number) ? lastPage.number : allPages.length - 1;
        const totalPages = typeof lastPage.totalPages === 'number' && !isNaN(lastPage.totalPages) ? lastPage.totalPages : null;
        const pageSize = typeof lastPage.size === 'number' && !isNaN(lastPage.size) ? lastPage.size : 20;
        const contentLength = Array.isArray(lastPage.content) ? lastPage.content.length : 0;
        
        // If we have totalPages info, use it
        if (totalPages !== null && currentPage >= totalPages - 1) {
            return undefined;
        }
        
        // If the content length is less than page size, we're on the last page
        if (contentLength < pageSize) {
            return undefined;
        }
        
        // Otherwise, calculate next page based on pages already loaded
        // This is more reliable than trusting lastPage.number
        const nextPage = allPages.length;
        return nextPage;
    },
    staleTime: 5 * 60 * 1000, // 5 minutes
    gcTime: 10 * 60 * 1000, // 10 minutes
  });
};

/** Page size of the ranked integrations list, shared by the fetch and its pager. */
export const POPULAR_APIS_PAGE_SIZE = 20;

/**
 * One page of integrations ordered by how much the PLATFORM runs them (V461).
 *
 * Deliberately a separate endpoint rather than a `sort` flag on `/apis`: that one
 * loads the whole catalogue and slices it in JS, which is exactly the cost this list -
 * scrolled page by page from the palette - must not pay twice.
 */
export const fetchPopularApis = async ({ pageParam = 0 }: { pageParam?: number }) => {
  const page = typeof pageParam === 'number' && !isNaN(pageParam) ? pageParam : 0;
  const data = await apiClient.get<ApisResponse & { last?: boolean }>('/workflow-inspector/apis/popular', {
    params: { page, size: POPULAR_APIS_PAGE_SIZE },
  });
  return data;
};

/**
 * The palette's ranked integrations section.
 *
 * `enabled` is what makes it lazy: the caller only turns it on once the section has
 * actually been scrolled into view, so opening the palette on the categories does not
 * fetch a list nobody looked at.
 */
export const usePopularApis = (enabled: boolean) => {
  return useInfiniteQuery({
    queryKey: ['mcp-apis-popular'],
    queryFn: ({ pageParam }) => fetchPopularApis({ pageParam }),
    enabled,
    initialPageParam: 0,
    getNextPageParam: (lastPage, allPages) => {
      if (lastPage?.last === true) return undefined;
      const content = Array.isArray(lastPage?.content) ? lastPage.content : [];
      // A short page is the last page: the ranking is a total order over a fixed
      // catalogue, so a full page always means there is more behind it.
      if (content.length < POPULAR_APIS_PAGE_SIZE) return undefined;
      return allPages.length;
    },
    // The ranking moves on a flush interval, not per interaction. Refetching it while
    // the builder is open would reorder the list under the pointer for no new information.
    staleTime: 5 * 60 * 1000,
    gcTime: 10 * 60 * 1000,
  });
};

export const fetchApiTools = async (apiSlug: string): Promise<ApiTool[]> => {
  if (!apiSlug) return [];
  return apiClient.get<ApiTool[]>(`/workflow-inspector/apis/${encodeURIComponent(apiSlug)}/tools`);
};

export const useMcpApiTools = (apiSlug: string | null) => {
  return useQuery({
    queryKey: ['mcp-api-tools', apiSlug],
    queryFn: () => fetchApiTools(apiSlug!),
    enabled: !!apiSlug,
    staleTime: 5 * 60 * 1000, // 5 minutes
  });
};

export const fetchToolDetails = async (toolSlug: string) => {
  if (!toolSlug) return null;
  return apiClient.get<any>(`/workflow-inspector/tools/${encodeURIComponent(toolSlug)}/details`);
};

/** Resolve an operation within its integration before creating a configured node. */
export const fetchCatalogTool = async (apiName: string, operationName: string) => {
  const apis: ApiSystem[] = [];
  let page = 0;
  while (true) {
    const result = await fetchApis({ pageParam: page, searchQuery: apiName });
    apis.push(...result.content);
    if (result.last || page + 1 >= result.totalPages || !result.content.length) break;
    page += 1;
  }
  const api = apis.find((candidate) => candidate.apiName.toLowerCase() === apiName.toLowerCase());
  if (!api) throw new Error(`Integration unavailable: ${apiName}`);
  const tools = await fetchApiTools(api.slug);
  const matches = tools.filter((candidate) => candidate.name === operationName);
  if (matches.length !== 1) throw new Error(`Operation unavailable or ambiguous: ${apiName}/${operationName}`);
  const tool = matches[0];
  const details = await fetchToolDetails(tool.slug);
  if (!details || !Array.isArray(details.parameters)) {
    throw new Error(`Tool details unavailable: ${tool.slug}`);
  }
  return { api, tool, details };
};

export type CatalogTool = Awaited<ReturnType<typeof fetchCatalogTool>>;

export const useMcpToolDetails = (toolSlug: string | null) => {
    return useQuery({
        queryKey: ['mcp-tool-details', toolSlug],
        queryFn: () => fetchToolDetails(toolSlug!),
        enabled: !!toolSlug,
        staleTime: 5 * 60 * 1000,
    });
};

export interface StructureNode {
  key: string;
  type: string;
  hasChildren: boolean;
}

export const fetchStructureRoot = async (structureId: string): Promise<StructureNode[]> => {
  if (!structureId) return [];
  const data = await apiClient.get<any>(`/v1/structure/${encodeURIComponent(structureId)}/root`);
  return Array.isArray(data) ? data : [];
};

export const fetchStructurePath = async (structureId: string, path: string[]): Promise<StructureNode[]> => {
  if (!structureId) return [];
  const params: Record<string, string> = {};
  path.forEach((p, i) => {
    params[`path`] = p; // Note: apiClient may need array support, using last value for now
  });
  // For array params, we need to build the URL manually or use a different approach
  const pathQuery = path.length > 0
    ? '?' + path.map(p => `path=${encodeURIComponent(p)}`).join('&')
    : '';
  const data = await apiClient.get<any>(`/v1/structure/${encodeURIComponent(structureId)}/path${pathQuery}`);
  return Array.isArray(data) ? data : [];
};

export const useStructureRoot = (structureId: string | null) => {
  return useQuery({
    queryKey: ['structure-root', structureId],
    queryFn: () => fetchStructureRoot(structureId!),
    enabled: !!structureId,
    staleTime: 5 * 60 * 1000, // 5 minutes
  });
};

export const useStructurePath = (structureId: string | null, path: string[]) => {
  // Serialize path array to string for stable query key
  const pathKey = path.join(',');
  return useQuery({
    queryKey: ['structure-path', structureId, pathKey],
    queryFn: () => fetchStructurePath(structureId!, path),
    enabled: !!structureId,
    staleTime: 5 * 60 * 1000, // 5 minutes
  });
};

