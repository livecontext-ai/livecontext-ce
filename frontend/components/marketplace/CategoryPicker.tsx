'use client';

import React, { useCallback, useEffect, useState } from 'react';
import { useTranslations } from 'next-intl';
import * as LucideIcons from 'lucide-react';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { orchestratorApi } from '@/lib/api';

/**
 * Resolve a Lucide icon component from a category's iconSlug. The catalog stores
 * iconSlug as kebab-case (e.g. "shopping-bag"); Lucide exports the PascalCase
 * (e.g. ShoppingBag). Returns null when the slug is unset or the lookup fails
 * so callers can fall back to a no-icon layout.
 */
function getCategoryIcon(iconSlug?: string): React.ComponentType<{ className?: string }> | null {
  if (!iconSlug) return null;
  const pascalCase = iconSlug
    .split('-')
    .map(word => word.charAt(0).toUpperCase() + word.slice(1))
    .join('');
  // Lucide's type is too loose to constrain at compile-time - keyed lookup is
  // intentional and the null fallback above protects all callsites.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return ((LucideIcons as any)[pascalCase] as React.ComponentType<{ className?: string }>) || null;
}

interface Category {
  id: string;
  name: string;
  iconSlug?: string;
  slug?: string;
}

interface CategoryPickerProps {
  /** Currently selected category id, or "none" / undefined for no selection. */
  value: string;
  /** Called with the selected category id, or "none" when cleared. */
  onChange: (value: string) => void;
  /** Optional className forwarded to the SelectTrigger wrapper. */
  className?: string;
  /** When true, the Select is disabled (e.g. while saving). */
  disabled?: boolean;
  /**
   * Optional pre-fetched categories. If provided, the component skips its own
   * fetch - useful in parents that already load categories for other reasons
   * (icon thumbnails, autocomplete) to avoid a duplicate HTTP round-trip.
   */
  categories?: Category[];
  /**
   * Whether to offer the "No Category" option. Defaults to true (back-compat).
   * Set false on surfaces where a category is mandatory (e.g. publishing an
   * application), so the publisher can never clear the selection.
   */
  allowNone?: boolean;
}

/**
 * Shared marketplace category picker - used by every publish surface
 * (ShareWorkflowModal for workflows/applications, PublishResourceModal for
 * tables/interfaces/skills, PublishAgentModal for agents). Centralises:
 *
 * <ul>
 *   <li>The {@code orchestratorApi.getCategories(true)} fetch</li>
 *   <li>The kebab-case → Lucide icon resolution</li>
 *   <li>The "none" sentinel that the publisher selects to clear category</li>
 *   <li>The shared i18n keys under {@code publishWorkflow.*}</li>
 * </ul>
 *
 * <p>Pre-fix only ShareWorkflowModal had a category picker; PublishResourceModal
 * + PublishAgentModal (and the 4 MCP publish modules) silently dropped
 * {@code categoryId} on the floor so all non-workflow Templates landed in the
 * catalog uncategorised. Backend accepts {@code categoryId} on every publish
 * endpoint already - only the wiring was missing (audit finding D-1/D-4).
 */
export function CategoryPicker({
  value,
  onChange,
  className,
  disabled,
  categories: providedCategories,
  allowNone = true,
}: CategoryPickerProps) {
  const t = useTranslations('publishWorkflow');
  const [fetched, setFetched] = useState<Category[] | null>(null);
  const [isLoading, setIsLoading] = useState(false);

  const fetchCategories = useCallback(async () => {
    setIsLoading(true);
    try {
      const response = await orchestratorApi.getCategories(true);
      setFetched(response.categories ?? []);
    } catch {
      setFetched([]);
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!providedCategories) {
      void fetchCategories();
    }
  }, [providedCategories, fetchCategories]);

  const categories = providedCategories ?? fetched ?? [];
  const showLoadingPlaceholder = providedCategories ? false : isLoading;

  return (
    <Select value={value} onValueChange={onChange} disabled={disabled || showLoadingPlaceholder}>
      <SelectTrigger className={className ?? 'w-full'}>
        <SelectValue placeholder={showLoadingPlaceholder ? t('loadingCategories') : t('selectCategory')} />
      </SelectTrigger>
      <SelectContent className="z-[10000]">
        {allowNone && (
          <SelectItem value="none">
            <span className="text-theme-secondary">{t('noCategory')}</span>
          </SelectItem>
        )}
        {categories.map((cat) => {
          const IconComponent = getCategoryIcon(cat.iconSlug);
          return (
            <SelectItem key={cat.id} value={cat.id}>
              <div className="flex items-center gap-2">
                {IconComponent && <IconComponent className="h-3.5 w-3.5 flex-shrink-0" />}
                <span>{cat.name}</span>
              </div>
            </SelectItem>
          );
        })}
      </SelectContent>
    </Select>
  );
}
