/**
 * Removes the syntax-highlight wrappers the expression editor paints (`<span class="token-expression">{{x}}</span>`)
 * from a value, should one ever have been saved with them, and leaves every other tag alone.
 *
 * Each wrapper is removed TOGETHER WITH ITS OWN closing tag. Stripping `</span>` globally is what
 * this replaced, and it corrupted real templates: an interface whose HTML used `<span>` lost every
 * closing tag on its first save from the inspector, so its spans nested into one another and its
 * script, writing `textContent` into one of them, deleted the elements it looked up next.
 */
export function stripEditorHighlightSpans(value: string): string {
  if (!value || !value.includes('token-expression')) return value || '';
  // token-expression is the only class the editor paints: a template's own `token-*` class stays.
  return value.replace(/<span\b[^>]*\bclass="token-expression"[^>]*>([^<]*)<\/span>/gi, '$1');
}
