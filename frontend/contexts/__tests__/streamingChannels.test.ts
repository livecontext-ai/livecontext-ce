import { describe, it, expect } from 'vitest';
import { selectLiveChannelIds, PLACEHOLDER_CONVERSATION_PREFIX } from '../streamingChannels';
import type { StreamingStatus } from '../StreamingContext';

/**
 * Which conversations get a live WebSocket channel.
 *
 * A brand-new chat has no conversation id until the send POST returns one, so the client
 * keys its local stream state on a placeholder. That placeholder used to reach the
 * subscriber too, which means every new conversation opened by asking the gateway for a
 * channel that cannot exist ("User 1 denied access to channel conversation:temp-...",
 * one WARN per new chat, sitting in the log next to the genuine authorization failures
 * it is indistinguishable from). Nothing was ever published there: the server publishes
 * on the conversation it minted.
 */
describe('selectLiveChannelIds', () => {
  // Typed against the real union: `state.streams` holds SingleStreamState, so a fixture
  // that widens `status` to string both hides a typo and fails the repo's typecheck -
  // which `next build` runs, so it would break the frontend image rather than CI.
  const withStatus = (id: string, status: StreamingStatus): [string, { status: StreamingStatus }] =>
    [id, { status }];
  const streaming = (id: string) => withStatus(id, 'streaming');

  it('never opens a channel for a conversation that does not exist yet', () => {
    const streams = new Map([
      streaming(`${PLACEHOLDER_CONVERSATION_PREFIX}1789644686366`),
      streaming('a017fa4e-8781-40e0-9422-ef62ca617749'),
    ]);

    expect(selectLiveChannelIds(streams, undefined))
      .toEqual(['a017fa4e-8781-40e0-9422-ef62ca617749']);
  });

  it('keeps the real conversation once the send resolves', () => {
    const streams = new Map([streaming('dafadaf4-71d1-4628-9ab2-6cbe9e2c8a9c')]);

    expect(selectLiveChannelIds(streams, undefined))
      .toEqual(['dafadaf4-71d1-4628-9ab2-6cbe9e2c8a9c']);
  });

  it('includes a just-completed stream, so its terminal snapshot still lands', () => {
    expect(selectLiveChannelIds(new Map([withStatus('conv-done', 'completed')]), undefined))
      .toEqual(['conv-done']);
  });

  it('drops a stopped stream, and an error with no stream behind it (a refused send)', () => {
    // Not a made-up status: SingleStreamState.status is exactly
    // streaming | completed | stopped | error.
    expect(selectLiveChannelIds(new Map([withStatus('conv-stopped', 'stopped')]), undefined))
      .toEqual([]);
    expect(selectLiveChannelIds(new Map([withStatus('conv-error', 'error')]), undefined))
      .toEqual([]);
  });

  it('keeps listening to a live stream that reported an error, so its later reply still lands', () => {
    // An execution-link fallback publishes `error` and retries the turn under the SAME stream;
    // dropping the channel on the error threw away the content and the `done` that followed.
    const streams = new Map<string, { status: StreamingStatus; streamId: string | null }>([
      ['conv-fallback', { status: 'error', streamId: 'sid-1' }],
    ]);

    expect(selectLiveChannelIds(streams, undefined)).toEqual(['conv-fallback']);
  });

  it('releases the channel of an error the server confirmed as the end of the turn', () => {
    // Kept, it would hold a slot, request a snapshot on every resubscribe and cost REST reads
    // on every reconnect, for a turn that is over.
    const streams = new Map<string, { status: StreamingStatus; streamId: string | null; errorSettled?: boolean }>([
      ['conv-failed', { status: 'error', streamId: 'sid-1', errorSettled: true }],
    ]);

    expect(selectLiveChannelIds(streams, undefined)).toEqual([]);
  });

  it('adds the streams the server reports active after a reload, without duplicating', () => {
    const streams = new Map([streaming('conv-a')]);

    expect(selectLiveChannelIds(streams, new Set(['conv-a', 'conv-b'])))
      .toEqual(['conv-a', 'conv-b']);
  });

  it('drops a placeholder the server-active list carries too, not just a local one', () => {
    // Both inputs feed the same channel set, so both need the filter - the reload path
    // rebuilds serverActiveStreams from the server and must not reintroduce one.
    expect(selectLiveChannelIds(new Map(), new Set([`${PLACEHOLDER_CONVERSATION_PREFIX}42`, 'conv-real'])))
      .toEqual(['conv-real']);
  });

  it('is sorted, so per-token content changes cannot churn the subscription set', () => {
    const streams = new Map([streaming('conv-z'), streaming('conv-a')]);

    expect(selectLiveChannelIds(streams, undefined)).toEqual(['conv-a', 'conv-z']);
  });
});
