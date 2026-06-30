"use client";

import React, { useState, useMemo } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeSanitize from "rehype-sanitize";
import { Prism as SyntaxHighlighter } from "react-syntax-highlighter";
import { oneDark, oneLight } from "react-syntax-highlighter/dist/esm/styles/prism";
import { useThemeSafely } from "../hooks/useThemeSafely";
import { Copy, Check } from "lucide-react";
import { VisualizeBlock } from "./chat/VisualizeBlock";
import { ResourceCarousel, type CarouselItem } from "./chat/ResourceCarousel";

type Props = {
  text: string;
  allowSafeHtml?: boolean;
  isStreaming?: boolean; // Show spinner on last tool_call when streaming
  onDeleteVisualization?: (type: 'workflow' | 'application' | 'agent' | 'datasource' | 'interface' | 'web_search' | 'agent_browse' | 'image_generation', id: string) => void;
  onRunWorkflow?: (workflowId: string) => void;
  /** Read-only mode: renders static resource cards without API calls (public share pages) */
  readOnly?: boolean;
};

// Component for the copy button
function CopyButton({ code, className }: { code: string; className?: string }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (err) {
      console.error('Error during copy:', err);
    }
  };

  return (
    <button
      onClick={handleCopy}
      className={`copy-button absolute top-2 right-2 p-1 rounded-full text-theme-muted hover:text-theme-primary hover:bg-theme-tertiary/50 transition-all duration-200 opacity-0 group-hover:opacity-100 ${className}`}
      title={copied ? "Copied" : "Copy code"}
    >
      {copied ? <Check className="w-3.5 h-3.5" /> : <Copy className="w-3.5 h-3.5" />}
    </button>
  );
}

// Helper functions outside component to avoid recreation
const hasMarkdownTable = (content: string): boolean => {
  if (!content || typeof content !== 'string') return false;

  const lines = content.split('\n');
  let hasHeaderRow = false;
  let hasSeparatorRow = false;
  let hasDataRow = false;

  for (let i = 0; i < lines.length; i++) {
    const trimmed = lines[i].trim();
    if (!trimmed.includes('|')) continue;

    // Check for separator row: |---|---| or | --- | --- |
    if (/^\|?[\s]*[-:]+[\s]*\|/.test(trimmed) && /[-:]{3,}/.test(trimmed)) {
      hasSeparatorRow = true;
      // If we found separator, previous line should be header
      if (i > 0 && lines[i - 1].trim().includes('|')) {
        hasHeaderRow = true;
      }
      continue;
    }

    // Check for data row (has | and content)
    if (trimmed.startsWith('|') || trimmed.endsWith('|')) {
      if (hasSeparatorRow) {
        hasDataRow = true;
      }
    }
  }

  return hasHeaderRow && hasSeparatorRow;
};

const convertAsciiTableToMarkdown = (content: string): string => {
  if (!content || typeof content !== 'string') return content;

  const lines = content.split('\n');
  const result: string[] = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];
    const trimmed = line.trim();

    // Detect ASCII table start: +----+----+
    if (/^\+[-+]+\+$/.test(trimmed)) {
      const tableBlock: string[] = [];
      let j = i;

      // Collect table block
      while (j < lines.length) {
        const currentLine = lines[j].trim();
        if (/^\+[-+]+\+$/.test(currentLine) || /^\|.*\|$/.test(currentLine)) {
          tableBlock.push(lines[j]);
          j++;
        } else {
          break;
        }
      }

      if (tableBlock.length >= 3) {
        const markdownTable = convertAsciiBlockToMarkdown(tableBlock);
        if (markdownTable) {
          result.push(markdownTable);
          i = j;
          continue;
        }
      }
    }

    result.push(line);
    i++;
  }

  return result.join('\n');
};

const convertAsciiBlockToMarkdown = (tableBlock: string[]): string | null => {
  const dataLines: string[] = [];

  for (const line of tableBlock) {
    const trimmed = line.trim();
    // Skip separator lines (+----+)
    if (/^\+[-+]+\+$/.test(trimmed)) continue;

    // Process data lines (|...|)
    if (/^\|.*\|$/.test(trimmed)) {
      const cells = trimmed
        .slice(1, -1) // Remove leading and trailing |
        .split('|')
        .map(cell => cell.trim());
      dataLines.push(`| ${cells.join(' | ')} |`);
    }
  }

  if (dataLines.length < 1) return null;

  // Add separator after header
  const columnCount = (dataLines[0].match(/\|/g) || []).length - 1;
  const separator = '|' + ' --- |'.repeat(columnCount);
  dataLines.splice(1, 0, separator);

  return dataLines.join('\n');
};

// Process tables in code blocks marked as markdown
const processMarkdownCodeBlocks = (content: string): string => {
  if (!content) return content;

  return content.replace(/```(?:markdown|md)?\n([\s\S]*?)\n```/g, (match, code) => {
    if (hasMarkdownTable(code)) {
      return `\n\n${code}\n\n`;
    }
    return match;
  });
};

// Format a tool/skill name from its raw ID to a human-readable label
const TOOL_DISPLAY_NAMES: Record<string, string> = {
  datasource: 'Table',
  table: 'Table',
  interface: 'Interface',
  workflow: 'Workflow',
  catalog: 'Catalog',
  agent: 'Agent',
  skill: 'Skill',
  application: 'Application',
  web_search: 'Web Search',
};

const formatToolCallName = (name?: string): string => {
  if (!name) return 'tool';
  const lower = name.toLowerCase();
  if (TOOL_DISPLAY_NAMES[lower]) return TOOL_DISPLAY_NAMES[lower];
  // Fallback: replace underscores/hyphens, title-case words
  return name.replace(/[-_]/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
};

// Check if text contains tool call markers
const hasToolCallMarker = (text: string): boolean => {
  return /\[tool_call:[^\]]+\]/.test(text);
};

/**
 * Visualize types recognised by the chat renderer. Single source of
 * truth for the three regex sites below - adding a new type here
 * (e.g. {@code 'image_generation'}) automatically wires it into all
 * detection / parsing / splitting paths. The corresponding card
 * component must also be added to {@code VisualizeBlock.tsx} for
 * the UI to render it.
 *
 * <p>{@code workflow_run} is recognised in {@link splitByMarkers} but
 * not by the higher-level {@link parseVisualizeMarker} helper - kept in
 * the splitter for back-compat with older messages.
 */
export const VISUALIZE_TYPES = [
  'workflow',
  'datasource',
  'interface',
  'application',
  'agent',
  'web_search',
  'agent_browse',
  'slide',
  'image_generation',
  'file',
] as const;

export type VisualizeType = (typeof VISUALIZE_TYPES)[number];

const VISUALIZE_TYPE_ALTERNATION = VISUALIZE_TYPES.join('|');

// Check if text contains visualize markers
const hasVisualizeMarker = (text: string): boolean => {
  return new RegExp(`\\[visualize:(${VISUALIZE_TYPE_ALTERNATION}):[^\\]]+\\]`).test(text);
};

// Parse visualize marker: [visualize:type:id:title] or [visualize:type:id]
const parseVisualizeMarker = (marker: string): { type: VisualizeType; id: string; title?: string } | null => {
  const match = marker.match(new RegExp(`\\[visualize:(${VISUALIZE_TYPE_ALTERNATION}):([^:\\]]+)(?::([^\\]]+))?\\]`));
  if (!match) return null;
  return {
    type: match[1] as VisualizeType,
    id: match[2],
    title: match[3],
  };
};

// Segment types
export type Segment =
  | { type: 'text'; content: string }
  | { type: 'tool_call'; content: string; toolName?: string }
  | { type: 'visualize'; vizType: VisualizeType; id: string; title?: string; runId?: string };

// UUID-shaped id guard. For visualize types whose id is a real UUID, a
// malformed id (placeholder like `<appId>`, prose, truncated UUID) must NOT
// render a 404 "not found" card - it degrades to plain text. Types whose id
// is not a UUID are exempt: datasource / file use numeric / opaque (storage)
// ids, and web_search renders nothing. agent_browse / image_generation are
// included because VisualizeBlock addresses them by a UUID interfaceId, exactly
// like `interface` - a malformed id there 404s the same way.
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const UUID_VIZ_TYPES = new Set<string>([
  'workflow', 'interface', 'application', 'agent', 'agent_browse', 'image_generation',
]);

/**
 * Replace fenced (``` / ~~~) and inline (`…`) code regions with equal-length
 * blanks so marker scanning ignores any [visualize:…] / [tool_call:…] written
 * as documentation inside code. Length is preserved char-for-char (only
 * non-newline chars are blanked) so match offsets map back onto the ORIGINAL
 * text unchanged - we always slice/parse from the original, never the mask.
 */
const maskCodeRegions = (text: string): string => {
  const blank = (m: string) => m.replace(/[^\n]/g, ' ');
  return text
    .replace(/```[\s\S]*?```/g, blank)
    .replace(/~~~[\s\S]*?~~~/g, blank)
    .replace(/`[^`\n]*`/g, blank);
};

// Split text into segments (normal text, tool calls, and visualizations)
// Exported for unit testing (code-region masking, UUID guard, 4-field runId
// capture, dedup). Not part of the public render API.
export const splitByMarkers = (text: string): Segment[] => {
  const segments: Segment[] = [];
  const seenVisualizations = new Set<string>();
  // Scan a code-masked copy so markers inside fenced/inline code are never
  // promoted to cards (they are explanatory examples, not real tool output).
  // The mask preserves length, so scan offsets address the ORIGINAL text.
  const scanText = maskCodeRegions(text);
  // Combined regex - workflow_run kept in alternation for legacy markers
  // (it's recognised here but not in parseVisualizeMarker / hasVisualizeMarker).
  const regex = new RegExp(
    `\\[tool_call:([^\\]]+)\\]|\\[visualize:(workflow_run|${VISUALIZE_TYPE_ALTERNATION}):([^:\\]]+)(?::([^\\]]+))?\\]`,
    'g',
  );
  // Single-marker re-parser (anchored) used to recover capture groups from the
  // original slice, since the scanned groups are blanks inside masked regions.
  const single = new RegExp(
    `^\\[tool_call:([^\\]]+)\\]$|^\\[visualize:(workflow_run|${VISUALIZE_TYPE_ALTERNATION}):([^:\\]]+)(?::([^\\]]+))?\\]$`,
  );
  let lastIndex = 0;
  let scanMatch;

  while ((scanMatch = regex.exec(scanText)) !== null) {
    const start = scanMatch.index;
    const end = regex.lastIndex;
    const realMarker = text.slice(start, end);
    const match = realMarker.match(single);

    // Add text before the marker
    if (start > lastIndex) {
      const textContent = text.slice(lastIndex, start).trim();
      if (textContent) {
        segments.push({ type: 'text', content: textContent });
      }
    }

    // Defensive: original slice failed to re-parse - keep it as text.
    if (!match) {
      segments.push({ type: 'text', content: realMarker });
      lastIndex = end;
      continue;
    }

    // Determine which type of marker was matched
    if (match[1]) {
      // tool_call marker
      segments.push({ type: 'tool_call', content: match[0], toolName: match[1] });
    } else if (match[2]) {
      // UUID guard - a malformed id for a UUID-based type degrades to text
      // instead of rendering a broken "not found" card. workflow_run keeps
      // its legacy behaviour (id is a workflowId, still a UUID, so guarded).
      if (UUID_VIZ_TYPES.has(match[2] === 'workflow_run' ? 'workflow' : match[2]) && !UUID_RE.test(match[3])) {
        segments.push({ type: 'text', content: realMarker });
        lastIndex = end;
        continue;
      }
      // visualize marker - deduplicate by type+id+runId so multiple distinct
      // executions of the same application emit distinct cards (each carries
      // its own runId in the 4-field shape).
      const vizKey = `${match[2]}:${match[3]}:${match[4] ?? ''}`;
      if (!seenVisualizations.has(vizKey)) {
        seenVisualizations.add(vizKey);
        if (match[2] === 'workflow_run') {
          // [visualize:workflow_run:workflowId:runId] → render as workflow with runId
          segments.push({
            type: 'visualize',
            vizType: 'workflow',
            id: match[3],
            runId: match[4],
          });
        } else if (match[2] === 'application') {
          // [visualize:application:id(:runId)?] - for application markers the
          // optional 4th field is interpreted as runId. ApplicationExecuteModule
          // (execute) and ApplicationCrudModule.executeCreate (create) emit the
          // 4-field form to pin the chat-history preview to that run; acquire /
          // visualize emit the 3-field form (runId undefined).
          segments.push({
            type: 'visualize',
            vizType: 'application',
            id: match[3],
            runId: match[4],
          });
        } else {
          segments.push({
            type: 'visualize',
            vizType: match[2] as VisualizeType,
            id: match[3],
            title: match[4]
          });
        }
      }
    }

    lastIndex = end;
  }

  // Add remaining text after last marker
  if (lastIndex < text.length) {
    const textContent = text.slice(lastIndex).trim();
    if (textContent) {
      segments.push({ type: 'text', content: textContent });
    }
  }

  return segments;
};

// Legacy function for backwards compatibility
const splitByToolCalls = (text: string): Array<{ type: 'text' | 'tool_call'; content: string; toolName?: string }> => {
  const segments: Array<{ type: 'text' | 'tool_call'; content: string; toolName?: string }> = [];
  const regex = /\[tool_call:([^\]]+)\]/g;
  let lastIndex = 0;
  let match;

  while ((match = regex.exec(text)) !== null) {
    // Add text before the tool call
    if (match.index > lastIndex) {
      const textContent = text.slice(lastIndex, match.index).trim();
      if (textContent) {
        segments.push({ type: 'text', content: textContent });
      }
    }
    // Add the tool call
    segments.push({ type: 'tool_call', content: match[0], toolName: match[1] });
    lastIndex = regex.lastIndex;
  }

  // Add remaining text after last tool call
  if (lastIndex < text.length) {
    const textContent = text.slice(lastIndex).trim();
    if (textContent) {
      segments.push({ type: 'text', content: textContent });
    }
  }

  return segments;
};

// Group consecutive visualize segments into runs for carousel rendering
type RenderItem =
  | { kind: 'segment'; segment: Segment }
  | { kind: 'vizGroup'; items: Extract<Segment, { type: 'visualize' }>[] };

function groupConsecutiveViz(segments: Segment[]): RenderItem[] {
  const result: RenderItem[] = [];
  let vizBuffer: Extract<Segment, { type: 'visualize' }>[] = [];

  for (const seg of segments) {
    if (seg.type === 'visualize') {
      vizBuffer.push(seg);
    } else {
      if (vizBuffer.length > 0) {
        result.push({ kind: 'vizGroup', items: [...vizBuffer] });
        vizBuffer = [];
      }
      result.push({ kind: 'segment', segment: seg });
    }
  }
  if (vizBuffer.length > 0) {
    result.push({ kind: 'vizGroup', items: vizBuffer });
  }
  return result;
}

export default function MarkdownRender({ text, allowSafeHtml = false, isStreaming = false, onDeleteVisualization, onRunWorkflow, readOnly }: Props) {
  const { theme } = useThemeSafely();

  // Process text with memoization
  const processedText = useMemo(() => {
    if (!text || typeof text !== 'string') return '';

    let result = text;

    // Convert ASCII tables to Markdown
    result = convertAsciiTableToMarkdown(result);

    // Process markdown code blocks containing tables
    result = processMarkdownCodeBlocks(result);

    return result;
  }, [text]);

  // Memoize components to prevent recreation on each render
  const components = useMemo(() => ({
    code({ node, className, children, ...props }: any) {
      const match = /language-(\w+)/.exec(className || "");
      const language = match?.[1] || "plaintext";
      const codeString = String(children).replace(/\n$/, "");

      // Inline code (no language match and short content)
      if (!match) {
        return (
          <code className="font-mono text-[0.9em] text-theme-muted" {...props}>
            {children}
          </code>
        );
      }

      // Code block with syntax highlighting
      const safeLanguage = /^[a-zA-Z0-9_-]+$/.test(language) ? language : 'plaintext';

      return (
        <div className="relative group my-2 bg-theme-secondary rounded-xl overflow-hidden">
          <SyntaxHighlighter
            language={safeLanguage}
            style={theme === 'dark' ? oneDark : oneLight}
            PreTag="div"
            customStyle={{
              margin: 0,
              padding: '0.75rem',
              background: 'transparent',
              fontSize: '0.875rem',
              lineHeight: '1.5',
              borderRadius: '0.75rem',
            }}
            wrapLines={true}
            wrapLongLines={true}
          >
            {codeString}
          </SyntaxHighlighter>
          <CopyButton code={codeString} />
        </div>
      );
    },

    // Links
    a({ href, children, ...props }: any) {
      return (
        <a
          href={href || '#'}
          target="_blank"
          rel="noopener noreferrer"
          className="text-theme-accent hover:text-theme-accent/80 hover:underline transition-colors duration-200"
          {...props}
        >
          {children}
        </a>
      );
    },

    // Headings
    h1: ({ children, ...props }: any) => (
      <h1 className="text-2xl font-bold mb-4 mt-6 text-theme-primary" {...props}>{children}</h1>
    ),
    h2: ({ children, ...props }: any) => (
      <h2 className="text-xl font-semibold mb-3 mt-5 text-theme-primary" {...props}>{children}</h2>
    ),
    h3: ({ children, ...props }: any) => (
      <h3 className="text-lg font-medium mb-2 mt-4 text-theme-primary" {...props}>{children}</h3>
    ),
    h4: ({ children, ...props }: any) => (
      <h4 className="text-base font-medium mb-2 mt-3 text-theme-primary" {...props}>{children}</h4>
    ),

    // Paragraphs
    p: ({ children, ...props }: any) => (
      <p className="text-theme-primary leading-relaxed" {...props}>{children}</p>
    ),

    // Lists
    ul: ({ children, ...props }: any) => (
      <ul className="list-disc pl-6 mb-4 space-y-1 text-theme-primary" {...props}>{children}</ul>
    ),
    ol: ({ children, ...props }: any) => (
      <ol className="list-decimal pl-6 mb-4 space-y-1 text-theme-primary" {...props}>{children}</ol>
    ),
    li: ({ children, ...props }: any) => (
      <li className="text-theme-primary" {...props}>{children}</li>
    ),

    // Blockquote
    blockquote: ({ children, ...props }: any) => (
      <blockquote className="border-l-4 border-theme-accent pl-4 italic text-theme-muted my-4" {...props}>
        {children}
      </blockquote>
    ),

    // Horizontal rule
    hr: ({ ...props }: any) => (
      <hr className="my-6 border-theme" {...props} />
    ),

    // Strong and emphasis
    strong: ({ children, ...props }: any) => (
      <strong className="font-semibold text-theme-primary" {...props}>{children}</strong>
    ),
    em: ({ children, ...props }: any) => (
      <em className="italic" {...props}>{children}</em>
    ),

    // Tables
    table: ({ children, ...props }: any) => (
      <div className="overflow-x-auto my-3 rounded-xl overflow-hidden border border-theme">
        <table className="min-w-full" style={{ borderSpacing: '0' }} {...props}>
          {children}
        </table>
      </div>
    ),
    thead: ({ children, ...props }: any) => (
      <thead className="bg-theme-secondary border-b border-theme" {...props}>{children}</thead>
    ),
    tbody: ({ children, ...props }: any) => (
      <tbody {...props}>{children}</tbody>
    ),
    tr: ({ children, ...props }: any) => (
      <tr className="hover:bg-theme-secondary/50 transition-colors duration-150" {...props}>{children}</tr>
    ),
    th: ({ children, ...props }: any) => (
      <th className="px-4 py-2.5 font-medium text-left text-theme-primary text-sm" {...props}>{children}</th>
    ),
    td: ({ children, ...props }: any) => (
      <td className="px-4 py-2 text-theme-primary text-sm" {...props}>{children}</td>
    ),

    // Images
    img: ({ src, alt, ...props }: any) => (
      <img
        src={src}
        alt={alt || ''}
        className="max-w-full h-auto rounded-lg my-4"
        loading="lazy"
        {...props}
      />
    ),

    // Task lists (checkboxes)
    input: ({ type, checked, ...props }: any) => {
      if (type === 'checkbox') {
        return (
          <input
            type="checkbox"
            checked={checked}
            readOnly
            className="mr-2 rounded border-theme-secondary"
            {...props}
          />
        );
      }
      return <input type={type} {...props} />;
    },
  }), [theme]);

  if (!processedText) {
    return null;
  }

  // Check for markers (tool calls or visualizations)
  const hasToolCall = hasToolCallMarker(processedText);
  const hasVisualize = hasVisualizeMarker(processedText);

  // If we have any markers, use the unified segment parser
  if (hasToolCall || hasVisualize) {
    const segments = splitByMarkers(processedText);

    // Fallback if splitting fails
    if (segments.length === 0) {
      return (
        <div className="prose prose-sm max-w-none dark:prose-invert">
          <ReactMarkdown
            remarkPlugins={[remarkGfm]}
            rehypePlugins={allowSafeHtml ? [rehypeSanitize] : []}
            components={components}
          >
            {processedText.replace(/\[tool_call:[^\]]+\]/g, '').replace(/\[visualize:[^\]]+\]/g, '')}
          </ReactMarkdown>
        </div>
      );
    }

    // Group consecutive visualize segments for carousel rendering
    const renderItems = groupConsecutiveViz(segments);

    return (
      <div className="prose prose-sm max-w-none dark:prose-invert">
        {renderItems.map((item, index) => {
          if (item.kind === 'vizGroup') {
            // VisualizeBlock doesn't have a 'slide' case (handled by a
            // separate slide preview elsewhere). Skip those before
            // narrowing to the VisualizeBlock-compatible union.
            type RenderableVizType = Exclude<VisualizeType, 'slide'>;
            const isRenderable = (v: typeof item.items[number]): v is typeof v & { vizType: RenderableVizType } =>
              v.vizType !== 'slide';
            const renderableItems = item.items.filter(isRenderable);
            if (renderableItems.length === 0) {
              return null;
            }
            // Single viz: render directly (no carousel chrome)
            if (renderableItems.length === 1) {
              const viz = renderableItems[0];
              return (
                <div key={index} className="not-prose">
                  <VisualizeBlock
                    type={viz.vizType}
                    id={viz.id}
                    title={viz.title}
                    runId={viz.runId}
                    onDelete={onDeleteVisualization}
                    onRunWorkflow={onRunWorkflow}
                    readOnly={readOnly}
                  />
                </div>
              );
            }
            // Multiple consecutive viz: carousel
            const carouselItems: CarouselItem[] = renderableItems.map(v => ({
              vizType: v.vizType,
              id: v.id,
              title: v.title,
              // Carry runId so grouped application cards render the live
              // execution run (newest epoch) instead of the frozen showcase.
              runId: v.runId,
            }));
            return (
              <ResourceCarousel
                key={index}
                items={carouselItems}
                onDeleteVisualization={onDeleteVisualization}
                onRunWorkflow={onRunWorkflow}
                readOnly={readOnly}
              />
            );
          }

          const segment = item.segment;

          if (segment.type === 'tool_call') {
            // Check if this is the last tool_call and we're still streaming
            const isLastToolCall = segments.filter(s => s.type === 'tool_call').pop() === segment;
            const isExecuting = isStreaming && isLastToolCall;

            // Render tool call in ChatGPT-style: muted, italic, minimal
            // With pulse animation when executing
            return (
              <div
                key={index}
                className={`text-theme-muted text-sm italic py-1 ${isExecuting ? 'animate-pulse' : ''}`}
              >
                <span className={isExecuting ? 'opacity-90' : 'opacity-70'}>
                  {isExecuting ? 'Using' : 'Used'} {formatToolCallName(segment.toolName)}
                  {isExecuting && '...'}
                </span>
              </div>
            );
          }

          // Render normal text with markdown
          if (segment.type === 'text') {
            return (
              <ReactMarkdown
                key={index}
                remarkPlugins={[remarkGfm]}
                rehypePlugins={allowSafeHtml ? [rehypeSanitize] : []}
                components={components}
              >
                {segment.content}
              </ReactMarkdown>
            );
          }

          return null;
        })}
      </div>
    );
  }

  return (
    <div className="prose prose-sm max-w-none dark:prose-invert">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={allowSafeHtml ? [rehypeSanitize] : []}
        components={components}
      >
        {processedText}
      </ReactMarkdown>
    </div>
  );
}
