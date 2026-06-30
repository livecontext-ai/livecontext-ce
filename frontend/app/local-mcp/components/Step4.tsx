import React, { useState } from 'react';
import { ChevronLeft, ChevronRight, Play, CheckCircle, XCircle, Clock } from 'lucide-react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { StatCard } from './common/StatCard';
import { TestResultItem } from './step4/TestResultItem';
import { LocalMcpTool } from '../types';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useTranslations } from 'next-intl';

interface Step4Props {
  mcpTools: LocalMcpTool[];
  testAllTools: () => Promise<void>;
  goToPrevStep: () => void;
  goToNextStep: () => void;
}

export default function Step4({
  mcpTools,
  testAllTools,
  goToPrevStep,
  goToNextStep
}: Step4Props) {
  const [isTesting, setIsTesting] = useState(false);
  const t = useTranslations('localMcp');
  // Test results are now stored in mcpTools props

  const handleTestAll = async () => {
    setIsTesting(true);
    try {
      await testAllTools();
      // Results will be updated via mcpTools props
    } catch (error) {
      console.error('Error during tests:', error);
    } finally {
      setIsTesting(false);
    }
  };

  const getTestStatusIcon = (tool: LocalMcpTool) => {
    if (!tool.testStatus || tool.testStatus === 'PENDING') {
      return <Clock className="w-5 h-5 text-gray-500" />;
    }
    if (tool.testStatus === 'SUCCESS') {
      return <CheckCircle className="w-5 h-5 text-green-500" />;
    }
    return <XCircle className="w-5 h-5 text-red-500" />;
  };

  const getTestStatusText = (tool: LocalMcpTool) => {
    if (!tool.testStatus) return t('step4.testStatus.notTested');
    switch (tool.testStatus) {
      case 'PENDING': return t('step4.testStatus.pending');
      case 'SUCCESS': return t('step4.testStatus.success');
      case 'ERROR': return t('step4.testStatus.failed');
      case 'TIMEOUT': return t('step4.testStatus.timeout');
      default: return t('step4.testStatus.unknown');
    }
  };

  const getTestStatusColor = (tool: LocalMcpTool) => {
    if (!tool.testStatus || tool.testStatus === 'PENDING') return 'text-gray-500';
    if (tool.testStatus === 'SUCCESS') return 'text-green-600';
    return 'text-red-600';
  };

  const allToolsTested = mcpTools.every(tool => tool.testStatus && tool.testStatus !== 'PENDING');
  const allTestsSuccessful = mcpTools.every(tool => tool.testStatus === 'SUCCESS');
  const successfulTests = mcpTools.filter(tool => tool.testStatus === 'SUCCESS').length;
  const failedTests = mcpTools.filter(tool => tool.testStatus === 'ERROR' || tool.testStatus === 'TIMEOUT').length;

  return (
    <div className="space-y-8">
      {/* Test summary */}
      <Card className="bg-theme-secondary border-theme">
        <CardHeader>
          <CardTitle className="text-theme-primary">{t('step4.title')}</CardTitle>
          <CardDescription className="text-theme-secondary">
            {t('step4.description')}
          </CardDescription>
        </CardHeader>
        
        <CardContent>
          <div className="grid grid-cols-1 md:grid-cols-4 gap-4 mb-6">
            <StatCard label={t('step4.stats.totalTools')} value={mcpTools.length} />
            <StatCard label={t('step4.stats.successfulTests')} value={successfulTests} accent="success" />
            <StatCard label={t('step4.stats.failedTests')} value={failedTests} accent="danger" />
            <StatCard
              label={t('step4.stats.successRate')}
              value={`${mcpTools.length > 0 ? Math.round((successfulTests / mcpTools.length) * 100) : 0}%`}
            />
          </div>

          <div className="flex justify-center">
            <Button 
              onClick={handleTestAll}
              disabled={isTesting}
              className="bg-blue-600 hover:bg-blue-700 text-white"
              size="lg"
            >
              {isTesting ? (
                <>
                  <LoadingSpinner size="sm" className="mr-2" />
                  {t('step4.testsInProgress')}
                </>
              ) : (
                <>
                  <Play className="w-4 h-4 mr-2" />
                  {t('step4.testAllTools')}
                </>
              )}
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* Test results by tool */}
      <Card className="bg-theme-secondary border-theme">
        <CardHeader>
          <CardTitle className="text-theme-primary">{t('step4.resultsTitle')}</CardTitle>
          <CardDescription className="text-theme-secondary">
            {t('step4.resultsDescription')}
          </CardDescription>
        </CardHeader>
        
        <CardContent>
          <div className="space-y-4">
            {mcpTools.map((tool) => (
              <TestResultItem
                key={tool.id}
                tool={tool}
                statusIcon={getTestStatusIcon(tool)}
                statusText={getTestStatusText(tool)}
                statusColor={getTestStatusColor(tool)}
              />
            ))}
          </div>
        </CardContent>
      </Card>

      {/* Recommendations and warnings */}
      {allToolsTested && (
        <Card className="bg-theme-secondary border-theme">
          <CardHeader>
            <CardTitle className="text-theme-primary">{t('step4.recommendations')}</CardTitle>
          </CardHeader>
          
          <CardContent>
            {allTestsSuccessful ? (
              <Alert className="border-green-200 bg-green-50 dark:bg-green-900/30">
                <CheckCircle className="h-4 w-4 text-green-600" />
                <AlertDescription className="text-green-800 dark:text-green-200">
                  <div className="font-medium mb-2">{t('step4.allTestsPassed')}</div>
                  <p>{t('step4.readyToDeploy')}</p>
                </AlertDescription>
              </Alert>
            ) : failedTests > 0 ? (
              <Alert className="border-yellow-200 bg-yellow-50 dark:bg-yellow-900/30">
                <XCircle className="h-4 w-4 text-yellow-600" />
                <AlertDescription className="text-yellow-800 dark:text-yellow-200">
                  <div className="font-medium mb-2">{t('step4.someTestsFailed', { count: failedTests })}</div>
                  <ul className="list-disc list-inside space-y-1">
                    <li>{t('step4.failedHints.checkCommands')}</li>
                    <li>{t('step4.failedHints.checkPaths')}</li>
                    <li>{t('step4.failedHints.checkPermissions')}</li>
                    <li>{t('step4.failedHints.testManually')}</li>
                  </ul>
                  <p className="mt-2">{t('step4.canContinue')}</p>
                </AlertDescription>
              </Alert>
            ) : (
              <Alert>
                <Clock className="h-4 w-4" />
                <AlertDescription>
                  <div className="font-medium mb-2">{t('step4.testsPending')}</div>
                  <p>{t('step4.clickToTest')}</p>
                </AlertDescription>
              </Alert>
            )}
          </CardContent>
        </Card>
      )}

      {/* Navigation */}
      <div className="flex justify-between">
        <Button 
          onClick={goToPrevStep}
          variant="outline"
          className="bg-theme-tertiary border-theme text-theme-primary"
        >
          <ChevronLeft className="w-4 h-4 mr-2" />
          {t('navigation.previous')}
        </Button>

        <Button
          onClick={goToNextStep}
          className="bg-blue-600 hover:bg-blue-700 text-white"
        >
          {t('navigation.finalize')}
          <ChevronRight className="w-4 h-4 ml-2" />
        </Button>
      </div>
    </div>
  );
}
