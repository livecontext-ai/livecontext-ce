import { describe, it, expect } from 'vitest';
import { buildToolsConfigPayload, getAllowedIds } from '../toolsConfigAccess';

/**
 * Files are a first-class but OPT-IN agent resource: the CreateAgentModal picker
 * writes toolsConfig.files, and an empty list means full org access (the inverse of
 * the 5 internal keys where []=deny). These guard the read/write helpers the picker uses.
 */
describe('toolsConfigAccess - files allow-list (opt-in agent resource)', () => {
  const base = {
    mode: 'all' as const,
    workflows: [],
    tables: [],
    interfaces: [],
    agents: [],
    applications: [],
  };

  it('emits a non-empty files allow-list so a scoped selection persists', () => {
    const payload = buildToolsConfigPayload({ ...base, files: ['f1', 'f2'] });
    expect(payload.files).toEqual(['f1', 'f2']);
  });

  it('always emits files (as []) so deselecting all on edit CLEARS a prior scope', () => {
    // Omitting files would let the backend merge keep the old list; [] resets to full access.
    const payload = buildToolsConfigPayload({ ...base, files: [] });
    expect(payload.files).toEqual([]);
    const payloadNoFiles = buildToolsConfigPayload({ ...base });
    expect(payloadNoFiles.files).toEqual([]);
  });

  it('reads back the stored files allow-list on edit (restore selection)', () => {
    expect(getAllowedIds({ files: ['a', 'b'] }, 'files')).toEqual(['a', 'b']);
    expect(getAllowedIds({ files: [] }, 'files')).toEqual([]);
    expect(getAllowedIds({}, 'files')).toEqual([]);
    expect(getAllowedIds(null, 'files')).toEqual([]);
  });

  it('does not entangle files with the other resource lists', () => {
    // New model: a resource list is emitted as its scope ONLY when that family's
    // grant is 'custom'. Pin workflows to 'custom' so its id list is the scope,
    // then assert files stays its own independent axis.
    const payload = buildToolsConfigPayload({
      ...base,
      workflows: ['w1'],
      workflowsGrant: 'custom',
      files: ['f1'],
    });
    expect(payload.workflows).toEqual(['w1']);
    expect(payload.files).toEqual(['f1']);
    expect(payload.tables).toEqual([]);
  });
});
