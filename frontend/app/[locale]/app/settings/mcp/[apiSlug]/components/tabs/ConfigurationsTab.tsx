import React, { useState } from 'react';
import { useTranslations } from 'next-intl';
import {
  Settings,
  Shield,
  Globe,
  Key,
  Eye,
  EyeOff,
  Save,
  TestTube,
  CheckCircle,
  AlertCircle,
  Info
} from 'lucide-react';

interface ConfigurationsTabProps {
  apiConfig: any;
  onSaveConfig: (config: any) => void;
}

const ConfigurationsTab: React.FC<ConfigurationsTabProps> = ({
  apiConfig,
  onSaveConfig
}) => {
  const t = useTranslations('mcp.configurationsTab');
  const [isEditing, setIsEditing] = useState(false);
  const [showSecrets, setShowSecrets] = useState(false);
  const [config, setConfig] = useState(apiConfig);
  const [isSaving, setIsSaving] = useState(false);

  const handleSave = async () => {
    setIsSaving(true);
    try {
      await onSaveConfig(config);
      setIsEditing(false);
    } catch (error) {
      console.error('Error saving configuration:', error);
    } finally {
      setIsSaving(false);
    }
  };

  const handleCancel = () => {
    setConfig(apiConfig);
    setIsEditing(false);
  };

  const toggleSecretVisibility = () => {
    setShowSecrets(!showSecrets);
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="bg-theme-secondary rounded-xl p-6 border border-theme/30 shadow-lg">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-2xl font-bold text-theme-primary">{t('header.title')}</h2>
            <p className="text-theme-secondary">{t('header.description')}</p>
          </div>
          <div className="flex items-center space-x-3">
            {isEditing ? (
              <>
                <button
                  onClick={handleCancel}
                  className="px-4 py-2 bg-theme-tertiary text-theme-primary rounded-lg hover:bg-theme-tertiary/80 transition-colors duration-200"
                >
                  {t('actions.cancel')}
                </button>
                <button
                  onClick={handleSave}
                  disabled={isSaving}
                  className="flex items-center space-x-2 px-4 py-2 bg-theme-primary text-theme-secondary rounded-lg hover:bg-theme-primary/90 transition-colors duration-200 disabled:opacity-50"
                >
                  <Save className="w-4 h-4" />
                  <span>{isSaving ? t('actions.saving') : t('actions.save')}</span>
                </button>
              </>
            ) : (
              <button
                onClick={() => setIsEditing(true)}
                className="flex items-center space-x-2 px-4 py-2 bg-theme-primary text-theme-secondary rounded-lg hover:bg-theme-primary/90 transition-colors duration-200"
              >
                <Settings className="w-4 h-4" />
                <span>{t('actions.edit')}</span>
              </button>
            )}
          </div>
        </div>
      </div>

      {/* Configuration Sections */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Basic Configuration */}
        <div className="bg-theme-secondary rounded-xl p-6 border border-theme/30 shadow-lg">
          <div className="flex items-center space-x-3 mb-6">
            <div className="w-10 h-10 bg-theme-primary/10 rounded-xl flex items-center justify-center">
              <Globe className="w-5 h-5 text-theme-primary" />
            </div>
            <div>
              <h3 className="text-lg font-semibold text-theme-primary">{t('basicSettings.title')}</h3>
              <p className="text-sm text-theme-secondary">{t('basicSettings.description')}</p>
            </div>
          </div>

          <div className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-theme-primary mb-2">
                {t('fields.baseUrl')}
              </label>
              {isEditing ? (
                <input
                  type="url"
                  value={config.baseUrl || ''}
                  onChange={(e) => setConfig({ ...config, baseUrl: e.target.value })}
                  className="w-full h-9 px-3 text-sm bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary focus:outline-none focus:ring-2 focus:ring-theme-primary/50"
                  placeholder="https://api.example.com"
                />
              ) : (
                <div className="px-3 py-2 bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary font-mono">
                  {config.baseUrl || t('notConfigured')}
                </div>
              )}
            </div>

            <div>
              <label className="block text-sm font-medium text-theme-primary mb-2">
                {t('fields.healthCheckEndpoint')}
              </label>
              {isEditing ? (
                <input
                  type="text"
                  value={config.healthcheckEndpoint || ''}
                  onChange={(e) => setConfig({ ...config, healthcheckEndpoint: e.target.value })}
                  className="w-full h-9 px-3 text-sm bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary focus:outline-none focus:ring-2 focus:ring-theme-primary/50"
                  placeholder="/health"
                />
              ) : (
                <div className="px-3 py-2 bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary font-mono">
                  {config.healthcheckEndpoint || '/health'}
                </div>
              )}
            </div>

            <div>
              <label className="block text-sm font-medium text-theme-primary mb-2">
                {t('fields.visibility')}
              </label>
              {isEditing ? (
                <select
                  value={config.visibility || 'public'}
                  onChange={(e) => setConfig({ ...config, visibility: e.target.value })}
                  className="w-full h-9 px-3 text-sm bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary focus:outline-none focus:ring-2 focus:ring-theme-primary/50"
                >
                  <option value="public">{t('visibility.public')}</option>
                  <option value="private">{t('visibility.private')}</option>
                  <option value="unlisted">{t('visibility.unlisted')}</option>
                </select>
              ) : (
                <div className="px-3 py-2 bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary capitalize">
                  {config.visibility || t('visibility.public')}
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Authentication Configuration */}
        <div className="bg-theme-secondary rounded-xl p-6 border border-theme/30 shadow-lg">
          <div className="flex items-center space-x-3 mb-6">
            <div className="w-10 h-10 bg-theme-primary/10 rounded-xl flex items-center justify-center">
              <Shield className="w-5 h-5 text-theme-primary" />
            </div>
            <div>
              <h3 className="text-lg font-semibold text-theme-primary">{t('authentication.title')}</h3>
              <p className="text-sm text-theme-secondary">{t('authentication.description')}</p>
            </div>
          </div>

          <div className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-theme-primary mb-2">
                {t('fields.authType')}
              </label>
              {isEditing ? (
                <select
                  value={config.authorization?.type || 'none'}
                  onChange={(e) => setConfig({
                    ...config,
                    authorization: {
                      ...config.authorization,
                      type: e.target.value
                    }
                  })}
                  className="w-full h-9 px-3 text-sm bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary focus:outline-none focus:ring-2 focus:ring-theme-primary/50"
                >
                  <option value="none">{t('authTypes.none')}</option>
                  <option value="apikey">{t('authTypes.apiKey')}</option>
                  <option value="bearer">{t('authTypes.bearer')}</option>
                  <option value="basic">{t('authTypes.basic')}</option>
                  <option value="oauth2">{t('authTypes.oauth2')}</option>
                </select>
              ) : (
                <div className="px-3 py-2 bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary capitalize">
                  {config.authorization?.type || 'none'}
                </div>
              )}
            </div>

            {config.authorization?.type !== 'none' && (
              <>
                <div>
                  <label className="block text-sm font-medium text-theme-primary mb-2">
                    {t('fields.headerName')}
                  </label>
                  {isEditing ? (
                    <input
                      type="text"
                      value={config.authorization?.headerName || ''}
                      onChange={(e) => setConfig({
                        ...config,
                        authorization: {
                          ...config.authorization,
                          headerName: e.target.value
                        }
                      })}
                      className="w-full h-9 px-3 text-sm bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary focus:outline-none focus:ring-2 focus:ring-theme-primary/50"
                      placeholder="Authorization"
                    />
                  ) : (
                    <div className="px-3 py-2 bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary font-mono">
                      {config.authorization?.headerName || t('notSet')}
                    </div>
                  )}
                </div>

                <div>
                  <label className="block text-sm font-medium text-theme-primary mb-2">
                    {config.authorization?.type === 'basic' ? t('fields.username') : t('fields.apiKeyToken')}
                  </label>
                  <div className="relative">
                    {isEditing ? (
                      <input
                        type={showSecrets ? 'text' : 'password'}
                        value={config.authorization?.headerValue || ''}
                        onChange={(e) => setConfig({
                          ...config,
                          authorization: {
                            ...config.authorization,
                            headerValue: e.target.value
                          }
                        })}
                        className="w-full h-9 px-3 pr-10 text-sm bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary focus:outline-none focus:ring-2 focus:ring-theme-primary/50"
                        placeholder={t('placeholders.apiKeyToken')}
                      />
                    ) : (
                      <div className="px-3 py-2 pr-10 bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary font-mono">
                        {showSecrets ? (config.authorization?.headerValue || t('notSet')) : '••••••••••••••••'}
                      </div>
                    )}
                    <button
                      type="button"
                      onClick={toggleSecretVisibility}
                      className="absolute right-3 top-1/2 transform -translate-y-1/2 text-theme-secondary hover:text-theme-primary transition-colors duration-200"
                    >
                      {showSecrets ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                    </button>
                  </div>
                </div>

                {config.authorization?.type === 'basic' && (
                  <div>
                    <label className="block text-sm font-medium text-theme-primary mb-2">
                      {t('fields.password')}
                    </label>
                    <div className="relative">
                      {isEditing ? (
                        <input
                          type={showSecrets ? 'text' : 'password'}
                          value={config.authorization?.password || ''}
                          onChange={(e) => setConfig({
                            ...config,
                            authorization: {
                              ...config.authorization,
                              password: e.target.value
                            }
                          })}
                          className="w-full h-9 px-3 pr-10 text-sm bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary focus:outline-none focus:ring-2 focus:ring-theme-primary/50"
                          placeholder={t('placeholders.password')}
                        />
                      ) : (
                        <div className="px-3 py-2 pr-10 bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary font-mono">
                          {showSecrets ? (config.authorization?.password || t('notSet')) : '••••••••••••••••'}
                        </div>
                      )}
                    </div>
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      </div>

      {/* Rate Limiting */}
      <div className="bg-theme-secondary rounded-xl p-6 border border-theme/30 shadow-lg">
        <div className="flex items-center space-x-3 mb-6">
          <div className="w-10 h-10 bg-theme-primary/10 rounded-xl flex items-center justify-center">
            <Key className="w-5 h-5 text-theme-primary" />
          </div>
          <div>
            <h3 className="text-lg font-semibold text-theme-primary">{t('rateLimiting.title')}</h3>
            <p className="text-sm text-theme-secondary">{t('rateLimiting.description')}</p>
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          <div>
            <label className="block text-sm font-medium text-theme-primary mb-2">
              {t('fields.requestsPerPeriod')}
            </label>
            {isEditing ? (
              <input
                type="number"
                value={config.rateLimit?.requests || 1000}
                onChange={(e) => setConfig({
                  ...config,
                  rateLimit: {
                    ...config.rateLimit,
                    requests: parseInt(e.target.value)
                  }
                })}
                className="w-full h-9 px-3 text-sm bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary focus:outline-none focus:ring-2 focus:ring-theme-primary/50"
                min="1"
              />
            ) : (
              <div className="px-3 py-2 bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary">
                {config.rateLimit?.requests || 1000}
              </div>
            )}
          </div>

          <div>
            <label className="block text-sm font-medium text-theme-primary mb-2">
              {t('fields.timePeriod')}
            </label>
            {isEditing ? (
              <select
                value={config.rateLimit?.period || 'hour'}
                onChange={(e) => setConfig({
                  ...config,
                  rateLimit: {
                    ...config.rateLimit,
                    period: e.target.value
                  }
                })}
                className="w-full h-9 px-3 text-sm bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary focus:outline-none focus:ring-2 focus:ring-theme-primary/50"
              >
                <option value="minute">{t('periods.perMinute')}</option>
                <option value="hour">{t('periods.perHour')}</option>
                <option value="day">{t('periods.perDay')}</option>
                <option value="month">{t('periods.perMonth')}</option>
              </select>
            ) : (
              <div className="px-3 py-2 bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary capitalize">
                {config.rateLimit?.period || 'hour'}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Test Configuration */}
      <div className="bg-theme-secondary rounded-xl p-6 border border-theme/30 shadow-lg">
        <div className="flex items-center justify-between mb-6">
          <div className="flex items-center space-x-3">
            <div className="w-10 h-10 bg-theme-primary/10 rounded-xl flex items-center justify-center">
              <TestTube className="w-5 h-5 text-theme-primary" />
            </div>
            <div>
              <h3 className="text-lg font-semibold text-theme-primary">{t('testConfig.title')}</h3>
              <p className="text-sm text-theme-secondary">{t('testConfig.description')}</p>
            </div>
          </div>
          <button className="flex items-center space-x-2 px-4 py-2 bg-theme-primary text-theme-secondary rounded-lg hover:bg-theme-primary/90 transition-colors duration-200">
            <TestTube className="w-4 h-4" />
            <span>{t('testConfig.testApi')}</span>
          </button>
        </div>

        <div className="bg-theme-tertiary rounded-lg p-4">
          <div className="flex items-center space-x-2 mb-2">
            <Info className="w-4 h-4 text-blue-500" />
            <span className="text-sm font-medium text-theme-primary">{t('testConfig.statusTitle')}</span>
          </div>
          <div className="space-y-2 text-sm text-theme-secondary">
            <div className="flex items-center space-x-2">
              <CheckCircle className="w-4 h-4 text-green-500" />
              <span>{t('testConfig.baseUrlConfigured')}</span>
            </div>
            <div className="flex items-center space-x-2">
              <CheckCircle className="w-4 h-4 text-green-500" />
              <span>{t('testConfig.authConfigured')}</span>
            </div>
            <div className="flex items-center space-x-2">
              <AlertCircle className="w-4 h-4 text-yellow-500" />
              <span>{t('testConfig.healthCheckNotTested')}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default ConfigurationsTab;
