import React from "react";
import { Wrench, Copy } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { useTranslations } from "next-intl";

interface ApiHeaderProps {
  apiName: string;
  status: "active" | "paused" | "error";
  apiStatus?: string;
  apiId?: string;
}

const ApiHeader: React.FC<ApiHeaderProps> = ({
  apiName,
  status,
  apiStatus,
  apiId,
}) => {
  const t = useTranslations('mcp.header');

  const getStatusText = (status: string) => {
    switch (status) {
      case "active":
      case "approved":
        return t('status.active');
      case "paused":
      case "draft":
        return t('status.paused');
      case "reviewing":
        return t('status.reviewing');
      case "submitted":
        return t('status.submitted');
      case "error":
      case "rejected":
        return t('status.error');
      default:
        return t('status.unknown');
    }
  };

  const getStatusBadgeColor = (status: string) => {
    switch (status) {
      case "active":
      case "approved":
        return "bg-green-100 text-green-800 border-green-200 dark:bg-green-900/20 dark:text-green-300 dark:border-green-700";
      case "paused":
      case "draft":
      case "reviewing":
        return "bg-yellow-100 text-yellow-800 border-yellow-200 dark:bg-yellow-900/20 dark:text-yellow-300 dark:border-yellow-700";
      case "submitted":
        return "bg-blue-100 text-blue-800 border-blue-200 dark:bg-blue-900/20 dark:text-blue-300 dark:border-blue-700";
      case "error":
      case "rejected":
        return "bg-red-100 text-red-800 border-red-200 dark:bg-red-900/20 dark:text-red-300 dark:border-red-700";
      default:
        return "bg-gray-100 text-gray-800 border-gray-200 dark:bg-gray-900/20 dark:text-gray-300 dark:border-gray-700";
    }
  };

  return (
    <div className="flex flex-col gap-6 mb-8">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="space-y-2">
          <div className="flex items-center gap-3">
            <h2 className="text-2xl font-semibold text-theme-primary sm:text-3xl">
              {apiName}
            </h2>
            {apiId && (
              <Badge
                variant="outline"
                className="cursor-pointer text-xs hover:bg-theme-primary/5 transition-colors duration-200"
                onClick={() => {
                  navigator.clipboard.writeText(apiId || "");
                }}
                title={t('copyId')}
              >
                <Copy className="mr-1 h-3 w-3" />
                ID: {apiId.slice(0, 8)}...
              </Badge>
            )}
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <Badge className={getStatusBadgeColor(status)}>
            {getStatusText(status)}
          </Badge>
          {apiStatus && apiStatus !== status && (
            <Badge className={getStatusBadgeColor(apiStatus)}>
              {apiStatus}
            </Badge>
          )}
        </div>
      </div>
    </div>
  );
};

export default ApiHeader;
