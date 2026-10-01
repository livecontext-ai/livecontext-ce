import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

import { PARTNER_TERMS_EN } from '../en';
import { PARTNER_TERMS_FR } from '../fr';
import type { PartnerTermsBlock, PartnerTermsDocument } from '../types';
import {
  PARTNER_TERMS_FINGERPRINT,
  PARTNER_TERMS_PATH,
  PARTNER_TERMS_PATH_FR,
  PARTNER_TERMS_VERSION,
  partnerTermsPathFor,
} from '@/lib/partners/terms';
import { MAX_AGE_MS } from '@/lib/lifecycle/pendingRewardCode';

/**
 * The Partner Program Terms are a contract: a figure in Schedule 1 that drifts from what the
 * backend actually applies is a promise we break (or a claim we lose). Nothing type-checks the
 * text against the Java defaults, so these tests read the Java sources themselves, the same way
 * the other wire-format guards do, pin the two language versions to each other, and freeze the
 * text of the published version. (The pages themselves are tested under app/legal/partners.)
 */

const SERVICE_DIR = join(
  __dirname,
  '../../../../../backend/auth-service/src/main/java/com/apimarketplace/auth/service',
);

/** The default of `@Value("${<key>:<default>}")` in a backend service, read from its source. */
function javaDefault(file: string, key: string): string {
  const source = readFileSync(join(SERVICE_DIR, file), 'utf8');
  const escaped = key.replace(/[.*+?^${}()|[\]\\-]/g, '\\$&');
  const match = source.match(new RegExp(`\\$\\{${escaped}:([^}]+)\\}`));
  expect(match, `${key} has no default in ${file}`).not.toBeNull();
  return match![1];
}

/** A `public static final String <name> = "..."` of a backend service, read from its source. */
function javaConstant(file: string, name: string): string {
  const match = readFileSync(join(SERVICE_DIR, file), 'utf8').match(new RegExp(`\\b${name}\\s*=\\s*"([^"]+)"`));
  expect(match, `${name} not found in ${file}`).not.toBeNull();
  return match![1];
}

const backend = {
  termsVersion: javaConstant('PartnerTermsService.java', 'CURRENT_VERSION'),
  termsFingerprint: javaConstant('PartnerTermsService.java', 'CURRENT_FINGERPRINT'),
  silverPercent: Number(javaDefault('PartnerTierService.java', 'reward.partner.commission-bps')) / 100,
  goldPercent: Number(javaDefault('PartnerTierService.java', 'reward.partner.tier.gold-bps')) / 100,
  platinumPercent: Number(javaDefault('PartnerTierService.java', 'reward.partner.tier.platinum-bps')) / 100,
  goldThreshold: Number(javaDefault('PartnerTierService.java', 'reward.partner.tier.gold-threshold-minor')) / 100,
  platinumThreshold: Number(javaDefault('PartnerTierService.java', 'reward.partner.tier.platinum-threshold-minor')) / 100,
  tierCurrency: javaDefault('PartnerTierService.java', 'reward.partner.tier.currency'),
  settleDays: Number(javaDefault('PartnerTierService.java', 'reward.partner.tier.settle-days')),
  founderUntil: javaDefault('PartnerTierService.java', 'reward.partner.founder-until'),
  commissionMonths: Number(javaDefault('PartnerProgramAdminService.java', 'reward.partner.commission-months')),
  holdDays: Number(javaDefault('PartnerProgramAdminService.java', 'reward.partner.hold-days')),
  newAccountDays: Number(javaDefault('RewardService.java', 'reward.partner.new-account-days')),
};
const linkKeptDays = MAX_AGE_MS / (24 * 60 * 60 * 1000);

/** French typesetting uses non-breaking spaces; compare on plain spaces. */
const plain = (text: string) => text.replace(/[\u00a0\u202f]/g, ' ');
const blockText = (b: PartnerTermsBlock) => (typeof b === 'string' ? b : b.list.join('\n'));
const clauseText = (doc: PartnerTermsDocument) => plain(doc.clauses.flatMap((c) => c.blocks.map(blockText)).join('\n'));
const scheduleRow = (doc: PartnerTermsDocument, label: RegExp) => {
  const row = doc.schedule.rows.find(([l]) => label.test(plain(l)));
  expect(row, `no Schedule 1 row matching ${label}`).toBeDefined();
  return plain(row![1]);
};
/** The whole document as a reader sees it, for the checks that must hold everywhere. */
const everyString = (doc: PartnerTermsDocument) => [
  doc.title, doc.metaDescription, doc.versionLabel, doc.effectiveLabel, doc.effectiveDate, doc.otherLanguageLabel,
  doc.prevailingNote, doc.contentsLabel, ...doc.intro,
  ...doc.clauses.flatMap((c) => [c.title, ...c.blocks.map(blockText)]),
  doc.schedule.title, doc.schedule.intro, ...doc.schedule.rows.flat(),
].join('\n');
/** The sub-clause numbers ("5.3") a clause declares, in order. */
const subClauses = (doc: PartnerTermsDocument) => doc.clauses.map((c) => c.blocks
  .filter((b): b is string => typeof b === 'string')
  .map((b) => b.match(/^(\d+\.\d+) /)?.[1])
  .filter((n): n is string => Boolean(n)));

describe('Partner Program Terms: the version a partner accepts', () => {
  it('is the one the backend requires, or every acceptance is refused as terms_outdated', () => {
    expect(PARTNER_TERMS_VERSION).toBe(backend.termsVersion);
  });

  it('has a frozen text: its fingerprint is the one the backend records with every acceptance', () => {
    const fingerprint = `sha256:${createHash('sha256').update(JSON.stringify([PARTNER_TERMS_EN, PARTNER_TERMS_FR])).digest('hex')}`;

    // A tripwire: a failure means the text changed. Once a version is published, do NOT just
    // update the fingerprint (partners who accepted it accepted the old words): publish a new
    // version (a new date in PARTNER_TERMS_VERSION and CURRENT_VERSION) with its fingerprint in
    // both places. Updating the fingerprint in place is only legitimate before publication.
    expect(fingerprint, `the text of version ${PARTNER_TERMS_VERSION} changed`).toBe(PARTNER_TERMS_FINGERPRINT);
    expect(backend.termsFingerprint).toBe(PARTNER_TERMS_FINGERPRINT);
  });

  it('is in force from the date the version names, in both languages', () => {
    const day = new Date(`${PARTNER_TERMS_VERSION}T00:00:00Z`);
    expect(Number.isNaN(day.getTime())).toBe(false);
    expect(PARTNER_TERMS_EN.effectiveDate)
      .toBe(new Intl.DateTimeFormat('en-US', { dateStyle: 'long', timeZone: 'UTC' }).format(day));
    expect(plain(PARTNER_TERMS_FR.effectiveDate).replace(/^1er /, '1 '))
      .toBe(new Intl.DateTimeFormat('fr-FR', { dateStyle: 'long', timeZone: 'UTC' }).format(day));
  });

  it('sends a French reader to the French text, which prevails, and everyone else to the English one', () => {
    expect(partnerTermsPathFor('fr')).toBe(PARTNER_TERMS_PATH_FR);
    expect(partnerTermsPathFor('en')).toBe(PARTNER_TERMS_PATH);
    expect(partnerTermsPathFor('de')).toBe(PARTNER_TERMS_PATH);
  });
});

describe('Partner Program Terms: Schedule 1 states what the backend applies', () => {
  const thousands = (n: number) => String(n).replace(/\B(?=(\d{3})+$)/g, ',');

  it('the tiers use the backend rates, thresholds and currency', () => {
    expect(backend.tierCurrency).toBe('usd');
    expect(scheduleRow(PARTNER_TERMS_EN, /^Tier rates/))
      .toBe(`Silver ${backend.silverPercent}%, Gold ${backend.goldPercent}%, Platinum ${backend.platinumPercent}%`);
    expect(scheduleRow(PARTNER_TERMS_EN, /^Tier thresholds/))
      .toBe(`Gold: USD ${thousands(backend.goldThreshold)}. Platinum: USD ${thousands(backend.platinumThreshold)}`);

    expect(scheduleRow(PARTNER_TERMS_FR, /^Taux par Palier/))
      .toBe(`Silver ${backend.silverPercent} %, Gold ${backend.goldPercent} %, Platinum ${backend.platinumPercent} %`);
    expect(scheduleRow(PARTNER_TERMS_FR, /^Seuils des Paliers/))
      .toBe(`Gold : ${thousands(backend.goldThreshold).replace(',', ' ')} USD. Platinum : ${thousands(backend.platinumThreshold).replace(',', ' ')} USD`);
  });

  it('the attribution period is both the account age the backend accepts and how long the link is kept', () => {
    expect(linkKeptDays).toBe(backend.newAccountDays);
    const n = backend.newAccountDays;
    expect(scheduleRow(PARTNER_TERMS_EN, /^Attribution period/)).toBe(
      `${n} days: a Partner Code or Link applies to accounts created less than ${n} days earlier, and a Partner Link is kept ${n} days in the browser`,
    );
    expect(scheduleRow(PARTNER_TERMS_FR, /^Période d’attribution/)).toBe(
      `${n} jours : un Code ou Lien Partenaire s’applique aux comptes créés depuis moins de ${n} jours, et un Lien Partenaire est conservé ${n} jours dans le navigateur`,
    );
  });

  it('the settlement, commission and holding periods are the backend ones', () => {
    expect(scheduleRow(PARTNER_TERMS_EN, /^Settlement period/)).toBe(`${backend.settleDays} days`);
    expect(scheduleRow(PARTNER_TERMS_EN, /^Commission period/)).toMatch(new RegExp(`^${backend.commissionMonths} months `));
    expect(scheduleRow(PARTNER_TERMS_EN, /^Holding period/)).toBe(`${backend.holdDays} days`);

    expect(scheduleRow(PARTNER_TERMS_FR, /^Période de consolidation/)).toBe(`${backend.settleDays} jours`);
    expect(scheduleRow(PARTNER_TERMS_FR, /^Durée de commissionnement/)).toMatch(new RegExp(`^${backend.commissionMonths} mois `));
    expect(scheduleRow(PARTNER_TERMS_FR, /^Période de retenue/)).toBe(`${backend.holdDays} jours`);
  });

  it('founding partners can be designated until the backend cut-off', () => {
    const cutOff = new Date(backend.founderUntil);
    expect(cutOff.toISOString()).toBe('2027-01-01T00:00:00.000Z');
    expect(scheduleRow(PARTNER_TERMS_EN, /^Founding partners/)).toBe('1 January 2027 (00:00 UTC)');
    expect(scheduleRow(PARTNER_TERMS_FR, /^Désignation des partenaires fondateurs/)).toBe('1er janvier 2027 (00 h 00 UTC)');
  });

  it('every "currently" figure in the clauses repeats Schedule 1, so the body never contradicts it', () => {
    const en = clauseText(PARTNER_TERMS_EN);
    const fr = clauseText(PARTNER_TERMS_FR);
    for (const [enText, frText] of [
      [`currently ${backend.newAccountDays} days after the account is created`, `actuellement ${backend.newAccountDays} jours après la création du compte`],
      [`(currently ${linkKeptDays} days)`, `(actuellement ${linkKeptDays} jours)`],
      [`(currently ${backend.commissionMonths} months)`, `(actuellement ${backend.commissionMonths} mois)`],
      [`(currently ${backend.holdDays} days)`, `(actuellement ${backend.holdDays} jours)`],
      [`(currently ${backend.settleDays} days)`, `(actuellement ${backend.settleDays} jours)`],
    ]) {
      expect(en).toContain(enText);
      expect(fr).toContain(frText);
    }
    // No stray "currently N" that the list above does not vouch for.
    expect(en.match(/currently \d+/g)).toHaveLength(5);
    expect(fr.match(/actuellement \d+/g)).toHaveLength(5);
  });
});

describe('Partner Program Terms: the two language versions are the same contract', () => {
  it('have the same clauses, in the same order, with the same sub-clauses', () => {
    expect(PARTNER_TERMS_FR.clauses.map((c) => c.number)).toEqual(PARTNER_TERMS_EN.clauses.map((c) => c.number));
    expect(PARTNER_TERMS_FR.clauses.map((c) => c.blocks.length)).toEqual(PARTNER_TERMS_EN.clauses.map((c) => c.blocks.length));
    expect(subClauses(PARTNER_TERMS_FR)).toEqual(subClauses(PARTNER_TERMS_EN));
    expect(PARTNER_TERMS_FR.schedule.rows).toHaveLength(PARTNER_TERMS_EN.schedule.rows.length);
  });

  it('number the clauses 1..N and each sub-clause under its own clause, in sequence', () => {
    PARTNER_TERMS_EN.clauses.forEach((clause, i) => {
      expect(clause.number).toBe(String(i + 1));
      const subs = subClauses(PARTNER_TERMS_EN)[i];
      subs.forEach((sub, j) => expect(sub).toBe(`${clause.number}.${j + 1}`));
    });
  });

  it('only refer to clauses that exist (a dangling reference is an ambiguity read against the drafter)', () => {
    for (const [doc, word] of [[PARTNER_TERMS_EN, 'clauses?'], [PARTNER_TERMS_FR, 'articles?']] as const) {
      const clauses = new Set(doc.clauses.map((c) => c.number));
      const subs = new Set(subClauses(doc).flat());
      const text = plain(everyString(doc));
      const refs = [...text.matchAll(new RegExp(`\\b${word} ((?:\\d+(?:\\.\\d+)?(?: ?\\([a-z]\\))?(?:, | and | or | et | ou )?)+)`, 'gi'))]
        .flatMap((m) => [...m[1].matchAll(/\d+(?:\.\d+)?/g)].map((n) => n[0]));
      expect(refs.length).toBeGreaterThan(20);
      // Articles of French codes and statutes (1218 du Code civil, 182 B du CGI) are above the last clause.
      const internal = refs.filter((r) => Number(r.split('.')[0]) <= doc.clauses.length);
      const dangling = internal.filter((r) => (r.includes('.') ? !subs.has(r) : !clauses.has(r)));
      expect(dangling, `${doc.lang}: dangling references`).toEqual([]);
    }
  });

  it('never use an em-dash or en-dash', () => {
    expect(everyString(PARTNER_TERMS_EN)).not.toMatch(/[\u2013\u2014]/);
    expect(everyString(PARTNER_TERMS_FR)).not.toMatch(/[\u2013\u2014]/);
  });

  it('say that the French version prevails, in both languages', () => {
    const last = (doc: PartnerTermsDocument) => blockText(doc.clauses[doc.clauses.length - 1].blocks.at(-1)!);
    expect(last(PARTNER_TERMS_EN)).toMatch(/French version prevails/);
    expect(plain(last(PARTNER_TERMS_FR))).toMatch(/version française prévaut/);
  });
});
