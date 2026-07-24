/**
 * NodeConfigurationRule - Validates node-specific structural configuration
 *
 * Validations:
 * #10  Step/MCP without tool ID (error)
 * #11  Decision without conditions (error)
 * #12  Loop without condition (error)
 * #13  Switch without expression or cases (error)
 *
 * #14  Required fields for core node types (error)
 * #15  MCP tool required parameters (error)
 */

import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import type { ValidationContext, ValidationIssue } from '../core/types';
import { BaseValidationRule } from './BaseValidationRule';
import { normalizeLabel } from '../../../utils/labelNormalizer';
import { getNodeType, isInterfaceNode, isCrudNode, isNoteNode } from '../core/nodeUtils';
import { nodeRegistry } from '../../../registry/nodeRegistry';
import {
  isMediaOperation,
  MEDIA_CONCAT_INPUTS_MAX,
  MEDIA_DIMENSION_MAX,
  MEDIA_DIMENSION_MIN,
  MEDIA_NORMALIZE_LUFS_MAX,
  MEDIA_NORMALIZE_LUFS_MIN,
  MEDIA_OPACITY_MAX,
  MEDIA_OPACITY_MIN,
  MEDIA_SPEED_MAX,
  MEDIA_SPEED_MIN,
  MEDIA_TARGET_FPS_MAX,
  MEDIA_TARGET_FPS_MIN,
  MEDIA_TRACKS_MAX,
  MEDIA_TRANSITION_SECONDS_MAX,
  MEDIA_TRANSITION_SECONDS_MIN,
  MEDIA_VOLUME_MAX,
  MEDIA_VOLUME_MIN,
  MEDIA_WIDTH_PERCENT_MAX,
  MEDIA_WIDTH_PERCENT_MIN,
} from '../../../utils/mediaParams';

export class NodeConfigurationRule extends BaseValidationRule {
  readonly ruleName = 'NodeConfiguration' as const;
  readonly isCritical = true;
  readonly priority = 8;

  validate(context: ValidationContext): ReturnType<typeof this.buildResult> {
    const issues: ValidationIssue[] = [];
    const { nodes } = context;

    for (const node of nodes) {
      if (isNoteNode(node)) continue;

      const nodeType = getNodeType(node);
      const label = node.data?.label;
      const norm = label ? normalizeLabel(label) : null;
      const elementKey = norm ? `${nodeType}:${norm}` : `${nodeType}:${node.id}`;

      // #10: Step/MCP without tool ID
      if (nodeType === 'mcp' && !isInterfaceNode(node) && !isCrudNode(node)) {
        const toolData = node.data?.toolData;
        if (!toolData || (!toolData.toolId && !toolData.toolSlug)) {
          issues.push(
            this.createError(elementKey, 'mcp', 'Step id is required', {
              rule: 'step_missing_tool',
              nodeId: node.id,
            })
          );
        }
      }

      // #11: Decision without conditions
      if (nodeRegistry.isDecisionNode(node)) {
        this.validateDecision(node, elementKey, issues);
      }

      // #12: Loop without condition
      if (nodeRegistry.isLoopNode(node)) {
        this.validateLoop(node, elementKey, issues);
      }

      // #13: Switch without expression or cases
      if (nodeRegistry.isSwitchNode(node) || nodeRegistry.isOptionNode(node)) {
        this.validateSwitch(node, elementKey, issues);
      }

      // Approval: context template is recommended (WARNING, non-blocking). Without it
      // the approver sees no description of what they are approving; the run still works.
      if (nodeRegistry.isUserApprovalNode(node)) {
        this.validateApprovalContext(node, elementKey, issues);
        this.validateApprovalDelegation(node, elementKey, issues);
      }

      // #14: Required fields for all core node types
      this.validateRequiredFields(node, elementKey, issues);

      // Optional-component availability (deployment-level, WARNING): browser_agent
      // nodes fail at run time and interface render toggles silently no-op when the
      // backing opt-in component is absent - surface it at build time.
      this.validateOptionalComponentAvailability(node, elementKey, context, issues);

      // #15: MCP tool required parameters
      if (nodeType === 'mcp' && !isInterfaceNode(node) && !isCrudNode(node)) {
        this.validateToolParameters(node, elementKey, issues);
      }
    }

    return this.buildResult(issues);
  }

  /**
   * Warns when a node depends on an OPTIONAL deployment component reported absent
   * by the capabilities endpoint. Warning (not error): the workflow stays saveable
   * and runnable elsewhere (publish/clone onto an install that has the component).
   * Unknown capabilities (context field absent: loading, fetch error, older
   * backend) emit nothing - never a possibly-false warning.
   */
  private validateOptionalComponentAvailability(
    node: Node<BuilderNodeData>,
    elementKey: string,
    context: ValidationContext,
    issues: ValidationIssue[]
  ): void {
    const caps = context.featureCapabilities;
    if (!caps) return;

    const d = node.data as any;

    if (nodeRegistry.isBrowserAgentNode(node) && !caps.browserAgent) {
      issues.push(this.createWarning(elementKey, 'agent',
        'Browser agent is not enabled on this installation - this node will fail at run time. An administrator can enable the optional browser-agent component or link the installation to the cloud.',
        { rule: 'browser_agent_component_unavailable', nodeId: node.id }));
    }

    if (isInterfaceNode(node) && !caps.screenshotRenderer) {
      const iface = d?.interfaceData ?? {};
      if (iface.generateScreenshot === true || iface.generatePdf === true || iface.generateVideo === true) {
        issues.push(this.createWarning(elementKey, 'interface',
          'Screenshot/PDF/video generation is enabled but the optional renderer component is not running on this installation - the screenshot/pdf/video outputs will be absent. An administrator can enable the renderer component.',
          { rule: 'interface_renderer_unavailable', nodeId: node.id }));
      }
    }
  }

  /**
   * Validates required fields for all core node types.
   * Each node type has specific fields that must be non-empty.
   */
  private validateRequiredFields(
    node: Node<BuilderNodeData>,
    elementKey: string,
    issues: ValidationIssue[]
  ): void {
    const d = node.data as any;
    const kind = d?.kind;
    if (!kind) return;

    // Data processing nodes: input expression required
    const dataInputMap: Record<string, string> = {
      filter: 'filterInput', sort: 'sortInput', limit: 'limitInput',
      remove_duplicates: 'dedupInput', summarize: 'summarizeInput',
    };
    if (dataInputMap[kind]) {
      this.requireField(d, dataInputMap[kind], 'Input data is required - specify the items to process', 'data_node_missing_input', node.id, elementKey, issues);
    }

    // XML: input required
    if (kind === 'xml') {
      this.requireField(d, 'xmlInput', 'Input data is required', 'xml_missing_input', node.id, elementKey, issues);
    }
    // Compression: input required
    if (kind === 'compression') {
      this.requireField(d, 'compressionInput', 'Input data is required', 'compression_missing_input', node.id, elementKey, issues);
    }
    // ConvertToFile: value required
    if (kind === 'convert_to_file') {
      this.requireField(d, 'convertValue', 'Data source is required', 'convert_missing_value', node.id, elementKey, issues);
    }
    // ExtractFromFile: value required
    if (kind === 'extract_from_file') {
      this.requireField(d, 'extractValue', 'File content or URL is required', 'extract_missing_value', node.id, elementKey, issues);
    }
    // CompareDatasets: inputA and inputB required
    if (kind === 'compare_datasets') {
      this.requireField(d, 'compareInputA', 'Dataset A is required', 'compare_missing_input_a', node.id, elementKey, issues);
      this.requireField(d, 'compareInputB', 'Dataset B is required', 'compare_missing_input_b', node.id, elementKey, issues);
    }
    // RSS: url required
    if (kind === 'rss') {
      this.requireField(d, 'rssUrl', 'Feed URL is required', 'rss_missing_url', node.id, elementKey, issues);
    }
    // Code: code content required
    if (kind === 'code') {
      this.requireField(d, 'codeContent', 'Code is required', 'code_missing_content', node.id, elementKey, issues);
    }
    // HttpRequest: url required
    if (kind === 'http_request') {
      const httpData = d?.httpRequestData;
      if (!httpData?.url || httpData.url.trim() === '') {
        issues.push(this.createError(elementKey, 'core', 'URL is required', { rule: 'http_missing_url', nodeId: node.id }));
      }
    }
    // DownloadFile: url required
    if (kind === 'download_file') {
      this.requireField(d, 'downloadUrl', 'URL is required', 'download_missing_url', node.id, elementKey, issues);
    }
    // PublicLink: file expression required
    if (kind === 'public_link') {
      this.requireField(d, 'publicLinkFile', 'File reference is required', 'public_link_missing_file', node.id, elementKey, issues);
    }
    // Media: operation + per-operation required file expressions and numeric bounds
    if (kind === 'media') {
      this.validateMedia(d, node.id, elementKey, issues);
    }
    // SendEmail: to and subject required
    if (kind === 'send_email') {
      this.requireField(d, 'emailTo', 'Recipient email is required', 'email_missing_to', node.id, elementKey, issues);
      this.requireField(d, 'emailSubject', 'Subject is required', 'email_missing_subject', node.id, elementKey, issues);
    }
    // EmailInbox: single-message actions need messageUid; the mailbox-level actions
    // ('none', 'list_folders', 'create_folder') never do. move and create_folder need targetFolder.
    if (kind === 'email_inbox') {
      const action = d?.emailAction;
      const isMailboxAction = !action || action === 'none' || action === 'list_folders' || action === 'create_folder';
      if (!isMailboxAction) {
        this.requireField(d, 'emailMessageUid', 'Message UID is required for this action', 'inbox_missing_uid', node.id, elementKey, issues);
      }
      if (action === 'move') {
        this.requireField(d, 'emailTargetFolder', 'Target folder is required for the move action', 'inbox_missing_target', node.id, elementKey, issues);
      }
      if (action === 'create_folder') {
        this.requireField(d, 'emailTargetFolder', 'Folder name is required for the create folder action', 'inbox_missing_target', node.id, elementKey, issues);
      }
    }
    // Split: list required
    if (nodeRegistry.isSplitNode(node)) {
      this.requireField(d, 'list', 'List expression is required', 'split_missing_list', node.id, elementKey, issues);
    }
    // Set: at least one assignment required
    if (kind === 'set') {
      const assignments = d?.setAssignments;
      if (!Array.isArray(assignments) || assignments.length === 0) {
        issues.push(this.createError(elementKey, 'core',
          'Set node must have at least one assignment',
          { rule: 'set_missing_assignments', nodeId: node.id }));
      }
    }
    // HtmlExtract: sourceHtml + at least one field required
    if (kind === 'html_extract') {
      this.requireField(d, 'htmlExtractSource', 'Source HTML is required', 'html_extract_missing_source', node.id, elementKey, issues);
      const fields = d?.htmlExtractFields;
      if (!Array.isArray(fields) || fields.length === 0) {
        issues.push(this.createError(elementKey, 'core',
          'HTML Extract node must have at least one field',
          { rule: 'html_extract_missing_fields', nodeId: node.id }));
      }
    }
    // Agent (reasoning): prompt always required, model only for inline agents
    if (kind === 'reasoning' || d?.agentType === 'agent') {
      this.requireParamExpression(d, 'prompt', 'Prompt is required', 'agent_missing_prompt', node.id, elementKey, issues);
      if (!d?.agentConfigId) {
        this.requireParamExpression(d, 'model', 'Model is required', 'agent_missing_model', node.id, elementKey, issues);
      }
    }
    // Guardrail: input text and at least one rule required
    if (kind === 'guardrail' || d?.agentType === 'guardrail') {
      this.requireParamExpression(d, 'guardrailParams', 'Input text is required', 'guardrail_missing_input', node.id, elementKey, issues);
      const rules = d?.guardrailRules;
      if (!Array.isArray(rules) || rules.length === 0) {
        issues.push(this.createError(elementKey, 'agent',
          'Guardrail must have at least one rule',
          { rule: 'guardrail_missing_rules', nodeId: node.id }));
      }
    }
    // Classify: prompt and at least two categories required
    if (kind === 'classify' || d?.agentType === 'classify') {
      this.requireParamExpression(d, 'prompt', 'Prompt is required', 'classify_missing_prompt', node.id, elementKey, issues);
      const categories = d?.classifyCategories;
      if (!Array.isArray(categories) || categories.length < 2) {
        issues.push(this.createError(elementKey, 'agent',
          'Classify must have at least two categories',
          { rule: 'classify_missing_categories', nodeId: node.id }));
      }
    }
    // Browser Agent: natural-language goal ('task') is required so the
    // backend agent has something to execute. Model defaults are resolved
    // server-side, so we don't require it here unless the agent ever
    // becomes inline-configured (matches Agent's `agentConfigId` carve-out).
    if (kind === 'browser_agent' || d?.agentType === 'browser_agent') {
      this.requireParamExpression(d, 'task', 'Task is required', 'browser_agent_missing_task', node.id, elementKey, issues);
    }
    // Response: message required
    if (kind === 'output' || kind === 'response') {
      this.requireField(d, 'responseMessage', 'Response message is required', 'response_missing_message', node.id, elementKey, issues);
    }
    // Task: operation-specific required fields
    if (kind === 'task') {
      const op = d?.taskOperation;
      if (op === 'create_task') {
        this.requireField(d, 'taskTitle', 'Task title is required for create', 'task_missing_title', node.id, elementKey, issues);
        // taskContext is optional but must be a JSON object - stepProcessor
        // silently drops anything else, so flag it here instead.
        const ctxJson = d?.taskContextJson;
        if (typeof ctxJson === 'string' && ctxJson.trim() !== '') {
          let invalid = false;
          try {
            const parsed = JSON.parse(ctxJson);
            invalid = parsed === null || typeof parsed !== 'object' || Array.isArray(parsed);
          } catch {
            invalid = true;
          }
          if (invalid) {
            issues.push(this.createError(elementKey, 'core',
              'Task context must be a valid JSON object',
              { rule: 'task_invalid_task_context', nodeId: node.id }));
          }
        }
      }
      if (op === 'get_task' || op === 'update_task' || op === 'delete_task') {
        this.requireField(d, 'taskTaskId', 'Task ID is required', 'task_missing_task_id', node.id, elementKey, issues);
      }
    }
    // SSH: command required (host comes from credential)
    if (kind === 'ssh') {
      this.requireField(d, 'sshCommand', 'Command is required', 'ssh_missing_command', node.id, elementKey, issues);
    }
    // SFTP: remotePath required (host comes from credential)
    if (kind === 'sftp') {
      this.requireField(d, 'sftpRemotePath', 'Remote path is required', 'sftp_missing_remote_path', node.id, elementKey, issues);
    }
    // Database: query required (host/databaseName come from credential)
    if (kind === 'database') {
      this.requireField(d, 'dbQuery', 'SQL query is required', 'db_missing_query', node.id, elementKey, issues);
    }
  }

  /**
   * Media node: mirrors the backend contract's validation rules.
   * - operation required, one of probe | mux_audio | mix | extract_audio |
   *   concat | frame | overlay
   * - probe/extract_audio/frame need `input`; mux_audio needs `video` + `audio`;
   *   mix needs a non-empty `tracks` array (max 8) with a `source` per track;
   *   concat needs a non-empty `inputs` array (max 8) with a `source` per clip;
   *   overlay needs `video` + `image`
   * - duck_under must reference ANOTHER existing track id
   * - concat: crossfade needs >= 2 clips; trim_end > trim_start per clip;
   *   target_width/target_height set together (both or neither)
   * - numeric bounds (checked only on plain numbers; template strings resolve
   *   at run time): volume 0-400, speed 0.5-2.0, offsets/trims/fades >= 0,
   *   custom normalize LUFS in [-70, -5], transition 0.1-5s, dimensions
   *   16-4096, fps 1-60, width_percent 1-100, opacity 0-1
   */
  private validateMedia(
    d: any, nodeId: string, elementKey: string, issues: ValidationIssue[]
  ): void {
    const operation = d?.mediaOperation;
    const p = (d?.mediaParams || {}) as Record<string, any>;

    const error = (message: string, rule: string) => {
      issues.push(this.createError(elementKey, 'core', message, { rule, nodeId }));
    };
    const requireParam = (key: string, message: string, rule: string) => {
      const value = p[key];
      if (!value || (typeof value === 'string' && value.trim() === '')) {
        error(message, rule);
      }
    };
    const checkRange = (value: unknown, min: number, max: number, message: string, rule: string) => {
      if (typeof value === 'number' && (value < min || value > max)) {
        error(message, rule);
      }
    };
    const checkNonNegative = (value: unknown, label: string) => {
      if (typeof value === 'number' && value < 0) {
        error(`${label} must be 0 or greater`, 'media_negative_number');
      }
    };
    const checkNormalize = (value: unknown) => {
      if (typeof value === 'number' && (value < MEDIA_NORMALIZE_LUFS_MIN || value > MEDIA_NORMALIZE_LUFS_MAX)) {
        error(`Normalize loudness target must be between ${MEDIA_NORMALIZE_LUFS_MIN} and ${MEDIA_NORMALIZE_LUFS_MAX} LUFS`, 'media_normalize_out_of_range');
      }
    };
    const checkCommonNumericBounds = () => {
      checkRange(p.volume, MEDIA_VOLUME_MIN, MEDIA_VOLUME_MAX, `Volume must be between ${MEDIA_VOLUME_MIN} and ${MEDIA_VOLUME_MAX} percent`, 'media_volume_out_of_range');
      checkRange(p.original_volume, MEDIA_VOLUME_MIN, MEDIA_VOLUME_MAX, `Original volume must be between ${MEDIA_VOLUME_MIN} and ${MEDIA_VOLUME_MAX} percent`, 'media_volume_out_of_range');
      checkNonNegative(p.offset_seconds, 'Offset');
      checkNonNegative(p.trim_start_seconds, 'Trim start');
      checkNonNegative(p.trim_end_seconds, 'Trim end');
      checkNonNegative(p.fade_in_seconds, 'Fade in');
      checkNonNegative(p.fade_out_seconds, 'Fade out');
      checkNormalize(p.normalize);
    };

    if (!operation || (typeof operation === 'string' && operation.trim() === '')) {
      error('Operation is required - choose probe, mux_audio, mix, extract_audio, concat, frame, or overlay', 'media_missing_operation');
      return;
    }
    if (!isMediaOperation(operation)) {
      error(`Unknown operation "${operation}" - must be probe, mux_audio, mix, extract_audio, concat, frame, or overlay`, 'media_invalid_operation');
      return;
    }

    if (operation === 'probe' || operation === 'extract_audio') {
      requireParam('input', 'Input file is required', 'media_missing_input');
      if (operation === 'extract_audio') {
        checkNonNegative(p.trim_start_seconds, 'Trim start');
        checkNonNegative(p.trim_end_seconds, 'Trim end');
      }
    }

    if (operation === 'mux_audio') {
      requireParam('video', 'Video file is required', 'media_missing_video');
      requireParam('audio', 'Audio file is required', 'media_missing_audio');
      checkCommonNumericBounds();
      // loop + trim on the same audio is contradictory (loop needs the full segment anchor)
      if (p.loop === true && (p.trim_start_seconds !== undefined || p.trim_end_seconds !== undefined)) {
        error('Loop cannot be combined with trim start/end on the same audio - remove the trims or turn loop off', 'media_loop_with_trim');
      }
    }

    if (operation === 'mix') {
      const tracks = p.tracks;
      if (!Array.isArray(tracks) || tracks.length === 0) {
        error('At least one track is required', 'media_missing_tracks');
      } else {
        if (tracks.length > MEDIA_TRACKS_MAX) {
          error(`A mix supports at most ${MEDIA_TRACKS_MAX} tracks`, 'media_too_many_tracks');
        }
        const trackIds = tracks.map((t: any, i: number) =>
          (t && typeof t.id === 'string' && t.id.trim() !== '') ? t.id : `track_${i + 1}`);
        // Duplicate ids would make duck_under references ambiguous (backend rejects them too)
        const seenIds = new Set<string>();
        trackIds.forEach((id: string, i: number) => {
          if (seenIds.has(id)) {
            error(`Track ${i + 1} reuses the id "${id}" - track ids must be unique`, 'media_duplicate_track_id');
          }
          seenIds.add(id);
        });
        tracks.forEach((t: any, i: number) => {
          const source = t?.source;
          if (!source || (typeof source === 'string' && source.trim() === '')) {
            error(`Track ${i + 1} is missing its source file`, 'media_track_missing_source');
          }
          checkRange(t?.volume, MEDIA_VOLUME_MIN, MEDIA_VOLUME_MAX, `Track ${i + 1} volume must be between ${MEDIA_VOLUME_MIN} and ${MEDIA_VOLUME_MAX} percent`, 'media_volume_out_of_range');
          checkRange(t?.speed, MEDIA_SPEED_MIN, MEDIA_SPEED_MAX, `Track ${i + 1} speed must be between ${MEDIA_SPEED_MIN} and ${MEDIA_SPEED_MAX}`, 'media_speed_out_of_range');
          checkNonNegative(t?.offset_seconds, `Track ${i + 1} offset`);
          checkNonNegative(t?.trim_start_seconds, `Track ${i + 1} trim start`);
          checkNonNegative(t?.trim_end_seconds, `Track ${i + 1} trim end`);
          checkNonNegative(t?.fade_in_seconds, `Track ${i + 1} fade in`);
          checkNonNegative(t?.fade_out_seconds, `Track ${i + 1} fade out`);
          // loop + trim on the same track is contradictory
          if (t?.loop === true && (t?.trim_start_seconds !== undefined || t?.trim_end_seconds !== undefined)) {
            error(`Track ${i + 1} cannot combine loop with trim start/end - remove the trims or turn loop off`, 'media_loop_with_trim');
          }
          const duckUnder = t?.duck_under;
          if (typeof duckUnder === 'string' && duckUnder.trim() !== '') {
            if (duckUnder === trackIds[i]) {
              error(`Track ${i + 1} cannot duck under itself`, 'media_invalid_duck_under');
            } else if (!trackIds.includes(duckUnder)) {
              error(`Track ${i + 1} ducks under "${duckUnder}" which is not the id of another track`, 'media_invalid_duck_under');
            }
          }
        });
        // Audio-only mix (no video) needs a length anchor: at least one non-looping track
        const hasVideo = typeof p.video === 'string' ? p.video.trim() !== '' : p.video !== undefined && p.video !== null;
        if (!hasVideo && tracks.every((t: any) => t?.loop === true)) {
          error('An audio-only mix cannot loop every track - at least one track must not loop so the mix has a length, or add a video', 'media_all_tracks_loop');
        }
      }
      checkRange(p.original_volume, MEDIA_VOLUME_MIN, MEDIA_VOLUME_MAX, `Original volume must be between ${MEDIA_VOLUME_MIN} and ${MEDIA_VOLUME_MAX} percent`, 'media_volume_out_of_range');
      checkNormalize(p.normalize);
    }

    if (operation === 'concat') {
      const inputs = p.inputs;
      if (!Array.isArray(inputs) || inputs.length === 0) {
        error('At least one clip is required', 'media_missing_inputs');
      } else {
        if (inputs.length > MEDIA_CONCAT_INPUTS_MAX) {
          error(`A concat supports at most ${MEDIA_CONCAT_INPUTS_MAX} clips`, 'media_too_many_inputs');
        }
        inputs.forEach((item: any, i: number) => {
          const source = item?.source;
          if (!source || (typeof source === 'string' && source.trim() === '')) {
            error(`Clip ${i + 1} is missing its source file`, 'media_input_missing_source');
          }
          checkRange(item?.speed, MEDIA_SPEED_MIN, MEDIA_SPEED_MAX, `Clip ${i + 1} speed must be between ${MEDIA_SPEED_MIN} and ${MEDIA_SPEED_MAX}`, 'media_speed_out_of_range');
          checkNonNegative(item?.trim_start_seconds, `Clip ${i + 1} trim start`);
          checkNonNegative(item?.trim_end_seconds, `Clip ${i + 1} trim end`);
          // trim_end must leave a non-empty segment (backend rejects trim_end <= trim_start)
          if (typeof item?.trim_start_seconds === 'number' && typeof item?.trim_end_seconds === 'number'
            && item.trim_end_seconds <= item.trim_start_seconds) {
            error(`Clip ${i + 1} trim end must be greater than its trim start`, 'media_trim_end_before_start');
          }
        });
        // Crossfading needs two clips to fade between
        if (p.transition === 'crossfade' && inputs.length < 2) {
          error('A crossfade needs at least 2 clips - add a clip or switch the transition to cut', 'media_crossfade_needs_two_inputs');
        }
      }
      checkRange(p.transition_seconds, MEDIA_TRANSITION_SECONDS_MIN, MEDIA_TRANSITION_SECONDS_MAX, `Transition duration must be between ${MEDIA_TRANSITION_SECONDS_MIN} and ${MEDIA_TRANSITION_SECONDS_MAX} seconds`, 'media_transition_seconds_out_of_range');
      // Output canvas: both dimensions or neither (backend rejects the XOR case)
      const hasWidth = p.target_width !== undefined && p.target_width !== null && p.target_width !== '';
      const hasHeight = p.target_height !== undefined && p.target_height !== null && p.target_height !== '';
      if (hasWidth !== hasHeight) {
        error('Target width and target height must be set together (both or neither)', 'media_target_size_incomplete');
      }
      checkRange(p.target_width, MEDIA_DIMENSION_MIN, MEDIA_DIMENSION_MAX, `Target width must be between ${MEDIA_DIMENSION_MIN} and ${MEDIA_DIMENSION_MAX}`, 'media_dimension_out_of_range');
      checkRange(p.target_height, MEDIA_DIMENSION_MIN, MEDIA_DIMENSION_MAX, `Target height must be between ${MEDIA_DIMENSION_MIN} and ${MEDIA_DIMENSION_MAX}`, 'media_dimension_out_of_range');
      checkRange(p.target_fps, MEDIA_TARGET_FPS_MIN, MEDIA_TARGET_FPS_MAX, `Target fps must be between ${MEDIA_TARGET_FPS_MIN} and ${MEDIA_TARGET_FPS_MAX}`, 'media_fps_out_of_range');
      checkNonNegative(p.fade_in_seconds, 'Fade in');
      checkNonNegative(p.fade_out_seconds, 'Fade out');
      checkNormalize(p.normalize);
    }

    if (operation === 'frame') {
      requireParam('input', 'Input file is required', 'media_missing_input');
      checkNonNegative(p.at_seconds, 'Timestamp');
      checkRange(p.width, MEDIA_DIMENSION_MIN, MEDIA_DIMENSION_MAX, `Width must be between ${MEDIA_DIMENSION_MIN} and ${MEDIA_DIMENSION_MAX}`, 'media_dimension_out_of_range');
    }

    if (operation === 'overlay') {
      requireParam('video', 'Video file is required', 'media_missing_video');
      requireParam('image', 'Image file is required', 'media_missing_image');
      checkNonNegative(p.margin_px, 'Margin');
      checkRange(p.width_percent, MEDIA_WIDTH_PERCENT_MIN, MEDIA_WIDTH_PERCENT_MAX, `Width percent must be between ${MEDIA_WIDTH_PERCENT_MIN} and ${MEDIA_WIDTH_PERCENT_MAX}`, 'media_width_percent_out_of_range');
      checkRange(p.opacity, MEDIA_OPACITY_MIN, MEDIA_OPACITY_MAX, `Opacity must be between ${MEDIA_OPACITY_MIN} and ${MEDIA_OPACITY_MAX}`, 'media_opacity_out_of_range');
      checkNonNegative(p.start_seconds, 'Start');
      checkNonNegative(p.end_seconds, 'End');
      // The visibility window must be non-empty (backend rejects end <= start)
      if (typeof p.start_seconds === 'number' && typeof p.end_seconds === 'number'
        && p.end_seconds <= p.start_seconds) {
        error('End must be greater than start for the overlay window', 'media_end_before_start');
      }
    }
  }

  private requireField(
    data: any, fieldKey: string, message: string, rule: string,
    nodeId: string, elementKey: string, issues: ValidationIssue[]
  ): void {
    const value = data?.[fieldKey];
    if (!value || (typeof value === 'string' && value.trim() === '')) {
      issues.push(this.createError(elementKey, 'core', message, { rule, nodeId }));
    }
  }

  /**
   * Checks that a paramExpressions field is non-empty.
   * Falls back to the direct data field if paramExpressions doesn't have it.
   */
  private requireParamExpression(
    data: any, fieldKey: string, message: string, rule: string,
    nodeId: string, elementKey: string, issues: ValidationIssue[]
  ): void {
    const expr = data?.paramExpressions?.[fieldKey];
    const direct = data?.[fieldKey];
    const value = expr || direct;
    if (!value || (typeof value === 'string' && value.trim() === '')) {
      issues.push(this.createError(elementKey, 'agent', message, { rule, nodeId }));
    }
  }

  /**
   * Approval context template is recommended (WARNING, never blocking). The approver
   * relies on it to know what they are approving; the run still proceeds without it.
   */
  private validateApprovalContext(
    node: Node<BuilderNodeData>,
    elementKey: string,
    issues: ValidationIssue[]
  ): void {
    const template = (node.data as any)?.approvalContextTemplate;
    if (!template || (typeof template === 'string' && template.trim() === '')) {
      issues.push(
        this.createWarning(
          elementKey,
          'core',
          'Approval context is recommended so reviewers see what they are approving',
          { rule: 'approval_missing_context_template', nodeId: node.id }
        )
      );
    }
  }

  /**
   * External-channel delegation checks (all WARNING, never blocking). Only fire
   * when the section is actually enabled (a channel is selected): a delegation
   * without a chat id cannot deliver the approval message, and the channel counts
   * as a single approver decision so requiredApprovals > 1 can never be satisfied
   * by the external channel alone.
   *
   * NOTE: a missing credentialId is a VALID configuration (no warning): the backend
   * falls back to the user's own Telegram credential (catalog implicit resolution,
   * same as an mcp:telegram step without an explicit credential). A present but
   * non-numeric value is surfaced backend-side as APPROVAL_DELEGATION_INVALID_CREDENTIAL.
   */
  private validateApprovalDelegation(
    node: Node<BuilderNodeData>,
    elementKey: string,
    issues: ValidationIssue[]
  ): void {
    const delegation = (node.data as any)?.approvalDelegation;
    const channel = delegation?.channel;
    if (!channel || (typeof channel === 'string' && channel.trim() === '')) {
      return; // delegation disabled - nothing to check
    }

    const chatId = delegation.chatId;
    if (!chatId || (typeof chatId === 'string' && chatId.trim() === '')) {
      issues.push(
        this.createWarning(
          elementKey,
          'core',
          'Delegation is enabled but the chat ID is empty',
          { rule: 'approval_delegation_missing_chat_id', nodeId: node.id }
        )
      );
    }

    const requiredApprovals = (node.data as any)?.requiredApprovals;
    if (typeof requiredApprovals === 'number' && requiredApprovals > 1) {
      issues.push(
        this.createWarning(
          elementKey,
          'core',
          'The external channel counts as a single approver decision; it cannot satisfy more than one required approval on its own',
          { rule: 'approval_delegation_multi_approvals', nodeId: node.id }
        )
      );
    }
  }

  private validateDecision(
    node: Node<BuilderNodeData>,
    elementKey: string,
    issues: ValidationIssue[]
  ): void {
    const conditions = node.data?.decisionConditions;

    if (!conditions || conditions.length === 0) {
      issues.push(
        this.createError(elementKey, 'core', 'Decision node must have at least one condition', {
          rule: 'decision_no_conditions',
          nodeId: node.id,
        })
      );
      return;
    }

    const ifConditions = conditions.filter((c) => c.type === 'if');
    if (ifConditions.length === 0) {
      issues.push(
        this.createError(elementKey, 'core', "Decision node must have an 'if' condition", {
          rule: 'decision_no_if',
          nodeId: node.id,
        })
      );
    } else if (ifConditions.length > 1) {
      issues.push(
        this.createError(elementKey, 'core', "Decision node can only have one 'if' condition", {
          rule: 'decision_multiple_if',
          nodeId: node.id,
        })
      );
    }

    conditions.forEach((condition, index) => {
      if (condition.type !== 'else' && (!condition.expression || condition.expression.trim() === '')) {
        issues.push(
          this.createError(
            elementKey,
            'core',
            `Condition ${index + 1} (${condition.type || 'unknown'}) is empty`,
            {
              rule: 'decision_empty_condition',
              nodeId: node.id,
              conditionIndex: index,
            }
          )
        );
      }
    });
  }

  private validateLoop(
    node: Node<BuilderNodeData>,
    elementKey: string,
    issues: ValidationIssue[]
  ): void {
    const loopCondition = node.data?.whileCondition ?? node.data?.loopCondition;

    if (!loopCondition || loopCondition.trim() === '') {
      issues.push(
        this.createError(elementKey, 'core', 'Loop node must have a loop condition', {
          rule: 'loop_no_condition',
          nodeId: node.id,
        })
      );
    }
  }

  private validateSwitch(
    node: Node<BuilderNodeData>,
    elementKey: string,
    issues: ValidationIssue[]
  ): void {
    const data = node.data as any;

    // Option node (optionChoices)
    if (nodeRegistry.isOptionNode(node)) {
      const choices = data?.optionChoices;
      if (!choices || choices.length === 0) {
        issues.push(
          this.createError(elementKey, 'core', 'Option node must have at least one choice', {
            rule: 'option_no_choices',
            nodeId: node.id,
          })
        );
        return;
      }
      choices.forEach((choice: any, index: number) => {
        if (!choice.label || choice.label.trim() === '') {
          issues.push(
            this.createError(elementKey, 'core', `Choice ${index + 1} is missing a label`, {
              rule: 'option_empty_choice_label',
              nodeId: node.id,
              choiceIndex: index,
            })
          );
        }
      });
      return;
    }

    // Switch node (switchExpression + switchCases)
    const switchExpression = data?.switchExpression;
    if (!switchExpression || switchExpression.trim() === '') {
      issues.push(
        this.createError(elementKey, 'core', 'Switch expression is missing', {
          rule: 'switch_no_expression',
          nodeId: node.id,
        })
      );
    }

    const cases = data?.switchCases;
    if (!cases || cases.length === 0) {
      issues.push(
        this.createError(elementKey, 'core', 'Switch must have at least one case', {
          rule: 'switch_no_cases',
          nodeId: node.id,
        })
      );
    }
  }

  /**
   * Validates MCP tool required parameters are filled in.
   */
  private validateToolParameters(
    node: Node<BuilderNodeData>,
    elementKey: string,
    issues: ValidationIssue[]
  ): void {
    const data = node.data as any;
    const toolData = data?.toolData;
    if (!toolData) return;

    const parameters = toolData.parameters;
    if (!Array.isArray(parameters) || parameters.length === 0) return;

    const paramExpressions = data.paramExpressions || {};

    for (const param of parameters) {
      const isRequired = param.isRequired === true || param.required === true;
      const paramName = param.name || param.id;
      if (!paramName) continue;

      const expression = paramExpressions[paramName];
      if (isRequired && (!expression || (typeof expression === 'string' && expression.trim() === ''))) {
        issues.push(
          this.createError(elementKey, 'mcp', `Required parameter "${paramName}" is missing`, {
            rule: 'tool_missing_required_param',
            nodeId: node.id,
            parameter: paramName,
          })
        );
      }
    }
  }
}
