/**
 * The chat services a workspace can be reached on, shared by the approval node's
 * delegation section and the Settings > Channels page.
 *
 * Mirrors the backend `ChatChannelConnectorRegistry.KNOWN_CHANNELS` (pinned there by
 * `KnownChannelsTest`); the ids are the values the plan's `approval.delegation.channel`
 * and the `channel` tool take. `integration` is the credential integration name the
 * connection is stored under (the catalog iconSlug, not always the channel id).
 * Brand names are not translated.
 *
 * `accountSetting` is the one value a service needs at connect beyond its credential (the backend
 * connector's accountSettingLabel), and `discoversChats` whether its chats can be listed to pick
 * from (WhatsApp cannot: the destination is a phone number).
 */
/** One cache entry for the workspace's destinations (under useOrgScopedQuery), shared by every reader. */
export const CHAT_CHANNELS_QUERY_KEY = 'chat-channels';

export const CHAT_CHANNELS = [
  { id: 'telegram', label: 'Telegram', integration: 'telegram', identifiesPresser: true, sendsImages: true,
    accountSetting: null, discoversChats: true },
  { id: 'slack', label: 'Slack', integration: 'slack', identifiesPresser: true, sendsImages: false,
    accountSetting: null, discoversChats: true },
  { id: 'discord', label: 'Discord', integration: 'discord', identifiesPresser: true, sendsImages: false,
    accountSetting: 'publicKey', discoversChats: true },
  { id: 'whatsapp', label: 'WhatsApp', integration: 'whatsapp', identifiesPresser: true, sendsImages: false,
    accountSetting: 'phoneNumberId', discoversChats: false },
  { id: 'teams', label: 'Microsoft Teams', integration: 'microsoftteams', identifiesPresser: false, sendsImages: false,
    accountSetting: null, discoversChats: true },
] as const;

export type ChatChannelId = (typeof CHAT_CHANNELS)[number]['id'];

export type ChatChannelInfo = (typeof CHAT_CHANNELS)[number];

/** The channel's descriptor, or undefined for an id this build does not know. */
export function findChatChannel(id: string | undefined | null): ChatChannelInfo | undefined {
  return CHAT_CHANNELS.find((channel) => channel.id === id);
}

/**
 * The channel's descriptor, falling back to Telegram for a missing or unknown id. For an editor
 * whose value defaults to Telegram; anything that DISPLAYS a stored channel uses findChatChannel,
 * so an unknown id is shown as itself rather than mislabelled.
 */
export function chatChannelInfo(id: string | undefined | null): ChatChannelInfo {
  return CHAT_CHANNELS.find((channel) => channel.id === id) ?? CHAT_CHANNELS[0];
}
