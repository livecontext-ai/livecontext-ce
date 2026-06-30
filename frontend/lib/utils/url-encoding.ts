/**
 * URL Encoding Utilities
 * Functions to safely encode values for use in URL paths
 */

/**
 * Encode a stepAlias for use in URL path segments.
 * Replaces slashes with dashes to avoid routing issues, as slashes are path separators.
 * 
 * The backend will handle both formats (with slash and with dash) when searching.
 * 
 * @param stepAlias - The step alias to encode
 * @returns Encoded step alias safe for use in URL paths
 * 
 * @example
 * encodeStepAliasForUrl("If / else") // Returns "If%20-%20else" (encoded)
 */
export function encodeStepAliasForUrl(stepAlias: string): string {
  if (!stepAlias) return '';
  
  // Replace slashes with dashes before encoding to avoid routing issues
  // Spring Boot will decode the path variable, and slashes break routing
  // The backend will handle both formats (with / and with -) when searching
  const safeAlias = stepAlias.replace(/\//g, '-');
  
  // Then encode the result
  return encodeURIComponent(safeAlias);
}

