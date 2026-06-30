import React from 'react';

interface StatCardProps {
  label: string;
  value: React.ReactNode;
  accent?: 'default' | 'success' | 'danger';
}

const accentClasses: Record<Required<StatCardProps>['accent'], string> = {
  default: 'text-theme-primary',
  success: 'text-green-600',
  danger: 'text-red-600'
};

export const StatCard: React.FC<StatCardProps> = ({ label, value, accent = 'default' }) => (
  <div className="bg-theme-tertiary p-4 rounded-lg border border-theme text-center">
    <div className={`text-2xl font-bold ${accentClasses[accent]}`}>{value}</div>
    <div className="text-sm text-theme-secondary">{label}</div>
  </div>
);

export default StatCard;
