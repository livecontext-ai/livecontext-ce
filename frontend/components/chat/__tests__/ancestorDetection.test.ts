import { describe, it, expect } from 'vitest';
import { computeAncestorIds } from '../ancestorDetection';

describe('computeAncestorIds', () => {
  // Helper to create a minimal agent with optional toolsConfig.agents
  function agent(id: string, subAgents?: string[]) {
    return {
      id,
      name: id,
      toolsConfig: subAgents ? { agents: subAgents } : undefined,
    } as any;
  }

  describe('static config (toolsConfig.agents)', () => {
    it('should detect direct parent', () => {
      // pulse → nova (pulse has nova as sub-agent)
      const agents = [agent('pulse', ['nova']), agent('nova'), agent('sakura')];

      const result = computeAncestorIds('nova', agents, []);

      expect(result.has('pulse')).toBe(true);
      expect(result.has('sakura')).toBe(false);
    });

    it('should detect transitive ancestors (grandparent)', () => {
      // grandpa → pulse → nova
      const agents = [
        agent('grandpa', ['pulse']),
        agent('pulse', ['nova']),
        agent('nova'),
      ];

      const result = computeAncestorIds('nova', agents, []);

      expect(result.has('pulse')).toBe(true);
      expect(result.has('grandpa')).toBe(true);
    });

    it('should NOT mark agents without sub-agent config as ancestors', () => {
      // sakura has no toolsConfig → not a parent of anyone
      const agents = [agent('nova'), agent('sakura')];

      const result = computeAncestorIds('nova', agents, []);

      expect(result.has('sakura')).toBe(false);
    });

    it('should NOT mark agents with unrelated sub-agents as ancestors', () => {
      // pulse → sakura (not nova)
      const agents = [agent('pulse', ['sakura']), agent('nova'), agent('sakura')];

      const result = computeAncestorIds('nova', agents, []);

      expect(result.has('pulse')).toBe(false);
    });

    it('should NOT include the agent itself', () => {
      const agents = [agent('nova', ['nova'])]; // self-reference

      const result = computeAncestorIds('nova', agents, []);

      expect(result.has('nova')).toBe(false);
    });

    it('should return empty set when no agents exist', () => {
      const result = computeAncestorIds('nova', [], []);

      expect(result.size).toBe(0);
    });

    it('should handle toolsConfig with empty agents array', () => {
      const agents = [agent('pulse', []), agent('nova')];

      const result = computeAncestorIds('nova', agents, []);

      expect(result.has('pulse')).toBe(false);
    });
  });

  describe('runtime edges', () => {
    it('should detect parent from runtime edges only', () => {
      // No static config, but runtime shows pulse called nova
      const agents = [agent('pulse'), agent('nova')];
      const edges = [{ callerId: 'pulse', calleeId: 'nova' }];

      const result = computeAncestorIds('nova', agents, edges);

      expect(result.has('pulse')).toBe(true);
    });

    it('should detect transitive ancestors from runtime edges', () => {
      // grandpa → pulse → nova (all runtime)
      const agents = [agent('grandpa'), agent('pulse'), agent('nova')];
      const edges = [
        { callerId: 'grandpa', calleeId: 'pulse' },
        { callerId: 'pulse', calleeId: 'nova' },
      ];

      const result = computeAncestorIds('nova', agents, edges);

      expect(result.has('pulse')).toBe(true);
      expect(result.has('grandpa')).toBe(true);
    });

    it('should NOT mark callees of target as ancestors', () => {
      // nova → sakura (nova calls sakura, not the reverse)
      const agents = [agent('nova'), agent('sakura')];
      const edges = [{ callerId: 'nova', calleeId: 'sakura' }];

      const result = computeAncestorIds('nova', agents, edges);

      expect(result.has('sakura')).toBe(false);
    });
  });

  describe('mixed static + runtime', () => {
    it('should merge both sources for ancestor detection', () => {
      // Static: pulse → nova
      // Runtime: grandpa → pulse
      const agents = [
        agent('grandpa'),
        agent('pulse', ['nova']),
        agent('nova'),
      ];
      const edges = [{ callerId: 'grandpa', calleeId: 'pulse' }];

      const result = computeAncestorIds('nova', agents, edges);

      expect(result.has('pulse')).toBe(true);
      expect(result.has('grandpa')).toBe(true);
    });

    it('should handle complex graph without cycles', () => {
      // A → B → D
      // A → C → D
      // D is the target
      const agents = [
        agent('A', ['B', 'C']),
        agent('B', ['D']),
        agent('C'),
        agent('D'),
      ];
      const edges = [{ callerId: 'C', calleeId: 'D' }];

      const result = computeAncestorIds('D', agents, edges);

      expect(result.has('A')).toBe(true);
      expect(result.has('B')).toBe(true);
      expect(result.has('C')).toBe(true);
    });

    it('should handle duplicate edges (static + runtime overlap)', () => {
      const agents = [agent('pulse', ['nova']), agent('nova')];
      const edges = [{ callerId: 'pulse', calleeId: 'nova' }];

      const result = computeAncestorIds('nova', agents, edges);

      expect(result.has('pulse')).toBe(true);
      expect(result.size).toBe(1);
    });
  });
});
