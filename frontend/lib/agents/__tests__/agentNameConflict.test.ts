import { describe, expect, it } from 'vitest';
import { readAgentNameConflict } from '../agentNameConflict';

/**
 * The 409 AGENT_NAME_CONFLICT agent-service answers when a typed agent name (create, rename,
 * resume) is already held by an active agent of the workspace, as apiClient hands it over
 * (ApiError: `code` = body.error, `details` = body). Read by shape so it also works where the
 * API client module is mocked.
 */
describe('readAgentNameConflict', () => {
  it('reads the taken name and the suggestion from an ApiError-shaped conflict', () => {
    const err = Object.assign(new Error('taken'), {
      status: 409, code: 'AGENT_NAME_CONFLICT',
      details: { error: 'AGENT_NAME_CONFLICT', name: 'Nova', suggestedName: 'Nova (2)' },
    });
    expect(readAgentNameConflict(err)).toEqual({ name: 'Nova', suggestedName: 'Nova (2)' });
  });

  it('recognises the conflict from details.error alone (a code the client derived differently)', () => {
    const err = { status: 409, code: 'HTTP_409', details: { error: 'AGENT_NAME_CONFLICT', suggestedName: 'Nova (3)' } };
    expect(readAgentNameConflict(err)).toEqual({ name: null, suggestedName: 'Nova (3)' });
  });

  it('answers a conflict with no suggestion when the server could not compute one (or sent a blank one)', () => {
    expect(readAgentNameConflict({ code: 'AGENT_NAME_CONFLICT', details: {} }))
      .toEqual({ name: null, suggestedName: null });
    expect(readAgentNameConflict({ code: 'AGENT_NAME_CONFLICT', details: { suggestedName: '  ', name: ' ' } }))
      .toEqual({ name: null, suggestedName: null });
  });

  it('is null for every other error, including a generic duplicate', () => {
    expect(readAgentNameConflict(new Error('boom'))).toBeNull();
    expect(readAgentNameConflict({ code: 'DUPLICATE_RESOURCE', details: { error: 'DUPLICATE_RESOURCE' } })).toBeNull();
    expect(readAgentNameConflict(null)).toBeNull();
    expect(readAgentNameConflict('AGENT_NAME_CONFLICT')).toBeNull();
  });
});
