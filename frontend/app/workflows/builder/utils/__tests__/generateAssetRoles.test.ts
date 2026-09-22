import { describe, it, expect } from 'vitest';
import { GENERATE_ASSET_ROLES } from '../generateParams';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';

/**
 * The file-slot label is built from a translation key ending in the model's
 * declared ROLE, so the two lists have to agree.
 *
 * <p>What the role buys is not decoration: "input_image" says an image goes
 * here, and only the role says whether that image comes back edited, becomes
 * the clip's first frame, or merely lends its style. Filling the wrong one
 * costs a paid call.
 *
 * <p>A role the locale files do not know renders its own key path onto the
 * field ("assetRoles.last_frame"), so the guard in the form falls back to the
 * parameter name instead. That fallback is correct and silent, which is exactly
 * why the lists need a test: adding a fifth AssetRole on the backend would
 * quietly downgrade every label for it with nothing failing.
 */
describe('generate asset roles', () => {
  const locales: Record<string, any> = { en, fr, de, es, pt, zh };
  const rolesOf = (m: any) => Object.keys(m.generation.assetRoles);
  const translated = rolesOf(en);

  it('every role the form will accept has a translation', () => {
    expect(
      GENERATE_ASSET_ROLES.filter((role) => !translated.includes(role)),
      'a role with no translation falls back to the parameter name, losing what the file is FOR',
    ).toEqual([]);
  });

  it('and every translated role is one the form accepts', () => {
    expect(
      translated.filter((role) => !GENERATE_ASSET_ROLES.includes(role)),
      'a translation nothing can reach is a role the form silently refuses to name',
    ).toEqual([]);
  });

  it('names the five the backend enum declares', () => {
    // GenerationSpec.AssetRole. Listed rather than derived: this file is the
    // record of what the two sides agreed on, and a sixth role must be a
    // deliberate edit here, in the locale files and in the enum together.
    expect([...GENERATE_ASSET_ROLES].sort())
      .toEqual(['first_frame', 'last_frame', 'mask', 'reference', 'source']);
  });

  it('says what each role DOES, in all six locales', () => {
    // The name of a slot says which file goes in it; only the hint says what
    // happens to the file. "First frame" and "Last frame" are two images of the
    // same scene and two different videos, and on a model that takes both, this
    // sentence is the entire difference the reader can see.
    for (const [name, messages] of Object.entries(locales)) {
      expect(
        GENERATE_ASSET_ROLES.filter((role) => !(messages.generation.assetRoleHints || {})[role]),
        `${name} is missing what a role does`,
      ).toEqual([]);
    }
  });

  it('translates each hint rather than copying the English one', () => {
    for (const role of GENERATE_ASSET_ROLES) {
      expect(
        (fr as any).generation.assetRoleHints[role],
        `fr.${role} hint was left as the English string`,
      ).not.toBe((en as any).generation.assetRoleHints[role]);
    }
  });

  it('has the role translated in ALL SIX locales, not only the reference one', () => {
    // Reading en alone made this a one-locale guard while the repo rule is that
    // every key exists in all six with a real translation: a role added to en and
    // forgotten elsewhere would have passed, and those readers would see the
    // English fallback on a label whose whole job is to say what the file is FOR.
    for (const [name, messages] of Object.entries(locales)) {
      expect(
        GENERATE_ASSET_ROLES.filter((role) => !rolesOf(messages).includes(role)),
        `${name} is missing a role the form will accept`,
      ).toEqual([]);
    }
  });

  it('translates each role differently from the reference locale, so none is a copy', () => {
    // A key copied from en is present and useless. Checked on French, which
    // shares no word with English for any of the four.
    for (const role of GENERATE_ASSET_ROLES) {
      expect(
        (fr as any).generation.assetRoles[role],
        `fr.${role} was left as the English string`,
      ).not.toBe((en as any).generation.assetRoles[role]);
    }
  });
});
