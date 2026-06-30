import React from "react";
import { Paperclip, FileText, Gauge, Hash, Image as ImageIcon, Link2, Star, Tag, ThumbsDown, ThumbsUp, CheckSquare, Calendar, CircleDot, Code, Mail, Phone, ExternalLink, ToggleLeft, ListChecks, ChevronDown, Upload, X, Binary } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';

import type { ColumnDisplayConfig, ColumnStructure, ColumnVisualType } from '@/types/data-sources';

export const splitPath = (path: string) => path.split('.').filter(Boolean);

export const getValueAtPath = (data: Record<string, any>, path: string) => {
  if (!path) return undefined;
  return splitPath(path).reduce<any | undefined>((acc, segment) => {
    if (acc === undefined || acc === null) return undefined;
    return acc[segment];
  }, data);
};

export const setValueAtPath = (data: Record<string, any>, path: string, newValue: any) => {
  const segments = splitPath(path);
  if (segments.length === 0) return data;
  const clone: Record<string, any> = { ...data };
  let current: Record<string, any> = clone;
  segments.forEach((segment, index) => {
    if (index === segments.length - 1) {
      current[segment] = newValue;
    } else {
      const next = current[segment];
      current[segment] =
        next && typeof next === 'object' && !Array.isArray(next) ? { ...next } : {};
      current = current[segment];
    }
  });
  return clone;
};

export type ColumnStylePreset = {
  id: string;
  label: string;
  description: string;
  visualType: ColumnVisualType;
  structure: ColumnStructure;
  icon: LucideIcon;
  display?: ColumnDisplayConfig;
  defaultValue?: any;
};

export const COLUMN_STYLE_PRESETS: ColumnStylePreset[] = [
  // Essentials
  {
    id: 'text',
    label: 'Rich text',
    description: 'Best for free-form notes or summaries.',
    visualType: 'text',
    structure: 'scalar',
    icon: FileText,
    display: { label: 'Text' },
    defaultValue: '',
  },
  {
    id: 'number',
    label: 'Number',
    description: 'Numeric field with smart formatting.',
    visualType: 'number',
    structure: 'scalar',
    icon: Hash,
    display: { label: 'Number', format: 'plain', decimals: 0 },
    defaultValue: 0,
  },
  {
    id: 'date',
    label: 'Date',
    description: 'Date picker with locale formatting.',
    visualType: 'date',
    structure: 'scalar',
    icon: Calendar,
    display: { dateFormat: 'date' },
  },
  {
    id: 'checkbox',
    label: 'Checkbox',
    description: 'True/false toggle for flags.',
    visualType: 'checkbox',
    structure: 'scalar',
    icon: ToggleLeft,
    display: { label: 'Checkbox' },
    defaultValue: false,
  },
  // Choices
  {
    id: 'select',
    label: 'Select',
    description: 'Interactive pills for statuses.',
    visualType: 'select',
    structure: 'scalar',
    icon: CheckSquare,
    display: { options: [
      { label: 'Pending', value: 'Pending', color: '#f97316' },
      { label: 'Ready', value: 'Ready', color: '#0ea5e9' },
      { label: 'Done', value: 'Done', color: '#22c55e' },
    ] },
    defaultValue: 'Pending',
  },
  {
    id: 'multi_select',
    label: 'Multi-select',
    description: 'Multiple tags or labels per row.',
    visualType: 'multi_select',
    structure: 'scalar',
    icon: ListChecks,
    display: { options: [
      { label: 'Design', value: 'design', color: '#8b5cf6' },
      { label: 'Dev', value: 'dev', color: '#3b82f6' },
    ] },
    defaultValue: [],
  },
  // Metrics
  {
    id: 'rating',
    label: 'Rating',
    description: 'Star rating to capture quality quickly.',
    visualType: 'rating',
    structure: 'scalar',
    icon: Star,
    display: { label: 'Rating', max: 5, color: '#fbbf24' },
    defaultValue: 0,
  },
  {
    id: 'sentiment',
    label: 'Thumbs',
    description: 'Binary thumbs up/down feedback.',
    visualType: 'sentiment',
    structure: 'scalar',
    icon: ThumbsUp,
    display: { label: 'Sentiment' },
    defaultValue: 'neutral',
  },
  {
    id: 'progress',
    label: 'Progress bar',
    description: 'Track completion with a visual bar.',
    visualType: 'progress',
    structure: 'scalar',
    icon: Gauge,
    display: { max: 100 },
    defaultValue: 0,
  },
  // Media
  {
    id: 'file',
    label: 'Attachment',
    description: 'Upload or link a document.',
    visualType: 'file',
    structure: 'scalar',
    icon: Paperclip,
    display: { label: 'Attachment' },
  },
  {
    id: 'image',
    label: 'Image preview',
    description: 'Responsive thumbnail for visuals.',
    visualType: 'image',
    structure: 'scalar',
    icon: ImageIcon,
    display: { ratio: '4:3', imageFit: 'cover' },
  },
  // Contact
  {
    id: 'email',
    label: 'Email',
    description: 'Email address with mailto link.',
    visualType: 'email',
    structure: 'scalar',
    icon: Mail,
    display: { label: 'Email' },
  },
  {
    id: 'phone',
    label: 'Phone',
    description: 'Phone number with dial link.',
    visualType: 'phone',
    structure: 'scalar',
    icon: Phone,
    display: { label: 'Phone' },
  },
  {
    id: 'url',
    label: 'URL',
    description: 'Clickable link opening in new tab.',
    visualType: 'url',
    structure: 'scalar',
    icon: ExternalLink,
    display: { label: 'URL' },
  },
  // Vector
  {
    id: 'vector',
    label: 'Vector',
    description: 'Embedding vector for similarity search (RAG).',
    visualType: 'vector',
    structure: 'scalar',
    icon: Binary,
    display: { dimension: 1536, metric: 'cosine' },
  },
];

export const PRESET_CATEGORIES = [
  { id: 'essentials', presetIds: ['text', 'number', 'date', 'checkbox'] },
  { id: 'choices', presetIds: ['select', 'multi_select'] },
  { id: 'metrics', presetIds: ['rating', 'sentiment', 'progress'] },
  { id: 'media', presetIds: ['file', 'image'] },
  { id: 'contact', presetIds: ['email', 'phone', 'url'] },
  { id: 'ai', presetIds: ['vector'] },
] as const;

export const COLUMN_TYPE_META: Record<ColumnVisualType, { label: string; icon: LucideIcon; badgeClass: string }> = {
  // 15 canonical types
  text: { label: 'Text', icon: FileText, badgeClass: 'bg-slate-100 text-slate-600' },
  number: { label: 'Number', icon: Hash, badgeClass: 'bg-amber-100 text-amber-700' },
  date: { label: 'Date', icon: Calendar, badgeClass: 'bg-blue-100 text-blue-700' },
  checkbox: { label: 'Checkbox', icon: ToggleLeft, badgeClass: 'bg-emerald-100 text-emerald-700' },
  select: { label: 'Select', icon: CheckSquare, badgeClass: 'bg-cyan-100 text-cyan-700' },
  multi_select: { label: 'Multi-select', icon: ListChecks, badgeClass: 'bg-fuchsia-100 text-fuchsia-700' },
  rating: { label: 'Rating', icon: Star, badgeClass: 'bg-amber-100 text-amber-700' },
  sentiment: { label: 'Sentiment', icon: ThumbsUp, badgeClass: 'bg-pink-100 text-pink-700' },
  progress: { label: 'Progress', icon: Gauge, badgeClass: 'bg-lime-100 text-lime-700' },
  file: { label: 'Attachment', icon: Paperclip, badgeClass: 'bg-sky-100 text-sky-700' },
  image: { label: 'Image', icon: ImageIcon, badgeClass: 'bg-indigo-100 text-indigo-700' },
  email: { label: 'Email', icon: Mail, badgeClass: 'bg-teal-100 text-teal-700' },
  phone: { label: 'Phone', icon: Phone, badgeClass: 'bg-violet-100 text-violet-700' },
  url: { label: 'URL', icon: ExternalLink, badgeClass: 'bg-blue-100 text-blue-700' },
  vector: { label: 'Vector', icon: Binary, badgeClass: 'bg-purple-100 text-purple-700' },
  // Deprecated aliases (kept for backward compat display)
  boolean: { label: 'Checkbox', icon: ToggleLeft, badgeClass: 'bg-emerald-100 text-emerald-700' },
  badge: { label: 'Select', icon: Tag, badgeClass: 'bg-orange-100 text-orange-700' },
  tags: { label: 'Multi-select', icon: CircleDot, badgeClass: 'bg-fuchsia-100 text-fuchsia-700' },
  link: { label: 'URL', icon: Link2, badgeClass: 'bg-blue-100 text-blue-700' },
  json: { label: 'Text', icon: Code, badgeClass: 'bg-purple-100 text-purple-700' },
  code: { label: 'Text', icon: Code, badgeClass: 'bg-slate-100 text-slate-700' },
};

export const getDisplayOptions = (display?: ColumnDisplayConfig) => {
  const options = display?.options;
  if (!options) return [] as Array<{ label: string; value: string; color?: string }>;
  if (Array.isArray(options)) {
    return options.map(option =>
      typeof option === 'string' ? { label: option, value: option } : option
    );
  }
  return [];
};

export const renderPresetPreview = (preset: ColumnStylePreset) => {
  switch (preset.visualType) {
    case 'rating': {
      const max = (preset.display?.max as number) ?? 5;
      const filled = Math.ceil(max * 0.6);
      return (
        <div className="flex items-center gap-1 text-amber-500">
          {Array.from({ length: max }).map((_, index) => (
            <Star
              key={`${preset.id}-star-${index}`}
              className={`h-4 w-4 ${index < filled ? 'fill-current' : 'fill-transparent opacity-30'}`}
            />
          ))}
        </div>
      );
    }
    case 'sentiment':
      return (
        <div className="flex items-center gap-3">
          <span className="flex h-9 w-9 items-center justify-center rounded-full bg-emerald-100 dark:bg-emerald-900/40 ring-2 ring-black/30 dark:ring-white/30">
            <ThumbsUp className="h-5 w-5 text-black dark:text-white" />
          </span>
          <span className="flex h-9 w-9 items-center justify-center rounded-full bg-slate-100 dark:bg-slate-800 opacity-40">
            <ThumbsDown className="h-5 w-5 text-slate-400 dark:text-slate-500" />
          </span>
        </div>
      );
    case 'file':
      return (
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2 rounded-lg bg-sky-50 dark:bg-sky-900/30 px-2.5 py-1.5">
            <Paperclip className="h-3.5 w-3.5 text-sky-600 dark:text-sky-400" />
            <span className="text-xs font-medium text-sky-700 dark:text-sky-300">report.pdf</span>
          </div>
          <div className="flex items-center gap-1 text-theme-secondary">
            <Upload className="h-3 w-3" />
            <span className="text-[10px]">Upload</span>
          </div>
        </div>
      );
    case 'image':
      return (
        <div className="mx-auto h-12 w-12 overflow-hidden rounded-full border border-slate-200 dark:border-slate-700/50 bg-theme-secondary">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src="/examples/user-photo.jpg" alt="preview" className="h-full w-full object-cover" />
        </div>
      );
    case 'select': {
      const selectOptions = getDisplayOptions(preset.display);
      const active = selectOptions[0];
      return (
        <div className="inline-flex items-center gap-2 rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-2.5 min-w-[160px]">
          {active && (
            <span
              className="rounded-full px-2.5 py-0.5 text-xs font-semibold text-white"
              style={{ backgroundColor: active.color || '#0ea5e9' }}
            >
              {active.label}
            </span>
          )}
          <ChevronDown className="h-4 w-4 text-theme-secondary opacity-50 ml-auto" />
        </div>
      );
    }
    case 'multi_select': {
      const msOptions = getDisplayOptions(preset.display);
      return (
        <div className="inline-flex items-center gap-1.5 rounded-xl border border-theme bg-[var(--bg-primary)] px-3 py-2 min-w-[160px] flex-wrap">
          {msOptions.map(option => (
            <span
              key={option.value}
              className="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium text-white"
              style={{ backgroundColor: option.color || '#8b5cf6' }}
            >
              {option.label}
              <X className="h-2.5 w-2.5 opacity-60" />
            </span>
          ))}
          <ChevronDown className="h-4 w-4 text-theme-secondary opacity-50 ml-auto" />
        </div>
      );
    }
    case 'progress':
      return (
        <div className="flex w-full flex-col items-center gap-1">
          <div className="h-2 w-full rounded-full bg-slate-200 dark:bg-slate-700">
            <div className="h-full w-2/3 rounded-full bg-lime-500 transition-all" />
          </div>
          <span className="text-[10px] font-semibold text-theme-secondary">67%</span>
        </div>
      );
    case 'number':
      return (
        <div className="flex items-baseline gap-1.5">
          <span className="text-sm font-semibold text-theme-primary">1,250</span>
          <span className="text-[10px] text-theme-secondary">.00</span>
        </div>
      );
    case 'date':
      return (
        <div className="flex items-center gap-1.5 text-sm text-theme-primary">
          <Calendar className="h-3.5 w-3.5 text-theme-secondary" />
          <span>03/15/2025</span>
        </div>
      );
    case 'checkbox':
      return (
        <div className="flex items-center gap-2.5">
          <div className="h-5 w-5 rounded-md border-2 border-black dark:border-white bg-black dark:bg-white flex items-center justify-center">
            <svg className="h-3.5 w-3.5 text-white dark:text-black" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={3}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
            </svg>
          </div>
          <div className="h-5 w-5 rounded-md border-2 border-slate-300 dark:border-slate-600" />
        </div>
      );
    case 'email':
      return (
        <div className="flex items-center gap-1.5 text-sm">
          <Mail className="h-3.5 w-3.5 text-theme-secondary" />
          <span className="text-theme-primary underline-offset-2 underline">user@example.com</span>
        </div>
      );
    case 'phone':
      return (
        <div className="flex items-center gap-1.5 text-sm">
          <Phone className="h-3.5 w-3.5 text-theme-secondary" />
          <span className="text-theme-primary underline-offset-2 underline">+1 (555) 123-4567</span>
        </div>
      );
    case 'url':
      return (
        <div className="flex items-center gap-1.5 text-sm">
          <ExternalLink className="h-3.5 w-3.5 text-theme-secondary" />
          <span className="text-theme-primary underline-offset-2 underline">example.com/docs</span>
        </div>
      );
    case 'vector':
      return (
        <div className="flex items-center gap-1.5 text-sm">
          <Binary className="h-3.5 w-3.5 text-purple-500" />
          <span className="font-mono text-xs text-theme-secondary">vec(1536)</span>
        </div>
      );
    case 'text':
    default:
      return <span className="text-sm text-theme-secondary italic">The quick brown fox...</span>;
  }
};
