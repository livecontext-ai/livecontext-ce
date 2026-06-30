/**
 * Service responsable uniquement de la récupération des données d'outils/APIs/DataSources
 * Single Responsibility: Fetch tool, API, and datasource data
 *
 * OPTIMIZATIONS (Phase 1):
 * - Removed redundant API fetch (tool details already contains iconSlug)
 * - Added datasource cache to avoid refetching during import
 * - Use apiSlug as fallback for iconSlug
 */

import type { BuilderNodeData } from '../../types';
import { orchestratorApi, apiClient, type DataSource } from '@/lib/api';

export interface ToolDataResult {
  toolData?: BuilderNodeData['toolData'];
  apiData?: BuilderNodeData['apiData'];
  dataSourceData?: BuilderNodeData['dataSourceData'];
}

// Cache for batch-fetched tool data during import session
let toolDataBatchCache: Map<string, ToolDataResult> | null = null;

// PHASE 3.1: In-flight request deduplication
// Prevents duplicate requests for the same tool while a request is in progress
const inFlightRequests: Map<string, Promise<ToolDataResult>> = new Map();

// PHASE 3.3: Progress tracking
export interface ImportProgress {
  total: number;
  completed: number;
  current: string;
}
type ProgressCallback = (progress: ImportProgress) => void;
let progressCallback: ProgressCallback | null = null;
let progressState: ImportProgress = { total: 0, completed: 0, current: '' };

export class ToolDataService {
  // Cache for datasources during import session
  private static dataSourcesCache: DataSource[] | null = null;

  /**
   * Clear all caches (call after import completes)
   */
  static clearCache(): void {
    this.dataSourcesCache = null;
    toolDataBatchCache = null;
    inFlightRequests.clear();
    progressCallback = null;
    progressState = { total: 0, completed: 0, current: '' };
  }

  /**
   * PHASE 3.3: Set progress callback for import progress tracking
   */
  static setProgressCallback(callback: ProgressCallback | null): void {
    progressCallback = callback;
  }

  /**
   * PHASE 3.3: Update and notify progress
   */
  private static updateProgress(completed: number, total: number, current: string): void {
    progressState = { total, completed, current };
    if (progressCallback) {
      progressCallback(progressState);
    }
  }

  /**
   * PHASE 3.3: Get current progress
   */
  static getProgress(): ImportProgress {
    return { ...progressState };
  }

  /**
   * PHASE 3.2: Preload common/frequently used tools
   * Call this at app initialization to have instant access to common tools
   *
   * @param commonToolSlugs List of commonly used tool slugs to preload
   */
  static async preloadCommonTools(commonToolSlugs: string[]): Promise<void> {
    if (!commonToolSlugs || commonToolSlugs.length === 0) {
      return;
    }

    console.log(`[ToolDataService] Preloading ${commonToolSlugs.length} common tools...`);

    try {
      await this.fetchToolsBatch(commonToolSlugs);
      console.log(`[ToolDataService] Preload complete: ${commonToolSlugs.length} tools cached`);
    } catch (error) {
      console.warn('[ToolDataService] Preload failed (non-blocking):', error);
      // Don't throw - preload failure shouldn't block the app
    }
  }

  /**
   * PHASE 3.2: Check if tools are preloaded and ready
   */
  static isPreloaded(): boolean {
    return toolDataBatchCache !== null && toolDataBatchCache.size > 0;
  }

  /**
   * PHASE 3.2: Get list of preloaded tool slugs
   */
  static getPreloadedToolSlugs(): string[] {
    if (!toolDataBatchCache) return [];
    return Array.from(toolDataBatchCache.keys());
  }

  /**
   * Batch fetch multiple tools in a single request
   * PHASE 2 OPTIMIZATION: Reduces N+1 queries to a single batch request
   * PHASE 3.3: Includes progress tracking
   *
   * @param toolIds List of tool IDs from the plan (can be "api-slug/tool-slug" or "tool-slug")
   * @returns Map of toolSlug -> ToolDataResult
   */
  static async fetchToolsBatch(toolIds: string[]): Promise<Map<string, ToolDataResult>> {
    if (!toolIds || toolIds.length === 0) {
      return new Map();
    }

    // Extract tool slugs from tool IDs
    const slugs: string[] = [];
    const idToSlugMap = new Map<string, string>(); // Map original IDs to their slugs

    for (const toolId of toolIds) {
      const { toolSlug } = this.extractSlugs(toolId);
      if (toolSlug) {
        slugs.push(toolSlug);
        idToSlugMap.set(toolId, toolSlug);
      }
    }

    // Deduplicate slugs
    const uniqueSlugs = [...new Set(slugs)];

    if (uniqueSlugs.length === 0) {
      return new Map();
    }

    // PHASE 3.3: Initialize progress
    this.updateProgress(0, uniqueSlugs.length, 'Starting batch fetch...');

    // Check cache first
    if (toolDataBatchCache) {
      const allCached = uniqueSlugs.every(slug => toolDataBatchCache!.has(slug));
      if (allCached) {
        console.log('[ToolDataService] All tools found in batch cache');
        this.updateProgress(uniqueSlugs.length, uniqueSlugs.length, 'Complete (cached)');
        return toolDataBatchCache;
      }
    }

    try {
      console.log(`[ToolDataService] Batch fetching ${uniqueSlugs.length} tools`);
      this.updateProgress(0, uniqueSlugs.length, 'Fetching tool data...');

      // Call the new batch endpoint
      const response = await apiClient.post<Record<string, any>>('/workflow-inspector/tools/batch', {
        toolSlugs: uniqueSlugs,
      });

      // Initialize cache if needed
      if (!toolDataBatchCache) {
        toolDataBatchCache = new Map();
      }

      // Process response and populate cache
      let processed = 0;
      for (const [slug, data] of Object.entries(response)) {
        if (!data) continue;

        const iconSlug = data.iconSlug || data.apiSlug || slug;

        const toolDataResult: ToolDataResult = {
          apiData: data.apiSlug ? {
            apiId: data.apiId?.toString(),
            apiSlug: data.apiSlug,
            apiName: data.apiName || data.name,
            baseUrl: data.baseUrl,
            iconSlug: iconSlug,
          } : undefined,
          toolData: {
            toolId: data.id?.toString(),
            toolSlug: data.slug || slug,
            apiId: data.apiId?.toString(),
            apiSlug: data.apiSlug,
            apiName: data.apiName || data.name,
            iconSlug: iconSlug,
            endpoint: data.endpoint,
            method: data.method,
            parameters: data.parameters || [],
            credentials: data.credentials || [],
            responses: data.responses || [],
          },
        };

        toolDataBatchCache.set(slug, toolDataResult);
        processed++;
        this.updateProgress(processed, uniqueSlugs.length, `Processing ${slug}...`);
      }

      console.log(`[ToolDataService] Batch fetch complete: ${toolDataBatchCache.size} tools cached`);
      this.updateProgress(uniqueSlugs.length, uniqueSlugs.length, 'Complete');
      return toolDataBatchCache;
    } catch (error) {
      // The batch endpoint can be unavailable (e.g. blocked as a non-GET in a
      // read-only share context). Falling back to one request PER NODE *and*
      // letting node creation drive them serially made a 38-tool workflow take
      // ~10s to load. Instead, fetch every slug in PARALLEL here and populate
      // the batch cache, so downstream getFromBatchCache() hits and no
      // sequential per-node fetch fires.
      console.warn('[ToolDataService] Batch fetch failed, falling back to parallel individual fetches:', error);
      this.updateProgress(0, uniqueSlugs.length, 'Batch failed, fetching in parallel...');
      if (!toolDataBatchCache) {
        toolDataBatchCache = new Map();
      }
      let done = 0;
      await Promise.all(uniqueSlugs.map(async (slug) => {
        try {
          const data = await this.fetchToolDataBySlug(slug);
          if (data) toolDataBatchCache!.set(slug, data);
        } catch (e) {
          console.warn(`[ToolDataService] Parallel fallback failed for ${slug}:`, e);
        } finally {
          this.updateProgress(++done, uniqueSlugs.length, `Fetched ${slug}...`);
        }
      }));
      return toolDataBatchCache;
    }
  }

  /**
   * Get tool data from batch cache (for use after fetchToolsBatch)
   */
  static getFromBatchCache(toolId: string): ToolDataResult | undefined {
    if (!toolDataBatchCache) return undefined;
    const { toolSlug } = this.extractSlugs(toolId);
    return toolSlug ? toolDataBatchCache.get(toolSlug) : undefined;
  }

  /**
   * Fetch tool data by API slug and tool slug
   * OPTIMIZED: Only fetches tool details, uses apiSlug for icon fallback
   * PHASE 3.1: Request deduplication - reuses in-flight requests
   */
  static async fetchToolData(apiSlug: string, toolSlug: string): Promise<ToolDataResult> {
    const cacheKey = `${apiSlug}/${toolSlug}`;

    // PHASE 3.1: Check for in-flight request
    const inFlight = inFlightRequests.get(cacheKey);
    if (inFlight) {
      console.log(`[ToolDataService] Reusing in-flight request for ${cacheKey}`);
      return inFlight;
    }

    // Create the fetch promise
    const fetchPromise = (async (): Promise<ToolDataResult> => {
      try {
        // Fetch tool details (with parameters) - this should include iconSlug
        let toolData: any;
        try {
          toolData = await apiClient.get<any>(`/workflow-inspector/tools/${encodeURIComponent(toolSlug)}/details`);
        } catch {
          // Fallback to basic tool endpoint if details fails
          toolData = await apiClient.get<any>(`/workflow-inspector/tools/${encodeURIComponent(toolSlug)}`);
        }

        // Use iconSlug from toolData if available, otherwise use apiSlug as fallback
        const iconSlug = toolData.iconSlug || apiSlug;

        return {
          apiData: {
            apiId: toolData.apiId?.toString(),
            apiSlug: toolData.apiSlug || apiSlug,
            apiName: toolData.apiName,
            baseUrl: toolData.baseUrl,
            iconSlug: iconSlug,
          },
          toolData: {
            toolId: toolData.id?.toString(),
            toolSlug: toolData.slug || toolSlug,
            toolName: toolData.name,
            apiId: toolData.apiId?.toString(),
            apiSlug: toolData.apiSlug || apiSlug,
            apiName: toolData.apiName,
            iconSlug: iconSlug,
            endpoint: toolData.endpoint,
            method: toolData.method,
            parameters: toolData.parameters || [],
            credentials: toolData.credentials || [],
            responses: toolData.responses || [],
          },
        };
      } finally {
        // Remove from in-flight map when complete
        inFlightRequests.delete(cacheKey);
      }
    })();

    // Store in in-flight map
    inFlightRequests.set(cacheKey, fetchPromise);

    try {
      return await fetchPromise;
    } catch (error) {
      console.error(`Error fetching tool data for ${apiSlug}/${toolSlug}:`, error);
      throw error;
    }
  }
  
  /**
   * Fetch tool data by tool slug only (no API slug)
   * OPTIMIZED: No longer fetches API data separately - uses apiSlug as icon fallback
   * PHASE 3.1: Request deduplication - reuses in-flight requests
   */
  static async fetchToolDataBySlug(toolSlug: string): Promise<ToolDataResult> {
    const cacheKey = toolSlug;

    // PHASE 3.1: Check for in-flight request
    const inFlight = inFlightRequests.get(cacheKey);
    if (inFlight) {
      console.log(`[ToolDataService] Reusing in-flight request for ${cacheKey}`);
      return inFlight;
    }

    // Create the fetch promise
    const fetchPromise = (async (): Promise<ToolDataResult> => {
      try {
        // Fetch tool details (with parameters) using the /details endpoint
        let toolData: any;
        try {
          toolData = await apiClient.get<any>(`/workflow-inspector/tools/${encodeURIComponent(toolSlug)}/details`);
        } catch {
          // Fallback to basic tool endpoint if details fails
          toolData = await apiClient.get<any>(`/workflow-inspector/tools/${encodeURIComponent(toolSlug)}`);
        }

        // Use iconSlug from toolData if available, otherwise use apiSlug as fallback
        const iconSlug = toolData.iconSlug || toolData.apiSlug || toolSlug;

        return {
          apiData: toolData.apiSlug ? {
            apiId: toolData.apiId?.toString(),
            apiSlug: toolData.apiSlug,
            apiName: toolData.apiName,
            baseUrl: toolData.baseUrl,
            iconSlug: iconSlug,
          } : undefined,
          toolData: {
            toolId: toolData.id?.toString(),
            toolSlug: toolData.slug || toolSlug,
            toolName: toolData.name,
            apiId: toolData.apiId?.toString(),
            apiSlug: toolData.apiSlug,
            apiName: toolData.apiName,
            iconSlug: iconSlug,
            endpoint: toolData.endpoint,
            method: toolData.method,
            parameters: toolData.parameters || [],
            credentials: toolData.credentials || [],
            responses: toolData.responses || [],
          },
        };
      } finally {
        // Remove from in-flight map when complete
        inFlightRequests.delete(cacheKey);
      }
    })();

    // Store in in-flight map
    inFlightRequests.set(cacheKey, fetchPromise);

    try {
      return await fetchPromise;
    } catch (error) {
      console.error(`Error fetching tool data for ${toolSlug}:`, error);
      throw error;
    }
  }
  
  /**
   * Fetch datasource data by datasource ID
   * Note: Datasources are managed by orchestrator-service
   * OPTIMIZED: Uses cache to avoid refetching during import session
   */
  static async fetchDataSourceData(dataSourceId: string | number, tenantId: string = 'google-oauth2|109706784165946220967'): Promise<ToolDataResult> {
    // Return minimal data immediately if fetch fails - don't block import
    // IMPORTANT: parseInt('0beafcbb-...') returns 0, not NaN! So we must verify the entire string is numeric.
    const dataSourceIdStr = String(dataSourceId);
    const isFullyNumeric = typeof dataSourceId === 'number' || /^\d+$/.test(dataSourceIdStr);
    const numericDataSourceId = isFullyNumeric
      ? (typeof dataSourceId === 'number' ? dataSourceId : parseInt(dataSourceIdStr, 10))
      : NaN;

    // If datasourceId is not a valid numeric ID (e.g., UUID starting with 0), use -1 as placeholder
    const safeDataSourceId = isNaN(numericDataSourceId) ? -1 : numericDataSourceId;
    const isInvalidId = !isFullyNumeric;

    const minimalDataSourceData: BuilderNodeData['dataSourceData'] = {
      dataSourceId: safeDataSourceId,
      dataSourceName: isInvalidId ? `DataSource` : `DataSource ${dataSourceId}`,
      tableName: undefined,
      schema: undefined,
    };

    // If datasourceId is not numeric, return minimal data with warning
    if (isInvalidId) {
      console.warn(`Invalid datasource ID format: "${dataSourceId}" (expected numeric ID, got UUID/string). Creating trigger with minimal data.`);
      return { dataSourceData: minimalDataSourceData };
    }

    try {
      // Use cached datasources if available, otherwise fetch and cache
      if (!this.dataSourcesCache) {
        this.dataSourcesCache = await orchestratorApi.getDataSources();
      }

      if (!Array.isArray(this.dataSourcesCache)) {
        console.warn(`Invalid response format for datasources. Creating trigger with minimal data.`);
        return { dataSourceData: minimalDataSourceData };
      }

      // Find the datasource with matching ID from cache
      const dataSource = this.dataSourcesCache.find((ds: any) => ds.id === numericDataSourceId);

      if (!dataSource) {
        console.warn(`Datasource ${dataSourceId} not found in tenant ${tenantId}. Creating trigger with minimal data.`);
        return { dataSourceData: minimalDataSourceData };
      }

      // Extract table name and schema from source_config if available
      const tableName = (dataSource as any).source_config?.table || (dataSource as any).name;
      const schema = (dataSource as any).source_config?.schema || (dataSource as any).source_config?.connection?.split('/').pop()?.split('?')[0] || 'public';

      return {
        dataSourceData: {
          dataSourceId: numericDataSourceId,
          dataSourceName: (dataSource as any).name || `DataSource ${dataSourceId}`,
          tableName: tableName,
          schema: schema,
        },
      };
    } catch (error) {
      // Catch any other unexpected errors
      console.warn(`Unexpected error fetching datasource data for ${dataSourceId}:`, error);
      console.warn(`Creating trigger with minimal datasource data. Import will continue.`);

      return { dataSourceData: minimalDataSourceData };
    }
  }
  
  /**
   * Fetch tool data based on tool_id from plan
   */
  static async fetchToolDataFromPlan(toolId: string): Promise<ToolDataResult> {
    const { apiSlug, toolSlug } = this.extractSlugs(toolId);
    
    if (apiSlug && toolSlug) {
      return this.fetchToolData(apiSlug, toolSlug);
    } else if (toolSlug) {
      return this.fetchToolDataBySlug(toolSlug);
    } else {
      throw new Error(`Invalid tool_id format: ${toolId}`);
    }
  }
  
  /**
   * Extract API slug and tool slug from tool_id
   * Handles formats:
   * - "api-slug/tool-slug" (with slash)
   * - "api-slug-tool-slug" (complete tool slug, no slash)
   */
  private static extractSlugs(toolId: string): { apiSlug?: string; toolSlug: string } {
    // First try to split by slash (format: "api-slug/tool-slug")
    const parts = toolId.split('/');
    if (parts.length === 2) {
      return { apiSlug: parts[0], toolSlug: parts[1] };
    }
    
    // If no slash, the toolId is the complete tool slug (format: "api-slug-tool-slug")
    // We'll use it directly and let the API return the apiSlug
    return { toolSlug: toolId };
  }
}

