import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../types';
import { normalizeLabel, triggerKey } from './labelNormalizer';

/**
 * Find the live builder node that carries the form definition for a SPECIFIC
 * waiting form trigger.
 *
 * A workflow can host several form triggers. Every form-trigger node shares the
 * same "form-trigger-<suffix>" data.id prefix, and the suffix is a timestamp +
 * random that carries NO label (see TriggerNodeCreator.getDataIdForTrigger). So a
 * naive prefix match (`data.id.startsWith('form-trigger-')`) returns the FIRST
 * form node for EVERY waiting trigger, which makes two distinct forms render
 * identically in the run panel. We bind the node to the trigger by its normalized
 * label instead, the only per-trigger discriminator the node actually carries.
 *
 * @param nodes        Builder graph nodes.
 * @param triggerId    The waiting trigger's normalized key (e.g. "trigger:mcall_setup").
 * @param triggerLabel The waiting trigger's raw label (e.g. "mcall setup").
 * @returns The matching node, or undefined when none carries a form for this trigger.
 */
export function findLiveFormTriggerNode(
  nodes: Node<BuilderNodeData>[],
  triggerId: string,
  triggerLabel: string | null | undefined,
): Node<BuilderNodeData> | undefined {
  const wantedLabel = normalizeLabel(triggerLabel || '');
  return nodes.find((node) => {
    const data = node.data as any;
    if (!data?.formTriggerData) return false;
    const nodeTriggerKey = triggerKey(data.label);
    if (nodeTriggerKey && nodeTriggerKey === triggerId) return true;
    const nodeLabel = normalizeLabel(data.label);
    return nodeLabel != null && nodeLabel === wantedLabel;
  });
}

/**
 * The field list a waiting form trigger is rendered with: the FIRST NON-EMPTY of the
 * live node's fields (unsaved builder edits), the backend trigger config's fields and
 * the plan's params fields.
 *
 * Non-empty, not merely defined: the config list defaults to [], which is truthy, so a
 * plain `||` chain never reached the plan's fields. The form then arrived with no
 * fields until the live node data loaded, and a template seed applied in that window
 * filled nothing (the user had to ask for the example values twice).
 */
export function pickFormTriggerFields(
  ...candidates: Array<unknown[] | null | undefined>
): any[] {
  return (candidates.find(f => Array.isArray(f) && f.length > 0) as any[] | undefined) ?? [];
}
