import React, { useState, useRef } from 'react';
import { useTranslations } from 'next-intl';
import { renderMarkdownPreview } from '@/lib/utils/renderMarkdownPreview';
import {
  Bold,
  Italic,
  List,
  ListOrdered,
  Quote,
  Code,
  Link,
  Smile,
  Undo,
  Redo,
  Eye,
  Edit3
} from 'lucide-react';

interface RichTextareaProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  rows?: number;
  disabled?: boolean;
  className?: string;
  forcePreview?: boolean; // New prop to force preview
  resizable?: boolean; // New prop to allow resizing
}

const RichTextarea: React.FC<RichTextareaProps> = ({
  value,
  onChange,
  placeholder,
  rows = 4,
  disabled = false,
  className = '',
  forcePreview = false,
  resizable = true
}) => {
  const t = useTranslations('developers');
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const [showEmojiPicker, setShowEmojiPicker] = useState(false);
  const [viewMode, setViewMode] = useState<'edit' | 'preview'>(forcePreview ? 'preview' : 'edit');
  
  // Force edit mode when component mounts (if not forcePreview and not disabled)
  React.useEffect(() => {
    if (!forcePreview && !disabled) {
      setViewMode('edit');
    }
  }, [forcePreview, disabled]);
  
  // History for undo/redo
  const [history, setHistory] = useState<string[]>([]);
  const [historyIndex, setHistoryIndex] = useState(-1);

  // Initialize history with initial value
  React.useEffect(() => {
    if (history.length === 0 && value) {
      setHistory([value]);
      setHistoryIndex(0);
    }
  }, []);

  // Add a value to history
  const addToHistory = (newValue: string) => {
    const newHistory = history.slice(0, historyIndex + 1);
    newHistory.push(newValue);
    
    // Limit history to 50 entries
    if (newHistory.length > 50) {
      newHistory.shift();
    }
    
    setHistory(newHistory);
    setHistoryIndex(newHistory.length - 1);
  };

  // Function to insert text at cursor position
  const insertAtCursor = (textToInsert: string) => {
    if (!textareaRef.current) return;
    
    const textarea = textareaRef.current;
    const start = textarea.selectionStart;
    const end = textarea.selectionEnd;
    const text = value;
    
    const before = text.substring(0, start);
    const after = text.substring(end);
    const newText = before + textToInsert + after;
    
    onChange(newText);
    addToHistory(newText);
    
    // Restore focus and cursor position
    setTimeout(() => {
      textarea.focus();
      textarea.setSelectionRange(start + textToInsert.length, start + textToInsert.length);
    }, 0);
  };

  // Function to wrap selection with characters
  const wrapSelection = (before: string, after: string) => {
    if (!textareaRef.current) return;
    
    const textarea = textareaRef.current;
    const start = textarea.selectionStart;
    const end = textarea.selectionEnd;
    const selectedText = value.substring(start, end);
    
    if (start === end) {
      // No selection, insert characters and place cursor between
      insertAtCursor(before + after);
      setTimeout(() => {
        textarea.setSelectionRange(start + before.length, start + before.length);
      }, 0);
    } else {
      // Wrap selection
      const beforeText = value.substring(0, start);
      const afterText = value.substring(end);
      const newText = beforeText + before + selectedText + after + afterText;
      
      onChange(newText);
      addToHistory(newText);
      
      // Restore selection
      setTimeout(() => {
        textarea.focus();
        textarea.setSelectionRange(start, end + before.length + after.length);
      }, 0);
    }
  };

  // Formatting functions
  const formatBold = () => wrapSelection('**', '**');
  const formatItalic = () => wrapSelection('*', '*');
  const formatCode = () => wrapSelection('`', '`');
  const formatQuote = () => {
    const lines = value.split('\n');
    const start = textareaRef.current?.selectionStart || 0;
    const end = textareaRef.current?.selectionEnd || 0;
    
    // Find selected lines
    let currentPos = 0;
    const selectedLines: number[] = [];
    
    lines.forEach((line, index) => {
      const lineLength = line.length + 1; // +1 for line break
      if (currentPos <= start && start <= currentPos + lineLength) {
        selectedLines.push(index);
      }
      if (currentPos <= end && end <= currentPos + lineLength) {
        selectedLines.push(index);
      }
      currentPos += lineLength;
    });
    
    if (selectedLines.length === 0) {
      // No line selected, format current line
      const currentLine = value.substring(0, start).split('\n').length - 1;
      selectedLines.push(currentLine);
    }
    
    // Format selected lines
    const newLines = lines.map((line, index) => {
      if (selectedLines.includes(index)) {
        return line.startsWith('> ') ? line : `> ${line}`;
      }
      return line;
    });
    
    const newText = newLines.join('\n');
    onChange(newText);
    addToHistory(newText);
  };

  const formatList = () => {
    const lines = value.split('\n');
    const start = textareaRef.current?.selectionStart || 0;
    const end = textareaRef.current?.selectionEnd || 0;
    
    // Find selected lines
    let currentPos = 0;
    const selectedLines: number[] = [];
    
    lines.forEach((line, index) => {
      const lineLength = line.length + 1;
      if (currentPos <= start && start <= currentPos + lineLength) {
        selectedLines.push(index);
      }
      if (currentPos <= end && end <= currentPos + lineLength) {
        selectedLines.push(index);
      }
      currentPos += lineLength;
    });
    
    if (selectedLines.length === 0) {
      const currentLine = value.substring(0, start).split('\n').length - 1;
      selectedLines.push(currentLine);
    }
    
    // Format selected lines
    const newLines = lines.map((line, index) => {
      if (selectedLines.includes(index)) {
        return line.startsWith('- ') ? line : `- ${line}`;
      }
      return line;
    });
    
    const newText = newLines.join('\n');
    onChange(newText);
    addToHistory(newText);
  };

  const formatOrderedList = () => {
    const lines = value.split('\n');
    const start = textareaRef.current?.selectionStart || 0;
    const end = textareaRef.current?.selectionEnd || 0;
    
    let currentPos = 0;
    const selectedLines: number[] = [];
    
    lines.forEach((line, index) => {
      const lineLength = line.length + 1;
      if (currentPos <= start && start <= currentPos + lineLength) {
        selectedLines.push(index);
      }
      if (currentPos <= end && end <= currentPos + lineLength) {
        selectedLines.push(index);
      }
      currentPos += lineLength;
    });
    
    if (selectedLines.length === 0) {
      const currentLine = value.substring(0, start).split('\n').length - 1;
      selectedLines.push(currentLine);
    }
    
    // Format selected lines
    let counter = 1;
    const newLines = lines.map((line, index) => {
      if (selectedLines.includes(index)) {
        const formatted = line.replace(/^\d+\.\s*/, '');
        return `${counter++}. ${formatted}`;
      }
      return line;
    });
    
    const newText = newLines.join('\n');
    onChange(newText);
    addToHistory(newText);
  };

  const formatLink = () => {
    const selectedText = value.substring(
      textareaRef.current?.selectionStart || 0,
      textareaRef.current?.selectionEnd || 0
    );
    
    if (selectedText) {
      wrapSelection('[', '](url)');
    } else {
      insertAtCursor('[link text](url)');
    }
  };

  // Popular emojis
  const popularEmojis = ['😊', '🚀', '💡', '🔥', '⭐', '🎯', '📱', '💻', '🔧', '📊', '🎨', '🌟'];

  const insertEmoji = (emoji: string) => {
    insertAtCursor(emoji);
    setShowEmojiPicker(false);
  };

  // Enhanced undo/redo functions
  const undo = () => {
    if (historyIndex > 0) {
      const newIndex = historyIndex - 1;
      setHistoryIndex(newIndex);
      onChange(history[newIndex]);
    }
  };

  const redo = () => {
    if (historyIndex < history.length - 1) {
      const newIndex = historyIndex + 1;
      setHistoryIndex(newIndex);
      onChange(history[newIndex]);
    }
  };

  // Handle value changes for history
  const handleChange = (newValue: string) => {
    onChange(newValue);
    // Don't add to history if it's an undo/redo
    if (newValue !== history[historyIndex]) {
      addToHistory(newValue);
    }
  };

  // Markdown->HTML preview (HTML-escaped + href-scheme-validated to prevent XSS). Extracted to
  // lib/utils/renderMarkdownPreview so the escaping is unit-testable.
  const htmlContent = renderMarkdownPreview(value);

  // If forcePreview is enabled, always show preview
  const currentViewMode = forcePreview ? 'preview' : viewMode;

  return (
    <div className={`rich-textarea ${className}`}>
      {/* Formatting toolbar - hidden if forcePreview */}
      {!forcePreview && (
        <div className="flex flex-wrap items-center gap-1 p-2 bg-theme-secondary border border-theme rounded-t-lg">
          {/* Edit/Preview tabs */}
          <div className="flex items-center bg-theme-primary rounded-md p-1 mr-4">
            <button
              type="button"
              onClick={() => setViewMode('edit')}
              className={`px-3 py-1 rounded text-sm font-medium transition-colors ${
                viewMode === 'edit'
                  ? 'bg-blue-500 text-white'
                  : 'text-theme-muted hover:text-theme-primary'
              }`}
              title={t('richTextarea.editMode')}
            >
              <Edit3 className="w-4 h-4 inline mr-1" />
              {t('richTextarea.edit')}
            </button>
            <button
              type="button"
              onClick={() => setViewMode('preview')}
              className={`px-3 py-1 rounded text-sm font-medium transition-colors ${
                viewMode === 'preview'
                  ? 'bg-blue-500 text-white'
                  : 'text-theme-muted hover:text-theme-primary'
              }`}
              title={t('richTextarea.livePreview')}
            >
              <Eye className="w-4 h-4 inline mr-1" />
              {t('richTextarea.preview')}
            </button>
          </div>

          <div className="w-px h-6 bg-theme mx-1" />
          
          <button
            type="button"
            onClick={undo}
            disabled={disabled || viewMode === 'preview' || historyIndex <= 0}
            className="p-2 text-theme-muted hover:text-theme-primary hover:bg-theme-background rounded transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            title={t('richTextarea.undo')}
          >
            <Undo className="w-4 h-4" />
          </button>
          
          <button
            type="button"
            onClick={redo}
            disabled={disabled || viewMode === 'preview' || historyIndex >= history.length - 1}
            className="p-2 text-theme-muted hover:text-theme-primary hover:bg-theme-background rounded transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            title={t('richTextarea.redo')}
          >
            <Redo className="w-4 h-4" />
          </button>
          
          <div className="w-px h-6 bg-theme mx-1" />
          
          <button
            type="button"
            onClick={formatBold}
            disabled={disabled || viewMode === 'preview'}
            className="p-2 text-theme-muted hover:text-theme-primary hover:bg-theme-background rounded transition-colors"
            title={t('richTextarea.bold')}
          >
            <Bold className="w-4 h-4" />
          </button>
          
          <button
            type="button"
            onClick={formatItalic}
            disabled={disabled || viewMode === 'preview'}
            className="p-2 text-theme-muted hover:text-theme-primary hover:bg-theme-background rounded transition-colors"
            title={t('richTextarea.italic')}
          >
            <Italic className="w-4 h-4" />
          </button>
          
          <button
            type="button"
            onClick={formatCode}
            disabled={disabled || viewMode === 'preview'}
            className="p-2 text-theme-muted hover:text-theme-primary hover:bg-theme-background rounded transition-colors"
            title={t('richTextarea.inlineCode')}
          >
            <Code className="w-4 h-4" />
          </button>
          
          <div className="w-px h-6 bg-theme mx-1" />
          
          <button
            type="button"
            onClick={formatQuote}
            disabled={disabled || viewMode === 'preview'}
            className="p-2 text-theme-muted hover:text-theme-primary hover:bg-theme-background rounded transition-colors"
            title={t('richTextarea.quote')}
          >
            <Quote className="w-4 h-4" />
          </button>
          
          <button
            type="button"
            onClick={formatList}
            disabled={disabled || viewMode === 'preview'}
            className="p-2 text-theme-muted hover:text-theme-primary hover:bg-theme-background rounded transition-colors"
            title={t('richTextarea.bulletList')}
          >
            <List className="w-4 h-4" />
          </button>
          
          <button
            type="button"
            onClick={formatOrderedList}
            disabled={disabled || viewMode === 'preview'}
            className="p-2 text-theme-muted hover:text-theme-primary hover:bg-theme-background rounded transition-colors"
            title={t('richTextarea.numberedList')}
          >
            <ListOrdered className="w-4 h-4" />
          </button>
          
          <button
            type="button"
            onClick={formatLink}
            disabled={disabled || viewMode === 'preview'}
            className="p-2 text-theme-muted hover:text-theme-primary hover:bg-theme-background rounded transition-colors"
            title={t('richTextarea.link')}
          >
            <Link className="w-4 h-4" />
          </button>
          
          <div className="w-px h-6 bg-theme mx-1" />
          
          <div className="relative">
            <button
              type="button"
              onClick={() => setShowEmojiPicker(!showEmojiPicker)}
              disabled={disabled || viewMode === 'preview'}
              className="p-2 text-theme-muted hover:text-theme-primary hover:bg-theme-background rounded transition-colors"
              title={t('richTextarea.insertEmoji')}
            >
              <Smile className="w-4 h-4" />
            </button>
            
            {showEmojiPicker && (
              <div className="absolute bottom-full right-0 mb-2 p-3 bg-theme-primary border border-theme rounded-lg shadow-lg z-10 min-w-[200px]">
                <div className="grid grid-cols-6 gap-2">
                  {popularEmojis.map((emoji, index) => (
                    <button
                      key={index}
                      type="button"
                      onClick={() => insertEmoji(emoji)}
                      className="p-2 hover:bg-theme-secondary rounded text-lg transition-colors"
                    >
                      {emoji}
                    </button>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>
      )}
      
      {/* Content area (edit or preview) */}
      {currentViewMode === 'edit' ? (
        <textarea
          ref={textareaRef}
          value={value}
          onChange={(e) => handleChange(e.target.value)}
          placeholder={placeholder}
          rows={rows}
          disabled={disabled}
          className={`w-full px-4 py-3 bg-theme-primary border-x border-b border-theme rounded-b-lg text-theme-primary placeholder-theme-muted focus:outline-none focus:ring-2 focus:ring-blue-500/50 transition-all duration-300 disabled:opacity-50 disabled:cursor-not-allowed ${
            resizable ? 'resize-y' : 'resize-none'
          }`}
          style={{ minHeight: `${rows * 1.5}rem` }}
        />
      ) : (
        <div className="w-full px-4 py-3 bg-theme-primary border-x border-b border-theme rounded-b-lg min-h-[120px] max-h-[300px] overflow-y-auto">
          {value ? (
            <div 
              className="markdown-content"
              dangerouslySetInnerHTML={{ __html: htmlContent }}
            />
          ) : (
            <div className="text-theme-muted italic">
              {placeholder || t('richTextarea.noContentToPreview')}
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default RichTextarea;
