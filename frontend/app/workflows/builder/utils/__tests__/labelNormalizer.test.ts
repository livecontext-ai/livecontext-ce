import { describe, it, expect } from 'vitest';
import {
  normalizeLabel,
  triggerKey,
  mcpKey,
  coreKey,
  agentKey,
  tableKey,
  interfaceKey,
  noteKey,
  isNormalizedKey,
  isTriggerKey,
  isMcpKey,
  isCoreKey,
  isAgentKey,
  isTableKey,
  isNoteKey,
  isInterfaceKey,
  getNodeType,
  extractLabelFromKey,
  extractAndNormalizeLabel,
  extractCoreLabel,
  extractCoreLabelWithoutPort,
  extractTriggerLabel,
  extractMcpLabel,
  extractTableLabel,
  isReusableTriggerType,
} from '../labelNormalizer';

// ============================================================================
// normalizeLabel
// ============================================================================
describe('normalizeLabel', () => {
  describe('basic normalization', () => {
    it('should lowercase a simple label', () => {
      expect(normalizeLabel('Hello')).toBe('hello');
    });

    it('should replace spaces with underscores', () => {
      expect(normalizeLabel('My Label')).toBe('my_label');
    });

    it('should replace hyphens with underscores', () => {
      expect(normalizeLabel('Step-123')).toBe('step_123');
    });

    it('should replace slashes with underscores', () => {
      expect(normalizeLabel('If / else')).toBe('if_else');
    });

    it('should handle multiple spaces', () => {
      expect(normalizeLabel('Hello   World')).toBe('hello_world');
    });

    it('should collapse multiple consecutive underscores', () => {
      expect(normalizeLabel('a__b___c')).toBe('a_b_c');
    });

    it('should remove leading underscores', () => {
      expect(normalizeLabel('_leading')).toBe('leading');
    });

    it('should remove trailing underscores', () => {
      expect(normalizeLabel('trailing_')).toBe('trailing');
    });

    it('should remove both leading and trailing underscores', () => {
      expect(normalizeLabel('__both__')).toBe('both');
    });

    it('should handle a mix of special characters', () => {
      expect(normalizeLabel('Trigger - Joueurs Football - Instagram')).toBe(
        'trigger_joueurs_football_instagram'
      );
    });
  });

  describe('accent transliteration', () => {
    it('should transliterate French accented e', () => {
      expect(normalizeLabel('Entree IDs')).toBe('entree_ids');
    });

    it('should transliterate e with acute accent', () => {
      expect(normalizeLabel('\u00e9l\u00e8ve')).toBe('eleve');
    });

    it('should transliterate c with cedilla', () => {
      expect(normalizeLabel('Fran\u00e7ais')).toBe('francais');
    });

    it('should transliterate u with accent', () => {
      expect(normalizeLabel('d\u00e9j\u00e0 vu')).toBe('deja_vu');
    });

    it('should transliterate n with tilde', () => {
      expect(normalizeLabel('Espa\u00f1ol')).toBe('espanol');
    });

    it('should transliterate German umlauts', () => {
      expect(normalizeLabel('\u00fcber')).toBe('uber');
    });

    it('should transliterate o with circumflex', () => {
      expect(normalizeLabel('r\u00f4le')).toBe('role');
    });
  });

  describe('null/empty/undefined handling', () => {
    it('should return null for null input', () => {
      expect(normalizeLabel(null)).toBeNull();
    });

    it('should return null for undefined input', () => {
      expect(normalizeLabel(undefined)).toBeNull();
    });

    it('should return null for empty string', () => {
      expect(normalizeLabel('')).toBeNull();
    });

    it('should return null for whitespace-only string', () => {
      expect(normalizeLabel('   ')).toBeNull();
    });

    it('should return null for string that normalizes to empty', () => {
      // All special characters with no alphanumeric content
      expect(normalizeLabel('---')).toBeNull();
    });

    it('should return null for string of only special chars', () => {
      expect(normalizeLabel('!@#$%')).toBeNull();
    });
  });

  describe('edge cases', () => {
    it('should handle single character', () => {
      expect(normalizeLabel('A')).toBe('a');
    });

    it('should handle numbers only', () => {
      expect(normalizeLabel('123')).toBe('123');
    });

    it('should handle mixed case with numbers', () => {
      expect(normalizeLabel('Step2Go')).toBe('step2go');
    });

    it('should trim whitespace before normalizing', () => {
      expect(normalizeLabel('  hello world  ')).toBe('hello_world');
    });

    it('should handle tab characters', () => {
      expect(normalizeLabel('hello\tworld')).toBe('hello_world');
    });

    it('should handle dots', () => {
      expect(normalizeLabel('file.name')).toBe('file_name');
    });

    it('should handle parentheses', () => {
      expect(normalizeLabel('function(arg)')).toBe('function_arg');
    });

    it('should handle While Loop example', () => {
      expect(normalizeLabel('While Loop')).toBe('while_loop');
    });
  });
});

// ============================================================================
// Key builder functions
// ============================================================================
describe('key builders', () => {
  describe('triggerKey', () => {
    it('should create a trigger key from a label', () => {
      expect(triggerKey('My Webhook')).toBe('trigger:my_webhook');
    });

    it('should return null for null input', () => {
      expect(triggerKey(null)).toBeNull();
    });

    it('should return null for undefined input', () => {
      expect(triggerKey(undefined)).toBeNull();
    });

    it('should return null for empty string', () => {
      expect(triggerKey('')).toBeNull();
    });

    it('should handle accented characters', () => {
      expect(triggerKey('\u00c9v\u00e9nement')).toBe('trigger:evenement');
    });
  });

  describe('mcpKey', () => {
    it('should create an mcp key from a label', () => {
      expect(mcpKey('API Call')).toBe('mcp:api_call');
    });

    it('should return null for null input', () => {
      expect(mcpKey(null)).toBeNull();
    });

    it('should handle hyphens', () => {
      expect(mcpKey('fetch-data')).toBe('mcp:fetch_data');
    });
  });

  describe('coreKey', () => {
    it('should create a core key from a label', () => {
      expect(coreKey('Check Status')).toBe('core:check_status');
    });

    it('should return null for null input', () => {
      expect(coreKey(null)).toBeNull();
    });

    it('should handle simple labels', () => {
      expect(coreKey('Split')).toBe('core:split');
    });
  });

  describe('agentKey', () => {
    it('should create an agent key from a label', () => {
      expect(agentKey('My Analyzer')).toBe('agent:my_analyzer');
    });

    it('should return null for null input', () => {
      expect(agentKey(null)).toBeNull();
    });
  });

  describe('tableKey', () => {
    it('should create a table key from a label', () => {
      expect(tableKey('Users Table')).toBe('table:users_table');
    });

    it('should return null for null input', () => {
      expect(tableKey(null)).toBeNull();
    });
  });

  describe('interfaceKey', () => {
    it('should create an interface key from a label', () => {
      expect(interfaceKey('User Form')).toBe('interface:user_form');
    });

    it('should return null for null input', () => {
      expect(interfaceKey(null)).toBeNull();
    });
  });

  describe('noteKey', () => {
    it('should create a note key from a label', () => {
      expect(noteKey('My Note')).toBe('note:my_note');
    });

    it('should return null for null input', () => {
      expect(noteKey(null)).toBeNull();
    });
  });
});

// ============================================================================
// Key validation functions
// ============================================================================
describe('key validation', () => {
  describe('isNormalizedKey', () => {
    it('should return true for trigger key', () => {
      expect(isNormalizedKey('trigger:start')).toBe(true);
    });

    it('should return true for mcp key', () => {
      expect(isNormalizedKey('mcp:fetch_data')).toBe(true);
    });

    it('should return true for core key', () => {
      expect(isNormalizedKey('core:loop')).toBe(true);
    });

    it('should return true for agent key', () => {
      expect(isNormalizedKey('agent:analyzer')).toBe(true);
    });

    it('should return true for table key', () => {
      expect(isNormalizedKey('table:users')).toBe(true);
    });

    it('should return true for note key', () => {
      expect(isNormalizedKey('note:my_note')).toBe(true);
    });

    it('should return true for interface key', () => {
      expect(isNormalizedKey('interface:form')).toBe(true);
    });

    it('should return false for unknown prefix', () => {
      expect(isNormalizedKey('unknown:something')).toBe(false);
    });

    it('should return false for null', () => {
      expect(isNormalizedKey(null)).toBe(false);
    });

    it('should return false for undefined', () => {
      expect(isNormalizedKey(undefined)).toBe(false);
    });

    it('should return false for empty string', () => {
      expect(isNormalizedKey('')).toBe(false);
    });

    it('should return false for whitespace', () => {
      expect(isNormalizedKey('   ')).toBe(false);
    });

    it('should return false for no prefix', () => {
      expect(isNormalizedKey('just_a_label')).toBe(false);
    });
  });

  describe('isTriggerKey', () => {
    it('should return true for trigger key', () => {
      expect(isTriggerKey('trigger:start')).toBe(true);
    });

    it('should return false for non-trigger key', () => {
      expect(isTriggerKey('mcp:step')).toBe(false);
    });

    it('should return false for null', () => {
      expect(isTriggerKey(null)).toBe(false);
    });

    it('should return false for undefined', () => {
      expect(isTriggerKey(undefined)).toBe(false);
    });
  });

  describe('isMcpKey', () => {
    it('should return true for mcp key', () => {
      expect(isMcpKey('mcp:fetch')).toBe(true);
    });

    it('should return false for non-mcp key', () => {
      expect(isMcpKey('core:loop')).toBe(false);
    });

    it('should return false for null', () => {
      expect(isMcpKey(null)).toBe(false);
    });
  });

  describe('isCoreKey', () => {
    it('should return true for core key', () => {
      expect(isCoreKey('core:decision')).toBe(true);
    });

    it('should return false for non-core key', () => {
      expect(isCoreKey('mcp:step')).toBe(false);
    });

    it('should return false for null', () => {
      expect(isCoreKey(null)).toBe(false);
    });
  });

  describe('isAgentKey', () => {
    it('should return true for agent key', () => {
      expect(isAgentKey('agent:classifier')).toBe(true);
    });

    it('should return false for non-agent key', () => {
      expect(isAgentKey('mcp:step')).toBe(false);
    });

    it('should return false for null', () => {
      expect(isAgentKey(null)).toBe(false);
    });
  });

  describe('isTableKey', () => {
    it('should return true for table key', () => {
      expect(isTableKey('table:users')).toBe(true);
    });

    it('should return false for non-table key', () => {
      expect(isTableKey('mcp:step')).toBe(false);
    });

    it('should return false for null', () => {
      expect(isTableKey(null)).toBe(false);
    });
  });

  describe('isNoteKey', () => {
    it('should return true for note key', () => {
      expect(isNoteKey('note:readme')).toBe(true);
    });

    it('should return false for non-note key', () => {
      expect(isNoteKey('mcp:step')).toBe(false);
    });

    it('should return false for null', () => {
      expect(isNoteKey(null)).toBe(false);
    });
  });

  describe('isInterfaceKey', () => {
    it('should return true for interface key', () => {
      expect(isInterfaceKey('interface:form')).toBe(true);
    });

    it('should return false for non-interface key', () => {
      expect(isInterfaceKey('mcp:step')).toBe(false);
    });

    it('should return false for null', () => {
      expect(isInterfaceKey(null)).toBe(false);
    });
  });
});

// ============================================================================
// Utility functions
// ============================================================================
describe('utility functions', () => {
  describe('getNodeType', () => {
    it('should extract trigger type', () => {
      expect(getNodeType('trigger:start')).toBe('trigger');
    });

    it('should extract mcp type', () => {
      expect(getNodeType('mcp:api_call')).toBe('mcp');
    });

    it('should extract core type', () => {
      expect(getNodeType('core:decision')).toBe('core');
    });

    it('should extract agent type', () => {
      expect(getNodeType('agent:analyzer')).toBe('agent');
    });

    it('should return null for null input', () => {
      expect(getNodeType(null)).toBeNull();
    });

    it('should return null for undefined input', () => {
      expect(getNodeType(undefined)).toBeNull();
    });

    it('should return null for empty string', () => {
      expect(getNodeType('')).toBeNull();
    });

    it('should return null for string without colon', () => {
      expect(getNodeType('no_colon')).toBeNull();
    });

    it('should return null for string starting with colon', () => {
      expect(getNodeType(':no_prefix')).toBeNull();
    });
  });

  describe('extractLabelFromKey', () => {
    it('should extract label from mcp key', () => {
      expect(extractLabelFromKey('mcp:api_call')).toBe('api_call');
    });

    it('should extract label from trigger key', () => {
      expect(extractLabelFromKey('trigger:start')).toBe('start');
    });

    it('should extract label from core key with port', () => {
      expect(extractLabelFromKey('core:decision:if')).toBe('decision:if');
    });

    it('should return null for null input', () => {
      expect(extractLabelFromKey(null)).toBeNull();
    });

    it('should return null for undefined input', () => {
      expect(extractLabelFromKey(undefined)).toBeNull();
    });

    it('should return null for empty string', () => {
      expect(extractLabelFromKey('')).toBeNull();
    });

    it('should return null for key ending with colon and nothing after', () => {
      expect(extractLabelFromKey('mcp:')).toBeNull();
    });
  });

  describe('extractAndNormalizeLabel', () => {
    it('should extract and normalize from core prefix', () => {
      expect(extractAndNormalizeLabel('core:My Label', 'core:')).toBe('my_label');
    });

    it('should extract and normalize from trigger prefix', () => {
      expect(extractAndNormalizeLabel('trigger:Test Trigger', 'trigger:')).toBe(
        'test_trigger'
      );
    });

    it('should extract and normalize from mcp prefix', () => {
      expect(extractAndNormalizeLabel('mcp:My Step', 'mcp:')).toBe('my_step');
    });

    it('should normalize without prefix if prefix does not match', () => {
      expect(extractAndNormalizeLabel('Just A Label', 'core:')).toBe(
        'just_a_label'
      );
    });

    it('should return null for null input', () => {
      expect(extractAndNormalizeLabel(null, 'core:')).toBeNull();
    });

    it('should return null for undefined input', () => {
      expect(extractAndNormalizeLabel(undefined, 'core:')).toBeNull();
    });

    it('should return null for empty string', () => {
      expect(extractAndNormalizeLabel('', 'core:')).toBeNull();
    });

    it('should handle whitespace in prefixed ref', () => {
      expect(extractAndNormalizeLabel('  core:My Label  ', 'core:')).toBe(
        'my_label'
      );
    });
  });

  describe('extractCoreLabel', () => {
    it('should extract core label', () => {
      expect(extractCoreLabel('core:my_loop')).toBe('my_loop');
    });

    it('should normalize raw label inside core ref', () => {
      expect(extractCoreLabel('core:My Loop')).toBe('my_loop');
    });

    it('should return null for null', () => {
      expect(extractCoreLabel(null)).toBeNull();
    });

    it('should normalize non-prefixed label', () => {
      expect(extractCoreLabel('Some Label')).toBe('some_label');
    });
  });

  describe('extractTriggerLabel', () => {
    it('should extract trigger label', () => {
      expect(extractTriggerLabel('trigger:my_webhook')).toBe('my_webhook');
    });

    it('should return null for null', () => {
      expect(extractTriggerLabel(null)).toBeNull();
    });
  });

  describe('extractMcpLabel', () => {
    it('should extract mcp label', () => {
      expect(extractMcpLabel('mcp:api_call')).toBe('api_call');
    });

    it('should return null for null', () => {
      expect(extractMcpLabel(null)).toBeNull();
    });
  });

  describe('extractTableLabel', () => {
    it('should extract table label', () => {
      expect(extractTableLabel('table:users_table')).toBe('users_table');
    });

    it('should return null for null', () => {
      expect(extractTableLabel(null)).toBeNull();
    });
  });
});

// ============================================================================
// extractCoreLabelWithoutPort
// ============================================================================
describe('extractCoreLabelWithoutPort', () => {
  describe('core refs without ports', () => {
    it('should return the label when no port is present', () => {
      expect(extractCoreLabelWithoutPort('core:my_loop')).toBe('my_loop');
    });

    it('should normalize the label', () => {
      expect(extractCoreLabelWithoutPort('core:My Loop')).toBe('my_loop');
    });
  });

  describe('decision ports', () => {
    it('should strip :if port', () => {
      expect(extractCoreLabelWithoutPort('core:my_decision:if')).toBe(
        'my_decision'
      );
    });

    it('should strip :else port', () => {
      expect(extractCoreLabelWithoutPort('core:my_decision:else')).toBe(
        'my_decision'
      );
    });

    it('should strip :elseif_0 port', () => {
      expect(extractCoreLabelWithoutPort('core:my_decision:elseif_0')).toBe(
        'my_decision'
      );
    });

    it('should strip :elseif_1 port', () => {
      expect(extractCoreLabelWithoutPort('core:my_decision:elseif_1')).toBe(
        'my_decision'
      );
    });
  });

  describe('switch ports', () => {
    it('should strip :case_0 port', () => {
      expect(extractCoreLabelWithoutPort('core:my_switch:case_0')).toBe(
        'my_switch'
      );
    });

    it('should strip :case_1 port', () => {
      expect(extractCoreLabelWithoutPort('core:my_switch:case_1')).toBe(
        'my_switch'
      );
    });

    it('should strip :default port', () => {
      expect(extractCoreLabelWithoutPort('core:my_switch:default')).toBe(
        'my_switch'
      );
    });
  });

  describe('loop ports', () => {
    it('should strip :body port', () => {
      expect(extractCoreLabelWithoutPort('core:my_loop:body')).toBe('my_loop');
    });

    it('should strip :iterate port', () => {
      expect(extractCoreLabelWithoutPort('core:my_loop:iterate')).toBe(
        'my_loop'
      );
    });

    it('should strip :exit port', () => {
      expect(extractCoreLabelWithoutPort('core:my_loop:exit')).toBe('my_loop');
    });
  });

  describe('fork ports', () => {
    it('should strip :branch_0 port', () => {
      expect(extractCoreLabelWithoutPort('core:my_fork:branch_0')).toBe(
        'my_fork'
      );
    });

    it('should strip :branch_1 port', () => {
      expect(extractCoreLabelWithoutPort('core:my_fork:branch_1')).toBe(
        'my_fork'
      );
    });

    it('should strip :branch_10 port', () => {
      expect(extractCoreLabelWithoutPort('core:my_fork:branch_10')).toBe(
        'my_fork'
      );
    });
  });

  describe('hash suffix stripping', () => {
    it('should strip hash suffix from streaming refs', () => {
      expect(extractCoreLabelWithoutPort('core:my_loop:body#1')).toBe(
        'my_loop'
      );
    });

    it('should strip hash suffix without port', () => {
      expect(extractCoreLabelWithoutPort('core:my_loop#2')).toBe('my_loop');
    });
  });

  describe('non-core refs', () => {
    it('should normalize non-core ref as label', () => {
      expect(extractCoreLabelWithoutPort('mcp:my_step')).toBe('mcp_my_step');
    });

    it('should normalize plain label', () => {
      expect(extractCoreLabelWithoutPort('Some Label')).toBe('some_label');
    });
  });

  describe('null/empty handling', () => {
    it('should return null for null', () => {
      expect(extractCoreLabelWithoutPort(null)).toBeNull();
    });

    it('should return null for undefined', () => {
      expect(extractCoreLabelWithoutPort(undefined)).toBeNull();
    });

    it('should return null for empty string', () => {
      expect(extractCoreLabelWithoutPort('')).toBeNull();
    });

    it('should return null for whitespace', () => {
      expect(extractCoreLabelWithoutPort('   ')).toBeNull();
    });
  });

  describe('edge case: last part is not a known port', () => {
    it('should include last part in label when it is not a port', () => {
      expect(extractCoreLabelWithoutPort('core:my_node:custom')).toBe(
        'my_node_custom'
      );
    });
  });
});

// ============================================================================
// isReusableTriggerType
// ============================================================================
describe('isReusableTriggerType', () => {
  it('should return true for webhook', () => {
    expect(isReusableTriggerType('webhook')).toBe(true);
  });

  it('should return true for manual', () => {
    expect(isReusableTriggerType('manual')).toBe(true);
  });

  it('should return true for chat', () => {
    expect(isReusableTriggerType('chat')).toBe(true);
  });

  it('should return true for datasource', () => {
    expect(isReusableTriggerType('datasource')).toBe(true);
  });

  it('should return true for schedule', () => {
    expect(isReusableTriggerType('schedule')).toBe(true);
  });

  it('should return true for workflow', () => {
    expect(isReusableTriggerType('workflow')).toBe(true);
  });

  it('should be case-insensitive (uppercase)', () => {
    expect(isReusableTriggerType('WEBHOOK')).toBe(true);
  });

  it('should be case-insensitive (mixed case)', () => {
    expect(isReusableTriggerType('Manual')).toBe(true);
  });

  it('should return false for unknown type', () => {
    expect(isReusableTriggerType('custom')).toBe(false);
  });

  it('should return false for null', () => {
    expect(isReusableTriggerType(null)).toBe(false);
  });

  it('should return false for undefined', () => {
    expect(isReusableTriggerType(undefined)).toBe(false);
  });

  it('should return false for empty string', () => {
    expect(isReusableTriggerType('')).toBe(false);
  });
});
