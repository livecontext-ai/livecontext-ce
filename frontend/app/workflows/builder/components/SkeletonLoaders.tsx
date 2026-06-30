'use client';

import React from 'react';

/**
 * Skeleton loader pour un item API
 */
export const ApiItemSkeleton: React.FC = () => {
  return (
    <div className="group flex items-center gap-2 px-3 py-2 rounded-lg relative animate-pulse">
      <div className="flex-shrink-0 w-8 h-8 rounded bg-gray-200 dark:bg-gray-700" />
      <div className="flex-1 min-w-0 flex items-center justify-between gap-4">
        <div className="flex-1 min-w-0 space-y-2">
          <div className="h-4 bg-gray-200 dark:bg-gray-700 rounded w-3/4" />
          <div className="h-3 bg-gray-200 dark:bg-gray-700 rounded w-full" />
          <div className="h-3 bg-gray-200 dark:bg-gray-700 rounded w-1/2" />
        </div>
        <div className="w-4 h-4 bg-gray-200 dark:bg-gray-700 rounded flex-shrink-0" />
      </div>
    </div>
  );
};

/**
 * Skeleton loader pour une liste d'APIs
 */
export const ApiListSkeleton: React.FC<{ count?: number }> = ({ count = 5 }) => {
  return (
    <div className="space-y-1">
      {Array.from({ length: count }).map((_, index) => (
        <ApiItemSkeleton key={index} />
      ))}
    </div>
  );
};

/**
 * Skeleton loader pour un item Tool
 */
export const ToolItemSkeleton: React.FC = () => {
  return (
    <div className="group flex items-center gap-2 px-3 py-2 rounded-lg relative animate-pulse">
      <div className="flex-shrink-0 w-8 h-8 rounded bg-gray-200 dark:bg-gray-700" />
      <div className="flex-1 min-w-0 space-y-2">
        <div className="flex items-center gap-2">
          <div className="h-5 w-12 bg-gray-200 dark:bg-gray-700 rounded" />
          <div className="h-4 bg-gray-200 dark:bg-gray-700 rounded flex-1" />
        </div>
        <div className="h-3 bg-gray-200 dark:bg-gray-700 rounded w-full" />
      </div>
    </div>
  );
};

/**
 * Skeleton loader pour une liste de Tools
 */
export const ToolListSkeleton: React.FC<{ count?: number }> = ({ count = 5 }) => {
  return (
    <div className="space-y-1">
      {Array.from({ length: count }).map((_, index) => (
        <ToolItemSkeleton key={index} />
      ))}
    </div>
  );
};

/**
 * Skeleton loader pour un paramètre de tool
 */
export const ToolParameterSkeleton: React.FC = () => {
  return (
    <div className="space-y-2 p-3 rounded-lg border border-gray-200 dark:border-gray-700 animate-pulse">
      <div className="flex items-center justify-between">
        <div className="h-4 bg-gray-200 dark:bg-gray-700 rounded w-1/4" />
        <div className="h-5 w-16 bg-gray-200 dark:bg-gray-700 rounded" />
      </div>
      <div className="h-3 bg-gray-200 dark:bg-gray-700 rounded w-3/4" />
      <div className="h-10 bg-gray-100 dark:bg-gray-800 rounded" />
    </div>
  );
};

/**
 * Skeleton loader pour une liste de paramètres
 */
export const ToolParametersSkeleton: React.FC<{ count?: number }> = ({ count = 3 }) => {
  return (
    <div className="space-y-2">
      {Array.from({ length: count }).map((_, index) => (
        <ToolParameterSkeleton key={index} />
      ))}
    </div>
  );
};

/**
 * Skeleton loader pour une réponse de tool
 */
export const ToolResponseSkeleton: React.FC = () => {
  return (
    <div className="p-3 rounded-lg border border-gray-200 dark:border-gray-700 animate-pulse space-y-2">
      <div className="flex items-center justify-between">
        <div className="h-4 bg-gray-200 dark:bg-gray-700 rounded w-1/3" />
        <div className="h-5 w-12 bg-gray-200 dark:bg-gray-700 rounded" />
      </div>
      <div className="h-3 bg-gray-200 dark:bg-gray-700 rounded w-2/3" />
      <div className="h-20 bg-gray-100 dark:bg-gray-800 rounded" />
    </div>
  );
};

/**
 * Skeleton loader pour une liste de réponses
 */
export const ToolResponsesSkeleton: React.FC<{ count?: number }> = ({ count = 2 }) => {
  return (
    <div className="space-y-2">
      {Array.from({ length: count }).map((_, index) => (
        <ToolResponseSkeleton key={index} />
      ))}
    </div>
  );
};

/**
 * Skeleton loader pour un credential de tool
 */
export const ToolCredentialSkeleton: React.FC = () => {
  return (
    <div className="p-3 rounded-lg border border-gray-200 dark:border-gray-700 animate-pulse space-y-2">
      <div className="h-4 bg-gray-200 dark:bg-gray-700 rounded w-1/2" />
      <div className="h-3 bg-gray-200 dark:bg-gray-700 rounded w-3/4" />
      <div className="flex gap-2">
        <div className="h-5 w-20 bg-gray-200 dark:bg-gray-700 rounded" />
        <div className="h-5 w-20 bg-gray-200 dark:bg-gray-700 rounded" />
      </div>
    </div>
  );
};

/**
 * Skeleton loader pour une liste de credentials
 */
export const ToolCredentialsSkeleton: React.FC<{ count?: number }> = ({ count = 2 }) => {
  return (
    <div className="space-y-2">
      {Array.from({ length: count }).map((_, index) => (
        <ToolCredentialSkeleton key={index} />
      ))}
    </div>
  );
};

/**
 * Skeleton loader pour les détails complets d'un tool
 */
export const ToolDetailsSkeleton: React.FC = () => {
  return (
    <div className="space-y-6 animate-pulse">
      {/* Parameters Section */}
      <div>
        <div className="h-5 bg-gray-200 dark:bg-gray-700 rounded w-32 mb-3" />
        <ToolParametersSkeleton count={3} />
      </div>
      
      {/* Responses Section */}
      <div>
        <div className="h-5 bg-gray-200 dark:bg-gray-700 rounded w-32 mb-3" />
        <ToolResponsesSkeleton count={2} />
      </div>
      
      {/* Credentials Section */}
      <div>
        <div className="h-5 bg-gray-200 dark:bg-gray-700 rounded w-32 mb-3" />
        <ToolCredentialsSkeleton count={2} />
      </div>
    </div>
  );
};

