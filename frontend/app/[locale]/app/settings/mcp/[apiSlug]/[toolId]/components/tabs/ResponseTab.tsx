"use client";

import React from "react";
import { useTranslations } from 'next-intl';
import { FileJson } from "lucide-react";
import LoadingSpinner from "@/components/LoadingSpinner";

interface ResponseTabProps {
    toolResponses: any[];
    loadingResponses: boolean;
}

const ResponseTab: React.FC<ResponseTabProps> = ({
    toolResponses,
    loadingResponses,
}) => {
    const t = useTranslations('mcp.toolDetail.responseTab');
    return (
        <div className="space-y-4">
            {/* Content */}
            {loadingResponses ? (
                <div className="flex items-center justify-center py-8">
                    <LoadingSpinner />
                </div>
            ) : toolResponses.length === 0 ? (
                <div className="text-center py-8">
                    <FileJson className="w-12 h-12 text-theme-muted mx-auto mb-4" />
                    <h4 className="text-lg font-medium text-theme-primary mb-2">{t('noResponsesTitle')}</h4>
                    <p className="text-theme-muted">
                        {t('noResponsesDescription')}
                    </p>
                </div>
            ) : (
                <div className="space-y-4">
                    {toolResponses.map((response, index) => (
                        <div key={response.id || index} className="py-3 border-b border-theme/10 last:border-b-0">
                            {(response.example || response.example_jsonb) && (
                                <div>
                                    <h5 className="text-sm font-medium text-theme-secondary mb-2">{t('exampleResponse')}</h5>
                                    <pre className="text-sm text-theme-primary whitespace-pre-wrap overflow-auto max-h-64">
                                        {response.example_jsonb
                                            ? (typeof response.example_jsonb === 'string'
                                                ? response.example_jsonb
                                                : JSON.stringify(response.example_jsonb, null, 2))
                                            : (typeof response.example === 'string'
                                                ? response.example
                                                : JSON.stringify(response.example, null, 2))
                                        }
                                    </pre>
                                </div>
                            )}
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
};

export default ResponseTab;
