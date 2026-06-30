'use client';

import * as React from 'react';
import { Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import type { BuilderNodeData } from '../../../types';
import type { ConnectionProps } from '../ExpressionField';

interface CryptoJwtParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

const OPERATIONS = [
  'hash',
  'hmacSign',
  'hmacVerify',
  'encrypt',
  'decrypt',
  'jwtCreate',
  'jwtDecode',
  'jwtVerify',
  'base64Encode',
  'base64Decode',
  'generateUuid',
  'generateSecret',
] as const;

const ENCODINGS = ['hex', 'base64', 'utf8'] as const;

/**
 * Algorithm options available per operation.
 */
const ALGORITHM_OPTIONS: Record<string, readonly string[]> = {
  hash: ['MD5', 'SHA-1', 'SHA-256', 'SHA-512'],
  hmacSign: ['HmacSHA256', 'HmacSHA512'],
  hmacVerify: ['HmacSHA256', 'HmacSHA512'],
  encrypt: ['AES'],
  decrypt: ['AES'],
  jwtCreate: ['HS256', 'HS512', 'RS256'],
  jwtVerify: ['HS256', 'HS512', 'RS256'],
};

/**
 * Which fields are visible for each operation.
 */
const VISIBLE_FIELDS: Record<string, Set<string>> = {
  hash: new Set(['value', 'algorithm', 'encoding']),
  hmacSign: new Set(['value', 'secret', 'algorithm', 'encoding']),
  hmacVerify: new Set(['value', 'secret', 'algorithm']),
  encrypt: new Set(['value', 'key', 'algorithm']),
  decrypt: new Set(['value', 'key', 'algorithm']),
  jwtCreate: new Set(['payload', 'secret', 'algorithm']),
  jwtDecode: new Set(['token']),
  jwtVerify: new Set(['token', 'secret', 'algorithm']),
  base64Encode: new Set(['value']),
  base64Decode: new Set(['value']),
  generateUuid: new Set([]),
  generateSecret: new Set([]),
};

/**
 * Form component for CryptoJwt node parameters.
 * Configures cryptographic operations such as hashing, HMAC, encryption, JWT, and encoding.
 */
export function CryptoJwtParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: CryptoJwtParametersFormProps) {
  const t = useTranslations('workflowBuilder.cryptoJwtNode');

  const operation: string = (data as any).cryptoOperation ?? 'hash';
  const algorithm: string = (data as any).cryptoAlgorithm ?? '';
  const value: string = (data as any).cryptoValue ?? '';
  const key: string = (data as any).cryptoKey ?? '';
  const secret: string = (data as any).cryptoSecret ?? '';
  const token: string = (data as any).cryptoToken ?? '';
  const payload: string = (data as any).cryptoPayload ?? '';
  const encoding: string = (data as any).cryptoEncoding ?? 'hex';

  const visibleFields = VISIBLE_FIELDS[operation] ?? new Set();
  const algorithmOptions = ALGORITHM_OPTIONS[operation] ?? [];

  const handleOperationChange = React.useCallback((newOperation: string) => {
    if (isRunMode) return;
    const newAlgorithms = ALGORITHM_OPTIONS[newOperation];
    onUpdate({
      ...data,
      cryptoOperation: newOperation,
      cryptoAlgorithm: newAlgorithms ? newAlgorithms[0] : '',
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleAlgorithmChange = React.useCallback((newAlgorithm: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      cryptoAlgorithm: newAlgorithm,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleValueChange = React.useCallback((val: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      cryptoValue: val,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleKeyChange = React.useCallback((val: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      cryptoKey: val,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleSecretChange = React.useCallback((val: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      cryptoSecret: val,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleTokenChange = React.useCallback((val: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      cryptoToken: val,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handlePayloadChange = React.useCallback((val: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      cryptoPayload: val,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleEncodingChange = React.useCallback((newEncoding: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      cryptoEncoding: newEncoding,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  return (
    <div className="space-y-4 pt-2">
      {/* Info header */}
      <div className="flex items-center gap-1.5">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('title')}</span>
        <Popover>
          <PopoverTrigger asChild>
            <button
              type="button"
              className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5"
            >
              <Info className="h-3 w-3 text-slate-400 dark:text-slate-500" />
            </button>
          </PopoverTrigger>
          <PopoverContent className="w-72 p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
            <div className="space-y-2 text-sm text-slate-600 dark:text-slate-300">
              <p className="font-semibold text-slate-900 dark:text-slate-100">{t('infoTitle')}</p>
              <p>{t('infoDescription')}</p>
            </div>
          </PopoverContent>
        </Popover>
      </div>

      {/* Operation */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('operation')}</span>
        <Select value={operation} onValueChange={handleOperationChange} disabled={isRunMode}>
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {OPERATIONS.map((op) => (
              <SelectItem key={op} value={op}>
                {t(`operations.${op}`)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* Algorithm */}
      {visibleFields.has('algorithm') && algorithmOptions.length > 0 && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('algorithm')}</span>
          <Select value={algorithm} onValueChange={handleAlgorithmChange} disabled={isRunMode}>
            <SelectTrigger className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {algorithmOptions.map((alg) => (
                <SelectItem key={alg} value={alg}>
                  {t(`algorithms.${alg}`)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      )}

      {/* Value */}
      {visibleFields.has('value') && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('value')}</span>
          <ExpressionEditor
            value={value}
            onChange={handleValueChange}
            placeholder={t('valuePlaceholder')}
            className="w-full"
            unknownVariables={findUnknownVariables({ cryptoValue: value })}
            handleId={`crypto-value-${node.id}`}
            connections={connectionProps.connections}
            onHandleClick={connectionProps.handleHandleClick}
            draggingFromHandle={connectionProps.draggingFromHandle}
            onHandleMouseDown={connectionProps.handleHandleMouseDown}
            onHandleMouseUp={connectionProps.handleHandleMouseUp}
            hoveredTargetHandle={connectionProps.hoveredTargetHandle}
            onSetHandleRef={connectionProps.handleSetHandleRef}
            readOnly={isRunMode}
          />
        </div>
      )}

      {/* Key */}
      {visibleFields.has('key') && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('key')}</span>
          <ExpressionEditor
            value={key}
            onChange={handleKeyChange}
            placeholder={t('keyPlaceholder')}
            className="w-full"
            unknownVariables={findUnknownVariables({ cryptoKey: key })}
            handleId={`crypto-key-${node.id}`}
            connections={connectionProps.connections}
            onHandleClick={connectionProps.handleHandleClick}
            draggingFromHandle={connectionProps.draggingFromHandle}
            onHandleMouseDown={connectionProps.handleHandleMouseDown}
            onHandleMouseUp={connectionProps.handleHandleMouseUp}
            hoveredTargetHandle={connectionProps.hoveredTargetHandle}
            onSetHandleRef={connectionProps.handleSetHandleRef}
            readOnly={isRunMode}
          />
        </div>
      )}

      {/* Secret */}
      {visibleFields.has('secret') && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('secret')}</span>
          <ExpressionEditor
            value={secret}
            onChange={handleSecretChange}
            placeholder={t('secretPlaceholder')}
            className="w-full"
            unknownVariables={findUnknownVariables({ cryptoSecret: secret })}
            handleId={`crypto-secret-${node.id}`}
            connections={connectionProps.connections}
            onHandleClick={connectionProps.handleHandleClick}
            draggingFromHandle={connectionProps.draggingFromHandle}
            onHandleMouseDown={connectionProps.handleHandleMouseDown}
            onHandleMouseUp={connectionProps.handleHandleMouseUp}
            hoveredTargetHandle={connectionProps.hoveredTargetHandle}
            onSetHandleRef={connectionProps.handleSetHandleRef}
            readOnly={isRunMode}
          />
        </div>
      )}

      {/* Token */}
      {visibleFields.has('token') && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('token')}</span>
          <ExpressionEditor
            value={token}
            onChange={handleTokenChange}
            placeholder={t('tokenPlaceholder')}
            className="w-full"
            unknownVariables={findUnknownVariables({ cryptoToken: token })}
            handleId={`crypto-token-${node.id}`}
            connections={connectionProps.connections}
            onHandleClick={connectionProps.handleHandleClick}
            draggingFromHandle={connectionProps.draggingFromHandle}
            onHandleMouseDown={connectionProps.handleHandleMouseDown}
            onHandleMouseUp={connectionProps.handleHandleMouseUp}
            hoveredTargetHandle={connectionProps.hoveredTargetHandle}
            onSetHandleRef={connectionProps.handleSetHandleRef}
            readOnly={isRunMode}
          />
        </div>
      )}

      {/* Payload */}
      {visibleFields.has('payload') && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('payload')}</span>
          <ExpressionEditor
            value={payload}
            onChange={handlePayloadChange}
            placeholder={'{"sub": "1234", "name": "John"}'}
            className="w-full"
            unknownVariables={findUnknownVariables({ cryptoPayload: payload })}
            handleId={`crypto-payload-${node.id}`}
            connections={connectionProps.connections}
            onHandleClick={connectionProps.handleHandleClick}
            draggingFromHandle={connectionProps.draggingFromHandle}
            onHandleMouseDown={connectionProps.handleHandleMouseDown}
            onHandleMouseUp={connectionProps.handleHandleMouseUp}
            hoveredTargetHandle={connectionProps.hoveredTargetHandle}
            onSetHandleRef={connectionProps.handleSetHandleRef}
            readOnly={isRunMode}
          />
        </div>
      )}

      {/* Encoding */}
      {visibleFields.has('encoding') && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('encoding')}</span>
          <Select value={encoding} onValueChange={handleEncodingChange} disabled={isRunMode}>
            <SelectTrigger className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {ENCODINGS.map((enc) => (
                <SelectItem key={enc} value={enc}>
                  {t(`encodings.${enc}`)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      )}
    </div>
  );
}
