import React from 'react';
import { StatCard } from '../common/StatCard';

interface ToolStatsProps {
  toolCount: number;
  typeCount: number;
  categoryCount: number;
}

export const ToolStats: React.FC<ToolStatsProps> = ({ toolCount, typeCount, categoryCount }) => (
  <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
    <StatCard label="Configured tools" value={toolCount} />
    <StatCard label="Tool types" value={typeCount} />
    <StatCard label="Categories" value={categoryCount} />
  </div>
);

export default ToolStats;
