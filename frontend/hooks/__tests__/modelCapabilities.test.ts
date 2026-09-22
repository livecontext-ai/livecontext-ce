import { describe, it, expect } from 'vitest';
import { modelHasCapability, type AIModel } from '@/hooks/useModels';

/**
 * Which models a picker is allowed to offer.
 *
 * <p>This is the client half of a rule the backend also enforces, and the half a user
 * actually sees: a model that cannot hold a conversation must not appear in a chat
 * picker, and a model that cannot classify must not appear alone in a classify one. The
 * capability is read from the catalogue's own `mode` wherever there is one, so a new
 * kind of model does not need this file edited to be filtered correctly.
 */
describe('modelHasCapability', () => {
  const model = (overrides: Partial<AIModel>): AIModel => ({
    id: 'm1',
    name: 'M1',
    provider: 'p1',
    ...overrides,
  });

  describe('derived from the catalogue mode', () => {
    it('a decision model is a decision model and nothing else', () => {
      const jev = model({ id: 'jev-latest', provider: 'typesafe', mode: 'decision' });

      expect(modelHasCapability(jev, 'decision')).toBe(true);
      // The assertion that matters: it must never pass a chat filter, because every
      // selection made there would break at run time.
      expect(modelHasCapability(jev, 'chat')).toBe(false);
      expect(modelHasCapability(jev, 'image')).toBe(false);
    });

    it('a chat model is not offered where a decision model is wanted', () => {
      const gpt = model({ id: 'gpt-5', mode: 'chat' });

      expect(modelHasCapability(gpt, 'chat')).toBe(true);
      expect(modelHasCapability(gpt, 'decision')).toBe(false);
    });

    it('an image model is read from its mode, not only from the hardcoded id list', () => {
      const unknownImageModel = model({ id: 'some-future-image-model', mode: 'image' });

      expect(modelHasCapability(unknownImageModel, 'image')).toBe(true);
      expect(modelHasCapability(unknownImageModel, 'chat')).toBe(false);
    });
  });

  describe('fallbacks', () => {
    it('a row with no mode is chat, which is what every model was before modes existed', () => {
      expect(modelHasCapability(model({ id: 'legacy' }), 'chat')).toBe(true);
      expect(modelHasCapability(model({ id: 'legacy' }), 'decision')).toBe(false);
    });

    it('a known image id still works without a mode, so older payloads keep behaving', () => {
      expect(modelHasCapability(model({ id: 'gpt-image-1.5' }), 'image')).toBe(true);
    });

    it('an explicit capability list still wins over everything', () => {
      const tagged = model({ id: 'x', mode: 'decision', capabilities: ['chat'] });

      expect(modelHasCapability(tagged, 'chat')).toBe(true);
    });

    it('an unrecognised mode falls back to chat rather than vanishing from every picker', () => {
      // Hiding a model everywhere the day the backend adds a mode this file has not
      // learned yet would be a worse failure than showing it in the wrong list.
      expect(modelHasCapability(model({ id: 'x', mode: 'transcription' }), 'chat')).toBe(true);
    });
  });

  describe('a surface that accepts more than one kind', () => {
    const CLASSIFY_ENGINES = ['chat', 'decision'] as const;

    it('offers both engines to the classify node', () => {
      const jev = model({ id: 'jev-latest', provider: 'typesafe', mode: 'decision' });
      const gpt = model({ id: 'gpt-5', mode: 'chat' });

      expect(modelHasCapability(jev, CLASSIFY_ENGINES)).toBe(true);
      expect(modelHasCapability(gpt, CLASSIFY_ENGINES)).toBe(true);
    });

    it('still excludes a kind it did not ask for', () => {
      const image = model({ id: 'gpt-image-1.5', mode: 'image' });

      expect(modelHasCapability(image, CLASSIFY_ENGINES)).toBe(false);
    });
  });
});
