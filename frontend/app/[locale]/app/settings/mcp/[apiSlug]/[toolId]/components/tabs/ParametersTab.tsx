"use client";

import React from "react";
import { useTranslations } from 'next-intl';
import { Code, FileJson, Shield, FileText, Plus, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Checkbox } from "@/components/ui/checkbox";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import { LucideIcon } from "lucide-react";

interface ParametersTabProps {
    title: string;
    description?: string;
    icon: LucideIcon;
    iconColor: string;
    parameters: any[];
    isEditing: boolean;
    parameterType: 'path' | 'query' | 'header' | 'body';
    emptyMessage: string;
    emptyHint?: string;
    addParameter: (type: string) => void;
    updateParameter: (type: string, index: number, field: string, value: any) => void;
    removeParameter: (type: string, index: number) => void;
    getDisplayParameters: (type: 'path' | 'query' | 'header' | 'body') => any[];
    showValueField?: boolean; // For headers and body params
}

const ParametersTab: React.FC<ParametersTabProps> = ({
    title,
    description,
    icon: Icon,
    iconColor,
    parameters,
    isEditing,
    parameterType,
    emptyMessage,
    emptyHint,
    addParameter,
    updateParameter,
    removeParameter,
    getDisplayParameters,
    showValueField = false,
}) => {
    const t = useTranslations('mcp.toolDetail.parametersTab');
    const displayParams = getDisplayParameters(parameterType);

    return (
        <div className="space-y-4">
            {/* Add Parameter Button */}
            {isEditing && (
                <div className="flex justify-end">
                    <Button
                        size="sm"
                        onClick={() => addParameter(parameterType)}
                        className="flex items-center space-x-2"
                    >
                        <Plus className="w-4 h-4" />
                        <span>{t('addParameter')}</span>
                    </Button>
                </div>
            )}

            {/* Parameters List */}
            {displayParams.length === 0 ? (
                <div className="text-center py-8 text-gray-500">
                    <Icon className="w-12 h-12 mx-auto mb-4 text-gray-300" />
                    <p>{emptyMessage}</p>
                    {emptyHint && <p className="text-sm mt-2">{emptyHint}</p>}
                </div>
            ) : (
                <div className="space-y-4">
                    {displayParams.map((param, index) => (
                        <div
                            key={index}
                            className="py-3 border-b border-theme/10 last:border-b-0"
                        >
                            <div className="flex items-start justify-between gap-4">
                                <div className="flex-1 grid grid-cols-1 md:grid-cols-2 gap-4">
                                    {/* Row 1: Name + Type */}
                                    <div>
                                        <label className="text-xs font-medium text-theme-secondary dark:text-gray-300 uppercase tracking-wide">
                                            {t('labels.name')}
                                        </label>
                                        <Input
                                            value={param.name || ''}
                                            onChange={(e) => updateParameter(parameterType, index, 'name', e.target.value)}
                                            placeholder={t('placeholders.parameterName')}
                                            className="mt-1 disabled:bg-muted/30 disabled:opacity-100 disabled:cursor-default disabled:text-foreground focus-visible:ring-0"
                                            disabled={!isEditing}
                                        />
                                    </div>

                                    <div>
                                        <label className="text-xs font-medium text-theme-secondary dark:text-gray-300 uppercase tracking-wide">
                                            {t('labels.type')}
                                        </label>
                                        <Select
                                            value={param.type}
                                            onValueChange={(value) => updateParameter(parameterType, index, 'type', value)}
                                            disabled={!isEditing}
                                        >
                                            <SelectTrigger className="w-full mt-1 disabled:opacity-100 disabled:cursor-default disabled:bg-muted/30">
                                                <SelectValue />
                                            </SelectTrigger>
                                            <SelectContent>
                                                <SelectItem value="string">{t('types.string')}</SelectItem>
                                                <SelectItem value="number">{t('types.number')}</SelectItem>
                                                <SelectItem value="boolean">{t('types.boolean')}</SelectItem>
                                                {parameterType === 'body' && <SelectItem value="file">{t('types.file')}</SelectItem>}
                                            </SelectContent>
                                        </Select>
                                    </div>

                                    {/* Row 2: Value/Example + Required */}
                                    {showValueField ? (
                                        <div>
                                            <label className="text-xs font-medium text-theme-secondary dark:text-gray-300 uppercase tracking-wide">
                                                {t('labels.value')}
                                            </label>
                                            <Input
                                                value={param.value || ''}
                                                onChange={(e) => updateParameter(parameterType, index, 'value', e.target.value)}
                                                placeholder={t('placeholders.value')}
                                                className="mt-1 disabled:bg-muted/30 disabled:opacity-100 disabled:cursor-default disabled:text-foreground focus-visible:ring-0"
                                                disabled={!isEditing}
                                            />
                                        </div>
                                    ) : (
                                        <div>
                                            <label className="text-xs font-medium text-theme-secondary dark:text-gray-300 uppercase tracking-wide">
                                                {t('labels.example')}
                                            </label>
                                            <Input
                                                value={param.example || (param as any).exampleValue || ''}
                                                onChange={(e) => updateParameter(parameterType, index, 'example', e.target.value)}
                                                placeholder={t('placeholders.exampleValue')}
                                                className="mt-1 disabled:bg-muted/30 disabled:opacity-100 disabled:cursor-default disabled:text-foreground focus-visible:ring-0"
                                                disabled={!isEditing}
                                            />
                                        </div>
                                    )}

                                    <div className="flex items-center gap-4">
                                        <div className="flex-1">
                                            <label className="text-xs font-medium text-theme-secondary dark:text-gray-300 uppercase tracking-wide">
                                                {t('labels.required')}
                                            </label>
                                            <div className="mt-2 flex items-center gap-2">
                                                <Checkbox
                                                    id={`required-${parameterType}-${index}`}
                                                    checked={param.required}
                                                    onCheckedChange={(checked) => updateParameter(parameterType, index, 'required', !!checked)}
                                                    disabled={!isEditing}
                                                    className="disabled:opacity-100 disabled:cursor-default"
                                                />
                                                <label
                                                    htmlFor={`required-${parameterType}-${index}`}
                                                    className={`text-sm ${!isEditing ? "text-theme-secondary" : "text-theme-primary cursor-pointer"}`}
                                                >
                                                    {param.required ? t('requiredOptions.yes') : t('requiredOptions.no')}
                                                </label>
                                            </div>
                                        </div>
                                    </div>

                                    {/* Row 3: Description (full width) */}
                                    {!showValueField && (
                                        <div className="md:col-span-2">
                                            <label className="text-xs font-medium text-theme-secondary dark:text-gray-300 uppercase tracking-wide">
                                                {t('labels.description')}
                                            </label>
                                            <Input
                                                value={param.description || ''}
                                                onChange={(e) => updateParameter(parameterType, index, 'description', e.target.value)}
                                                placeholder={t('placeholders.description')}
                                                className="mt-1 disabled:bg-muted/30 disabled:opacity-100 disabled:cursor-default disabled:text-foreground focus-visible:ring-0"
                                                disabled={!isEditing}
                                            />
                                        </div>
                                    )}
                                </div>

                                {isEditing && (
                                    <Button
                                        variant="ghost"
                                        size="sm"
                                        onClick={() => removeParameter(parameterType, index)}
                                        className="text-red-600 hover:text-red-700 shrink-0"
                                    >
                                        <X className="w-4 h-4" />
                                    </Button>
                                )}
                            </div>
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
};

export default ParametersTab;
