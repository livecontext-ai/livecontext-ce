/**
 * Resolves the FORMAT of the interfaces a plan renders, so the import knows the box
 * each interface node is going to paint before it has painted anything.
 *
 * <h3>Why the import needs this at all</h3>
 *
 * Every other sized node carries its box in the plan (a note's `width`, a data-input's
 * `dataInputWidth`). An interface node does not: its box is snapped from the FORMAT of
 * the interface entity it renders (`snapBoxToFormat`), which lives in another service
 * and is not part of the workflow plan. So an interface node was imported at the
 * historical 400x250 default, and the automatic layout reserved that box for a node
 * that goes on to paint, say, 283x400 for an A4 page: the node ends up off its own axis
 * and the next rank lands inside it.
 *
 * Resolving the format here makes the FIRST layout right, on every entry point, instead
 * of relying on a post-paint correction - which only the agent plan-sync can run
 * (`MeasuredLayoutSync`), never a plain load, where a position write would arm Save and
 * the undo stack on a workflow the user merely opened.
 *
 * <h3>It reads the caller's cache, it does not keep one</h3>
 *
 * The lookup goes through the React Query client the CALLER hands down, under the SAME
 * key the interface node itself uses (`['interface', id]`, {@link useInterfaceById}). In
 * edit mode that makes it free: the node is about to fetch that entity anyway, so one of
 * the two is served from cache, and both share one entry, one TTL and one invalidation -
 * the refetch the inspector runs after saving a page, and the `interfaceModified` the
 * chat fires when an agent rewrites one. A private memo here would duplicate the request
 * and then have to re-derive both of those rules, badly. The client is a PARAMETER for
 * the reason `importPlan` already takes the layout direction as one: a plain service must
 * not reach into a React context, and a module-level singleton would hand the wrong
 * client to a page that mounts its own (`/s/[token]` does).
 *
 * <h3>What is NOT asked for</h3>
 *
 * - Any page of a PUBLICATION SNAPSHOT plan. Those entries carry `_snapshot_*` fields;
 *   the publish step also writes `_snapshot_format` (since the format moved onto the
 *   entity), which the creator reads directly. A snapshot plan describes pages that are
 *   not the reader's to query - the reader is usually anonymous - so no `_snapshot_`
 *   entry is ever looked up, whether or not it carries the format key.
 * - A node rendered COMPACT (`showPreview: false`). It is laid out from its label like
 *   any other node, so its page's shape is irrelevant to it.
 * - Anything inside a marketplace preview, which is anonymous by construction.
 *
 * <h3>A run is asked a different question</h3>
 *
 * A run paints the format FROZEN when it started (`interface_run_snapshots.format`), so
 * the live format would be the wrong answer for it: a page reformatted since the run still
 * renders at its old shape. A run canvas therefore reads the run's OWN snapshots, all of
 * them in a single request rather than one per page, and falls back to the live format for
 * a page the run has no snapshot row for - which is exactly what `getTemplateConfigForRun`
 * does server-side, and therefore exactly what such a page paints.
 *
 * Unlike edit mode, this read is NOT free: a run canvas never asks for an interface entity
 * (`useInterfaceById` is off there), so this is one request that surface did not make before,
 * plus one per page the run has no snapshot row for, and the listing carries each page's whole
 * source to be asked one word of it. It buys the only thing that can fix a run canvas, whose
 * layout has no post-paint corrector either, and the deadline below caps what it can cost. The
 * bulk would go away with a format-only projection on that endpoint.
 *
 * <h3>It may never gate the canvas</h3>
 *
 * `createNodes` awaits this before it creates a single node, so a slow or failing page
 * would otherwise blank the builder for as long as the request takes (the app's client
 * retries 5xx three times with backoff, and apiClient's own timeout is 30s). The box is
 * a layout refinement: retries are off, and the whole resolution is raced against a
 * short deadline after which the import proceeds with whatever came back.
 *
 * A failure is deliberately not remembered either, so a plan pointing at a deleted page
 * re-asks (and warns) on every import, the plan-sync included. That is one 404 per edit
 * against never noticing the page came back, and the first is the cheaper mistake.
 *
 * The blocking is accepted even on a plan that will NOT be laid out (one whose nodes all
 * carry positions, where the importer keeps them and the box only feeds the node's own
 * snap). Gating on that would mean predicting the layout decision from the plan BEFORE the
 * nodes exist, and the two disagreeing by one synthesised node is exactly how this defect
 * comes back - silently, on the path with no corrector. A warm cache answers in a tick,
 * a cold one within the deadline, and either is cheaper than that risk.
 */

import type { QueryClient } from '@tanstack/react-query';
import { orchestratorApi } from '@/lib/api';
import { getActivePublicPreview } from '@/contexts/PublicationSnapshotContext';

/** Plan interface entry, narrowed to what decides whether its format must be fetched. */
export interface InterfaceFormatCandidate {
  id?: string;
  showPreview?: boolean;
  [snapshotField: string]: unknown;
}

/**
 * Format string per interface id (`null` = the page declares none, which the node
 * renders at the classic 1280x800 viewport). An id ABSENT from the map is one we could
 * not resolve: callers must keep their historical default for it rather than guess.
 */
export type InterfaceFormatMap = ReadonlyMap<string, string | null>;

/** The plan key a publication snapshot carries its page's format in. */
export const SNAPSHOT_FORMAT_KEY = '_snapshot_format';

/** Prefix of every field the publish step enriches a snapshot plan's pages with. */
const SNAPSHOT_FIELD_PREFIX = '_snapshot_';

/** Same freshness the interface node's own query uses, so the two share one entry. */
const INTERFACE_STALE_TIME_MS = 5 * 60 * 1000;

/**
 * How long the WHOLE resolution may hold the import up - every page is asked at once, so
 * this is each one's budget as much as the total. Long enough for a healthy round trip,
 * short enough that a struggling one costs a layout refinement rather than a canvas:
 * past it the import continues and every page that has not answered keeps its default.
 *
 * A plan whose pages answer unevenly can therefore be laid out with some real boxes and
 * some defaults, and on the load path nothing corrects the difference afterwards. That
 * is the accepted cost of never blanking the canvas; making it rarer means making the
 * read cheap (the endpoint returns the page's whole source to be asked one word of it),
 * which is a change to that endpoint, not to this caller.
 */
const RESOLVE_DEADLINE_MS = 1_500;

/** Same freshness the run's other reads use: a re-fire can re-stamp a run's snapshots. */
const RUN_SNAPSHOT_STALE_TIME_MS = 30 * 1000;

/** What the import needs to know about the surface it is running on. */
export interface InterfaceFormatContext {
  /** The caller's React Query client, so this shares the node's own cache entry. */
  queryClient?: QueryClient;
  /** A canvas showing a run reads the formats that run froze, not the live ones. */
  isRunMode?: boolean;
  /**
   * The run's INTERNAL id, when `isRunMode`. Not the public `run_...` the URL carries: the
   * snapshots are keyed by the uuid and the endpoint rejects anything else. Without it a
   * run canvas resolves nothing, rather than a live format it is not going to paint.
   */
  workflowRunId?: string | null;
}

export class InterfaceFormatService {
  /**
   * Ids whose format has to be resolved: the pages a node actually previews, minus the
   * ones that belong to a publication snapshot.
   */
  static candidateIds(interfaces: readonly InterfaceFormatCandidate[] | undefined): string[] {
    if (!Array.isArray(interfaces)) return [];
    const ids = interfaces
      .filter((iface) => iface?.showPreview !== false && !isSnapshotEntry(iface))
      .map((iface) => iface?.id)
      .filter((id): id is string => typeof id === 'string' && id.length > 0);
    return [...new Set(ids)];
  }

  /**
   * Resolve the declared format of each page. Best-effort by design: the box is a
   * layout refinement, so a page that fails to answer, or answers too late, must import
   * at the historical default rather than fail or delay the whole plan.
   */
  static async fetchFormats(
    interfaceIds: readonly string[],
    context: InterfaceFormatContext = {},
  ): Promise<InterfaceFormatMap> {
    const formats = new Map<string, string | null>();
    // A marketplace visitor cannot call the authenticated interfaces endpoint at all.
    if (interfaceIds.length === 0 || getActivePublicPreview()) return formats;

    const resolved = context.isRunMode
      ? readRunFormats(interfaceIds, formats, context)
      : Promise.all(interfaceIds.map(async (id) => {
        try {
          const details = await readInterface(id, context.queryClient);
          formats.set(id, details?.format ?? null);
        } catch (error) {
          console.warn(`[InterfaceFormat] Could not resolve the format of ${id}:`, error);
        }
      }));

    // Whatever HAS answered by the deadline is used, and a straggler simply does not make
    // it into this import. The copy matters: the losing branch of the race keeps writing
    // into `formats`, and the caller must not see a page appear halfway through its own
    // work.
    await withDeadline(resolved, RESOLVE_DEADLINE_MS);
    return new Map(formats);
  }
}

/**
 * A page carried by a publication snapshot. An ENRICHED entry has `_snapshot_*` fields;
 * the format key is one of them on anything published since the format moved onto the
 * interface entity, and an older snapshot simply has no format to read - in neither case
 * is the live entity ours to query. An entry the publish step skipped (no id, or an id
 * that is not a UUID) carries no such field and is not recognised here; the surfaces that
 * read a snapshot are gated a second time, by the preview flag and by run mode.
 */
function isSnapshotEntry(iface: InterfaceFormatCandidate | undefined): boolean {
  return !!iface && Object.keys(iface).some((key) => key.startsWith(SNAPSHOT_FIELD_PREFIX));
}

/** Wait for `work`, or give up after `ms`. The timer is always cleared, either way. */
async function withDeadline(work: Promise<unknown>, ms: number): Promise<void> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    await Promise.race([work, new Promise<void>((resolve) => { timer = setTimeout(resolve, ms); })]);
  } finally {
    if (timer !== undefined) clearTimeout(timer);
  }
}

/**
 * The formats a RUN froze, in one request for the whole run. A page the run has no
 * snapshot for (added to the plan after the run started, or a run predating the snapshot)
 * is left out, and keeps the plan's box like any other unresolved page.
 */
async function readRunFormats(
  interfaceIds: readonly string[],
  formats: Map<string, string | null>,
  context: InterfaceFormatContext,
): Promise<void> {
  const runId = context.workflowRunId;
  if (!runId) return;
  // Ids are matched case-insensitively: the plan carries whatever spelling of the uuid it
  // was written with, the server answers with its own, and the map is keyed by the PLAN's
  // so the creator can look it up.
  const wanted = new Map(interfaceIds.map((id) => [id.toLowerCase(), id]));
  try {
    const snapshots = await readSnapshots(runId, context.queryClient);
    for (const snapshot of snapshots ?? []) {
      const planId = wanted.get(String(snapshot.interfaceId).toLowerCase());
      if (planId) formats.set(planId, snapshot.format ?? null);
    }
  } catch (error) {
    console.warn(`[InterfaceFormat] Could not read the formats run ${runId} froze:`, error);
  }

  // A page the run froze no row for paints its LIVE format, and the render path falls back the
  // same way, so it is resolved the same way rather than left at the default box.
  //
  // Where the two part company: after a FAILED listing every page lands here, and the server
  // would still be reading rows we could not. Live is then a guess - right whenever nobody has
  // reformatted the page since the run, which is nearly always - and the alternative is the
  // 400x250 default, which is wrong for every page that is not classic.
  await Promise.all(interfaceIds.filter((id) => !formats.has(id)).map(async (id) => {
    try {
      const details = await readInterface(id, context.queryClient);
      formats.set(id, details?.format ?? null);
    } catch (error) {
      console.warn(`[InterfaceFormat] Could not resolve the format of ${id}:`, error);
    }
  }));
}

/** Read a run's snapshots through the caller's query cache, else directly. */
async function readSnapshots(
  runId: string,
  client?: QueryClient,
): Promise<Array<{ interfaceId: string; format?: string | null }>> {
  if (!client) return orchestratorApi.getInterfaceSnapshotsForRun(runId);
  return client.fetchQuery({
    queryKey: ['interface-run-snapshots', runId],
    queryFn: () => orchestratorApi.getInterfaceSnapshotsForRun(runId),
    staleTime: RUN_SNAPSHOT_STALE_TIME_MS,
    retry: false,
  });
}

/** Read one page through the caller's query cache, falling back to a direct call. */
async function readInterface(
  id: string,
  client?: QueryClient,
): Promise<{ format?: string | null } | undefined> {
  if (!client) return orchestratorApi.getInterface(id);
  return client.fetchQuery({
    queryKey: ['interface', id],
    queryFn: () => orchestratorApi.getInterface(id),
    staleTime: INTERFACE_STALE_TIME_MS,
    // The app's client retries a 5xx three times with backoff. That is right for data a
    // component needs and wrong for a box: this call blocks node creation.
    retry: false,
  });
}
