/**
 * Widget API service for public (unauthenticated) widget endpoints.
 * Uses raw fetch since these endpoints don't require authentication tokens.
 */

export interface WidgetConfig {
  position: string;
  theme: string;
  primaryColor: string;
  welcomeMessage: string;
  bubbleText: string;
  showAvatar: boolean;
  autoOpenDelay: number;
  agentName: string;
  agentAvatarUrl?: string;
}

export interface WidgetSession {
  sessionId: string;
  conversationId: string;
}

export interface WidgetChatResponse {
  content: string;
  conversationId: string;
}

export interface WidgetMessage {
  id?: string;
  role: 'user' | 'assistant';
  content: string;
  createdAt?: string;
}

interface WidgetApiResponse<T = Record<string, unknown>> {
  status: string;
  message?: string;
  data?: T;
}

export class WidgetApiService {
  private baseUrl: string;
  private token: string;

  constructor(token: string) {
    this.token = token;
    // Widget embed page is served through the gateway, so API calls
    // go directly to /widget/{token}/... on the same origin (gateway).
    this.baseUrl = '/widget';
  }

  async getConfig(): Promise<WidgetConfig | null> {
    try {
      const res = await fetch(`${this.baseUrl}/${this.token}/config`, {
        method: 'GET',
        headers: { 'Content-Type': 'application/json' },
      });

      if (!res.ok) return null;

      const body: WidgetApiResponse<WidgetConfig> = await res.json();
      if (body.status !== 'success' || !body.data) return null;

      return body.data;
    } catch {
      return null;
    }
  }

  async createSession(): Promise<WidgetSession | null> {
    try {
      const res = await fetch(`${this.baseUrl}/${this.token}/session`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
      });

      if (!res.ok) return null;

      const body: WidgetApiResponse<WidgetSession> = await res.json();
      if (body.status !== 'success' || !body.data) return null;

      return body.data;
    } catch {
      return null;
    }
  }

  async sendMessage(sessionId: string, message: string): Promise<WidgetChatResponse | null> {
    try {
      const res = await fetch(`${this.baseUrl}/${this.token}/chat`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Widget-Session': sessionId,
          'Accept': 'application/json',
        },
        body: JSON.stringify({ message }),
      });

      if (!res.ok) return null;

      const body: WidgetApiResponse<WidgetChatResponse> = await res.json();
      if (body.status !== 'success' || !body.data) return null;

      return body.data;
    } catch {
      return null;
    }
  }

  async getHistory(sessionId: string): Promise<WidgetMessage[]> {
    try {
      const res = await fetch(`${this.baseUrl}/${this.token}/history`, {
        method: 'GET',
        headers: {
          'Content-Type': 'application/json',
          'X-Widget-Session': sessionId,
        },
      });

      if (!res.ok) return [];

      const body: WidgetApiResponse<{ messages: Array<{ id?: string; role?: string; content?: string; createdAt?: string }> }> = await res.json();
      if (body.status !== 'success' || !body.data?.messages) return [];

      return body.data.messages.map((msg) => ({
        id: msg.id,
        role: msg.role === 'assistant' ? 'assistant' as const : 'user' as const,
        content: msg.content || '',
        createdAt: msg.createdAt,
      }));
    } catch {
      return [];
    }
  }
}
