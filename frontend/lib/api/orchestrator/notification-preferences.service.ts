import { apiClient } from '../api-client';

/** The four groups a person chooses a delivery for. */
export type NotificationTopic = 'FAILURES' | 'CREDITS' | 'ACCOUNT' | 'TASKS';

/** Where a topic reaches the person besides the bell, which always gets it. */
export type NotificationDelivery = 'OFF' | 'EMAIL' | 'CHANNEL' | 'BOTH';

export interface NotificationTopicPreference {
  topic: NotificationTopic;
  delivery: NotificationDelivery;
  defaultDelivery: NotificationDelivery;
  /** False when the plan does not include email for this topic (credit alerts always do). */
  emailAvailable: boolean;
  /**
   * The choice applies in every workspace of the person (credits: the wallet is theirs and the
   * alert lands in their personal workspace, whose channel it uses, not the active one's).
   */
  personScoped: boolean;
}

export interface NotificationPreferences {
  topics: NotificationTopicPreference[];
  /** The plan needed for email alerts, or null when the current plan includes them. */
  emailRequiredPlan: string | null;
  /** The active workspace's default chat destination, where channel alerts go. */
  channel: { connected: boolean; channel: string | null; title: string | null };
}

/** A person's notification delivery in the ACTIVE workspace (the gateway supplies it). */
class NotificationPreferencesService {
  async get(): Promise<NotificationPreferences> {
    return apiClient.get<NotificationPreferences>('/notifications/preferences');
  }

  async update(topic: NotificationTopic, delivery: NotificationDelivery): Promise<NotificationPreferences> {
    return apiClient.put<NotificationPreferences>('/notifications/preferences', { topic, delivery });
  }
}

export const notificationPreferencesService = new NotificationPreferencesService();
