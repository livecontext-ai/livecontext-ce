'use client';

import * as React from 'react';
import { createPortal } from 'react-dom';
import clsx from 'clsx';

interface ExpressionEditorProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  className?: string;
  fullHeight?: boolean;
  readOnly?: boolean;
  unknownVariables?: string[];
  handleId?: string;
  connections?: Array<{ id: string; source: string; target: string }>;
  onHandleMouseDown?: (handleId: string, e: React.MouseEvent) => void;
  onHandleMouseUp?: (handleId: string, e: React.MouseEvent) => void;
  onHandleClick?: (handleId: string, e: React.MouseEvent) => void;
  draggingFromHandle?: string | null;
  hoveredTargetHandle?: string | null;
  onSetHandleRef?: (handleId: string, element: HTMLDivElement | null) => void;
  showConnectionHandle?: boolean;
  isRequired?: boolean;
  onDrop?: (e: React.DragEvent<HTMLTextAreaElement>) => void;
}

// Expression suggestions with full operation support
interface SuggestionItem {
  label: string;
  insert: string;
  description: string;
  category: 'arithmetic' | 'comparison' | 'logical' | 'ternary' | 'string' | 'collection' | 'type' | 'navigation' | 'variable' | 'math' | 'utility' | 'date';
  returnType?: 'number' | 'string' | 'boolean' | 'array' | 'object' | 'any';
}

const SPEL_SUGGESTIONS: SuggestionItem[] = [
  // TYPE CASTING FUNCTIONS
  { label: 'int()', insert: 'int(value)', description: 'Convert to integer (handles strings)', category: 'type', returnType: 'number' },
  { label: 'long()', insert: 'long(value)', description: 'Convert to long integer', category: 'type', returnType: 'number' },
  { label: 'float()', insert: 'float(value)', description: 'Convert to float', category: 'type', returnType: 'number' },
  { label: 'double()', insert: 'double(value)', description: 'Convert to double precision', category: 'type', returnType: 'number' },
  { label: 'string()', insert: 'string(value)', description: 'Convert to string', category: 'type', returnType: 'string' },
  { label: 'bool()', insert: 'bool(value)', description: 'Convert to boolean (1/0, true/false, yes/no)', category: 'type', returnType: 'boolean' },

  // UTILITY FUNCTIONS
  { label: 'size()', insert: 'size(value)', description: 'Get size of string/list/map', category: 'utility', returnType: 'number' },
  { label: 'len()', insert: 'len(value)', description: 'Get length (alias for size)', category: 'utility', returnType: 'number' },
  { label: 'typeof()', insert: 'typeof(value)', description: 'Get type name (string, int, list...)', category: 'utility', returnType: 'string' },
  { label: 'default()', insert: 'default(value, fallback)', description: 'Return fallback if value is null/empty', category: 'utility', returnType: 'any' },
  { label: 'coalesce()', insert: 'coalesce(a, b, c)', description: 'Return first non-null value', category: 'utility', returnType: 'any' },
  { label: 'ifempty()', insert: 'ifempty(value, fallback)', description: 'Return fallback if value is empty', category: 'utility', returnType: 'any' },
  { label: 'isnull()', insert: 'isnull(value)', description: 'Check if value is null', category: 'utility', returnType: 'boolean' },
  { label: 'isempty()', insert: 'isempty(value)', description: 'Check if value is empty (null, "", [])', category: 'utility', returnType: 'boolean' },
  { label: 'length()', insert: 'length(value)', description: 'Get string length', category: 'utility', returnType: 'number' },
  { label: 'json()', insert: 'json(value)', description: 'Parse JSON string → typed Map/List (idempotent on objects)', category: 'utility', returnType: 'any' },
  { label: 'fromjson()', insert: 'fromjson(value)', description: 'Alias for json() - GitHub Actions parity', category: 'utility', returnType: 'any' },
  { label: 'tojson()', insert: 'tojson(value)', description: 'Serialize Map/List → compact JSON string', category: 'utility', returnType: 'string' },

  // MATH FUNCTIONS
  { label: 'abs()', insert: 'abs(value)', description: 'Absolute value', category: 'math', returnType: 'number' },
  { label: 'round()', insert: 'round(value, decimals)', description: 'Round to decimal places', category: 'math', returnType: 'number' },
  { label: 'floor()', insert: 'floor(value)', description: 'Round down to nearest integer', category: 'math', returnType: 'number' },
  { label: 'ceil()', insert: 'ceil(value)', description: 'Round up to nearest integer', category: 'math', returnType: 'number' },
  { label: 'min()', insert: 'min(a, b)', description: 'Minimum of two values', category: 'math', returnType: 'number' },
  { label: 'max()', insert: 'max(a, b)', description: 'Maximum of two values', category: 'math', returnType: 'number' },
  { label: 'pow()', insert: 'pow(base, exp)', description: 'Power function (base^exp)', category: 'math', returnType: 'number' },
  { label: 'sqrt()', insert: 'sqrt(value)', description: 'Square root', category: 'math', returnType: 'number' },

  // STRING FUNCTIONS
  { label: 'uppercase()', insert: 'uppercase(value)', description: 'Convert to UPPERCASE', category: 'string', returnType: 'string' },
  { label: 'lowercase()', insert: 'lowercase(value)', description: 'Convert to lowercase', category: 'string', returnType: 'string' },
  { label: 'capitalize()', insert: 'capitalize(value)', description: 'Capitalize first letter', category: 'string', returnType: 'string' },
  { label: 'trim()', insert: 'trim(value)', description: 'Remove leading/trailing whitespace', category: 'string', returnType: 'string' },
  { label: 'truncate()', insert: "truncate(value, 50, '...')", description: 'Truncate with ellipsis', category: 'string', returnType: 'string' },
  { label: 'padleft()', insert: "padleft(value, 5, '0')", description: 'Pad left with character', category: 'string', returnType: 'string' },
  { label: 'padright()', insert: "padright(value, 5, ' ')", description: 'Pad right with character', category: 'string', returnType: 'string' },
  { label: 'replace()', insert: "replace(value, 'old', 'new')", description: 'Replace text occurrences', category: 'string', returnType: 'string' },
  { label: 'substring()', insert: 'substring(value, start, end)', description: 'Extract substring', category: 'string', returnType: 'string' },
  { label: 'split()', insert: "split(value, ',')", description: 'Split string into array', category: 'string', returnType: 'array' },
  { label: 'join()', insert: "join(array, ', ')", description: 'Join array into string', category: 'string', returnType: 'string' },
  { label: 'startswith()', insert: "startswith(value, 'prefix')", description: 'Check if starts with', category: 'string', returnType: 'boolean' },
  { label: 'endswith()', insert: "endswith(value, 'suffix')", description: 'Check if ends with', category: 'string', returnType: 'boolean' },
  { label: 'contains()', insert: "contains(value, 'text')", description: 'Check if contains substring', category: 'string', returnType: 'boolean' },
  { label: 'matches()', insert: "matches(value, 'regex')", description: 'Match against regex pattern', category: 'string', returnType: 'boolean' },

  // DATE/NUMBER FORMATTING
  { label: 'formatdate()', insert: "formatdate(value, 'yyyy-MM-dd')", description: 'Format date with pattern', category: 'date', returnType: 'string' },
  { label: 'formatnumber()', insert: 'formatnumber(value, 2)', description: 'Format number with decimals', category: 'date', returnType: 'string' },
  { label: 'formatcurrency()', insert: "formatcurrency(value, 'EUR')", description: 'Format as currency', category: 'date', returnType: 'string' },
  { label: 'now()', insert: 'now()', description: 'Current timestamp (epoch ms)', category: 'date', returnType: 'number' },
  { label: 'today()', insert: 'today()', description: "Today's date (ISO format)", category: 'date', returnType: 'string' },

  // ARITHMETIC OPERATORS
  { label: '+', insert: ' + ', description: 'Addition', category: 'arithmetic', returnType: 'number' },
  { label: '-', insert: ' - ', description: 'Subtraction', category: 'arithmetic', returnType: 'number' },
  { label: '*', insert: ' * ', description: 'Multiplication', category: 'arithmetic', returnType: 'number' },
  { label: '/', insert: ' / ', description: 'Division', category: 'arithmetic', returnType: 'number' },
  { label: '%', insert: ' % ', description: 'Modulo (remainder)', category: 'arithmetic', returnType: 'number' },

  // COMPARISON OPERATORS
  { label: '==', insert: ' == ', description: 'Equal to', category: 'comparison', returnType: 'boolean' },
  { label: '!=', insert: ' != ', description: 'Not equal', category: 'comparison', returnType: 'boolean' },
  { label: '>', insert: ' > ', description: 'Greater than', category: 'comparison', returnType: 'boolean' },
  { label: '<', insert: ' < ', description: 'Less than', category: 'comparison', returnType: 'boolean' },
  { label: '>=', insert: ' >= ', description: 'Greater or equal', category: 'comparison', returnType: 'boolean' },
  { label: '<=', insert: ' <= ', description: 'Less or equal', category: 'comparison', returnType: 'boolean' },

  // LOGICAL OPERATORS
  { label: '&&', insert: ' && ', description: 'Logical AND', category: 'logical', returnType: 'boolean' },
  { label: '||', insert: ' || ', description: 'Logical OR', category: 'logical', returnType: 'boolean' },
  { label: '!', insert: '!', description: 'Logical NOT', category: 'logical', returnType: 'boolean' },

  // TERNARY & NULL-SAFE
  { label: '? :', insert: ' ? true_val : false_val', description: 'Ternary condition', category: 'ternary', returnType: 'any' },
  { label: '?:', insert: ' ?: default', description: 'Elvis operator (null fallback)', category: 'ternary', returnType: 'any' },
  { label: '?.', insert: '?.', description: 'Safe navigation (null-safe)', category: 'navigation', returnType: 'any' },

  // COLLECTION METHODS
  { label: '.size()', insert: '.size()', description: 'Collection size', category: 'collection', returnType: 'number' },
  { label: '.get()', insert: '.get(index)', description: 'Get by index/key', category: 'collection', returnType: 'any' },
  { label: '[n]', insert: '[0]', description: 'Array index access', category: 'collection', returnType: 'any' },
  { label: "['key']", insert: "['key']", description: 'Map key access', category: 'collection', returnType: 'any' },
  { label: '.?[]', insert: '.?[condition]', description: 'Filter (select matching)', category: 'collection', returnType: 'array' },
  { label: '.![]', insert: '.![field]', description: 'Projection (map/extract)', category: 'collection', returnType: 'array' },

  // VARIABLES & NAMESPACES - Using unified {{type:label.output.field}} pattern
  { label: 'trigger:', insert: 'trigger:', description: 'Trigger outputs (trigger:label.output.field)', category: 'variable', returnType: 'object' },
  { label: 'mcp:', insert: 'mcp:', description: 'MCP/Tool outputs (mcp:label.output.field)', category: 'variable', returnType: 'object' },
  { label: 'agent:', insert: 'agent:', description: 'Agent outputs (agent:label.output.field)', category: 'variable', returnType: 'object' },
  { label: 'core:', insert: 'core:', description: 'Core node outputs (core:label.output.field)', category: 'variable', returnType: 'object' },
  { label: 'core:split.output.current_item', insert: 'core:split.output.current_item', description: 'Current item in parallel branch (runtime context)', category: 'variable', returnType: 'object' },
  { label: 'core:split.output.current_index', insert: 'core:split.output.current_index', description: 'Current item index (0-based, runtime context)', category: 'variable', returnType: 'number' },
  { label: 'core:split.output.items', insert: 'core:split.output.items', description: 'Split items list (persisted output)', category: 'variable', returnType: 'array' },
  { label: 'core:split.output.item_count', insert: 'core:split.output.item_count', description: 'Split total item count (persisted output)', category: 'variable', returnType: 'number' },
  { label: 'core:loop.output.iteration', insert: 'core:loop.output.iteration', description: 'Loop iteration (1-based)', category: 'variable', returnType: 'number' },
  { label: 'true', insert: 'true', description: 'Boolean true', category: 'variable', returnType: 'boolean' },
  { label: 'false', insert: 'false', description: 'Boolean false', category: 'variable', returnType: 'boolean' },
  { label: 'null', insert: 'null', description: 'Null value', category: 'variable', returnType: 'any' },
];

// Parse text into segments for rendering
interface TextSegment {
  text: string;
  isExpression: boolean;
}

function parseExpressions(text: string): TextSegment[] {
  const segments: TextSegment[] = [];
  let lastIndex = 0;
  const regex = /\{\{([^}|]+(?:\|[^}]*)?)\}\}/g;
  let match;

  while ((match = regex.exec(text)) !== null) {
    // Add text before the match
    if (match.index > lastIndex) {
      segments.push({
        text: text.substring(lastIndex, match.index),
        isExpression: false,
      });
    }
    // Add the expression
    segments.push({
      text: match[0],
      isExpression: true,
    });
    lastIndex = match.index + match[0].length;
  }

  // Add remaining text
  if (lastIndex < text.length) {
    segments.push({
      text: text.substring(lastIndex),
      isExpression: false,
    });
  }

  return segments;
}

export function ExpressionEditor({
  value: rawValue,
  onChange,
  placeholder = "Expression...",
  className,
  fullHeight = false,
  readOnly = false,
  onDrop,
  isRequired,
}: ExpressionEditorProps) {
  // Coerce to string - callers may pass numbers or objects from dynamic form data
  const value = typeof rawValue === 'string' ? rawValue : rawValue != null ? String(rawValue) : '';
  const editorRef = React.useRef<HTMLDivElement>(null);
  const containerRef = React.useRef<HTMLDivElement>(null);

  const [showSuggestions, setShowSuggestions] = React.useState(false);
  const [selectedIndex, setSelectedIndex] = React.useState(0);
  const [filterText, setFilterText] = React.useState('');
  const [popoverPosition, setPopoverPosition] = React.useState({ top: 0, left: 0 });
  const [popoverTab, setPopoverTab] = React.useState<'suggestions' | 'help'>('suggestions');
  const [isFocused, setIsFocused] = React.useState(false);
  // Drop-target indicator: a thin vertical bar shown at the would-be drop caret while
  // dragging over, so the user sees where the dragged content will land.
  const [dropCaret, setDropCaret] = React.useState<{ top: number; left: number; height: number } | null>(null);

  // Filter suggestions
  const filteredSuggestions = React.useMemo(() => {
    if (!filterText) return SPEL_SUGGESTIONS;
    const lower = filterText.toLowerCase();
    return SPEL_SUGGESTIONS.filter(s =>
      s.label.toLowerCase().includes(lower) ||
      s.description.toLowerCase().includes(lower)
    );
  }, [filterText]);

  // Check if cursor is inside {{...}}
  const isInsideExpression = React.useCallback((text: string, pos: number): boolean => {
    let depth = 0;
    for (let i = 0; i < pos && i < text.length; i++) {
      if (text[i] === '{' && text[i + 1] === '{') {
        depth++;
        i++;
      } else if (text[i] === '}' && text[i + 1] === '}' && depth > 0) {
        depth--;
        i++;
      }
    }
    return depth > 0;
  }, []);

  // Get current word for filtering
  const getCurrentWord = React.useCallback((text: string, pos: number): string => {
    let start = pos;
    while (start > 0 && /[\w.]/.test(text[start - 1])) {
      start--;
    }
    return text.substring(start, pos);
  }, []);

  // Get cursor position in contentEditable
  const getCursorPosition = React.useCallback((): number => {
    const selection = window.getSelection();
    if (!selection || !selection.rangeCount || !editorRef.current) return 0;

    const range = selection.getRangeAt(0);
    const preCaretRange = range.cloneRange();
    preCaretRange.selectNodeContents(editorRef.current);
    preCaretRange.setEnd(range.startContainer, range.startOffset);

    // Get the text content up to cursor
    const fragment = preCaretRange.cloneContents();
    const tempDiv = document.createElement('div');
    tempDiv.appendChild(fragment);
    return tempDiv.textContent?.length || 0;
  }, []);

  // Set cursor position in contentEditable
  const setCursorPosition = React.useCallback((pos: number) => {
    if (!editorRef.current) return;

    const selection = window.getSelection();
    if (!selection) return;

    const range = document.createRange();
    let currentPos = 0;
    let found = false;

    const walkNodes = (node: Node): boolean => {
      if (node.nodeType === Node.TEXT_NODE) {
        const textLength = node.textContent?.length || 0;
        if (currentPos + textLength >= pos) {
          range.setStart(node, pos - currentPos);
          range.setEnd(node, pos - currentPos);
          found = true;
          return true;
        }
        currentPos += textLength;
      } else {
        for (const child of Array.from(node.childNodes)) {
          if (walkNodes(child)) return true;
        }
      }
      return false;
    };

    walkNodes(editorRef.current);

    if (found) {
      selection.removeAllRanges();
      selection.addRange(range);
    }
  }, []);

  // Update popover position
  const updatePopoverPosition = React.useCallback(() => {
    if (!containerRef.current) return;
    const rect = containerRef.current.getBoundingClientRect();
    setPopoverPosition({
      top: rect.bottom + 4,
      left: rect.left,
    });
  }, []);

  // Track if we're currently updating to prevent loops
  const isUpdatingRef = React.useRef(false);
  const lastValueRef = React.useRef(value);

  // Build HTML with highlighting
  const buildHighlightedHtml = React.useCallback((text: string): string => {
    if (!text) return '';

    const segments = parseExpressions(text);
    let html = '';
    for (const segment of segments) {
      const escapedText = segment.text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');

      if (segment.isExpression) {
        html += `<span class="token-expression">${escapedText}</span>`;
      } else {
        html += escapedText;
      }
    }
    return html;
  }, []);

  // Render content with syntax highlighting - only when value changes externally AND
  // the editor is NOT focused. Re-writing innerHTML while the user is typing destroys
  // the caret anchor and resets scrollTop, which is the root of the "Enter snaps back"
  // / "scroll jumps to top" bugs. While focused we trust the browser's native DOM.
  const renderContent = React.useCallback(() => {
    if (readOnly) return;
    if (!editorRef.current || isUpdatingRef.current) return;
    // Skip during focus - the user is actively typing, native browser behavior wins.
    // handleInput keeps React state in sync; handleBlur re-paints the highlights.
    if (isFocused) {
      lastValueRef.current = value;
      return;
    }

    // Out of focus: read the editor and only repaint if value actually drifted.
    const currentText = editorRef.current.innerText || '';
    if (currentText === value && lastValueRef.current === value) {
      return;
    }

    const html = buildHighlightedHtml(value);
    isUpdatingRef.current = true;
    editorRef.current.innerHTML = html;
    isUpdatingRef.current = false;
    lastValueRef.current = value;
  }, [value, isFocused, readOnly, buildHighlightedHtml]);

  // Debounced highlight update
  const highlightTimeoutRef = React.useRef<NodeJS.Timeout | null>(null);

  const applyHighlighting = React.useCallback(() => {
    if (!editorRef.current || !isFocused || readOnly) return;

    const currentText = editorRef.current.textContent || '';
    const cursorPos = getCursorPosition();
    const html = buildHighlightedHtml(currentText);

    isUpdatingRef.current = true;
    editorRef.current.innerHTML = html;
    isUpdatingRef.current = false;

    // Restore cursor
    if (currentText.length > 0) {
      setCursorPosition(Math.min(cursorPos, currentText.length));
    }
  }, [getCursorPosition, setCursorPosition, isFocused, readOnly, buildHighlightedHtml]);

  // Reset state when switching between readonly and edit modes
  React.useEffect(() => {
    lastValueRef.current = value;
    // Clear the editorRef content when switching to readonly to prevent stale content
    if (readOnly && editorRef.current) {
      editorRef.current.innerHTML = '';
    }
  }, [readOnly, value]);

  // Handle input changes.
  //
  // KEY FIX (Enter / scroll / caret stability): we DO NOT re-render the editor's HTML
  // while the user is typing. innerHTML mutations destroy the caret anchor and reset
  // scrollTop, which historically caused Enter to "snap back" to the previous line and
  // scrolled editors to jump to the top. Instead, the browser's native contentEditable
  // handles caret/scroll/Enter perfectly during typing - we only update React state
  // (so the rest of the app sees the new value) and re-apply syntax highlighting when
  // the user blurs the editor.
  const handleInput = React.useCallback(() => {
    if (!editorRef.current || isUpdatingRef.current) return;

    // Read raw text - innerText preserves newlines from <div>/<br> insertions across
    // browsers more reliably than textContent.
    const newValue = editorRef.current.innerText || '';
    const cursorPos = getCursorPosition();

    onChange(newValue);

    // Suggestions popover: still works during typing because it reads `value`, not
    // the editor DOM directly.
    const inside = isInsideExpression(newValue, cursorPos);
    if (inside) {
      const word = getCurrentWord(newValue, cursorPos);
      setFilterText(word);
      setSelectedIndex(0);
      setShowSuggestions(true);
      updatePopoverPosition();
    } else {
      setShowSuggestions(false);
    }
    // No debounced applyHighlighting here - the browser keeps the user's typed DOM
    // intact (including line breaks). Highlighting is reapplied on blur (see handleBlur).
  }, [onChange, getCursorPosition, isInsideExpression, getCurrentWord, updatePopoverPosition]);

  // Cleanup timeout on unmount
  React.useEffect(() => {
    return () => {
      if (highlightTimeoutRef.current) {
        clearTimeout(highlightTimeoutRef.current);
      }
    };
  }, []);

  // Insert suggestion
  const insertSuggestion = React.useCallback((suggestion: SuggestionItem) => {
    if (!editorRef.current) return;

    const cursorPos = getCursorPosition();

    // Find word start to replace
    let start = cursorPos;
    while (start > 0 && /[\w.]/.test(value[start - 1])) {
      start--;
    }

    const newValue = value.substring(0, start) + suggestion.insert + value.substring(cursorPos);
    onChange(newValue);

    // Set cursor after inserted text
    const newPos = start + suggestion.insert.length;

    // Update the editor content with highlighting
    const html = buildHighlightedHtml(newValue);

    isUpdatingRef.current = true;
    editorRef.current.innerHTML = html;
    isUpdatingRef.current = false;
    lastValueRef.current = newValue;

    setTimeout(() => {
      editorRef.current?.focus();
      setCursorPosition(newPos);
    }, 0);

    setShowSuggestions(false);
  }, [value, onChange, getCursorPosition, setCursorPosition, buildHighlightedHtml]);

  // Handle keyboard navigation
  const handleKeyDown = React.useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Delete' || e.key === 'Backspace') {
      e.stopPropagation();
    }

    if (!showSuggestions || filteredSuggestions.length === 0) return;

    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        setSelectedIndex(i => Math.min(i + 1, filteredSuggestions.length - 1));
        break;
      case 'ArrowUp':
        e.preventDefault();
        setSelectedIndex(i => Math.max(i - 1, 0));
        break;
      case 'Tab':
      case 'Enter':
        e.preventDefault();
        insertSuggestion(filteredSuggestions[selectedIndex]);
        break;
      case 'Escape':
        setShowSuggestions(false);
        break;
    }
  }, [showSuggestions, filteredSuggestions, selectedIndex, insertSuggestion]);

  // Handle click to check for expression context
  const handleClick = React.useCallback(() => {
    const cursorPos = getCursorPosition();
    const inside = isInsideExpression(value, cursorPos);

    if (inside) {
      const word = getCurrentWord(value, cursorPos);
      setFilterText(word);
      setShowSuggestions(true);
      updatePopoverPosition();
    } else {
      setShowSuggestions(false);
    }
  }, [value, getCursorPosition, isInsideExpression, getCurrentWord, updatePopoverPosition]);

  // Handle focus
  const handleFocus = React.useCallback(() => {
    setIsFocused(true);
  }, []);

  // Handle blur - re-apply syntax highlighting once. During typing the editor DOM is
  // untouched so the caret/scroll/Enter behave natively; on blur we paint the colored
  // <span class="token-expression"> wrappers back over the final value.
  const handleBlur = React.useCallback(() => {
    setIsFocused(false);
    setTimeout(() => setShowSuggestions(false), 200);
    // Apply highlighting after focus state has updated (rAF) so we don't fight the
    // browser's selection-clearing on blur.
    requestAnimationFrame(() => {
      if (!editorRef.current) return;
      const currentText = editorRef.current.innerText || '';
      const html = buildHighlightedHtml(currentText);
      isUpdatingRef.current = true;
      editorRef.current.innerHTML = html;
      isUpdatingRef.current = false;
      lastValueRef.current = currentText;
    });
  }, [buildHighlightedHtml]);

  // Handle paste - strip formatting
  const handlePaste = React.useCallback((e: React.ClipboardEvent) => {
    e.preventDefault();
    const text = e.clipboardData.getData('text/plain');
    document.execCommand('insertText', false, text);
  }, []);

  // Compute the linear text offset under a mouse point. Uses caretPositionFromPoint
  // (Firefox / modern Chrome / Safari) with a fallback to caretRangeFromPoint (older
  // Chrome). Walks the editor and accumulates text length up to the (node, offset)
  // pair, counting <br> as 1 char so positions stay aligned with `value`.
  const caretOffsetFromPoint = React.useCallback((clientX: number, clientY: number): number | null => {
    if (!editorRef.current) return null;
    type Doc = Document & {
      caretPositionFromPoint?: (x: number, y: number) => { offsetNode: Node; offset: number } | null;
      caretRangeFromPoint?: (x: number, y: number) => Range | null;
    };
    const d = document as Doc;
    let node: Node | null = null;
    let offset = 0;
    if (typeof d.caretPositionFromPoint === 'function') {
      const pos = d.caretPositionFromPoint(clientX, clientY);
      if (!pos) return null;
      node = pos.offsetNode;
      offset = pos.offset;
    } else if (typeof d.caretRangeFromPoint === 'function') {
      const range = d.caretRangeFromPoint(clientX, clientY);
      if (!range) return null;
      node = range.startContainer;
      offset = range.startOffset;
    } else {
      return null;
    }
    if (!editorRef.current.contains(node)) return null;
    let acc = 0;
    let found = false;
    const walk = (n: Node): boolean => {
      if (n === node) {
        if (n.nodeType === Node.TEXT_NODE) {
          acc += offset;
        } else {
          for (let i = 0; i < offset && i < n.childNodes.length; i++) walk(n.childNodes[i]);
        }
        found = true;
        return true;
      }
      if (n.nodeType === Node.TEXT_NODE) {
        acc += n.textContent?.length || 0;
        return false;
      }
      if (n.nodeName === 'BR') { acc += 1; return false; }
      for (const child of Array.from(n.childNodes)) {
        if (walk(child)) return true;
      }
      return false;
    };
    walk(editorRef.current);
    return found ? acc : null;
  }, []);

  // dragover: keep dropEffect=copy and update the caret indicator position so the user
  // sees exactly where the dragged content will land.
  const handleDragOver = React.useCallback((e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'copy';
    if (!editorRef.current) return;
    type Doc = Document & {
      caretPositionFromPoint?: (x: number, y: number) => { offsetNode: Node; offset: number } | null;
      caretRangeFromPoint?: (x: number, y: number) => Range | null;
    };
    const d = document as Doc;
    let range: Range | null = null;
    if (typeof d.caretPositionFromPoint === 'function') {
      const pos = d.caretPositionFromPoint(e.clientX, e.clientY);
      if (pos) {
        range = document.createRange();
        range.setStart(pos.offsetNode, pos.offset);
        range.setEnd(pos.offsetNode, pos.offset);
      }
    } else if (typeof d.caretRangeFromPoint === 'function') {
      range = d.caretRangeFromPoint(e.clientX, e.clientY);
    }
    if (!range) return;
    const containerRect = editorRef.current.getBoundingClientRect();
    const rect = range.getBoundingClientRect();
    // For an empty range at the end of a line, getBoundingClientRect can return zero
    // size - fall back to the mouse y / a sensible line-height.
    const top = (rect.top || e.clientY) - containerRect.top;
    const left = (rect.left || e.clientX) - containerRect.left;
    const height = rect.height || 21;
    setDropCaret({ top, left, height });
  }, []);

  const handleDragLeave = React.useCallback(() => setDropCaret(null), []);

  // Handle drop: insert the dragged text at the MOUSE position (not the previous
  // selection), so the dragged content lands where the user aimed.
  const handleDrop = React.useCallback((e: React.DragEvent<HTMLDivElement>) => {
    setDropCaret(null);
    if (onDrop) {
      onDrop(e as unknown as React.DragEvent<HTMLTextAreaElement>);
      return;
    }
    e.preventDefault();
    const text = e.dataTransfer.getData('text/plain');
    if (!text || !editorRef.current) return;
    const dropOffset = caretOffsetFromPoint(e.clientX, e.clientY);
    const insertAt = dropOffset != null ? dropOffset : getCursorPosition();
    const newValue = value.substring(0, insertAt) + text + value.substring(insertAt);
    onChange(newValue);
    setTimeout(() => {
      editorRef.current?.focus();
      setCursorPosition(insertAt + text.length);
    }, 0);
  }, [onDrop, value, onChange, getCursorPosition, setCursorPosition, caretOffsetFromPoint]);

  // Sync rendered content when value changes externally - only in edit mode
  React.useEffect(() => {
    if (!readOnly) {
      renderContent();
    }
  }, [value, renderContent, readOnly]);

  // Category styling
  const getCategoryStyle = (cat: SuggestionItem['category']) => {
    switch (cat) {
      case 'arithmetic': return 'bg-blue-100 dark:bg-blue-900/40 text-blue-700 dark:text-blue-300';
      case 'comparison': return 'bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300';
      case 'logical': return 'bg-rose-100 dark:bg-rose-900/40 text-rose-700 dark:text-rose-300';
      case 'ternary': return 'bg-orange-100 dark:bg-orange-900/40 text-orange-700 dark:text-orange-300';
      case 'string': return 'bg-emerald-100 dark:bg-emerald-900/40 text-emerald-700 dark:text-emerald-300';
      case 'collection': return 'bg-purple-100 dark:bg-purple-900/40 text-purple-700 dark:text-purple-300';
      case 'type': return 'bg-indigo-100 dark:bg-indigo-900/40 text-indigo-700 dark:text-indigo-300';
      case 'navigation': return 'bg-teal-100 dark:bg-teal-900/40 text-teal-700 dark:text-teal-300';
      case 'variable': return 'bg-cyan-100 dark:bg-cyan-900/40 text-cyan-700 dark:text-cyan-300';
      case 'math': return 'bg-lime-100 dark:bg-lime-900/40 text-lime-700 dark:text-lime-300';
      case 'utility': return 'bg-fuchsia-100 dark:bg-fuchsia-900/40 text-fuchsia-700 dark:text-fuchsia-300';
      case 'date': return 'bg-sky-100 dark:bg-sky-900/40 text-sky-700 dark:text-sky-300';
      default: return 'bg-gray-100 dark:bg-gray-800 text-gray-700 dark:text-gray-300';
    }
  };

  // Return type badge styling
  const getReturnTypeStyle = (type?: SuggestionItem['returnType']) => {
    switch (type) {
      case 'number': return 'bg-green-100 text-green-700 dark:bg-green-900/40 dark:text-green-300';
      case 'string': return 'bg-sky-100 text-sky-700 dark:bg-sky-900/40 dark:text-sky-300';
      case 'boolean': return 'bg-pink-100 text-pink-700 dark:bg-pink-900/40 dark:text-pink-300';
      case 'array': return 'bg-violet-100 text-violet-700 dark:bg-violet-900/40 dark:text-violet-300';
      case 'object': return 'bg-slate-100 text-slate-700 dark:bg-slate-700 dark:text-slate-300';
      case 'any': return 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400';
      default: return 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400';
    }
  };

  const isEmpty = !value || value.trim() === '';
  const showRequiredWarning = isRequired && isEmpty;

  // Resize logic - MUST be declared before any conditional return to follow React hooks rules
  const [manualHeight, setManualHeight] = React.useState<number | null>(null);

  const handleResizePointerDown = React.useCallback((e: React.PointerEvent) => {
    e.preventDefault();
    e.stopPropagation();

    const target = e.currentTarget as HTMLElement;
    target.setPointerCapture(e.pointerId);

    const startY = e.clientY;
    const startHeight = containerRef.current?.offsetHeight || 0;

    const handlePointerMove = (moveEvent: PointerEvent) => {
      const delta = moveEvent.clientY - startY;
      const newHeight = Math.max(44, startHeight + delta);
      setManualHeight(newHeight);
    };

    const handlePointerUp = (upEvent: PointerEvent) => {
      target.releasePointerCapture(upEvent.pointerId);
      target.removeEventListener('pointermove', handlePointerMove);
      target.removeEventListener('pointerup', handlePointerUp);
    };

    target.addEventListener('pointermove', handlePointerMove);
    target.addEventListener('pointerup', handlePointerUp);
  }, []);

  // Readonly mode - simple div display
  if (readOnly) {
    // Only strip <span> tags added by the expression editor (syntax highlighting)
    // Keep all other HTML tags intact (user's template HTML)
    let cleanValue = value;
    if (value && (value.includes('<span') || value.includes('</span>'))) {
      cleanValue = value
        .replace(/<span[^>]*class="[^"]*token[^"]*"[^>]*>/gi, '')
        .replace(/<\/span>/gi, '')
        .trim();
    }

    const segments = parseExpressions(cleanValue);

    const isEmptyContent = !cleanValue || cleanValue.trim() === '';

    // fullHeight readonly mode
    if (fullHeight) {
      return (
        <div className={clsx("relative h-full", className)}>
          <div className="h-full relative border rounded-xl overflow-hidden bg-[var(--bg-secondary)] border-theme">
            <div
              className="whitespace-pre-wrap break-words h-full overflow-y-auto"
              style={{
                padding: '11.5px 12px',
                fontFamily: 'inherit',
                fontSize: '14px',
                lineHeight: '21px',
                color: 'var(--text-primary)',
              }}
            >
              {segments.map((segment, idx) => (
                segment.isExpression ? (
                  <span
                    key={idx}
                    className="token-expression"
                  >
                    {segment.text}
                  </span>
                ) : (
                  <span key={idx}>{segment.text}</span>
                )
              ))}
              {isEmptyContent && (
                <span style={{ color: 'var(--text-secondary)' }}>{placeholder}</span>
              )}
            </div>
          </div>
        </div>
      );
    }

    // Standard readonly mode
    return (
      <div className={clsx("relative flex items-start gap-2", className)}>
        <div className="flex-1 relative border rounded-xl overflow-hidden bg-[var(--bg-secondary)] border-theme min-h-[44px] max-h-[200px]">
          <div
            className="whitespace-pre-wrap break-words min-h-[44px] max-h-[200px] overflow-y-auto"
            style={{
              padding: '11.5px 12px',
              fontFamily: 'inherit',
              fontSize: '14px',
              lineHeight: '21px',
              color: 'var(--text-primary)',
            }}
          >
            {segments.map((segment, idx) => (
              segment.isExpression ? (
                <span
                  key={idx}
                  className="token-expression"
                >
                  {segment.text}
                </span>
              ) : (
                <span key={idx}>{segment.text}</span>
              )
            ))}
            {isEmptyContent && (
              <span style={{ color: 'var(--text-secondary)' }}>{placeholder}</span>
            )}
          </div>
        </div>
      </div>
    );
  }

  // Popover component
  const popover = showSuggestions && filteredSuggestions.length > 0 && typeof window !== 'undefined' ? createPortal(
    <div
      className="fixed z-[99999] w-[480px] max-h-[420px] bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl shadow-2xl flex flex-col"
      style={{ top: popoverPosition.top, left: popoverPosition.left }}
      onMouseDown={(e) => e.preventDefault()}
    >
      {/* Tabs Header */}
      <div className="flex items-center gap-1 px-2 pt-2 border-b border-slate-200 dark:border-slate-700 flex-shrink-0">
        <button
          type="button"
          className={clsx(
            "px-3 py-1.5 text-sm font-medium rounded-t-lg transition-colors",
            popoverTab === 'suggestions'
              ? "bg-slate-100 dark:bg-slate-700 text-slate-900 dark:text-slate-100"
              : "text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300"
          )}
          onClick={() => setPopoverTab('suggestions')}
        >
          <span className="flex items-center gap-1.5">
            <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
            </svg>
            Functions
          </span>
        </button>
        <button
          type="button"
          className={clsx(
            "px-3 py-1.5 text-sm font-medium rounded-t-lg transition-colors",
            popoverTab === 'help'
              ? "bg-slate-100 dark:bg-slate-700 text-slate-900 dark:text-slate-100"
              : "text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300"
          )}
          onClick={() => setPopoverTab('help')}
        >
          <span className="flex items-center gap-1.5">
            <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
            Syntax Guide
          </span>
        </button>
      </div>

      {/* Suggestions Tab */}
      {popoverTab === 'suggestions' && (
        <div className="p-2 flex-1 overflow-hidden flex flex-col">
          <div className="flex-1 overflow-y-auto overflow-x-hidden">
            {filteredSuggestions.map((suggestion, index) => (
              <button
                key={`${suggestion.label}-${index}`}
                type="button"
                className={clsx(
                  "w-full text-left px-2 py-1.5 rounded-lg flex items-center gap-2 text-sm transition-all",
                  index === selectedIndex
                    ? "bg-blue-50 dark:bg-blue-900/30 ring-1 ring-inset ring-blue-300 dark:ring-blue-700"
                    : "hover:bg-slate-50 dark:hover:bg-slate-700/50"
                )}
                onClick={() => insertSuggestion(suggestion)}
                onMouseEnter={() => setSelectedIndex(index)}
              >
                <span className={clsx(
                  "text-[10px] px-1.5 py-0.5 rounded uppercase tracking-wider font-mono flex-shrink-0 min-w-[80px] text-center",
                  getCategoryStyle(suggestion.category)
                )}>
                  {suggestion.label}
                </span>
                <span className="text-slate-600 dark:text-slate-300 text-sm flex-1 truncate">
                  {suggestion.description}
                </span>
                {suggestion.returnType && (
                  <span className={clsx(
                    "text-[10px] px-1.5 py-0.5 rounded uppercase tracking-wider font-mono flex-shrink-0",
                    getReturnTypeStyle(suggestion.returnType)
                  )}>
                    {suggestion.returnType}
                  </span>
                )}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Help Tab */}
      {popoverTab === 'help' && (
        <div className="p-3 flex-1 overflow-y-auto text-sm space-y-3">
          {/* Variables */}
          <div>
            <h4 className="font-semibold text-slate-900 dark:text-slate-100 mb-1.5 flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-cyan-500"></span>
              Variables
            </h4>
            <div className="space-y-1 text-slate-600 dark:text-slate-300 pl-3">
              <p>Use <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">{'{{'}type:label.output.field{'}}'}</code> pattern</p>
              <p><code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">{'{{'}trigger:label.output.field{'}}'}</code> - Trigger outputs</p>
              <p><code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">{'{{'}mcp:label.output.field{'}}'}</code> - MCP/Tool outputs</p>
              <p><code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">{'{{'}core:split.output.current_item{'}}'}</code> - Current item (in body nodes)</p>
              <p><code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">{'{{'}core:split.output.current_index{'}}'}</code> - Current index (in body nodes)</p>
              <p><code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">{'{{'}core:split.output.items{'}}'}</code> - Full items list</p>
              <p><code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">{'{{'}core:loop.output.iteration{'}}'}</code> - Loop iteration</p>
            </div>
          </div>

          {/* Arithmetic */}
          <div>
            <h4 className="font-semibold text-slate-900 dark:text-slate-100 mb-1.5 flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-blue-500"></span>
              Arithmetic
            </h4>
            <div className="space-y-1 text-slate-600 dark:text-slate-300 pl-3">
              <p><code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">+</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">-</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">*</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">/</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">%</code> (modulo)</p>
              <p className="text-[11px] text-slate-500 dark:text-slate-400 italic">{'{{'}quantity{'}}'} * {'{{'}price{'}}'}</p>
            </div>
          </div>

          {/* Comparison */}
          <div>
            <h4 className="font-semibold text-slate-900 dark:text-slate-100 mb-1.5 flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-amber-500"></span>
              Comparison
            </h4>
            <div className="space-y-1 text-slate-600 dark:text-slate-300 pl-3">
              <p><code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">==</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">!=</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">&lt;</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">&gt;</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">&lt;=</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">&gt;=</code></p>
              <p className="text-[11px] text-slate-500 dark:text-slate-400 italic">{'{{'}score{'}}'} &gt; 90 | {'{{'}status{'}}'} == &apos;active&apos;</p>
            </div>
          </div>

          {/* Logical */}
          <div>
            <h4 className="font-semibold text-slate-900 dark:text-slate-100 mb-1.5 flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-rose-500"></span>
              Logical
            </h4>
            <div className="space-y-1 text-slate-600 dark:text-slate-300 pl-3">
              <p><code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">&&</code> (and) <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">||</code> (or) <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">!</code> (not)</p>
              <p className="text-[11px] text-slate-500 dark:text-slate-400 italic">{'{{'}x{'}}'} &gt; 0 && {'{{'}y{'}}'} &lt; 100</p>
            </div>
          </div>

          {/* Type Casting */}
          <div>
            <h4 className="font-semibold text-slate-900 dark:text-slate-100 mb-1.5 flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-indigo-500"></span>
              Type Casting
            </h4>
            <div className="space-y-1 text-slate-600 dark:text-slate-300 pl-3">
              <p><code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">int()</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">double()</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">string()</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">bool()</code></p>
              <p className="text-[11px] text-slate-500 dark:text-slate-400 italic">{'{{'}int(user_id) % 2 == 0{'}}'}</p>
            </div>
          </div>

          {/* String Functions */}
          <div>
            <h4 className="font-semibold text-slate-900 dark:text-slate-100 mb-1.5 flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-emerald-500"></span>
              String Functions
            </h4>
            <div className="space-y-1 text-slate-600 dark:text-slate-300 pl-3">
              <p><code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">uppercase()</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">lowercase()</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">trim()</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">length()</code></p>
              <p><code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">contains()</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">startswith()</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">replace()</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">substring()</code></p>
              <p className="text-[11px] text-slate-500 dark:text-slate-400 italic">{'{{'}contains(email, &apos;@company.com&apos;){'}}'}</p>
            </div>
          </div>

          {/* Math Functions */}
          <div>
            <h4 className="font-semibold text-slate-900 dark:text-slate-100 mb-1.5 flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-lime-500"></span>
              Math Functions
            </h4>
            <div className="space-y-1 text-slate-600 dark:text-slate-300 pl-3">
              <p><code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">abs()</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">round()</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">floor()</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">ceil()</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">min()</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">max()</code></p>
              <p className="text-[11px] text-slate-500 dark:text-slate-400 italic">{'{{'}round(price, 2){'}}'}</p>
            </div>
          </div>

          {/* Utility Functions */}
          <div>
            <h4 className="font-semibold text-slate-900 dark:text-slate-100 mb-1.5 flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-fuchsia-500"></span>
              Utility Functions
            </h4>
            <div className="space-y-1 text-slate-600 dark:text-slate-300 pl-3">
              <p><code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">size()</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">default()</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">coalesce()</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">isempty()</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">isnull()</code></p>
              <p className="text-[11px] text-slate-500 dark:text-slate-400 italic">{'{{'}size(items){'}}'} &gt; 0 | {'{{'}default(name, &apos;Unknown&apos;){'}}'}</p>
            </div>
          </div>

          {/* Collection Access */}
          <div>
            <h4 className="font-semibold text-slate-900 dark:text-slate-100 mb-1.5 flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-purple-500"></span>
              Collection Access
            </h4>
            <div className="space-y-1 text-slate-600 dark:text-slate-300 pl-3">
              <p><code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">[0]</code> - Array index | <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">[&apos;key&apos;]</code> - Map key</p>
              <p><code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">.?[condition]</code> - Filter | <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">.![field]</code> - Projection</p>
              <p className="text-[11px] text-slate-500 dark:text-slate-400 italic">{'{{'}items[0].name{'}}'} | {'{{'}users.?[age &gt; 18]{'}}'}</p>
            </div>
          </div>

          {/* Ternary */}
          <div>
            <h4 className="font-semibold text-slate-900 dark:text-slate-100 mb-1.5 flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-orange-500"></span>
              Ternary Operator
            </h4>
            <div className="space-y-1 text-slate-600 dark:text-slate-300 pl-3">
              <p><code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">condition ? valueIfTrue : valueIfFalse</code></p>
              <p className="text-[11px] text-slate-500 dark:text-slate-400 italic">{'{{'}score &gt; 50 ? &apos;pass&apos; : &apos;fail&apos;{'}}'}</p>
            </div>
          </div>

          {/* Date Functions */}
          <div>
            <h4 className="font-semibold text-slate-900 dark:text-slate-100 mb-1.5 flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-sky-500"></span>
              Date/Number Formatting
            </h4>
            <div className="space-y-1 text-slate-600 dark:text-slate-300 pl-3">
              <p><code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">formatdate(value, &apos;yyyy-MM-dd&apos;)</code></p>
              <p><code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">formatnumber(value, 2)</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">formatcurrency(value, &apos;EUR&apos;)</code></p>
              <p><code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">now()</code> <code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">today()</code></p>
            </div>
          </div>

          {/* JSON Functions */}
          <div>
            <h4 className="font-semibold text-slate-900 dark:text-slate-100 mb-1.5 flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-amber-500"></span>
              JSON
            </h4>
            <div className="space-y-1 text-slate-600 dark:text-slate-300 pl-3">
              <p><code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">json(value)</code> - Parse JSON string → typed Map/List (idempotent on objects)</p>
              <p><code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">fromjson(value)</code> - Alias for json() - GitHub Actions parity</p>
              <p><code className="bg-slate-100 dark:bg-slate-700 px-1 rounded">tojson(value)</code> - Serialize Map/List → compact JSON string</p>
              <p className="text-[11px] text-slate-500 dark:text-slate-400 italic">{'{{'}json(mcp:fetch.output.body){'}}'} - typed Map for an object-typed param</p>
            </div>
          </div>
        </div>
      )}

      {/* Footer */}
      <div className="text-[10px] text-slate-500 dark:text-slate-400 px-3 py-2 border-t border-slate-200 dark:border-slate-700 flex justify-between bg-slate-50 dark:bg-slate-900/50 rounded-b-xl flex-shrink-0">
        <span className="flex items-center gap-1">
          <kbd className="px-1 py-0.5 bg-slate-200 dark:bg-slate-700 rounded text-[9px]">↑</kbd>
          <kbd className="px-1 py-0.5 bg-slate-200 dark:bg-slate-700 rounded text-[9px]">↓</kbd>
          Navigate
        </span>
        <span className="flex items-center gap-1">
          <kbd className="px-1.5 py-0.5 bg-slate-200 dark:bg-slate-700 rounded text-[9px]">Tab</kbd>
          Select
        </span>
        <span className="flex items-center gap-1">
          <kbd className="px-1 py-0.5 bg-slate-200 dark:bg-slate-700 rounded text-[9px]">Esc</kbd>
          Close
        </span>
      </div>
    </div>,
    document.body
  ) : null;

  // Shared styles for the editor - no overflow here, handled by container
  const editorStyles: React.CSSProperties = {
    padding: '11.5px 12px',
    fontFamily: 'inherit',
    fontSize: '14px',
    lineHeight: '21px',
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-word',
    overflowWrap: 'break-word',
    outline: 'none',
    color: 'var(--text-primary)',
    cursor: 'text',
  };

  // For fullHeight mode - take full height of parent and scroll
  if (fullHeight) {
    return (
      <div ref={containerRef} className={clsx("relative h-full", className)}>
        <div className={clsx(
          "h-full relative border rounded-xl bg-[var(--bg-secondary)] focus-within:ring-2 focus-within:ring-[var(--accent-primary)] focus-within:ring-offset-2 focus-within:ring-offset-[var(--bg-primary)] overflow-hidden",
          showRequiredWarning ? "border-red-400 dark:border-red-500" : "border-theme"
        )}>
          {/* Grid container to layer editor and ghost placeholder for proper sizing */}
          <div className="grid h-full">
            {/* Ghost placeholder - invisible but takes up space for sizing when empty */}
            {isEmpty && (
              <div
                aria-hidden="true"
                className="pointer-events-none invisible whitespace-pre-wrap break-words col-start-1 row-start-1"
                style={{
                  padding: '11.5px 12px',
                  fontSize: '14px',
                  lineHeight: '21px',
                }}
              >
                {placeholder}
              </div>
            )}
            {/* Editor content - overlays the ghost */}
            <div
              ref={editorRef}
              contentEditable
              suppressContentEditableWarning
              className="w-full h-full overflow-y-auto col-start-1 row-start-1"
              style={editorStyles}
              onInput={handleInput}
              onKeyDown={handleKeyDown}
              onClick={handleClick}
              onFocus={handleFocus}
              onBlur={handleBlur}
              onPaste={handlePaste}
              onDrop={handleDrop}
              onDragOver={handleDragOver}
              onDragLeave={handleDragLeave}
              data-placeholder={placeholder}
            />
            {/* Drop-target caret indicator: shows where dragged content will be inserted. */}
            {dropCaret && (
              <div
                aria-hidden="true"
                className="pointer-events-none absolute bg-[var(--accent-primary)] animate-pulse col-start-1 row-start-1"
                style={{
                  top: dropCaret.top,
                  left: dropCaret.left,
                  width: 2,
                  height: dropCaret.height,
                  zIndex: 10,
                }}
              />
            )}
          </div>
          {/* Placeholder - visible overlay */}
          {isEmpty && !isFocused && (
            <div
              className="absolute top-0 left-0 pointer-events-none text-[var(--text-secondary)]"
              style={{
                padding: '11.5px 12px',
                fontSize: '14px',
                lineHeight: '21px',
              }}
            >
              {placeholder}
            </div>
          )}
        </div>
        {popover}
      </div>
    );
  }

  // Standard mode - auto height with max
  return (
    <div
      ref={containerRef}
      className={clsx("relative flex items-start gap-2", className)}
      style={{
        height: manualHeight ? `${manualHeight}px` : undefined,
      }}
    >
      <div
        className={clsx(
          "flex-1 relative border rounded-xl bg-[var(--bg-secondary)] focus-within:ring-2 focus-within:ring-[var(--accent-primary)] focus-within:ring-offset-2 focus-within:ring-offset-[var(--bg-primary)] overflow-hidden",
          showRequiredWarning ? "border-red-400 dark:border-red-500" : "border-theme",
          // Only apply max-height class if no manual height
          !manualHeight && "max-h-[200px]"
        )}
        style={{
          height: manualHeight ? '100%' : undefined,
          maxHeight: manualHeight ? 'none' : undefined
        }}
      >
        {/* Grid container to layer editor and ghost placeholder for proper sizing */}
        <div className="grid" style={{ maxHeight: manualHeight ? 'none' : '200px' }}>
          {/* Ghost placeholder - invisible but takes up space for sizing when empty */}
          {isEmpty && (
            <div
              aria-hidden="true"
              className="pointer-events-none invisible whitespace-pre-wrap break-words col-start-1 row-start-1"
              style={{
                padding: '11.5px 12px',
                fontSize: '14px',
                lineHeight: '21px',
              }}
            >
              {placeholder}
            </div>
          )}
          {/* Editor content - overlays the ghost */}
          <div
            ref={editorRef}
            contentEditable
            suppressContentEditableWarning
            className={clsx(
              "w-full overflow-y-auto col-start-1 row-start-1",
              !manualHeight && "max-h-[200px]"
            )}
            style={{
              ...editorStyles,
              height: manualHeight ? '100%' : undefined,
              maxHeight: manualHeight ? 'none' : undefined
            }}
            onInput={handleInput}
            onKeyDown={handleKeyDown}
            onClick={handleClick}
            onFocus={handleFocus}
            onBlur={handleBlur}
            onPaste={handlePaste}
            onDrop={handleDrop}
            onDragOver={handleDragOver}
            onDragLeave={handleDragLeave}
            data-placeholder={placeholder}
          />
          {/* Drop-target caret indicator: shows where dragged content will be inserted. */}
          {dropCaret && (
            <div
              aria-hidden="true"
              className="pointer-events-none absolute bg-[var(--accent-primary)] animate-pulse col-start-1 row-start-1"
              style={{
                top: dropCaret.top,
                left: dropCaret.left,
                width: 2,
                height: dropCaret.height,
                zIndex: 10,
              }}
            />
          )}
        </div>
        {/* Placeholder - visible overlay */}
        {isEmpty && !isFocused && (
          <div
            className="absolute top-0 left-0 pointer-events-none text-[var(--text-secondary)]"
            style={{
              padding: '11.5px 12px',
              fontSize: '14px',
              lineHeight: '21px',
            }}
          >
            {placeholder}
          </div>
        )}

        {/* Resize Handle */}
        {!readOnly && (
          <div
            className="absolute bottom-0 right-0 w-5 h-5 cursor-ns-resize flex items-end justify-end pr-1 pb-1 opacity-40 hover:opacity-100 z-10 touch-none transition-opacity"
            onPointerDown={handleResizePointerDown}
          >
            <svg width="8" height="8" viewBox="0 0 8 8" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M8 8H0L8 0V8Z" fill="currentColor" className="text-slate-400 dark:text-slate-500" />
            </svg>
          </div>
        )}
      </div>
      {popover}
    </div>
  );
}
