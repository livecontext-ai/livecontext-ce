"use client";

import React, { useState, useEffect } from "react";
import { useTranslations } from "next-intl";
import {
  Database,
  Server,
  Code,
  Settings,
  Globe,
  Lock,
  CheckCircle2,
  XCircle,
  AlertCircle,
  Search,
  ChevronDown,
  ChevronRight,
  Info,
  Copy,
  ChevronLeft,
  ChevronRight as ChevronRightIcon,
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { PageTitle } from "@/app/shared/components";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Badge } from "@/components/ui/badge";
import LoadingSpinner from "@/components/LoadingSpinner";
import { useAuthGuard } from "@/hooks/useAuthGuard";
import { cn } from "@/lib/utils";
import { formatUtcDateTime } from "@/lib/utils/dateFormatters";

interface ApiTool {
  id: string;
  name: string;
  description: string;
  endpoint: string;
  method: string;
  protocol?: string;
  runtimeMetadata?: any;
  sqlConfig?: any;
  amqpConfig?: any;
  kafkaConfig?: any;
  mqttConfig?: any;
  redisConfig?: any;
  isActive: boolean;
  createdAt: number;
  updatedAt: number;
  parameters?: ApiToolParameter[];
  monetization?: ApiToolMonetization[];
  responses?: ToolResponse[];
  credentials?: ToolCredential[];
  status: string;
  toolCategories?: {
    id: string;
    name: string;
    slug: string;
    description?: string;
    icon?: string;
    color?: string;
    sortOrder?: number;
    isActive?: boolean;
    createdAt?: number;
    updatedAt?: number;
  };
}

interface ToolResponse {
  id: string;
  toolId: string;
  name?: string;
  description?: string;
  schema?: string;
  example?: string;
  exampleJsonb?: string;
  format: string;
  statusCode: number;
  isDefault: boolean;
  isActive: boolean;
  createdAt?: number;
  updatedAt?: number;
  createdBy?: string;
}

interface ToolCredential {
  id: string;
  apiToolId: string;
  credentialId?: string;
  credentialName: string;
  isRequired: boolean;
  usage?: string;
  condition?: string;
  metadata?: string;
  createdAt?: number;
  updatedAt?: number;
  credentialDetails?: {
    credentialName: string;
    displayName?: string;
    description?: string;
    credentialType?: string;
    authType?: string;
    testEndpoint?: string;
    documentationUrl?: string;
    iconUrl?: string;
    properties?: string;
    extends?: string;
  };
}

interface ApiToolParameter {
  id: string;
  name: string;
  type: string;
  description?: string;
  required: boolean;
  defaultValue?: string;
  exampleValue?: string;
  parameterType?: string;
}

interface ApiToolMonetization {
  id: string;
  apiToolId: string;
  monetizationType: string;
  planName?: string;
  rateLimitRequests?: number;
  rateLimitPeriod?: string;
  freeRequests?: number;
  freeRequestsType?: string;
  mauValue?: number;
  pricePerMau?: number;
  calls?: number;
  quota?: number;
  price?: number;
  overusageCost?: number;
  hardLimit?: boolean;
  createdAt?: number;
  updatedAt?: number;
}

interface ApiSystem {
  id: string;
  apiName: string;
  apiSlug?: string;
  description: string;
  baseUrl?: string;
  healthcheckEndpoint?: string;
  categoryId: string;
  categoryName?: string;
  subcategoryId: string;
  subcategoryName?: string;
  visibility: string;
  pricingModel: string;
  authType: string;
  authHeaderName?: string;
  authHeaderValue?: string;
  status: string;
  isPublic: boolean;
  isActive: boolean;
  isLocal?: boolean;
  createdBy: string;
  createdAt: number;
  updatedAt: number;
  tools?: ApiTool[];
}

const formatDate = (timestamp: number) => {
  if (!timestamp) return "N/A";
  return formatUtcDateTime(new Date(timestamp));
};

const getStatusBadge = (status: string, isActive: boolean, t: (key: string) => string) => {
  if (!isActive) {
    return (
      <Badge variant="destructive" className="flex items-center gap-1">
        <XCircle className="h-3 w-3" />
        {t('status.inactive')}
      </Badge>
    );
  }

  switch (status?.toUpperCase()) {
    case "APPROVED":
    case "ACTIVE":
      return (
        <Badge variant="default" className="flex items-center gap-1 bg-green-500">
          <CheckCircle2 className="h-3 w-3" />
          {t('status.active')}
        </Badge>
      );
    case "DRAFT":
      return (
        <Badge variant="secondary" className="flex items-center gap-1">
          <AlertCircle className="h-3 w-3" />
          {t('status.draft')}
        </Badge>
      );
    case "REJECTED":
      return (
        <Badge variant="destructive" className="flex items-center gap-1">
          <XCircle className="h-3 w-3" />
          {t('status.rejected')}
        </Badge>
      );
    case "SUSPENDED":
      return (
        <Badge variant="outline" className="flex items-center gap-1">
          <AlertCircle className="h-3 w-3" />
          {t('status.suspended')}
        </Badge>
      );
    default:
      return (
        <Badge variant="outline" className="flex items-center gap-1">
          <Info className="h-3 w-3" />
          {status || t('status.unknown')}
        </Badge>
      );
  }
};

const getMethodBadge = (method: string) => {
  const colors: Record<string, string> = {
    GET: "bg-blue-500",
    POST: "bg-green-500",
    PUT: "bg-yellow-500",
    DELETE: "bg-red-500",
    PATCH: "bg-purple-500",
  };

  return (
    <Badge
      className={cn(
        "text-white font-mono text-xs",
        colors[method.toUpperCase()] || "bg-gray-500"
      )}
    >
      {method.toUpperCase()}
    </Badge>
  );
};

/**
 * Generate a curl command for a tool
 */
const generateCurlCommand = (api: ApiSystem, tool: ApiTool): string => {
  const baseUrl = api.baseUrl || '';
  const endpoint = tool.endpoint || '';
  
  // Combine base URL and endpoint
  let fullUrl = baseUrl;
  if (endpoint.startsWith('/')) {
    fullUrl += endpoint;
  } else if (endpoint) {
    fullUrl += '/' + endpoint;
  }
  
  // Build headers
  const headers: string[] = [];
  if (api.authType && api.authType !== 'none' && api.authHeaderName) {
    const authValue = api.authHeaderValue || 'YOUR_TOKEN_HERE';
    headers.push(`-H "${api.authHeaderName}: ${authValue}"`);
  }
  headers.push('-H "Content-Type: application/json"');
  
  // Build parameters based on method
  const method = tool.method?.toUpperCase() || 'GET';
  let curlCommand = `curl -X ${method} "${fullUrl}"`;
  
  if (headers.length > 0) {
    curlCommand += ' \\\n  ' + headers.join(' \\\n  ');
  }
  
  // Add body for POST, PUT, PATCH
  if (['POST', 'PUT', 'PATCH'].includes(method) && tool.parameters && tool.parameters.length > 0) {
    const bodyParams: Record<string, any> = {};
    tool.parameters.forEach(param => {
      if (param.parameterType === 'body' || param.parameterType === 'BODY') {
        const value = param.exampleValue || param.defaultValue || `"${param.name}"`;
        bodyParams[param.name] = value;
      }
    });
    
    if (Object.keys(bodyParams).length > 0) {
      curlCommand += ' \\\n  -d \'' + JSON.stringify(bodyParams, null, 2) + '\'';
    }
  }
  
  // Add query parameters for GET, DELETE
  if (['GET', 'DELETE'].includes(method) && tool.parameters && tool.parameters.length > 0) {
    const queryParams: string[] = [];
    tool.parameters.forEach(param => {
      if (param.parameterType === 'query' || param.parameterType === 'QUERY') {
        const value = param.exampleValue || param.defaultValue || param.name;
        queryParams.push(`${param.name}=${encodeURIComponent(value)}`);
      }
    });
    
    if (queryParams.length > 0) {
      const separator = fullUrl.includes('?') ? '&' : '?';
      curlCommand = curlCommand.replace(`"${fullUrl}"`, `"${fullUrl}${separator}${queryParams.join('&')}"`);
    }
  }
  
  return curlCommand;
};

export default function TestApisPageContent() {
  const t = useTranslations('testApis');
  // Use isAuthChecking (OIDC native loading) for faster UI rendering
  const { user, isAuthenticated, isAuthChecking } = useAuthGuard();
  const [apis, setApis] = useState<ApiSystem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchTerm, setSearchTerm] = useState("");
  const [apiNameFilter, setApiNameFilter] = useState("");
  const [apiNameFilterApplied, setApiNameFilterApplied] = useState(""); // Filtre appliqué au backend
  const [statusFilter, setStatusFilter] = useState<string>("all");
  const [categoryFilter, setCategoryFilter] = useState<string>("all");
  const [expandedApis, setExpandedApis] = useState<Set<string>>(new Set());
  
  // Pagination state
  const [currentPage, setCurrentPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);

  // Fonction pour appliquer le filtre de nom
  const applyNameFilter = () => {
    setApiNameFilterApplied(apiNameFilter);
    setCurrentPage(0); // Reset to first page when applying filter
  };

  // Fonction pour gérer la touche Entrée
  const handleNameFilterKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      applyNameFilter();
    }
  };

  useEffect(() => {
    const fetchApis = async () => {
      // Attendre que l'authentification soit chargée
      if (isAuthChecking) {
        return;
      }

      setLoading(true);
      setError(null);

      try {
        const headers: HeadersInit = {
          "Content-Type": "application/json",
        };

        // Build URL with pagination and name filter (use applied filter, not the input value)
        const params = new URLSearchParams();
        params.set('page', currentPage.toString());
        params.set('size', pageSize.toString());
        if (apiNameFilterApplied.trim()) {
          params.set('name', apiNameFilterApplied.trim());
        }

        const url = `/api/test/apis?${params.toString()}`;
        const response = await fetch(url, {
          method: "GET",
          headers,
          credentials: "include",
        });

        if (!response.ok) {
          const errorText = await response.text();
          console.error("API Error:", errorText);
          throw new Error(`Failed to fetch APIs: ${response.status} ${response.statusText}`);
        }

        const data = await response.json();
        console.log("Fetched APIs:", data);
        
        // Handle paginated response
        if (data.content && Array.isArray(data.content)) {
          setApis(data.content);
          setTotalElements(data.totalElements || 0);
          setTotalPages(data.totalPages || 0);
        } else if (Array.isArray(data)) {
          // Fallback for non-paginated response
          setApis(data);
          setTotalElements(data.length);
          setTotalPages(1);
        } else {
          setApis([]);
        }
      } catch (err) {
        console.error("Error fetching APIs:", err);
        setError(
          err instanceof Error ? err.message : "Failed to fetch APIs"
        );
      } finally {
        setLoading(false);
      }
    };

    fetchApis();
  }, [isAuthChecking, currentPage, pageSize, apiNameFilterApplied]);

  const toggleApiExpansion = (apiId: string) => {
    const newExpanded = new Set(expandedApis);
    if (newExpanded.has(apiId)) {
      newExpanded.delete(apiId);
    } else {
      newExpanded.add(apiId);
    }
    setExpandedApis(newExpanded);
  };

  // Le filtre sur le nom est maintenant fait côté backend
  // On garde seulement les filtres locaux pour status et category
  const filteredApis = apis.filter((api) => {
    // Filtre de recherche générale (côté frontend pour description, catégorie, etc.)
    const matchesSearch =
      searchTerm === "" ||
      api.description?.toLowerCase().includes(searchTerm.toLowerCase()) ||
      api.categoryName?.toLowerCase().includes(searchTerm.toLowerCase()) ||
      api.subcategoryName?.toLowerCase().includes(searchTerm.toLowerCase()) ||
      api.createdBy?.toLowerCase().includes(searchTerm.toLowerCase());

    const matchesStatus =
      statusFilter === "all" ||
      (statusFilter === "active" && api.isActive) ||
      (statusFilter === "inactive" && !api.isActive) ||
      api.status?.toLowerCase() === statusFilter.toLowerCase();

    const matchesCategory =
      categoryFilter === "all" ||
      api.categoryId === categoryFilter ||
      api.categoryName?.toLowerCase() === categoryFilter.toLowerCase();

    return matchesSearch && matchesStatus && matchesCategory;
  });

  const categories = Array.from(
    new Set(apis.map((api) => api.categoryName).filter(Boolean))
  );

  if (isAuthChecking || loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-theme-primary transition-colors duration-300">
        <LoadingSpinner size="xl" text={t('loading')} />
      </div>
    );
  }

  return (
    <div className="w-full max-w-[1920px] mx-auto px-6 xl:px-12 py-8 space-y-8">
      <div className="flex items-center justify-between">
        <div>
          <PageTitle size="lg">
            {t('pageTitle')}
          </PageTitle>
          <p className="text-lg xl:text-xl text-theme-secondary">
            {t('pageDescription')}
          </p>
        </div>
        <div className="flex items-center gap-4">
          <Badge variant="outline" className="text-xl xl:text-2xl px-6 py-3">
            {t('apisDisplayed', { count: filteredApis.length })}
          </Badge>
          <Badge variant="outline" className="text-xl xl:text-2xl px-6 py-3">
            {t('totalApis', { count: totalElements })}
          </Badge>
        </div>
      </div>

      {/* Debug info */}
      {process.env.NODE_ENV === 'development' && (
        <div className="text-xs text-theme-secondary p-2 bg-theme-tertiary rounded">
          <div>Total APIs: {apis.length}</div>
          <div>Filtered: {filteredApis.length}</div>
          <div>Error: {error || 'None'}</div>
        </div>
      )}

      {error && (
        <Card className="bg-red-50 dark:bg-red-900/20 border-red-200 dark:border-red-800">
          <CardContent className="p-4">
            <div className="flex items-center space-x-2">
              <AlertCircle className="w-5 h-5 text-red-600" />
              <span className="text-red-800 dark:text-red-200">{error}</span>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Filtres */}
      <Card>
        <CardContent className="p-6">
          <div className="flex flex-col lg:flex-row gap-6">
            <div className="relative flex-1">
              <Search className="pointer-events-none absolute left-6 top-1/2 -translate-y-1/2 h-4 w-4 text-theme-secondary" />
              <Input
                value={apiNameFilter}
                onChange={(e) => setApiNameFilter(e.target.value)}
                onKeyDown={handleNameFilterKeyDown}
                placeholder={t('filterByName')}
                className="pl-14 pr-32 text-sm"
              />
              <Button
                onClick={applyNameFilter}
                className="absolute right-1 top-1/2 -translate-y-1/2 h-7 px-3"
                variant="default"
              >
                {t('search')}
              </Button>
            </div>
            <div className="relative flex-1">
              <Search className="pointer-events-none absolute left-6 top-1/2 -translate-y-1/2 h-4 w-4 text-theme-secondary" />
              <Input
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                placeholder={t('searchInDetails')}
                className="pl-14 text-sm"
              />
            </div>
            <Select value={statusFilter} onValueChange={setStatusFilter}>
              <SelectTrigger className="w-full lg:w-[220px] text-sm">
                <SelectValue placeholder={t('filters.statusPlaceholder')} />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">{t('filters.allStatuses')}</SelectItem>
                <SelectItem value="active">{t('status.active')}</SelectItem>
                <SelectItem value="inactive">{t('status.inactive')}</SelectItem>
                <SelectItem value="draft">{t('status.draft')}</SelectItem>
                <SelectItem value="approved">{t('filters.approved')}</SelectItem>
              </SelectContent>
            </Select>
            <Select value={categoryFilter} onValueChange={setCategoryFilter}>
              <SelectTrigger className="w-full lg:w-[220px] text-sm">
                <SelectValue placeholder={t('filters.categoryPlaceholder')} />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">{t('filters.allCategories')}</SelectItem>
                {categories.map((cat) => (
                  <SelectItem key={cat} value={cat?.toLowerCase() || ""}>
                    {cat}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Select value={pageSize.toString()} onValueChange={(value) => {
              setPageSize(parseInt(value));
              setCurrentPage(0); // Reset to first page when changing page size
            }}>
              <SelectTrigger className="w-full lg:w-[180px] text-sm">
                <SelectValue placeholder={t('filters.perPagePlaceholder')} />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="10">{t('pagination.perPage', { count: 10 })}</SelectItem>
                <SelectItem value="20">{t('pagination.perPage', { count: 20 })}</SelectItem>
                <SelectItem value="50">{t('pagination.perPage', { count: 50 })}</SelectItem>
                <SelectItem value="100">{t('pagination.perPage', { count: 100 })}</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </CardContent>
      </Card>

      {/* Liste des APIs */}
      <div className="space-y-6">
        {filteredApis.length === 0 ? (
          <Card>
            <CardContent className="p-8 text-center">
              <AlertCircle className="w-12 h-12 text-theme-secondary mx-auto mb-4" />
              <p className="text-theme-secondary">
                {t('noApisFound')}
              </p>
            </CardContent>
          </Card>
        ) : (
          filteredApis.map((api) => {
            const isExpanded = expandedApis.has(api.id);
            const toolsCount = api.tools?.length || 0;

            return (
              <Card key={api.id} className="overflow-hidden">
                <CardHeader
                  className="cursor-pointer hover:bg-theme-secondary/50 transition-colors p-6"
                  onClick={() => toggleApiExpansion(api.id)}
                >
                  <div className="flex items-start justify-between">
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-4 mb-3">
                        <CardTitle className="text-2xl xl:text-3xl font-semibold text-theme-primary">
                          {api.apiName}
                        </CardTitle>
                        {getStatusBadge(api.status, api.isActive, t)}
                        {api.isLocal && (
                          <Badge variant="outline" className="flex items-center gap-1">
                            <Server className="h-3 w-3" />
                            Local
                          </Badge>
                        )}
                        {api.isPublic ? (
                          <Badge variant="outline" className="flex items-center gap-1">
                            <Globe className="h-3 w-3" />
                            Public
                          </Badge>
                        ) : (
                          <Badge variant="outline" className="flex items-center gap-1">
                            <Lock className="h-3 w-3" />
                            {t('private')}
                          </Badge>
                        )}
                      </div>
                      <p className="text-base xl:text-lg text-theme-secondary line-clamp-2 mt-2">
                        {api.description}
                      </p>
                      <div className="flex flex-wrap items-center gap-6 mt-4 text-sm xl:text-base text-theme-secondary">
                        <div className="flex items-center gap-1">
                          <Database className="h-3 w-3" />
                          <span>
                            {api.categoryName || "N/A"}
                            {api.subcategoryName && ` / ${api.subcategoryName}`}
                          </span>
                        </div>
                        <div className="flex items-center gap-1">
                          <Code className="h-3 w-3" />
                          <span>{t('toolCount', { count: toolsCount })}</span>
                        </div>
                        <div className="flex items-center gap-1">
                          <Settings className="h-3 w-3" />
                          <span>v1.0.0</span>
                        </div>
                        {api.createdBy && (
                          <div className="flex items-center gap-1">
                            <span>{t('by')}: {api.createdBy}</span>
                          </div>
                        )}
                      </div>
                    </div>
                    <div className="flex items-center gap-2 ml-6">
                      {isExpanded ? (
                        <ChevronDown className="h-6 w-6 xl:h-8 xl:w-8 text-theme-secondary" />
                      ) : (
                        <ChevronRight className="h-6 w-6 xl:h-8 xl:w-8 text-theme-secondary" />
                      )}
                    </div>
                  </div>
                </CardHeader>

                {isExpanded && (
                  <div className="animate-in slide-in-from-top-2 duration-200">
                    <CardContent className="pt-0 space-y-8 p-6">
                      {/* Informations détaillées de l'API */}
                      <div className="grid grid-cols-1 lg:grid-cols-2 xl:grid-cols-3 gap-6 xl:gap-8 pt-6 border-t">
                        <div>
                          <h4 className="text-lg xl:text-xl font-semibold text-theme-primary mb-4">
                            {t('configuration')}
                          </h4>
                          <div className="space-y-3 text-base">
                            <div>
                              <span className="text-theme-secondary">Base URL:</span>{" "}
                              <code className="bg-theme-tertiary px-3 py-1.5 rounded text-sm xl:text-base break-all">
                                {api.baseUrl || "N/A"}
                              </code>
                            </div>
                            {api.healthcheckEndpoint && (
                              <div>
                                <span className="text-theme-secondary">
                                  Healthcheck:
                                </span>{" "}
                                <code className="bg-theme-tertiary px-3 py-1.5 rounded text-sm xl:text-base break-all">
                                  {api.healthcheckEndpoint}
                                </code>
                              </div>
                            )}
                            <div>
                              <span className="text-theme-secondary">{t('visibility')}:</span>{" "}
                              <Badge variant="outline">{api.visibility}</Badge>
                            </div>
                            <div>
                              <span className="text-theme-secondary">{t('pricingModel')}:</span>{" "}
                              <Badge variant="outline">{api.pricingModel}</Badge>
                            </div>
                            <div>
                              <span className="text-theme-secondary">Auth Type:</span>{" "}
                              <Badge variant="outline">{api.authType || "none"}</Badge>
                            </div>
                          </div>
                        </div>
                        <div>
                          <h4 className="text-lg xl:text-xl font-semibold text-theme-primary mb-4">
                            {t('metadata')}
                          </h4>
                          <div className="space-y-3 text-base">
                            <div>
                              <span className="text-theme-secondary">ID:</span>{" "}
                              <code className="bg-theme-tertiary px-3 py-1.5 rounded text-sm xl:text-base break-all">
                                {api.id}
                              </code>
                            </div>
                            {api.apiSlug && (
                              <div>
                                <span className="text-theme-secondary">Slug:</span>{" "}
                                <code className="bg-theme-tertiary px-3 py-1.5 rounded text-sm xl:text-base break-all">
                                  {api.apiSlug}
                                </code>
                              </div>
                            )}
                            <div>
                              <span className="text-theme-secondary">{t('createdAt')}:</span>{" "}
                              {formatDate(api.createdAt)}
                            </div>
                            <div>
                              <span className="text-theme-secondary">{t('updatedAt')}:</span>{" "}
                              {formatDate(api.updatedAt)}
                            </div>
                          </div>
                        </div>
                      </div>

                      {/* Liste des outils */}
                      {toolsCount > 0 && (
                        <div className="pt-6 border-t">
                          <h4 className="text-xl xl:text-2xl font-semibold text-theme-primary mb-6">
                            {t('tools')} ({toolsCount})
                          </h4>
                          <div className="space-y-4 xl:space-y-6">
                            {api.tools?.map((tool) => (
                              <Card key={tool.id} className="bg-theme-secondary">
                                <CardContent className="p-6">
                                  <div className="flex items-start justify-between mb-3">
                                    <div className="flex-1">
                                      <div className="flex items-center gap-3 mb-2">
                                        {getMethodBadge(tool.method)}
                                        <span className="text-lg xl:text-xl font-medium text-theme-primary">
                                          {tool.name || tool.id}
                                        </span>
                                        {tool.protocol && (
                                          <Badge variant="outline" className="text-xs">
                                            {tool.protocol}
                                          </Badge>
                                        )}
                                        {getStatusBadge(tool.status, tool.isActive, t)}
                                      </div>
                                      <p className="text-base text-theme-secondary mt-2">
                                        {tool.description}
                                      </p>
                                      <code className="text-sm xl:text-base bg-theme-tertiary px-3 py-2 rounded mt-3 block break-all">
                                        {tool.endpoint}
                                      </code>
                                      
                                      {/* Curl Command */}
                                      <div className="mt-4 pt-4 border-t border-theme-tertiary">
                                        <h6 className="text-sm xl:text-base font-semibold text-theme-secondary mb-2">
                                          {t('curlCommand')}
                                        </h6>
                                        <div className="relative">
                                          <code className="text-xs xl:text-sm bg-slate-900 text-green-400 px-4 py-3 rounded block overflow-x-auto font-mono whitespace-pre">
                                            {generateCurlCommand(api, tool)}
                                          </code>
                                          <Button
                                            variant="ghost"
                                            size="sm"
                                            className="absolute top-2 right-2"
                                            onClick={() => {
                                              navigator.clipboard.writeText(generateCurlCommand(api, tool));
                                            }}
                                          >
                                            <Copy className="h-4 w-4" />
                                          </Button>
                                        </div>
                                      </div>
                                    </div>
                                  </div>

                                  {/* Paramètres de l'outil */}
                                  {tool.parameters && tool.parameters.length > 0 && (
                                    <div className="mt-4 pt-4 border-t border-theme-tertiary">
                                      <h5 className="text-base xl:text-lg font-semibold text-theme-secondary mb-3">
                                        {t('parameters')} ({tool.parameters.length})
                                      </h5>
                                      <div className="space-y-2 xl:space-y-3">
                                        {tool.parameters.map((param) => (
                                          <div
                                            key={param.id}
                                            className="flex flex-wrap items-center gap-2 xl:gap-3 text-sm xl:text-base"
                                          >
                                            <code className="bg-theme-tertiary px-3 py-1.5 rounded text-sm xl:text-base">
                                              {param.name}
                                            </code>
                                            <Badge variant="outline" className="text-sm xl:text-base">
                                              {param.type}
                                            </Badge>
                                            {param.required && (
                                              <Badge
                                                variant="destructive"
                                                className="text-sm xl:text-base"
                                              >
                                                {t('required')}
                                              </Badge>
                                            )}
                                            {param.parameterType && (
                                              <Badge variant="outline" className="text-sm xl:text-base">
                                                {param.parameterType}
                                              </Badge>
                                            )}
                                            {param.description && (
                                              <span className="text-theme-secondary text-sm xl:text-base">
                                                - {param.description}
                                              </span>
                                            )}
                                          </div>
                                        ))}
                                      </div>
                                    </div>
                                  )}

                                  {/* Monetisation */}
                                  {tool.monetization &&
                                    tool.monetization.length > 0 && (
                                      <div className="mt-4 pt-4 border-t border-theme-tertiary">
                                        <h5 className="text-base xl:text-lg font-semibold text-theme-secondary mb-3">
                                          {t('monetization')}
                                        </h5>
                                        <div className="space-y-2 xl:space-y-3">
                                          {tool.monetization.map((mon) => (
                                            <div
                                              key={mon.id}
                                              className="text-sm xl:text-base text-theme-secondary"
                                            >
                                              <Badge variant="outline" className="mr-2 text-sm xl:text-base">
                                                {mon.monetizationType}
                                              </Badge>
                                              {mon.planName && (
                                                <Badge variant="outline" className="mr-2 text-sm xl:text-base">
                                                  {mon.planName}
                                                </Badge>
                                              )}
                                              {mon.price !== undefined && (
                                                <span>{t('price')}: ${mon.price}</span>
                                              )}
                                              {mon.quota && (
                                                <span className="ml-2">
                                                  {t('quota')}: {mon.quota}
                                                </span>
                                              )}
                                            </div>
                                          ))}
                                        </div>
                                      </div>
                                    )}

                                  {/* Réponses */}
                                  {tool.responses && tool.responses.length > 0 && (
                                    <div className="mt-4 pt-4 border-t border-theme-tertiary">
                                      <h5 className="text-base xl:text-lg font-semibold text-theme-secondary mb-3">
                                        {t('responses')} ({tool.responses.length})
                                      </h5>
                                      <div className="space-y-2 xl:space-y-3">
                                        {tool.responses.map((resp) => (
                                          <div
                                            key={resp.id}
                                            className="text-sm xl:text-base"
                                          >
                                            <div className="flex items-center gap-2 mb-1">
                                              <Badge variant="outline" className="text-sm xl:text-base">
                                                {resp.format}
                                              </Badge>
                                              <Badge variant="outline" className="text-sm xl:text-base">
                                                {resp.statusCode}
                                              </Badge>
                                              {resp.isDefault && (
                                                <Badge variant="default" className="text-sm xl:text-base">
                                                  {t('default')}
                                                </Badge>
                                              )}
                                              {resp.name && (
                                                <span className="font-medium text-theme-primary">{resp.name}</span>
                                              )}
                                            </div>
                                            {resp.description && (
                                              <p className="text-theme-secondary text-xs xl:text-sm mt-1">
                                                {resp.description}
                                              </p>
                                            )}
                                            {resp.exampleJsonb && (
                                              <code className="text-xs xl:text-sm bg-theme-tertiary px-2 py-1 rounded mt-1 block overflow-x-auto">
                                                {typeof resp.exampleJsonb === 'string' 
                                                  ? resp.exampleJsonb.substring(0, 200) + (resp.exampleJsonb.length > 200 ? '...' : '')
                                                  : JSON.stringify(resp.exampleJsonb).substring(0, 200)}
                                              </code>
                                            )}
                                          </div>
                                        ))}
                                      </div>
                                    </div>
                                  )}

                                  {/* Credentials */}
                                  {tool.credentials && tool.credentials.length > 0 && (
                                    <div className="mt-4 pt-4 border-t border-theme-tertiary">
                                      <h5 className="text-base xl:text-lg font-semibold text-theme-secondary mb-3">
                                        Credentials ({tool.credentials.length})
                                      </h5>
                                      <div className="space-y-2 xl:space-y-3">
                                        {tool.credentials.map((cred) => (
                                          <div
                                            key={cred.id}
                                            className="text-sm xl:text-base"
                                          >
                                            <div className="flex items-center gap-2 mb-1">
                                              <code className="bg-theme-tertiary px-3 py-1.5 rounded text-sm xl:text-base">
                                                {cred.credentialName}
                                              </code>
                                              {cred.isRequired && (
                                                <Badge variant="destructive" className="text-sm xl:text-base">
                                                  {t('required')}
                                                </Badge>
                                              )}
                                              {cred.usage && (
                                                <Badge variant="outline" className="text-sm xl:text-base">
                                                  {cred.usage}
                                                </Badge>
                                              )}
                                            </div>
                                            {cred.credentialDetails && (
                                              <div className="mt-2 text-xs xl:text-sm text-theme-secondary space-y-1">
                                                {cred.credentialDetails.displayName && (
                                                  <div><strong>Display:</strong> {cred.credentialDetails.displayName}</div>
                                                )}
                                                {cred.credentialDetails.description && (
                                                  <div><strong>Description:</strong> {cred.credentialDetails.description}</div>
                                                )}
                                                {cred.credentialDetails.credentialType && (
                                                  <div><strong>Type:</strong> {cred.credentialDetails.credentialType}</div>
                                                )}
                                                {cred.credentialDetails.authType && (
                                                  <div><strong>Auth Type:</strong> {cred.credentialDetails.authType}</div>
                                                )}
                                              </div>
                                            )}
                                          </div>
                                        ))}
                                      </div>
                                    </div>
                                  )}
                                </CardContent>
                              </Card>
                            ))}
                          </div>
                        </div>
                      )}
                    </CardContent>
                  </div>
                )}
              </Card>
            );
          })
        )}
      </div>

      {/* Pagination Controls */}
      {totalPages > 1 && (
        <Card>
          <CardContent className="p-6">
            <div className="flex items-center justify-between">
              <div className="text-sm xl:text-base text-theme-secondary">
                {t('pagination.pageInfo', { current: currentPage + 1, total: totalPages, count: totalElements })}
              </div>
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="lg"
                  onClick={() => setCurrentPage(0)}
                  disabled={currentPage === 0}
                  className="h-12 px-4"
                >
                  <ChevronLeft className="h-5 w-5" />
                  {t('pagination.first')}
                </Button>
                <Button
                  variant="outline"
                  size="lg"
                  onClick={() => setCurrentPage(Math.max(0, currentPage - 1))}
                  disabled={currentPage === 0}
                  className="h-12 px-4"
                >
                  <ChevronLeft className="h-5 w-5" />
                  {t('pagination.previous')}
                </Button>
                <div className="px-4 py-2 text-lg font-semibold text-theme-primary">
                  {currentPage + 1}
                </div>
                <Button
                  variant="outline"
                  size="lg"
                  onClick={() => setCurrentPage(Math.min(totalPages - 1, currentPage + 1))}
                  disabled={currentPage >= totalPages - 1}
                  className="h-12 px-4"
                >
                  {t('pagination.next')}
                  <ChevronRightIcon className="h-5 w-5" />
                </Button>
                <Button
                  variant="outline"
                  size="lg"
                  onClick={() => setCurrentPage(totalPages - 1)}
                  disabled={currentPage >= totalPages - 1}
                  className="h-12 px-4"
                >
                  {t('pagination.last')}
                  <ChevronRightIcon className="h-5 w-5" />
                </Button>
              </div>
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

