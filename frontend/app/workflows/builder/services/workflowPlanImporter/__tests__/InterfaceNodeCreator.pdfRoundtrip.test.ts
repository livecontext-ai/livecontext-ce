import { describe, it, expect } from 'vitest';
import { createInterfaceNodes } from '../InterfaceNodeCreator';
import { collectInterfaces } from '../../../utils/interfaceProcessor';

/**
 * Regression: the interface node's generatePdf toggle + pdfFormat/pdfLandscape options were
 * dropped on the frontend plan round-trip. The import (createInterfaceNodes) mapped
 * generateScreenshot/exposeRenderedSource into interfaceData but NOT the PDF fields, so a plan
 * built by the agent (which HAS generatePdf) opened with the checkbox OFF; the export
 * (collectInterfaces) then re-serialized without the PDF fields, so any save stripped generatePdf
 * from the plan entirely (symptom: {{interface:x.output.pdf}} downstream, but no pdf produced).
 * These tests pin both directions + the full round-trip.
 */
describe('interface node PDF fields survive the frontend plan round-trip', () => {
  it('IMPORT: plan -> interfaceData maps generatePdf / pdfFormat / pdfLandscape', () => {
    const { nodes } = createInterfaceNodes(
      [{ id: 'iface-1', label: 'Invoice', generatePdf: true, pdfFormat: 'Letter', pdfLandscape: true }],
      0, 0);

    const interfaceData = (nodes[0].data as any).interfaceData;
    expect(interfaceData.generatePdf).toBe(true);
    expect(interfaceData.pdfFormat).toBe('Letter');
    expect(interfaceData.pdfLandscape).toBe(true);
  });

  it('EXPORT: interfaceData -> plan serializes generatePdf / pdfFormat / pdfLandscape', () => {
    const node = {
      id: 'interface-iface-1',
      type: 'interfaceNode',
      position: { x: 0, y: 0 },
      data: {
        label: 'Invoice',
        interfaceData: { interfaceId: 'iface-1', generatePdf: true, pdfFormat: 'Legal', pdfLandscape: true },
      },
    };
    const ctx: any = { nodes: [node], plan: {}, interfaceNodeIdMap: new Map() };

    collectInterfaces(ctx);

    const entry = ctx.plan.interfaces[0];
    expect(entry.generatePdf).toBe(true);
    expect(entry.pdfFormat).toBe('Legal');
    expect(entry.pdfLandscape).toBe(true);
  });

  it('EXPORT: omits PDF fields entirely when generatePdf is off (no plan pollution)', () => {
    const node = {
      id: 'interface-iface-2',
      type: 'interfaceNode',
      position: { x: 0, y: 0 },
      data: { label: 'Plain', interfaceData: { interfaceId: 'iface-2', pdfFormat: 'A4' } },
    };
    const ctx: any = { nodes: [node], plan: {}, interfaceNodeIdMap: new Map() };

    collectInterfaces(ctx);

    const entry = ctx.plan.interfaces[0];
    expect(entry.generatePdf).toBeUndefined();
    expect(entry.pdfFormat).toBeUndefined();
    expect(entry.pdfLandscape).toBeUndefined();
  });

  it('ROUND-TRIP: import a plan then export it preserves generatePdf + options', () => {
    const planIn = { id: 'iface-3', label: 'Report', generatePdf: true, pdfFormat: 'A4', pdfLandscape: false };
    const { nodes } = createInterfaceNodes([planIn], 0, 0);

    const ctx: any = { nodes, plan: {}, interfaceNodeIdMap: new Map() };
    collectInterfaces(ctx);

    const entry = ctx.plan.interfaces[0];
    expect(entry.generatePdf).toBe(true);
    expect(entry.pdfFormat).toBe('A4');
    // pdfLandscape=false is not serialized (only true is persisted, mirrors generateScreenshot)
    expect(entry.pdfLandscape).toBeUndefined();
  });
});
