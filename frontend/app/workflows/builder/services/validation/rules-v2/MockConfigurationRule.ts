/**
 * MockConfigurationRule - Validates per-node mock blocks (`data.mock`)
 *
 * Warnings only (isCritical = false) - a misconfigured mock never blocks the
 * workflow, the backend simply rejects/ignores it at parse time. Mirrored
 * backend rules:
 *  - exactly ONE mock source: static output | catalog_example | error
 *    (a bare `port` is allowed on port-selecting nodes),
 *  - catalog_example only on mcp catalog tool nodes,
 *  - port only on decision/switch/option/approval cores + classify agents,
 *    and only a port that actually exists on the node,
 *  - a static mock on a port-selecting node MUST select a branch (port),
 *  - output must be a plain JSON object,
 *  - no mock at all on triggers, notes and split/merge/aggregate/loop/fork.
 */

import type { BuilderNodeData } from '../../../types';
import type { ValidationContext, ValidationIssue } from '../core/types';
import { BaseValidationRule } from './BaseValidationRule';
import { normalizeLabel } from '../../../utils/labelNormalizer';
import { getNodeType } from '../core/nodeUtils';
import {
  isMcpCatalogToolNode,
  isPortSelectingNode,
  nodePortOptions,
  nodeSupportsMock,
} from '../../../utils/nodeMock';

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

export class MockConfigurationRule extends BaseValidationRule {
  readonly ruleName = 'MockConfiguration' as const;
  readonly isCritical = false;
  readonly priority = 8;

  validate(context: ValidationContext): ReturnType<typeof this.buildResult> {
    const issues: ValidationIssue[] = [];
    const { nodes } = context;

    for (const node of nodes) {
      const rawMock = (node.data as BuilderNodeData | undefined)?.mock as unknown;
      if (rawMock === undefined || rawMock === null) continue;

      const nodeType = getNodeType(node);
      const label = node.data?.label;
      const norm = label ? normalizeLabel(label) : null;
      const elementKey = norm ? `${nodeType}:${norm}` : `${nodeType}:${node.id}`;

      // (e) Mock on an unsupported node type - it will be ignored/rejected.
      if (!nodeSupportsMock(node)) {
        issues.push(
          this.createWarning(
            elementKey,
            nodeType,
            'Mock is not supported on this node type (triggers, notes, split, merge, aggregate, loop and fork cannot be mocked) - it will be ignored',
            { rule: 'mock_unsupported_node', nodeId: node.id }
          )
        );
        continue;
      }

      if (!isPlainObject(rawMock)) {
        issues.push(
          this.createWarning(
            elementKey,
            nodeType,
            'Mock must be an object - it will be ignored',
            { rule: 'mock_invalid_shape', nodeId: node.id }
          )
        );
        continue;
      }

      const mock = rawMock as Record<string, unknown>;
      const outputPresent = mock.output !== undefined;
      const outputValid = isPlainObject(mock.output);
      const hasCatalogExample = mock.source === 'catalog_example';
      const error = mock.error;
      const hasValidError =
        isPlainObject(error) &&
        typeof (error as Record<string, unknown>).message === 'string' &&
        ((error as Record<string, unknown>).message as string).trim() !== '';
      const port = mock.port;
      const hasPort = typeof port === 'string' && port.trim() !== '';
      const portAllowedHere = isPortSelectingNode(node);

      // (d) output present but not a plain object.
      if (outputPresent && !outputValid) {
        issues.push(
          this.createWarning(
            elementKey,
            nodeType,
            'Mock output must be a JSON object',
            { rule: 'mock_output_not_object', nodeId: node.id }
          )
        );
      }

      // (a) exactly one source required (bare port allowed on port-selecting nodes).
      const sourceCount =
        (outputValid ? 1 : 0) + (hasCatalogExample ? 1 : 0) + (hasValidError ? 1 : 0);
      const bareValidPort = sourceCount === 0 && hasPort && portAllowedHere;
      if (sourceCount !== 1 && !bareValidPort) {
        issues.push(
          this.createWarning(
            elementKey,
            nodeType,
            'Mock must define exactly one source: a static output, the catalog example, or a simulated error (a bare branch is allowed on branching nodes)',
            { rule: 'mock_source_count', nodeId: node.id }
          )
        );
      }

      // Error mocks cannot pick a branch.
      if (hasValidError && hasPort) {
        issues.push(
          this.createWarning(
            elementKey,
            nodeType,
            'A simulated error mock cannot select a branch - remove the port',
            { rule: 'mock_error_with_port', nodeId: node.id }
          )
        );
      }

      // (b) catalog_example only on mcp catalog tool nodes.
      if (hasCatalogExample && !isMcpCatalogToolNode(node)) {
        issues.push(
          this.createWarning(
            elementKey,
            nodeType,
            'Catalog example mocks are only available on API tool nodes',
            { rule: 'mock_catalog_example_non_mcp', nodeId: node.id }
          )
        );
      }

      // (f) a static mock on a port-selecting node must select a branch -
      // the backend rejects it at parse time, failing every editor run.
      if (outputValid && !hasCatalogExample && !hasValidError && !hasPort && portAllowedHere) {
        issues.push(
          this.createWarning(
            elementKey,
            nodeType,
            'A static mock on a branching node must select the branch to take - pick a port',
            { rule: 'mock_port_required', nodeId: node.id }
          )
        );
      }

      // (c) port only on port-selecting nodes, and only an existing port.
      if (hasPort) {
        if (!portAllowedHere) {
          issues.push(
            this.createWarning(
              elementKey,
              nodeType,
              'Mock port is only available on decision, switch, option, approval and classify nodes',
              { rule: 'mock_port_unsupported_node', nodeId: node.id }
            )
          );
        } else if (!nodePortOptions(node).includes(port as string)) {
          issues.push(
            this.createWarning(
              elementKey,
              nodeType,
              `Mock port "${port}" does not exist on this node`,
              { rule: 'mock_port_unknown', nodeId: node.id }
            )
          );
        }
      }
    }

    return this.buildResult(issues);
  }
}
