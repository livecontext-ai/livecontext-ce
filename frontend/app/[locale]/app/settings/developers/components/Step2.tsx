import React, { useState } from 'react';
import { Info, Globe, Shield, Eye, AlertCircle } from 'lucide-react';
import { Step2Props } from '../types';
import { useTranslations } from 'next-intl';
import {
  FormSection,
  FormField,
  FormInput,
  FormSelect,
  FormTextarea,
  FormGrid,
  InfoBox
} from './common';

const Step2: React.FC<Step2Props> = ({ apiConfig, setApiConfig, apiName }) => {
  const t = useTranslations('developers.step2');
  // States to manage section expansion
  const [sectionsExpanded, setSectionsExpanded] = useState({
    technical: true,
    authorization: true,
    visibility: true
  });



  // State to show/hide authorization information
  const [showAuthorizationInfo, setShowAuthorizationInfo] = useState(false);

  // Generate a random default header value and set the default name
  React.useEffect(() => {
    // Generate default value only if it doesn't exist yet
    if (apiConfig.authorization.type === 'apisecret' && !apiConfig.authorization.headerValue) {
      const generateRandomHeaderValue = () => {
        const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
        let result = 'sk-';
        for (let i = 0; i < 32; i++) {
          result += chars.charAt(Math.floor(Math.random() * chars.length));
        }
        return result;
      };
      
      setApiConfig({
        ...apiConfig,
        authorization: {
          ...apiConfig.authorization,
          headerValue: generateRandomHeaderValue()
        }
      });
    }
  }, [apiConfig.authorization.type, apiConfig.authorization.headerValue, setApiConfig]);

  const toggleSection = (section: keyof typeof sectionsExpanded) => {
    setSectionsExpanded(prev => ({
      ...prev,
      [section]: !prev[section]
    }));
  };

  const authorizationOptions = [
    { value: 'apisecret', label: t('auth.options.apisecret') },
    { value: 'bearer', label: t('auth.options.bearer') },
    { value: 'basic', label: t('auth.options.basic') },
    { value: 'none', label: t('auth.options.none') }
  ];

  const visibilityOptions = [
    { value: 'public', label: t('visibility.options.public') },
    { value: 'private', label: t('visibility.options.private') }
  ];

  return (
    <div className="space-y-6">
      {/* Technical API Configuration Section */}
      <FormSection
        title={t('technical.title')}
        description={t('technical.description')}
        icon={Globe}
        collapsible
        isExpanded={sectionsExpanded.technical}
        onToggle={() => toggleSection('technical')}
      >
        <FormField
          label={t('technical.baseUrl')}
          required
          description={t('technical.baseUrlDescription')}
        >
          <FormInput
            type="url"
            value={apiConfig.baseUrl}
            onChange={(value) => setApiConfig({ ...apiConfig, baseUrl: value })}
            placeholder={t('technical.baseUrlPlaceholder')}
          />
          {/* Error message for invalid URL */}
          {apiConfig.baseUrl && (() => {
            try {
              const urlObj = new URL(apiConfig.baseUrl);
              const isValidProtocol = urlObj.protocol === 'http:' || urlObj.protocol === 'https:';
              if (!isValidProtocol) {
                return (
                  <div className="mt-2 p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-700 rounded-lg">
                    <div className="flex items-start space-x-2">
                      <AlertCircle className="w-4 h-4 text-red-500 mt-0.5 flex-shrink-0" />
                      <div className="text-sm text-red-700 dark:text-red-300">
                        <p className="font-medium mb-1">{t('technical.errors.invalidUrl')}</p>
                        <p>{t('technical.errors.urlProtocol')}</p>
                        <p className="mt-1 text-xs">{t('technical.errors.validExamples')}</p>
                        <ul className="mt-1 text-xs space-y-1">
                          <li>• <code className="bg-red-100 dark:bg-red-800 px-1 py-0.5 rounded">https://api.example.com</code></li>
                          <li>• <code className="bg-red-100 dark:bg-red-800 px-1 py-0.5 rounded">http://localhost:3000</code></li>
                          <li>• <code className="bg-red-100 dark:bg-red-800 px-1 py-0.5 rounded">https://my-api.herokuapp.com/v1</code></li>
                        </ul>
                      </div>
                    </div>
                  </div>
                );
              }
              return null;
            } catch {
              return (
                <div className="mt-2 p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-700 rounded-lg">
                  <div className="flex items-start space-x-2">
                    <AlertCircle className="w-4 h-4 text-red-500 mt-0.5 flex-shrink-0" />
                    <div className="text-sm text-red-700 dark:text-red-300">
                      <p className="font-medium mb-1">{t('technical.errors.invalidFormat')}</p>
                      <p>{t('technical.errors.formatDescription')}</p>
                      <p className="mt-1 text-xs">{t('technical.errors.validExamples')}</p>
                      <ul className="mt-1 text-xs space-y-1">
                        <li>• <code className="bg-red-100 dark:bg-red-800 px-1 py-0.5 rounded">https://api.example.com</code></li>
                        <li>• <code className="bg-red-100 dark:bg-red-800 px-1 py-0.5 rounded">http://localhost:3000</code></li>
                        <li>• <code className="bg-red-100 dark:bg-red-800 px-1 py-0.5 rounded">https://my-api.herokuapp.com/v1</code></li>
                      </ul>
                    </div>
                  </div>
                </div>
              );
            }
          })()}
        </FormField>

        <div className="mt-4">
          <FormField
            label={t('technical.healthcheck')}
            description={t('technical.healthcheckDescription')}
          >
            <FormInput
              type="text"
              value={apiConfig.healthcheckEndpoint}
              onChange={(value) => setApiConfig({ ...apiConfig, healthcheckEndpoint: value })}
              placeholder="/health"
            />
          </FormField>
        </div>
      </FormSection>

      {/* Authorization Section */}
      <FormSection
        title={t('auth.title')}
        description={t('auth.description')}
        icon={Shield}
        collapsible
        isExpanded={sectionsExpanded.authorization}
        onToggle={() => toggleSection('authorization')}
      >
        <div className="flex items-center justify-between mb-4">
          <button
            type="button"
            onClick={() => setShowAuthorizationInfo(!showAuthorizationInfo)}
            className="text-theme-muted hover:text-theme-primary transition-colors"
          >
            <Info className="w-5 h-5" />
          </button>
        </div>

        {showAuthorizationInfo && (
          <InfoBox
            type="info"
            title={t('auth.infoTitle')}
          >
            <p>
              {t('auth.infoText')}
            </p>
          </InfoBox>
        )}

        <div className="space-y-4 mt-4">
          <FormField
            label={t('auth.type')}
            description={t('auth.typeDescription')}
            required
          >
            <FormSelect
              value={apiConfig.authorization.type}
              onChange={(value) => {
                const newType = value as any;
                let updatedAuth = { ...apiConfig.authorization, type: newType };
                
                // Set default values based on type
                if (newType === 'bearer') {
                  updatedAuth.headerName = 'Authorization';
                  updatedAuth.headerValue = 'Bearer ';
                  // Clear basic auth fields
                  delete updatedAuth.username;
                  delete updatedAuth.password;
                } else if (newType === 'apisecret') {
                  updatedAuth.headerName = 'X-MCPW-PROXY-SECRET';
                  // Generate new API key when switching to apisecret
                  const generateRandomHeaderValue = () => {
                    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
                    let result = 'sk-';
                    for (let i = 0; i < 32; i++) {
                      result += chars.charAt(Math.floor(Math.random() * chars.length));
                    }
                    return result;
                  };
                  updatedAuth.headerValue = generateRandomHeaderValue();
                  // Clear basic auth fields
                  delete updatedAuth.username;
                  delete updatedAuth.password;
                } else if (newType === 'basic') {
                  // Clear header fields for basic auth
                  delete updatedAuth.headerName;
                  delete updatedAuth.headerValue;
                  // Set default username/password if not already set
                  updatedAuth.username = updatedAuth.username || '';
                  updatedAuth.password = updatedAuth.password || '';
                } else if (newType === 'none') {
                  // Clear all auth fields
                  updatedAuth = {
                    type: 'none',
                    description: 'No authentication required'
                  };
                }
                
                setApiConfig({
                  ...apiConfig,
                  authorization: updatedAuth
                });
              }}
              options={authorizationOptions}
            />
          </FormField>

          {(apiConfig.authorization.type === 'apisecret' || apiConfig.authorization.type === 'bearer') && (
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <FormField
                label={t('auth.headerName')}
                description={t('auth.headerNameDescription')}
                required
              >
                <FormInput
                  type="text"
                  value={apiConfig.authorization.type === 'bearer' && !apiConfig.authorization.headerName ? 'Authorization' : apiConfig.authorization.headerName}
                  onChange={(value) => {
                    setApiConfig({
                      ...apiConfig,
                      authorization: {
                        ...apiConfig.authorization,
                        headerName: value
                      }
                    });
                  }}
                  placeholder={apiConfig.authorization.type === 'apisecret' ? 'X-MCPW-PROXY-SECRET' : 'Authorization'}
                />
              </FormField>

              <FormField
                label={t('auth.headerValue')}
                description={apiConfig.authorization.type === 'apisecret' ? t('auth.headerValueApiSecret') : t('auth.headerValueBearer')}
                required
              >
                <FormInput
                  type="text"
                  value={apiConfig.authorization.type === 'bearer' && !apiConfig.authorization.headerValue ? 'Bearer ' : apiConfig.authorization.headerValue || ''}
                  onChange={(value) => {
                    setApiConfig({
                      ...apiConfig,
                      authorization: {
                        ...apiConfig.authorization,
                        headerValue: value
                      }
                    });
                  }}
                  placeholder={apiConfig.authorization.type === 'apisecret' ? 'sk-1234567890abcdef...' : 'your-bearer-token-here'}
                />
              </FormField>
            </div>
          )}

          {apiConfig.authorization.type === 'basic' && (
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <FormField
                label={t('auth.username')}
                description={t('auth.usernameDescription')}
                required
              >
                <FormInput
                  type="text"
                  value={apiConfig.authorization.username || ''}
                  onChange={(value) => {
                    setApiConfig({
                      ...apiConfig,
                      authorization: {
                        ...apiConfig.authorization,
                        username: value
                      }
                    });
                  }}
                  placeholder="your-username"
                />
              </FormField>

              <FormField
                label={t('auth.password')}
                description={t('auth.passwordDescription')}
                required
              >
                <FormInput
                  type="password"
                  value={apiConfig.authorization.password || ''}
                  onChange={(value) => {
                    setApiConfig({
                      ...apiConfig,
                      authorization: {
                        ...apiConfig.authorization,
                        password: value
                      }
                    });
                  }}
                  placeholder="your-password"
                />
              </FormField>
            </div>
          )}
        </div>
      </FormSection>

      {/* API Visibility Section */}
      <FormSection
        title={t('visibility.title')}
        description={t('visibility.description')}
        icon={Eye}
        collapsible
        isExpanded={sectionsExpanded.visibility}
        onToggle={() => toggleSection('visibility')}
      >
        <FormField
          label={t('visibility.label')}
          required
          description={t('visibility.labelDescription')}
        >
          <FormSelect
            value={apiConfig.visibility}
            onChange={(value) => setApiConfig({ ...apiConfig, visibility: value as any })}
            options={visibilityOptions}
          />
        </FormField>
      </FormSection>
    </div>
  );
};

export default Step2;
