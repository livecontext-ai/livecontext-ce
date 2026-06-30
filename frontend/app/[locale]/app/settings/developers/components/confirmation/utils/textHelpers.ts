import { MonetizationConfig, ApiConfig } from '../../../types';

/**
 * Gets the pricing model display text key
 */
export function getPricingModelTextKey(monetizationConfig: MonetizationConfig): string {
  switch (monetizationConfig.pricing) {
    case 'freemium':
      return 'pricing.freemium';
    case 'paid':
      return 'pricing.paid';
    default:
      return 'common.notDefined';
  }
}

/**
 * Gets the authorization type display text key
 */
export function getAuthorizationTypeTextKey(apiConfig: ApiConfig): string {
  switch (apiConfig.authorization.type) {
    case 'apisecret':
      return 'auth.apiProxySecret';
    case 'bearer':
      return 'auth.bearerToken';
    case 'basic':
      return 'auth.basicAuth';
    case 'none':
      return 'auth.noAuth';
    default:
      return 'common.notDefined';
  }
}

/**
 * Formats an endpoint for mobile display (truncates long paths)
 */
export function formatEndpointForMobile(endpoint: string): string {
  if (!endpoint || endpoint.length <= 45) {
    return endpoint || 'No endpoint';
  }

  const parts = endpoint.split('/');
  const pathParams = parts.filter(part => part.startsWith('{') && part.endsWith('}'));

  if (pathParams.length > 0) {
    const basePath = parts.slice(0, 3).filter(p => p);
    const lastPart = parts[parts.length - 1];

    let result = '/' + basePath.join('/');
    if (pathParams.length <= 2) {
      result += '/' + pathParams.join('/');
    } else {
      result += '/' + pathParams.slice(0, 2).join('/') + '/...';
    }
    if (lastPart && !lastPart.startsWith('{')) {
      result += '/' + lastPart;
    }
    return result;
  } else if (parts.length > 4) {
    return '/' + parts.slice(1, 3).join('/') + '/...' + (parts[parts.length - 1] ? '/' + parts[parts.length - 1] : '');
  }

  return endpoint;
}

/**
 * Color mapping for parameter types
 */
export const PARAMETER_TYPE_COLORS: Record<string, string> = {
  string: 'bg-blue-100 text-blue-800',
  number: 'bg-green-100 text-green-800',
  boolean: 'bg-purple-100 text-purple-800',
  object: 'bg-orange-100 text-orange-800',
  array: 'bg-pink-100 text-pink-800'
};

/**
 * Color mapping for response types
 */
export const RESPONSE_TYPE_COLORS: Record<string, string> = {
  json: 'bg-blue-100 text-blue-800',
  xml: 'bg-orange-100 text-orange-800',
  csv: 'bg-green-100 text-green-800',
  text: 'bg-gray-100 text-gray-800',
  html: 'bg-purple-100 text-purple-800',
  binary: 'bg-red-100 text-red-800'
};
