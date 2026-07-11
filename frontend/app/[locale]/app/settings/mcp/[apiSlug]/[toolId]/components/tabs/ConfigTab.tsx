"use client";

import React from "react";
import { useTranslations } from 'next-intl';
import { Settings, Info } from "lucide-react";
import { Textarea } from "@/components/ui/textarea";
import { Input } from "@/components/ui/input";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import { UriInput } from "@/app/[locale]/app/settings/developers/components/common";
import { ToolTabProps } from "./types";

interface ConfigTabProps extends ToolTabProps {
    updateEndpoint: (value: string) => void;
}

const ConfigTab: React.FC<ConfigTabProps> = ({
    tool,
    toolState,
    setToolState,
    isEditing,
    api,
    setConfigError,
    updateEndpoint,
}) => {
    const t = useTranslations('mcp.toolDetail.configTab');
    return (
        <div className="space-y-6">
            {/* Tool Category and Name */}
            {/* Tool Category */}
            <div className="space-y-2">
                <label className="text-sm font-medium text-theme-primary dark:text-white">
                    {t('toolCategory')}
                </label>
                <div className="relative">
                    <Input
                        value={tool?.toolCategory || tool?.category || 'Not set'}
                        disabled={true}
                        className="bg-muted/30 disabled:opacity-100 disabled:cursor-default disabled:text-foreground focus-visible:ring-0"
                    />
                    {tool?.isCustomCategory && (
                        <div className="absolute -top-2 -right-2 bg-yellow-500 text-white text-xs px-2 py-1 rounded-full z-10">
                            {t('badges.custom')}
                        </div>
                    )}
                    {!tool?.toolCategory && !tool?.category && (
                        <div className="absolute -top-2 -right-2 bg-red-500 text-white text-xs px-2 py-1 rounded-full z-10">
                            {t('badges.missing')}
                        </div>
                    )}
                </div>
                {!tool?.toolCategory && !tool?.category && (
                    <p className="text-xs text-amber-600 dark:text-amber-400 mt-1">
                        {t('warnings.categoryMissing')}
                    </p>
                )}
            </div>

            {/* Tool Name */}
            <div className="space-y-2">
                <label className="text-sm font-medium text-theme-primary dark:text-white">
                    {t('toolName')}
                </label>
                <div className="relative">
                    <Input
                        value={toolState?.name || 'Not set'}
                        disabled={true}
                        className="bg-muted/30 disabled:opacity-100 disabled:cursor-default disabled:text-foreground focus-visible:ring-0"
                    />
                    {tool?.isCustomToolName && (
                        <div className="absolute -top-2 -right-2 bg-yellow-500 text-white text-xs px-2 py-1 rounded-full z-10">
                            {t('badges.custom')}
                        </div>
                    )}
                </div>
            </div>


            {/* Method and Active Status */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                <div className="space-y-2">
                    <label className="text-sm font-medium text-theme-primary dark:text-white">
                        {t('httpMethod')}
                    </label>
                    <Select
                        value={toolState?.method || 'GET'}
                        onValueChange={(value) => setToolState(prev => prev ? { ...prev, method: value as any } : null)}
                        disabled={!isEditing}
                    >
                        <SelectTrigger className="w-full disabled:opacity-100 disabled:cursor-default disabled:bg-muted/30">
                            <SelectValue placeholder={t('placeholders.selectMethod')} />
                        </SelectTrigger>
                        <SelectContent>
                            <SelectItem value="GET">GET</SelectItem>
                            <SelectItem value="POST">POST</SelectItem>
                            <SelectItem value="PUT">PUT</SelectItem>
                            <SelectItem value="DELETE">DELETE</SelectItem>
                            <SelectItem value="PATCH">PATCH</SelectItem>
                        </SelectContent>
                    </Select>
                </div>

                <div className="space-y-2">
                    <label className="text-sm font-medium text-theme-primary dark:text-white">
                        {t('isActive')}
                    </label>
                    <div className="space-y-2">
                        <Select
                            value={toolState?.isActive ? 'true' : 'false'}
                            onValueChange={(value) => {
                                if (value === 'true' && toolState?.status !== 'approved') {
                                    setConfigError?.('Cannot activate tool: Tool must be approved first');
                                    return;
                                }
                                setToolState(prev => prev ? { ...prev, isActive: value === 'true' } : null);
                            }}
                            disabled={!isEditing || toolState?.status !== 'approved'}
                        >
                            <SelectTrigger className="w-full disabled:opacity-100 disabled:cursor-default disabled:bg-muted/30">
                                <SelectValue placeholder={t('placeholders.selectStatus')} />
                            </SelectTrigger>
                            <SelectContent>
                                <SelectItem
                                    value="true"
                                    disabled={toolState?.status !== 'approved'}
                                >
                                    {t('activeOptions.true')} {toolState?.status !== 'approved' ? t('activeOptions.requiresApproval') : ''}
                                </SelectItem>
                                <SelectItem value="false">{t('activeOptions.false')}</SelectItem>
                            </SelectContent>
                        </Select>
                        {isEditing && toolState?.status !== 'approved' && (
                            <div className="text-sm text-yellow-600 dark:text-yellow-400 bg-yellow-50 dark:bg-yellow-900/20 p-2 rounded border border-yellow-200 dark:border-yellow-700">
                                <strong>Note:</strong> {t('warnings.approvalRequired')}
                                {t('warnings.currentStatus')} <span className="font-semibold">{toolState?.status || 'Unknown'}</span>
                            </div>
                        )}
                    </div>
                </div>
            </div>

            {/* Endpoint URL */}
            <div className="space-y-2">
                <label className="text-sm font-medium text-theme-primary dark:text-white">
                    {t('endpointUrl')}
                </label>
                {isEditing ? (
                    <UriInput
                        value={toolState?.endpoint || ''}
                        onChange={updateEndpoint}
                        baseUrl={api?.baseUrl || ""}
                        placeholder={t('placeholders.endpointExample')}
                        required={true}
                        showFullUrl={!!api?.baseUrl}
                        showValidation={true}
                    />
                ) : (
                    <div className="flex h-9 w-full items-center rounded-md border border-input bg-muted/30 px-3 text-sm ring-offset-background file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-foreground/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 opacity-100 text-foreground">
                        {toolState?.endpoint ? (api?.baseUrl ? `${api.baseUrl}${toolState.endpoint}` : toolState.endpoint) : t('placeholders.notSet')}
                    </div>
                )}
            </div>

            {/* Description */}
            <div className="space-y-2">
                <label className="text-sm font-medium text-theme-primary dark:text-white">
                    {t('description')}
                </label>
                <div className="space-y-3">
                    <div className="p-3 bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-700 rounded-lg">
                        <div className="flex items-center space-x-2">
                            <Info className="w-4 h-4 text-blue-600 dark:text-blue-400" />
                            <span className="text-sm font-medium text-blue-800 dark:text-blue-200">
                                {t('info.title')}
                            </span>
                        </div>
                        <p className="text-sm text-blue-700 dark:text-blue-300 mt-1">
                            {t('info.description')}
                        </p>
                    </div>

                    <div>
                        <Textarea
                            value={toolState?.description || ''}
                            onChange={(e) => {
                                if (e.target.value.length <= 250) {
                                    setToolState(prev => prev ? { ...prev, description: e.target.value } : null);
                                }
                            }}
                            placeholder={t('placeholders.descriptionExample')}
                            rows={3}
                            disabled={!isEditing}
                            className="disabled:bg-muted/30 disabled:opacity-100 disabled:cursor-default disabled:text-foreground focus-visible:ring-0"
                        />
                        {isEditing && (
                            <div className="flex justify-end">
                                <span className={`text-xs ${(toolState?.description?.length || 0) > 200 ? 'text-red-500' : 'text-gray-500'}`}>
                                    {toolState?.description?.length || 0}/250 {t('characters')}
                                </span>
                            </div>
                        )}
                    </div>
                </div>
            </div>
        </div >
    );
};

export default ConfigTab;
