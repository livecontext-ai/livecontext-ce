'use client';

import React, { useState, useEffect } from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import { Database, ArrowRight, Clock, FileText, HardDrive } from 'lucide-react';
import Link from 'next/link';

interface StorageInfoProps {
  userId: string;
}

interface StorageData {
  totalRequests: number;
  totalStorage: number;
  usedStorage: number;
  lastRequest: string;
  recentRequests: Array<{
    id: string;
    timestamp: string;
    type: string;
    status: 'success' | 'error' | 'pending';
    size: string;
  }>;
}

export default function StorageInfo({ userId }: StorageInfoProps) {
  const [storageData, setStorageData] = useState<StorageData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // Charger les donnees mockees immediatement (pas de delai artificiel)
    try {
      // Donnees mockees pour le storage
      const mockStorageData: StorageData = {
        totalRequests: 1247,
        totalStorage: 100, // 100GB
        usedStorage: 15.7, // 15.7GB
        lastRequest: '2 hours ago',
        recentRequests: [
          {
            id: 'req_001',
            timestamp: '2 hours ago',
            type: 'GPT-4 Query',
            status: 'success',
            size: '2.3 MB'
          },
          {
            id: 'req_002',
            timestamp: '4 hours ago',
            type: 'Image Generation',
            status: 'success',
            size: '5.1 MB'
          },
          {
            id: 'req_003',
            timestamp: '6 hours ago',
            type: 'Code Analysis',
            status: 'error',
            size: '0.8 MB'
          },
          {
            id: 'req_004',
            timestamp: '1 day ago',
            type: 'Document Processing',
            status: 'success',
            size: '12.4 MB'
          },
          {
            id: 'req_005',
            timestamp: '2 days ago',
            type: 'Data Visualization',
            status: 'success',
            size: '3.2 MB'
          }
        ]
      };

      setStorageData(mockStorageData);
      setLoading(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unknown error');
      setLoading(false);
    }
  }, []);

  const formatBytes = (bytes: number) => {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  const formatNumber = (num: number) => {
    return num.toLocaleString(getClientLocale());
  };

  const calculateUsagePercentage = (used: number, total: number): number => {
    if (total === 0) return 0;
    const percentage = (used / total) * 100;
    return Math.min(Math.max(percentage, 0), 100);
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'success': return 'text-green-500';
      case 'error': return 'text-red-500';
      case 'pending': return 'text-yellow-500';
      default: return 'text-gray-500';
    }
  };

  const getStatusIcon = (status: string) => {
    switch (status) {
      case 'success': return '✓';
      case 'error': return '✗';
      case 'pending': return '⏳';
      default: return '?';
    }
  };

  if (loading) {
    return (
      <div className="bg-gradient-to-r from-theme-primary to-theme-secondary p-6 rounded-xl border border-theme mb-8 animate-pulse">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-4">
            <div className="w-12 h-12 bg-theme-tertiary rounded-xl flex items-center justify-center">
              <Database className="w-6 h-6 text-theme-primary" />
            </div>
            <div>
              <div className="h-5 bg-theme-tertiary rounded mb-2 w-32"></div>
              <div className="h-4 bg-theme-tertiary rounded w-48"></div>
            </div>
          </div>
          <div className="h-10 bg-theme-tertiary rounded w-32"></div>
        </div>
      </div>
    );
  }

  if (error || !storageData) {
    return (
      <div className="bg-gradient-to-r from-theme-primary to-theme-secondary p-6 rounded-xl border border-theme mb-8">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-4">
            <div className="w-12 h-12 bg-theme-tertiary rounded-xl flex items-center justify-center">
              <Database className="w-6 h-6 text-theme-primary" />
            </div>
            <div>
              <h3 className="text-lg font-semibold text-theme-secondary mb-1">Request Storage</h3>
              <p className="text-theme-secondary/80 text-sm">Impossible de charger les donnees de stockage</p>
            </div>
          </div>
          <Link
            href="/app/settings/storage"
            className="bg-theme-secondary text-theme-primary px-6 py-3 rounded-lg hover:bg-theme-tertiary transition-all duration-300 font-medium"
          >
            Access Storage
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="bg-gradient-to-r from-theme-primary to-theme-secondary p-6 rounded-xl border border-theme mb-8">
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center space-x-4">
          <div className="w-12 h-12 bg-theme-tertiary rounded-xl flex items-center justify-center">
            <Database className="w-6 h-6 text-theme-primary" />
          </div>
          <div>
            <h3 className="text-lg font-semibold text-theme-secondary mb-1">Request Storage</h3>
            <p className="text-theme-secondary/80 text-sm">Access the complete history of your requests and results</p>
          </div>
        </div>
        <Link
          href="/app/settings/storage"
          className="bg-theme-secondary text-theme-primary px-6 py-3 rounded-lg hover:bg-theme-tertiary transition-all duration-300 font-medium flex items-center space-x-2"
        >
          <span>Access Storage</span>
          <ArrowRight className="w-4 h-4" />
        </Link>
      </div>

      {/* Storage Stats */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-4">
        <div className="bg-theme-tertiary/50 rounded-lg p-4 border border-theme">
          <div className="flex items-center space-x-2 mb-2">
            <FileText className="w-4 h-4 text-blue-500" />
            <span className="text-sm font-medium text-theme-secondary">Total Requests</span>
          </div>
          <div className="text-2xl font-bold text-theme-primary">
            {formatNumber(storageData.totalRequests)}
          </div>
        </div>

        <div className="bg-theme-tertiary/50 rounded-lg p-4 border border-theme">
          <div className="flex items-center space-x-2 mb-2">
            <HardDrive className="w-4 h-4 text-green-500" />
            <span className="text-sm font-medium text-theme-secondary">Storage Used</span>
          </div>
          <div className="text-2xl font-bold text-theme-primary">
            {storageData.usedStorage.toFixed(1)} GB
          </div>
          <div className="text-xs text-theme-muted">
            of {storageData.totalStorage} GB ({calculateUsagePercentage(storageData.usedStorage, storageData.totalStorage).toFixed(1)}%)
          </div>
        </div>

        <div className="bg-theme-tertiary/50 rounded-lg p-4 border border-theme">
          <div className="flex items-center space-x-2 mb-2">
            <Clock className="w-4 h-4 text-purple-500" />
            <span className="text-sm font-medium text-theme-secondary">Last Request</span>
          </div>
          <div className="text-lg font-semibold text-theme-primary">
            {storageData.lastRequest}
          </div>
        </div>
      </div>

      {/* Recent Requests */}
      <div className="bg-theme-tertiary/30 rounded-lg p-4 border border-theme">
        <h4 className="text-sm font-semibold text-theme-secondary mb-3">Recent Requests</h4>
        <div className="space-y-2">
          {storageData.recentRequests.slice(0, 3).map((request) => (
            <div key={request.id} className="flex items-center justify-between py-2 px-3 bg-theme-secondary/50 rounded border border-theme">
              <div className="flex items-center space-x-3">
                <span className={`text-sm ${getStatusColor(request.status)}`}>
                  {getStatusIcon(request.status)}
                </span>
                <div>
                  <div className="text-sm font-medium text-theme-primary">{request.type}</div>
                  <div className="text-xs text-theme-muted">{request.timestamp}</div>
                </div>
              </div>
              <div className="text-xs text-theme-muted">{request.size}</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
