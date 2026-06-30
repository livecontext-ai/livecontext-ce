'use client';

import { useEffect, useState } from 'react';
import { useTranslations } from 'next-intl';
import * as LucideIcons from 'lucide-react';
import { WorkflowCategory, orchestratorApi } from '@/lib/api';
import { useAuth } from '@/lib/providers/smart-providers';
import { IS_CE } from '@/lib/edition';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';

const CE_API_BASE = 'https://livecontext.ai/api';

interface CategoryFilterProps {
  selectedCategory?: string;
  onCategoryChange: (categorySlug?: string) => void;
  className?: string;
}

export function CategoryFilter({
  selectedCategory,
  onCategoryChange,
  className = ''
}: CategoryFilterProps) {
  const t = useTranslations('marketplace');
  const { isLoading: isAuthLoading } = useAuth();
  const [categories, setCategories] = useState<WorkflowCategory[]>([]);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    if (!IS_CE && isAuthLoading) return;

    const fetchCategories = IS_CE
      ? fetch(`${CE_API_BASE}/categories?activeOnly=true`)
          .then(res => res.json())
          .then(data => data as { categories?: WorkflowCategory[] })
      : orchestratorApi.getCategories(true);

    fetchCategories
      .then(({ categories }) => {
        setCategories(categories ?? []);
      })
      .catch((error) => {
        console.error('Failed to load categories:', error);
      })
      .finally(() => {
        setIsLoading(false);
      });
  }, [isAuthLoading]);

  if (isLoading) {
    return (
      <div className="h-10 w-48 rounded-xl bg-theme-primary border border-theme animate-pulse" />
    );
  }

  const getIconComponent = (iconSlug?: string) => {
    if (!iconSlug) return null;

    // Convert kebab-case to PascalCase (e.g., 'bar-chart-3' -> 'BarChart3')
    const pascalCase = iconSlug
      .split('-')
      .map((word, i) => i === 0 ? word.charAt(0).toUpperCase() + word.slice(1) : word.charAt(0).toUpperCase() + word.slice(1))
      .join('');

    return (LucideIcons as any)[pascalCase];
  };

  const selectedCategoryData = categories.find(cat => cat.slug === selectedCategory);
  const SelectedIcon = selectedCategoryData ? getIconComponent(selectedCategoryData.iconSlug) : null;

  return (
    <Select
      value={selectedCategory || 'all'}
      onValueChange={(value) => onCategoryChange(value === 'all' ? undefined : value)}
    >
      <SelectTrigger className={`h-10 w-48 rounded-xl bg-theme-primary border-theme ${className}`}>
        <SelectValue>
          <div className="flex items-center gap-2">
            {selectedCategory ? (
              <>
                {SelectedIcon && <SelectedIcon className="h-3.5 w-3.5 flex-shrink-0" />}
                <span className="text-sm">{selectedCategoryData?.name}</span>
              </>
            ) : (
              <>
                <LucideIcons.LayoutGrid className="h-3.5 w-3.5" />
                <span className="text-sm">{t('allCategories')}</span>
              </>
            )}
          </div>
        </SelectValue>
      </SelectTrigger>
      <SelectContent>
        <SelectItem value="all">
          <div className="flex items-center gap-2">
            <LucideIcons.LayoutGrid className="h-3.5 w-3.5" />
            <span>{t('allCategories')}</span>
          </div>
        </SelectItem>
        {categories.map((category) => {
          const IconComponent = getIconComponent(category.iconSlug);
          return (
            <SelectItem key={category.id} value={category.slug}>
              <div className="flex items-center gap-2">
                {IconComponent && <IconComponent className="h-3.5 w-3.5 flex-shrink-0" />}
                <span>{category.name}</span>
              </div>
            </SelectItem>
          );
        })}
      </SelectContent>
    </Select>
  );
}
