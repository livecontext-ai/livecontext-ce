import { apiClient } from '../api-client';

/** One connected destination, as `GET /chat-channels` lists it. */
export interface ChatChannelSummary {
  linkId: string;
  channel: string;
  credentialId: number | null;
  botUsername: string | null;
  chatId: string;
  chatTitle: string | null;
  chatType: string | null;
  isDefault: boolean;
  active: boolean;
  /** Null = nothing was ever delivered there. */
  verifiedAt: string | null;
  lastError: string | null;
  /** Empty = anyone in the chat may decide. */
  allowedUserIds: string[];
}

export interface ChatChannelListResponse {
  channels: ChatChannelSummary[];
}

/** A chat the account can post to, offered to pick from (`POST /chat-channels/discover`). */
export interface ChatCandidate {
  chatId: string;
  title: string | null;
  type: string | null;
  fromUsername: string | null;
}

export interface ChatChannelDiscovery {
  channel: string;
  credentialId: number;
  botUsername: string | null;
  chats: ChatCandidate[];
  /**
   * Null when the list was read. Otherwise why it could not be (on Telegram: the bot is already
   * connected) and what to give instead. The bot is still named, so it can be opened.
   */
  notice?: string | null;
}

export interface ChatChannelConnectRequest {
  channel: string;
  credentialId: number | null;
  chatId: string;
  chatTitle?: string | null;
  chatType?: string | null;
  /** The service's extra value at connect: Discord's public key, WhatsApp's phone number id. */
  accountSetting?: string | null;
}

export interface ChatChannelConnectResult {
  channel: ChatChannelSummary;
  /** True only when the test message really arrived: the one proof the destination works. */
  delivered: boolean;
  warning: string | null;
  /** What is left to do in the service's own console (a URL to paste, a verify token). */
  setupInstructions: string | null;
}

/**
 * The workspace's connected chat destinations: list, discover and connect (the manual setup, which
 * costs no assistant turn), move the default, disconnect.
 */
class ChatChannelService {
  async list(): Promise<ChatChannelListResponse> {
    return apiClient.get<ChatChannelListResponse>('/chat-channels');
  }

  async discover(channel: string, credentialId: number | null): Promise<ChatChannelDiscovery> {
    return apiClient.post<ChatChannelDiscovery>('/chat-channels/discover', { channel, credentialId });
  }

  async connect(request: ChatChannelConnectRequest): Promise<ChatChannelConnectResult> {
    return apiClient.post<ChatChannelConnectResult>('/chat-channels', request);
  }

  async setDefault(linkId: string): Promise<ChatChannelSummary> {
    return apiClient.post<ChatChannelSummary>(`/chat-channels/${encodeURIComponent(linkId)}/default`, {});
  }

  async disconnect(linkId: string): Promise<void> {
    await apiClient.delete(`/chat-channels/${encodeURIComponent(linkId)}`);
  }
}

export const chatChannelService = new ChatChannelService();
