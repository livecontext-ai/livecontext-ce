// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { fixtureTranslations } from './fixtureTranslations';
import { afterEach, expect, it, vi } from 'vitest';
import type { DataTableProps } from '@/components/data-table/types';
import fr from '@/messages/fr.json';
import WorkflowRecapPanel from '../WorkflowRecapPanel';
import { BUSINESS_EXAMPLE_KEYS, CREATOR_EXAMPLE_KEYS, type PersonaKey } from '../personas';
const capture = vi.hoisted(() => ({ table: {} as DataTableProps }));
vi.mock('next/dynamic', () => ({ default: () => (props: DataTableProps) => { capture.table = props; return <div data-testid="native-data-table" />; } }));
afterEach(cleanup);
const examples = [...CREATOR_EXAMPLE_KEYS.map(example => ({ persona: 'creator' as PersonaKey, example })), ...Object.entries(BUSINESS_EXAMPLE_KEYS).flatMap(([persona, keys]) => keys.map(example => ({ persona: persona as PersonaKey, example })))];
it.each(examples)('shows all saved fields for $persona $example in the native offline table', ({ persona, example }) => {
 const close = vi.fn();
 const translate = fixtureTranslations('fr', fr.PersonaLanding.personas[persona].workflowShowcase);
 const t = (key: string) => translate(`examples.${example}.table.${key}`);
 render(<NextIntlClientProvider locale="fr" messages={fr} onError={error => { throw error; }}><WorkflowRecapPanel persona={persona} example={example} onClose={close} /></NextIntlClientProvider>);
 expect(screen.getByRole('region', { name: t('name') })).toBeInTheDocument();
 expect(capture.table.dataSourceId).toBeUndefined();
 expect(capture.table.workflowContext).toBeUndefined();
 expect(capture.table.readOnly).toBe(true);
 expect(capture.table.embedded).toBe(true);
 const snapshot = capture.table.snapshotData!;
 expect(snapshot.rows).toHaveLength(6);
 // Every table carries one image column: a table that holds images is a claim the
 // product makes, and a wall of text would not back it.
 expect(snapshot.columns.filter(column => column.type === 'image')).toHaveLength(1);
 if (persona === 'creator') {
  // Creator leads on the post it published: there, the image IS the record.
  expect(snapshot.columns[0]).toMatchObject({ field: 'data.preview', type: 'image' });
 } else {
  // Everywhere else the portrait is one attribute of the row, so first place stays with
  // the column that names the row and the face comes right after it.
  expect(snapshot.columns[0].type).not.toBe('image');
  expect(snapshot.columns[1]).toMatchObject({ field: 'data.photo', type: 'image', displayConfig: { render: 'thumbnail' } });
 }
 const images = snapshot.rows.map(row => String(row.data[persona === 'creator' ? 'preview' : 'photo']));
 expect(images.every(image => image.startsWith('/landing/'))).toBe(true);
 // Distinct images, so the column cannot pass by repeating one file six times. Creator
 // shows the four posts it has, the others one portrait per row.
 expect(new Set(images).size).toBeGreaterThanOrEqual(persona === 'creator' ? 4 : 6);
 if (persona === 'recruiting') {
  expect(snapshot.columns).toHaveLength(7);
  expect(snapshot.columns.map(column => column.field).slice(0, 3)).toEqual(['data.candidate', 'data.photo', 'data.role']);
  expect(snapshot.columns.map(column => column.header_name)).not.toContain(fr.PersonaLanding.tablePreview.field);
  expect(new Set(snapshot.rows.map(row => row.data.candidate)).size).toBe(6);
  expect(new Set(snapshot.rows.map(row => row.data.photo)).size).toBe(6);
  expect(snapshot.rows.map(row => row.data.candidate)).toEqual(['Alex Morgan', 'Sarah Chen', 'Malik Benali', 'Emma Dubois', 'Lucas Martin', 'Sofia Rossi']);
  expect(snapshot.rows.every(row => String(row.data.photo).startsWith('/landing/personas/'))).toBe(true);
 } else if (persona === 'support') {
  const expectedColumns = example === 'reply'
   ? ['Ticket', 'Client', 'Sujet', 'Priorité', 'SLA', 'Statut']
   : example === 'refund'
    ? ['Compte', 'Client', 'MRR', 'Motif', 'Action', 'Statut']
    : ['Incident', 'Sévérité', 'Service', 'Durée', 'Impact', 'Statut'];
  expect(snapshot.columns.map(column => column.header_name)).toEqual([expectedColumns[0], fr.PersonaLanding.tablePreview.photo, ...expectedColumns.slice(1)]);
  expect(snapshot.columns.map(column => column.header_name)).not.toContain(fr.PersonaLanding.tablePreview.field);
  expect(snapshot.rows).toHaveLength(6);
  expect(new Set(snapshot.rows.map(row => Object.values(row.data)[1])).size).toBe(6);
 } else if (persona === 'sales') {
  const expectedColumns = example === 'quote'
   ? ['Devis', 'Client', 'Entreprise', 'Montant', 'Valable jusqu’au', 'Responsable', 'Statut']
   : example === 'prospect'
    ? ['Entreprise', 'Contact', 'Score', 'Source', 'Étape', 'Prochaine action', 'Responsable']
    : ['Entreprise', 'Contact', 'Dernier contact', 'Prochaine relance', 'Canal', 'Responsable', 'Statut'];
  expect(snapshot.columns.map(column => column.header_name)).toEqual([expectedColumns[0], fr.PersonaLanding.tablePreview.photo, ...expectedColumns.slice(1)]);
  expect(snapshot.rows).toHaveLength(6);
  expect(new Set(snapshot.rows.map(row => Object.values(row.data)[1])).size).toBe(6);
  if (example === 'quote') {
   expect(snapshot.columns.find(column => column.field === 'data.validUntil')?.type).toBe('date');
   expect(snapshot.columns.find(column => column.field === 'data.status')?.type).toBe('select');
  } else if (example === 'prospect') {
   expect(snapshot.columns.find(column => column.field === 'data.score')?.type).toBe('progress');
   expect(snapshot.rows.every(row => typeof row.data.score === 'number')).toBe(true);
  } else {
   expect(snapshot.columns.filter(column => column.type === 'date')).toHaveLength(2);
   expect(snapshot.columns.find(column => column.field === 'data.channel')?.type).toBe('select');
  }
 } else if (persona === 'creator') {
  expect(snapshot.columns.map(column => column.header_name)).toEqual(['Aperçu', 'Publication', 'Format', 'Réseaux', 'Programmation', 'Statut']);
  expect(snapshot.columns[0]).toMatchObject({ field: 'data.preview', type: 'image', displayConfig: { render: 'thumbnail', ratio: '9:16' } });
  expect(snapshot.rows).toHaveLength(6);
  expect(snapshot.rows.every(row => Array.isArray(row.data.platforms))).toBe(true);
  expect(new Set(snapshot.rows.map(row => row.data.content)).size).toBe(6);
  expect(new Set(snapshot.rows.map(row => row.data.status))).toEqual(new Set(['Programmé', 'Validé', 'Publié']));
  expect(snapshot.rows[0].data.preview).toBe(`/landing/creator/creator-${example === 'product' ? 'product' : example === 'cafe' ? 'cafe' : example}-preview.webp`);
  expect(snapshot.rows.every(row => String(row.data.preview).startsWith('/landing/creator/'))).toBe(true);
  expect(screen.queryByRole('img')).not.toBeInTheDocument();
 } else if (persona === 'ops') {
  const ops = fr.PersonaLanding.tablePreview.ops.columns;
  const expectedColumns = example === 'report'
   ? [ops.week, ops.owner, ops.runs, ops.onTime, ops.hoursSaved, ops.status]
   : example === 'intake'
    ? [ops.client, ops.plan, ops.owner, ops.startDate, ops.progress, ops.status]
    : [ops.sku, ops.product, ops.onHand, ops.threshold, ops.supplier, ops.status];
  expect(snapshot.columns.map(column => column.header_name)).toEqual([expectedColumns[0], fr.PersonaLanding.tablePreview.photo, ...expectedColumns.slice(1)]);
  expect(snapshot.columns.find(column => column.field === 'data.status')?.type).toBe('select');
  expect(snapshot.columns.filter(column => column.type === 'date')).toHaveLength(example === 'intake' ? 1 : 0);
  expect(new Set(snapshot.rows.map(row => Object.values(row.data)[1])).size).toBe(6);
  expect(snapshot.rows.every(row => Object.values(row.data).every(value => String(value).trim() !== ''))).toBe(true);
 } else {
  // Marketing used to land in the generic two-column field/value fallback, which is why
  // its table was the only one that did not look like a table of records.
  const marketing = fr.PersonaLanding.tablePreview.marketing.columns;
  const expectedColumns = example === 'campaign'
   ? [marketing.campaign, marketing.channel, marketing.audience, marketing.sendAt, marketing.reach, marketing.status]
   : example === 'seo'
    ? [marketing.page, marketing.keyword, marketing.position, marketing.volume, marketing.action, marketing.status]
    : [marketing.source, marketing.mention, marketing.sentiment, marketing.reach, marketing.postedAt, marketing.status];
  expect(persona).toBe('marketing');
  expect(snapshot.columns.map(column => column.header_name)).toEqual([expectedColumns[0], fr.PersonaLanding.tablePreview.photo, ...expectedColumns.slice(1)]);
  expect(snapshot.columns.map(column => column.header_name)).not.toContain(fr.PersonaLanding.tablePreview.field);
  expect(snapshot.columns.filter(column => column.type === 'date')).toHaveLength(example === 'seo' ? 0 : 1);
  expect(snapshot.columns.find(column => column.field === 'data.status')?.type).toBe('select');
  expect(new Set(snapshot.rows.map(row => Object.values(row.data).join('|'))).size).toBe(6);
  expect(snapshot.rows.every(row => Object.values(row.data).every(value => String(value).trim() !== ''))).toBe(true);
 }
 if (persona === 'recruiting') expect(screen.queryByRole('img')).not.toBeInTheDocument();
 fireEvent.click(screen.getByRole('button', { name: fr.PersonaLanding.tablePreview.close }));
 expect(close).toHaveBeenCalledOnce();
});
