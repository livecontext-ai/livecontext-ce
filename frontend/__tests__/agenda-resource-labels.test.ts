import { describe, expect, it } from 'vitest';
import { createTranslator } from 'next-intl';
import de from '@/messages/de.json';
import en from '@/messages/en.json';
import es from '@/messages/es.json';
import fr from '@/messages/fr.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';
import type { ProductionResourceKind } from '@/lib/api/orchestrator/resource-control';

const LOCALES = { de, en, es, fr, pt, zh } as const;
const RESOURCE_KINDS: ProductionResourceKind[] = ['workflow', 'agent', 'interface'];

describe('agenda resource action labels', () => {
  it.each(Object.entries(LOCALES))('renders explicit resource names in %s', (locale, messages) => {
    // Limit key inference to the namespaces this test exercises. Inferring the
    // union of six complete catalogs makes unrelated translations costly to check.
    const agenda = createTranslator({ locale, messages: messages.agenda });
    const actions = createTranslator({ locale, messages: messages.chat.home.live.triggerActions });
    const agendaLabels = RESOURCE_KINDS.flatMap((type) => [
      agenda('menu.pauseResource', { type }),
      agenda('menu.resumeResource', { type }),
      agenda('toasts.resourcePausedTitle', { type }),
      agenda('toasts.resourcePausedMessage', { type }),
      agenda('toasts.resourceResumedTitle', { type }),
      agenda('toasts.resourceResumedMessage', { type }),
      agenda('toasts.resourceToggleFailedTitle', { type }),
    ]);
    const notificationLabels = RESOURCE_KINDS.flatMap((type) => [
      actions('pauseResource', { type }),
      actions('resumeResource', { type }),
      actions('resourcePaused', { type }),
      actions('resourceResumed', { type }),
      actions('resourceToggleFailed', { type }),
    ]);

    expect(new Set(RESOURCE_KINDS.map((type) =>
      agenda('menu.pauseResource', { type }))).size).toBe(3);
    expect(new Set(RESOURCE_KINDS.map((type) =>
      actions('pauseResource', { type }))).size).toBe(3);
    for (const label of [...agendaLabels, ...notificationLabels]) {
      expect(label).not.toContain('{type');
    }
  });

  it('makes the two scopes unmistakable in French', () => {
    const agenda = createTranslator({ locale: 'fr', messages: fr.agenda });
    const actions = createTranslator({ locale: 'fr', messages: fr.chat.home.live.triggerActions });

    expect(agenda('menu.pause')).toBe('Suspendre cette planification');
    expect(agenda('menu.pauseResource', { type: 'workflow' })).toBe('Désactiver le workflow');
    expect(agenda('menu.pauseResource', { type: 'agent' })).toBe('Désactiver l’agent');
    expect(agenda('menu.pauseResource', { type: 'interface' })).toBe('Désactiver l’interface');
    expect(agenda('toasts.resourcePausedTitle', { type: 'interface' })).toBe('Interface désactivée');
    expect(actions('pauseResource', { type: 'interface' }))
      .toBe('Désactiver l’interface');
  });
});
