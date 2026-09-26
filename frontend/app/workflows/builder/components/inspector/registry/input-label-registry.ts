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
  'user_approval': {
    // The third parking node. Its only step row is written when the signal resolves, and
    // these are the keys it now reports there: which roles could approve, how many were
    // needed, how long it had, who it was delegated to and what the run did next. An
    // approval that sat unanswered, or was answered by an unexpected person, cannot be
    // explained without them.
    approverRoles: 'Approver Roles',
    requiredApprovals: 'Required Approvals',
    timeoutMs: 'Timeout (ms)',
    contextTemplate: 'Context',
    contextResolved: 'Context (resolved)',
    delegation: 'Delegation',
    continuationMode: 'On Approval',
  },
  'loop': {
    loopCondition: 'Condition',
    maxIterations: 'Max Iterations',
    strategy: 'Strategy',
    list: 'Items',
    // Only on the row a terminated loop leaves behind: the condition as it read on
    // the iteration that ended the loop. `loopCondition` stays the CONFIGURED
    // expression there, because that row is written after the loop stopped and
    // re-resolving then would read a context the loop no longer runs in.
    lastConditionResolved: 'Condition (last evaluated)',
  },
  'while-group': {
    condition: 'Condition',
    maxIterations: 'Max Iterations',
  },
  'split': {
    list: 'Items',
    // What the `list` expression evaluated to, described rather than copied (the
    // data itself is the node's own output). On a split that spawned nothing this
    // is the row that says whether the array was empty or whether the reference
    // pointed at a wrapper object the split could not iterate.
    listResolved: 'Items (resolved)',
    itemCount: 'Item Count',
    maxItems: 'Max Items',
    splitStrategy: 'Strategy',
    // Reported on the failure path only, beside the configuration that failed.
    error: 'Error',
  },
  'aggregate': {
    fields: 'Collected Fields',
    // Plus ONE KEY PER AUTHOR LABEL, which no registry can label: those fall
    // through to humanizeKey, which is the right rendering for a name the user
    // chose. The COLLECTED values live in the node's output, not here.,
    // under the field labels, which is where a reader looks for them.
  },
  'decision': {
    // Dynamic keys: if, elsif_N, else → humanizeKey fallback handles them
    branches: 'Branches',
  },
  'switch': {
    switchExpression: 'Expression',
    resolved_value: 'Resolved Value',
    // Beyond these, one key per CASE under its own label -> humanizeKey.
  },
  'filter': {
    input: 'Input',
    input_count: 'Items received',
    conditions: 'Conditions',
    mode: 'Mode',
    expression: 'Filter Expression',
    field: 'Field',
    operator: 'Operator',
    value: 'Value',
  },
  'sort': {
    input: 'Input',
    input_count: 'Items received',
    fields: 'Sort Fields',
    field: 'Sort Field',
    order: 'Order',
    expression: 'Expression',
  },
  'limit': {
    input: 'Input',
    input_count: 'Items received',
    count: 'Limit',
    from: 'From',
    offset: 'Offset',
  },
  'merge': {
    strategy: 'Strategy',
    sources: 'Sources',
  },
  'exit': {
    reason: 'Reason',
  },
  'fork': {
    // One key per BRANCH under its own label -> humanizeKey.
  },
  'response': {
    message: 'Message',
  },
  'stop_on_error': {
    errorCode: 'Error Code',
    errorMessage: 'Error Message',
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
    fileResolved: 'File (resolved)',
    ttl_minutes: 'TTL (minutes)',
    disposition: 'Disposition',
  },
  'generate': {
    model: 'Model',
    prompt: 'Prompt',
    negative_prompt: 'Negative Prompt',
    duration_seconds: 'Duration (s)',
    n: 'Count',
    aspect_ratio: 'Aspect Ratio',
    resolution: 'Resolution',
    quality: 'Quality',
    style: 'Style',
    voice: 'Voice',
    language: 'Language',
    seed: 'Seed',
    // Named by what the file IS to the model, which is also what the inspector and the
    // studio call these. "Reference Image" over a slot the model treats as a first frame
    // is the same mislabelling the forms were fixed for, one surface later.
    input_image: 'Input Image',
    input_audio: 'Input Audio',
    input_video: 'Input Video',
    first_frame_image: 'First Frame',
    last_frame_image: 'Last Frame',
    reference_image: 'Reference Images',
    credential_source: 'Credential Source',
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
    cues: 'Captions',
    font_family: 'Font',
    font_size_percent: 'Font Size (% of height)',
    position_percent: 'Position (% from top)',
    text_color: 'Text Colour',
    outline_color: 'Outline Colour',
  },
  // No 'option' block: `option` is not an InspectorNodeType, so detectNodeType
  // resolves an option node to 'unknown' and no entry here could ever be read.
  // Its keys humanise instead - `choices` reads "Choices", the author-named
  // choice labels read as themselves, which is the right rendering anyway.
  'sftp': {
    localContentSize: 'Upload size (chars)',
    credentialId: 'Credential',
  },
  'ssh': {
    credentialId: 'Credential',
  },
  'database': {
    credentialId: 'Credential',
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
    smtpUsername: 'SMTP User',
    smtpUseTls: 'Use TLS',
    ccEmail: 'CC',
    bccEmail: 'BCC',
    credentialId: 'Credential',
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
    credentialId: 'Credential',
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
    inputMappingResolved: 'Input Mapping (resolved)',
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
    value: 'Source',
    mode: 'Mode',
    delimiter: 'Delimiter',
    sheetName: 'Sheet Name',
    hasHeaders: 'Has Headers',
    chunking: 'Chunking',
    chunkingStrategy: 'Chunking Strategy',
    chunkSize: 'Chunk Size',
    chunkUnit: 'Chunk Unit',
    overlap: 'Overlap',
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
  'html_extract': {
    sourceHtml: 'Source HTML',
    sourceHtmlLength: 'Source length (chars)',
    extractionMode: 'Extraction Mode',
    rootSelector: 'Root Selector',
    cleanWhitespace: 'Clean Whitespace',
    field_count: 'Fields configured',
    fields: 'Fields',
  },
  'compare_datasets': {
    inputA: 'Input A',
    inputB: 'Input B',
    inputACount: 'Input A rows',
    inputBCount: 'Input B rows',
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
    value: 'Value',
    secret: 'Secret',
    token: 'Token',
    payload: 'Payload',
  },
  'remove_duplicates': {
    input: 'Input',
    input_count: 'Items received',
    fields: 'Compared Fields',
    keep: 'Keep',
    field: 'Field',
    expression: 'Expression',
  },
  'summarize_data': {
    input: 'Input',
    input_count: 'Items received',
    aggregations: 'Aggregations',
    aggregation_count: 'Aggregations configured',
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
  'set': {
    input: 'Input',
    keepOnlySet: 'Keep only set fields',
    assignmentCount: 'Assignments',
    // Beyond these, the keys are the assignment names → humanizeKey fallback.
  },
  'task': {
    operation: 'Operation',
    taskId: 'Task',
    title: 'Title',
    instructions: 'Instructions',
    priority: 'Priority',
    status: 'Status',
    taskContext: 'Context',
    agentId: 'Agent',
    reviewerAgentId: 'Reviewer Agent',
    search: 'Search',
    limit: 'Limit',
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
    // The list-expression strategy, used when the node reads its rows from an
    // expression rather than from the table. `list` is the expression as written;
    // `listResolved` is what it evaluated to, or the reason it was not evaluated.
    list: 'Items',
    listResolved: 'Items (resolved)',
    maxItems: 'Max Items',
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
  // INTERFACE
  // ============================================================================
  'interface': {
    interfaceId: 'Interface',
    actions: 'Actions',
    // One entry per template variable: the expression it is wired to, what it held
    // when the node ran, and whether that was measured at all. An interface that
    // renders an empty screen is read here.
    variableMapping: 'Variables',
    variableMappingError: 'Variables not read',
    isEntryInterface: 'Entry Interface',
    generateScreenshot: 'Screenshot',
    exposeRenderedSource: 'Expose Rendered Source',
    generatePdf: 'PDF',
    pdfFormat: 'PDF Format',
    pdfLandscape: 'PDF Landscape',
    generateVideo: 'Video',
    videoPreset: 'Video Preset',
    videoMaxDurationSeconds: 'Video Max Duration (s)',
    videoMode: 'Video Mode',
    videoFps: 'Video FPS',
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
 * Keys the reporting gate itself can add to ANY node's params, so they belong to no
 * node type and are read before the per-type registry.
 *
 * `paramsTruncated` is the only one: `ReportedParams.forReport` bounds the WHOLE map and
 * says under this key how many entries it dropped. Left to humanizeKey it reads "Params
 * Truncated" among the real parameters, as if the node had a parameter by that name.
 */
const CROSS_NODE_INPUT_LABELS: Record<string, string> = {
  paramsTruncated: 'Truncated',
  // A trigger whose params could not be resolved still fires (its payload and epoch stand) and
  // says why here, instead of reporting the raw plan map as if it were what it ran with.
  paramsResolutionError: 'Resolution error',
  // The signal bookkeeping a PARKED node's row carries beside its own parameters
  // (SignalResumeService.buildSignalInputData). It belongs to no node type either: an
  // interface, an approval and a wait all get these five when their signal resolves, and
  // without a label they render as the humanised raw key this registry exists to replace.
  signal_type: 'Signal',
  signal_config: 'Signal Configuration',
  item_id: 'Item',
  trigger_id: 'Trigger',
  epoch: 'Epoch',
};

/**
 * Get the label for an input data key, given the node type.
 * Falls back to humanizeKey() if no static mapping exists.
 */
export function getInputLabel(
  nodeType: InspectorNodeType,
  key: string,
): string {
  const crossNode = CROSS_NODE_INPUT_LABELS[key];
  if (crossNode) {
    return crossNode;
  }
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
