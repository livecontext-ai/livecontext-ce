'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Info, Pencil, ChevronDown } from 'lucide-react';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { Input } from '@/components/ui/input';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { NONE_SENTINEL } from './forms/TaskParametersForm';
import { GoogleDrivePickerField } from './forms/GoogleDrivePickerField';

/**
 * Connection props that are always passed to ExpressionEditor
 */
export interface ConnectionProps {
  connections: Array<{ id: string; source: string; target: string }>;
  draggingFromHandle: string | null;
  hoveredTargetHandle: string | null;
  handleHandleClick: (handleId: string, event: React.MouseEvent) => void;
  handleHandleMouseDown: (handleId: string, event: React.MouseEvent) => void;
  handleHandleMouseUp: (handleId: string, event: React.MouseEvent) => void;
  handleSetHandleRef: (handleId: string, element: HTMLDivElement | null) => void;
}

/**
 * Props for the unified ExpressionField component
 */
interface ExpressionFieldProps {
  // Core props
  label: string;
  value: string;
  onChange: (value: string) => void;
  nodeId: string;
  fieldName: string;

  // Optional configuration
  placeholder?: string;
  isRequired?: boolean;
  isRunMode?: boolean;
  className?: string;
  fullHeight?: boolean;

  // For variable validation
  findUnknownVariables?: (expressions: Record<string, string>) => string[];

  // Key to use for unknown variables validation (defaults to fieldName)
  variableKey?: string;

  // Connection props (grouped)
  connectionProps?: ConnectionProps;

  // Optional description
  description?: string;

  // Type hint
  typeHint?: string;

  // Info tooltip content (shows a clickable info icon)
  infoContent?: string;

  /**
   * Server-documented default value for this param. When set and the param type
   * is scalar (string/integer/number/boolean), the field renders an Input
   * pre-filled with this value instead of the bare expression editor. The user
   * can switch to expression mode via a toggle below the input.
   */
  defaultValue?: string | null;

  /**
   * Closed enum of admissible values declared by the API catalog. When non-empty
   * and the param type is scalar, the field renders a Select dropdown. The user
   * can switch to expression mode via a toggle below the dropdown.
   */
  allowedValues?: string[] | null;

  /**
   * Drive picker hint from the catalog param's {@code extras.picker}. When the
   * provider is {@code google-drive}, the field renders a "Pick from Drive" button
   * (GoogleDrivePickerField) that opens the Google Picker filtered by mimeType and
   * writes the picked file ID as the value. The user can switch to expression mode.
   */
  picker?: { provider?: string; mimeType?: string } | null;
}

const SCALAR_TYPE_RE = /^(string|integer|number|boolean)$/i;
const EXPRESSION_RE = /\{\{/;

/**
 * ExpressionField - A unified wrapper around ExpressionEditor
 *
 * This component consolidates the repeated patterns across the codebase:
 * - Standard label/required badge layout
 * - Connection props spreading
 * - Run mode readonly handling
 * - Unknown variables validation
 * - Handle ID construction
 */
export function ExpressionField({
  label,
  value,
  onChange,
  nodeId,
  fieldName,
  placeholder = "Enter Expression...",
  isRequired = false,
  isRunMode = false,
  className = "w-full",
  fullHeight = false,
  findUnknownVariables,
  variableKey,
  connectionProps,
  description,
  typeHint,
  infoContent,
  defaultValue,
  allowedValues,
  picker,
}: ExpressionFieldProps) {
  const t = useTranslations('workflowBuilder.forms');

  // Construct handle ID using consistent pattern
  const handleId = `${fieldName}-${nodeId}`;

  // Use variableKey or extract simple key from fieldName (e.g., "param-prompt" -> "prompt")
  const effectiveVariableKey = variableKey || fieldName.split('-').slice(1).join('-') || fieldName;

  // Calculate unknown variables if function provided
  const unknownVariables = React.useMemo(() => {
    if (!findUnknownVariables) return [];
    return findUnknownVariables({ [effectiveVariableKey]: value });
  }, [findUnknownVariables, effectiveVariableKey, value]);

  // Handle change - always allow editing
  const handleChange = React.useCallback((newValue: string) => {
    onChange(newValue);
  }, [onChange]);

  // Tri-mode picker decision (Select / Input / Expression). Object/array params and
  // values that already contain {{...}} fall through to the expression editor - that
  // preserves wirings on saved workflows and avoids forcing dropdowns where they
  // don't make sense.
  const scalar = !typeHint || SCALAR_TYPE_RE.test(typeHint);
  const hasAllowed = scalar && Array.isArray(allowedValues) && allowedValues.length > 0;
  const hasDefault =
    scalar && defaultValue !== undefined && defaultValue !== null && defaultValue !== '';
  const hasPicker =
    scalar && !!picker && picker.provider === 'google-drive' && !!picker.mimeType;
  const valueIsExpression = !!value && EXPRESSION_RE.test(value);

  const [forceExpression, setForceExpression] = React.useState<boolean>(valueIsExpression);
  React.useEffect(() => {
    // If a parent later writes an expression value, route to expression mode.
    if (valueIsExpression && !forceExpression) {
      setForceExpression(true);
    }
  }, [valueIsExpression, forceExpression]);

  type Mode = 'select' | 'input' | 'expression' | 'picker';
  const mode: Mode = (() => {
    if (forceExpression || valueIsExpression) return 'expression';
    if (hasPicker) return 'picker';
    if (hasAllowed) return 'select';
    if (hasDefault) return 'input';
    return 'expression';
  })();

  const switchToExpression = () => setForceExpression(true);
  const switchBackToPicker = () => {
    const currentMatchesAllowed = hasAllowed && allowedValues!.includes(value);
    // Never wipe a picked file ID when returning from expression mode (a picker value
    // is a freeform string, not a closed enum); only reset for select/input defaults.
    if (!currentMatchesAllowed && !hasPicker) {
      onChange(hasDefault ? (defaultValue as string) : '');
    }
    setForceExpression(false);
  };

  return (
    <div className="flex flex-col gap-1.5">
      {/* Header with label and required/optional badge */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-1.5">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{label}</span>
          {typeHint && (
            <span className="text-[10px] text-[var(--text-tertiary)] font-mono">({typeHint})</span>
          )}
          {infoContent && (
            <Popover>
              <PopoverTrigger asChild>
                <button type="button" className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5">
                  <Info className="h-3 w-3 text-slate-400" />
                </button>
              </PopoverTrigger>
              <PopoverContent className="w-[280px] p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                <p className="text-xs text-slate-600 dark:text-slate-300">{infoContent}</p>
              </PopoverContent>
            </Popover>
          )}
        </div>
        {isRequired && (
          <span className="text-sm text-slate-500 dark:text-slate-400">Required</span>
        )}
      </div>

      {/* Optional description */}
      {description && (
        <p className="text-[11px] text-[var(--text-tertiary)] -mt-0.5">{description}</p>
      )}

      {/* Tri-mode field: Select for closed enums, Input for scalar defaults, ExpressionEditor otherwise */}
      {mode === 'select' && (() => {
        // For optional params with a closed enum, expose `(none)` as the explicit
        // omit choice. Empty value resolves to the sentinel so Radix renders
        // `(none)` selected. Picking `(none)` writes '' upstream → the value is
        // stripped at plan-generation time → the API receives the request without
        // the param (server default applies).
        const showNoneOption = !isRequired;
        const selectValue = showNoneOption && (value === '' || value == null)
          ? NONE_SENTINEL
          : (value || '');
        const handleSelectChange = (v: string) => {
          handleChange(v === NONE_SENTINEL ? '' : v);
        };
        return (
        <div className="space-y-1">
          <Select
            value={selectValue}
            onValueChange={handleSelectChange}
            disabled={isRunMode}
          >
            <SelectTrigger data-testid={`expr-select-${fieldName}`} className="text-sm">
              <SelectValue
                placeholder={
                  hasDefault
                    ? `${defaultValue} (${t('default')})`
                    : t('selectValue')
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
          {!isRunMode && (
            <button
              type="button"
              onClick={switchToExpression}
              className="flex items-center gap-1 text-xs text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
              data-testid={`expr-switch-expression-${fieldName}`}
            >
              <Pencil className="h-3 w-3" />
              {t('useExpression')}
            </button>
          )}
        </div>
        );
      })()}

      {mode === 'input' && (
        <div className="space-y-1">
          <Input
            data-testid={`expr-input-${fieldName}`}
            type={typeHint === 'integer' || typeHint === 'number' ? 'number' : 'text'}
            value={value ?? ''}
            onChange={(e) => handleChange(e.target.value)}
            placeholder={String(defaultValue ?? placeholder ?? '')}
            readOnly={isRunMode}
            className="text-sm"
          />
          {!isRunMode && (
            <button
              type="button"
              onClick={switchToExpression}
              className="flex items-center gap-1 text-xs text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
              data-testid={`expr-switch-expression-${fieldName}`}
            >
              <Pencil className="h-3 w-3" />
              {t('useExpression')}
            </button>
          )}
        </div>
      )}

      {mode === 'picker' && (
        <GoogleDrivePickerField
          paramName={effectiveVariableKey}
          value={value ?? ''}
          onChange={handleChange}
          mimeType={picker!.mimeType as string}
          readOnly={isRunMode}
          onUseExpression={isRunMode ? undefined : switchToExpression}
        />
      )}

      {mode === 'expression' && (
        <div className="space-y-1">
          <ExpressionEditor
            value={value}
            onChange={handleChange}
            placeholder={placeholder}
            className={className}
            fullHeight={fullHeight}
            readOnly={isRunMode}
            unknownVariables={unknownVariables}
            handleId={handleId}
            isRequired={isRequired}
            // Connection props - only spread if provided
            connections={connectionProps?.connections}
            onHandleClick={connectionProps?.handleHandleClick}
            draggingFromHandle={connectionProps?.draggingFromHandle}
            onHandleMouseDown={connectionProps?.handleHandleMouseDown}
            onHandleMouseUp={connectionProps?.handleHandleMouseUp}
            hoveredTargetHandle={connectionProps?.hoveredTargetHandle}
            onSetHandleRef={connectionProps?.handleSetHandleRef}
          />
          {!isRunMode && (hasAllowed || hasDefault || hasPicker) && (
            <button
              type="button"
              onClick={switchBackToPicker}
              className="flex items-center gap-1 text-xs text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
              data-testid={`expr-switch-picker-${fieldName}`}
            >
              <ChevronDown className="h-3 w-3" />
              {t(hasPicker ? 'usePicker' : 'useDefault')}
            </button>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * Hook to create connection props object from individual handlers
 * Use this in parent components to bundle connection props
 */
export function useConnectionProps(
  connections: ConnectionProps['connections'],
  draggingFromHandle: ConnectionProps['draggingFromHandle'],
  hoveredTargetHandle: ConnectionProps['hoveredTargetHandle'],
  handleHandleClick: ConnectionProps['handleHandleClick'],
  handleHandleMouseDown: ConnectionProps['handleHandleMouseDown'],
  handleHandleMouseUp: ConnectionProps['handleHandleMouseUp'],
  handleSetHandleRef: ConnectionProps['handleSetHandleRef']
): ConnectionProps {
  return React.useMemo(() => ({
    connections,
    draggingFromHandle,
    hoveredTargetHandle,
    handleHandleClick,
    handleHandleMouseDown,
    handleHandleMouseUp,
    handleSetHandleRef,
  }), [
    connections,
    draggingFromHandle,
    hoveredTargetHandle,
    handleHandleClick,
    handleHandleMouseDown,
    handleHandleMouseUp,
    handleSetHandleRef,
  ]);
}
