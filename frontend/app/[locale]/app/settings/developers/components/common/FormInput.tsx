import React from 'react';
import { Input } from '@/components/ui/input';

interface FormInputProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  type?: 'text' | 'email' | 'password' | 'number' | 'url';
  disabled?: boolean;
  className?: string;
  min?: number;
  max?: number;
  step?: number;
}

// Nouvelle interface pour l'input d'URI avec base URL
interface UriInputProps {
  value: string;
  onChange: (value: string) => void;
  baseUrl: string;
  placeholder?: string;
  disabled?: boolean;
  className?: string;
  showFullUrl?: boolean;
  onBaseUrlChange?: (baseUrl: string) => void;
}

const FormInput: React.FC<FormInputProps> = ({
  value,
  onChange,
  placeholder,
  type = 'text',
  disabled = false,
  className = '',
  min,
  max,
  step
}) => {
  return (
    <Input
      type={type}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder={placeholder}
      disabled={disabled}
      min={min}
      max={max}
      step={step}
      className={className}
    />
  );
};

// New component for URI input with base URL pre-filling
const UriInput: React.FC<UriInputProps> = ({
  value,
  onChange,
  baseUrl,
  placeholder = "/api/endpoint",
  disabled = false,
  className = '',
  showFullUrl = true,
  onBaseUrlChange
}) => {
  const handleEndpointChange = (newValue: string) => {
    // Ensure endpoint starts with /
    const cleanValue = newValue.startsWith('/') ? newValue : `/${newValue}`;
    onChange(cleanValue);
  };

  const handleBaseUrlChange = (newBaseUrl: string) => {
    if (onBaseUrlChange) {
      onBaseUrlChange(newBaseUrl);
    }
  };

  const getFullUrl = () => {
    if (!baseUrl || !value) return '';
    const cleanBaseUrl = baseUrl.replace(/\/$/, '');
    const cleanEndpoint = value.replace(/^\//, '');
    return `${cleanBaseUrl}/${cleanEndpoint}`;
  };

  return (
    <div className="space-y-3">
      {/* Base URL + endpoint display */}
      <div className="flex items-center space-x-2">
        <div className="flex-shrink-0">
          <Input
            type="url"
            value={baseUrl}
            onChange={(e) => handleBaseUrlChange(e.target.value)}
            placeholder="https://api.example.com"
            disabled={disabled || !onBaseUrlChange}
            className={`px-3 text-sm ${
              onBaseUrlChange ? 'cursor-text' : 'cursor-not-allowed'
            }`}
          />
        </div>
        <span className="text-theme-muted text-sm font-medium">+</span>
        <div className="flex-1">
          <Input
            type="text"
            value={value}
            onChange={(e) => handleEndpointChange(e.target.value)}
            placeholder={placeholder}
            disabled={disabled}
            className={className}
          />
        </div>
      </div>

      {/* Full URL display */}
      {showFullUrl && getFullUrl() && (
        <div className="p-2 bg-gray-100 dark:bg-gray-700 rounded border border-gray-300 dark:border-gray-600">
          <p className="text-sm text-gray-600 dark:text-gray-400 mb-1">Complete URL:</p>
          <p className="text-sm font-mono text-gray-800 dark:text-gray-200 break-all">
            {getFullUrl()}
          </p>
        </div>
      )}
    </div>
  );
};

export default FormInput;
export { UriInput };
