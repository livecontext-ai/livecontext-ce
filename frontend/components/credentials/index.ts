/**
 * Reusable credential management components
 *
 * @example
 * ```tsx
 * import {
 *   CredentialWizard,
 *   type CredentialRequirement,
 * } from "@/components/credentials";
 *
 * // Single credential mode
 * <CredentialWizard
 *   template={selectedTemplate}
 *   open={isOpen}
 *   onOpenChange={setIsOpen}
 *   onComplete={(completed) => console.log('Done:', completed)}
 * />
 *
 * // Multiple credentials mode
 * <CredentialWizard
 *   requirements={[
 *     { iconSlug: 'gmail', serviceName: 'Gmail' },
 *     { iconSlug: 'slack', serviceName: 'Slack' },
 *   ]}
 *   open={isOpen}
 *   onOpenChange={setIsOpen}
 *   onComplete={(completed) => console.log('Completed:', completed)}
 * />
 * ```
 */

// Main unified component
export {
  CredentialWizard,
  type CredentialWizardProps,
  type CredentialRequirement as CredentialWizardRequirement,
  type CredentialRequirement,
} from "./CredentialWizard";

// OAuth2 hook (still useful for programmatic OAuth2 initiation)
export {
  useOAuth2,
  type UseOAuth2Options,
  type UseOAuth2Return,
  type OAuth2CallbackResult,
} from "./useOAuth2";

// Legacy components (kept for backward compatibility)
export {
  OAuth2CredentialPicker,
  type OAuth2CredentialPickerProps,
} from "./OAuth2CredentialPicker";

