'use client';

import { BuilderEdge } from '../components/BuilderEdge';
import { BrowserAgentNode } from '../components/nodes/BrowserAgentNode';
import { ClassifyNode } from '../components/nodes/ClassifyNode';
import { DecisionNode } from '../components/nodes/DecisionNode';
import { FlowNode } from '../components/nodes/FlowNode';
import { GuardrailNode } from '../components/nodes/GuardrailNode';
import { SplitNode } from '../components/nodes/SplitNode';
import { AggregateNode } from '../components/nodes/AggregateNode';
import { ExitNode } from '../components/nodes/ExitNode';
import { ResponseNode } from '../components/nodes/ResponseNode';
import { OptionNode } from '../components/nodes/OptionNode';
import { MergeNode } from '../components/nodes/MergeNode';
import { ForkNode } from '../components/nodes/ForkNode';
import { InterfacePreviewNode } from '../components/nodes/InterfacePreviewNode';
import { NoteNode } from '../components/nodes/NoteNode';
import { SwitchNode } from '../components/nodes/SwitchNode';
import { WhileGroupNode } from '../components/nodes/WhileGroupNode';
import { UserApprovalNode } from '../components/nodes/UserApprovalNode';
import { ResourceChipNode } from '../components/nodes/ResourceChipNode';

/**
 * Shared node types for ReactFlow canvases (BuilderCanvas and PreviewCanvas).
 */
export const nodeTypes = {
  flowNode: FlowNode,
  decisionNode: DecisionNode,
  switchNode: SwitchNode,
  splitNode: SplitNode,
  aggregateNode: AggregateNode,
  exitNode: ExitNode,
  responseNode: ResponseNode,
  optionNode: OptionNode,
  userApprovalNode: UserApprovalNode,
  mergeNode: MergeNode,
  forkNode: ForkNode,
  noteNode: NoteNode,
  classifyNode: ClassifyNode,
  guardrailNode: GuardrailNode,
  browserAgentNode: BrowserAgentNode,
  interfaceNode: InterfacePreviewNode,
  whileGroupNode: WhileGroupNode,
  resourceChipNode: ResourceChipNode,
};

/**
 * Shared edge types for ReactFlow canvases.
 */
export const edgeTypes = {
  builderEdge: BuilderEdge,
};
