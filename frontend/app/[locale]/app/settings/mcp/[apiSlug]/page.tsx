"use client";

import React, { useState, useCallback } from "react";
import { useTranslations } from "next-intl";
import { useParams, useRouter } from "next/navigation";
import { useAuthGuard } from "@/hooks/useAuthGuard";
import { useUserApis } from "@/hooks/useUserApis";
import { unifiedApiService } from "@/lib/api/unified-api-service";
import { useUnifiedUserData } from "@/hooks/useUnifiedUserData";
import { useApiDetails, useApiTools } from "@/hooks/useApiDetails";
import { useApiById } from "@/hooks/useApiById";
import NavigationLoader from "@/components/NavigationLoader";
import LoadingSpinner from "@/components/LoadingSpinner";
import { AlertCircle } from "lucide-react";
import { useTabPersistence } from "@/hooks/useTabPersistence";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";

// Import new components
import ApiHeader from "./components/ApiHeader";
import TabNavigation from "./components/TabNavigation";
import OverviewTab from "./components/tabs/OverviewTab";
import ToolsTab from "./components/tabs/ToolsTab";
import MonetizeTab from "./components/tabs/MonetizeTab";

// Define UserTool type locally
interface UserTool {
  id: string;
  apiName: string;
  apiSlug?: string;
  name?: string;
  slug?: string;
  description: string;
  category: string;
  categoryName?: string;
  subcategoryName?: string;
  isPublic: boolean;
  isActive: boolean;
  version?: string;
  responseTime?: number;
  uptime?: number;
  successRate?: number;
  totalRequests?: number;
  lastUsed?: string;
  status?: "active" | "paused" | "error";
  type?: "local" | "external";
  apiId?: string;
  baseUrl?: string;
  userId?: string;
  configuration?: any;
  createdAt: string;
  updatedAt: string;
  tools?: any[];
  // API Configuration fields
  healthcheckEndpoint?: string;
  visibility?: string;
  authType?: string;
  authHeaderName?: string;
  authHeaderValue?: string;
  pricingModel?: string;
  apiStatus?: string;
}

export default function ApiDetailPage() {
  const t = useTranslations('mcp.overview.page');
  // Use isAuthChecking for faster UI rendering (isLoading not used here)
  const { user, isAuthenticated, isAuthChecking } = useAuthGuard();
  const {
    apis,
    isLoading: apisLoading,
    error: apisError,
    getApiById,
  } = useUserApis();

  // Utilisation correcte des hooks - pas dans des conditions
  const userData = useUnifiedUserData();
  const status = userData?.status || null;
  const profile = userData?.profile || null;
  const monetization = userData?.monetization || null;
  const userDataLoading = userData?.isLoading || false;
  const params = useParams();
  const router = useRouter();
  const apiId = params.apiSlug as string; // Utilise apiId au lieu de apiSlug

  // Utilisation des hooks standardises - evite useState + useEffect
  const {
    api,
    isLoading: apiLoading,
    error: apiError,
    refetch: refetchApi,
  } = useApiById(apiId);
  const {
    tools,
    isLoading: toolsLoading,
    error: toolsError,
  } = useApiTools(apiId);

  // Force le rafraîchissement des donnees quand l'apiId change
  React.useEffect(() => {
    if (apiId && !api && !apiLoading) {
      refetchApi();
    }
  }, [apiId]);

  // Gestion des erreurs et chargement
  const error = apiError || toolsError;
  const loading = apiLoading || toolsLoading;

  // etat initial : si on n'a pas encore commence a charger et qu'on n'a pas d'API
  const isInitialLoading = !apiLoading && !toolsLoading && !api && !error;

  // etat pour les erreurs de configuration
  const [configError, setConfigError] = useState<string | null>(null);

  // etats de navigation avec persistance
  const [currentTab, setCurrentTab] = useTabPersistence({
    apiId,
    defaultTab: "overview",
    validTabs: ["overview", "tools", "monetize"],
  });

  // etats pour les configurations
  const [apiConfig, setApiConfig] = useState({
    baseUrl: "https://api.example.com",
    healthcheckEndpoint: "/health",
    authorization: {
      type: "bearer",
      headerName: "Authorization",
      headerValue: "Bearer YOUR_TOKEN",
    },
    visibility: "public",
    rateLimit: {
      requests: 1000,
      period: "hour",
    },
  });

  // Update apiConfig when API data changes
  React.useEffect(() => {
    if (api) {
      setApiConfig((prev) => ({
        ...prev,
        baseUrl: api.baseUrl || prev.baseUrl,
        healthcheckEndpoint:
          api.healthcheckEndpoint || prev.healthcheckEndpoint,
        authorization: {
          type: (api.authType || prev.authorization.type) as
            | "bearer"
            | "basic"
            | "apisecret"
            | "none",
          headerName: api.authHeaderName || prev.authorization.headerName,
          headerValue: api.authHeaderValue || prev.authorization.headerValue,
          username:
            (api as any).authUsername ||
            (prev.authorization as any).username ||
            "",
          password:
            (api as any).authPassword ||
            (prev.authorization as any).password ||
            "",
        },
        visibility:
          api.visibility ||
          (api.isPublic ? "public" : "private") ||
          prev.visibility,
        rateLimit: {
          requests: api.rateLimit || prev.rateLimit.requests,
          period: prev.rateLimit.period,
        },
      }));
    }
  }, [api]);

  const [monetizationConfig, setMonetizationConfig] = useState(() => {
    // Load from localStorage or use default
    if (typeof window !== "undefined") {
      const saved = localStorage.getItem(`monetization-config-${apiId}`);
      if (saved) {
        try {
          return JSON.parse(saved);
        } catch (e) {
          console.warn("Failed to parse saved monetization config:", e);
        }
      }
    }

    return {
      pricing: "freemium",
      selectedPricingModels: ["freemium"],
      selectedPlans: {
        basic: true,
        pro: false,
        ultra: false,
        mega: false,
      },
      priceBasic: 0,
      pricePro: 9.99,
      priceUltra: 29.99,
      priceMega: 99.99,
      quotaBasic: 1000,
      quotaPro: 10000,
      quotaUltra: 50000,
      quotaMega: 200000,
      rpsBasic: 10,
      rpsPro: 100,
      rpsUltra: 500,
      rpsMega: 1000,
      rpsPeriodBasic: "minute",
      rpsPeriodPro: "minute",
      rpsPeriodUltra: "minute",
      rpsPeriodMega: "minute",
      overusageCostBasic: 0.01,
      overusageCostPro: 0.008,
      overusageCostUltra: 0.005,
      overusageCostMega: 0.003,
      freeRequestsPerUser: 1000,
      monetization: {
        freeRequests: 1000,
        tokenValue: 1,
      },
      planTools: {
        basic: [],
        pro: [],
        ultra: [],
        mega: [],
      },
    };
  });

  // Les donnees sont maintenant gerees par les hooks standardises
  // Plus besoin de useEffect + useState pour les donnees API

  // Handlers pour les actions
  const handleSaveApiConfig = async (config: any) => {
    setApiConfig(config);
    // Ici vous pourriez faire un appel API pour sauvegarder
    console.log("Saving API config:", config);
  };

  const handleSaveMonetizationConfig = async (config: any) => {
    setMonetizationConfig(config);

    // Validation : au moins un modele de pricing doit etre selectionne
    const selectedModels = config.selectedPricingModels || [config.pricing];
    if (!selectedModels || selectedModels.length === 0) {
      setConfigError("At least one pricing model must be selected.");
      return;
    }

    // Save to localStorage
    if (typeof window !== "undefined") {
      localStorage.setItem(
        `monetization-config-${apiId}`,
        JSON.stringify(config),
      );
    }

    // Appel API pour sauvegarder les pricing models
    try {
      console.log("🔄 Saving monetization config to backend:", config);

      // Payload simplifie : seulement l'API ID et les modeles de pricing selectionnes
      const pricingUpdateRequest = {
        apiId: apiId,
        selectedPricingModels: selectedModels,
      };

      // Utiliser la route proxy generale comme les autres endpoints
      const response = await unifiedApiService.updatePricingModels(
        apiId,
        pricingUpdateRequest,
      );
      console.log("✅ Monetization config saved successfully:", response);

      // Clear any previous errors
      setConfigError(null);
    } catch (error) {
      console.error("❌ Error saving monetization config:", error);
      setConfigError("Error saving monetization configuration.");
    }
  };

  /**
   * Update FREEMIUM configuration for a specific tool by api_tool_id
   */
  const handleUpdateToolFreemiumConfig = async (
    apiToolId: string,
    toolConfig: {
      freeRequests?: number;
      freeRequestsType?: "per-user" | "global";
      rateLimitRequests?: number;
      rateLimitPeriod?: "second" | "minute" | "hour" | "day" | "month";
      mauValue?: number;
      calls?: number;
    },
  ) => {
    try {
      console.log(
        `🔄 Updating FREEMIUM config for tool ID: ${apiToolId}`,
        toolConfig,
      );

      // Prepare the payload for tool-specific FREEMIUM update
      const toolUpdateRequest = {
        apiId: apiId,
        apiToolId: apiToolId,
        monetizationType: "FREEMIUM",
        config: {
          freeRequests: toolConfig.freeRequests,
          freeRequestsType: toolConfig.freeRequestsType,
          rateLimitRequests: toolConfig.rateLimitRequests,
          rateLimitPeriod: toolConfig.rateLimitPeriod,
          mauValue: toolConfig.mauValue,
          calls: toolConfig.calls,
        },
      };

      // Call the tool-specific update endpoint using api_tool_id
      const response = await unifiedApiService.updateToolFreemiumConfig(
        apiId,
        apiToolId,
        toolUpdateRequest,
      );
      console.log(
        `✅ FREEMIUM config updated for tool ID ${apiToolId}:`,
        response,
      );

      // Clear any previous errors
      setConfigError(null);

      return response;
    } catch (error) {
      console.error(
        `❌ Error updating FREEMIUM config for tool ID ${apiToolId}:`,
        error,
      );
      setConfigError(`Error updating FREEMIUM configuration for tool.`);
      throw error;
    }
  };

  /**
   * Update multiple tools FREEMIUM configuration in batch by api_tool_id
   */
  const handleBatchUpdateToolsFreemiumConfig = async (
    toolsConfig: Record<
      string,
      {
        freeRequests?: number;
        freeRequestsType?: "per-user" | "global";
        rateLimitRequests?: number;
        rateLimitPeriod?: "second" | "minute" | "hour" | "day" | "month";
        mauValue?: number;
        calls?: number;
      }
    >,
  ) => {
    try {
      console.log("🔄 Batch updating FREEMIUM config for tools:", toolsConfig);

      // Prepare the payload for batch update (keys are api_tool_ids)
      const batchUpdateRequest = {
        apiId: apiId,
        monetizationType: "FREEMIUM",
        toolsConfig: toolsConfig, // Keys are api_tool_ids, values are configs
      };

      // Call the batch update endpoint
      const response = await unifiedApiService.updateBatchFreemiumConfig(
        apiId,
        batchUpdateRequest,
      );
      console.log("✅ Batch FREEMIUM config updated:", response);

      // Clear any previous errors
      setConfigError(null);

      return response;
    } catch (error) {
      console.error("❌ Error batch updating FREEMIUM config:", error);
      setConfigError("Error batch updating FREEMIUM configuration.");
      throw error;
    }
  };

  /**
   * Update PAID configuration for a specific tool by api_tool_id
   */
  const handleUpdateToolPaidConfig = async (
    apiToolId: string,
    toolConfig: {
      planName?: string;
      quota?: number;
      price?: number;
      overusageCost?: number;
      hardLimit?: boolean;
      rateLimitRequests?: number;
      rateLimitPeriod?: "second" | "minute" | "hour" | "day" | "month";
    },
  ) => {
    try {
      console.log(
        `🔄 Updating PAID config for tool ID: ${apiToolId}`,
        toolConfig,
      );

      // Prepare the payload for tool-specific PAID update
      const toolUpdateRequest = {
        apiId: apiId,
        apiToolId: apiToolId,
        monetizationType: "PAID",
        config: {
          planName: toolConfig.planName,
          quota: toolConfig.quota,
          price: toolConfig.price,
          overusageCost: toolConfig.overusageCost,
          hardLimit: toolConfig.hardLimit,
          rateLimitRequests: toolConfig.rateLimitRequests,
          rateLimitPeriod: toolConfig.rateLimitPeriod,
        },
      };

      // Call the tool-specific update endpoint using api_tool_id
      const response = await unifiedApiService.updateToolPaidConfig(
        apiId,
        apiToolId,
        toolUpdateRequest,
      );
      console.log(`✅ PAID config updated for tool ID ${apiToolId}:`, response);

      // Clear any previous errors
      setConfigError(null);

      return response;
    } catch (error) {
      console.error(
        `❌ Error updating PAID config for tool ID ${apiToolId}:`,
        error,
      );
      setConfigError(`Error updating PAID configuration for tool.`);
      throw error;
    }
  };

  /**
   * Update multiple tools PAID configuration in batch by api_tool_id
   */
  const handleBatchUpdateToolsPaidConfig = async (
    toolsConfig: Record<
      string,
      {
        planName?: string;
        quota?: number;
        price?: number;
        overusageCost?: number;
        hardLimit?: boolean;
        rateLimitRequests?: number;
        rateLimitPeriod?: "second" | "minute" | "hour" | "day" | "month";
      }
    >,
  ) => {
    try {
      console.log("🔄 Batch updating PAID config for tools:", toolsConfig);

      // Prepare the payload for batch update (keys are api_tool_ids)
      const batchUpdateRequest = {
        apiId: apiId,
        monetizationType: "PAID",
        toolsConfig: toolsConfig, // Keys are api_tool_ids, values are configs
      };

      // Call the batch update endpoint
      const response = await unifiedApiService.updateBatchPaidConfig(
        apiId,
        batchUpdateRequest,
      );
      console.log("✅ Batch PAID config updated:", response);

      // Clear any previous errors
      setConfigError(null);

      return response;
    } catch (error) {
      console.error("❌ Error batch updating PAID config:", error);
      setConfigError("Error batch updating PAID configuration.");
      throw error;
    }
  };

  /**
   * Update PAID plans selection for the API
   */
  const handleUpdatePaidPlans = async (
    selectedPlans: Record<string, boolean>,
    planTools: Record<string, string[]>,
  ) => {
    try {
      console.log("🔄 Updating PAID plans:", { selectedPlans, planTools });

      const paidPlansRequest = {
        selectedPlans: selectedPlans,
        planTools: planTools,
      };

      const response = await unifiedApiService.updatePaidPlans(
        apiId,
        paidPlansRequest,
      );
      console.log("✅ PAID plans updated successfully:", response);

      // Clear any previous errors
      setConfigError(null);

      return response;
    } catch (error) {
      console.error("❌ Error updating PAID plans:", error);
      setConfigError("Error updating PAID plans configuration.");
      throw error;
    }
  };

  // Donnees mockees pour les outils
  const mockTools = [
    {
      id: "1",
      name: "get-profile",
      description: "Get Instagram profile information by user ID",
      endpoint: "/users/{user_id}",
      method: "GET" as const,
      status: "active" as const,
      lastTested: "2 minutes ago",
      successRate: 99.5,
      responseTime: 245,
    },
    {
      id: "2",
      name: "get-posts",
      description: "Retrieve user posts with engagement metrics",
      endpoint: "/users/{user_id}/media",
      method: "GET" as const,
      status: "active" as const,
      lastTested: "5 minutes ago",
      successRate: 98.2,
      responseTime: 320,
    },
    {
      id: "3",
      name: "get-followers",
      description: "Get follower list and statistics",
      endpoint: "/users/{user_id}/followers",
      method: "GET" as const,
      status: "paused" as const,
      lastTested: "1 hour ago",
      successRate: 95.8,
      responseTime: 450,
    },
  ];

  // Gestion des erreurs
  if (error) {
    return (
      <div className="space-y-8">
        <Card className="bg-theme-secondary/80">
          <CardContent className="py-12 text-center space-y-4">
            <AlertCircle className="mx-auto h-12 w-12 text-red-500" />
            <div className="space-y-2">
              <h2 className="text-xl font-semibold text-theme-primary">
                {t('loadingError')}
              </h2>
              <p className="text-theme-secondary">{error}</p>
            </div>
            <Button
              onClick={() => refetchApi()}
              variant="default"
              size="default"
            >
              {t('tryAgain')}
            </Button>
          </CardContent>
        </Card>
      </div>
    );
  }

  // ecran de chargement avec message plus explicite
  if (loading || isInitialLoading) {
    return (
      <div className="space-y-8">
        <Card className="bg-theme-secondary/80">
          <CardContent className="py-12 text-center space-y-4">
            <NavigationLoader />
            <div className="flex items-center justify-center">
              <LoadingSpinner size="lg" text={t('loadingApi')} />
            </div>
          </CardContent>
        </Card>
      </div>
    );
  }

  // API non trouvee (seulement si pas en cours de chargement et qu'on a une erreur)
  if (!loading && !isInitialLoading && !api && error) {
    return (
      <div className="space-y-8">
        <Card className="bg-theme-secondary/80">
          <CardContent className="py-12 text-center space-y-4">
            <AlertCircle className="mx-auto h-12 w-12 text-yellow-500" />
            <div className="space-y-2">
              <h2 className="text-xl font-semibold text-theme-primary">
                {t('apiNotFound')}
              </h2>
              <p className="text-theme-secondary">
                {t('apiNotFoundDesc')}
              </p>
            </div>
            <Button
              onClick={() => router.push("/app/settings/mcp")}
              variant="default"
              size="default"
            >
              {t('backToApis')}
            </Button>
          </CardContent>
        </Card>
      </div>
    );
  }

  // Verification de securite : s'assurer que l'API est chargee
  if (!api && !error) {
    return (
      <div className="space-y-8">
        <Card className="bg-theme-secondary/80">
          <CardContent className="py-12 text-center space-y-4">
            <NavigationLoader />
            <div className="flex items-center justify-center space-x-2">
              <LoadingSpinner />
              <p className="text-theme-secondary">{t('finalizingLoading')}</p>
            </div>
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="px-6 space-y-8">
      {api && (
        <>
          <ApiHeader
            apiName={api.apiName || api.name || "API"}
            status={api.isActive ? "active" : "paused"}
            apiStatus={api.status}
            apiId={api.id}
          />

          <TabNavigation
            activeTab={currentTab}
            onTabChange={setCurrentTab}
          />

          <div className="space-y-8">
            {currentTab === "overview" && (
              <OverviewTab
                apiData={api}
                apiConfig={apiConfig}
                onSaveConfig={handleSaveApiConfig}
              />
            )}

            {currentTab === "tools" && <ToolsTab tools={tools} />}


            {currentTab === "monetize" && (
              <MonetizeTab
                apiData={api}
                monetizationConfig={monetizationConfig}
                onSaveConfig={handleSaveMonetizationConfig}
                onUpdateToolFreemiumConfig={handleUpdateToolFreemiumConfig}
                onBatchUpdateToolsFreemiumConfig={
                  handleBatchUpdateToolsFreemiumConfig
                }
                onUpdateToolPaidConfig={handleUpdateToolPaidConfig}
                onBatchUpdateToolsPaidConfig={handleBatchUpdateToolsPaidConfig}
                onUpdatePaidPlans={handleUpdatePaidPlans}
              />
            )}
          </div>
        </>
      )}
    </div>
  );
}
