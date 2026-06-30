import { describe, it, expect } from 'vitest';
import { WORKFLOW_SUGGESTIONS } from '../workflowSuggestions';
import { TRIGGER_TYPES } from '../../components/inspector/nodeTypes';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';

/**
 * Strict parity guard for the new-workflow ("How can I help you?") screen i18n:
 * the suggestion chips (workflowBuilder.canvas.suggestions.<id>.label/.prompt) and
 * the trigger dropdown (workflowBuilder.canvas.triggerTypes.<id>). Every suggestion
 * id and every trigger id must resolve in ALL 6 locales, or a user sees the English
 * fallback (or, worse, a chip whose prompt is in the wrong language). Also enforces
 * the project-wide em-dash / en-dash ban on these strings.
 */
const LOCALES: Record<string, any> = { en, fr, de, es, pt, zh };
const canvasOf = (loc: any) => loc?.workflowBuilder?.canvas ?? {};
const BANNED = ['-', '-']; // em-dash, en-dash

describe('new-workflow screen i18n parity (suggestions + triggerTypes)', () => {
  for (const [locale, messages] of Object.entries(LOCALES)) {
    it(`${locale}.json has a non-empty label+prompt for every suggestion id`, () => {
      const suggestions = canvasOf(messages).suggestions ?? {};
      const missing: string[] = [];
      for (const { id } of WORKFLOW_SUGGESTIONS) {
        const entry = suggestions[id];
        if (typeof entry?.label !== 'string' || entry.label.length === 0) missing.push(`${id}.label`);
        if (typeof entry?.prompt !== 'string' || entry.prompt.length === 0) missing.push(`${id}.prompt`);
      }
      expect(missing, `${locale} missing: ${missing.join(', ')}`).toEqual([]);
    });

    it(`${locale}.json has a non-empty name for every trigger id`, () => {
      const triggerTypes = canvasOf(messages).triggerTypes ?? {};
      const missing = TRIGGER_TYPES
        .map((t) => t.id)
        .filter((id) => typeof triggerTypes[id] !== 'string' || triggerTypes[id].length === 0);
      expect(missing, `${locale} missing trigger names: ${missing.join(', ')}`).toEqual([]);
    });

    it(`${locale}.json suggestions/triggerTypes contain no em-dash or en-dash`, () => {
      const canvas = canvasOf(messages);
      const offenders: string[] = [];
      const scan = (label: string, value: unknown) => {
        if (typeof value === 'string' && BANNED.some((d) => value.includes(d))) offenders.push(label);
      };
      for (const [id, v] of Object.entries(canvas.suggestions ?? {})) {
        scan(`suggestions.${id}.label`, (v as any).label);
        scan(`suggestions.${id}.prompt`, (v as any).prompt);
      }
      for (const [id, v] of Object.entries(canvas.triggerTypes ?? {})) scan(`triggerTypes.${id}`, v);
      expect(offenders, `${locale} banned dash in: ${offenders.join(', ')}`).toEqual([]);
    });
  }

  it('all locales expose exactly the same suggestion + trigger ids as en.json', () => {
    const refSug = Object.keys(canvasOf(en).suggestions ?? {}).sort();
    const refTrg = Object.keys(canvasOf(en).triggerTypes ?? {}).sort();
    expect(refSug.length).toBe(WORKFLOW_SUGGESTIONS.length);
    expect(refTrg.length).toBe(TRIGGER_TYPES.length);
    for (const [locale, messages] of Object.entries(LOCALES)) {
      expect(Object.keys(canvasOf(messages).suggestions ?? {}).sort(), `${locale} suggestion ids`).toEqual(refSug);
      expect(Object.keys(canvasOf(messages).triggerTypes ?? {}).sort(), `${locale} trigger ids`).toEqual(refTrg);
    }
  });
});
