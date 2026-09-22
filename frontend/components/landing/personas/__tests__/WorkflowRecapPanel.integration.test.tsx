// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createTranslator, NextIntlClientProvider } from 'next-intl';
import { afterEach, expect, it, vi } from 'vitest';
import fr from '@/messages/fr.json';
import DataTable from '@/components/DataTable';
import { buildCreatorSnapshot, buildRecruitingSnapshot, buildSalesSnapshot, buildSupportSnapshot } from '../WorkflowRecapPanel';

vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/hooks/useAuthGuard', () => ({ useAuthGuard: () => ({ isLoading: true }) }));
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
it('renders six candidates with native image cells in the actual table without authentication or HTTP', async () => {
 const fetch = vi.fn(); vi.stubGlobal('fetch',fetch);
 vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} unobserve() {} });
 const translate = createTranslator({ locale: 'fr', messages: fr, namespace: 'PersonaLanding.tablePreview' });
 const snapshot = buildRecruitingSnapshot('shortlist', translate);
 render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><NextIntlClientProvider locale="fr" messages={fr}><DataTable snapshotData={snapshot} readOnly embedded /></NextIntlClientProvider></QueryClientProvider>);
 const table = await screen.findByRole('table');
 for (const name of ['Alex Morgan', 'Sarah Chen', 'Malik Benali', 'Emma Dubois', 'Lucas Martin', 'Sofia Rossi']) expect(within(table).getByText(name, { exact: true })).toBeVisible();
 expect(within(table).getAllByRole('img')).toHaveLength(6);
 expect(within(table).getAllByText('Très bon profil', { exact: true }).length).toBeGreaterThan(0);
 expect(within(table).queryByText('Aucune option', { exact: true })).not.toBeInTheDocument();
 expect(within(table).getAllByRole('row')).toHaveLength(7);
 expect(fetch).not.toHaveBeenCalled();
});

it('renders six creator posts with large native media previews without authentication or HTTP', async () => {
 const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
 vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} unobserve() {} });
 const common = createTranslator({ locale: 'fr', messages: fr, namespace: 'PersonaLanding.tablePreview' });
 const creator = createTranslator({ locale: 'fr', messages: fr, namespace: 'PersonaLanding.personas.creator.workflowShowcase' });
 const snapshot = buildCreatorSnapshot('product', creator, common);
 render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><NextIntlClientProvider locale="fr" messages={fr}><DataTable snapshotData={snapshot} readOnly embedded /></NextIntlClientProvider></QueryClientProvider>);
 const table = await screen.findByRole('table');
 expect(within(table).getAllByRole('row')).toHaveLength(7);
 expect(within(table).getAllByRole('img')).toHaveLength(6);
 expect(within(table).getByText('Un nouveau rituel du matin · Instagram + LinkedIn', { exact: true })).toBeVisible();
 for (const header of ['Aperçu', 'Publication', 'Format', 'Réseaux', 'Programmation', 'Statut']) {
  expect(within(table).getByText(header, { exact: true })).toBeVisible();
 }
 expect(fetch).not.toHaveBeenCalled();
});

it.each([
 ['reply', ['#1042', '#1043', '#1044', '#1045', '#1046', '#1047'], ['Ticket', 'Client', 'Sujet', 'Priorité', 'SLA', 'Statut']],
 ['refund', ['#2087', '#2088', '#2089', '#2090', '#2091', '#2092'], ['Compte', 'Client', 'MRR', 'Motif', 'Action', 'Statut']],
 ['incident', ['INC-3175', 'INC-3176', 'INC-3177', 'INC-3178', 'INC-3179', 'INC-3180'], ['Incident', 'Sévérité', 'Service', 'Durée', 'Impact', 'Statut']],
] as const)('renders six professional support records for %s in the actual table', async (scenario, recordIds, headers) => {
 const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
 vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} unobserve() {} });
 const common = createTranslator({ locale: 'fr', messages: fr, namespace: 'PersonaLanding.tablePreview' });
 const snapshot = buildSupportSnapshot(scenario, common);
 render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><NextIntlClientProvider locale="fr" messages={fr}><DataTable snapshotData={snapshot} readOnly embedded /></NextIntlClientProvider></QueryClientProvider>);
 const table = await screen.findByRole('table');
 expect(within(table).getAllByRole('row')).toHaveLength(7);
 for (const header of headers) expect(within(table).getByText(header, { exact: true })).toBeVisible();
 for (const recordId of recordIds) expect(within(table).getByText(recordId, { exact: true })).toBeVisible();
 expect(fetch).not.toHaveBeenCalled();
});

it.each([
 ['quote', ['Devis', 'Client', 'Entreprise', 'Montant', 'Valable jusqu’au', 'Responsable', 'Statut']],
 ['prospect', ['Entreprise', 'Contact', 'Score', 'Source', 'Étape', 'Prochaine action', 'Responsable']],
 ['followup', ['Entreprise', 'Contact', 'Dernier contact', 'Prochaine relance', 'Canal', 'Responsable', 'Statut']],
] as const)('renders six professional sales records for %s in the actual table', async (scenario, headers) => {
 const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
 vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} unobserve() {} });
 const common = createTranslator({ locale: 'fr', messages: fr, namespace: 'PersonaLanding.tablePreview' });
 const snapshot = buildSalesSnapshot(scenario, common);
 render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><NextIntlClientProvider locale="fr" messages={fr}><DataTable snapshotData={snapshot} readOnly embedded /></NextIntlClientProvider></QueryClientProvider>);
 const table = await screen.findByRole('table');
 expect(within(table).getAllByRole('row')).toHaveLength(7);
 for (const header of headers) expect(within(table).getByText(header, { exact: true })).toBeVisible();
 expect(new Set(snapshot.rows.map(row => Object.values(row.data)[0])).size).toBe(6);
 expect(fetch).not.toHaveBeenCalled();
});
