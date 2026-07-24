/**
 * Input Label Registry - Maps node types to human-readable labels for their input data keys.
 *
 * Used by ResolvedParamsView to display resolved parameters with meaningful labels
 * instead of raw JSON keys. For node types not listed here (or unknown keys),
 * humanizeKey() provides a fallback by converting camelCase to Title Case.
 *
 * Every node type MUST emit `resolved_params` in its backend output. If a node currently
 * doesn't emit resolved_params, the backend should be fixed to add it.
 *
 * For MCP tool nodes, labels come from toolParameters (dynamic), not this registry.
 */

import type { InspectorNodeType } from '../core/types';

/**
 * Static label mappings per node type.
 * Key = inputData field name, Value = display label.
 */
export const inputLabelRegistry: Partial<Record<InspectorNodeType, Record<string, string>>> = {
  // ============================================================================
  // CORES (control flow)
  // ============================================================================
  'wait': {
    duration: 'Duration (ms)',
  },
  'loop': {
    condition: 'Condition',
    maxIterations: 'Max Iterations',
    list: 'Items',
  },
  'while-group': {
    condition: 'Condition',
    maxIterations: 'Max Iterations',
  },
  'split': {
    list: 'Items',
    itemCount: 'Item Count',
    maxItems: 'Max Items',
    splitStrategy: 'Strategy',
  },
  'aggregate': {
    // Dynamic keys from field labels → humanizeKey fallback handles them
  },
  'decision': {
    // Dynamic keys: if, elsif_N, else → humanizeKey fallback handles them
    branches: 'Branches',
  },
  'switch': {
    expression: 'Expression',
    resolved_value: 'Resolved Value',
    cases: 'Cases',
  },
  'filter': {
    conditions: 'Conditions',
    mode: 'Mode',
    expression: 'Filter Expression',
    field: 'Field',
    operator: 'Operator',
    value: 'Value',
  },
  'sort': {
    field: 'Sort Field',
    order: 'Order',
    expression: 'Expression',
  },
  'limit': {
    count: 'Limit',
    from: 'From',
    offset: 'Offset',
  },
  'merge': {
    strategy: 'Strategy',
    sources: 'Sources',
  },
  'fork': {
    branches: 'Branches',
  },
  'response': {
    message: 'Message',
  },
  'http_request': {
    method: 'Method',
    url: 'URL',
    body: 'Body',
    bodyType: 'Body Type',
    authType: 'Auth Type',
    timeout: 'Timeout',
    headers: 'Headers',
    queryParams: 'Query Params',
  },
  'download_file': {
    url: 'URL',
    filename: 'Filename',
    mimeType: 'MIME Type',
  },
  'public_link': {
    file: 'File',
    ttl_minutes: 'TTL (minutes)',
    disposition: 'Disposition',
  },
  'media': {
    operation: 'Operation',
    input: 'Input File',
    video: 'Video',
    audio: 'Audio',
    tracks: 'Tracks',
    volume: 'Volume (%)',
    offset_seconds: 'Offset (s)',
    trim_start_seconds: 'Trim Start (s)',
    trim_end_seconds: 'Trim End (s)',
    loop: 'Loop',
    fade_in_seconds: 'Fade In (s)',
    fade_out_seconds: 'Fade Out (s)',
    keep_original_audio: 'Keep Original Audio',
    original_volume: 'Original Volume (%)',
    audio_fit: 'Audio Fit',
    normalize: 'Normalize',
    audio_bitrate: 'Audio Bitrate',
    output_format: 'Output Format',
    inputs: 'Clips',
    transition: 'Transition',
    transition_seconds: 'Transition (s)',
    target_width: 'Target Width',
    target_height: 'Target Height',
    target_fps: 'Target FPS',
    at_seconds: 'Timestamp (s)',
    image_format: 'Image Format',
    width: 'Width (px)',
    image: 'Image',
    position: 'Position',
    margin_px: 'Margin (px)',
    width_percent: 'Width (% of video)',
    opacity: 'Opacity',
    start_seconds: 'Start (s)',
    end_seconds: 'End (s)',
  },
  'respond_to_webhook': {
    statusCode: 'Status Code',
    contentType: 'Content Type',
    body: 'Body',
    headers: 'Headers',
  },
  'send_email': {
    smtpHost: 'SMTP Host',
    smtpPort: 'SMTP Port',
    toEmail: 'To',
    subject: 'Subject',
    isHtml: 'HTML',
    to: 'To',
    from: 'From',
    fromEmail: 'From Email',
    fromName: 'From Name',
    replyTo: 'Reply To',
    cc: 'CC',
    bcc: 'BCC',
    body: 'Body',
    inReplyTo: 'In-Reply-To',
    references: 'References',
  },
  'email_inbox': {
    folder: 'Folder',
    unreadOnly: 'Unread only',
    flaggedOnly: 'Flagged only',
    limit: 'Limit',
    markSeen: 'Mark seen',
    sinceDays: 'Since (days)',
    beforeDays: 'Before (days)',
    fromContains: 'Sender contains',
    subjectContains: 'Subject contains',
    bodyContains: 'Body contains',
    downloadAttachments: 'Download attachments',
    action: 'Action',
    messageUid: 'Message UID',
    targetFolder: 'Target Folder',
    createTargetIfMissing: 'Create folder if missing',
  },
  'sub_workflow': {
    workflowId: 'Workflow',
    inputMapping: 'Input Mapping',
    inputs: 'Inputs',
    timeoutSeconds: 'Timeout (s)',
    maxDepth: 'Max Depth',
  },
  'code': {
    language: 'Language',
    code: 'Code',
    codeLength: 'Code Length',
    timeoutSeconds: 'Timeout (s)',
  },
  'convert_to_file': {
    format: 'Format',
    filename: 'Filename',
    content: 'Content',
    delimiter: 'Delimiter',
    includeHeaders: 'Include Headers',
    value: 'Value',
  },
  'extract_from_file': {
    format: 'Format',
    file: 'File',
    delimiter: 'Delimiter',
    sheetName: 'Sheet Name',
    hasHeaders: 'Has Headers',
  },
  'xml': {
    operation: 'Operation',
    value: 'Value',
    content: 'Content',
    rootElement: 'Root Element',
    xpath: 'XPath',
    preserveAttributes: 'Preserve Attributes',
  },
  'compression': {
    operation: 'Operation',
    format: 'Format',
    content: 'Content',
    value: 'Value',
    filename: 'Filename',
  },
  'rss': {
    url: 'URL',
    maxItems: 'Max Items',
  },
  'compare_datasets': {
    inputA: 'Input A',
    inputB: 'Input B',
    left: 'Left Dataset',
    right: 'Right Dataset',
    matchFields: 'Match Fields',
    key: 'Comparison Key',
    returnMatched: 'Return Matched',
    returnOnlyA: 'Return Only A',
    returnOnlyB: 'Return Only B',
  },
  'date_time': {
    operation: 'Operation',
    value: 'Value',
    date: 'Date',
    format: 'Format',
    inputFormat: 'Input Format',
    outputFormat: 'Output Format',
    timezone: 'Timezone',
    targetTimezone: 'Target Timezone',
    durationUnit: 'Duration Unit',
    durationAmount: 'Duration Amount',
    secondValue: 'Second Value',
    extractPart: 'Extract Part',
  },
  'crypto_jwt': {
    operation: 'Operation',
    algorithm: 'Algorithm',
    secret: 'Secret',
    token: 'Token',
    payload: 'Payload',
  },
  'remove_duplicates': {
    field: 'Field',
    expression: 'Expression',
  },
  'summarize_data': {
    aggregations: 'Aggregations',
    field: 'Field',
    operation: 'Operation',
    groupBy: 'Group By',
  },
  'data_input': {
    // Dynamic keys from item labels → humanizeKey fallback handles them
  },
  'transform': {
    // Dynamic keys from mapping labels → humanizeKey fallback handles them
  },

  // ============================================================================
  // AGENTS
  // ============================================================================
  'agent': {
    prompt: 'Prompt',
    model: 'Model',
    provider: 'Provider',
    temperature: 'Temperature',
    maxTokens: 'Max Tokens',
    systemPrompt: 'System Prompt',
    content: 'Content',
    context: 'Context',
  },
  'summarize': {
    prompt: 'Prompt',
    model: 'Model',
    provider: 'Provider',
    temperature: 'Temperature',
    maxTokens: 'Max Tokens',
    content: 'Content',
    context: 'Context',
  },
  'guardrail': {
    prompt: 'Prompt',
    model: 'Model',
    provider: 'Provider',
    temperature: 'Temperature',
    maxTokens: 'Max Tokens',
    content: 'Content',
    action: 'Action',
    rules: 'Rules',
  },
  'classify': {
    prompt: 'Prompt',
    model: 'Model',
    provider: 'Provider',
    temperature: 'Temperature',
    maxTokens: 'Max Tokens',
    content: 'Content',
    categories: 'Categories',
  },

  // ============================================================================
  // CRUD (tables)
  // ============================================================================
  'create-row': {
    dataSourceId: 'Table',
    crud: 'CRUD Data',
    columns: 'Columns',
    values: 'Values',
    trigger: 'Trigger Data',
    steps: 'Step Data',
  },
  'read-row': {
    dataSourceId: 'Table',
    crud: 'CRUD Data',
    where: 'Where',
    limit: 'Limit',
    orderBy: 'Order By',
    trigger: 'Trigger Data',
    steps: 'Step Data',
  },
  'update-row': {
    dataSourceId: 'Table',
    crud: 'CRUD Data',
    where: 'Where',
    set: 'Set',
    trigger: 'Trigger Data',
    steps: 'Step Data',
  },
  'delete-row': {
    dataSourceId: 'Table',
    crud: 'CRUD Data',
    where: 'Where',
    trigger: 'Trigger Data',
    steps: 'Step Data',
  },
  'find-row': {
    dataSourceId: 'Table',
    crud: 'CRUD Data',
    where: 'Where',
    similarity: 'Vector Similarity',
    limit: 'Limit',
    orderBy: 'Order By',
    trigger: 'Trigger Data',
    steps: 'Step Data',
  },
  'list-rows': {
    dataSourceId: 'Table',
    crud: 'CRUD Data',
    limit: 'Limit',
    offset: 'Offset',
    orderBy: 'Order By',
    trigger: 'Trigger Data',
    steps: 'Step Data',
  },

  // ============================================================================
  // TOOLS (MCP) - labels from toolParameters at runtime, fallback keys here
  // ============================================================================
  'tool': {
    dataSourceId: 'Table',
    crud: 'CRUD Data',
    trigger: 'Trigger Data',
    steps: 'Step Data',
  },
};

/**
 * Get the label for an input data key, given the node type.
 * Falls back to humanizeKey() if no static mapping exists.
 */
export function getInputLabel(
  nodeType: InspectorNodeType,
  key: string,
): string {
  const labels = inputLabelRegistry[nodeType];
  if (labels && labels[key]) {
    return labels[key];
  }
  return humanizeKey(key);
}

/**
 * Convert a camelCase or snake_case key into Title Case.
 * Examples:
 *   "maxIterations" → "Max Iterations"
 *   "data_source_id" → "Data Source Id"
 *   "url" → "Url"
 *   "bodyType" → "Body Type"
 */
export function humanizeKey(key: string): string {
  return key
    // Insert space before uppercase letters (camelCase)
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    // Replace underscores and hyphens with spaces
    .replace(/[_-]/g, ' ')
    // Capitalize first letter of each word
    .replace(/\b\w/g, (c) => c.toUpperCase())
    .trim();
}
