"use client";

import React, { useState, useEffect, useRef, useCallback } from "react";
import {
  Search,
  X,
  FileText,
  Type,
} from "lucide-react";
import { useTranslations } from 'next-intl';
import { Conversation } from "@/lib/api/conversationApi";
import { conversationApi } from "@/lib/api/conversationApi";
import { useTheme } from "../ThemeProvider";
import { Button } from "../ui/button";
import { SearchField } from "@/components/ui/search-field";
import { cn } from "@/lib/utils";
import { formatUtcDateTime } from "@/lib/utils/dateFormatters";
import { conversationDisplayTitle } from "@/lib/utils/conversationTitle";

interface SearchConversationModalProps {
  isOpen: boolean;
  onClose: () => void;
  onConversationSelect: (conversation: Conversation) => void;
  currentConversationId?: string | null;
}

export const SearchConversationModal: React.FC<
  SearchConversationModalProps
> = ({ isOpen, onClose, onConversationSelect, currentConversationId }) => {
  const { theme } = useTheme();
  const t = useTranslations('chat.search');
  const tSearch = useTranslations('globalSearch');
  const [searchTerm, setSearchTerm] = useState("");
  const [searchType, setSearchType] = useState<"title" | "content">("content");
  const [hasSearched, setHasSearched] = useState(false);

  const inputRef = useRef<HTMLInputElement>(null);
  const modalRef = useRef<HTMLDivElement>(null);
  const isSearchingRef = useRef(false);
  const tabsContainerRef = useRef<HTMLDivElement>(null);

  // Slider state for animated tab indicator
  const [sliderStyle, setSliderStyle] = useState<{ left: number; width: number }>({ left: 0, width: 0 });

  // State for search results
  const [searchResults, setSearchResults] = useState<Conversation[]>([]);
  const [isSearching, setIsSearching] = useState(false);

  // Focus input when modal opens and clear search when closing
  useEffect(() => {
    if (isOpen && inputRef.current) {
      inputRef.current.focus();
    } else if (!isOpen) {
      // Clear search when modal closes
      setSearchTerm("");
      setHasSearched(false);
      setSearchResults([]);
      setIsSearching(false);
    }
  }, [isOpen]);

  // Handle search with debounce protection
  const handleSearch = useCallback(
    async (term: string, type: "title" | "content") => {
      if (!term.trim()) {
        setHasSearched(false);
        setSearchResults([]);
        setIsSearching(false);
        isSearchingRef.current = false;
        return;
      }

      // eviter les recherches multiples simultanees
      if (isSearchingRef.current) return;

      setHasSearched(true);
      setIsSearching(true);
      isSearchingRef.current = true;

      try {
        const response = await conversationApi.searchConversations(term, type);
        if (response && typeof response === "object") {
          const data = response as any;
          const content = data.content || [];
          setSearchResults(content);
        } else {
          setSearchResults([]);
        }
      } catch (error) {
        console.error("Error searching conversations:", error);
        setSearchResults([]);
      } finally {
        setIsSearching(false);
        isSearchingRef.current = false;
      }
    },
    [],
  );

  // Handle input change with debounce
  useEffect(() => {
    const timeoutId = setTimeout(() => {
      if (searchTerm.trim()) {
        handleSearch(searchTerm, searchType);
      } else {
        setHasSearched(false);
        setSearchResults([]);
        setIsSearching(false);
        isSearchingRef.current = false;
      }
    }, 300);

    return () => clearTimeout(timeoutId);
  }, [searchTerm, searchType, handleSearch]);

  // Handle search type change
  const handleSearchTypeChange = (type: "title" | "content") => {
    setSearchType(type);
    // La recherche sera declenchee automatiquement par le useEffect
  };

  // Calculate slider position based on active tab
  useEffect(() => {
    const updateSlider = () => {
      if (!tabsContainerRef.current) return;

      const activeButton = tabsContainerRef.current.querySelector(
        `[data-tab-id="${searchType}"]`
      ) as HTMLButtonElement;

      if (activeButton) {
        const containerRect = tabsContainerRef.current.getBoundingClientRect();
        const buttonRect = activeButton.getBoundingClientRect();

        setSliderStyle({
          left: buttonRect.left - containerRect.left,
          width: buttonRect.width,
        });
      }
    };

    // Small delay to ensure DOM is ready
    const timer = setTimeout(updateSlider, 50);
    window.addEventListener('resize', updateSlider);
    return () => {
      clearTimeout(timer);
      window.removeEventListener('resize', updateSlider);
    };
  }, [searchType, isOpen]);

  // Handle conversation selection
  const handleConversationClick = (conversation: Conversation) => {
    onConversationSelect(conversation);
    onClose();
  };

  // Handle escape key
  useEffect(() => {
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === "Escape" && isOpen) {
        onClose();
      }
    };

    if (isOpen) {
      document.addEventListener("keydown", handleEscape);
      return () => document.removeEventListener("keydown", handleEscape);
    }
  }, [isOpen, onClose]);

  // Handle click outside
  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (modalRef.current && !modalRef.current.contains(e.target as Node)) {
        onClose();
      }
    };

    if (isOpen) {
      document.addEventListener("mousedown", handleClickOutside);
      return () =>
        document.removeEventListener("mousedown", handleClickOutside);
    }
  }, [isOpen, onClose]);

  // Use search results from local state
  const displayResults = searchResults;

  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={(e) => e.target === e.currentTarget && onClose()}
    >
      <div
        ref={modalRef}
        className="bg-theme-primary border border-theme rounded-2xl shadow-[0_16px_48px_rgba(0,0,0,0.16)] w-full max-w-3xl max-h-[85vh] flex flex-col animate-in fade-in-0 zoom-in-95 duration-200"
      >
        {/* Header */}
        <div className="flex items-center justify-between p-6 bg-theme-secondary/30">
          <div className="flex items-center space-x-4">
            <Search className="w-6 h-6 text-theme-primary" />
            <div>
              <h2 className="text-xl font-semibold text-theme-primary">
                {t('title')}
              </h2>
              <p className="text-theme-secondary">
                {t('subtitle')}
              </p>
            </div>
          </div>
          <Button
            onClick={onClose}
            variant="ghost"
            size="icon"
            className="w-8 h-8"
          >
            <X className="w-5 h-5" />
          </Button>
        </div>

        {/* Search Input */}
        <div className="p-6 bg-theme-secondary/20">
          <SearchField
            ref={inputRef}
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            onClear={() => setSearchTerm("")}
            clearLabel={tSearch('clear')}
            placeholder={t('placeholder')}
            autoFocus
          />

          {/* Search Type Toggle - Same style as WorkflowMessagesPanel tabs */}
          <div className="flex items-center justify-center mt-8">
            <div
              ref={tabsContainerRef}
              className="relative inline-flex items-center gap-0.5 p-1 bg-theme-tertiary rounded-2xl"
            >
              {/* Animated slider background */}
              <div
                className="absolute top-1 bottom-1 rounded-xl bg-[var(--bg-primary)] transition-all duration-200 ease-out"
                style={{
                  left: `${sliderStyle.left}px`,
                  width: `${sliderStyle.width}px`,
                  opacity: sliderStyle.width > 0 ? 1 : 0,
                }}
              />

              {/* Content tab */}
              <button
                data-tab-id="content"
                type="button"
                onClick={() => handleSearchTypeChange("content")}
                className={cn(
                  "relative z-10 flex h-9 items-center gap-1.5 px-3 rounded-xl text-sm font-medium transition-all duration-200",
                  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60 outline-none",
                  searchType === "content"
                    ? "text-[var(--text-primary)]"
                    : "text-theme-secondary hover:text-theme-primary hover:bg-[var(--bg-primary)]/50"
                )}
              >
                <FileText
                  className={cn(
                    "w-4 h-4 transition-colors duration-200",
                    searchType === "content" ? "text-[var(--text-primary)]" : "text-current"
                  )}
                />
                <span className="whitespace-nowrap">{t('content')}</span>
              </button>

              {/* Title tab */}
              <button
                data-tab-id="title"
                type="button"
                onClick={() => handleSearchTypeChange("title")}
                className={cn(
                  "relative z-10 flex h-9 items-center gap-1.5 px-3 rounded-xl text-sm font-medium transition-all duration-200",
                  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60 outline-none",
                  searchType === "title"
                    ? "text-[var(--text-primary)]"
                    : "text-theme-secondary hover:text-theme-primary hover:bg-[var(--bg-primary)]/50"
                )}
              >
                <Type
                  className={cn(
                    "w-4 h-4 transition-colors duration-200",
                    searchType === "title" ? "text-[var(--text-primary)]" : "text-current"
                  )}
                />
                <span className="whitespace-nowrap">{t('titleTab')}</span>
              </button>
            </div>
          </div>
        </div>

        {/* Search Results - Fixed min-height to prevent layout shifts */}
        <div className="flex-1 overflow-y-auto p-6 bg-theme-primary min-h-[280px]">
          {/* Loading skeleton - matches card layout */}
          <div
            className={cn(
              "transition-all duration-300 ease-out",
              isSearching
                ? "opacity-100 translate-y-0"
                : "opacity-0 translate-y-2 absolute pointer-events-none"
            )}
          >
            {isSearching && (
              <div className="space-y-2">
                {[1, 2, 3, 4].map((i) => (
                  <div
                    key={i}
                    className="flex items-center gap-3 p-3 rounded-xl hover:bg-theme-secondary/30 transition-colors duration-200"
                  >
                    {/* Circular icon skeleton */}
                    <div
                      className="w-10 h-10 bg-theme-tertiary rounded-full animate-pulse flex-shrink-0"
                      style={{ animationDelay: `${i * 100}ms` }}
                    />
                    {/* Content skeleton */}
                    <div className="flex-1 min-w-0 space-y-2">
                      <div
                        className="h-4 bg-theme-tertiary rounded animate-pulse"
                        style={{ width: `${60 + (i % 3) * 15}%`, animationDelay: `${i * 100 + 50}ms` }}
                      />
                      <div
                        className="h-3 bg-theme-tertiary rounded animate-pulse"
                        style={{ width: `${40 + (i % 2) * 20}%`, animationDelay: `${i * 100 + 100}ms` }}
                      />
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Results list */}
          <div
            className={cn(
              "transition-all duration-300 ease-out",
              !isSearching && hasSearched && searchTerm.trim() && displayResults.length > 0
                ? "opacity-100 translate-y-0"
                : "opacity-0 translate-y-2 absolute pointer-events-none"
            )}
          >
            {!isSearching && hasSearched && searchTerm.trim() && displayResults.length > 0 && (
              <div className="space-y-2">
                <div className="flex items-center justify-between mb-4">
                  <span className="text-theme-secondary text-sm">
                    {t('resultCount', { count: displayResults.length })}
                  </span>
                </div>
                {displayResults.map((conversation) => (
                  <div
                    key={conversation.id}
                    onClick={() => handleConversationClick(conversation)}
                    className={cn(
                      "flex items-center gap-3 p-3 rounded-xl cursor-pointer transition-all duration-200",
                      conversation.id === currentConversationId
                        ? "bg-theme-accent/10 border border-theme-accent"
                        : "hover:bg-theme-secondary/50 border border-transparent"
                    )}
                  >
                    {/* Circular icon - changes based on search type */}
                    <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center flex-shrink-0">
                      {searchType === "content" ? (
                        <FileText className="w-5 h-5 text-theme-primary" />
                      ) : (
                        <Type className="w-5 h-5 text-theme-primary" />
                      )}
                    </div>

                    {/* Content */}
                    <div className="flex-1 min-w-0">
                      <h3 className="text-sm font-semibold text-theme-primary truncate">
                        {conversationDisplayTitle(conversation, t('untitledChat'))}
                      </h3>
                      <p className="text-sm text-theme-secondary truncate">
                        {conversation.messageCount || 0} messages · {formatUtcDateTime(conversation.updatedAt)}
                      </p>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* No results state */}
          <div
            className={cn(
              "flex flex-col items-center justify-center py-16 transition-all duration-300 ease-out",
              !isSearching && hasSearched && searchTerm.trim() && displayResults.length === 0
                ? "opacity-100 translate-y-0"
                : "opacity-0 translate-y-2 absolute pointer-events-none"
            )}
          >
            <div className="flex items-center gap-3 mb-6">
              <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
                <Search className="w-5 h-5 text-theme-primary" />
              </div>
              <div>
                <h2 className="text-lg font-semibold text-theme-primary">
                  {t('noResults')}
                </h2>
                <p className="text-sm text-theme-secondary">
                  {t('tryDifferent')}
                </p>
              </div>
            </div>
            <p className="text-theme-secondary text-center max-w-md mx-auto text-sm mb-6">
              {t('noResultsMessage')}
            </p>
            <div className="flex flex-col sm:flex-row gap-3 justify-center">
              <Button
                onClick={() => handleSearchTypeChange("title")}
                variant="outline"
                size="sm"
              >
                {t('byTitle')}
              </Button>
              <Button
                onClick={() => handleSearchTypeChange("content")}
                variant="outline"
                size="sm"
              >
                {t('byContent')}
              </Button>
            </div>
          </div>

          {/* Empty/initial state */}
          <div
            className={cn(
              "flex flex-col items-center justify-center py-16 transition-all duration-300 ease-out",
              !isSearching && (!hasSearched || !searchTerm.trim())
                ? "opacity-100 translate-y-0"
                : "opacity-0 translate-y-2 absolute pointer-events-none"
            )}
          >
            <div className="flex items-center gap-3 mb-6">
              <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
                <Search className="w-5 h-5 text-theme-primary" />
              </div>
              <div>
                <h2 className="text-lg font-semibold text-theme-primary">
                  {t('emptyTitle')}
                </h2>
                <p className="text-sm text-theme-secondary">
                  {t('emptySubtitle')}
                </p>
              </div>
            </div>
            <p className="text-theme-secondary text-center max-w-md mx-auto text-sm">
              {t('emptyMessage')}
            </p>
          </div>
        </div>

        {/* Footer */}
        <div className="p-6 bg-theme-secondary/30">
          <div className="flex items-center justify-between">
            <div className="text-sm text-theme-primary">
              Press{" "}
              <kbd className="px-2 py-1 bg-theme-tertiary text-theme-primary rounded text-xs font-mono">
                Esc
              </kbd>{" "}
              {t('toClose')}
            </div>
            <Button onClick={onClose} variant="outline">
              {t('close')}
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
};
