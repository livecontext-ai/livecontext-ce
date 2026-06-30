/**
 * HTML Template Resolver
 *
 * Backward compatibility layer for resolving HTML template expressions.
 * New code should use interfaceHtmlUtils.ts directly.
 */

import {
  renderForEditMode,
  ensureCompleteHtml,
  findResolvedValue,
  escapeHtml,
} from './interfaceHtmlUtils';

// Re-export for backward compatibility
export { escapeHtml };

/**
 * Resolve template variables with actual data.
 * @deprecated Use renderForRunMode from interfaceHtmlUtils instead
 */
export function resolveTemplateWithData(
  template: string,
  resolvedData: Record<string, unknown>
): string {
  if (!template || !resolvedData) return template;

  let result = template;

  // Handle {{expr|default}} format. Mirrors backend TemplateEngine.EXPRESSION_PATTERN -
  // accepts SpEL string literals containing `}`/`|` so {{json('{...}')}} works.
  result = result.replace(/\{\{((?:'(?:[^'\\]|\\.)*'|[^}|])+?)(?:\|([^}]*))?\}\}/g, (match, expr, defaultVal) => {
    const value = findResolvedValue(expr.trim(), resolvedData);
    if (value !== undefined && value !== null) {
      // Maps/Lists must serialize as JSON, not Java/JS toString.
      // Mirrors interfaceHtmlUtils.renderForRunMode (interfaceHtmlUtils.ts:858) so the chat
      // side panel and the standalone /app/interface/[id] page render objects as JSON
      // instead of "[object Object]".
      const stringified = typeof value === 'object' ? JSON.stringify(value) : String(value);
      return escapeHtml(stringified);
    }
    if (defaultVal !== undefined) {
      return escapeHtml(defaultVal);
    }
    return `[${expr.trim()}]`;
  });

  return result;
}

/**
 * Extract all variables from HTML template.
 */
export function extractVariables(html: string): string[] {
  // Mirrors backend TemplateEngine.EXPRESSION_PATTERN.
  const regex = /\{\{((?:'(?:[^'\\]|\\.)*'|[^}|])+?)(?:\|[^}]*)?\}\}/g;
  const variables: string[] = [];
  let match;
  while ((match = regex.exec(html)) !== null) {
    const varName = match[1].trim();
    if (varName && !variables.includes(varName)) {
      variables.push(varName);
    }
  }
  return variables;
}

/**
 * Resolve HTML template for edit/preview mode.
 * @deprecated Use renderInterfaceTemplate from interfaceHtmlUtils instead
 */
export function resolveHtmlTemplate(html: string): string {
  if (!html) return '';
  const resolved = renderForEditMode(html);
  return ensureCompleteHtml(resolved);
}
