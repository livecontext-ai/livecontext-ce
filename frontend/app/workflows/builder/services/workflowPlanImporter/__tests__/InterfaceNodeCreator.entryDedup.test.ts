import { describe, it, expect } from 'vitest';
import { createInterfaceNodes } from '../InterfaceNodeCreator';

/**
 * Single-entry invariant on plan IMPORT: an application has ONE entry page. The canvas
 * builder enforces it on edit, but a plan written by an agent (or an older backend path)
 * can carry several isEntryInterface=true interfaces. Import keeps the FIRST and clears
 * the rest, mirroring the backend showcase resolver's findFirst().
 */
describe('createInterfaceNodes entry-interface dedup', () => {
  const iface = (id: string, isEntryInterface?: boolean) => ({
    id,
    label: `UI ${id}`,
    isEntryInterface,
  });

  const entryFlag = (node: any) => (node.data as any).interfaceData.isEntryInterface;

  it('keeps the FIRST flagged entry and clears the rest', () => {
    const { nodes } = createInterfaceNodes(
      [iface('a', true), iface('b', true), iface('c')],
      0, 0,
    );
    expect(entryFlag(nodes[0])).toBe(true);
    expect(entryFlag(nodes[1])).toBe(false);
    expect(entryFlag(nodes[2])).toBe(false);
  });

  it('a single flagged entry imports unchanged, wherever it sits', () => {
    const { nodes } = createInterfaceNodes(
      [iface('a'), iface('b', true), iface('c')],
      0, 0,
    );
    expect(entryFlag(nodes[0])).toBe(false);
    expect(entryFlag(nodes[1])).toBe(true);
    expect(entryFlag(nodes[2])).toBe(false);
  });

  it('zero flagged entries stays zero (the resolver falls back to the first interface)', () => {
    const { nodes } = createInterfaceNodes([iface('a'), iface('b')], 0, 0);
    expect(entryFlag(nodes[0])).toBe(false);
    expect(entryFlag(nodes[1])).toBe(false);
  });
});
