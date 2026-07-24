/**
 * Form Adapters - Bridge between InspectorFormProps and legacy form interfaces.
 *
 * Each adapter converts InspectorFormProps to the format expected by each form.
 * No legacy, no fallback - all forms are properly adapted.
 */

'use client';

import * as React from 'react';
import { useState } from 'react';
import { InspectorFormProps } from './types';
import { normalizeLabel } from '../../../utils/labelNormalizer';

// =============================================================================
// TRIGGER FORMS
// =============================================================================

import { ManualTriggerParametersForm } from '../forms/ManualTriggerParametersForm';
import { ChatTriggerParametersForm } from '../forms/ChatTriggerParametersForm';
import { WebhookTriggerParametersForm } from '../forms/WebhookTriggerParametersForm';
import { ScheduleTriggerParametersForm } from '../forms/ScheduleTriggerParametersForm';
import { FormTriggerParametersForm } from '../forms/FormTriggerParametersForm';
import { WorkflowTriggerParametersForm } from '../forms/WorkflowTriggerParametersForm';
import { ErrorTriggerParametersForm } from '../forms/ErrorTriggerParametersForm';
import { TablesTriggerParametersForm } from '../forms/TablesTriggerParametersForm';

// =============================================================================
// CORE FORMS
// =============================================================================

import { TransformParametersForm } from '../forms/TransformParametersForm';
import { FilterParametersForm } from '../forms/FilterParametersForm';
import { MergeParametersForm } from '../forms/MergeParametersForm';
import { ForkParametersForm } from '../forms/ForkParametersForm';
import { WaitParametersForm } from '../forms/WaitParametersForm';
import { DecisionBranchesForm } from '../forms/DecisionBranchesForm';
import { SwitchCasesForm } from '../forms/SwitchCasesForm';
import { SplitParametersForm } from '../forms/SplitParametersForm';
import { AggregateParametersForm } from '../forms/AggregateParametersForm';
import { DownloadFileParametersForm } from '../forms/DownloadFileParametersForm';
import { PublicLinkParametersForm } from '../forms/PublicLinkParametersForm';
import { MediaParametersForm } from '../forms/MediaParametersForm';
import { HttpRequestParametersForm } from '../forms/HttpRequestParametersForm';
import { DataInputParametersForm } from '../forms/DataInputParametersForm';
import { WhileGroupParametersForm } from '../forms/WhileGroupParametersForm';
import { ResponseParametersForm } from '../forms/ResponseParametersForm';
import { SortParametersForm } from '../forms/SortParametersForm';
import { LimitParametersForm } from '../forms/LimitParametersForm';
import { RemoveDuplicatesParametersForm } from '../forms/RemoveDuplicatesParametersForm';
import { SummarizeParametersForm } from '../forms/SummarizeParametersForm';
import { DateTimeParametersForm } from '../forms/DateTimeParametersForm';
import { CryptoJwtParametersForm } from '../forms/CryptoJwtParametersForm';
import { XmlParametersForm } from '../forms/XmlParametersForm';
import { CompressionParametersForm } from '../forms/CompressionParametersForm';
import { RssParametersForm } from '../forms/RssParametersForm';
import { ConvertToFileParametersForm } from '../forms/ConvertToFileParametersForm';
import { ExtractFromFileParametersForm } from '../forms/ExtractFromFileParametersForm';
import { CompareDatasetsParametersForm } from '../forms/CompareDatasetsParametersForm';
import { SubWorkflowParametersForm } from '../forms/SubWorkflowParametersForm';
import { RespondToWebhookParametersForm } from '../forms/RespondToWebhookParametersForm';
import { SendEmailParametersForm } from '../forms/SendEmailParametersForm';
import { EmailInboxParametersForm } from '../forms/EmailInboxParametersForm';
import { CodeParametersForm } from '../forms/CodeParametersForm';
import { SetParametersForm } from '../forms/SetParametersForm';
import { HtmlExtractParametersForm } from '../forms/HtmlExtractParametersForm';
import { TaskParametersForm } from '../forms/TaskParametersForm';
import { StopOnErrorParametersForm } from '../forms/StopOnErrorParametersForm';
import { SshParametersForm } from '../forms/SshParametersForm';
import { SftpParametersForm } from '../forms/SftpParametersForm';
import { DatabaseParametersForm } from '../forms/DatabaseParametersForm';

// =============================================================================
// AGENT FORMS - Handled directly in ParameterColumn
// =============================================================================

// =============================================================================
// OTHER FORMS
// =============================================================================

import { NoteParametersForm } from '../forms/NoteParametersForm';

// =============================================================================
// HELPER: Unpack connection props to legacy format
// =============================================================================

function unpackConnectionProps(connectionProps: InspectorFormProps['connectionProps'], nodeId: string) {
  return {
    connections: connectionProps.connections,
    draggingFromHandle: connectionProps.draggingFromHandle,
    hoveredTargetHandle: connectionProps.hoveredTargetHandle,
    handleHandleClick: (handleId: string, e: React.MouseEvent) =>
      connectionProps.handleHandleClick(handleId, nodeId),
    handleHandleMouseDown: (handleId: string, e: React.MouseEvent) =>
      connectionProps.handleHandleMouseDown(e, handleId, nodeId),
    handleHandleMouseUp: (handleId: string, e: React.MouseEvent) =>
      connectionProps.handleHandleMouseUp(handleId, nodeId),
    handleSetHandleRef: (handleId: string, el: HTMLDivElement | null) =>
      connectionProps.handleSetHandleRef(handleId, el),
  };
}

// =============================================================================
// TRIGGER ADAPTERS
// =============================================================================

export function ManualTriggerFormAdapter(props: InspectorFormProps) {
  // Calculate triggerId from node label for multi-DAG support
  const nodeLabel = props.node.data.label;
  const normalizedLabel = normalizeLabel(nodeLabel);
  const triggerId = normalizedLabel ? `trigger:${normalizedLabel}` : null;

  return (
    <ManualTriggerParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      triggerId={triggerId}
    />
  );
}

export function ChatTriggerFormAdapter(props: InspectorFormProps) {
  // Calculate triggerId from node label for multi-DAG support
  const nodeLabel = props.node.data.label;
  const normalizedLabel = normalizeLabel(nodeLabel);
  const triggerId = normalizedLabel ? `trigger:${normalizedLabel}` : null;

  return (
    <ChatTriggerParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      triggerId={triggerId}
    />
  );
}

export function WebhookTriggerFormAdapter(props: InspectorFormProps) {
  return (
    <WebhookTriggerParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
    />
  );
}

export function ScheduleTriggerFormAdapter(props: InspectorFormProps) {
  // Calculate triggerId from node label for multi-DAG support
  const nodeLabel = props.node.data.label;
  const normalizedLabel = normalizeLabel(nodeLabel);
  const triggerId = normalizedLabel ? `trigger:${normalizedLabel}` : null;

  return (
    <ScheduleTriggerParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      triggerId={triggerId}
    />
  );
}

export function FormTriggerFormAdapter(props: InspectorFormProps) {
  // Calculate triggerId from node label for multi-DAG form lookup
  const nodeLabel = props.node.data.label;
  const normalizedLabel = normalizeLabel(nodeLabel);
  const triggerId = normalizedLabel ? `trigger:${normalizedLabel}` : null;

  return (
    <FormTriggerParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      triggerId={triggerId}
    />
  );
}

export function WorkflowsTriggerFormAdapter(props: InspectorFormProps) {
  // Calculate triggerId from node label for multi-DAG support
  const nodeLabel = props.node.data.label;
  const normalizedLabel = normalizeLabel(nodeLabel);
  const triggerId = normalizedLabel ? `trigger:${normalizedLabel}` : null;

  return (
    <WorkflowTriggerParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      triggerId={triggerId}
    />
  );
}

export function ErrorTriggerFormAdapter(props: InspectorFormProps) {
  // Mirrors WorkflowsTriggerFormAdapter - error trigger fires on parent FAILED/PARTIAL_SUCCESS
  // instead of completion. Per-trigger triggerId for multi-DAG (a workflow can watch several
  // parents at once with separately-labelled error triggers).
  const nodeLabel = props.node.data.label;
  const normalizedLabel = normalizeLabel(nodeLabel);
  const triggerId = normalizedLabel ? `trigger:${normalizedLabel}` : null;

  return (
    <ErrorTriggerParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      triggerId={triggerId}
    />
  );
}

export function TablesTriggerFormAdapter(props: InspectorFormProps) {
  // Tables trigger is event-driven: the inspector configures event types
  // + an optional server-side filter. Row columns are declared at the datasource
  // level and discovered dynamically by the output column - the node has no
  // column-mapping of its own. The form always renders (event types are always
  // configurable); `columns` is only consumed for the filter-column dropdown.
  return (
    <TablesTriggerParametersForm
      node={props.node}
      data={props.node.data}
      columns={props.columns as any}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
    />
  );
}

// =============================================================================
// CORE ADAPTERS
// =============================================================================

export function TransformFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);

  return (
    <TransformParametersForm
      node={props.node}
      data={props.node.data}
      connections={legacyProps.connections}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      findUnknownVariables={props.findUnknownVariables}
      draggingFromHandle={legacyProps.draggingFromHandle}
      hoveredTargetHandle={legacyProps.hoveredTargetHandle}
      handleHandleClick={legacyProps.handleHandleClick}
      handleHandleMouseDown={legacyProps.handleHandleMouseDown}
      handleHandleMouseUp={legacyProps.handleHandleMouseUp}
      handleSetHandleRef={legacyProps.handleSetHandleRef}
    />
  );
}

export function MergeFormAdapter(props: InspectorFormProps) {
  return (
    <MergeParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
    />
  );
}

export function ForkFormAdapter(props: InspectorFormProps) {
  return (
    <ForkParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
    />
  );
}

export function WaitFormAdapter(props: InspectorFormProps) {
  return (
    <WaitParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
    />
  );
}

export function DecisionFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);

  // Decision form requires condition state from parent
  if (!props.currentConditions || !props.getConditionExpression) {
    return null;
  }

  return (
    <DecisionBranchesForm
      node={props.node}
      connections={legacyProps.connections}
      isRunMode={props.isRunMode}
      currentConditions={props.currentConditions}
      getConditionHandleId={props.getConditionHandleId || (() => '')}
      getConditionExpression={props.getConditionExpression}
      handleConditionExpressionChange={props.handleConditionExpressionChange || (() => {})}
      handleAddCondition={props.handleAddCondition || (() => {})}
      handleDeleteCondition={props.handleDeleteCondition || (() => {})}
      handleRenameCondition={props.handleRenameCondition || (() => {})}
      findUnknownVariables={props.findUnknownVariables}
      draggingFromHandle={legacyProps.draggingFromHandle}
      hoveredTargetHandle={legacyProps.hoveredTargetHandle}
      handleHandleClick={legacyProps.handleHandleClick}
      handleHandleMouseDown={legacyProps.handleHandleMouseDown}
      handleHandleMouseUp={legacyProps.handleHandleMouseUp}
      handleSetHandleRef={legacyProps.handleSetHandleRef}
    />
  );
}

export function SwitchFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);

  // Switch form requires case state from parent
  if (!props.currentCases || !props.getCaseValue) {
    return null;
  }

  return (
    <SwitchCasesForm
      node={props.node}
      connections={legacyProps.connections}
      isRunMode={props.isRunMode}
      currentCases={props.currentCases}
      switchExpression={props.switchExpression || ''}
      getCaseHandleId={props.getCaseHandleId || (() => '')}
      getCaseValue={props.getCaseValue}
      handleCaseValueChange={props.handleCaseValueChange || (() => {})}
      handleSwitchExpressionChange={props.handleSwitchExpressionChange || (() => {})}
      handleAddCase={props.handleAddCase || (() => {})}
      handleDeleteCase={props.handleDeleteCase || (() => {})}
      handleRenameCase={props.handleRenameCase || (() => {})}
      findUnknownVariables={props.findUnknownVariables}
      draggingFromHandle={legacyProps.draggingFromHandle}
      hoveredTargetHandle={legacyProps.hoveredTargetHandle}
      handleHandleClick={legacyProps.handleHandleClick}
      handleHandleMouseDown={legacyProps.handleHandleMouseDown}
      handleHandleMouseUp={legacyProps.handleHandleMouseUp}
      handleSetHandleRef={legacyProps.handleSetHandleRef}
    />
  );
}

export function LoopFormAdapter(_props: InspectorFormProps) {
  // Loop configuration is now handled via back-edge config panel (BackEdgeConfigPanel)
  return null;
}

export function SplitFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);
  const [showOptional, setShowOptional] = useState(props.showOptionalParams || false);

  return (
    <SplitParametersForm
      node={props.node}
      data={props.node.data}
      connections={legacyProps.connections}
      isRunMode={props.isRunMode}
      showOptionalParams={showOptional}
      onToggleOptionalParams={() => setShowOptional(!showOptional)}
      onUpdate={(data) => props.onUpdate(data)}
      getListExpression={() => props.node.data.list || ''}
      handleListExpressionChange={(value) =>
        props.onUpdate({ list: value })
      }
      findUnknownVariables={props.findUnknownVariables}
      draggingFromHandle={legacyProps.draggingFromHandle}
      hoveredTargetHandle={legacyProps.hoveredTargetHandle}
      handleHandleClick={legacyProps.handleHandleClick}
      handleHandleMouseDown={legacyProps.handleHandleMouseDown}
      handleHandleMouseUp={legacyProps.handleHandleMouseUp}
      handleSetHandleRef={legacyProps.handleSetHandleRef}
    />
  );
}

export function WhileGroupFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);
  const [showOptional, setShowOptional] = useState(props.showOptionalParams || false);

  return (
    <WhileGroupParametersForm
      node={props.node}
      data={props.node.data}
      connections={legacyProps.connections}
      isRunMode={props.isRunMode}
      showOptionalParams={showOptional}
      onToggleOptionalParams={() => setShowOptional(!showOptional)}
      onUpdate={(data) => props.onUpdate(data)}
      getConditionExpression={() => props.node.data.whileCondition || ''}
      handleConditionExpressionChange={(value) =>
        props.onUpdate({ whileCondition: value })
      }
      findUnknownVariables={props.findUnknownVariables}
      draggingFromHandle={legacyProps.draggingFromHandle}
      hoveredTargetHandle={legacyProps.hoveredTargetHandle}
      handleHandleClick={legacyProps.handleHandleClick}
      handleHandleMouseDown={legacyProps.handleHandleMouseDown}
      handleHandleMouseUp={legacyProps.handleHandleMouseUp}
      handleSetHandleRef={legacyProps.handleSetHandleRef}
    />
  );
}

export function AggregateFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);

  return (
    <AggregateParametersForm
      node={props.node}
      data={props.node.data}
      connections={legacyProps.connections}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      findUnknownVariables={props.findUnknownVariables}
      draggingFromHandle={legacyProps.draggingFromHandle}
      hoveredTargetHandle={legacyProps.hoveredTargetHandle}
      handleHandleClick={legacyProps.handleHandleClick}
      handleHandleMouseDown={legacyProps.handleHandleMouseDown}
      handleHandleMouseUp={legacyProps.handleHandleMouseUp}
      handleSetHandleRef={legacyProps.handleSetHandleRef}
    />
  );
}

export function DownloadFileFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);

  return (
    <DownloadFileParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      connectionProps={legacyProps}
      findUnknownVariables={props.findUnknownVariables}
    />
  );
}

export function PublicLinkFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);

  return (
    <PublicLinkParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      connectionProps={legacyProps}
      findUnknownVariables={props.findUnknownVariables}
    />
  );
}

export function MediaFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);

  return (
    <MediaParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      connectionProps={legacyProps}
      findUnknownVariables={props.findUnknownVariables}
    />
  );
}

export function HttpRequestFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);

  return (
    <HttpRequestParametersForm
      node={props.node}
      data={props.node.data}
      connections={legacyProps.connections}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      findUnknownVariables={props.findUnknownVariables}
      draggingFromHandle={legacyProps.draggingFromHandle}
      hoveredTargetHandle={legacyProps.hoveredTargetHandle}
      handleHandleClick={legacyProps.handleHandleClick}
      handleHandleMouseDown={legacyProps.handleHandleMouseDown}
      handleHandleMouseUp={legacyProps.handleHandleMouseUp}
      handleSetHandleRef={legacyProps.handleSetHandleRef}
    />
  );
}

export function ResponseFormAdapter(props: InspectorFormProps) {
  return (
    <ResponseParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
    />
  );
}

export function FilterFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);
  return (
    <FilterParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      connectionProps={legacyProps}
      findUnknownVariables={props.findUnknownVariables}
    />
  );
}

export function SortFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);
  return (
    <SortParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      connectionProps={legacyProps}
      findUnknownVariables={props.findUnknownVariables}
    />
  );
}

export function LimitFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);
  return (
    <LimitParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      connectionProps={legacyProps}
      findUnknownVariables={props.findUnknownVariables}
    />
  );
}

export function RemoveDuplicatesFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);
  return (
    <RemoveDuplicatesParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      connectionProps={legacyProps}
      findUnknownVariables={props.findUnknownVariables}
    />
  );
}

export function SummarizeFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);
  return (
    <SummarizeParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      connectionProps={legacyProps}
      findUnknownVariables={props.findUnknownVariables}
    />
  );
}

export function DateTimeFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);

  return (
    <DateTimeParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      connectionProps={legacyProps}
      findUnknownVariables={props.findUnknownVariables}
    />
  );
}

export function CryptoJwtFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);

  return (
    <CryptoJwtParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      connectionProps={legacyProps}
      findUnknownVariables={props.findUnknownVariables}
    />
  );
}

export function XmlFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);
  return (
    <XmlParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      connectionProps={legacyProps}
      findUnknownVariables={props.findUnknownVariables}
    />
  );
}

export function CompressionFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);
  return (
    <CompressionParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      connectionProps={legacyProps}
      findUnknownVariables={props.findUnknownVariables}
    />
  );
}

export function RssFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);
  return (
    <RssParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      connectionProps={legacyProps}
      findUnknownVariables={props.findUnknownVariables}
    />
  );
}

export function ConvertToFileFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);
  return (
    <ConvertToFileParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      connectionProps={legacyProps}
      findUnknownVariables={props.findUnknownVariables}
    />
  );
}

export function ExtractFromFileFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);
  return (
    <ExtractFromFileParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      connectionProps={legacyProps}
      findUnknownVariables={props.findUnknownVariables}
    />
  );
}

export function CompareDatasetsFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);
  return (
    <CompareDatasetsParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      connectionProps={legacyProps}
      findUnknownVariables={props.findUnknownVariables}
    />
  );
}

export function SetFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);
  return (
    <SetParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      connectionProps={legacyProps}
      findUnknownVariables={props.findUnknownVariables}
    />
  );
}

export function HtmlExtractFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);
  return (
    <HtmlExtractParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      connectionProps={legacyProps}
      findUnknownVariables={props.findUnknownVariables}
    />
  );
}

export function TaskFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);

  return (
    <TaskParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      connectionProps={legacyProps}
      findUnknownVariables={props.findUnknownVariables}
    />
  );
}

export function SubWorkflowFormAdapter(props: InspectorFormProps) {
  return (
    <SubWorkflowParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
    />
  );
}

export function RespondToWebhookFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);

  return (
    <RespondToWebhookParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      connectionProps={legacyProps}
      findUnknownVariables={props.findUnknownVariables}
    />
  );
}

export function SendEmailFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);

  return (
    <SendEmailParametersForm
      node={props.node}
      data={props.node.data}
      connections={legacyProps.connections}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      findUnknownVariables={props.findUnknownVariables}
      draggingFromHandle={legacyProps.draggingFromHandle}
      hoveredTargetHandle={legacyProps.hoveredTargetHandle}
      handleHandleClick={legacyProps.handleHandleClick}
      handleHandleMouseDown={legacyProps.handleHandleMouseDown}
      handleHandleMouseUp={legacyProps.handleHandleMouseUp}
      handleSetHandleRef={legacyProps.handleSetHandleRef}
    />
  );
}

export function EmailInboxFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);

  return (
    <EmailInboxParametersForm
      node={props.node}
      data={props.node.data}
      connections={legacyProps.connections}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      findUnknownVariables={props.findUnknownVariables}
      draggingFromHandle={legacyProps.draggingFromHandle}
      hoveredTargetHandle={legacyProps.hoveredTargetHandle}
      handleHandleClick={legacyProps.handleHandleClick}
      handleHandleMouseDown={legacyProps.handleHandleMouseDown}
      handleHandleMouseUp={legacyProps.handleHandleMouseUp}
      handleSetHandleRef={legacyProps.handleSetHandleRef}
    />
  );
}

export function CodeFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);

  return (
    <CodeParametersForm
      node={props.node}
      data={props.node.data}
      connections={legacyProps.connections}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      findUnknownVariables={props.findUnknownVariables}
      draggingFromHandle={legacyProps.draggingFromHandle}
      hoveredTargetHandle={legacyProps.hoveredTargetHandle}
      handleHandleClick={legacyProps.handleHandleClick}
      handleHandleMouseDown={legacyProps.handleHandleMouseDown}
      handleHandleMouseUp={legacyProps.handleHandleMouseUp}
      handleSetHandleRef={legacyProps.handleSetHandleRef}
    />
  );
}

export function StopOnErrorFormAdapter(props: InspectorFormProps) {
  return (
    <StopOnErrorParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
    />
  );
}

export function SshFormAdapter(props: InspectorFormProps) {
  return (
    <SshParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
    />
  );
}

export function SftpFormAdapter(props: InspectorFormProps) {
  return (
    <SftpParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
    />
  );
}

export function DatabaseFormAdapter(props: InspectorFormProps) {
  return (
    <DatabaseParametersForm
      node={props.node}
      data={props.node.data}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
    />
  );
}

export function DataInputFormAdapter(props: InspectorFormProps) {
  const legacyProps = unpackConnectionProps(props.connectionProps, props.node.id);

  return (
    <DataInputParametersForm
      node={props.node}
      data={props.node.data}
      connections={legacyProps.connections}
      isRunMode={props.isRunMode}
      onUpdate={(data) => props.onUpdate(data)}
      findUnknownVariables={props.findUnknownVariables}
      draggingFromHandle={legacyProps.draggingFromHandle}
      hoveredTargetHandle={legacyProps.hoveredTargetHandle}
      handleHandleClick={legacyProps.handleHandleClick}
      handleHandleMouseDown={legacyProps.handleHandleMouseDown}
      handleHandleMouseUp={legacyProps.handleHandleMouseUp}
      handleSetHandleRef={legacyProps.handleSetHandleRef}
    />
  );
}

// =============================================================================
// AGENT ADAPTERS
// =============================================================================

export function AgentFormAdapter(_props: InspectorFormProps) {
  // Agent forms are handled directly in ParameterColumn with proper props
  return null;
}

export function BrowserAgentFormAdapter(_props: InspectorFormProps) {
  // BrowserAgent forms are handled directly in ParameterColumn - see
  // BrowserAgentParametersForm. Adapter is a stub mirroring AgentFormAdapter.
  return null;
}

export function ClassifyFormAdapter(_props: InspectorFormProps) {
  // Classify forms are handled directly in ParameterColumn with proper props
  return null;
}

export function GuardrailFormAdapter(_props: InspectorFormProps) {
  // Guardrail forms are handled directly in ParameterColumn with proper props
  return null;
}

// =============================================================================
// OTHER ADAPTERS
// =============================================================================

export function NoteFormAdapter(props: InspectorFormProps) {
  return (
    <NoteParametersForm
      data={props.node.data}
      onUpdate={(data) => props.onUpdate(data)}
      isRunMode={props.isRunMode}
    />
  );
}

// =============================================================================
// TOOL ADAPTER (handled by McpToolSelector in parent)
// =============================================================================

export function ToolFormAdapter(_props: InspectorFormProps) {
  // Tool forms are handled by McpToolSelector which needs special MCP state
  // This adapter returns null - parent should render McpToolSelector directly
  return null;
}

// =============================================================================
// TABLES (CRUD) ADAPTERS - Handled by parent with datasource hooks
// =============================================================================

export function CreateRowFormAdapter(_props: InspectorFormProps) {
  // CRUD forms need datasource columns from hooks in parent
  return null;
}

export function ReadRowFormAdapter(_props: InspectorFormProps) {
  return null;
}

export function UpdateRowFormAdapter(_props: InspectorFormProps) {
  return null;
}

export function DeleteRowFormAdapter(_props: InspectorFormProps) {
  return null;
}

export function ListRowsFormAdapter(_props: InspectorFormProps) {
  return null;
}

export function FindRowFormAdapter(_props: InspectorFormProps) {
  return null;
}
