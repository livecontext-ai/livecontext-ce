'use client';

import React from 'react';
import { Button } from '@/components/ui/button';

interface DataSourceSuggestionsProps {
  messageId: string;
  onSelect: (messageId: string, suggestion: string) => void;
  onManualMode: () => void;
}

const dataSourceSuggestions: Array<{ id: string; name: string; description: string }> = [
  {
    id: 'travel-destinations',
    name: 'Summer travel destinations',
    description: 'Create a comprehensive database of top summer travel destinations including popular cities, beach resorts, mountain retreats, and cultural sites with details about weather, activities, accommodations, and local attractions to help plan perfect summer vacations.',
  },
  {
    id: 'crm',
    name: 'Customer Relationship Management',
    description: 'Build a CRM data source to track customer interactions, contact information, communication history, purchase records, preferences, support tickets, and engagement metrics to improve customer relationships and sales performance.',
  },
  {
    id: 'prospects',
    name: 'Sales prospects database',
    description: 'Generate a prospects database containing potential customers with company information, contact details, industry classification, company size, revenue estimates, decision makers, and qualification status to streamline your sales pipeline.',
  },
  {
    id: 'products',
    name: 'Product catalog',
    description: 'Create a comprehensive product catalog with item names, descriptions, categories, specifications, pricing, inventory levels, images, SKUs, suppliers, and availability status to manage your product inventory effectively.',
  },
  {
    id: 'customers',
    name: 'Customer database',
    description: 'Build a customer database storing customer profiles, purchase history, preferences, contact information, loyalty status, lifetime value, communication preferences, and service history to personalize experiences and improve retention.',
  },
  {
    id: 'inventory',
    name: 'Inventory management',
    description: 'Create an inventory management system tracking stock levels, item locations, reorder points, supplier information, cost prices, selling prices, movement history, and alerts for low stock to optimize warehouse operations.',
  },
  {
    id: 'sales',
    name: 'Sales data and analytics',
    description: 'Generate a sales data source containing transaction records, revenue by period, product performance, sales team metrics, conversion rates, customer segments, regional performance, and trends to analyze and improve sales strategies.',
  },
  {
    id: 'analytics',
    name: 'Business analytics dashboard',
    description: 'Create a business analytics data source with key performance indicators, metrics, trends, user behavior data, conversion funnels, engagement statistics, and performance reports to make data-driven business decisions.',
  },
  {
    id: 'marketing',
    name: 'Marketing campaigns',
    description: 'Build a marketing campaigns database tracking campaign types, channels, budgets, target audiences, performance metrics, conversion rates, ROI, engagement data, and results to optimize marketing strategies and spending.',
  },
  {
    id: 'finance',
    name: 'Financial data and records',
    description: 'Generate a financial data source containing transactions, expenses, revenue streams, budgets, forecasts, account balances, invoices, payments, financial statements, and cash flow data to manage finances effectively.',
  },
];

export const DataSourceSuggestions: React.FC<DataSourceSuggestionsProps> = ({
  messageId,
  onSelect,
  onManualMode,
}) => {
  return (
    <div className="mt-4 pt-4">
      <div className="mb-4 text-center">
        <h2 className="text-lg text-theme-primary font-medium mb-8">
          How can I help you with your data source?
        </h2>
      </div>
      <div className="flex flex-wrap gap-2">
        {dataSourceSuggestions.map((suggestion) => (
          <Button
            key={suggestion.id}
            variant="outline"
            size="sm"
            onClick={() => onSelect(messageId, suggestion.name)}
            className="text-xs border-gray-300/70 dark:border-gray-600/70"
          >
            {suggestion.name}
          </Button>
        ))}
        <Button
          key="manual"
          variant="outline"
          size="sm"
          onClick={onManualMode}
          className="text-xs border-gray-300/70 dark:border-gray-600/70"
        >
          Manual
        </Button>
      </div>
    </div>
  );
};

