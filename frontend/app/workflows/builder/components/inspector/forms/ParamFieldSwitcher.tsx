'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Pencil, ChevronDown } from 'lucide-react';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { Input } from '@/components/ui/input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { NONE_SENTINEL } from './TaskParametersForm';
import { GoogleDrivePickerField } from './GoogleDrivePickerField';

interface PickerHint {
  provider?: string;
  mimeType?: string;
}

interface Connection {
  id: string;
  source: string;
  target: string;
}

interface ParamFieldSwitcherProps {
  paramName: string;
  paramType?: string;
  defaultValue?: string | null;
  allowedValues?: string[] | null;
  picker?: PickerHint | null;
  value: string;
  onChange: (value: string) => void;
  isRequired: boolean;
  readOnly?: boolean;
  placeholder?: string;
  // Expression-mode-only props (forwarded to ExpressionEditor)
  unknownVariables?: string[];
  handleId?: string;
  connections?: Connection[];
  onHandleClick?: (handleId: string, e: React.MouseEvent) => void;
  draggingFromHandle?: string | null;
  onHandleMouseDown?: (handleId: string, e: React.MouseEvent) => void;
  onHandleMouseUp?: (handleId: string, e: React.MouseEvent) => void;
  hoveredTargetHandle?: string | null;
  onSetHandleRef?: (handleId: string, el: HTMLDivElement | null) => void;
}

const SCALAR_TYPES = new Set(['string', 'integer', 'number', 'boolean']);

/**
 * Returns true when the param's data type makes a closed-set picker meaningful.
 * For arrays/objects we always fall back to the expression editor regardless of
 * a {@code defaultValue} or {@code allowedValues} declaration.
 */
function isScalarType(type?: string): boolean {
  if (!type) return true;
  return SCALAR_TYPES.has(type.toLowerCase());
}

/**
 * Detects whether the current value is a workflow expression (`{{...}}`).
 * Used to auto-route a saved expression back to the editor regardless of the
 * user's last picker preference.
 */
function isExpressionValue(value: string): boolean {
  return /\{\{/.test(value);
}

type Mode = 'select' | 'input' | 'expression' | 'picker';

function decideInitialMode(
  value: string,
  hasAllowed: boolean,
  hasDefault: boolean,
  hasPicker: boolean,
  scalar: boolean
): Mode {
  if (!scalar) return 'expression';
  if (isExpressionValue(value)) return 'expression';
  if (hasPicker) return 'picker';
  if (hasAllowed) return 'select';
  if (hasDefault) return 'input';
  return 'expression';
}

export function ParamFieldSwitcher({
  paramName,
  paramType,
  defaultValue,
  allowedValues,
  picker,
  value,
  onChange,
  isRequired,
  readOnly,
  placeholder,
  unknownVariables,
  handleId,
  connections,
  onHandleClick,
  draggingFromHandle,
  onHandleMouseDown,
  onHandleMouseUp,
  hoveredTargetHandle,
  onSetHandleRef,
}: ParamFieldSwitcherProps) {
  const t = useTranslations('workflowBuilder.forms');

  const scalar = isScalarType(paramType);
  const hasAllowed = scalar && Array.isArray(allowedValues) && allowedValues.length > 0;
  const hasDefault =
    scalar && defaultValue !== undefined && defaultValue !== null && defaultValue !== '';
  const hasPicker = scalar && picker?.provider === 'google-drive' && !!picker?.mimeType;

  // The picker mode is per-render: derived from the current value + a one-shot
  // user override stored as state. We never persist the mode in the workflow
  // model - only the value itself is saved.
  const [forceExpression, setForceExpression] = React.useState<boolean>(() =>
    isExpressionValue(value)
  );

  const mode: Mode = forceExpression
    ? 'expression'
    : decideInitialMode(value, hasAllowed, hasDefault, hasPicker, scalar);

  // When the saved value flips into an expression (e.g. user pasted a {{var}})
  // outside our control, stick to expression mode.
  React.useEffect(() => {
    if (isExpressionValue(value) && !forceExpression) {
      setForceExpression(true);
    }
  }, [value, forceExpression]);

  const switchToExpression = () => setForceExpression(true);
  const switchBackToPicker = () => {
    // When the current value is a valid allowed option, keep it as-is.
    // Otherwise reset to default (or wipe) so the picker doesn't render with
    // an invisible selection that the dropdown can't represent.
    const currentMatchesAllowed = hasAllowed && allowedValues!.includes(value);
    if (!currentMatchesAllowed) {
      onChange(hasDefault ? (defaultValue as string) : '');
    }
    setForceExpression(false);
  };

  if (mode === 'picker') {
    return (
      <div className="space-y-1">
        <GoogleDrivePickerField
          paramName={paramName}
          value={value}
          onChange={onChange}
          mimeType={picker!.mimeType as string}
          readOnly={readOnly}
          placeholder={placeholder}
        />
        {!readOnly && (
          <button
            type="button"
            onClick={switchToExpression}
            className="flex items-center gap-1 text-xs text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
            data-testid={`param-switch-expression-${paramName}`}
          >
            <Pencil className="h-3 w-3" />
            {t('useExpression')}
          </button>
        )}
      </div>
    );
  }

  if (mode === 'select') {
    // For optional params with a closed enum, expose an explicit `(none)` option.
    // Empty value resolves to the sentinel so Radix renders `(none)` as selected
    // (instead of an ambiguous placeholder). Picking `(none)` writes '' upstream,
    // which planHelpers strips → the param key is omitted from the workflow plan
    // → the API receives the request without it (server default applies).
    const showNoneOption = !isRequired;
    const selectValue = showNoneOption && (value === '' || value == null)
      ? NONE_SENTINEL
      : (value || '');
    const handleSelectChange = (v: string) => {
      onChange(v === NONE_SENTINEL ? '' : v);
    };
    return (
      <div className="space-y-1">
        <Select
          value={selectValue}
          onValueChange={handleSelectChange}
          disabled={readOnly}
        >
          <SelectTrigger
            data-testid={`param-select-${paramName}`}
            className="text-sm"
          >
            <SelectValue
              placeholder={
                hasDefault
                  ? `${defaultValue} (${t('default')})`
                  : placeholder ?? t('selectValue')
              }
            />
          </SelectTrigger>
          <SelectContent>
            {showNoneOption && (
              <SelectItem
                key={NONE_SENTINEL}
                value={NONE_SENTINEL}
                className="text-sm italic text-slate-500 dark:text-slate-400"
              >
                {t('noneOption')}
              </SelectItem>
            )}
            {(allowedValues ?? []).map((opt) => (
              <SelectItem key={opt} value={opt} className="text-sm">
                {opt}
                {opt === defaultValue && (
                  <span className="ml-2 text-xs text-slate-400 dark:text-slate-500">
                    ({t('default')})
                  </span>
                )}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        {!readOnly && (
          <button
            type="button"
            onClick={switchToExpression}
            className="flex items-center gap-1 text-xs text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
            data-testid={`param-switch-expression-${paramName}`}
          >
            <Pencil className="h-3 w-3" />
            {t('useExpression')}
          </button>
        )}
      </div>
    );
  }

  if (mode === 'input') {
    return (
      <div className="space-y-1">
        <Input
          data-testid={`param-input-${paramName}`}
          type={paramType === 'integer' || paramType === 'number' ? 'number' : 'text'}
          value={value ?? ''}
          onChange={(e) => onChange(e.target.value)}
          placeholder={String(defaultValue ?? placeholder ?? '')}
          readOnly={readOnly}
          className="text-sm"
        />
        {!readOnly && (
          <button
            type="button"
            onClick={switchToExpression}
            className="flex items-center gap-1 text-xs text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
            data-testid={`param-switch-expression-${paramName}`}
          >
            <Pencil className="h-3 w-3" />
            {t('useExpression')}
          </button>
        )}
      </div>
    );
  }

  // expression mode
  return (
    <div className="space-y-1">
      <ExpressionEditor
        value={value}
        onChange={onChange}
        placeholder={placeholder ?? t('enterExpression')}
        className="w-full"
        unknownVariables={unknownVariables}
        handleId={handleId}
        connections={connections}
        onHandleClick={onHandleClick}
        draggingFromHandle={draggingFromHandle}
        onHandleMouseDown={onHandleMouseDown}
        onHandleMouseUp={onHandleMouseUp}
        hoveredTargetHandle={hoveredTargetHandle}
        onSetHandleRef={onSetHandleRef}
        isRequired={isRequired}
        readOnly={readOnly}
      />
      {!readOnly && (hasAllowed || hasDefault || hasPicker) && (
        <button
          type="button"
          onClick={switchBackToPicker}
          className="flex items-center gap-1 text-xs text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
          data-testid={`param-switch-picker-${paramName}`}
        >
          <ChevronDown className="h-3 w-3" />
          {hasPicker ? t('usePicker') : t('useDefault')}
        </button>
      )}
    </div>
  );
}
