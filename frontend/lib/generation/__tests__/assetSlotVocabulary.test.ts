import { describe, expect, it } from 'vitest';
import { ASSET_ACCEPT, ASSET_PARAMS } from '../paramSpec';
import { GENERATE_FILE_PARAMS, GENERATE_PARAM_KEYS } from '@/app/workflows/builder/utils/generateParams';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';

/**
 * The file slots a model can declare, and the three surfaces that have to offer them.
 *
 * <p>A generation model states which files it takes; the studio composer, the generation dialog and
 * the `agent:generate` inspector each turn that into controls. They used to hold three private
 * copies of the list, and the failure mode was not an error: a surface whose copy was not updated
 * simply stopped offering the slot, so a model advertised a first frame and the reader had nowhere
 * to put one. Nothing failed, nothing was logged, and the parameter was reachable through the agent
 * and not through the app.
 *
 * <p>So the list lives in one place and this suite is what keeps the rest pointing at it.
 */
describe('the file slots every surface must agree on', () => {
  const locales: Record<string, any> = { en, fr, de, es, pt, zh };

  /** Every dictionary that puts a word on a file slot. Missing from one = that surface shows a raw name. */
  const dictionaries = (messages: any) => ({
    'generation.params': messages.generation.params,
    'generationModal.params': messages.generationModal.params,
    'workflowBuilder.forms.generate.params': messages.workflowBuilder.forms.generate.params,
  });

  /**
   * Every dictionary that explains a ROLE. Two surfaces read these under two namespaces, and a
   * missing key is silent in both: `assetRoleHint` returns null and the field simply stops saying
   * what the file is for.
   */
  const roleDictionaries = (messages: any) => ({
    'generation.assetRoles': messages.generation.assetRoles,
    'generation.assetRoleHints': messages.generation.assetRoleHints,
    'generationModal.assetRoles': messages.generationModal.assetRoles,
    'generationModal.assetRoleHints': messages.generationModal.assetRoleHints,
  });

  it('the builder renders a control for every slot the studio knows about', () => {
    expect(
      ASSET_PARAMS.filter((name) => !GENERATE_FILE_PARAMS.includes(name)),
      'a slot the generate node cannot render is a model half-configurable in a workflow',
    ).toEqual([]);
  });

  it('and knows of no slot the studio does not', () => {
    expect(
      GENERATE_FILE_PARAMS.filter((name) => !(ASSET_PARAMS as readonly string[]).includes(name)),
      'a slot only the builder knows is drawn as a text field in the studio, where a path is refused',
    ).toEqual([]);
  });

  it('every slot is in the vocabulary the inspector will draw at all', () => {
    // GENERATE_PARAM_KEYS decides which parameters get a control; a file slot outside it is
    // rendered by nothing, whatever GENERATE_FILE_PARAMS says about it.
    expect(
      GENERATE_FILE_PARAMS.filter((name) => !(GENERATE_PARAM_KEYS as readonly string[]).includes(name)),
    ).toEqual([]);
  });

  it('every slot says what its picker accepts, so the reader is not shown every file they own', () => {
    expect(ASSET_PARAMS.filter((name) => !ASSET_ACCEPT[name])).toEqual([]);
  });

  it('every slot is named in all six locales, in each dictionary that labels one', () => {
    for (const [locale, messages] of Object.entries(locales)) {
      for (const [where, dictionary] of Object.entries(dictionaries(messages))) {
        expect(
          ASSET_PARAMS.filter((name) => !dictionary[name]),
          `${locale} ${where} is missing a file slot, which renders as its raw contract name`,
        ).toEqual([]);
      }
    }
  });

  it('every role is named AND explained in both dictionaries, in all six locales', () => {
    // The dialog reads `generationModal`, the studio and the inspector read `generation`. A key
    // present in one and missing in the other is a surface that silently stops explaining itself.
    const roles = Object.keys((en as any).generation.assetRoles);
    for (const [locale, messages] of Object.entries(locales)) {
      for (const [where, dictionary] of Object.entries(roleDictionaries(messages))) {
        expect(
          roles.filter((role) => !dictionary?.[role]),
          `${locale} ${where} is missing a role`,
        ).toEqual([]);
      }
    }
  });

  it('names both pairing rules in every locale, since a refusal is what they prevent', () => {
    for (const [locale, messages] of Object.entries(locales)) {
      for (const namespace of ['generation', 'generationModal']) {
        expect(
          ['goesWith', 'notWith'].filter((key) => !messages[namespace]?.assetPairing?.[key]),
          `${locale} ${namespace}.assetPairing is incomplete`,
        ).toEqual([]);
      }
    }
  });

  it('names every string the attachment menu prints, in every locale', () => {
    // The studio's menu is the surface where the slot is chosen, and a missing key there
    // renders the key path itself into the menu. Its tests use a stub translator, so nothing
    // else in the suite would notice.
    for (const [locale, messages] of Object.entries(locales)) {
      expect(
        ['chooseRole', 'goesWith', 'notWith', 'addFile', 'allSlotsFull', 'fileWanted']
          .filter((key) => !messages.studio?.composer?.[key]),
        `${locale} studio.composer is missing an attachment string`,
      ).toEqual([]);
      if (locale === 'en') continue;
      for (const key of ['chooseRole', 'goesWith', 'notWith', 'fileWanted']) {
        expect(
          messages.studio.composer[key],
          `${locale} studio.composer.${key} was left as the English string`,
        ).not.toBe((en as any).studio.composer[key]);
      }
    }
  });

  it('translates every ROLE word and pairing line too, not only the parameter names', () => {
    // Same guard as the one below, over the dictionaries that say what a file is FOR. A key
    // copied from en is present and useless, and it is the half a reader actually reads.
    for (const [locale, messages] of Object.entries(locales)) {
      if (locale === 'en') continue;
      for (const [where, dictionary] of Object.entries(roleDictionaries(messages))) {
        const reference = where.split('.').reduce((node: any, part) => node?.[part], en as any);
        for (const key of Object.keys(reference ?? {})) {
          expect(
            dictionary?.[key],
            `${locale} ${where}.${key} was left as the English string`,
          ).not.toBe(reference[key]);
        }
      }
      for (const namespace of ['generation', 'generationModal']) {
        for (const key of ['goesWith', 'notWith']) {
          expect(
            messages[namespace]?.assetPairing?.[key],
            `${locale} ${namespace}.assetPairing.${key} was left as the English string`,
          ).not.toBe((en as any)[namespace].assetPairing[key]);
        }
      }
    }
  });

  it('translates every name, in every locale, rather than shipping the English one', () => {
    // Checked on all five, not only French: a key copied from en is present and useless, and the
    // one-locale version of this guard let de/es/pt/zh ship the English string and pass.
    for (const [locale, messages] of Object.entries(locales)) {
      if (locale === 'en') continue;
      for (const name of ASSET_PARAMS) {
        expect(
          messages.generation.params[name],
          `${locale}.${name} was left as the English string`,
        ).not.toBe((en as any).generation.params[name]);
      }
    }
  });
});
