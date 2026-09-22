import type { BuilderNodeData, PaletteDragItem } from '../types';
import { getPaletteItemDataFromId } from '../nodes/nodeClasses';
import type { CatalogTool } from '../hooks/useMcpData';
import { triggerKey } from '../utils/labelNormalizer';

export type TriggerShortcutId =
  | 'gmail-new-email'
  | 'outlook-new-email'
  | 'telegram-update'
  | 'slack-event'
  | 'github-event'
  | 'google-drive-file';

type PollingShortcutDefinition = {
  id: TriggerShortcutId;
  mode: 'polling';
  iconSlug: string;
  apiSlug: string;
  apiName: string;
  toolSlug: string;
  method: string;
  cron: string;
  paramExpressions?: Record<string, string>;
};

type WebhookShortcutDefinition = {
  id: TriggerShortcutId;
  mode: 'webhook';
  iconSlug: string;
  apiSlug: string;
  apiName: string;
  toolSlug: string;
  payloadParams: Record<string, string>;
};

export type TriggerShortcutDefinition = PollingShortcutDefinition | WebhookShortcutDefinition;

export const TRIGGER_SHORTCUTS: TriggerShortcutDefinition[] = [
  {
    id: 'gmail-new-email',
    mode: 'polling',
    iconSlug: 'gmail',
    apiSlug: 'gmail',
    apiName: 'Gmail',
    toolSlug: 'list_messages',
    method: 'GET',
    cron: '* * * * *',
    paramExpressions: { userId: 'me', q: 'is:unread', maxResults: '25' },
  },
  {
    id: 'outlook-new-email',
    mode: 'polling',
    iconSlug: 'microsoftoutlook',
    apiSlug: 'microsoft_outlook',
    apiName: 'Microsoft Outlook',
    toolSlug: 'list_messages',
    method: 'GET',
    cron: '* * * * *',
    paramExpressions: {
      '$filter': 'isRead eq false',
      '$orderby': 'receivedDateTime desc',
      '$top': '25',
    },
  },
  {
    id: 'telegram-update',
    mode: 'webhook',
    iconSlug: 'telegram',
    apiSlug: 'telegram',
    apiName: 'Telegram',
    toolSlug: 'get_chat',
    payloadParams: { chat_id: 'message.chat.id' },
  },
  {
    id: 'slack-event',
    mode: 'webhook',
    iconSlug: 'slack',
    apiSlug: 'slack',
    apiName: 'Slack',
    toolSlug: 'get_conversation_info',
    payloadParams: { channel: 'event.channel' },
  },
  {
    id: 'github-event',
    mode: 'webhook',
    iconSlug: 'github',
    apiSlug: 'github',
    apiName: 'GitHub',
    toolSlug: 'get_repo',
    payloadParams: { owner: 'repository.owner.login', repo: 'repository.name' },
  },
  {
    id: 'google-drive-file',
    mode: 'polling',
    iconSlug: 'googledrive',
    apiSlug: 'google_drive',
    apiName: 'Google Drive',
    toolSlug: 'list_files',
    method: 'GET',
    cron: '*/5 * * * *',
    paramExpressions: {
      q: 'trashed = false',
      orderBy: 'modifiedTime desc',
      pageSize: '25',
    },
  },
];

export interface TriggerShortcutCopy {
  description: string;
  triggerLabel: string;
  actionLabel?: string;
}

export interface TriggerShortcutNodeSpec {
  item: PaletteDragItem;
  offset: number;
}

export interface TriggerShortcutSelection {
  selectionType: 'trigger-shortcut';
  shortcutId: TriggerShortcutId;
  nodes: TriggerShortcutNodeSpec[];
  connections: Array<{ sourceIndex: number; targetIndex: number }>;
}

export function isTriggerShortcutSelection(value: unknown): value is TriggerShortcutSelection {
  return !!value
    && typeof value === 'object'
    && (value as TriggerShortcutSelection).selectionType === 'trigger-shortcut';
}

export function buildTriggerShortcutSelection(
  definition: TriggerShortcutDefinition,
  copy: TriggerShortcutCopy,
  catalog: CatalogTool,
): TriggerShortcutSelection {
  const triggerId = definition.mode === 'polling' ? 'schedule-trigger' : 'webhook-trigger';
  const triggerItem = getPaletteItemDataFromId(triggerId, copy.triggerLabel, copy.description);

  if (!triggerItem) {
    throw new Error(`Missing palette node class: ${triggerId}`);
  }

  const triggerInitialData: Partial<BuilderNodeData> = definition.mode === 'polling'
    ? {
        scheduleTriggerData: {
          cronExpression: definition.cron,
          timezone: 'UTC',
          maxExecutions: null,
        },
      }
    : {
        webhookTriggerData: { httpMethod: 'POST', authType: 'none' },
      };

  const triggerNode: TriggerShortcutNodeSpec = {
    item: { ...triggerItem, initialData: triggerInitialData },
    offset: 0,
  };

  const { api, tool, details } = catalog;
  const paramExpressions = definition.mode === 'polling'
    ? definition.paramExpressions
    : Object.fromEntries(Object.entries(definition.payloadParams).map(([name, path]) => [
        name, `{{${triggerKey(copy.triggerLabel)}.output.payload.${path}}}`,
      ]));
  const parameterNames = new Set(details.parameters.map((parameter: { name: string }) => parameter.name));
  if (Object.keys(paramExpressions || {}).some((name) => !parameterNames.has(name))) {
    throw new Error(`Catalog parameters do not match application trigger: ${tool.slug}`);
  }

  const toolNode: TriggerShortcutNodeSpec = {
    item: {
      id: `tool-${tool.slug}`,
      label: copy.actionLabel || tool.name,
      description: copy.description,
      kind: 'tool',
      nodeType: 'flowNode',
      initialData: {
        apiData: {
          apiSlug: api.slug,
          apiName: api.apiName,
          iconSlug: tool.iconSlug || api.iconSlug,
          iconUrl: tool.iconUrl || api.iconUrl,
        },
        toolData: {
          toolId: tool.toolId,
          toolSlug: tool.slug,
          toolName: tool.name,
          apiSlug: api.slug,
          apiName: api.apiName,
          method: tool.method,
          iconSlug: tool.iconSlug || api.iconSlug,
          iconUrl: tool.iconUrl || api.iconUrl,
          parameters: details.parameters,
          credentials: details.credentials || [],
          responses: details.responses || [],
        },
        paramExpressions,
      },
    },
    offset: 1,
  };

  return {
    selectionType: 'trigger-shortcut',
    shortcutId: definition.id,
    nodes: [triggerNode, toolNode],
    connections: [{ sourceIndex: 0, targetIndex: 1 }],
  };
}
