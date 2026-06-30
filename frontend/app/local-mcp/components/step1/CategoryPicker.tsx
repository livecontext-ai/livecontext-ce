import React from 'react';
import { Label } from '@/components/ui/label';
import { LOCAL_MCP_CATEGORIES } from '../../types';

interface CategoryPickerProps {
  selectedCategory: string;
  onSelectCategory: (categoryId: string) => void;
  selectedSubcategory: string;
  onSelectSubcategory: (subcategoryId: string) => void;
}

export const CategoryPicker: React.FC<CategoryPickerProps> = ({
  selectedCategory,
  onSelectCategory,
  selectedSubcategory,
  onSelectSubcategory
}) => {
  const categoryData = LOCAL_MCP_CATEGORIES.find(cat => cat.id === selectedCategory);

  return (
    <div className="space-y-6">
      <div>
        <Label className="text-base font-medium text-theme-primary mb-4 block">
          Choose a category
        </Label>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
          {LOCAL_MCP_CATEGORIES.map((category) => (
            <button
              type="button"
              key={category.id}
              onClick={() => onSelectCategory(category.id)}
              className={`text-left p-4 rounded-lg border transition-all ${
                selectedCategory === category.id
                  ? 'border-blue-500 bg-blue-50 dark:bg-blue-900/30'
                  : 'border-theme bg-theme-tertiary hover:border-blue-300'
              }`}
            >
              <h4 className="font-medium text-theme-primary text-sm mb-1">{category.name}</h4>
              <p className="text-xs text-theme-secondary">{category.description}</p>
            </button>
          ))}
        </div>
      </div>

      {categoryData && (
        <div>
          <Label className="text-base font-medium text-theme-primary mb-4 block">
            Subcategory
          </Label>
          <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-2">
            {categoryData.subcategories.map((subcategory) => (
              <button
                type="button"
                key={subcategory.id}
                onClick={() => onSelectSubcategory(subcategory.id)}
                className={`p-3 rounded-lg border transition-all text-center ${
                  selectedSubcategory === subcategory.id
                    ? 'border-blue-500 bg-blue-50 dark:bg-blue-900/30'
                    : 'border-theme bg-theme-tertiary hover:border-blue-300'
                }`}
              >
                <span className="text-sm font-medium text-theme-primary">
                  {subcategory.name}
                </span>
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};

export default CategoryPicker;
