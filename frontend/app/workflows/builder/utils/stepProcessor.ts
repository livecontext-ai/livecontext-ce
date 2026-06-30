import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../types';
import type { PlanGeneratorContext } from './planGeneratorContext';
import { normalizeLabel } from './labelNormalizer';
import {
  getNodePosition,
  hasKeys,
  convertParamExpressionsToInputs,
  isAiReasoningNode,
  isTransformNode,
  isWaitNode,
  isDownloadFileNode,
  isHttpRequestNode,
  isDataInputNode,
  isMergeNode,
  isAggregateNode,
  isExitNode,
  isResponseNode,
  isOptionNode,
  isCrudNode,
  isFilterNode,
  isSortNode,
  isLimitNode,
  isRemoveDuplicatesNode,
  isSummarizeNode,
  isDateTimeNode,
  isCryptoJwtNode,
  isXmlNode,
  isCompressionNode,
  isRssNode,
  isConvertToFileNode,
  isExtractFromFileNode,
  isCompareDatasetsNode,
  isSetNode,
  isHtmlExtractNode,
  isTaskNode,
  isSubWorkflowNode,
  isRespondToWebhookNode,
  isSendEmailNode,
  isEmailInboxNode,
  isCodeNode,
  isStopOnErrorNode,
  isSshNode,
  isSftpNode,
  isDatabaseNode,
} from './planHelpers';
import { nodeRegistry } from '../registry/nodeRegistry';

/**
 * Processes all step nodes (MCP catalog tools only) and adds them to the plan.
 * Note: Transform and Wait are now in cores, not mcps.
 */
export function processSteps(ctx: PlanGeneratorContext): void {
  const stepNodes = filterStepNodes(ctx.nodes);
  processStepNodes(stepNodes, ctx);
}

/**
 * Processes all CRUD nodes and adds them to the tables array.
 */
export function processCrudNodes(ctx: PlanGeneratorContext): void {
  const crudNodes = ctx.nodes.filter((node) => isCrudNode(node));

  const validCrudOperations = ['create-row', 'read-row', 'update-row', 'delete-row', 'create-column', 'find-row', 'find-rows', 'find'];

  crudNodes.forEach((node) => {
    const dataSourceData = (node.data as any).dataSourceData;
    const crudOperation = dataSourceData?.crudOperation;

    // Skip nodes with invalid CRUD operations (defense-in-depth)
    if (!crudOperation || !validCrudOperations.includes(crudOperation)) {
      console.warn(`[processCrudNodes] Skipping node ${node.id} with invalid crudOperation: ${crudOperation}`);
      return;
    }

    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);

    ctx.stepLabelMap.set(node.id, normalizedLabel);

    // Build CRUD type from operation (e.g., 'create-row' -> 'crud-create-row')
    // Backend Step.java only accepts 'crud-find', not 'crud-find-row' or 'crud-find-rows'
    const normalizedOp = (crudOperation === 'find-row' || crudOperation === 'find-rows') ? 'find' : crudOperation;
    const crudType = `crud-${normalizedOp}` as 'crud-create-row' | 'crud-read-row' | 'crud-update-row' | 'crud-delete-row' | 'crud-create-column' | 'crud-find';

    const step: any = {
      type: crudType,
      label: label,
      dataSourceId: dataSourceData?.dataSourceId,
      graphNodeId: node.id,
    };

    const nodePosition = getNodePosition(node);
    if (nodePosition) {
      step.position = nodePosition;
    }

    if (node.data.parentLoopId) {
      step.parentLoopId = node.data.parentLoopId;
    }

    const rawParams = convertParamExpressionsToInputs(node.data.paramExpressions);
    // Filter out crud, dataSourceId, similarity - stored in step.crud, step.dataSourceId, and step.params.similarity (via whereCondition export)
    const { crud: _crud, dataSourceId: _dsId, similarity: _sim, ...params } = rawParams;
    if (hasKeys(params)) {
      step.params = params;
    }

    step.crud = {};

    switch (crudOperation) {
      case 'create-row':
        if (dataSourceData.rows && dataSourceData.rows.length > 0) {
          step.crud.rows = dataSourceData.rows.map((row: any) => ({
            id: row.id,
            columns: row.columns || {},
          }));
        }
        break;

      case 'create-column':
        if (dataSourceData.newColumns && dataSourceData.newColumns.length > 0) {
          step.crud.columns = dataSourceData.newColumns.map((col: any) => ({
            name: col.name,
            type: col.type,
            ...(col.defaultValue && { defaultValue: col.defaultValue }),
          }));
        }
        break;

      case 'read-row':
        if (dataSourceData.whereCondition) {
          step.crud.where = {
            column: dataSourceData.whereCondition.column,
            operator: dataSourceData.whereCondition.operator,
            ...(dataSourceData.whereCondition.value && {
              value: dataSourceData.whereCondition.value,
            }),
          };
        }
        step.crud.limit = dataSourceData.limit ?? 50;
        if (dataSourceData.offset != null && dataSourceData.offset > 0) {
          step.crud.offset = dataSourceData.offset;
        }
        break;

      case 'update-row':
        if (dataSourceData.whereCondition) {
          step.crud.where = {
            column: dataSourceData.whereCondition.column,
            operator: dataSourceData.whereCondition.operator,
            ...(dataSourceData.whereCondition.value && {
              value: dataSourceData.whereCondition.value,
            }),
          };
        }
        if (dataSourceData.setColumns) {
          const setObj: Record<string, string> = {};
          if (Array.isArray(dataSourceData.setColumns)) {
            dataSourceData.setColumns.forEach((col: any) => {
              if (col.column && col.value !== undefined) {
                setObj[col.column] = col.value;
              }
            });
          } else {
            Object.assign(setObj, dataSourceData.setColumns);
          }
          if (hasKeys(setObj)) {
            step.crud.set = setObj;
          }
        }
        break;

      case 'delete-row':
        if (dataSourceData.whereCondition) {
          step.crud.where = {
            column: dataSourceData.whereCondition.column,
            operator: dataSourceData.whereCondition.operator,
            ...(dataSourceData.whereCondition.value && {
              value: dataSourceData.whereCondition.value,
            }),
          };
        }
        break;

      case 'find-row':
        if (dataSourceData.whereCondition) {
          if (dataSourceData.whereCondition.operator === 'SIMILAR_TO') {
            // Vector similarity → export as params.similarity
            const col = dataSourceData.whereCondition.column || '';
            const cleanCol = col.startsWith('data.') ? col.slice('data.'.length) : col;
            if (!step.params) step.params = {};
            step.params.similarity = {
              column: cleanCol,
              queryVector: dataSourceData.whereCondition.queryVector || '',
              topK: dataSourceData.whereCondition.topK ?? 5,
            };
          } else {
            // Standard WHERE condition
            step.crud.where = {
              column: dataSourceData.whereCondition.column,
              operator: dataSourceData.whereCondition.operator,
              ...(dataSourceData.whereCondition.value && {
                value: dataSourceData.whereCondition.value,
              }),
            };
          }
        }
        step.crud.limit = dataSourceData.limit ?? 100;
        break;
    }

    // CRUD nodes go to tables array
    ctx.plan.tables!.push(step);
    ctx.stepPlanByNodeId.set(node.id, step);
  });
}

/**
 * Processes Transform, Wait, Aggregate, Stop, Response, Download File, and HTTP Request nodes and adds them to cores array.
 * Note: Option nodes are handled by edgeProcessor.ts (registerOptionNode) to avoid duplication.
 */
export function processTransformAndWaitNodes(ctx: PlanGeneratorContext): void {
  const transformNodes = ctx.nodes.filter((node) => isTransformNode(node));
  const waitNodes = ctx.nodes.filter((node) => isWaitNode(node));
  const downloadFileNodes = ctx.nodes.filter((node) => isDownloadFileNode(node));
  const httpRequestNodes = ctx.nodes.filter((node) => isHttpRequestNode(node));
  const dataInputNodes = ctx.nodes.filter((node) => isDataInputNode(node));
  const aggregateNodes = ctx.nodes.filter((node) => isAggregateNode(node));
  const exitNodes = ctx.nodes.filter((node) => isExitNode(node));
  const responseNodes = ctx.nodes.filter((node) => isResponseNode(node));
  // Note: Option nodes are NOT processed here - they are handled by edgeProcessor.ts registerOptionNode()

  // Process transform nodes
  transformNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,  // Use node.id directly to avoid prefix doubling on reload
      graphNodeId: node.id,
      type: 'transform',
      label: label,
    };

    const transformMappings = (node.data as any).transformMappings;
    if (transformMappings && Array.isArray(transformMappings)) {
      core.transform = {
        mappings: transformMappings.map((m: any) => ({
          label: m.label,
          expression: m.expression || '',
        })),
      };
    }

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) {
      core.params = params;
    }

    const nodePosition = getNodePosition(node);
    if (nodePosition) {
      core.position = nodePosition;
    }

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process wait nodes
  waitNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,  // Use node.id directly to avoid prefix doubling on reload
      graphNodeId: node.id,
      type: 'wait',
      label: label,
    };

    const waitDuration = (node.data as any).waitDuration || 0;
    core.wait = { duration: waitDuration };

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) {
      core.params = params;
    }

    const nodePosition = getNodePosition(node);
    if (nodePosition) {
      core.position = nodePosition;
    }

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process download file nodes
  downloadFileNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,  // Use node.id directly to avoid prefix doubling on reload
      graphNodeId: node.id,
      type: 'download_file',
      label: label,
    };

    // Download config from node data
    const downloadUrl = (node.data as any).downloadUrl || '';
    const downloadFilename = (node.data as any).downloadFilename || '';
    const downloadHeaders = (node.data as any).downloadHeaders;
    const downloadTimeout = (node.data as any).downloadTimeout;

    const hasValidHeaders =
      downloadHeaders &&
      typeof downloadHeaders === 'object' &&
      !Array.isArray(downloadHeaders) &&
      Object.keys(downloadHeaders).length > 0;

    core.download = {
      url: downloadUrl,
      ...(downloadFilename && { filename: downloadFilename }),
      ...(hasValidHeaders && { headers: downloadHeaders }),
      ...(typeof downloadTimeout === 'number' && { timeout: downloadTimeout }),
    };

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) {
      core.params = params;
    }

    const nodePosition = getNodePosition(node);
    if (nodePosition) {
      core.position = nodePosition;
    }

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process HTTP Request nodes
  httpRequestNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,  // Use node.id directly to avoid prefix doubling on reload
      graphNodeId: node.id,
      type: 'http_request',
      label: label,
    };

    // HTTP Request config from node data
    const httpRequestData = (node.data as any).httpRequestData;
    if (httpRequestData) {
      core.httpRequest = {
        method: httpRequestData.method || 'GET',
        url: httpRequestData.url || '',
        ...(httpRequestData.authType && httpRequestData.authType !== 'none' && {
          authType: httpRequestData.authType,
          authConfig: httpRequestData.authConfig,
        }),
        ...(httpRequestData.queryParams && httpRequestData.queryParams.length > 0 && {
          queryParams: httpRequestData.queryParams
            .filter((p: any) => p.key)
            .map((p: any) => ({ key: p.key, value: p.value || '' })),
        }),
        ...(httpRequestData.headers && httpRequestData.headers.length > 0 && {
          headers: httpRequestData.headers
            .filter((h: any) => h.key)
            .map((h: any) => ({ key: h.key, value: h.value || '' })),
        }),
        ...(httpRequestData.bodyType && httpRequestData.bodyType !== 'none' && {
          bodyType: httpRequestData.bodyType,
          body: httpRequestData.body || '',
          ...(httpRequestData.contentType && { contentType: httpRequestData.contentType }),
        }),
        ...(httpRequestData.timeout && { timeout: httpRequestData.timeout }),
      };
    }

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) {
      core.params = params;
    }

    const nodePosition = getNodePosition(node);
    if (nodePosition) {
      core.position = nodePosition;
    }

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process data input nodes (multi-item)
  dataInputNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'data_input',
      label: label,
    };

    // Serialize items array for the plan
    const items = (node.data as any).dataInputItems || [];
    core.dataInput = {
      items: items.map((item: any) => ({
        id: item.id,
        label: item.label,
        type: item.type,
        ...(item.type === 'text' ? { text: item.text || '' } : {}),
        ...(item.type === 'file' && item.file ? { file: item.file } : {}),
      })),
    };

    if ((node.data as any).dataInputWidth) {
      core.dataInputWidth = (node.data as any).dataInputWidth;
    }
    if ((node.data as any).dataInputHeight) {
      core.dataInputHeight = (node.data as any).dataInputHeight;
    }


    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) {
      core.params = params;
    }

    const nodePosition = getNodePosition(node);
    if (nodePosition) {
      core.position = nodePosition;
    }

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process aggregate nodes
  aggregateNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,  // Use node.id directly to avoid prefix doubling on reload
      graphNodeId: node.id,
      type: 'aggregate',
      label: label,
    };

    // Aggregate fields from node data
    const aggregateFields = (node.data as any).aggregateFields;
    if (aggregateFields && Array.isArray(aggregateFields)) {
      core.aggregate = {
        fields: aggregateFields.map((f: any) => ({
          label: f.label,
          expression: f.expression || '',
        })),
      };
    }

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) {
      core.params = params;
    }

    const nodePosition = getNodePosition(node);
    if (nodePosition) {
      core.position = nodePosition;
    }

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process exit nodes
  exitNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,  // Use node.id directly to avoid prefix doubling on reload
      graphNodeId: node.id,
      type: 'exit',
      label: label,
    };

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) {
      core.params = params;
    }

    const nodePosition = getNodePosition(node);
    if (nodePosition) {
      core.position = nodePosition;
    }

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process filter nodes
  const filterNodes = ctx.nodes.filter((node) => isFilterNode(node));
  filterNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'filter',
      label: label,
    };

    const filterConditions = (node.data as any).filterConditions;
    const filterMode = (node.data as any).filterMode || 'and';
    if (filterConditions && Array.isArray(filterConditions)) {
      core.filter = {
        conditions: filterConditions.map((c: any) => ({
          field: c.field || '',
          operator: c.operator || 'equals',
          value: c.value || '',
        })),
        mode: filterMode,
      };
    }

    const d = node.data as any;
    const params: any = convertParamExpressionsToInputs(node.data.paramExpressions) || {};
    if (d.filterInput) params.input = d.filterInput;
    if (hasKeys(params)) core.params = params;

    const nodePosition = getNodePosition(node);
    if (nodePosition) {
      core.position = nodePosition;
    }

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process sort nodes
  const sortNodes = ctx.nodes.filter((node) => isSortNode(node));
  sortNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'sort',
      label: label,
    };

    const sortFields = (node.data as any).sortFields;
    if (sortFields && Array.isArray(sortFields)) {
      core.sort = {
        fields: sortFields.map((f: any) => ({
          field: f.field || '',
          direction: f.direction || 'asc',
        })),
      };
    }

    const dSort = node.data as any;
    const params: any = convertParamExpressionsToInputs(node.data.paramExpressions) || {};
    if (dSort.sortInput) params.input = dSort.sortInput;
    if (hasKeys(params)) core.params = params;

    const nodePosition = getNodePosition(node);
    if (nodePosition) {
      core.position = nodePosition;
    }

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process limit nodes
  const limitNodes = ctx.nodes.filter((node) => isLimitNode(node));
  limitNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'limit',
      label: label,
    };

    const limitCount = (node.data as any).limitCount ?? 10;
    const limitFrom = (node.data as any).limitFrom || 'first';
    const limitOffset = (node.data as any).limitOffset ?? 0;
    core.limit = {
      count: limitCount,
      from: limitFrom,
      offset: limitOffset,
    };

    const dLimit = node.data as any;
    const params: any = convertParamExpressionsToInputs(node.data.paramExpressions) || {};
    if (dLimit.limitInput) params.input = dLimit.limitInput;
    if (hasKeys(params)) core.params = params;

    const nodePosition = getNodePosition(node);
    if (nodePosition) {
      core.position = nodePosition;
    }

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process remove duplicates nodes
  const removeDuplicatesNodes = ctx.nodes.filter((node) => isRemoveDuplicatesNode(node));
  removeDuplicatesNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'remove_duplicates',
      label: label,
    };

    const dedupFields = (node.data as any).dedupFields;
    const dedupKeep = (node.data as any).dedupKeep || 'first';
    core.removeDuplicates = {
      fields: dedupFields || [],
      keep: dedupKeep,
    };

    const dDedup = node.data as any;
    const params: any = convertParamExpressionsToInputs(node.data.paramExpressions) || {};
    if (dDedup.dedupInput) params.input = dDedup.dedupInput;
    if (hasKeys(params)) core.params = params;

    const nodePosition = getNodePosition(node);
    if (nodePosition) {
      core.position = nodePosition;
    }

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process summarize nodes
  const summarizeNodes = ctx.nodes.filter((node) => isSummarizeNode(node));
  summarizeNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'summarize',
      label: label,
    };

    const aggregations = (node.data as any).summarizeAggregations;
    const groupBy = (node.data as any).summarizeGroupBy;
    core.summarize = {
      aggregations: aggregations || [],
      groupBy: groupBy || [],
    };

    const dSummarize = node.data as any;
    const params: any = convertParamExpressionsToInputs(node.data.paramExpressions) || {};
    if (dSummarize.summarizeInput) params.input = dSummarize.summarizeInput;
    if (hasKeys(params)) core.params = params;

    const nodePosition = getNodePosition(node);
    if (nodePosition) {
      core.position = nodePosition;
    }

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process date/time nodes
  const dateTimeNodes = ctx.nodes.filter((node) => isDateTimeNode(node));
  dateTimeNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'date_time',
      label: label,
    };

    const dateTimeConfig: any = {};
    const d = node.data as any;
    if (d.dateTimeOperation) dateTimeConfig.operation = d.dateTimeOperation;
    if (d.dateTimeValue) dateTimeConfig.value = d.dateTimeValue;
    if (d.dateTimeInputFormat) dateTimeConfig.inputFormat = d.dateTimeInputFormat;
    if (d.dateTimeOutputFormat) dateTimeConfig.outputFormat = d.dateTimeOutputFormat;
    if (d.dateTimeTimezone) dateTimeConfig.timezone = d.dateTimeTimezone;
    if (d.dateTimeTargetTimezone) dateTimeConfig.targetTimezone = d.dateTimeTargetTimezone;
    if (d.dateTimeDurationUnit) dateTimeConfig.durationUnit = d.dateTimeDurationUnit;
    if (d.dateTimeDurationAmount) dateTimeConfig.durationAmount = d.dateTimeDurationAmount;
    if (d.dateTimeSecondValue) dateTimeConfig.secondValue = d.dateTimeSecondValue;
    if (d.dateTimeExtractPart) dateTimeConfig.extractPart = d.dateTimeExtractPart;
    core.dateTime = dateTimeConfig;

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) {
      core.params = params;
    }

    const nodePosition = getNodePosition(node);
    if (nodePosition) {
      core.position = nodePosition;
    }

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process crypto/JWT nodes
  const cryptoJwtNodes = ctx.nodes.filter((node) => isCryptoJwtNode(node));
  cryptoJwtNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'crypto_jwt',
      label: label,
    };

    const cryptoConfig: any = {};
    const d = node.data as any;
    if (d.cryptoOperation) cryptoConfig.operation = d.cryptoOperation;
    if (d.cryptoAlgorithm) cryptoConfig.algorithm = d.cryptoAlgorithm;
    if (d.cryptoValue) cryptoConfig.value = d.cryptoValue;
    if (d.cryptoKey) cryptoConfig.key = d.cryptoKey;
    if (d.cryptoSecret) cryptoConfig.secret = d.cryptoSecret;
    if (d.cryptoToken) cryptoConfig.token = d.cryptoToken;
    if (d.cryptoPayload) cryptoConfig.payload = d.cryptoPayload;
    if (d.cryptoEncoding) cryptoConfig.encoding = d.cryptoEncoding;
    core.cryptoJwt = cryptoConfig;

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) {
      core.params = params;
    }

    const nodePosition = getNodePosition(node);
    if (nodePosition) {
      core.position = nodePosition;
    }

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process XML nodes
  const xmlNodes = ctx.nodes.filter((node) => isXmlNode(node));
  xmlNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'xml',
      label: label,
    };

    const xmlConfig: any = {};
    const d = node.data as any;
    if (d.xmlOperation) xmlConfig.operation = d.xmlOperation;
    if (d.xmlInput) xmlConfig.value = d.xmlInput;
    if (d.xmlRootElement) xmlConfig.rootElement = d.xmlRootElement;
    if (d.xmlPreserveAttributes != null) xmlConfig.preserveAttributes = d.xmlPreserveAttributes;
    core.xml = xmlConfig;

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) {
      core.params = params;
    }

    const nodePosition = getNodePosition(node);
    if (nodePosition) {
      core.position = nodePosition;
    }

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process compression nodes
  const compressionNodes = ctx.nodes.filter((node) => isCompressionNode(node));
  compressionNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'compression',
      label: label,
    };

    const compressionConfig: any = {};
    const d = node.data as any;
    if (d.compressionOperation) compressionConfig.operation = d.compressionOperation;
    if (d.compressionFormat) compressionConfig.format = d.compressionFormat;
    if (d.compressionInput) compressionConfig.value = d.compressionInput;
    if (d.compressionFilename) compressionConfig.filename = d.compressionFilename;
    core.compression = compressionConfig;

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) {
      core.params = params;
    }

    const nodePosition = getNodePosition(node);
    if (nodePosition) {
      core.position = nodePosition;
    }

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process RSS nodes
  const rssNodes = ctx.nodes.filter((node) => isRssNode(node));
  rssNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'rss',
      label: label,
    };

    const rssConfig: any = {};
    const d = node.data as any;
    if (d.rssUrl) rssConfig.url = d.rssUrl;
    if (d.rssMaxItems) rssConfig.maxItems = d.rssMaxItems;
    core.rss = rssConfig;

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) {
      core.params = params;
    }

    const nodePosition = getNodePosition(node);
    if (nodePosition) {
      core.position = nodePosition;
    }

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process ConvertToFile nodes
  const convertToFileNodes = ctx.nodes.filter((node) => isConvertToFileNode(node));
  convertToFileNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'convert_to_file',
      label: label,
    };

    const config: any = {};
    const d = node.data as any;
    if (d.convertFormat) config.format = d.convertFormat;
    if (d.convertValue) config.value = d.convertValue;
    if (d.convertFilename) config.filename = d.convertFilename;
    if (d.convertDelimiter) config.delimiter = d.convertDelimiter;
    if (d.convertIncludeHeaders !== undefined) config.includeHeaders = d.convertIncludeHeaders;
    core.convertToFile = config;

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) core.params = params;
    const nodePosition = getNodePosition(node);
    if (nodePosition) core.position = nodePosition;

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process ExtractFromFile nodes
  const extractFromFileNodes = ctx.nodes.filter((node) => isExtractFromFileNode(node));
  extractFromFileNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'extract_from_file',
      label: label,
    };

    const config: any = {};
    const d = node.data as any;
    if (d.extractFormat) config.format = d.extractFormat;
    if (d.extractValue) config.value = d.extractValue;
    if (d.extractMode) config.mode = d.extractMode;
    if (d.extractDelimiter) config.delimiter = d.extractDelimiter;
    if (d.extractSheetName) config.sheetName = d.extractSheetName;
    if (d.extractHasHeaders !== undefined) config.hasHeaders = d.extractHasHeaders;
    if (d.extractChunking) config.chunking = d.extractChunking;
    if (d.extractChunkSize) config.chunkSize = parseInt(d.extractChunkSize, 10) || 500;
    if (d.extractOverlap) config.overlap = parseInt(d.extractOverlap, 10) || 50;
    if (d.extractChunkingStrategy) config.chunkingStrategy = d.extractChunkingStrategy;
    if (d.extractSeparator) config.separator = d.extractSeparator;
    if (d.extractChunkUnit) config.chunkUnit = d.extractChunkUnit;
    core.extractFromFile = config;

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) core.params = params;
    const nodePosition = getNodePosition(node);
    if (nodePosition) core.position = nodePosition;

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process CompareDatasets nodes
  const compareDatasetsNodes = ctx.nodes.filter((node) => isCompareDatasetsNode(node));
  compareDatasetsNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'compare_datasets',
      label: label,
    };

    const config: any = {};
    const d = node.data as any;
    if (d.compareInputA) config.inputA = d.compareInputA;
    if (d.compareInputB) config.inputB = d.compareInputB;
    if (d.compareMatchFields) config.matchFields = d.compareMatchFields;
    if (d.compareReturnMatched !== undefined) config.returnMatched = d.compareReturnMatched;
    if (d.compareReturnOnlyA !== undefined) config.returnOnlyA = d.compareReturnOnlyA;
    if (d.compareReturnOnlyB !== undefined) config.returnOnlyB = d.compareReturnOnlyB;
    core.compareDatasets = config;

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) core.params = params;
    const nodePosition = getNodePosition(node);
    if (nodePosition) core.position = nodePosition;

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process Set nodes
  const setNodes = ctx.nodes.filter((node) => isSetNode(node));
  setNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'set',
      label: label,
    };

    const d = node.data as any;
    const config: any = {
      assignments: Array.isArray(d.setAssignments) ? d.setAssignments : [],
      keepOnlySet: !!d.setKeepOnlySet,
    };
    if (d.setInput) config.input = d.setInput;
    core.set = config;

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) core.params = params;
    const nodePosition = getNodePosition(node);
    if (nodePosition) core.position = nodePosition;

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process HtmlExtract nodes
  const htmlExtractNodes = ctx.nodes.filter((node) => isHtmlExtractNode(node));
  htmlExtractNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'html_extract',
      label: label,
    };

    const d = node.data as any;
    const config: any = {
      sourceHtml: d.htmlExtractSource || '',
      extractionMode: d.htmlExtractMode || 'single',
      cleanWhitespace: d.htmlExtractCleanWhitespace !== false,
      fields: Array.isArray(d.htmlExtractFields) ? d.htmlExtractFields : [],
    };
    if (d.htmlExtractRootSelector) config.rootSelector = d.htmlExtractRootSelector;
    core.htmlExtract = config;

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) core.params = params;
    const nodePosition = getNodePosition(node);
    if (nodePosition) core.position = nodePosition;

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process Task nodes
  const taskNodes = ctx.nodes.filter((node) => isTaskNode(node));
  taskNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'task',
      label: label,
    };

    const d = node.data as any;
    const config: any = {
      operation: d.taskOperation || 'create_task',
    };
    if (d.taskTaskId) config.taskId = d.taskTaskId;
    if (d.taskTitle) config.title = d.taskTitle;
    if (d.taskInstructions) config.instructions = d.taskInstructions;
    if (d.taskPriority) config.priority = d.taskPriority;
    if (d.taskAgentId) config.agentId = d.taskAgentId;
    if (d.taskReviewerAgentId) config.reviewerAgentId = d.taskReviewerAgentId;
    if (d.taskStatus) config.status = d.taskStatus;
    if (d.taskSearch) config.search = d.taskSearch;
    if (d.taskLimit && d.taskLimit !== 50) config.limit = d.taskLimit;
    if (typeof d.taskContextJson === 'string' && d.taskContextJson.trim()) {
      // Backend (Core.TaskConfig) expects an object; invalid JSON is flagged by
      // NodeConfigurationRule and dropped here rather than corrupting the plan.
      try {
        const parsed = JSON.parse(d.taskContextJson);
        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed) && Object.keys(parsed).length > 0) {
          config.taskContext = parsed;
        }
      } catch {
        // ignore - surfaced as a builder validation issue
      }
    }
    core.task = config;

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) core.params = params;
    const nodePosition = getNodePosition(node);
    if (nodePosition) core.position = nodePosition;

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process SubWorkflow nodes
  const subWorkflowNodes = ctx.nodes.filter((node) => isSubWorkflowNode(node));
  subWorkflowNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'sub_workflow',
      label: label,
    };

    const config: any = {};
    const d = node.data as any;
    config.workflowId = d.subWorkflowId || d.workflowData?.workflowId;
    if (d.workflowData?.workflowName) config.workflowName = d.workflowData.workflowName;
    if (d.subWorkflowInputMapping) config.inputMapping = d.subWorkflowInputMapping;
    if (d.subWorkflowTimeoutSeconds) config.timeoutSeconds = d.subWorkflowTimeoutSeconds;
    if (d.subWorkflowMaxDepth) config.maxDepth = d.subWorkflowMaxDepth;
    core.subWorkflow = config;

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) core.params = params;
    const nodePosition = getNodePosition(node);
    if (nodePosition) core.position = nodePosition;

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process RespondToWebhook nodes
  const respondToWebhookNodes = ctx.nodes.filter((node) => isRespondToWebhookNode(node));
  respondToWebhookNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'respond_to_webhook',
      label: label,
    };

    const config: any = {};
    const d = node.data as any;
    if (d.respondToWebhookStatusCode) config.statusCode = d.respondToWebhookStatusCode;
    if (d.respondToWebhookBody) config.body = d.respondToWebhookBody;
    if (d.respondToWebhookContentType) config.contentType = d.respondToWebhookContentType;
    if (d.respondToWebhookHeaders) config.headers = d.respondToWebhookHeaders;
    core.respondToWebhook = config;

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) core.params = params;
    const nodePosition = getNodePosition(node);
    if (nodePosition) core.position = nodePosition;

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process SendEmail nodes
  const sendEmailNodes = ctx.nodes.filter((node) => isSendEmailNode(node));
  sendEmailNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'send_email',
      label: label,
    };

    const config: any = {};
    const d = node.data as any;
    // Per-email fields only - SMTP credentials come from Settings > Credentials
    if (d.emailTo) config.toEmail = d.emailTo;
    if (d.emailCc) config.ccEmail = d.emailCc;
    if (d.emailBcc) config.bccEmail = d.emailBcc;
    if (d.emailFromName) config.fromName = d.emailFromName;
    if (d.emailSubject) config.subject = d.emailSubject;
    if (d.emailBody != null && d.emailBody !== '') config.body = d.emailBody;
    if (d.emailIsHtml === 'true' || d.emailIsHtml === true) config.isHtml = true;
    if (d.emailInReplyTo) config.inReplyTo = d.emailInReplyTo;
    if (d.emailReferences) config.references = d.emailReferences;
    if (d.smtpCredentialId) config.credentialId = d.smtpCredentialId;
    core.sendEmail = config;

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) core.params = params;
    const nodePosition = getNodePosition(node);
    if (nodePosition) core.position = nodePosition;

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process EmailInbox nodes
  const emailInboxNodes = ctx.nodes.filter((node) => isEmailInboxNode(node));
  emailInboxNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'email_inbox',
      label: label,
    };

    const config: any = {};
    const d = node.data as any;
    // Per-node fields only - IMAP credentials come from Settings > Credentials
    if (d.emailFolder) config.folder = d.emailFolder;
    if (d.emailUnreadOnly === 'true' || d.emailUnreadOnly === true) config.unreadOnly = true;
    if (d.emailLimit != null && d.emailLimit !== '') config.limit = Number(d.emailLimit);
    if (d.emailMarkSeen === 'true' || d.emailMarkSeen === true) config.markSeen = true;
    if (d.emailSinceDays != null && d.emailSinceDays !== '') config.sinceDays = Number(d.emailSinceDays);
    if (d.emailAction) config.action = d.emailAction;
    if (d.emailMessageUid) config.messageUid = d.emailMessageUid;
    if (d.emailTargetFolder) config.targetFolder = d.emailTargetFolder;
    if (d.emailFromContains) config.fromContains = d.emailFromContains;
    if (d.emailSubjectContains) config.subjectContains = d.emailSubjectContains;
    if (d.emailBodyContains) config.bodyContains = d.emailBodyContains;
    if (d.emailFlaggedOnly === 'true' || d.emailFlaggedOnly === true) config.flaggedOnly = true;
    if (d.emailBeforeDays != null && d.emailBeforeDays !== '') config.beforeDays = Number(d.emailBeforeDays);
    if (d.emailDownloadAttachments === 'true' || d.emailDownloadAttachments === true) config.downloadAttachments = true;
    if (d.imapCredentialId) config.credentialId = d.imapCredentialId;
    core.emailInbox = config;

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) core.params = params;
    const nodePosition = getNodePosition(node);
    if (nodePosition) core.position = nodePosition;

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process Code nodes
  const codeNodes = ctx.nodes.filter((node) => isCodeNode(node));
  codeNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'code',
      label: label,
    };

    const config: any = {};
    const d = node.data as any;
    if (d.codeLanguage) config.language = d.codeLanguage;
    if (d.codeContent != null && d.codeContent !== '') config.code = d.codeContent;
    const timeout = d.codeTimeoutSeconds ?? d.codeTimeout;
    if (timeout) config.timeoutSeconds = timeout;
    core.code = config;
    console.log('[stepProcessor] Code node export:', node.id, 'codeContent:', d.codeContent?.substring(0, 80), 'config:', JSON.stringify(config));

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) core.params = params;
    const nodePosition = getNodePosition(node);
    if (nodePosition) core.position = nodePosition;

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process StopOnError nodes
  const stopOnErrorNodes = ctx.nodes.filter((node) => isStopOnErrorNode(node));
  stopOnErrorNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'stop_on_error',
      label: label,
    };

    const config: any = {};
    const d = node.data as any;
    if (d.stopOnErrorMessage) config.errorMessage = d.stopOnErrorMessage;
    if (d.stopOnErrorCode) config.errorCode = d.stopOnErrorCode;
    if (Object.keys(config).length > 0) core.stopOnError = config;

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) core.params = params;
    const nodePosition = getNodePosition(node);
    if (nodePosition) core.position = nodePosition;

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process SSH nodes
  const sshNodes = ctx.nodes.filter((node) => isSshNode(node));
  sshNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'ssh',
      label: label,
    };

    const config: any = {};
    const d = node.data as any;
    if (d.sshCredentialId) config.credentialId = d.sshCredentialId;
    if (d.sshCommand) config.command = d.sshCommand;
    if (d.sshTimeout && d.sshTimeout !== 30000) config.timeout = d.sshTimeout;
    core.ssh = config;

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) core.params = params;
    const nodePosition = getNodePosition(node);
    if (nodePosition) core.position = nodePosition;

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process SFTP nodes
  const sftpNodes = ctx.nodes.filter((node) => isSftpNode(node));
  sftpNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'sftp',
      label: label,
    };

    const config: any = {};
    const d = node.data as any;
    if (d.sftpCredentialId) config.credentialId = d.sftpCredentialId;
    if (d.sftpOperation) config.operation = d.sftpOperation;
    if (d.sftpRemotePath) config.remotePath = d.sftpRemotePath;
    if (d.sftpLocalContent) config.localContent = d.sftpLocalContent;
    if (d.sftpNewPath) config.newPath = d.sftpNewPath;
    if (d.sftpTimeout && d.sftpTimeout !== 30000) config.timeout = d.sftpTimeout;
    core.sftp = config;

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) core.params = params;
    const nodePosition = getNodePosition(node);
    if (nodePosition) core.position = nodePosition;

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process Database nodes
  const databaseNodes = ctx.nodes.filter((node) => isDatabaseNode(node));
  databaseNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,
      graphNodeId: node.id,
      type: 'database',
      label: label,
    };

    const config: any = {};
    const d = node.data as any;
    if (d.dbCredentialId) config.credentialId = d.dbCredentialId;
    if (d.dbOperation) config.operation = d.dbOperation;
    if (d.dbQuery) config.query = d.dbQuery;
    if (d.dbQueryParams && d.dbQueryParams.length > 0) config.queryParams = d.dbQueryParams;
    if (d.dbTimeout && d.dbTimeout !== 30000) config.timeout = d.dbTimeout;
    core.database = config;

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) core.params = params;
    const nodePosition = getNodePosition(node);
    if (nodePosition) core.position = nodePosition;

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

  // Process response nodes
  responseNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    const core: any = {
      id: node.id,  // Use node.id directly to avoid prefix doubling on reload
      graphNodeId: node.id,
      type: 'response',
      label: label,
    };

    // Response message expression from node data
    const responseMessage = (node.data as any).responseMessage;
    if (responseMessage) {
      core.response = {
        message: responseMessage,
      };
    }

    const params = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(params)) {
      core.params = params;
    }

    const nodePosition = getNodePosition(node);
    if (nodePosition) {
      core.position = nodePosition;
    }

    ctx.plan.cores!.push(core);
    ctx.stepPlanByNodeId.set(node.id, core);
  });

}

/**
 * Filters nodes to get only MCP step nodes (excluding triggers, agents, control nodes, transform, wait, etc.).
 * Uses nodeRegistry for centralized node type detection.
 */
function filterStepNodes(nodes: Node<BuilderNodeData>[]): Node<BuilderNodeData>[] {
  return nodes.filter((node) => {
    // Exclude CRUD nodes
    if (isCrudNode(node)) return false;
    // Exclude triggers
    if (nodeRegistry.isTrigger(node)) return false;
    // Exclude agents
    if (isAiReasoningNode(node)) return false;
    // Exclude all control nodes (decision, switch, option, loop, split, merge, fork, transform, wait, download_file, aggregate, exit, response)
    if (nodeRegistry.isControlNode(node)) return false;
    // Exclude notes
    if (nodeRegistry.isNoteNode(node)) return false;
    // Exclude interface nodes
    if (nodeRegistry.isInterfaceNode(node)) return false;
    // Include only tool/API nodes
    if (node.data.toolData || node.data.apiData) return true;
    return false;
  });
}

/**
 * Processes MCP step nodes and adds them to the plan.
 */
function processStepNodes(
  stepNodes: Node<BuilderNodeData>[],
  ctx: PlanGeneratorContext
): void {
  stepNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);

    let stepId: string;
    if (node.data.toolData?.toolSlug) {
      if (node.data.apiData?.apiSlug) {
        stepId = `${node.data.apiData.apiSlug}/${node.data.toolData.toolSlug}`;
      } else {
        stepId = node.data.toolData.toolSlug;
      }
    } else if (node.data.apiData?.apiSlug) {
      stepId = node.data.apiData.apiSlug;
    } else {
      stepId = normalizedLabel;
    }

    const step: any = {
      id: stepId,
      type: 'mcp',
      label: label,
      graphNodeId: node.id,
    };

    // Persist iconSlug for node icon display on workflow cards
    const iconSlug = node.data.toolData?.iconSlug || node.data.apiData?.iconSlug;
    if (iconSlug) {
      step.iconSlug = iconSlug;
    }

    const stepParams = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(stepParams)) {
      step.params = stepParams;
    }

    // Credential source / credential pin.
    // User-selected credentials are workflow-specific and must be preserved
    // explicitly; otherwise catalog execution resolves by integration and picks
    // the default credential. Platform pins stay on their existing source path.
    const toolData = (node.data as any)?.toolData;
    if (toolData?.credentialSource === 'platform' && toolData.platformCredentialId != null) {
      step.credentialSource = 'platform';
      step.platformCredentialId = toolData.platformCredentialId;
    } else if (toolData?.selectedCredentialId != null) {
      step.selectedCredentialId = toolData.selectedCredentialId;
    }

    const nodePosition = getNodePosition(node);
    if (nodePosition) {
      step.position = nodePosition;
    } else if (node.data.toolData || node.data.apiData) {
      step.position = { x: 0, y: 0 };
    }

    ctx.plan.mcps.push(step);
    ctx.stepPlanByNodeId.set(node.id, step);
  });
}
