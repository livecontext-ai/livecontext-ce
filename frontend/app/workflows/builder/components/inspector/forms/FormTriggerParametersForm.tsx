'use client';

import * as React from 'react';
import * as ReactDOM from 'react-dom';
import { Plus, Trash2, Info, X, GripVertical, ChevronDown, ChevronUp, Copy, Check, AlertCircle, ExternalLink } from 'lucide-react';
import type { Node } from 'reactflow';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Button } from '@/components/ui/button';
import { Switch } from '@/components/ui/switch';
import type { BuilderNodeData } from '../../../types';
import { usePopoverPosition } from '../../../hooks/ui/usePopoverPosition';
import { useTranslations } from 'next-intl';
import { normalizeLabel } from '../../../utils/labelNormalizer';
import { formEndpointSettingsService } from '@/lib/api/orchestrator';
import type { StandaloneFormEndpoint } from '@/lib/api/orchestrator';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { buildStandaloneSourceNodeId } from '../../../utils/standaloneSourceNodeId';
import Link from 'next/link';

// Field types available in the form builder
const FIELD_TYPES = [
  { value: 'text', label: 'Text Input', description: 'Single line text input' },
  { value: 'email', label: 'Email', description: 'Email address input with validation' },
  { value: 'password', label: 'Password', description: 'Password input (masked)' },
  { value: 'number', label: 'Number', description: 'Numeric input' },
  { value: 'textarea', label: 'Text Area', description: 'Multi-line text input' },
  { value: 'select', label: 'Dropdown', description: 'Single selection from options' },
  { value: 'multiselect', label: 'Multi Select', description: 'Multiple selection from options' },
  { value: 'checkbox', label: 'Checkbox', description: 'Single checkbox (true/false)' },
  { value: 'checkboxGroup', label: 'Checkbox Group', description: 'Multiple checkboxes' },
  { value: 'radio', label: 'Radio Buttons', description: 'Single selection from radio buttons' },
  { value: 'date', label: 'Date', description: 'Date picker' },
  { value: 'datetime', label: 'Date & Time', description: 'Date and time picker' },
  { value: 'time', label: 'Time', description: 'Time picker' },
  { value: 'file', label: 'File Upload', description: 'File upload field' },
  { value: 'url', label: 'URL', description: 'URL input with validation' },
  { value: 'tel', label: 'Phone', description: 'Phone number input' },
  { value: 'hidden', label: 'Hidden', description: 'Hidden field (not visible to user)' },
] as const;

type FieldType = typeof FIELD_TYPES[number]['value'];

// Authentication types
const AUTH_TYPES = [
  { value: 'none', label: 'None (Public)', description: 'Anyone can submit the form' },
  { value: 'basic', label: 'Basic Auth', description: 'Username and password required' },
] as const;

type AuthType = typeof AUTH_TYPES[number]['value'];

// Option for select/multiselect/radio/checkboxGroup fields
interface FieldOption {
  id: string;
  label: string;
  value: string;
}

// Form field definition
interface FormField {
  id: string;
  type: FieldType;
  name: string;
  label: string;
  placeholder: string;
  required: boolean;
  options?: FieldOption[];  // For select, multiselect, radio, checkboxGroup
  defaultValue?: string;
  minLength?: number;
  maxLength?: number;
  min?: number;
  max?: number;
  accept?: string;  // For file upload (e.g., "image/*,.pdf")
}

// Form trigger data structure
interface FormTriggerData {
  title: string;
  description: string;
  authType: AuthType;
  basicAuth?: {
    username: string;
    password: string;
  };
  fields: FormField[];
  submitButtonText: string;
}

// Module-level guard: prevent duplicate creation across remounts for the same node
const pendingOrCreatedFormEndpoints = new Map<string, string>();

interface FormTriggerParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  /** Trigger ID for multi-DAG support (e.g., "trigger:my_form") */
  triggerId?: string | null;
}

// Component for managing options (used by select, multiselect, radio, checkboxGroup)
function OptionsEditor({
  options,
  onChange,
  disabled,
}: {
  options: FieldOption[];
  onChange: (options: FieldOption[]) => void;
  disabled?: boolean;
}) {
  const t = useTranslations('formTrigger');

  const handleAddOption = () => {
    const newOption: FieldOption = {
      id: `opt-${Date.now()}`,
      label: `Option ${options.length + 1}`,
      value: `option_${options.length + 1}`,
    };
    onChange([...options, newOption]);
  };

  const handleRemoveOption = (id: string) => {
    onChange(options.filter(o => o.id !== id));
  };

  const handleOptionChange = (id: string, field: 'label' | 'value', value: string) => {
    onChange(options.map(o => o.id === id ? { ...o, [field]: value } : o));
  };

  return (
    <div className="space-y-2 pl-4 border-l-2 border-slate-200 dark:border-slate-700">
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium text-slate-500">{t('options')}</span>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="h-6 px-2 text-xs"
          onClick={handleAddOption}
          disabled={disabled}
        >
          <Plus className="h-3 w-3 mr-1" />
          {t('addOption')}
        </Button>
      </div>
      {options.map((option, index) => (
        <div key={option.id} className="flex items-center gap-2">
          <Input
            className="flex-1"
            value={option.label}
            onChange={(e) => handleOptionChange(option.id, 'label', e.target.value)}
            placeholder={t('optionLabel')}
            disabled={disabled}
          />
          <Input
            className="flex-1"
            value={option.value}
            onChange={(e) => handleOptionChange(option.id, 'value', e.target.value)}
            placeholder={t('optionValue')}
            disabled={disabled}
          />
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="h-6 w-6 text-slate-400 hover:text-red-500"
            onClick={() => handleRemoveOption(option.id)}
            disabled={disabled || options.length <= 1}
          >
            <Trash2 className="h-3 w-3" />
          </Button>
        </div>
      ))}
    </div>
  );
}

// Single form field editor
function FormFieldEditor({
  field,
  index,
  onChange,
  onRemove,
  canRemove,
  disabled,
  onDragStart,
  onDragOver,
  onDragEnd,
  isDragging,
  isDropTarget,
}: {
  field: FormField;
  index: number;
  onChange: (field: FormField) => void;
  onRemove: () => void;
  canRemove: boolean;
  disabled?: boolean;
  onDragStart: (e: React.DragEvent, index: number) => void;
  onDragOver: (e: React.DragEvent, index: number) => void;
  onDragEnd: () => void;
  isDragging: boolean;
  isDropTarget: boolean;
}) {
  const [isExpanded, setIsExpanded] = React.useState(true);
  const t = useTranslations('formTrigger');

  const hasOptions = ['select', 'multiselect', 'radio', 'checkboxGroup'].includes(field.type);

  const handleTypeChange = (newType: FieldType) => {
    const updatedField = { ...field, type: newType };
    // Initialize options for types that need them
    if (['select', 'multiselect', 'radio', 'checkboxGroup'].includes(newType) && !field.options?.length) {
      updatedField.options = [
        { id: `opt-${Date.now()}`, label: 'Option 1', value: 'option_1' },
      ];
    }
    onChange(updatedField);
  };

  return (
    <div
      className={`border rounded-lg overflow-hidden transition-all ${
        isDragging ? 'opacity-50 border-dashed border-slate-400' :
        isDropTarget ? 'border-blue-500 border-2' :
        'border-slate-200 dark:border-slate-700'
      }`}
      draggable={!disabled}
      onDragStart={(e) => onDragStart(e, index)}
      onDragOver={(e) => onDragOver(e, index)}
      onDragEnd={onDragEnd}
    >
      {/* Header */}
      <div
        className="flex items-center gap-2 p-2 bg-slate-50 dark:bg-slate-800 cursor-pointer"
        onClick={() => setIsExpanded(!isExpanded)}
      >
        <GripVertical className={`h-4 w-4 text-slate-400 ${disabled ? 'cursor-default' : 'cursor-grab active:cursor-grabbing'}`} />
        <span className="text-xs font-mono text-slate-400">#{index + 1}</span>
        <span className="text-sm font-medium text-slate-700 dark:text-slate-200 flex-1 truncate">
          {field.label || field.name || t('untitledField')}
        </span>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="h-6 w-6 text-slate-400 hover:text-red-500"
          onClick={(e) => {
            e.stopPropagation();
            onRemove();
          }}
          disabled={disabled || !canRemove}
        >
          <Trash2 className="h-3 w-3" />
        </Button>
        {isExpanded ? <ChevronUp className="h-4 w-4 text-slate-400" /> : <ChevronDown className="h-4 w-4 text-slate-400" />}
      </div>

      {/* Body */}
      {isExpanded && (
        <div className="p-3 space-y-3">
          {/* Field Type */}
          <div className="space-y-1">
            <label className="text-xs font-medium text-slate-500">{t('fieldType')}</label>
            <Select
              value={field.type}
              onValueChange={(value) => handleTypeChange(value as FieldType)}
              disabled={disabled}
            >
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {FIELD_TYPES.map((ft) => (
                  <SelectItem key={ft.value} value={ft.value} description={ft.description}>
                    {ft.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {/* Field Name */}
          <div className="space-y-1">
            <label className="text-xs font-medium text-slate-500">{t('fieldName')}</label>
            <Input
              value={field.name}
              onChange={(e) => {
                // Use centralized normalizeLabel for field name sanitization
                const normalized = normalizeLabel(e.target.value) || e.target.value.toLowerCase().replace(/\s+/g, '_');
                onChange({ ...field, name: normalized });
              }}
              placeholder="field_name"
              disabled={disabled}
            />
          </div>

          {/* Label */}
          <div className="space-y-1">
            <label className="text-xs font-medium text-slate-500">{t('label')}</label>
            <Input
              value={field.label}
              onChange={(e) => onChange({ ...field, label: e.target.value })}
              placeholder={t('labelPlaceholder')}
              disabled={disabled}
            />
          </div>

          {/* Placeholder (not for checkbox/radio) */}
          {!['checkbox', 'checkboxGroup', 'radio', 'file'].includes(field.type) && (
            <div className="space-y-1">
              <label className="text-xs font-medium text-slate-500">{t('placeholder')}</label>
              <Input
                value={field.placeholder}
                onChange={(e) => onChange({ ...field, placeholder: e.target.value })}
                placeholder={t('placeholderPlaceholder')}
                disabled={disabled}
              />
            </div>
          )}

          {/* Options for select/multiselect/radio/checkboxGroup */}
          {hasOptions && (
            <OptionsEditor
              options={field.options || []}
              onChange={(options) => onChange({ ...field, options })}
              disabled={disabled}
            />
          )}

          {/* File accept (for file type) */}
          {field.type === 'file' && (
            <div className="space-y-1">
              <label className="text-xs font-medium text-slate-500">{t('acceptedFiles')}</label>
              <Input
                value={field.accept || ''}
                onChange={(e) => onChange({ ...field, accept: e.target.value })}
                placeholder="image/*,.pdf,.doc,.docx"
                disabled={disabled}
              />
            </div>
          )}

          {/* Required toggle */}
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium text-slate-500">{t('requiredField')}</span>
            <Switch
              checked={field.required}
              onCheckedChange={(checked) => onChange({ ...field, required: checked })}
              disabled={disabled}
            />
          </div>
        </div>
      )}
    </div>
  );
}

export function FormTriggerParametersForm({
  node,
  data,
  isRunMode: _isRunModeProp = false,
  onUpdate,
  triggerId,
}: FormTriggerParametersFormProps) {
  const t = useTranslations('formTrigger');
  const { isRunMode: isRunModeContext } = useWorkflowMode();
  const isRunMode = isRunModeContext;

  const [copied, setCopied] = React.useState(false);

  // Auto-created standalone form endpoint data from node
  const standaloneFormEndpointId = (data as any).standaloneFormEndpointId as string | undefined;
  const standaloneFormUrl = (data as any).standaloneFormUrl as string | undefined;

  // All available form endpoints for the selector
  const [allEndpoints, setAllEndpoints] = React.useState<StandaloneFormEndpoint[]>([]);
  const [isLoadingList, setIsLoadingList] = React.useState(false);

  // Currently selected/fetched endpoint details
  const [standaloneEndpoint, setStandaloneEndpoint] = React.useState<StandaloneFormEndpoint | null>(null);
  const [isLoadingEndpoint, setIsLoadingEndpoint] = React.useState(false);
  const [isCreating, setIsCreating] = React.useState(false);
  const [autoCreateFailed, setAutoCreateFailed] = React.useState(false);

  // Fetch all form endpoints for the selector
  React.useEffect(() => {
    setIsLoadingList(true);
    formEndpointSettingsService.getAll()
      .then(setAllEndpoints)
      .catch(() => setAllEndpoints([]))
      .finally(() => setIsLoadingList(false));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Fetch current endpoint details when node already has one
  React.useEffect(() => {
    if (standaloneFormEndpointId) {
      setIsLoadingEndpoint(true);
      formEndpointSettingsService.getById(standaloneFormEndpointId)
        .then(setStandaloneEndpoint)
        .catch(() => setStandaloneEndpoint(null))
        .finally(() => setIsLoadingEndpoint(false));
    }
  }, [standaloneFormEndpointId]);

  // Auto-create form endpoint if node has none (waits for list to load for unique name)
  React.useEffect(() => {
    if (standaloneFormEndpointId || isRunMode || isCreating || isLoadingList) return;
    const nodeDataId = node.id;
    // Module-level dedup guard
    const existingId = pendingOrCreatedFormEndpoints.get(nodeDataId);
    if (existingId) {
      if (existingId !== '__pending__') {
        onUpdate({ ...data, standaloneFormEndpointId: existingId } as BuilderNodeData);
      }
      return;
    }
    pendingOrCreatedFormEndpoints.set(nodeDataId, '__pending__');
    setIsCreating(true);
    setIsLoadingEndpoint(true);
    setAutoCreateFailed(false);
    const endpointNumber = allEndpoints.length + 1;
    const sourceNodeId = buildStandaloneSourceNodeId('form', nodeDataId);
    formEndpointSettingsService.create({ name: `Form #${endpointNumber}`, sourceNodeId })
      .then((endpoint) => {
        pendingOrCreatedFormEndpoints.set(nodeDataId, endpoint.id);
        setStandaloneEndpoint(endpoint);
        setAllEndpoints((prev) => [...prev, endpoint]);
        onUpdate({
          ...data,
          standaloneFormEndpointId: endpoint.id,
          standaloneFormUrl: endpoint.formUrl,
          standaloneFormToken: endpoint.token,
        } as BuilderNodeData);
      })
      .catch(() => {
        pendingOrCreatedFormEndpoints.delete(nodeDataId);
        setAutoCreateFailed(true);
      })
      .finally(() => setIsLoadingEndpoint(false));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [standaloneFormEndpointId, isRunMode, isLoadingList]);

  const effectiveFormUrl = standaloneFormUrl || standaloneEndpoint?.formUrl || '';

  const handleCopyUrl = React.useCallback(() => {
    if (!effectiveFormUrl) return;
    navigator.clipboard.writeText(effectiveFormUrl);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }, [effectiveFormUrl]);

  // Get form trigger data from node data, with defaults
  const formTriggerData: FormTriggerData = React.useMemo(() => {
    const existing = (data as any).formTriggerData as FormTriggerData | undefined;
    if (!existing) {
      return {
        title: '',
        description: '',
        authType: 'none',
        fields: [],
        submitButtonText: 'Submit',
      };
    }
    // Ensure every field has a unique id (legacy data may lack ids)
    const fields = existing.fields?.map((f, i) => ({
      ...f,
      id: f.id || `field-${i}-${Date.now()}`,
    })) ?? [];
    return { ...existing, fields };
  }, [(data as any).formTriggerData]);

  const [isInfoOpen, setIsInfoOpen] = React.useState(false);
  const { buttonRef: infoButtonRef, popoverPosition } = usePopoverPosition(isInfoOpen, 300);

  // Update handler for form trigger data
  const handleUpdate = React.useCallback((updates: Partial<FormTriggerData>) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      formTriggerData: {
        ...formTriggerData,
        ...updates,
      },
    } as BuilderNodeData);
  }, [data, formTriggerData, isRunMode, onUpdate]);

  // Add new field
  const handleAddField = React.useCallback(() => {
    if (isRunMode) return;
    const newField: FormField = {
      id: `field-${Date.now()}`,
      type: 'text',
      name: `field_${formTriggerData.fields.length + 1}`,
      label: '',
      placeholder: '',
      required: false,
    };
    handleUpdate({ fields: [...formTriggerData.fields, newField] });
  }, [formTriggerData.fields, isRunMode, handleUpdate]);

  // Remove field
  const handleRemoveField = React.useCallback((fieldId: string) => {
    if (isRunMode) return;
    handleUpdate({ fields: formTriggerData.fields.filter(f => f.id !== fieldId) });
  }, [formTriggerData.fields, isRunMode, handleUpdate]);

  // Update field
  const handleFieldChange = React.useCallback((fieldId: string, updatedField: FormField) => {
    if (isRunMode) return;
    handleUpdate({
      fields: formTriggerData.fields.map(f => f.id === fieldId ? updatedField : f),
    });
  }, [formTriggerData.fields, isRunMode, handleUpdate]);

  // Basic auth handlers
  const handleBasicAuthChange = React.useCallback((field: 'username' | 'password', value: string) => {
    handleUpdate({
      basicAuth: {
        ...formTriggerData.basicAuth,
        username: formTriggerData.basicAuth?.username || '',
        password: formTriggerData.basicAuth?.password || '',
        [field]: value,
      },
    });
  }, [handleUpdate, formTriggerData.basicAuth]);

  // Drag and drop state for reordering fields
  const [dragIndex, setDragIndex] = React.useState<number | null>(null);
  const [dropIndex, setDropIndex] = React.useState<number | null>(null);

  const handleDragStart = React.useCallback((e: React.DragEvent, index: number) => {
    if (isRunMode) return;
    setDragIndex(index);
    e.dataTransfer.effectAllowed = 'move';
  }, [isRunMode]);

  const handleDragOver = React.useCallback((e: React.DragEvent, index: number) => {
    e.preventDefault();
    if (dragIndex === null || dragIndex === index) return;
    setDropIndex(index);
  }, [dragIndex]);

  const handleDragEnd = React.useCallback(() => {
    if (dragIndex !== null && dropIndex !== null && dragIndex !== dropIndex) {
      const newFields = [...formTriggerData.fields];
      const [draggedField] = newFields.splice(dragIndex, 1);
      newFields.splice(dropIndex, 0, draggedField);
      handleUpdate({ fields: newFields });
    }
    setDragIndex(null);
    setDropIndex(null);
  }, [dragIndex, dropIndex, formTriggerData.fields, handleUpdate]);

  // Available outputs based on fields
  const availableOutputs = React.useMemo(() => {
    const outputs = ['formData', 'submittedAt'];
    formTriggerData.fields.forEach(f => {
      if (f.name) outputs.push(f.name);
    });
    return outputs;
  }, [formTriggerData.fields]);

  return (
    <div className="space-y-4 pt-2">
      {/* Header with info tooltip */}
      <div className="flex items-center gap-1.5">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('title')}</span>
        <div className="relative inline-flex">
          <button
            ref={infoButtonRef}
            onClick={(e) => {
              e.stopPropagation();
              setIsInfoOpen(!isInfoOpen);
            }}
            className="p-0.5 rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
            title={t('moreInfo')}
          >
            <Info className="h-3 w-3 text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300" />
          </button>
          {isInfoOpen && typeof document !== 'undefined' && ReactDOM.createPortal(
            <>
              <div
                className="fixed inset-0 z-[9998]"
                onClick={() => setIsInfoOpen(false)}
              />
              <div
                className="fixed z-[9999] w-80 p-3 bg-white dark:bg-slate-800 rounded-lg border border-slate-200 dark:border-slate-700 shadow-lg"
                style={{ top: popoverPosition.top, left: popoverPosition.left }}
              >
                <div className="flex items-start justify-between gap-2 mb-2">
                  <span className="font-medium text-sm text-slate-700 dark:text-slate-200">
                    {t('infoTitle')}
                  </span>
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      setIsInfoOpen(false);
                    }}
                    className="p-0.5 rounded hover:bg-slate-100 dark:hover:bg-slate-700"
                  >
                    <X className="h-3.5 w-3.5 text-slate-400" />
                  </button>
                </div>
                <p className="text-xs text-slate-500 dark:text-slate-400 leading-relaxed mb-2">
                  {t('infoDescription')}
                </p>
                <div className="border-t border-slate-200 dark:border-slate-700 pt-2">
                  <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">{t('availableOutputs')}</p>
                  <ul className="text-xs text-slate-500 dark:text-slate-400 space-y-0.5 font-mono max-h-32 overflow-y-auto">
                    {availableOutputs.map(output => (
                      <li key={output}>• {output}</li>
                    ))}
                  </ul>
                </div>
              </div>
            </>,
            document.body
          )}
        </div>
      </div>

      {/* Loading state while creating or fetching endpoint */}
      {(isLoadingEndpoint || isLoadingList) && !effectiveFormUrl && (
        <div className="flex items-center gap-2 p-3 bg-slate-50 dark:bg-slate-900 rounded-lg">
          <div className="h-4 w-4 animate-spin rounded-full border-2 border-slate-300 border-t-blue-500" />
          <span className="text-sm text-slate-500">{t('loadingEndpoint')}</span>
        </div>
      )}

      {/* Auto-create failed */}
      {autoCreateFailed && !standaloneFormEndpointId && !isLoadingEndpoint && (
        <div className="flex items-start gap-2 p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg">
          <AlertCircle className="h-4 w-4 text-red-500 flex-shrink-0 mt-0.5" />
          <div className="text-sm text-red-700 dark:text-red-300">
            {t('createFailed')}{' '}
            <button
              onClick={() => {
                setAutoCreateFailed(false);
                setIsCreating(false);
              }}
              className="underline hover:text-red-800 dark:hover:text-red-200"
            >
              {t('retry')}
            </button>
          </div>
        </div>
      )}

      {/* Form Endpoint URL Display */}
      {standaloneFormEndpointId && effectiveFormUrl && (
        <div className="space-y-2">
          <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('formUrl')}</label>
          <div className="flex items-center gap-2">
            <div className="flex-1 relative">
              <Input
                value={effectiveFormUrl}
                readOnly
                className="pr-10 font-mono text-xs bg-slate-50 dark:bg-slate-900"
              />
              <Button
                variant="ghost"
                size="sm"
                className="absolute right-1 top-1/2 -translate-y-1/2 h-6 px-2"
                onClick={handleCopyUrl}
                title={t('copyUrl')}
              >
                {copied ? (
                  <Check className="h-3.5 w-3.5 text-green-500" />
                ) : (
                  <Copy className="h-3.5 w-3.5" />
                )}
              </Button>
            </div>
          </div>
          <div className="pt-1">
            <Link
              href="/app/settings/public-access?tab=form"
              className="inline-flex items-center gap-1 text-xs text-blue-600 dark:text-blue-400 hover:underline"
            >
              <ExternalLink className="h-3 w-3" />
              {t('manageInSettings')}
            </Link>
          </div>
        </div>
      )}

      {/* Form Title */}
      <div className="space-y-2">
        <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('formTitle')}</label>
        <Input
          value={formTriggerData.title}
          onChange={(e) => handleUpdate({ title: e.target.value })}
          placeholder={t('formTitlePlaceholder')}
          disabled={isRunMode}
        />
      </div>

      {/* Form Description (Optional) */}
      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('formDescription')}</label>
          <span className="text-xs text-slate-400">{t('optional')}</span>
        </div>
        <Textarea
          value={formTriggerData.description}
          onChange={(e) => handleUpdate({ description: e.target.value })}
          placeholder={t('formDescriptionPlaceholder')}
          disabled={isRunMode}
          rows={2}
        />
      </div>

      {/* Authentication */}
      <div className="space-y-3">
        <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('authentication')}</label>
        <Select
          value={formTriggerData.authType}
          onValueChange={(value) => handleUpdate({ authType: value as AuthType })}
          disabled={isRunMode}
        >
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {AUTH_TYPES.map((auth) => (
              <SelectItem key={auth.value} value={auth.value} description={auth.description}>
                {auth.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        {/* Basic Auth Fields */}
        {formTriggerData.authType === 'basic' && (
          <div className="space-y-3 p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-200 dark:border-slate-700">
            <div className="space-y-2">
              <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('username')}</label>
              <Input
                value={formTriggerData.basicAuth?.username || ''}
                onChange={(e) => handleBasicAuthChange('username', e.target.value)}
                placeholder={t('enterUsername')}
                disabled={isRunMode}
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('password')}</label>
              <Input
                type="password"
                value={formTriggerData.basicAuth?.password || ''}
                onChange={(e) => handleBasicAuthChange('password', e.target.value)}
                placeholder={t('enterPassword')}
                disabled={isRunMode}
              />
            </div>
          </div>
        )}
      </div>

      {/* Submit Button Text */}
      <div className="space-y-2">
        <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('submitButtonText')}</label>
        <Input
          value={formTriggerData.submitButtonText}
          onChange={(e) => handleUpdate({ submitButtonText: e.target.value })}
          placeholder="Submit"
          disabled={isRunMode}
        />
      </div>

      {/* Form Fields */}
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('formFields')}</span>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="flex-shrink-0 h-8 w-8 text-[var(--text-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)]"
            onClick={handleAddField}
            disabled={isRunMode}
            title={isRunMode ? t('readOnlyInRunMode') : t('addField')}
          >
            <Plus className="h-4 w-4" />
          </Button>
        </div>

        {formTriggerData.fields.length === 0 ? (
          <div className="text-center py-6 text-sm text-slate-400 border border-dashed border-slate-200 dark:border-slate-700 rounded-lg">
            {t('noFields')}
          </div>
        ) : (
          <div className="space-y-2">
            {formTriggerData.fields.map((field, index) => (
              <FormFieldEditor
                key={field.id}
                field={field}
                index={index}
                onChange={(updatedField) => handleFieldChange(field.id, updatedField)}
                onRemove={() => handleRemoveField(field.id)}
                canRemove={formTriggerData.fields.length > 0}
                disabled={isRunMode}
                onDragStart={handleDragStart}
                onDragOver={handleDragOver}
                onDragEnd={handleDragEnd}
                isDragging={dragIndex === index}
                isDropTarget={dropIndex === index}
              />
            ))}
          </div>
        )}
      </div>

    </div>
  );
}
