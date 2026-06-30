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

