/**
 * The run summary under the pill's epoch timeline is one ICU message per locale: plural
 * forms where the language needs them, and the "running" / "stopped" parts only when
 * there are some. Formatted here with the same engine next-intl uses, since the
 * component tests mock the translator and never see these strings.
 */
import { describe, expect, it } from 'vitest';
import { IntlMessageFormat } from 'intl-messageformat';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';

const LOCALES = { en, fr, de, es, pt, zh } as const;

function summary(locale: keyof typeof LOCALES, values: { ok: number; failed: number; running: number; stopped: number }) {
  const message = (LOCALES[locale] as { workflow: { runInfo: { epochNav: { summary: string } } } }).workflow.runInfo.epochNav.summary;
  return String(new IntlMessageFormat(message, locale).format(values));
}

describe('epoch navigator summary', () => {
  it('leaves out "running" and "stopped" when there are none, in every locale', () => {
    for (const locale of Object.keys(LOCALES) as Array<keyof typeof LOCALES>) {
      const text = summary(locale, { ok: 2, failed: 1, running: 0, stopped: 0 });
      expect(text.split(' · '), `${locale}: ${text}`).toHaveLength(2);
    }
  });

  it('adds them when there are some, in every locale', () => {
    for (const locale of Object.keys(LOCALES) as Array<keyof typeof LOCALES>) {
      const text = summary(locale, { ok: 2, failed: 1, running: 1, stopped: 3 });
      expect(text.split(' · '), `${locale}: ${text}`).toHaveLength(4);
      expect(text, locale).toContain('3');
    }
  });

  it('agrees in number where the language inflects', () => {
    expect(summary('fr', { ok: 1, failed: 0, running: 0, stopped: 1 })).toBe('1 réussie · 0 en échec · 1 arrêtée');
    expect(summary('fr', { ok: 2, failed: 0, running: 0, stopped: 2 })).toBe('2 réussies · 0 en échec · 2 arrêtées');
    expect(summary('es', { ok: 1, failed: 1, running: 0, stopped: 0 })).toBe('1 correcta · 1 fallida');
    expect(summary('es', { ok: 2, failed: 2, running: 0, stopped: 0 })).toBe('2 correctas · 2 fallidas');
    expect(summary('pt', { ok: 1, failed: 0, running: 0, stopped: 1 })).toBe('1 concluída · 0 com falha · 1 interrompida');
    expect(summary('en', { ok: 2, failed: 1, running: 1, stopped: 0 })).toBe('2 succeeded · 1 failed · 1 running');
  });
});
