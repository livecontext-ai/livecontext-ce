'use client';

import { useCallback, useEffect, useState } from 'react';
import { CheckCircle } from 'lucide-react';
import PublicHeader from '@/components/sharing/PublicHeader';
import { useTranslations } from 'next-intl';
import { normalizeFormField, type FormFieldOption } from '@/lib/forms/formFieldShape';

interface FormFieldConfig {
  name: string;
  type: string;
  label: string;
  required?: boolean;
  placeholder?: string;
  options?: FormFieldOption[];
  defaultValue?: unknown;
}

interface FormConfig {
  name: string;
  description?: string;
  formConfig: FormFieldConfig[] | null;
  successMessage?: string;
  isActive: boolean;
}

interface PublicFormProps {
  token: string;
}

/** A field default as the string an input holds: numbers and booleans too, never "undefined". */
function toInputValue(value: unknown): string {
  if (value === undefined || value === null) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return '';
}

export default function PublicForm({ token }: PublicFormProps) {
  const t = useTranslations('publicShare');
  const [config, setConfig] = useState<FormConfig | null>(null);
  const [formData, setFormData] = useState<Record<string, string>>({});
  const [isLoading, setIsLoading] = useState(true);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string>('');

  // Fetch form config
  useEffect(() => {
    async function loadConfig() {
      try {
        const res = await fetch(`/form/${token}/config`, {
          method: 'GET',
          headers: { 'Content-Type': 'application/json' },
        });

        if (!res.ok) {
          setError(res.status === 404 ? t('form.notFound') : t('form.failedToLoad'));
          return;
        }

        const data: FormConfig = await res.json();

        if (!data.isActive) {
          setError(t('form.noLongerAccepting'));
          return;
        }

        // The copy this page reads may hold string-shorthand options or a `default` alias
        // written before the builder wrote the canonical shape: read it in that shape.
        const formConfig = data.formConfig
          ? data.formConfig
              .map((field, index) => normalizeFormField(field, index) as FormFieldConfig)
              // An entry that is not a named field cannot become an input: skip it.
              .filter((field) => field && typeof field === 'object' && typeof field.name === 'string' && field.name !== '')
          : null;
        setConfig({ ...data, formConfig });

        // Initialize form data with defaults
        if (formConfig) {
          const defaults: Record<string, string> = {};
          for (const field of formConfig) {
            defaults[field.name] = toInputValue(field.defaultValue);
          }
          setFormData(defaults);
        }
      } catch {
        setError(t('form.failedToLoad'));
      } finally {
        setIsLoading(false);
      }
    }

    loadConfig();
  }, [token, t]);

  const handleChange = useCallback((fieldName: string, value: string) => {
    setFormData(prev => ({ ...prev, [fieldName]: value }));
  }, []);

  const handleSubmit = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();
    if (!config) return;

    setIsSubmitting(true);
    setError(null);

    try {
      const res = await fetch(`/form/${token}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(formData),
      });

      if (!res.ok) {
        const body = await res.json().catch(() => null);
        setError(body?.error || t('form.failedToSubmit'));
        return;
      }

      const result = await res.json();
      setSuccessMessage(result.successMessage || config.successMessage || t('form.submitSuccess'));
      setSubmitted(true);
    } catch {
      setError(t('form.failedToSubmit'));
    } finally {
      setIsSubmitting(false);
    }
  }, [token, config, formData, t]);

  // Loading state
  if (isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-theme-primary">
        <div className="animate-spin h-6 w-6 border-2 border-theme border-t-[var(--accent-primary)] rounded-full" />
      </div>
    );
  }

  // Error state (no config loaded)
  if (error && !config) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-theme-primary px-4">
        <div className="text-center">
          <div className="h-12 w-12 rounded-xl bg-theme-secondary flex items-center justify-center mx-auto mb-4">
            <svg className="h-5 w-5 text-theme-muted" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m9-.75a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9 3.75h.008v.008H12v-.008Z" />
            </svg>
          </div>
          <p className="text-sm text-theme-secondary">{error}</p>
        </div>
      </div>
    );
  }

  // Success state
  if (submitted) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-theme-primary px-4">
        <div className="bg-theme-secondary rounded-xl shadow-sm border border-theme p-8 max-w-md w-full text-center">
          <div className="h-12 w-12 rounded-xl bg-green-100 dark:bg-green-900/30 flex items-center justify-center mx-auto mb-4">
            <CheckCircle className="h-6 w-6 text-green-600 dark:text-green-400" />
          </div>
          <h2 className="text-base font-semibold text-theme-primary mb-2">
            {t('form.submitted')}
          </h2>
          <p className="text-sm text-theme-secondary">
            {successMessage}
          </p>
        </div>
      </div>
    );
  }

  if (!config) return null;

  const fields = config.formConfig || [];

  return (
    <div className="min-h-screen flex flex-col bg-theme-primary">
      <PublicHeader title={config.name} />

      {/* Form content */}
      <main className="flex-1 flex items-start justify-center px-4 py-8">
        <div className="bg-theme-secondary rounded-xl shadow-sm border border-theme w-full max-w-lg">
          {/* Description */}
          {config.description && (
            <div className="px-6 pt-5 pb-0">
              <p className="text-sm text-theme-secondary">
                {config.description}
              </p>
            </div>
          )}

          {/* Form */}
          <form onSubmit={handleSubmit} className="px-6 py-5 space-y-4">
            {fields.length === 0 ? (
              // No formConfig defined - show a single text field
              <div>
                <label className="block text-sm font-medium text-theme-primary mb-1">
                  {t('form.message')}
                </label>
                <textarea
                  value={formData['message'] || ''}
                  onChange={(e) => handleChange('message', e.target.value)}
                  className="w-full px-3 py-2 text-sm border border-theme rounded-lg bg-theme-primary text-theme-primary placeholder:text-theme-muted focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)] focus:border-transparent"
                  rows={4}
                  placeholder={t('form.messagePlaceholder')}
                  required
                />
              </div>
            ) : (
              fields.map((field) => (
                <FormField
                  key={field.name}
                  field={field}
                  value={formData[field.name] || ''}
                  onChange={(val) => handleChange(field.name, val)}
                />
              ))
            )}

            {error && (
              <p className="text-sm text-red-600 dark:text-red-400">{error}</p>
            )}

            <button
              type="submit"
              disabled={isSubmitting}
              className="w-full px-4 py-2.5 text-sm font-medium text-[var(--accent-foreground)] bg-[var(--accent-primary)] hover:opacity-90 disabled:opacity-50 disabled:cursor-not-allowed rounded-xl transition-opacity"
            >
              {isSubmitting ? t('form.submitting') : t('form.submit')}
            </button>
          </form>
        </div>
      </main>
    </div>
  );
}

// ========== Field renderer ==========

function FormField({
  field,
  value,
  onChange,
}: {
  field: FormFieldConfig;
  value: string;
  onChange: (val: string) => void;
}) {
  const t = useTranslations('publicShare');
  const baseInputClass =
    'w-full px-3 py-2 text-sm border border-theme rounded-lg bg-theme-primary text-theme-primary placeholder:text-theme-muted focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)] focus:border-transparent';

  return (
    <div>
      <label className="block text-sm font-medium text-theme-primary mb-1">
        {field.label}
        {field.required && <span className="text-red-500 ml-0.5">*</span>}
      </label>

      {field.type === 'textarea' ? (
        <textarea
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className={baseInputClass}
          placeholder={field.placeholder}
          required={field.required}
          rows={4}
        />
      ) : field.type === 'select' && field.options ? (
        <select
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className={baseInputClass}
          required={field.required}
        >
          <option value="">{field.placeholder || t('form.selectPlaceholder')}</option>
          {field.options.map((opt) => (
            <option key={opt.id} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
      ) : field.type === 'checkbox' ? (
        <input
          type="checkbox"
          checked={value === 'true'}
          onChange={(e) => onChange(e.target.checked ? 'true' : 'false')}
          className="h-4 w-4 rounded border-theme text-[var(--accent-primary)] focus:ring-[var(--accent-primary)]"
        />
      ) : (
        <input
          type={field.type === 'email' ? 'email' : field.type === 'number' ? 'number' : 'text'}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className={baseInputClass}
          placeholder={field.placeholder}
          required={field.required}
        />
      )}
    </div>
  );
}
