import React, { useState } from 'react';
import { CheckCircle, AlertCircle, Copy, ExternalLink } from 'lucide-react';

interface UriInputProps {
  value: string;
  onChange: (value: string) => void;
  baseUrl: string;
  placeholder?: string;
  disabled?: boolean;
  className?: string;
  showFullUrl?: boolean;
  showValidation?: boolean;
  onBaseUrlChange?: (baseUrl: string) => void;
  label?: string;
  description?: string;
  required?: boolean;
}

const UriInput: React.FC<UriInputProps> = ({
  value,
  onChange,
  baseUrl,
  placeholder = "/api/endpoint",
  disabled = false,
  className = '',
  showFullUrl = true,
  showValidation = true,
  onBaseUrlChange,
  label = "Relative endpoint",
  description = "",
  required = false
}) => {
  const [copied, setCopied] = useState(false);

  const handleEndpointChange = (newValue: string) => {
    // Allow user to type freely, don't force leading slash
    onChange(newValue);
  };

  const handleBaseUrlChange = (newBaseUrl: string) => {
    if (onBaseUrlChange) {
      onBaseUrlChange(newBaseUrl);
    }
  };

  const getFullUrl = () => {
    if (!baseUrl || !value) return '';
    const cleanBaseUrl = baseUrl.replace(/\/$/, '');
    const cleanEndpoint = value.startsWith('/') ? value.substring(1) : value;
    return `${cleanBaseUrl}/${cleanEndpoint}`;
  };

  const copyToClipboard = async () => {
    const fullUrl = getFullUrl();
    if (fullUrl) {
      try {
        await navigator.clipboard.writeText(fullUrl);
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
      } catch (err) {
        console.error('Error during copy:', err);
      }
    }
  };

  const openUrl = () => {
    const fullUrl = getFullUrl();
    if (fullUrl) {
      window.open(fullUrl, '_blank');
    }
  };

  const isValidUrl = () => {
    try {
      const fullUrl = getFullUrl();
      return fullUrl && new URL(fullUrl);
    } catch {
      return false;
    }
  };

  const hasValue = value !== undefined && value !== null && value.trim() !== '';
  const fullUrl = getFullUrl();

  return (
    <div className="space-y-3">
      {/* Label and description */}
      {label && (
        <label className="block text-sm font-medium text-theme-primary mb-2">
          {label}
          {required && <span className="text-red-500 ml-1">*</span>}
          {description && (
            <span className="ml-2 text-xs text-theme-muted">
              {description}
            </span>
          )}
        </label>
      )}

      {/* Inputs for base URL and endpoint */}
      <div className="space-y-2">
        {/* Desktop layout */}
        <div className="hidden sm:flex items-center space-x-2">
          <div className="flex-shrink-0">
            <div
              className="overflow-hidden whitespace-nowrap text-xs sm:text-sm"
              title={baseUrl || "https://api.example.com"} // Tooltip to see full URL on hover
              style={{
                textOverflow: 'ellipsis',
                maxWidth: '180px'
              }}
            >
              {baseUrl || "https://api.example.com"}
            </div>
          </div>
          <span className="text-theme-muted text-sm font-medium">+</span>
          <div className="flex-1">
            <input
              type="text"
              value={value}
              onChange={(e) => handleEndpointChange(e.target.value)}
              placeholder={placeholder}
              disabled={disabled}
              className={`w-full px-4 py-3 bg-theme-primary border rounded-lg text-theme-primary placeholder-theme-muted focus:outline-none focus:ring-2 focus:ring-blue-500/50 transition-all duration-300 disabled:opacity-50 disabled:cursor-not-allowed ${
                !hasValue ? 'border-red-300' : 'border-theme'
              } ${className}`}
            />
          </div>
        </div>

        {/* Mobile layout */}
        <div className="sm:hidden space-y-3">
          {/* Base URL section */}
          <div className="space-y-1">
            <label className="text-xs text-theme-muted font-medium">Base URL</label>
            <div className="flex items-center space-x-1">
              <div className="flex-1 min-w-0">
                <div
                  className="overflow-hidden whitespace-nowrap text-xs"
                  title={baseUrl || "https://api.example.com"} // Tooltip to see full URL on hover
                  style={{
                    textOverflow: 'ellipsis',
                    maxWidth: '140px'
                  }}
                >
                  {baseUrl || "https://api.example.com"}
                </div>
              </div>
              <span className="text-theme-muted text-xs font-medium flex-shrink-0 bg-theme-secondary px-1.5 py-0.5 rounded ml-1">+</span>
            </div>
          </div>

          {/* Relative Path section */}
          <div className="space-y-1">
            <label className="text-xs text-theme-muted font-medium">Relative Path</label>
            <div className="relative">
              <input
                type="text"
                value={value}
                onChange={(e) => handleEndpointChange(e.target.value)}
                placeholder={placeholder}
                disabled={disabled}
                className={`w-full px-4 py-3 bg-theme-primary border rounded-lg text-theme-primary placeholder-theme-muted focus:outline-none focus:ring-2 focus:ring-blue-500/50 transition-all duration-300 disabled:opacity-50 disabled:cursor-not-allowed ${
                  !hasValue ? 'border-red-300' : 'border-theme'
                } ${className}`}
              />
            </div>
          </div>
        </div>
      </div>


      {/* Validation and status */}
      {showValidation && (
        <div className="space-y-2">
          {!hasValue && (
            <p className="text-sm text-orange-600 dark:text-orange-400 flex items-center space-x-1">
              <AlertCircle className="w-3 h-3" />
              <span>Relative endpoint required to test this tool</span>
            </p>
          )}

          {hasValue && !isValidUrl() && (
            <p className="text-sm text-red-600 dark:text-red-400 flex items-center space-x-1">
              <AlertCircle className="w-3 h-3" />
              <span>Invalid URL - Check the base URL</span>
            </p>
          )}
        </div>
      )}
    </div>
  );
};

export default UriInput;
