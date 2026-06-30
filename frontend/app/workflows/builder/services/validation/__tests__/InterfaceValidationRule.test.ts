import { describe, it, expect, beforeEach } from 'vitest';
import { InterfaceValidationRule } from '../rules-v2/InterfaceValidationRule';
import {
  makeInterfaceNode,
  buildContext,
  resetNodeCounter,
  resetEdgeCounter,
} from './test-helpers';

describe('InterfaceValidationRule', () => {
  let rule: InterfaceValidationRule;

  beforeEach(() => {
    rule = new InterfaceValidationRule();
    resetNodeCounter();
    resetEdgeCounter();
  });

  it('should have correct metadata', () => {
    expect(rule.ruleName).toBe('InterfaceValidation');
    expect(rule.priority).toBe(9);
  });

  // ===================== #20: Missing interface ID and HTML =====================

  describe('#20 - Interface without ID', () => {
    it('should error when interface has no ID', () => {
      const iface = makeInterfaceNode('My Page', { interfaceName: 'Test' });
      const ctx = buildContext([iface], []);
      const result = rule.validate(ctx);

      const idIssues = result.issues.filter((i) => i.context?.rule === 'interface_missing_id');
      expect(idIssues).toHaveLength(1);
      expect(idIssues[0].severity).toBe('error');
      expect(idIssues[0].elementType).toBe('interface');
      expect(idIssues[0].elementKey).toContain('interface:');
    });

    it('should pass when interface has valid ID', () => {
      const iface = makeInterfaceNode('My Page', {
        interfaceId: 'iface-123',
        interfaceName: 'Test',
      });
      const ctx = buildContext([iface], []);
      const result = rule.validate(ctx);

      const idIssues = result.issues.filter((i) => i.context?.rule === 'interface_missing_id');
      expect(idIssues).toHaveLength(0);
    });
  });

  // ===================== #21: Unmapped variables =====================

  describe('#21 - Unmapped template variables', () => {
    it('should warn when template variables are not mapped', () => {
      const iface = makeInterfaceNode('My Page', {
        interfaceId: 'iface-1',
        templateVariables: ['title', 'description', 'price'],
        variableMapping: { title: '{{trigger:start.output.title}}' },
      });
      const ctx = buildContext([iface], []);
      const result = rule.validate(ctx);

      const unmappedIssues = result.issues.filter((i) => i.context?.rule === 'interface_unmapped_variables');
      expect(unmappedIssues).toHaveLength(1);
      expect(unmappedIssues[0].severity).toBe('warning');
      expect(unmappedIssues[0].message).toContain('2 unmapped');
      expect(unmappedIssues[0].message).toContain('description');
      expect(unmappedIssues[0].message).toContain('price');
    });

    it('should pass when all variables are mapped', () => {
      const iface = makeInterfaceNode('My Page', {
        interfaceId: 'iface-1',
        templateVariables: ['title'],
        variableMapping: { title: '{{trigger:start.output.title}}' },
      });
      const ctx = buildContext([iface], []);
      const result = rule.validate(ctx);

      const unmappedIssues = result.issues.filter((i) => i.context?.rule === 'interface_unmapped_variables');
      expect(unmappedIssues).toHaveLength(0);
    });

    it('should pass when no template variables exist', () => {
      const iface = makeInterfaceNode('My Page', {
        interfaceId: 'iface-1',
      });
      const ctx = buildContext([iface], []);
      const result = rule.validate(ctx);

      const unmappedIssues = result.issues.filter((i) => i.context?.rule === 'interface_unmapped_variables');
      expect(unmappedIssues).toHaveLength(0);
    });
  });

  // ===================== shouldRun =====================

  describe('shouldRun', () => {
    it('should not run when no interface nodes exist', () => {
      const ctx = buildContext([], []);
      expect(rule.shouldRun!(ctx)).toBe(false);
    });

    it('should run when interface nodes exist', () => {
      const iface = makeInterfaceNode('Page');
      const ctx = buildContext([iface], []);
      expect(rule.shouldRun!(ctx)).toBe(true);
    });
  });
});
