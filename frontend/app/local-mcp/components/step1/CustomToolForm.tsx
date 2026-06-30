import React from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { CategoryPicker } from './CategoryPicker';
import { useTranslations } from 'next-intl';

interface CustomToolFormProps {
  showForm: boolean;
  onToggleForm: () => void;
  selectedCategory: string;
  onSelectCategory: (categoryId: string) => void;
  selectedSubcategory: string;
  onSelectSubcategory: (subcategoryId: string) => void;
  toolName: string;
  onToolNameChange: (value: string) => void;
  toolDescription: string;
  onToolDescriptionChange: (value: string) => void;
}

export const CustomToolForm: React.FC<CustomToolFormProps> = ({
  showForm,
  onToggleForm,
  selectedCategory,
  onSelectCategory,
  selectedSubcategory,
  onSelectSubcategory,
  toolName,
  onToolNameChange,
  toolDescription,
  onToolDescriptionChange
}) => {
  const t = useTranslations('localMcp');
  return (
    <Card className="bg-theme-secondary border-theme">
      <CardHeader>
        <CardTitle className="text-theme-primary">{t('customToolForm.title')}</CardTitle>
        <CardDescription className="text-theme-secondary">
          {t('customToolForm.description')}
        </CardDescription>
      </CardHeader>

      <CardContent>
        {!showForm ? (
          <div className="text-center py-8">
            <Button
              onClick={onToggleForm}
              className="bg-theme-primary text-theme-secondary hover:bg-theme-primary/90"
              size="lg"
            >
              {t('customToolForm.createButton')}
            </Button>
          </div>
        ) : (
          <div className="space-y-6">
            <CategoryPicker
              selectedCategory={selectedCategory}
              onSelectCategory={onSelectCategory}
              selectedSubcategory={selectedSubcategory}
              onSelectSubcategory={onSelectSubcategory}
            />

            <div className="grid grid-cols-1 gap-6">
              <div>
                <Label htmlFor="toolName" className="text-theme-primary">
                  {t('customToolForm.toolNameLabel')}
                </Label>
                <Input
                  id="toolName"
                  value={toolName}
                  onChange={(e) => onToolNameChange(e.target.value)}
                  placeholder={t('customToolForm.toolNamePlaceholder')}
                  className="bg-theme-tertiary border-theme text-theme-primary"
                />
              </div>

              <div>
                <Label htmlFor="toolDescription" className="text-theme-primary">
                  {t('customToolForm.descriptionLabel')}
                </Label>
                <Textarea
                  id="toolDescription"
                  value={toolDescription}
                  onChange={(e) => onToolDescriptionChange(e.target.value)}
                  placeholder={t('customToolForm.descriptionPlaceholder')}
                  rows={3}
                  className="bg-theme-tertiary border-theme text-theme-primary"
                />
              </div>
            </div>

          </div>
        )}
      </CardContent>
    </Card>
  );
};

export default CustomToolForm;
