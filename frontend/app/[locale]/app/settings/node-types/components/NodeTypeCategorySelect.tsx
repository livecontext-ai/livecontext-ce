"use client";

import React from "react";
import { useTranslations } from "next-intl";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  NODE_CATEGORY_ORDER,
  NODE_CATEGORY_LABEL_KEY,
  type NodeCategory,
} from "../categories";

interface NodeTypeCategorySelectProps {
  /** Count per category bucket (only buckets with ≥1 node are offered). */
  counts: Record<string, number>;
  /** Total node-type count, shown next to "All". */
  total: number;
  selectedCategory: NodeCategory | null;
  onSelectCategory: (category: NodeCategory | null) => void;
  loading?: boolean;
}

// next-intl/Radix Select can't use "" as an item value, so the "All" option
// uses a sentinel that maps back to null (no category filter).
const ALL_VALUE = "__all__";

export function NodeTypeCategorySelect({
  counts,
  total,
  selectedCategory,
  onSelectCategory,
  loading = false,
}: NodeTypeCategorySelectProps) {
  const t = useTranslations("nodeTypeSettings");

  const available = NODE_CATEGORY_ORDER.filter((id) => (counts[id] || 0) > 0);

  return (
    <Select
      value={selectedCategory ?? ALL_VALUE}
      onValueChange={(value) =>
        onSelectCategory(value === ALL_VALUE ? null : (value as NodeCategory))
      }
      disabled={loading}
    >
      <SelectTrigger
        className="w-full sm:w-64 text-sm"
        aria-label={t("categoryFilterLabel")}
      >
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value={ALL_VALUE}>
          {t("allCategory")} ({total})
        </SelectItem>
        {available.map((id) => (
          <SelectItem key={id} value={id}>
            {t(NODE_CATEGORY_LABEL_KEY[id])} ({counts[id]})
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}
