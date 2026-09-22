// @vitest-environment jsdom
/**
 * The delete METHODS announce the deletion, not their callers.
 *
 * There are six agent-delete call sites alone (the list's bulk bar, the side-panel
 * tab menu, the agent edit modal, two chat surfaces, a canvas node), and each one
 * that forgot to tell the rest of the app left a stale tab or a stale row. Putting
 * the broadcast in the caller makes "did you remember?" a permanent review
 * question; putting it in the method makes it impossible to forget, because the
 * fact announced - the server accepted the delete - is exactly what the method
 * knows and nothing else has to be told about.
 *
 * The other half is the failure case: a delete that threw has deleted nothing, so
 * a surface that dropped the row on the announcement would be showing a lie.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const del = vi.fn();
vi.mock('@/lib/api/api-client', () => ({ apiClient: { delete: (...args: unknown[]) => del(...args) } }));
vi.mock('../../api-client', () => ({ apiClient: { delete: (...args: unknown[]) => del(...args) } }));

import { RESOURCE_DELETED_EVENT, type ResourceDeletedDetail } from '@/lib/resources/resourceDeleted';
import { agentService } from '../agent.service';
import { workflowService } from '../workflow.service';
import { interfaceService } from '../interface.service';
import { dataSourceService } from '../datasource.service';
import { conversationApi } from '@/lib/api/conversationApi';
import type { DeletedResourceKind } from '@/lib/resources/resourceDeleted';
import { parseTabResource } from '@/lib/sidePanel/tabResource';

const ID = 'a0000000-0000-4000-8000-000000000001';

let announced: ResourceDeletedDetail[];
const record = (e: Event) => { announced.push((e as CustomEvent<ResourceDeletedDetail>).detail); };

beforeEach(() => {
  announced = [];
  del.mockReset().mockResolvedValue(undefined);
  window.addEventListener(RESOURCE_DELETED_EVENT, record);
});
afterEach(() => window.removeEventListener(RESOURCE_DELETED_EVENT, record));

describe('orchestrator delete methods announce the deletion', () => {
  it.each([
    ['agent', () => agentService.deleteAgent(ID)],
    ['workflow', () => workflowService.deleteWorkflow(ID)],
    ['interface', () => interfaceService.deleteInterface(ID)],
    ['datasource', () => dataSourceService.deleteDataSource(ID)],
    ['conversation', () => conversationApi.deleteConversation(ID)],
  ])('%s', async (kind, call) => {
    await call();

    expect(del).toHaveBeenCalledTimes(1);
    expect(announced).toEqual([{ kind, id: ID }]);
  });
});

describe('a refused delete announces nothing', () => {
  it.each([
    ['agent', () => agentService.deleteAgent(ID)],
    ['workflow', () => workflowService.deleteWorkflow(ID)],
    ['interface', () => interfaceService.deleteInterface(ID)],
    ['datasource', () => dataSourceService.deleteDataSource(ID)],
    // conversationApi rewrites the error rather than rethrowing it; the assertion
    // that matters is the same one: nothing was announced.
    ['conversation', () => conversationApi.deleteConversation(ID)],
  ])('%s keeps the error and stays silent', async (_kind, call) => {
    del.mockRejectedValueOnce(new Error('HTTP 403'));

    await expect(call()).rejects.toThrow();
    // The row is still there server-side. Announcing here would make every
    // listening list drop a resource the user still owns.
    expect(announced).toEqual([]);
  });
});

describe('the 204 branch, which this client treats as a success', () => {
  it('announces, because that branch already decided the delete went through', async () => {
    // apiClient returns null on a 204 today, so nothing produces this message; the
    // branch is the client's own belt and braces. What matters is that it does not
    // get to report success to its caller and stay silent to everyone else.
    del.mockRejectedValueOnce(new Error('Invalid response status code 204'));

    await expect(conversationApi.deleteConversation(ID)).resolves.toBeNull();

    expect(announced).toEqual([{ kind: 'conversation', id: ID }]);
  });
});

/**
 * The panel closes a deleted resource's tab by looking the kind up in the tab-id
 * grammar, so every deletable kind has to be one that grammar can PARSE. Type
 * assignability is not enough: adding a kind to both unions would compile and
 * still match no branch in `parseTabResource`, and the panel would close nothing.
 * So this asserts against the parser itself, at runtime.
 */
describe('every deletable kind is one the side panel can match', () => {
  const KINDS: DeletedResourceKind[] = ['agent', 'workflow', 'interface', 'datasource', 'conversation'];
  const UUID = 'f54f378a-c4ff-4398-a003-107c87e9f2a6';

  it.each(KINDS)('%s', (kind) => {
    // Every tab id in this app is `<kind>-<id>`; the parser owns the exceptions.
    expect(parseTabResource(`${kind}-${UUID}`)).toEqual({ kind, id: UUID });
  });

  it('names every kind the union declares, so a new one cannot slip past this list', () => {
    const declared: Record<DeletedResourceKind, true> = {
      agent: true, workflow: true, interface: true, datasource: true, conversation: true,
    };
    // Adding a kind to the union makes this object literal fail to compile until it
    // is listed here, and listing it puts it through the parser assertion above.
    expect(Object.keys(declared).sort()).toEqual([...KINDS].sort());
  });
});
