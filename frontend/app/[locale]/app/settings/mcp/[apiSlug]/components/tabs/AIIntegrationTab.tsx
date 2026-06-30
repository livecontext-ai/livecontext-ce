import React, { useState, useCallback } from 'react';
import { useTranslations } from 'next-intl';
import {
  Gift,
  Zap,
  DollarSign,
  Save,
  SquarePen,
  MessageSquare,
  Code,
  Settings,
  Edit,
  CheckCircle
} from 'lucide-react';
import { MonetizationConfig } from '@/app/[locale]/app/settings/developers/types';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';

interface AIIntegrationTabProps {
  config: MonetizationConfig;
  setConfig: (config: MonetizationConfig) => void;
  isEditing: boolean;
  setIsEditing: (editing: boolean) => void;
  isSaving: boolean;
  hasChanges: boolean;
  setHasChanges: (hasChanges: boolean) => void;
  onSave: () => void;
  onCancel: () => void;
  mcpTools: Array<{
    id?: string;
    name: string;
    toolCategory: string;
    endpoint: string;
  }>;
  userMonetizationState?: any;
  onUpdateToolFreemiumConfig?: (apiToolId: string, toolConfig: {
    freeRequests?: number;
    freeRequestsType?: 'per-user' | 'global';
    rateLimitRequests?: number;
    rateLimitPeriod?: 'second' | 'minute' | 'hour' | 'day' | 'month';
    mauValue?: number;
    calls?: number;
  }) => Promise<any>;
  onBatchUpdateToolsFreemiumConfig?: (toolsConfig: Record<string, {
    freeRequests?: number;
    freeRequestsType?: 'per-user' | 'global';
    rateLimitRequests?: number;
    rateLimitPeriod?: 'second' | 'minute' | 'hour' | 'day' | 'month';
    mauValue?: number;
    calls?: number;
  }>) => Promise<any>;
  setIsSubTabEditing?: (editing: boolean) => void;
}

const AIIntegrationTab: React.FC<AIIntegrationTabProps> = ({
  config,
  setConfig,
  isEditing,
  setIsEditing,
  isSaving,
  hasChanges,
  setHasChanges,
  onSave,
  onCancel,
  mcpTools,
  userMonetizationState,
  onUpdateToolFreemiumConfig,
  onBatchUpdateToolsFreemiumConfig,
  setIsSubTabEditing
}) => {
  const t = useTranslations('mcp.aiIntegrationTab');
  const [editingTool, setEditingTool] = useState<string | null>(null);
  const [isToolEditing, setIsToolEditing] = useState(false);

  const handleCancel = () => {
    setEditingTool(null);
    // Don't call onCancel() as it would trigger the main pricing model edit mode
    // Just reset the local editing state
  };

  const handleSave = () => {
    setEditingTool(null);
    onSave();
  };

  const handleToolEdit = (toolName: string) => {
    // If main editing mode is active, don't allow tool editing
    if (isEditing) {
      return;
    }
    setEditingTool(toolName);
    setIsToolEditing(true);
    setIsSubTabEditing?.(true);
  };

  const handleToolCancel = () => {
    setEditingTool(null);
    setIsToolEditing(false);
    setIsSubTabEditing?.(false);
  };

  const handleToolSave = async () => {
    if (editingTool && onUpdateToolFreemiumConfig) {
      try {
        // Find the tool to get its api_tool_id
        const tool = mcpTools.find(t => t.name === editingTool);
        if (!tool) {
          console.error('Tool not found:', editingTool);
          return;
        }

        // Check if tool has a valid ID, if not, use fallback or skip
        if (!tool.id) {
          console.warn('Tool missing ID, falling back to global save:', editingTool);
          // Fallback to the original save method
        setEditingTool(null);
        setIsToolEditing(false);
        setIsSubTabEditing?.(false);
        onSave();
          return;
        }

        // Get the current tool configuration from the config state
        const freeRequestsValue = config.toolFreeRequests?.[editingTool];
        const toolConfig = {
          freeRequests: typeof freeRequestsValue === 'string' ? parseInt(freeRequestsValue, 10) : freeRequestsValue,
          freeRequestsType: config.toolFreeRequests?.[`${editingTool}_type`] as 'per-user' | 'global' || 'per-user',
          rateLimitRequests: config.toolRateLimits?.[editingTool]?.requests,
          rateLimitPeriod: config.toolRateLimits?.[editingTool]?.period as 'second' | 'minute' | 'hour' | 'day' | 'month' || 'hour',
          mauValue: config.toolPricing?.[editingTool]?.mauValue,
          calls: config.toolPricing?.[editingTool]?.calls
        };

        // Call the tool-specific update method using api_tool_id
        await onUpdateToolFreemiumConfig(tool.id, toolConfig);
        
        setEditingTool(null);
        setIsToolEditing(false);
        setIsSubTabEditing?.(false);
        setHasChanges(false);
      } catch (error) {
        console.error('Error saving tool configuration:', error);
        // Keep editing mode open on error
      }
    } else {
      // Fallback to the original save method
      setEditingTool(null);
      setIsEditing(false);
      onSave();
    }
  };

  const rateLimitPeriodOptions = [
    { value: 'second', label: t('periods.perSecond') },
    { value: 'minute', label: t('periods.perMinute') },
    { value: 'hour', label: t('periods.perHour') },
    { value: 'day', label: t('periods.perDay') },
    { value: 'month', label: t('periods.perMonth') }
  ];

  const handleToolFreeRequestsChange = (toolName: string, value: number) => {
    setConfig({
      ...config,
      toolFreeRequests: {
        ...config.toolFreeRequests,
        [toolName]: value
      }
    });
    setHasChanges(true);
  };

  const handleToolRateLimitChange = (toolName: string, field: 'requests' | 'period', value: number | string) => {
    setConfig({
      ...config,
      toolRateLimits: {
        ...config.toolRateLimits,
        [toolName]: {
          ...config.toolRateLimits?.[toolName],
          [field]: value
        }
      }
    });
    setHasChanges(true);
  };

  const handleToolPricingChange = (toolName: string, field: 'mauValue' | 'calls', value: number) => {
    const currentPricing = config.toolPricing?.[toolName] || { mauValue: 1, price: 0.005, currency: 'USD', calls: 1 };
    const newPricing = {
      ...currentPricing,
      [field]: value,
      price: field === 'mauValue' ? value * 0.005 : currentPricing.price
    };
    
    setConfig({
      ...config,
      toolPricing: {
        ...config.toolPricing,
        [toolName]: newPricing
      }
    });
    setHasChanges(true);
  };

  // Debug: Log tools structure
  console.log('🔧 AIIntegrationTab - mcpTools:', mcpTools);

  return (
    <div className="space-y-6">
      {/* Individual Tools Configuration */}
      {mcpTools.length > 0 ? (
        <div className="space-y-6">
          {mcpTools.map((tool, index) => {
            // Map API data structure to our expected format
            const toolMonetization = userMonetizationState?.tools?.find((t: any) => t.name === tool.name);
            const freemiumConfig = toolMonetization?.monetization?.find((m: any) => m.monetizationType === 'FREEMIUM');
            const isToolEditing = editingTool === tool.name;
            
            console.log(`🔧 Tool: ${tool.name}`, {
              toolMonetization,
              freemiumConfig,
              configToolFreeRequests: config.toolFreeRequests?.[tool.name],
              configToolRateLimits: config.toolRateLimits?.[tool.name],
              configToolPricing: config.toolPricing?.[tool.name]
            });
            
            
            return (
              <Card key={index} className="bg-theme-secondary">
                <CardHeader>
                  <div className="flex items-center justify-between">
                    <div className="flex items-center space-x-3 ml-4">
                      <div>
                        <CardTitle className="text-xl font-semibold text-theme-primary">{tool.name}</CardTitle>
                        <div className="mt-1">
                          <p className="text-xs text-theme-secondary truncate">{tool.endpoint}</p>
                        </div>
                      </div>
                    </div>
                    <div className="flex items-center space-x-2">
                      {!isToolEditing ? (
                        <Button
                          onClick={() => handleToolEdit(tool.name)}
                          disabled={isEditing}
                          variant="ghost"
                          size="icon"
                          title={isEditing ? t('tooltips.finishPricingFirst') : t('tooltips.editToolConfig')}
                        >
                          <SquarePen className="w-4 h-4"/>
                        </Button>
                      ) : (
                        <div className="flex items-center space-x-3">
                          <Button
                            onClick={handleToolCancel}
                            variant="secondary"
                          >
                            {t('actions.cancel')}
                          </Button>
                          <Button
                            onClick={handleToolSave}
                            disabled={isSaving || !hasChanges}
                            variant="default"
                          >
                            <Save className="w-4 h-4"/>
                            <span>{isSaving ? t('actions.saving') : t('actions.saveChanges')}</span>
                          </Button>
                        </div>
                      )}
                    </div>
                  </div>
                </CardHeader>

                <CardContent>
                {/* Tool Configuration Sections */}
                <div className="p-6 space-y-6">
                  {/* Free Requests Section */}
                  <div className="bg-theme-primary/5 rounded-lg">
                    <div className="flex items-center space-x-2">
                      <Gift className="w-5 h-5 text-blue-600"/>
                      <h4 className="text-lg font-medium text-theme-primary">{t('sections.freeRequests')}</h4>
                    </div>

                    <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
                      <div>
                        <label className="block text-sm font-medium text-theme-primary mb-2">
                          {t('fields.freeRequests')}
                        </label>
                        {isToolEditing ? (
                          <Input
                            type="number"
                            value={config.toolFreeRequests?.[tool.name] || freemiumConfig?.freeRequests || 1000}
                            onChange={(e) => handleToolFreeRequestsChange(tool.name, parseInt(e.target.value) || 1000)}
                            min={0}
                          />
                        ) : (
                          <Button
                            variant="readonly"
                            disabled
                            className="w-full justify-start rounded-lg"
                          >
                            {config.toolFreeRequests?.[tool.name] || freemiumConfig?.freeRequests || 1000} requests
                          </Button>
                        )}
                      </div>

                      <div>
                        <label className="block text-sm font-medium text-theme-primary mb-2">
                          {t('fields.distributionType')}
                        </label>
                        {isToolEditing ? (
                          <Select
                            value={config.toolFreeRequests?.[`${tool.name}_type`] || freemiumConfig?.freeRequestsType || 'per-user'}
                            onValueChange={(value) => {
                              setConfig({
                                ...config,
                                toolFreeRequests: {
                                  ...config.toolFreeRequests,
                                  [`${tool.name}_type`]: value
                                }
                              });
                              setHasChanges(true);
                            }}
                          >
                            <SelectTrigger className="w-full">
                              <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                              <SelectItem value="per-user">{t('distributionTypes.perUser')}</SelectItem>
                              <SelectItem value="global">{t('distributionTypes.global')}</SelectItem>
                            </SelectContent>
                          </Select>
                        ) : (
                          <Button
                            variant="readonly"
                            disabled
                            className="w-full justify-start rounded-lg capitalize"
                          >
                            {config.toolFreeRequests?.[`${tool.name}_type`] || freemiumConfig?.freeRequestsType || 'per-user'}
                          </Button>
                        )}
                      </div>
                    </div>
                  </div>

                  {/* Rate Limiting Section */}
                  <div className="bg-theme-primary/5 rounded-lg">
                    <div className="flex items-center space-x-2">
                      <Zap className="w-5 h-5 text-orange-600"/>
                      <h4 className="text-lg font-medium text-theme-primary">{t('sections.rateLimiting')}</h4>
                    </div>

                    <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
                      <div>
                        <label className="block text-sm font-medium text-theme-primary mb-2">
                          {t('fields.rateLimitRequests')}
                        </label>
                        {isToolEditing ? (
                          <Input
                            type="number"
                            value={config.toolRateLimits?.[tool.name]?.requests || freemiumConfig?.rateLimitRequests || 1000}
                            onChange={(e) => handleToolRateLimitChange(tool.name, 'requests', parseInt(e.target.value) || 1000)}
                            min={0}
                          />
                        ) : (
                          <Button
                            variant="readonly"
                            disabled
                            className="w-full justify-start rounded-lg"
                          >
                            {config.toolRateLimits?.[tool.name]?.requests || freemiumConfig?.rateLimitRequests || 1000} requests
                          </Button>
                        )}
                      </div>

                      <div>
                        <label className="block text-sm font-medium text-theme-primary mb-2">
                          {t('fields.rateLimitPeriod')}
                        </label>
                        {isToolEditing ? (
                          <Select
                            value={config.toolRateLimits?.[tool.name]?.period || freemiumConfig?.rateLimitPeriod || 'hour'}
                            onValueChange={(value) => handleToolRateLimitChange(tool.name, 'period', value)}
                          >
                            <SelectTrigger className="w-full">
                              <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                              {rateLimitPeriodOptions.map(option => (
                                <SelectItem key={option.value} value={option.value}>
                                  {option.label}
                                </SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                        ) : (
                          <Button
                            variant="readonly"
                            disabled
                            className="w-full justify-start rounded-lg capitalize"
                          >
                            {config.toolRateLimits?.[tool.name]?.period || freemiumConfig?.rateLimitPeriod || 'hour'}
                          </Button>
                        )}
                      </div>
                    </div>
                  </div>

                  {/* Pricing Section */}
                  <div className="bg-theme-primary/5 rounded-lg">
                    <div className="flex items-center space-x-2">
                      <DollarSign className="w-5 h-5 text-green-600"/>
                      <h4 className="text-lg font-medium text-theme-primary">{t('sections.pricing')}</h4>
                    </div>

                    <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
                      <div>
                        <label className="block text-sm font-medium text-theme-primary mb-2">
                          {t('fields.mauValue')}
                        </label>
                        {isToolEditing ? (
                          <Input
                            type="number"
                            value={config.toolPricing?.[tool.name]?.mauValue || freemiumConfig?.mauValue || 1}
                            onChange={(e) => handleToolPricingChange(tool.name, 'mauValue', parseInt(e.target.value) || 1)}
                            min={1}
                          />
                        ) : (
                          <Button
                            variant="readonly"
                            disabled
                            className="w-full justify-start rounded-lg"
                          >
                            {config.toolPricing?.[tool.name]?.mauValue || freemiumConfig?.mauValue || 1} MAU
                          </Button>
                        )}
                      </div>

                      <div>
                        <label className="block text-sm font-medium text-theme-primary mb-2">
                          {t('fields.callsPerMau')}
                        </label>
                        {isToolEditing ? (
                          <Input
                            type="number"
                            value={config.toolPricing?.[tool.name]?.calls || freemiumConfig?.calls || 1}
                            onChange={(e) => handleToolPricingChange(tool.name, 'calls', parseInt(e.target.value) || 1)}
                            min={1}
                          />
                        ) : (
                          <Button
                            variant="readonly"
                            disabled
                            className="w-full justify-start rounded-lg"
                          >
                            {config.toolPricing?.[tool.name]?.calls || freemiumConfig?.calls || 1} call{(config.toolPricing?.[tool.name]?.calls || freemiumConfig?.calls || 1) > 1 ? 's' : ''}
                          </Button>
                        )}
                      </div>
                    </div>

                    {/* Price Display */}
                    <div className="mt-4 p-3 bg-theme-secondary rounded-lg">
                      <div className="flex items-center justify-between">
                        <span className="text-sm text-theme-secondary">{t('fields.pricePerCall')}</span>
                        <span className="text-theme-primary">
                          {(() => {
                            const mauValue = config.toolPricing?.[tool.name]?.mauValue || freemiumConfig?.mauValue || 1;
                            const calls = config.toolPricing?.[tool.name]?.calls || freemiumConfig?.calls || 1;
                            // Fixed price per MAU: 1 MAU = $0.005
                            const pricePerMau = 0.005;
                            const pricePerCall = (mauValue * pricePerMau) / calls;
                            
                            console.log(`💰 Price calculation for ${tool.name}:`, {
                              mauValue,
                              pricePerMau: '0.005 (fixed)',
                              calls,
                              pricePerCall,
                              formula: `(${mauValue} * 0.005) / ${calls} = ${pricePerCall}`
                            });
                            
                            return `$${pricePerCall.toFixed(8).replace(/\.?0+$/, '')}`;
                          })()}
                        </span>
                      </div>
                    </div>
                  </div>
                </div>
                </CardContent>
              </Card>
            );
          })}
        </div>
      ) : (
        <div className="text-center py-12">
          <Code className="w-12 h-12 text-theme-secondary mx-auto mb-4" />
          <h3 className="text-lg font-medium text-theme-primary mb-2">{t('emptyState.title')}</h3>
          <p className="text-theme-secondary">{t('emptyState.description')}</p>
        </div>
      )}
    </div>
  );
};

export default AIIntegrationTab;
