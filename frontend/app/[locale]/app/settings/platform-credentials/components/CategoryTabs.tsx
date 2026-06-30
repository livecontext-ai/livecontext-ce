"use client";

import React from "react";
import { CategoryInfo } from "@/lib/api/orchestrator/types";

interface CategoryTabsProps {
  categories: CategoryInfo[];
  selectedCategory: string | null;
  onSelectCategory: (category: string | null) => void;
  loading?: boolean;
}

export function CategoryTabs({
  categories,
  selectedCategory,
  onSelectCategory,
  loading = false,
}: CategoryTabsProps) {
  if (loading) {
    return (
      <div className="flex gap-2 overflow-x-auto pb-2">
        {[...Array(5)].map((_, i) => (
          <div
            key={i}
            className="h-8 w-24 bg-theme-secondary rounded-full animate-pulse"
          />
        ))}
      </div>
    );
  }

  return (
    <div className="flex gap-2 overflow-x-auto pb-2">
      <button
        onClick={() => onSelectCategory(null)}
        className={`px-4 py-1.5 rounded-full text-sm font-medium whitespace-nowrap transition-colors ${
          selectedCategory === null
            ? "bg-blue-500 text-white"
            : "bg-theme-secondary text-theme-secondary hover:bg-theme-tertiary"
        }`}
      >
        All ({categories.reduce((sum, c) => sum + c.integrationCount, 0)})
      </button>
      {categories.map((category) => (
        <button
          key={category.slug}
          onClick={() => onSelectCategory(category.slug)}
          className={`px-4 py-1.5 rounded-full text-sm font-medium whitespace-nowrap transition-colors ${
            selectedCategory === category.slug
              ? "bg-blue-500 text-white"
              : "bg-theme-secondary text-theme-secondary hover:bg-theme-tertiary"
          }`}
        >
          {category.name} ({category.integrationCount})
        </button>
      ))}
    </div>
  );
}
