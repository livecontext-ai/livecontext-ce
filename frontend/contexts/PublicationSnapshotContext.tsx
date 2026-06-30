'use client';

import React, { useEffect, useMemo, useSyncExternalStore, ReactNode } from 'react';

export interface DataSourceSnapshot {
  name?: string;
  description?: string;
  columnOrder?: string[];
  sourceType?: string;
  sourceConfig?: any;
  mappingSpec?: any;
  items?: Array<{ data: any; priority: number }>;
}

export interface AgentSnapshot {
  name?: string;
  description?: string;
  systemPrompt?: string;
  modelProvider?: string;
  modelName?: string;
  temperature?: number;
  maxTokens?: number;
  maxIterations?: number;
  executionTimeout?: number;
  inactivityTimeout?: number;
  avatarUrl?: string;
  config?: any;
  toolsConfig?: any;
  dataSourceId?: number;
  skills?: Array<{
    name: string;
    description?: string;
    icon?: string;
    instructions?: string;
    sortOrder?: number;
  }>;
}

export interface SubWorkflowSnapshot {
  plan: any;
  name: string;
  description?: string;
}

export interface InterfaceSnapshot {
  htmlTemplate?: string;
  cssTemplate?: string;
  jsTemplate?: string;
  data?: any;
}

export interface PublicationSnapshotContextValue {
  planSnapshot: any;
  getDataSourceSnapshot: (dataSourceId: number | string) => DataSourceSnapshot | null;
  getAgentSnapshot: (agentConfigId: string) => AgentSnapshot | null;
  getSubWorkflowSnapshot: (workflowId: string) => SubWorkflowSnapshot | null;
  getInterfaceSnapshot: (interfaceId: string) => InterfaceSnapshot | null;
}

/**
 * Module-level store for the active planSnapshot.
 *
 * Why not a React Context?
 * - The app layout renders <SidePanel /> as a sibling of the routed `{children}`,
 *   NOT as a descendant. Any React Context provided inside a page (like the
 *   marketplace preview page) is therefore NOT visible to side-panel tabs that
 *   the page opens - context lookups follow the rendered tree, not the JSX
 *   declaration order. A context-based snapshot would always resolve to null
 *   inside panel content and every panel would fall through to a live
 *   cross-tenant fetch (403 + data leak).
 *
 * - A module-level singleton + useSyncExternalStore bypasses the tree entirely,
 *   so ANY component anywhere in the app can read the active snapshot while
 *   the preview page is mounted, without threading props or wrapping every
 *   openTab() call site.
 *
 * Scope: the preview page is a leaf route; only one snapshot is active at a
 * time. If two previews ever needed to coexist the model would need to change.
 */
/**
 * Shape of the module-level snapshot state.
 *
 * <p>{@code publicationId} + {@code showcaseRunId} are populated by the
 * marketplace preview page so hooks like {@code useInterfaceRender} can
 * detect "I'm being called for a frozen showcase clone, route through the
 * public endpoint" - the alternative is auth'd {@code /api/interfaces/*}
 * calls that fail for anonymous visitors.
 */
interface StoredSnapshotState {
  planSnapshot: any;
  publicationId: string | null;
  showcaseRunId: string | null;
  /**
   * CE-cloud parity: the active preview is a CLOUD publication (cloud-linked CE),
   * so the gated showcase reads (showcase-render / run-state / aggregated-steps /
   * per-epoch state) must route through the CE backend's cloud proxy - the cloud
   * id is absent from the local DB. False for every cloud / authenticated-app
   * preview.
   */
  remote: boolean;
  /**
   * Acquirer/owner preview of a NON-PUBLIC publication (the publisher
   * unpublished/deleted it -> status INACTIVE, or it is PRIVATE). The anonymous
   * {@code /publications/by-id/.../showcase-render} 403s for non-public pubs, so
   * the interface render must hit the receipt-gated AUTH'D
   * {@code /publications/{id}/showcase-render} twin instead. Set by the preview
   * page only after the anonymous metadata read failed and the auth'd read
   * succeeded. Mutually exclusive with {@code remote} (cloud-linked CE keeps the
   * by-id proxy). False for every normal anonymous marketplace preview.
   */
  authenticated: boolean;
}

let storedState: StoredSnapshotState = { planSnapshot: null, publicationId: null, showcaseRunId: null, remote: false, authenticated: false };
const snapshotListeners = new Set<() => void>();

function subscribeSnapshot(cb: () => void): () => void {
  snapshotListeners.add(cb);
  return () => {
    snapshotListeners.delete(cb);
  };
}

function getStoredSnapshot(): StoredSnapshotState {
  return storedState;
}

function getServerSnapshot(): StoredSnapshotState {
  // SSR: no snapshot available until client-side mount of the preview page.
  return { planSnapshot: null, publicationId: null, showcaseRunId: null, remote: false, authenticated: false };
}

function publishSnapshot(next: StoredSnapshotState): void {
  if (
    storedState.planSnapshot === next.planSnapshot
    && storedState.publicationId === next.publicationId
    && storedState.showcaseRunId === next.showcaseRunId
    && storedState.remote === next.remote
    && storedState.authenticated === next.authenticated
  ) {
    return;
  }
  storedState = next;
  snapshotListeners.forEach((l) => l());
}

/**
 * Read-only access to the public preview context. Returns null when the
 * marketplace preview page is not mounted (authenticated app flows, etc.).
 * Consumed by interface-fetch hooks to decide between the public
 * {@code getShowcaseRender} endpoint and the auth'd {@code renderInterface}.
 */
export function getActivePublicPreview(): { publicationId: string; showcaseRunId: string; remote: boolean; authenticated: boolean } | null {
  if (!storedState.publicationId || !storedState.showcaseRunId) return null;
  return { publicationId: storedState.publicationId, showcaseRunId: storedState.showcaseRunId, remote: storedState.remote, authenticated: storedState.authenticated };
}

/**
 * Recursively walk the plan and any nested _snapshot_subworkflows[*].plan,
 * applying `predicate` until it returns a non-null match.
 * Used so that a sub-workflow opened in the side panel can resolve its own
 * tables/agents/sub-sub-workflows.
 */
export function findInPlans<T>(plan: any, predicate: (plan: any) => T | null): T | null {
  if (!plan || typeof plan !== 'object') return null;
  const direct = predicate(plan);
  if (direct !== null && direct !== undefined) return direct;

  const subs = plan._snapshot_subworkflows;
  if (subs && typeof subs === 'object') {
    for (const id in subs) {
      const subPlan = subs[id]?.plan;
      const found = findInPlans(subPlan, predicate);
      if (found) return found;
    }
  }
  return null;
}

/**
 * Marker check: a resource entry counts as "snapshotted" iff the publisher's
 * publish step wrote at least one `<prefix>*` field on it. This is more robust
 * than testing specific fields (e.g. `_snapshot_ds_name`), which may legitimately
 * be empty/null on a freshly-snapshotted resource and would otherwise cause the
 * panel to fall through to the live cross-tenant fetch (and 403).
 */
export function hasSnapshotPrefix(obj: any, prefix: string): boolean {
  if (!obj || typeof obj !== 'object') return false;
  for (const key in obj) {
    if (key.startsWith(prefix)) return true;
  }
  return false;
}

/**
 * Pure factory: builds the four snapshot lookup functions + exposes the raw
 * planSnapshot. Extracted from the Provider so it can be unit-tested without
 * a React environment.
 */
export function createSnapshotLookups(planSnapshot: any): PublicationSnapshotContextValue {
  return {
    planSnapshot,

    getDataSourceSnapshot: (dataSourceId) => {
      const target = String(dataSourceId);
      return findInPlans(planSnapshot, (plan) => {
        const tables = plan?.tables;
        if (!Array.isArray(tables)) return null;
        for (const t of tables) {
          if (String(t?.dataSourceId) !== target) continue;
          if (!hasSnapshotPrefix(t, '_snapshot_ds_')) continue;
          return {
            name: t._snapshot_ds_name,
            description: t._snapshot_ds_description,
            columnOrder: t._snapshot_ds_columnOrder,
            sourceType: t._snapshot_ds_sourceType,
            sourceConfig: t._snapshot_ds_sourceConfig,
            mappingSpec: t._snapshot_ds_mappingSpec,
            items: t._snapshot_ds_items,
          };
        }
        return null;
      });
    },

    getAgentSnapshot: (agentConfigId) => {
      const target = String(agentConfigId);
      return findInPlans(planSnapshot, (plan) => {
        const agents = plan?.agents;
        if (!Array.isArray(agents)) return null;
        for (const a of agents) {
          if (String(a?.agentConfigId) !== target) continue;
          if (!hasSnapshotPrefix(a, '_snapshot_agent_')) continue;
          return {
            name: a._snapshot_agent_name,
            description: a._snapshot_agent_description,
            systemPrompt: a._snapshot_agent_systemPrompt,
            modelProvider: a._snapshot_agent_modelProvider,
            modelName: a._snapshot_agent_modelName,
            temperature: a._snapshot_agent_temperature,
            maxTokens: a._snapshot_agent_maxTokens,
            maxIterations: a._snapshot_agent_maxIterations,
            executionTimeout: a._snapshot_agent_executionTimeout,
            inactivityTimeout: a._snapshot_agent_inactivityTimeout,
            avatarUrl: a._snapshot_agent_avatarUrl,
            config: a._snapshot_agent_config,
            toolsConfig: a._snapshot_agent_toolsConfig,
            dataSourceId: a._snapshot_agent_dataSourceId,
            skills: a._snapshot_agent_skills,
          };
        }
        return null;
      });
    },

    getSubWorkflowSnapshot: (workflowId) => {
      const target = String(workflowId);
      return findInPlans(planSnapshot, (plan) => {
        const subs = plan?._snapshot_subworkflows;
        if (subs && subs[target]) {
          return subs[target] as SubWorkflowSnapshot;
        }
        return null;
      });
    },

    getInterfaceSnapshot: (interfaceId) => {
      const target = String(interfaceId);
      return findInPlans(planSnapshot, (plan) => {
        const interfaces = plan?.interfaces;
        if (!Array.isArray(interfaces)) return null;
        for (const i of interfaces) {
          if (String(i?.id) !== target) continue;
          if (!hasSnapshotPrefix(i, '_snapshot_')) continue;
          return {
            htmlTemplate: i._snapshot_htmlTemplate,
            cssTemplate: i._snapshot_cssTemplate,
            jsTemplate: i._snapshot_jsTemplate,
            data: i._snapshot_data,
          };
        }
        return null;
      });
    },
  };
}

/**
 * Registers the publication's frozen planSnapshot with the module-level store
 * on mount and clears it on unmount. Writes via useEffect so render is pure.
 *
 * Intended to be rendered once by the marketplace preview page; children is
 * transparently passed through (no tree-scoped context - see store comment).
 */
export function PublicationSnapshotProvider({
  planSnapshot,
  publicationId = null,
  showcaseRunId = null,
  remote = false,
  authenticated = false,
  children,
}: {
  planSnapshot: any;
  /**
   * Publication UUID. When provided together with {@code showcaseRunId},
   * interface-fetch hooks route their calls through the public
   * {@code /api/publications/by-id/.../showcase-render} endpoint instead of
   * the auth'd {@code /api/interfaces/*}. Leave both null outside the
   * marketplace preview page.
   */
  publicationId?: string | null;
  /** Frozen {@code showcase_*} run ID tied to {@code publicationId}. */
  showcaseRunId?: string | null;
  /**
   * CE-cloud parity: the publication is a CLOUD one (cloud-linked CE preview),
   * so the gated showcase reads route through the cloud proxy. Default false.
   */
  remote?: boolean;
  /**
   * Acquirer/owner preview of a NON-PUBLIC publication: route the interface
   * render through the receipt-gated AUTH'D showcase endpoint so a publisher-
   * deleted (INACTIVE) or PRIVATE app still previews. Default false (normal
   * anonymous marketplace preview). Ignored when {@code remote} is set.
   */
  authenticated?: boolean;
  children: ReactNode;
}) {
  useEffect(() => {
    publishSnapshot({ planSnapshot, publicationId, showcaseRunId, remote, authenticated });
    // Cleanup is gated on publicationId match: when chat cards open back-to-back
    // (card A unmount → card B mount, or interleaved tab churn) React schedules
    // A's cleanup AFTER B's mount effect already ran. Without this guard A's
    // unmount would wipe the store and leave B's tree thinking it's no longer
    // in a preview - a fresh leak window. Compare against the LIVE store
    // identity, not the closure value, so the most recent publish always wins.
    const publishedId = publicationId;
    return () => {
      if (storedState.publicationId === publishedId) {
        publishSnapshot({ planSnapshot: null, publicationId: null, showcaseRunId: null, remote: false, authenticated: false });
      }
    };
  }, [planSnapshot, publicationId, showcaseRunId, remote, authenticated]);

  return <>{children}</>;
}

/**
 * Returns snapshot lookups when the preview page is mounted, otherwise null.
 * Reads from the module-level store via useSyncExternalStore so tabs rendered
 * outside the preview page's subtree (like side-panel tabs in the app layout)
 * still receive the snapshot.
 */
export function usePublicationSnapshot(): PublicationSnapshotContextValue | null {
  const state = useSyncExternalStore(subscribeSnapshot, getStoredSnapshot, getServerSnapshot);
  return useMemo(() => (state.planSnapshot ? createSnapshotLookups(state.planSnapshot) : null), [state.planSnapshot]);
}
