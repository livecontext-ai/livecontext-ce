'use client';

import { useMemo } from 'react';
import dynamic from 'next/dynamic';
import { useTranslations } from 'next-intl';
import { Table, X } from 'lucide-react';
import type { SnapshotTableData } from '@/components/data-table/types';
import { snapshotToDataTable } from '@/lib/datatable/snapshot-adapter';
import { type CreatorExampleKey, type BusinessExampleKey, type PersonaKey } from './personas';

const DataTable = dynamic(() => import('@/components/DataTable'), { ssr: false });
const FIELD_KEYS = ['first', 'second', 'third', 'fourth', 'fifth', 'sixth'] as const;
/** Exported: the recruiting section shows the same faces beside the same names, so the
 *  portrait a card carries and the portrait its table carries cannot drift apart. */
export const CANDIDATE_KEYS = ['alex', 'sarah', 'malik', 'emma', 'lucas', 'sofia'] as const;
export const CANDIDATE_PHOTOS = [
  '/landing/personas/recruiting-alex-morgan.webp',
  '/landing/personas/recruiting-candidate-2.webp',
  '/landing/personas/recruiting-candidate-3.webp',
  '/landing/personas/recruiting-candidate-4.webp',
  '/landing/personas/recruiting-candidate-5.webp',
  '/landing/personas/recruiting-candidate-6.webp',
] as const;

type RecruitingExampleKey = 'shortlist' | 'interview' | 'onboarding';
type SupportExampleKey = 'reply' | 'refund' | 'incident';
type SalesExampleKey = 'quote' | 'prospect' | 'followup';
type MarketingExampleKey = 'campaign' | 'seo' | 'listening';
type OpsExampleKey = 'report' | 'intake' | 'stock';
const SUPPORT_ROW_KEYS = ['first', 'second', 'third', 'fourth', 'fifth', 'sixth'] as const;
const MARKETING_ROW_KEYS = SUPPORT_ROW_KEYS;

/**
 * The six portraits the tables carry next to the row they belong to.
 *
 * <p>Every persona's table carries one, because "a table that holds images" is a claim
 * the product makes and a screenshot of text alone does not back. Recruiting drew them
 * first, hence the file names; they are generic portraits, reused here for the customer,
 * the account owner and the author of a mention.
 */
const TABLE_PORTRAITS = [
  '/landing/personas/recruiting-alex-morgan.webp',
  '/landing/personas/recruiting-candidate-2.webp',
  '/landing/personas/recruiting-candidate-3.webp',
  '/landing/personas/recruiting-candidate-4.webp',
  '/landing/personas/recruiting-candidate-5.webp',
  '/landing/personas/recruiting-candidate-6.webp',
] as const;

/** The image column itself, identical wherever it is added. */
function portraitColumn(translate: (key: string) => string) {
  return { path: 'data.photo', type: 'image', display: { label: translate('photo'), render: 'thumbnail', ratio: '1:1', imageFit: 'cover' } };
}

/**
 * Puts the portrait SECOND, right after the column that names the row.
 *
 * <p>First place belongs to what identifies the row: the week, the ticket, the quote, the
 * campaign. A face there reads as the subject of the table, when it is one attribute of a
 * row among others, and it pushed every scannable value one column to the right.
 */
function withPortrait(translate: (key: string) => string, columns: Record<string, unknown>) {
  const [lead, ...rest] = Object.keys(columns);
  return { [lead]: columns[lead], photo: portraitColumn(translate), ...Object.fromEntries(rest.map((key) => [key, columns[key]])) };
}

const CREATOR_POST_MEDIA: Record<CreatorExampleKey, string> = {
  product: '/landing/creator/creator-product-preview.webp',
  reel: '/landing/creator/creator-reel-preview.webp',
  cuts: '/landing/creator/creator-cuts-preview.webp',
  cafe: '/landing/creator/creator-cafe-preview.webp',
};

const CREATOR_POST_PLAN = [
  { example: 'product', platforms: ['Instagram', 'LinkedIn'], scheduledAt: '2026-09-15T08:30:00Z', status: 'scheduled' },
  { example: 'reel', platforms: ['Instagram', 'TikTok'], scheduledAt: '2026-09-15T17:45:00Z', status: 'approved' },
  { example: 'cuts', platforms: ['TikTok', 'YouTube Shorts'], scheduledAt: '2026-09-16T11:00:00Z', status: 'scheduled' },
  { example: 'cafe', platforms: ['Instagram', 'LinkedIn'], scheduledAt: '2026-09-17T07:45:00Z', status: 'published' },
  { example: 'product', platforms: ['Reddit', 'Twitter / X'], scheduledAt: '2026-09-18T12:15:00Z', status: 'approved' },
  { example: 'reel', platforms: ['YouTube Shorts', 'Instagram'], scheduledAt: '2026-09-19T16:30:00Z', status: 'scheduled' },
] as const satisfies readonly { example: CreatorExampleKey; platforms: readonly string[]; scheduledAt: string; status: 'approved' | 'scheduled' | 'published' }[];

export function buildCreatorSnapshot(
  selectedExample: CreatorExampleKey,
  translateCreator: (key: string) => string,
  translateCommon: (key: string) => string,
): SnapshotTableData {
  const orderedPosts = [...CREATOR_POST_PLAN].sort((left, right) =>
    Number(right.example === selectedExample) - Number(left.example === selectedExample));
  const rows = orderedPosts.map((post, priority) => ({
    priority,
    data: {
      preview: CREATOR_POST_MEDIA[post.example],
      content: `${translateCreator(`examples.${post.example}.title`)} · ${post.platforms.join(' + ')}`,
      format: translateCreator(`examples.${post.example}.format`),
      platforms: [...post.platforms],
      scheduledAt: post.scheduledAt,
      status: translateCommon(`creator.statuses.${post.status}`),
    },
  }));
  const column = (key: string, type: 'text' | 'image' | 'select' | 'multi_select' | 'date', options: Record<string, unknown> = {}) => ({
    path: `data.${key}`,
    type,
    display: { label: translateCommon(`creator.columns.${key}`), ...options },
  });
  const selectOptions = (key: 'format' | 'status' | 'platforms') => Array.from(new Set(rows.flatMap(row => {
    const value = row.data[key];
    return Array.isArray(value) ? value : [value];
  }))).map(value => ({ label: String(value), value: String(value) }));

  return snapshotToDataTable({
    items: rows,
    mappingSpec: {
      preview: column('preview', 'image', { render: 'thumbnail', ratio: '9:16', imageFit: 'cover' }),
      content: column('content', 'text'),
      format: column('format', 'select', { options: selectOptions('format') }),
      platforms: column('platforms', 'multi_select', { options: selectOptions('platforms') }),
      scheduledAt: column('scheduledAt', 'date', { dateFormat: 'datetime' }),
      status: column('status', 'select', { options: selectOptions('status') }),
    },
  });
}

export function buildRecruitingSnapshot(
  scenario: RecruitingExampleKey,
  translate: (key: string) => string,
): SnapshotTableData {
  const rows = CANDIDATE_KEYS.map((candidate, priority) => {
    const base = {
      photo: CANDIDATE_PHOTOS[priority],
      candidate: translate(`recruiting.candidates.${candidate}.name`),
      role: translate(`recruiting.candidates.${candidate}.role`),
      status: translate(`recruiting.candidates.${candidate}.${scenario}Status`),
    };
    if (scenario === 'shortlist') return { priority, data: { ...base, score: [92, 88, 84, 79, 76, 72][priority], experience: translate(`recruiting.candidates.${candidate}.experience`), skills: translate(`recruiting.candidates.${candidate}.skills`).split('|') } };
    if (scenario === 'interview') return { priority, data: { ...base, stage: translate(`recruiting.candidates.${candidate}.stage`), interviewDate: ['2026-09-16T09:00:00Z', '2026-09-16T13:30:00Z', '2026-09-17T08:30:00Z', '2026-09-17T14:00:00Z', '2026-09-18T09:30:00Z', '2026-09-18T13:00:00Z'][priority], panel: translate(`recruiting.candidates.${candidate}.panel`) } };
    return { priority, data: { ...base, startDate: ['2026-10-05', '2026-10-12', '2026-10-19', '2026-11-02', '2026-11-09', '2026-11-16'][priority], progress: [85, 72, 63, 54, 41, 28][priority], manager: translate(`recruiting.candidates.${candidate}.manager`) } };
  });
  const column = (key: string, type: 'text' | 'image' | 'number' | 'progress' | 'select' | 'multi_select' | 'date', options: Record<string, unknown> = {}) => ({
    path: `data.${key}`,
    type,
    display: { label: translate(`recruiting.columns.${key}`), ...options },
  });
  const commonColumns = {
    candidate: column('candidate', 'text'),
    photo: column('photo', 'image', { render: 'thumbnail', ratio: '1:1', imageFit: 'cover' }),
    role: column('role', 'text'),
  };
  const optionsFor = (key: string) => Array.from(new Set(rows.flatMap(row => {
    const value = row.data[key];
    return Array.isArray(value) ? value : [value];
  }).filter((value): value is string => typeof value === 'string'))).map(value => ({ label: value, value }));
  const mappingSpec = scenario === 'shortlist' ? {
    ...commonColumns,
    score: column('score', 'progress', { min: 0, max: 100 }),
    experience: column('experience', 'text'),
    skills: column('skills', 'multi_select', { options: optionsFor('skills') }),
    status: column('status', 'select', { options: optionsFor('status') }),
  } : scenario === 'interview' ? {
    ...commonColumns,
    stage: column('stage', 'select', { options: optionsFor('stage') }),
    interviewDate: column('interviewDate', 'date', { dateFormat: 'datetime' }),
    panel: column('panel', 'text'),
    status: column('status', 'select', { options: optionsFor('status') }),
  } : {
    ...commonColumns,
    startDate: column('startDate', 'date', { dateFormat: 'date' }),
    progress: column('progress', 'progress', { min: 0, max: 100 }),
    manager: column('manager', 'text'),
    status: column('status', 'select', { options: optionsFor('status') }),
  };
  return snapshotToDataTable({ items: rows, mappingSpec });
}

export function buildOpsSnapshot(
  scenario: OpsExampleKey,
  translate: (key: string) => string,
): SnapshotTableData {
  const fieldKeys = scenario === 'report'
    ? ['week', 'owner', 'runs', 'onTime', 'hoursSaved', 'status']
    : scenario === 'intake'
      ? ['client', 'plan', 'owner', 'startDate', 'progress', 'status']
      : ['sku', 'product', 'onHand', 'threshold', 'supplier', 'status'];
  const rows = MARKETING_ROW_KEYS.map((rowKey, priority) => {
    const values = translate(`ops.rows.${scenario}.${rowKey}`).split('|');
    return { priority, data: { photo: TABLE_PORTRAITS[priority], ...Object.fromEntries(fieldKeys.map((field, index) => [field, values[index]])) } };
  });
  const selectFields = scenario === 'report'
    ? new Set(['status'])
    : scenario === 'intake'
      ? new Set(['plan', 'status'])
      : new Set(['supplier', 'status']);
  const dateFields = new Set(['startDate']);
  const mappingSpec = withPortrait(translate, Object.fromEntries(fieldKeys.map(field => {
    const isSelect = selectFields.has(field);
    const isDate = dateFields.has(field);
    const values = Array.from(new Set(rows.map(row => row.data[field])));
    return [field, {
      path: `data.${field}`,
      type: isDate ? 'date' : isSelect ? 'select' : 'text',
      display: {
        label: translate(`ops.columns.${field}`),
        ...(isDate ? { dateFormat: 'date' } : {}),
        ...(isSelect ? { options: values.map(value => ({ label: value, value })) } : {}),
      },
    }];
  })));
  return snapshotToDataTable({ items: rows, mappingSpec });
}

export function buildSupportSnapshot(
  scenario: SupportExampleKey,
  translate: (key: string) => string,
): SnapshotTableData {
  const fieldKeys = scenario === 'reply'
    ? ['ticket', 'client', 'subject', 'priority', 'sla', 'status']
    : scenario === 'refund'
      ? ['account', 'client', 'mrr', 'reason', 'action', 'status']
      : ['incident', 'severity', 'service', 'duration', 'impact', 'status'];
  const rows = SUPPORT_ROW_KEYS.map((rowKey, priority) => {
    const values = translate(`support.rows.${scenario}.${rowKey}`).split('|');
    return {
      priority,
      data: { photo: TABLE_PORTRAITS[priority], ...Object.fromEntries(fieldKeys.map((field, index) => [field, values[index]])) },
    };
  });
  const selectableFields = scenario === 'reply'
    ? new Set(['priority', 'status'])
    : scenario === 'refund'
      ? new Set(['action', 'status'])
      : new Set(['severity', 'status']);
  const mappingSpec = withPortrait(translate, Object.fromEntries(fieldKeys.map(field => {
    const isSelect = selectableFields.has(field);
    const values = Array.from(new Set(rows.map(row => row.data[field])));
    return [field, {
      path: `data.${field}`,
      type: isSelect ? 'select' : 'text',
      display: {
        label: translate(`support.columns.${field}`),
        ...(isSelect ? { options: values.map(value => ({ label: value, value })) } : {}),
      },
    }];
  })));
  return snapshotToDataTable({ items: rows, mappingSpec });
}

export function buildMarketingSnapshot(
  scenario: MarketingExampleKey,
  translate: (key: string) => string,
): SnapshotTableData {
  const fieldKeys = scenario === 'campaign'
    ? ['campaign', 'channel', 'audience', 'sendAt', 'reach', 'status']
    : scenario === 'seo'
      ? ['page', 'keyword', 'position', 'volume', 'action', 'status']
      : ['source', 'mention', 'sentiment', 'reach', 'postedAt', 'status'];
  const rows = MARKETING_ROW_KEYS.map((rowKey, priority) => {
    const values = translate(`marketing.rows.${scenario}.${rowKey}`).split('|');
    return { priority, data: { photo: TABLE_PORTRAITS[priority], ...Object.fromEntries(fieldKeys.map((field, index) => [field, values[index]])) } };
  });
  const selectFields = scenario === 'campaign'
    ? new Set(['channel', 'status'])
    : scenario === 'seo'
      ? new Set(['action', 'status'])
      : new Set(['source', 'sentiment', 'status']);
  const dateFields = new Set(['sendAt', 'postedAt']);
  const mappingSpec = withPortrait(translate, Object.fromEntries(fieldKeys.map(field => {
    const isSelect = selectFields.has(field);
    const isDate = dateFields.has(field);
    const values = Array.from(new Set(rows.map(row => row.data[field])));
    return [field, {
      path: `data.${field}`,
      type: isDate ? 'date' : isSelect ? 'select' : 'text',
      display: {
        label: translate(`marketing.columns.${field}`),
        ...(isDate ? { dateFormat: 'datetime' } : {}),
        ...(isSelect ? { options: values.map(value => ({ label: value, value })) } : {}),
      },
    }];
  })));
  return snapshotToDataTable({ items: rows, mappingSpec });
}

export function buildSalesSnapshot(
  scenario: SalesExampleKey,
  translate: (key: string) => string,
): SnapshotTableData {
  const fieldKeys = scenario === 'quote'
    ? ['quote', 'client', 'company', 'amount', 'validUntil', 'owner', 'status']
    : scenario === 'prospect'
      ? ['company', 'contact', 'score', 'source', 'stage', 'nextStep', 'owner']
      : ['company', 'contact', 'lastContact', 'nextFollowup', 'channel', 'owner', 'status'];
  const rows = FIELD_KEYS.map((rowKey, priority) => {
    const values = translate(`sales.rows.${scenario}.${rowKey}`).split('|');
    const data: Record<string, string | number> = { photo: TABLE_PORTRAITS[priority], ...Object.fromEntries(fieldKeys.map((field, index) => [field, values[index]])) };
    if (scenario === 'prospect') data.score = Number(String(data.score).split('/')[0]);
    return { priority, data };
  });
  const selectFields = scenario === 'quote'
    ? new Set(['status'])
    : scenario === 'prospect'
      ? new Set(['source', 'stage'])
      : new Set(['channel', 'status']);
  const dateFields = new Set(['validUntil', 'lastContact', 'nextFollowup']);
  const mappingSpec = withPortrait(translate, Object.fromEntries(fieldKeys.map(field => {
    const isSelect = selectFields.has(field);
    const isDate = dateFields.has(field);
    const isScore = field === 'score';
    const values = Array.from(new Set(rows.map(row => row.data[field])));
    return [field, {
      path: `data.${field}`,
      type: isScore ? 'progress' : isDate ? 'date' : isSelect ? 'select' : 'text',
      display: {
        label: translate(`sales.columns.${field}`),
        ...(isScore ? { min: 0, max: 100 } : {}),
        ...(isDate ? { dateFormat: 'date' } : {}),
        ...(isSelect ? { options: values.map(value => ({ label: String(value), value: String(value) })) } : {}),
      },
    }];
  })));
  return snapshotToDataTable({ items: rows, mappingSpec });
}

export default function WorkflowRecapPanel({ persona, example, onClose }: {
  persona: PersonaKey;
  example: CreatorExampleKey | BusinessExampleKey;
  onClose: () => void;
}) {
  const t = useTranslations(`PersonaLanding.personas.${persona}.workflowShowcase.examples.${example}.table`);
  const common = useTranslations('PersonaLanding.tablePreview');
  const creatorCopy = useTranslations('PersonaLanding.personas.creator.workflowShowcase');
  const snapshot = useMemo(() => {
    if (persona === 'creator') {
      return buildCreatorSnapshot(example as CreatorExampleKey, creatorCopy, common);
    }
    if (persona === 'ops') {
      return buildOpsSnapshot(example as OpsExampleKey, common);
    }
    if (persona === 'support') {
      return buildSupportSnapshot(example as SupportExampleKey, common);
    }
    if (persona === 'sales') {
      return buildSalesSnapshot(example as SalesExampleKey, common);
    }
    if (persona === 'marketing') {
      // Marketing used to fall through to the two-column field/value fallback below,
      // which is why its table looked nothing like the other personas'.
      return buildMarketingSnapshot(example as MarketingExampleKey, common);
    }

    return buildRecruitingSnapshot(example as RecruitingExampleKey, common);
  }, [persona, example, t, common, creatorCopy]);

  return <section className="workflow-recap-panel" role="region" aria-label={t('name')} data-testid="workflow-recap-panel" data-persona={persona} data-example={example}>
<style>{`.workflow-recap-panel{position:absolute;z-index:10;inset:16px 16px 16px auto;width:min(1240px,92%);display:flex;flex-direction:column;overflow:hidden;border:1px solid var(--border-color);border-radius:16px;background:var(--bg-primary);color:var(--text-primary);box-shadow:0 20px 80px #0003;animation:workflow-recap-in .35s ease both}.workflow-recap-panel .persona-recap-table{min-height:0;flex:1;overflow:hidden}.persona-recap-table table{min-width:1180px;table-layout:fixed!important}.persona-recap-table table :is(th,td){box-sizing:border-box}.persona-recap-table table th{padding-top:8px;padding-bottom:8px;white-space:nowrap}.persona-recap-table table td{padding-top:4px;padding-bottom:4px}.persona-recap-table table td>div{padding-left:4px!important;padding-right:4px!important}.persona-recap-table table :is(th,td):nth-child(1){width:140px;min-width:140px}.persona-recap-table table :is(th,td):nth-child(2){width:90px;min-width:90px}.persona-recap-table table :is(th,td):nth-child(3){width:220px;min-width:220px}.persona-recap-table table :is(th,td):nth-child(4){width:125px;min-width:125px}.persona-recap-table table :is(th,td):nth-child(5){width:115px;min-width:115px}.persona-recap-table table :is(th,td):nth-child(6){width:330px;min-width:330px}.persona-recap-table table :is(th,td):nth-child(7){width:160px;min-width:160px}.persona-recap-table table td:nth-child(6) button{padding-left:0;padding-right:0}.persona-recap-table table td:nth-child(6) button>span{flex-wrap:nowrap}.persona-recap-table table td:nth-child(6) button span span{padding-left:4px;padding-right:4px}.persona-recap-table table td:has(img) .h-14{height:40px;width:40px}.persona-recap-table table td:has(img) .opacity-0{display:none}.persona-recap-table table td img{height:40px;width:40px}.workflow-recap-panel[data-persona="sales"] .persona-recap-table table{min-width:1170px}.workflow-recap-panel[data-persona="sales"] .persona-recap-table table :is(th,td):nth-child(1){width:145px;min-width:145px}.workflow-recap-panel[data-persona="sales"] .persona-recap-table table :is(th,td):nth-child(2){width:90px;min-width:90px}.workflow-recap-panel[data-persona="sales"] .persona-recap-table table :is(th,td):nth-child(3){width:140px;min-width:140px}.workflow-recap-panel[data-persona="sales"] .persona-recap-table table :is(th,td):nth-child(4){width:155px;min-width:155px}.workflow-recap-panel[data-persona="sales"] .persona-recap-table table :is(th,td):nth-child(5){width:140px;min-width:140px}.workflow-recap-panel[data-persona="sales"] .persona-recap-table table :is(th,td):nth-child(6){width:150px;min-width:150px}.workflow-recap-panel[data-persona="sales"] .persona-recap-table table :is(th,td):nth-child(7){width:210px;min-width:210px}.workflow-recap-panel[data-persona="sales"] .persona-recap-table table :is(th,td):nth-child(8){width:140px;min-width:140px}.workflow-recap-panel[data-persona="sales"] .persona-recap-table tbody tr{height:56px}.workflow-recap-panel[data-persona="sales"][data-example="quote"] .persona-recap-table table :is(th,td):nth-child(1){width:120px;min-width:120px}.workflow-recap-panel[data-persona="sales"][data-example="quote"] .persona-recap-table table :is(th,td):nth-child(3){width:130px;min-width:130px}.workflow-recap-panel[data-persona="sales"][data-example="quote"] .persona-recap-table table :is(th,td):nth-child(4){width:210px;min-width:210px}.workflow-recap-panel[data-persona="sales"][data-example="quote"] .persona-recap-table table :is(th,td):nth-child(5){width:120px;min-width:120px}.workflow-recap-panel[data-persona="sales"][data-example="quote"] .persona-recap-table table :is(th,td):nth-child(6){width:160px;min-width:160px}.workflow-recap-panel[data-persona="sales"][data-example="quote"] .persona-recap-table table :is(th,td):nth-child(7){width:175px;min-width:175px}.workflow-recap-panel[data-persona="sales"][data-example="quote"] .persona-recap-table table :is(th,td):nth-child(8){width:165px;min-width:165px}.workflow-recap-panel[data-persona="sales"] .persona-recap-table table :is(th,td):nth-child(5){text-align:right}.workflow-recap-panel[data-persona="sales"][data-example="prospect"] .persona-recap-table table :is(th,td):nth-child(7){width:250px;min-width:250px}.workflow-recap-panel[data-persona="support"] .persona-recap-table table{min-width:1170px}.workflow-recap-panel[data-persona="support"] .persona-recap-table table :is(th,td):nth-child(1){width:120px;min-width:120px}.workflow-recap-panel[data-persona="support"] .persona-recap-table table :is(th,td):nth-child(2){width:90px;min-width:90px}.workflow-recap-panel[data-persona="support"] .persona-recap-table table :is(th,td):nth-child(3){width:165px;min-width:165px}.workflow-recap-panel[data-persona="support"] .persona-recap-table table :is(th,td):nth-child(7){width:195px;min-width:195px}.workflow-recap-panel[data-persona="support"][data-example="reply"] .persona-recap-table table :is(th,td):nth-child(4){width:300px;min-width:300px}.workflow-recap-panel[data-persona="support"][data-example="reply"] .persona-recap-table table :is(th,td):nth-child(5){width:145px;min-width:145px}.workflow-recap-panel[data-persona="support"][data-example="reply"] .persona-recap-table table :is(th,td):nth-child(6){width:155px;min-width:155px}.workflow-recap-panel[data-persona="support"][data-example="refund"] .persona-recap-table table :is(th,td):nth-child(4){width:110px;min-width:110px}.workflow-recap-panel[data-persona="support"][data-example="refund"] .persona-recap-table table :is(th,td):nth-child(5){width:250px;min-width:250px}.workflow-recap-panel[data-persona="support"][data-example="refund"] .persona-recap-table table :is(th,td):nth-child(6){width:240px;min-width:240px}.workflow-recap-panel[data-persona="support"][data-example="incident"] .persona-recap-table table :is(th,td):nth-child(3){width:140px;min-width:140px}.workflow-recap-panel[data-persona="support"][data-example="incident"] .persona-recap-table table :is(th,td):nth-child(4){width:210px;min-width:210px}.workflow-recap-panel[data-persona="support"][data-example="incident"] .persona-recap-table table :is(th,td):nth-child(5){width:165px;min-width:165px}.workflow-recap-panel[data-persona="support"][data-example="incident"] .persona-recap-table table :is(th,td):nth-child(6){width:250px;min-width:250px}.workflow-recap-panel[data-persona="support"] .persona-recap-table tbody tr{height:54px}.workflow-recap-panel[data-persona="ops"] .persona-recap-table table{min-width:930px}.workflow-recap-panel[data-persona="ops"] .persona-recap-table tbody tr{height:54px}.workflow-recap-panel[data-persona="ops"] .persona-recap-table table :is(th,td):nth-child(1){width:150px;min-width:150px}.workflow-recap-panel[data-persona="ops"] .persona-recap-table table :is(th,td):nth-child(2){width:90px;min-width:90px}.workflow-recap-panel[data-persona="ops"] .persona-recap-table table :is(th,td):nth-child(3){width:170px;min-width:170px}.workflow-recap-panel[data-persona="ops"] .persona-recap-table table :is(th,td):nth-child(4){width:130px;min-width:130px}.workflow-recap-panel[data-persona="ops"] .persona-recap-table table :is(th,td):nth-child(5){width:140px;min-width:140px}.workflow-recap-panel[data-persona="ops"] .persona-recap-table table :is(th,td):nth-child(6){width:120px;min-width:120px}.workflow-recap-panel[data-persona="ops"] .persona-recap-table table :is(th,td):nth-child(7){width:130px;min-width:130px}.workflow-recap-panel[data-persona="marketing"] .persona-recap-table table{min-width:930px}.workflow-recap-panel[data-persona="marketing"] .persona-recap-table table :is(th,td):nth-child(1){width:190px;min-width:190px}.workflow-recap-panel[data-persona="marketing"] .persona-recap-table tbody tr{height:54px}.workflow-recap-panel[data-persona="marketing"] .persona-recap-table table :is(th,td):nth-child(2){width:90px;min-width:90px}.workflow-recap-panel[data-persona="marketing"] .persona-recap-table table :is(th,td):nth-child(3){width:110px;min-width:110px}.workflow-recap-panel[data-persona="marketing"] .persona-recap-table table :is(th,td):nth-child(4){width:150px;min-width:150px}.workflow-recap-panel[data-persona="marketing"] .persona-recap-table table :is(th,td):nth-child(5){width:160px;min-width:160px}.workflow-recap-panel[data-persona="marketing"] .persona-recap-table table :is(th,td):nth-child(6){width:100px;min-width:100px}.workflow-recap-panel[data-persona="marketing"] .persona-recap-table table :is(th,td):nth-child(7){width:130px;min-width:130px}.workflow-recap-panel[data-persona="marketing"][data-example="seo"] .persona-recap-table table :is(th,td):nth-child(3){width:220px;min-width:220px}.workflow-recap-panel[data-persona="marketing"][data-example="seo"] .persona-recap-table table :is(th,td):nth-child(4){width:110px;min-width:110px}.workflow-recap-panel[data-persona="marketing"][data-example="listening"] .persona-recap-table table :is(th,td):nth-child(3){width:270px;min-width:270px}.workflow-recap-panel[data-persona="marketing"][data-example="listening"] .persona-recap-table table :is(th,td):nth-child(4){width:130px;min-width:130px}.workflow-recap-panel[data-persona="creator"] .persona-recap-table table{min-width:1160px}.workflow-recap-panel[data-persona="creator"] .persona-recap-table table :is(th,td):nth-child(1){width:105px;min-width:105px}.workflow-recap-panel[data-persona="creator"] .persona-recap-table table :is(th,td):nth-child(2){width:300px;min-width:300px}.workflow-recap-panel[data-persona="creator"] .persona-recap-table table :is(th,td):nth-child(3){width:170px;min-width:170px}.workflow-recap-panel[data-persona="creator"] .persona-recap-table table :is(th,td):nth-child(4){width:240px;min-width:240px}.workflow-recap-panel[data-persona="creator"] .persona-recap-table table :is(th,td):nth-child(5){width:200px;min-width:200px}.workflow-recap-panel[data-persona="creator"] .persona-recap-table table :is(th,td):nth-child(6){width:145px;min-width:145px}.workflow-recap-panel[data-persona="creator"] .persona-recap-table tbody tr{height:82px}.workflow-recap-panel[data-persona="creator"] .persona-recap-table table td:has(img) .h-14{height:60px;width:52px;border-radius:9px}.workflow-recap-panel[data-persona="creator"] .persona-recap-table table td img{height:60px;width:52px;object-fit:cover}.workflow-recap-panel .text-theme-primary{color:var(--text-primary)}.workflow-recap-panel .text-theme-secondary{color:var(--text-secondary)}.workflow-recap-panel .bg-theme-primary{background:var(--bg-primary)}.workflow-recap-panel .bg-theme-secondary{background:var(--bg-secondary)}@keyframes workflow-recap-in{from{opacity:0;transform:translateX(40px)}to{opacity:1;transform:translateX(0)}}@media(max-width:640px){.workflow-recap-panel{width:calc(100% - 48px)}}@media(prefers-reduced-motion:reduce){.workflow-recap-panel{animation:none}}`}</style>
    <header className="flex items-center gap-3 border-b px-4 py-2" style={{ borderColor: 'var(--border-color)' }}>
      <Table className="h-5 w-5 shrink-0" aria-hidden="true" />
      <div className="min-w-0 flex-1"><p className="text-xs" style={{ color: 'var(--text-muted)' }}>LiveContext | {t('status')}</p><h3 className="text-base font-semibold">{t('name')}</h3></div>
      <button type="button" onClick={onClose} className="rounded-lg p-2" aria-label={common('close')}><X className="h-5 w-5" /></button>
    </header>
    {persona !== 'creator' && <div className="flex items-center gap-3 px-4 py-2"><p className="text-sm leading-snug">{t('description')}</p></div>}
    <div className="persona-recap-table"><DataTable snapshotData={snapshot} readOnly embedded className="h-full" /></div>
  </section>;
}
