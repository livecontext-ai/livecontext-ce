import { describe, it, expect } from 'vitest';
import { isPlaceholderTitle, conversationDisplayTitle } from '../conversationTitle';

describe('isPlaceholderTitle', () => {
  it('treats null/undefined/blank as a placeholder (no real title)', () => {
    expect(isPlaceholderTitle(undefined)).toBe(true);
    expect(isPlaceholderTitle(null)).toBe(true);
    expect(isPlaceholderTitle('')).toBe(true);
    expect(isPlaceholderTitle('   ')).toBe(true);
  });

  it('matches the backend generating sentinel in both casings (and with surrounding whitespace)', () => {
    // ChatStreamInitializer / MonolithChatController produce capital-T.
    expect(isPlaceholderTitle('Generating Title...')).toBe(true);
    // ConversationHistoryService null-title fallback produces lowercase-t.
    expect(isPlaceholderTitle('Generating title...')).toBe(true);
    expect(isPlaceholderTitle('  Generating Title...  ')).toBe(true);
    expect(isPlaceholderTitle('GENERATING TITLE...')).toBe(true);
  });

  it('does not match a real title that merely resembles the sentinel', () => {
    expect(isPlaceholderTitle('Generating titles for my app')).toBe(false);
    expect(isPlaceholderTitle('Title')).toBe(false);
    expect(isPlaceholderTitle('How do I generate a title?')).toBe(false);
  });
});

describe('conversationDisplayTitle', () => {
  const FALLBACK = 'Untitled';

  it('returns the real title when one is assigned', () => {
    expect(
      conversationDisplayTitle({ title: 'Trip planning', firstMessagePreview: 'Plan my trip' }, FALLBACK),
    ).toBe('Trip planning');
  });

  it('falls back to the first user message when the title is the generating placeholder', () => {
    // This is the core bug: a general chat whose title never got generated.
    expect(
      conversationDisplayTitle(
        { title: 'Generating Title...', firstMessagePreview: 'How do I deploy to k8s?' },
        FALLBACK,
      ),
    ).toBe('How do I deploy to k8s?');
  });

  it('falls back to the first user message when the title is null/blank (no type restriction)', () => {
    expect(conversationDisplayTitle({ title: null, firstMessagePreview: 'hello there' }, FALLBACK)).toBe(
      'hello there',
    );
    expect(conversationDisplayTitle({ title: '   ', firstMessagePreview: 'hello there' }, FALLBACK)).toBe(
      'hello there',
    );
  });

  it('models the "user stopped early" case: placeholder title + persisted user message -> shows the message', () => {
    // On abort the conversation keeps its placeholder title but the user message was
    // already saved, so the preview is present and must win over the fallback label.
    expect(
      conversationDisplayTitle(
        { title: 'Generating title...', firstMessagePreview: 'Summarize this PDF for me' },
        FALLBACK,
      ),
    ).toBe('Summarize this PDF for me');
  });

  it('only shows the fallback label when there is neither a real title nor a preview', () => {
    expect(conversationDisplayTitle({ title: 'Generating Title...', firstMessagePreview: null }, FALLBACK)).toBe(
      FALLBACK,
    );
    expect(conversationDisplayTitle({ title: null, firstMessagePreview: '   ' }, FALLBACK)).toBe(FALLBACK);
    expect(conversationDisplayTitle({}, FALLBACK)).toBe(FALLBACK);
  });

  it('trims a real title for display', () => {
    expect(conversationDisplayTitle({ title: '  Deploy guide  ' }, FALLBACK)).toBe('Deploy guide');
  });

  it('prefers a real title over the preview', () => {
    expect(
      conversationDisplayTitle({ title: 'My real title', firstMessagePreview: 'first message' }, FALLBACK),
    ).toBe('My real title');
  });

  it('does NOT re-screen the preview: a user message that is literally the sentinel still shows', () => {
    // The placeholder check applies to the TITLE only. If the user's first message
    // happens to be "Generating title...", that is their real content and must be
    // shown, not collapsed to the fallback label.
    expect(
      conversationDisplayTitle({ title: 'Generating Title...', firstMessagePreview: 'Generating title...' }, FALLBACK),
    ).toBe('Generating title...');
  });
});
