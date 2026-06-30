/**
 * Core module exports for the Inspector Panel architecture.
 */

// Types
export * from './types';

// Hooks
export { useFormCommonProps, spreadConnectionProps } from './useFormCommonProps';

// Renderers
export { FormRenderer } from './FormRenderer';
export { OutputRenderer } from './OutputRenderer';

// Form Adapters (for bridging legacy forms)
export * from './form-adapters';
