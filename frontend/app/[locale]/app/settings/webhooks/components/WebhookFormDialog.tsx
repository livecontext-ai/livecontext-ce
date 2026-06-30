'use client';

import React, { useState, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Webhook, Eye, EyeOff } from 'lucide-react';
import { useTranslations } from 'next-intl';
import type { StandaloneWebhook, CreateWebhookRequest } from '@/lib/api/orchestrator';

const HTTP_METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const;
const AUTH_TYPES = ['none', 'basic', 'header', 'jwt'] as const;

interface WebhookFormDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSubmit: (data: CreateWebhookRequest) => void;
  webhook?: StandaloneWebhook | null;
  isLoading?: boolean;
}

export function WebhookFormDialog({ open, onOpenChange, onSubmit, webhook, isLoading }: WebhookFormDialogProps) {
  const t = useTranslations('webhookSettings');
  const isEdit = !!webhook;

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [httpMethod, setHttpMethod] = useState('POST');
  const [authType, setAuthType] = useState('none');
  const [authConfig, setAuthConfig] = useState<Record<string, string>>({});
  const [showSecrets, setShowSecrets] = useState<Record<string, boolean>>({});
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  useEffect(() => {
    if (webhook) {
      setName(webhook.name);
      setDescription(webhook.description || '');
      setHttpMethod(webhook.httpMethod);
      setAuthType(webhook.authType);
      setAuthConfig(webhook.authConfig || {});
    } else {
      setName('');
      setDescription('');
      setHttpMethod('POST');
      setAuthType('none');
      setAuthConfig({});
    }
    setShowSecrets({});
  }, [webhook, open]);

  const handleSubmit = () => {
    if (!name.trim()) return;
    onSubmit({
      name,
      description: description || undefined,
      httpMethod,
      authType,
      authConfig: authType !== 'none' ? authConfig : undefined,
    });
  };

  const updateAuthConfig = (key: string, value: string) => {
    setAuthConfig((prev) => ({ ...prev, [key]: value }));
  };

  const toggleShowSecret = (field: string) => {
    setShowSecrets((prev) => ({ ...prev, [field]: !prev[field] }));
  };

  const handleClose = () => onOpenChange(false);

  if (!open || !mounted) return null;

  const modalContent = (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={handleClose}
    >
      <div
        className="max-w-lg w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="text-center mb-6">
          <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-4">
            <Webhook className="w-8 h-8 text-theme-primary" />
          </div>
          <h3 className="text-2xl font-semibold text-theme-primary">
            {isEdit ? t('editWebhook') : t('createWebhook')}
          </h3>
        </div>

        {/* Form */}
        <div className="space-y-5">
          {/* Name */}
          <div>
            <label className="block text-sm font-medium text-theme-primary mb-2">{t('name')}</label>
            <Input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={t('namePlaceholder')}
              className="w-full"
            />
          </div>

          {/* Description */}
          <div>
            <label className="block text-sm font-medium text-theme-primary mb-2">{t('description')}</label>
            <Input
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder={t('descriptionPlaceholder')}
              className="w-full"
            />
          </div>

          {/* HTTP Method */}
          <div>
            <label className="block text-sm font-medium text-theme-primary mb-2">{t('httpMethod')}</label>
            <Select value={httpMethod} onValueChange={setHttpMethod}>
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent className="z-[10000]">
                {HTTP_METHODS.map((m) => (
                  <SelectItem key={m} value={m}>
                    <span className="font-mono text-sm">{m}</span>
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {/* Auth Type */}
          <div>
            <label className="block text-sm font-medium text-theme-primary mb-2">{t('authType')}</label>
            <Select value={authType} onValueChange={setAuthType}>
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent className="z-[10000]">
                {AUTH_TYPES.map((a) => (
                  <SelectItem key={a} value={a}>
                    {a === 'none' ? t('authNone') : a === 'basic' ? t('authBasic') : a === 'header' ? t('authHeader') : t('authJwt')}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {/* Auth Config Fields */}
          {authType === 'basic' && (
            <div className="space-y-4 p-4 bg-theme-secondary rounded-xl border border-theme">
              <div>
                <label className="block text-sm font-medium text-theme-primary mb-1">{t('basicUsername')}</label>
                <Input
                  value={authConfig.basicUsername || ''}
                  onChange={(e) => updateAuthConfig('basicUsername', e.target.value)}
                  className="w-full"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-theme-primary mb-1">{t('basicPassword')}</label>
                <div className="relative">
                  <Input
                    type={showSecrets['basicPassword'] ? 'text' : 'password'}
                    value={authConfig.basicPassword || ''}
                    onChange={(e) => updateAuthConfig('basicPassword', e.target.value)}
                    className="w-full pr-10"
                  />
                  <button
                    type="button"
                    onClick={() => toggleShowSecret('basicPassword')}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-theme-secondary hover:text-theme-primary"
                  >
                    {showSecrets['basicPassword'] ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                  </button>
                </div>
              </div>
            </div>
          )}

          {authType === 'header' && (
            <div className="space-y-4 p-4 bg-theme-secondary rounded-xl border border-theme">
              <div>
                <label className="block text-sm font-medium text-theme-primary mb-1">{t('headerName')}</label>
                <Input
                  value={authConfig.authHeaderName || 'X-API-Key'}
                  onChange={(e) => updateAuthConfig('authHeaderName', e.target.value)}
                  className="w-full"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-theme-primary mb-1">{t('headerValue')}</label>
                <div className="relative">
                  <Input
                    type={showSecrets['authHeaderValue'] ? 'text' : 'password'}
                    value={authConfig.authHeaderValue || ''}
                    onChange={(e) => updateAuthConfig('authHeaderValue', e.target.value)}
                    className="w-full pr-10"
                  />
                  <button
                    type="button"
                    onClick={() => toggleShowSecret('authHeaderValue')}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-theme-secondary hover:text-theme-primary"
                  >
                    {showSecrets['authHeaderValue'] ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                  </button>
                </div>
              </div>
            </div>
          )}

          {authType === 'jwt' && (
            <div className="space-y-4 p-4 bg-theme-secondary rounded-xl border border-theme">
              <div>
                <label className="block text-sm font-medium text-theme-primary mb-1">{t('jwtSecret')}</label>
                <div className="relative">
                  <Input
                    type={showSecrets['jwtSecretKey'] ? 'text' : 'password'}
                    value={authConfig.jwtSecretKey || ''}
                    onChange={(e) => updateAuthConfig('jwtSecretKey', e.target.value)}
                    className="w-full pr-10"
                  />
                  <button
                    type="button"
                    onClick={() => toggleShowSecret('jwtSecretKey')}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-theme-secondary hover:text-theme-primary"
                  >
                    {showSecrets['jwtSecretKey'] ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                  </button>
                </div>
              </div>
              <div>
                <label className="block text-sm font-medium text-theme-primary mb-1">{t('jwtAlgorithm')}</label>
                <Select
                  value={authConfig.jwtAlgorithm || 'HS256'}
                  onValueChange={(v) => updateAuthConfig('jwtAlgorithm', v)}
                >
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent className="z-[10000]">
                    <SelectItem value="HS256">HS256</SelectItem>
                    <SelectItem value="HS384">HS384</SelectItem>
                    <SelectItem value="HS512">HS512</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>
          )}
        </div>

        {/* Actions */}
        <div className="flex gap-3 mt-8">
          <Button
            variant="outline"
            onClick={handleClose}
            disabled={isLoading}
            className="flex-1"
          >
            {t('cancel')}
          </Button>
          <Button
            onClick={handleSubmit}
            disabled={!name.trim() || isLoading}
            className="flex-1"
          >
            {isLoading ? t('saving') : t('save')}
          </Button>
        </div>
      </div>
    </div>
  );

  return createPortal(modalContent, document.body);
}
