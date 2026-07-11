import React, { useState } from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import { useTranslations } from 'next-intl';
import {
  BarChart3,
  TrendingUp,
  TrendingDown,
  Activity,
  Users,
  Clock,
  Zap,
  AlertTriangle,
  CheckCircle,
  DollarSign,
  Globe,
  Calendar,
  Download,
  RefreshCw,
  Filter,
  Eye
} from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';

interface AnalyzeTabProps {
  apiData: any;
}

const AnalyzeTab: React.FC<AnalyzeTabProps> = ({ apiData }) => {
  const t = useTranslations('mcp.analyzeTab');
  const [timeRange, setTimeRange] = useState('7d');
  const [refreshKey, setRefreshKey] = useState(0);

  const handleRefresh = () => {
    setRefreshKey(prev => prev + 1);
  };

  // Donnees mockees pour les analyses
  const analyticsData = {
    overview: {
      totalRequests: 125430,
      successRate: 99.2,
      avgResponseTime: 245,
      activeUsers: 1247,
      revenue: 2450.50,
      uptime: 99.9
    },
    requests: {
      hourly: [
        { hour: '00:00', requests: 120, errors: 2 },
        { hour: '01:00', requests: 95, errors: 1 },
        { hour: '02:00', requests: 78, errors: 0 },
        { hour: '03:00', requests: 65, errors: 1 },
        { hour: '04:00', requests: 89, errors: 0 },
        { hour: '05:00', requests: 134, errors: 2 },
        { hour: '06:00', requests: 198, errors: 1 },
        { hour: '07:00', requests: 267, errors: 3 },
        { hour: '08:00', requests: 345, errors: 2 },
        { hour: '09:00', requests: 423, errors: 4 },
        { hour: '10:00', requests: 512, errors: 3 },
        { hour: '11:00', requests: 589, errors: 5 },
        { hour: '12:00', requests: 634, errors: 4 },
        { hour: '13:00', requests: 678, errors: 6 },
        { hour: '14:00', requests: 712, errors: 3 },
        { hour: '15:00', requests: 745, errors: 4 },
        { hour: '16:00', requests: 689, errors: 2 },
        { hour: '17:00', requests: 623, errors: 3 },
        { hour: '18:00', requests: 567, errors: 1 },
        { hour: '19:00', requests: 498, errors: 2 },
        { hour: '20:00', requests: 434, errors: 1 },
        { hour: '21:00', requests: 356, errors: 0 },
        { hour: '22:00', requests: 278, errors: 1 },
        { hour: '23:00', requests: 189, errors: 0 }
      ],
      daily: [
        { date: '2024-01-01', requests: 15420, errors: 45, revenue: 234.50 },
        { date: '2024-01-02', requests: 16890, errors: 38, revenue: 267.80 },
        { date: '2024-01-03', requests: 18230, errors: 52, revenue: 289.20 },
        { date: '2024-01-04', requests: 19560, errors: 41, revenue: 312.40 },
        { date: '2024-01-05', requests: 20120, errors: 48, revenue: 325.60 },
        { date: '2024-01-06', requests: 18750, errors: 35, revenue: 298.30 },
        { date: '2024-01-07', requests: 17260, errors: 42, revenue: 274.80 }
      ]
    },
    topEndpoints: [
      { endpoint: '/users/{id}', requests: 45230, avgTime: 180, successRate: 99.8 },
      { endpoint: '/posts/search', requests: 32150, avgTime: 320, successRate: 98.5 },
      { endpoint: '/analytics/engagement', requests: 28940, avgTime: 450, successRate: 97.2 },
      { endpoint: '/media/upload', requests: 15680, avgTime: 1200, successRate: 95.8 },
      { endpoint: '/followers/list', requests: 12430, avgTime: 280, successRate: 99.1 }
    ],
    errors: [
      { code: 429, message: 'Rate limit exceeded', count: 45, percentage: 0.8 },
      { code: 500, message: 'Internal server error', count: 23, percentage: 0.4 },
      { code: 400, message: 'Bad request', count: 18, percentage: 0.3 },
      { code: 401, message: 'Unauthorized', count: 12, percentage: 0.2 },
      { code: 404, message: 'Not found', count: 8, percentage: 0.1 }
    ],
    users: {
      newUsers: 47,
      activeUsers: 1247,
      returningUsers: 892,
      churnRate: 2.3
    }
  };

  const getTimeRangeData = () => {
    return timeRange === '24h' ? analyticsData.requests.hourly : analyticsData.requests.daily;
  };

  const getMaxRequests = () => {
    const data = getTimeRangeData();
    return Math.max(...data.map(d => d.requests));
  };

  return (
    <div className="space-y-6">
      {/* Header avec contrôles */}
      <Card className="bg-theme-secondary">
        <CardHeader>
          <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
            <div>
              <CardTitle className="text-2xl font-bold text-theme-primary">{t('header.title')}</CardTitle>
              <CardDescription className="text-theme-secondary">{t('header.description')}</CardDescription>
            </div>
            <div className="flex items-center space-x-3">
            <Select
              value={timeRange}
              onValueChange={(value) => setTimeRange(value)}
            >
              <SelectTrigger className="px-3">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="24h">{t('timeRange.last24h')}</SelectItem>
                <SelectItem value="7d">{t('timeRange.last7d')}</SelectItem>
                <SelectItem value="30d">{t('timeRange.last30d')}</SelectItem>
                <SelectItem value="90d">{t('timeRange.last90d')}</SelectItem>
              </SelectContent>
            </Select>
            <button
              onClick={handleRefresh}
              className="flex items-center space-x-2 px-4 py-2 bg-theme-primary text-theme-secondary rounded-lg hover:bg-theme-primary/90 transition-colors duration-200"
            >
              <RefreshCw className="w-4 h-4" />
              <span>{t('actions.refresh')}</span>
            </button>
            <button className="flex items-center space-x-2 px-4 py-2 bg-theme-tertiary text-theme-primary rounded-lg hover:bg-theme-tertiary/80 transition-colors duration-200">
              <Download className="w-4 h-4" />
              <span>{t('actions.export')}</span>
            </button>
          </div>
        </div>
        </CardHeader>
      </Card>

      {/* Metriques principales */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6 gap-4">
        <Card className="bg-theme-secondary">
          <CardContent className="p-4">
            <div className="flex items-center justify-between mb-2">
              <div className="w-8 h-8 bg-blue-100 rounded-lg flex items-center justify-center">
                <BarChart3 className="w-4 h-4 text-blue-600" />
              </div>
              <span className="text-xs text-green-500 flex items-center">
                <TrendingUp className="w-3 h-3 mr-1" />
                +12%
              </span>
            </div>
            <div className="text-2xl font-bold text-theme-primary mb-1">
              {analyticsData.overview.totalRequests.toLocaleString(getClientLocale())}
            </div>
            <div className="text-xs text-theme-secondary">{t('metrics.totalRequests')}</div>
          </CardContent>
        </Card>

        <Card className="bg-theme-secondary">
          <CardContent className="p-4">
            <div className="flex items-center justify-between mb-2">
              <div className="w-8 h-8 bg-green-100 rounded-lg flex items-center justify-center">
                <CheckCircle className="w-4 h-4 text-green-600" />
              </div>
              <span className="text-xs text-green-500 flex items-center">
                <TrendingUp className="w-3 h-3 mr-1" />
                +0.2%
              </span>
            </div>
            <div className="text-2xl font-bold text-theme-primary mb-1">
              {analyticsData.overview.successRate}%
            </div>
            <div className="text-xs text-theme-secondary">{t('metrics.successRate')}</div>
          </CardContent>
        </Card>

        <Card className="bg-theme-secondary">
          <CardContent className="p-4">
            <div className="flex items-center justify-between mb-2">
              <div className="w-8 h-8 bg-yellow-100 rounded-lg flex items-center justify-center">
                <Clock className="w-4 h-4 text-yellow-600" />
              </div>
              <span className="text-xs text-red-500 flex items-center">
                <TrendingDown className="w-3 h-3 mr-1" />
                -15ms
              </span>
            </div>
            <div className="text-2xl font-bold text-theme-primary mb-1">
              {analyticsData.overview.avgResponseTime}ms
            </div>
            <div className="text-xs text-theme-secondary">{t('metrics.avgResponse')}</div>
          </CardContent>
        </Card>

        <Card className="bg-theme-secondary">
          <CardContent className="p-4">
            <div className="flex items-center justify-between mb-2">
              <div className="w-8 h-8 bg-purple-100 rounded-lg flex items-center justify-center">
                <Users className="w-4 h-4 text-purple-600" />
              </div>
              <span className="text-xs text-green-500 flex items-center">
                <TrendingUp className="w-3 h-3 mr-1" />
                +8%
              </span>
            </div>
            <div className="text-2xl font-bold text-theme-primary mb-1">
              {analyticsData.overview.activeUsers.toLocaleString(getClientLocale())}
            </div>
            <div className="text-xs text-theme-secondary">{t('metrics.activeUsers')}</div>
          </CardContent>
        </Card>

        <Card className="bg-theme-secondary">
          <CardContent className="p-4">
            <div className="flex items-center justify-between mb-2">
              <div className="w-8 h-8 bg-green-100 rounded-lg flex items-center justify-center">
                <DollarSign className="w-4 h-4 text-green-600" />
              </div>
              <span className="text-xs text-green-500 flex items-center">
                <TrendingUp className="w-3 h-3 mr-1" />
                +15%
              </span>
            </div>
            <div className="text-2xl font-bold text-theme-primary mb-1">
              ${analyticsData.overview.revenue.toLocaleString(getClientLocale())}
            </div>
            <div className="text-xs text-theme-secondary">{t('metrics.revenue')}</div>
          </CardContent>
        </Card>

        <Card className="bg-theme-secondary">
          <CardContent className="p-4">
            <div className="flex items-center justify-between mb-2">
              <div className="w-8 h-8 bg-blue-100 rounded-lg flex items-center justify-center">
                <Activity className="w-4 h-4 text-blue-600" />
              </div>
              <span className="text-xs text-green-500 flex items-center">
                <TrendingUp className="w-3 h-3 mr-1" />
                +0.1%
              </span>
            </div>
            <div className="text-2xl font-bold text-theme-primary mb-1">
              {analyticsData.overview.uptime}%
            </div>
            <div className="text-xs text-theme-secondary">{t('metrics.uptime')}</div>
          </CardContent>
        </Card>
      </div>

      {/* Graphiques principaux */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Graphique des requetes */}
        <Card className="bg-theme-secondary">
          <CardHeader>
            <div className="flex items-center justify-between">
              <CardTitle className="text-lg font-semibold text-theme-primary">{t('charts.requestVolume')}</CardTitle>
              <div className="flex items-center space-x-2">
                <div className="flex items-center space-x-1">
                  <div className="w-3 h-3 bg-blue-500 rounded-full"></div>
                  <span className="text-sm text-theme-secondary">{t('charts.requests')}</span>
                </div>
                <div className="flex items-center space-x-1">
                  <div className="w-3 h-3 bg-red-500 rounded-full"></div>
                  <span className="text-sm text-theme-secondary">{t('charts.errors')}</span>
                </div>
              </div>
            </div>
          </CardHeader>
          <CardContent className="p-6 pt-0">
            <div className="h-64 flex items-end space-x-1">
            {getTimeRangeData().slice(-24).map((data, index) => {
              const height = (data.requests / getMaxRequests()) * 100;
              const errorHeight = (data.errors / getMaxRequests()) * 100;
              return (
                <div key={index} className="flex-1 flex flex-col items-center space-y-1">
                  <div className="w-full flex flex-col items-end space-y-1">
                    <div 
                      className="w-full bg-blue-500 rounded-t"
                      style={{ height: `${height}%` }}
                    ></div>
                    <div 
                      className="w-full bg-red-500 rounded-b"
                      style={{ height: `${errorHeight}%` }}
                    ></div>
                  </div>
                  <div className="text-xs text-theme-secondary transform -rotate-45 origin-left">
                    {timeRange === '24h' ? data.hour : data.date?.split('-')[2]}
                  </div>
                </div>
              );
            })}
            </div>
          </CardContent>
        </Card>

        {/* Top Endpoints */}
        <Card className="bg-theme-secondary">
          <CardHeader>
            <CardTitle className="text-lg font-semibold text-theme-primary">{t('charts.topEndpoints')}</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              {analyticsData.topEndpoints.map((endpoint, index) => (
                <div key={index} className="flex items-center justify-between">
                  <div className="flex-1">
                    <div className="text-sm font-medium text-theme-primary font-mono">
                      {endpoint.endpoint}
                    </div>
                    <div className="text-xs text-theme-secondary">
                      {endpoint.requests.toLocaleString(getClientLocale())} requests • {endpoint.avgTime}ms avg
                    </div>
                  </div>
                  <div className="flex items-center space-x-3">
                    <div className="text-right">
                      <div className="text-sm font-medium text-theme-primary">
                        {endpoint.successRate}%
                      </div>
                      <div className="text-xs text-theme-secondary">{t('charts.success')}</div>
                    </div>
                    <div className="w-16 bg-theme-tertiary rounded-full h-2">
                      <div 
                        className="bg-green-500 h-2 rounded-full"
                        style={{ width: `${endpoint.successRate}%` }}
                      ></div>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Erreurs et Utilisateurs */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Erreurs */}
        <Card className="bg-theme-secondary">
          <CardHeader>
            <CardTitle className="text-lg font-semibold text-theme-primary">{t('errorAnalysis.title')}</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              {analyticsData.errors.map((error, index) => (
                <div key={index} className="flex items-center justify-between">
                  <div className="flex items-center space-x-3">
                    <div className={`w-8 h-8 rounded-lg flex items-center justify-center ${
                      error.code >= 500 ? 'bg-red-100' : 
                      error.code >= 400 ? 'bg-yellow-100' : 'bg-blue-100'
                    }`}>
                      <AlertTriangle className={`w-4 h-4 ${
                        error.code >= 500 ? 'text-red-600' : 
                        error.code >= 400 ? 'text-yellow-600' : 'text-blue-600'
                      }`} />
                    </div>
                    <div>
                      <div className="text-sm font-medium text-theme-primary">
                        {error.code} - {error.message}
                      </div>
                      <div className="text-xs text-theme-secondary">
                        {error.count} {t('errorAnalysis.occurrences')}
                      </div>
                    </div>
                  </div>
                  <div className="text-right">
                    <div className="text-sm font-medium text-theme-primary">
                      {error.percentage}%
                    </div>
                    <div className="w-16 bg-theme-tertiary rounded-full h-2 mt-1">
                      <div 
                        className="bg-red-500 h-2 rounded-full"
                        style={{ width: `${error.percentage * 10}%` }}
                      ></div>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>

        {/* Utilisateurs */}
        <Card className="bg-theme-secondary">
          <CardHeader>
            <CardTitle className="text-lg font-semibold text-theme-primary">{t('userAnalytics.title')}</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-6">
            <div className="grid grid-cols-2 gap-4">
              <div className="text-center">
                <div className="text-2xl font-bold text-theme-primary">
                  {analyticsData.users.newUsers}
                </div>
                <div className="text-sm text-theme-secondary">{t('userAnalytics.newUsers')}</div>
              </div>
              <div className="text-center">
                <div className="text-2xl font-bold text-theme-primary">
                  {analyticsData.users.activeUsers.toLocaleString(getClientLocale())}
                </div>
                <div className="text-sm text-theme-secondary">{t('userAnalytics.activeUsers')}</div>
              </div>
              <div className="text-center">
                <div className="text-2xl font-bold text-theme-primary">
                  {analyticsData.users.returningUsers.toLocaleString(getClientLocale())}
                </div>
                <div className="text-sm text-theme-secondary">{t('userAnalytics.returning')}</div>
              </div>
              <div className="text-center">
                <div className="text-2xl font-bold text-red-500">
                  {analyticsData.users.churnRate}%
                </div>
                <div className="text-sm text-theme-secondary">{t('userAnalytics.churnRate')}</div>
              </div>
            </div>
            
            <div className="pt-4 border-t border-theme/20">
              <div className="flex items-center justify-between mb-2">
                <span className="text-sm text-theme-secondary">{t('userAnalytics.userGrowth')}</span>
                <span className="text-sm text-green-500">+12%</span>
              </div>
              <div className="w-full bg-theme-tertiary rounded-full h-2">
                <div className="bg-green-500 h-2 rounded-full" style={{ width: '75%' }}></div>
              </div>
            </div>
          </div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
};

export default AnalyzeTab;
