import { describe, it, expect } from 'vitest';
import {
  parseEdgeRef,
  buildEdgeRef,
  hasPort,
  getNodeKey,
  getPort,
  isNodeType,
  isDecisionBranch,
  isSwitchCase,
  isLoopPort,
  isForkBranch,
  isClassifyCategory,
  parseForkPort,
  parseDecisionPort,
  parseSwitchPort,
  parseClassifyPort,
  buildForkPort,
  buildDecisionPort,
  buildSwitchPort,
  buildClassifyPort,
} from '../edgeRefParser';

// ============================================================================
// parseEdgeRef
// ============================================================================
describe('parseEdgeRef', () => {
  describe('simple node types (no ports)', () => {
    it('should parse trigger ref', () => {
      const result = parseEdgeRef('trigger:start');
      expect(result).toEqual({
        nodeType: 'trigger',
        nodeLabel: 'start',
        port: undefined,
      });
    });

    it('should parse mcp ref', () => {
      const result = parseEdgeRef('mcp:fetch_data');
      expect(result).toEqual({
        nodeType: 'mcp',
        nodeLabel: 'fetch_data',
        port: undefined,
      });
    });

    it('should parse table ref', () => {
      const result = parseEdgeRef('table:users');
      expect(result).toEqual({
        nodeType: 'table',
        nodeLabel: 'users',
        port: undefined,
      });
    });

    it('should parse note ref', () => {
      const result = parseEdgeRef('note:my_note');
      expect(result).toEqual({
        nodeType: 'note',
        nodeLabel: 'my_note',
        port: undefined,
      });
    });

    it('should parse interface ref', () => {
      const result = parseEdgeRef('interface:user_form');
      expect(result).toEqual({
        nodeType: 'interface',
        nodeLabel: 'user_form',
        port: undefined,
      });
    });
  });

  describe('core refs without ports', () => {
    it('should parse core ref without port', () => {
      const result = parseEdgeRef('core:my_split');
      expect(result).toEqual({
        nodeType: 'core',
        nodeLabel: 'my_split',
        port: undefined,
      });
    });
  });

  describe('core refs with decision ports', () => {
    it('should parse core:label:if', () => {
      const result = parseEdgeRef('core:check:if');
      expect(result).toEqual({
        nodeType: 'core',
        nodeLabel: 'check',
        port: 'if',
      });
    });

    it('should parse core:label:else', () => {
      const result = parseEdgeRef('core:check:else');
      expect(result).toEqual({
        nodeType: 'core',
        nodeLabel: 'check',
        port: 'else',
      });
    });

    it('should parse core:label:elseif_0', () => {
      const result = parseEdgeRef('core:check:elseif_0');
      expect(result).toEqual({
        nodeType: 'core',
        nodeLabel: 'check',
        port: 'elseif_0',
      });
    });

    it('should parse core:label:elseif_5', () => {
      const result = parseEdgeRef('core:decision:elseif_5');
      expect(result).toEqual({
        nodeType: 'core',
        nodeLabel: 'decision',
        port: 'elseif_5',
      });
    });
  });

  describe('core refs with switch ports', () => {
    it('should parse core:label:case_0', () => {
      const result = parseEdgeRef('core:my_switch:case_0');
      expect(result).toEqual({
        nodeType: 'core',
        nodeLabel: 'my_switch',
        port: 'case_0',
      });
    });

    it('should parse core:label:case_3', () => {
      const result = parseEdgeRef('core:my_switch:case_3');
      expect(result).toEqual({
        nodeType: 'core',
        nodeLabel: 'my_switch',
        port: 'case_3',
      });
    });

    it('should parse core:label:default', () => {
      const result = parseEdgeRef('core:my_switch:default');
      expect(result).toEqual({
        nodeType: 'core',
        nodeLabel: 'my_switch',
        port: 'default',
      });
    });
  });

  describe('core refs with loop ports', () => {
    it('should parse core:label:body', () => {
      const result = parseEdgeRef('core:my_loop:body');
      expect(result).toEqual({
        nodeType: 'core',
        nodeLabel: 'my_loop',
        port: 'body',
      });
    });

    it('should parse core:label:iterate', () => {
      const result = parseEdgeRef('core:my_loop:iterate');
      expect(result).toEqual({
        nodeType: 'core',
        nodeLabel: 'my_loop',
        port: 'iterate',
      });
    });

    it('should parse core:label:exit', () => {
      const result = parseEdgeRef('core:my_loop:exit');
      expect(result).toEqual({
        nodeType: 'core',
        nodeLabel: 'my_loop',
        port: 'exit',
      });
    });
  });

  describe('core refs with fork ports', () => {
    it('should parse core:label:branch_0', () => {
      const result = parseEdgeRef('core:parallel:branch_0');
      expect(result).toEqual({
        nodeType: 'core',
        nodeLabel: 'parallel',
        port: 'branch_0',
      });
    });

    it('should parse core:label:branch_1', () => {
      const result = parseEdgeRef('core:parallel:branch_1');
      expect(result).toEqual({
        nodeType: 'core',
        nodeLabel: 'parallel',
        port: 'branch_1',
      });
    });
  });

  describe('agent refs with classify ports', () => {
    it('should parse agent:label:category_0', () => {
      const result = parseEdgeRef('agent:classifier:category_0');
      expect(result).toEqual({
        nodeType: 'agent',
        nodeLabel: 'classifier',
        port: 'category_0',
      });
    });

    it('should parse agent:label:category_3', () => {
      const result = parseEdgeRef('agent:my_agent:category_3');
      expect(result).toEqual({
        nodeType: 'agent',
        nodeLabel: 'my_agent',
        port: 'category_3',
      });
    });

    it('should parse agent ref without port', () => {
      const result = parseEdgeRef('agent:my_agent');
      expect(result).toEqual({
        nodeType: 'agent',
        nodeLabel: 'my_agent',
        port: undefined,
      });
    });
  });

  describe('labels with colons (non-port node types)', () => {
    it('should include extra colon parts in label for mcp refs', () => {
      const result = parseEdgeRef('mcp:step:extra');
      expect(result).toEqual({
        nodeType: 'mcp',
        nodeLabel: 'step:extra',
        port: undefined,
      });
    });

    it('should include extra colon parts in label for trigger refs', () => {
      const result = parseEdgeRef('trigger:my:label');
      expect(result).toEqual({
        nodeType: 'trigger',
        nodeLabel: 'my:label',
        port: undefined,
      });
    });
  });

  describe('error cases', () => {
    it('should throw for empty string', () => {
      expect(() => parseEdgeRef('')).toThrow('Invalid edge ref');
    });

    it('should throw for null-like value', () => {
      expect(() => parseEdgeRef(null as unknown as string)).toThrow(
        'Invalid edge ref'
      );
    });

    it('should throw for undefined-like value', () => {
      expect(() => parseEdgeRef(undefined as unknown as string)).toThrow(
        'Invalid edge ref'
      );
    });

    it('should throw for string without colon', () => {
      expect(() => parseEdgeRef('nocolon')).toThrow(
        'expected at least 2 parts'
      );
    });

    it('should throw for invalid node type', () => {
      expect(() => parseEdgeRef('invalid:label')).toThrow(
        'Invalid node type: invalid'
      );
    });

    it('should throw for unknown prefix', () => {
      expect(() => parseEdgeRef('foo:bar')).toThrow('Invalid node type: foo');
    });
  });
});

// ============================================================================
// buildEdgeRef
// ============================================================================
describe('buildEdgeRef', () => {
  it('should build a simple trigger ref', () => {
    expect(buildEdgeRef('trigger', 'start')).toBe('trigger:start');
  });

  it('should build a simple mcp ref', () => {
    expect(buildEdgeRef('mcp', 'fetch_data')).toBe('mcp:fetch_data');
  });

  it('should build a core ref without port', () => {
    expect(buildEdgeRef('core', 'my_split')).toBe('core:my_split');
  });

  it('should build a core ref with decision port', () => {
    expect(buildEdgeRef('core', 'check', 'if')).toBe('core:check:if');
  });

  it('should build a core ref with else port', () => {
    expect(buildEdgeRef('core', 'check', 'else')).toBe('core:check:else');
  });

  it('should build a core ref with loop port', () => {
    expect(buildEdgeRef('core', 'my_loop', 'body')).toBe(
      'core:my_loop:body'
    );
  });

  it('should build a core ref with fork port', () => {
    expect(buildEdgeRef('core', 'parallel', 'branch_0')).toBe(
      'core:parallel:branch_0'
    );
  });

  it('should build an agent ref with classify port', () => {
    expect(buildEdgeRef('agent', 'classifier', 'category_0')).toBe(
      'agent:classifier:category_0'
    );
  });

  it('should build a table ref', () => {
    expect(buildEdgeRef('table', 'users')).toBe('table:users');
  });

  it('should build a note ref', () => {
    expect(buildEdgeRef('note', 'readme')).toBe('note:readme');
  });

  it('should build an interface ref', () => {
    expect(buildEdgeRef('interface', 'form')).toBe('interface:form');
  });

  describe('error cases', () => {
    it('should throw for invalid node type', () => {
      expect(() => buildEdgeRef('invalid' as any, 'label')).toThrow(
        'Invalid node type'
      );
    });

    it('should throw for empty label', () => {
      expect(() => buildEdgeRef('mcp', '')).toThrow('Node label is required');
    });

    it('should throw when assigning port to non-port node type', () => {
      expect(() => buildEdgeRef('mcp', 'step', 'if')).toThrow(
        'does not support ports'
      );
    });

    it('should throw when assigning port to trigger', () => {
      expect(() => buildEdgeRef('trigger', 'start', 'if')).toThrow(
        'does not support ports'
      );
    });

    it('should throw when assigning port to table', () => {
      expect(() => buildEdgeRef('table', 'users', 'branch_0')).toThrow(
        'does not support ports'
      );
    });

    it('should throw when assigning port to note', () => {
      expect(() => buildEdgeRef('note', 'readme', 'if')).toThrow(
        'does not support ports'
      );
    });

    it('should throw when assigning port to interface', () => {
      expect(() => buildEdgeRef('interface', 'form', 'body')).toThrow(
        'does not support ports'
      );
    });
  });
});

// ============================================================================
// hasPort
// ============================================================================
describe('hasPort', () => {
  it('should return true for core ref with port', () => {
    expect(hasPort('core:check:if')).toBe(true);
  });

  it('should return true for agent ref with port', () => {
    expect(hasPort('agent:classifier:category_0')).toBe(true);
  });

  it('should return false for core ref without port', () => {
    expect(hasPort('core:my_split')).toBe(false);
  });

  it('should return false for mcp ref', () => {
    expect(hasPort('mcp:fetch')).toBe(false);
  });

  it('should return false for trigger ref', () => {
    expect(hasPort('trigger:start')).toBe(false);
  });
});

// ============================================================================
// getNodeKey
// ============================================================================
describe('getNodeKey', () => {
  it('should return node key without port for decision', () => {
    expect(getNodeKey('core:check:if')).toBe('core:check');
  });

  it('should return node key without port for fork', () => {
    expect(getNodeKey('core:parallel:branch_0')).toBe('core:parallel');
  });

  it('should return node key without port for loop', () => {
    expect(getNodeKey('core:my_loop:body')).toBe('core:my_loop');
  });

  it('should return same key for mcp ref (no port)', () => {
    expect(getNodeKey('mcp:fetch')).toBe('mcp:fetch');
  });

  it('should return same key for trigger ref', () => {
    expect(getNodeKey('trigger:start')).toBe('trigger:start');
  });

  it('should return node key for agent with port', () => {
    expect(getNodeKey('agent:classifier:category_0')).toBe(
      'agent:classifier'
    );
  });

  it('should return same key for agent without port', () => {
    expect(getNodeKey('agent:my_agent')).toBe('agent:my_agent');
  });
});

// ============================================================================
// getPort
// ============================================================================
describe('getPort', () => {
  it('should return the port for decision if', () => {
    expect(getPort('core:check:if')).toBe('if');
  });

  it('should return the port for decision else', () => {
    expect(getPort('core:check:else')).toBe('else');
  });

  it('should return the port for loop body', () => {
    expect(getPort('core:my_loop:body')).toBe('body');
  });

  it('should return the port for fork branch', () => {
    expect(getPort('core:parallel:branch_0')).toBe('branch_0');
  });

  it('should return the port for switch case', () => {
    expect(getPort('core:my_switch:case_1')).toBe('case_1');
  });

  it('should return undefined for refs without port', () => {
    expect(getPort('mcp:fetch')).toBeUndefined();
  });

  it('should return undefined for core ref without port', () => {
    expect(getPort('core:my_split')).toBeUndefined();
  });

  it('should return the port for classify category', () => {
    expect(getPort('agent:classifier:category_2')).toBe('category_2');
  });
});

// ============================================================================
// isNodeType
// ============================================================================
describe('isNodeType', () => {
  it('should return true when matching trigger type', () => {
    expect(isNodeType('trigger:start', 'trigger')).toBe(true);
  });

  it('should return true when matching mcp type', () => {
    expect(isNodeType('mcp:fetch', 'mcp')).toBe(true);
  });

  it('should return true when matching core type', () => {
    expect(isNodeType('core:loop:body', 'core')).toBe(true);
  });

  it('should return true when matching agent type', () => {
    expect(isNodeType('agent:analyzer', 'agent')).toBe(true);
  });

  it('should return false when types do not match', () => {
    expect(isNodeType('mcp:fetch', 'core')).toBe(false);
  });

  it('should return false when types do not match (trigger vs mcp)', () => {
    expect(isNodeType('trigger:start', 'mcp')).toBe(false);
  });
});

// ============================================================================
// isDecisionBranch
// ============================================================================
describe('isDecisionBranch', () => {
  it('should return true for :if port', () => {
    expect(isDecisionBranch('core:check:if')).toBe(true);
  });

  it('should return true for :else port', () => {
    expect(isDecisionBranch('core:check:else')).toBe(true);
  });

  it('should return true for :elseif_0 port', () => {
    expect(isDecisionBranch('core:check:elseif_0')).toBe(true);
  });

  it('should return true for :elseif_3 port', () => {
    expect(isDecisionBranch('core:check:elseif_3')).toBe(true);
  });

  it('should return false for core ref without port', () => {
    expect(isDecisionBranch('core:my_split')).toBe(false);
  });

  it('should return false for loop port', () => {
    expect(isDecisionBranch('core:my_loop:body')).toBe(false);
  });

  it('should return false for mcp ref', () => {
    expect(isDecisionBranch('mcp:fetch')).toBe(false);
  });

  it('should return false for fork port', () => {
    expect(isDecisionBranch('core:parallel:branch_0')).toBe(false);
  });

  it('should return false for agent ref', () => {
    expect(isDecisionBranch('agent:analyzer:category_0')).toBe(false);
  });
});

// ============================================================================
// isSwitchCase
// ============================================================================
describe('isSwitchCase', () => {
  it('should return true for :case_0 port', () => {
    expect(isSwitchCase('core:my_switch:case_0')).toBe(true);
  });

  it('should return true for :case_5 port', () => {
    expect(isSwitchCase('core:my_switch:case_5')).toBe(true);
  });

  it('should return true for :default port', () => {
    expect(isSwitchCase('core:my_switch:default')).toBe(true);
  });

  it('should return false for core ref without port', () => {
    expect(isSwitchCase('core:my_split')).toBe(false);
  });

  it('should return false for decision port', () => {
    expect(isSwitchCase('core:check:if')).toBe(false);
  });

  it('should return false for mcp ref', () => {
    expect(isSwitchCase('mcp:fetch')).toBe(false);
  });

  it('should return false for agent ref', () => {
    expect(isSwitchCase('agent:my_agent:category_0')).toBe(false);
  });
});

// ============================================================================
// isLoopPort
// ============================================================================
describe('isLoopPort', () => {
  it('should return true for :body port', () => {
    expect(isLoopPort('core:my_loop:body')).toBe(true);
  });

  it('should return true for :iterate port', () => {
    expect(isLoopPort('core:my_loop:iterate')).toBe(true);
  });

  it('should return true for :exit port', () => {
    expect(isLoopPort('core:my_loop:exit')).toBe(true);
  });

  it('should return false for core ref without port', () => {
    expect(isLoopPort('core:my_split')).toBe(false);
  });

  it('should return false for decision port', () => {
    expect(isLoopPort('core:check:if')).toBe(false);
  });

  it('should return false for mcp ref', () => {
    expect(isLoopPort('mcp:fetch')).toBe(false);
  });

  it('should return false for fork port', () => {
    expect(isLoopPort('core:parallel:branch_0')).toBe(false);
  });
});

// ============================================================================
// isForkBranch
// ============================================================================
describe('isForkBranch', () => {
  it('should return true for :branch_0 port', () => {
    expect(isForkBranch('core:parallel:branch_0')).toBe(true);
  });

  it('should return true for :branch_1 port', () => {
    expect(isForkBranch('core:parallel:branch_1')).toBe(true);
  });

  it('should return true for :branch_10 port', () => {
    expect(isForkBranch('core:parallel:branch_10')).toBe(true);
  });

  it('should return false for core ref without port', () => {
    expect(isForkBranch('core:my_split')).toBe(false);
  });

  it('should return false for loop port', () => {
    expect(isForkBranch('core:my_loop:body')).toBe(false);
  });

  it('should return false for mcp ref', () => {
    expect(isForkBranch('mcp:fetch')).toBe(false);
  });

  it('should return false for decision port', () => {
    expect(isForkBranch('core:check:if')).toBe(false);
  });
});

// ============================================================================
// isClassifyCategory
// ============================================================================
describe('isClassifyCategory', () => {
  it('should return true for :category_0 port on agent', () => {
    expect(isClassifyCategory('agent:classifier:category_0')).toBe(true);
  });

  it('should return true for :category_5 port on agent', () => {
    expect(isClassifyCategory('agent:classifier:category_5')).toBe(true);
  });

  it('should return false for agent ref without port', () => {
    expect(isClassifyCategory('agent:my_agent')).toBe(false);
  });

  it('should return false for core ref with category-like port', () => {
    // core nodes cannot have classify ports
    expect(isClassifyCategory('core:check:category_0')).toBe(false);
  });

  it('should return false for mcp ref', () => {
    expect(isClassifyCategory('mcp:fetch')).toBe(false);
  });
});

// ============================================================================
// parseForkPort
// ============================================================================
describe('parseForkPort', () => {
  it('should parse branch_0', () => {
    expect(parseForkPort('branch_0')).toEqual({ type: 'branch', index: 0 });
  });

  it('should parse branch_1', () => {
    expect(parseForkPort('branch_1')).toEqual({ type: 'branch', index: 1 });
  });

  it('should parse branch_99', () => {
    expect(parseForkPort('branch_99')).toEqual({ type: 'branch', index: 99 });
  });

  it('should return null for non-branch port', () => {
    expect(parseForkPort('if')).toBeNull();
  });

  it('should return null for empty string', () => {
    expect(parseForkPort('')).toBeNull();
  });

  it('should return null for branch without number', () => {
    expect(parseForkPort('branch_')).toBeNull();
  });

  it('should return null for malformed branch port', () => {
    expect(parseForkPort('branch_abc')).toBeNull();
  });
});

// ============================================================================
// parseDecisionPort
// ============================================================================
describe('parseDecisionPort', () => {
  it('should parse if port', () => {
    expect(parseDecisionPort('if')).toEqual({ type: 'if' });
  });

  it('should parse else port', () => {
    expect(parseDecisionPort('else')).toEqual({ type: 'else' });
  });

  it('should parse elseif_0 port', () => {
    expect(parseDecisionPort('elseif_0')).toEqual({
      type: 'elseif',
      index: 0,
    });
  });

  it('should parse elseif_3 port', () => {
    expect(parseDecisionPort('elseif_3')).toEqual({
      type: 'elseif',
      index: 3,
    });
  });

  it('should return null for unknown port', () => {
    expect(parseDecisionPort('body')).toBeNull();
  });

  it('should return null for empty string', () => {
    expect(parseDecisionPort('')).toBeNull();
  });

  it('should return null for elseif without number', () => {
    expect(parseDecisionPort('elseif_')).toBeNull();
  });

  it('should return null for malformed elseif', () => {
    expect(parseDecisionPort('elseif_abc')).toBeNull();
  });
});

// ============================================================================
// parseSwitchPort
// ============================================================================
describe('parseSwitchPort', () => {
  it('should parse default port', () => {
    expect(parseSwitchPort('default')).toEqual({ type: 'default' });
  });

  it('should parse case_0 port', () => {
    expect(parseSwitchPort('case_0')).toEqual({ type: 'case', index: 0 });
  });

  it('should parse case_5 port', () => {
    expect(parseSwitchPort('case_5')).toEqual({ type: 'case', index: 5 });
  });

  it('should return null for unknown port', () => {
    expect(parseSwitchPort('if')).toBeNull();
  });

  it('should return null for empty string', () => {
    expect(parseSwitchPort('')).toBeNull();
  });

  it('should return null for case without number', () => {
    expect(parseSwitchPort('case_')).toBeNull();
  });

  it('should return null for malformed case', () => {
    expect(parseSwitchPort('case_abc')).toBeNull();
  });
});

// ============================================================================
// parseClassifyPort
// ============================================================================
describe('parseClassifyPort', () => {
  it('should parse category_0', () => {
    expect(parseClassifyPort('category_0')).toEqual({
      type: 'category',
      index: 0,
    });
  });

  it('should parse category_7', () => {
    expect(parseClassifyPort('category_7')).toEqual({
      type: 'category',
      index: 7,
    });
  });

  it('should return null for non-category port', () => {
    expect(parseClassifyPort('if')).toBeNull();
  });

  it('should return null for empty string', () => {
    expect(parseClassifyPort('')).toBeNull();
  });

  it('should return null for category without number', () => {
    expect(parseClassifyPort('category_')).toBeNull();
  });

  it('should return null for malformed category', () => {
    expect(parseClassifyPort('category_abc')).toBeNull();
  });
});

// ============================================================================
// Port builder functions
// ============================================================================
describe('buildForkPort', () => {
  it('should build branch_0', () => {
    expect(buildForkPort(0)).toBe('branch_0');
  });

  it('should build branch_5', () => {
    expect(buildForkPort(5)).toBe('branch_5');
  });

  it('should build branch_99', () => {
    expect(buildForkPort(99)).toBe('branch_99');
  });
});

describe('buildDecisionPort', () => {
  it('should build if port', () => {
    expect(buildDecisionPort('if')).toBe('if');
  });

  it('should build else port', () => {
    expect(buildDecisionPort('else')).toBe('else');
  });

  it('should build elseif_0 port', () => {
    expect(buildDecisionPort('elseif', 0)).toBe('elseif_0');
  });

  it('should build elseif_3 port', () => {
    expect(buildDecisionPort('elseif', 3)).toBe('elseif_3');
  });

  it('should return "elseif" when index is undefined for elseif type', () => {
    expect(buildDecisionPort('elseif')).toBe('elseif');
  });
});

describe('buildSwitchPort', () => {
  it('should build case_0 port', () => {
    expect(buildSwitchPort('case', 0)).toBe('case_0');
  });

  it('should build case_5 port', () => {
    expect(buildSwitchPort('case', 5)).toBe('case_5');
  });

  it('should build default port', () => {
    expect(buildSwitchPort('default')).toBe('default');
  });

  it('should return default when case type has no index', () => {
    expect(buildSwitchPort('case')).toBe('default');
  });
});

describe('buildClassifyPort', () => {
  it('should build category_0', () => {
    expect(buildClassifyPort(0)).toBe('category_0');
  });

  it('should build category_5', () => {
    expect(buildClassifyPort(5)).toBe('category_5');
  });

  it('should build category_99', () => {
    expect(buildClassifyPort(99)).toBe('category_99');
  });
});

// ============================================================================
// Round-trip (parse -> build) consistency
// ============================================================================
describe('round-trip consistency', () => {
  const testCases = [
    'trigger:start',
    'mcp:fetch_data',
    'table:users',
    'agent:analyzer',
    'core:my_split',
    'core:check:if',
    'core:check:else',
    'core:check:elseif_0',
    'core:my_switch:case_0',
    'core:my_switch:default',
    'core:my_loop:body',
    'core:my_loop:iterate',
    'core:my_loop:exit',
    'core:parallel:branch_0',
    'core:parallel:branch_1',
    'agent:classifier:category_0',
    'agent:classifier:category_3',
    'note:my_note',
    'interface:user_form',
  ];

  testCases.forEach((ref) => {
    it(`should round-trip: ${ref}`, () => {
      const parsed = parseEdgeRef(ref);
      const rebuilt = buildEdgeRef(
        parsed.nodeType,
        parsed.nodeLabel,
        parsed.port
      );
      expect(rebuilt).toBe(ref);
    });
  });
});
