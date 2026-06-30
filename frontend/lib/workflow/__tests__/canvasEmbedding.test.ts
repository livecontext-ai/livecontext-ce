import { describe, it, expect } from 'vitest';
import { isEmbeddedWorkflowCanvas } from '../canvasEmbedding';

describe('isEmbeddedWorkflowCanvas', () => {
  it('is NOT embedded on the workflow own page', () => {
    expect(isEmbeddedWorkflowCanvas('/app/workflow/wf-1', 'wf-1')).toBe(false);
  });

  it('is NOT embedded on a locale-prefixed workflow page', () => {
    expect(isEmbeddedWorkflowCanvas('/fr/app/workflow/wf-1', 'wf-1')).toBe(false);
  });

  it('is NOT embedded on the workflow run sub-route', () => {
    expect(isEmbeddedWorkflowCanvas('/app/workflow/wf-1/run/run-9', 'wf-1')).toBe(false);
  });

  it('is embedded on a chat page (SidePanel tab)', () => {
    expect(isEmbeddedWorkflowCanvas('/app/c/conv-42', 'wf-1')).toBe(true);
  });

  it('is embedded when a sub-workflow canvas opens on ANOTHER workflow page', () => {
    expect(isEmbeddedWorkflowCanvas('/app/workflow/parent-wf', 'child-wf')).toBe(true);
  });

  it('is NOT embedded without a workflowId (unsaved builder page)', () => {
    expect(isEmbeddedWorkflowCanvas('/app/c/conv-42', undefined)).toBe(false);
  });

  it('treats a null pathname as embedded (no page match possible)', () => {
    expect(isEmbeddedWorkflowCanvas(null, 'wf-1')).toBe(true);
  });

  it('does NOT treat a longer id sharing a prefix as the own page (wf-1 vs wf-12)', () => {
    expect(isEmbeddedWorkflowCanvas('/app/workflow/wf-12', 'wf-1')).toBe(true);
  });

  it('escapes regex metacharacters in the workflow id', () => {
    expect(isEmbeddedWorkflowCanvas('/app/workflow/wf.1', 'wf.1')).toBe(false);
    expect(isEmbeddedWorkflowCanvas('/app/workflow/wfx1', 'wf.1')).toBe(true);
  });
});
