/**
 * Home-status service - single round-trip for the chat welcome view.
 * Returns automations + bell items + unread count from one endpoint.
 *
 * Single Responsibility: read-only "what's running + what needs me" payload.
 */

import { apiClient } from '../api-client';
import type { ActiveAutomation } from './dashboard.service';

/**
 * Stable union of subject types the bell knows how to route. Mirrors the
 * backend `SubjectNameResolver` constants. Used for exhaustive switch
 * checking in `NotificationBell.handleRowClick` - adding a new subject
 * type in the backend should produce a TypeScript compile error here until
 * the routing switch is updated.
 */
export type SubjectType =
  | 'WORKFLOW'
  | 'CREDENTIAL'
  | 'AGENT_TASK'
  | 'APPLICATION'
  | 'TRIGGER'
  | 'ORG_INVITATION';

/**
 * Single notification item - aggregated per (subjectId, category).
 * Mirrors backend `NotificationItem` record exactly.
 *
 * P2a multi-category: `category` discriminates the kind of event so the bell
 * renders distinct labels per category (e.g. "X failed runs" vs
 * "Y pending approvals") even when the same subject has multiple categories.
 *
 * P7 multi-subject-type: `subjectType` drives frontend routing.
 * `runIdPublic` is WORKFLOW-only by emitter contract.
 * `integration` and `credentialId` are CREDENTIAL-only payload fields used
 * by the bell to deep-link to `/app/settings/credentials?credentialId=<id>`
 * and to render the integration's ServiceIcon on the row.
 */
export interface NotificationItem {
  /** UUID of the subject (any subject_type; was `workflowId` pre-P7). */
  subjectId: string;
  /** User-facing label resolved by the matching SubjectNameResolver. */
  subjectName: string;
  /** Drives frontend routing in `NotificationBell.handleRowClick`. */
  subjectType: SubjectType;
  /** WORKFLOW-only; null for non-WORKFLOW rows by emitter contract. */
  runIdPublic: string | null;
  /** e.g. "RUN_FAILED" | "APPROVAL_PENDING" | "CRED_EXPIRED". */
  category: string;
  severity: 'error' | 'warning' | 'info';
  count: number;
  firstEventAt: string;
  lastEventAt: string;
  unread: boolean;
  /** CREDENTIAL-only: integration slug (e.g. "googlecalendar") for ServiceIcon. */
  integration?: string | null;
  /** CREDENTIAL-only: stringified `auth.credentials.id` for click-through routing. */
  credentialId?: string | null;
  /**
   * TRIGGER-only: lowercase trigger kind from the emitter payload. Mirrors the
   * four constants written by {@code TriggerLifecycleManager.emitTriggerDisabledAfterCommit}
   * - adding a fifth on the backend should produce a compile error here until
   * the {@code triggerKindIcon} switch + the `?tab=` routing in
   * {@code NotificationBell.notificationHref} are updated. Drives the per-row
   * icon and the `?tab=` deep-link on `/app/settings/public-access`.
   */
  triggerKind?: 'schedule' | 'webhook' | 'chat' | 'form' | null;
}

/**
 * One-shot home-page payload.
 *
 * @property automations  pinned workflows / applications / agents with armed
 *                        triggers (forward-looking, "what's about to fire")
 * @property items        aggregated failed-pinned-run notifications
 *                        (reverse-looking, "what needs the user")
 * @property unreadCount  badge count from server (don't recompute client-side)
 * @property lastSeenAt   user's read-state cursor - informational
 */
export interface HomeStatus {
  automations: ActiveAutomation[];
  items: NotificationItem[];
  unreadCount: number;
  lastSeenAt: string | null;
}

/** Paginated bell payload returned by GET /api/notifications. */
export interface NotificationsPage {
  items: NotificationItem[];
  unreadCount: number;
  page: number;
  size: number;
  hasMore: boolean;
}

/** Reference to a single bucket - argument to the delete-batch endpoint. */
export interface NotificationBucketRef {
  subjectId: string;
  category: string;
}

class HomeStatusService {
  async getHomeStatus(): Promise<HomeStatus> {
    return apiClient.get<HomeStatus>('/dashboard/home-status');
  }

  async markAllNotificationsRead(): Promise<void> {
    await apiClient.post('/notifications/read', {});
  }

  /**
   * Paginated bell fetch. Page is zero-based; size is clamped server-side
   * to the {@code MAX_BUCKETS} cap (40). {@code unreadCount} is global -
   * doesn't change as the user paginates.
   */
  async getNotificationsPage(page: number, size: number): Promise<NotificationsPage> {
    return apiClient.get<NotificationsPage>('/notifications', {
      params: { page: String(page), size: String(size) },
    });
  }

  /**
   * Bulk-delete buckets. Single-row delete is a 1-element array. Empty
   * array is a no-op (200 OK, deleted=0). Returns the actual number of DB
   * rows removed (a bucket may aggregate multiple rows).
   */
  async deleteNotificationBuckets(buckets: NotificationBucketRef[]): Promise<{ deleted: number }> {
    return apiClient.post<{ deleted: number }>('/notifications/delete-batch', buckets);
  }
}

export const homeStatusService = new HomeStatusService();
