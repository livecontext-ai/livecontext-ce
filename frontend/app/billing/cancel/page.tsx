'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { XCircle, ArrowLeft, RefreshCw, HelpCircle } from 'lucide-react';
import Link from 'next/link';
import { useTranslations } from 'next-intl';

export default function BillingCancelPage() {
  const router = useRouter();
  const [countdown, setCountdown] = useState(10);
  const t = useTranslations('billing');

  useEffect(() => {
    // Automatic redirect countdown.
    const timer = setInterval(() => {
      setCountdown((prev) => {
        if (prev <= 1) {
          clearInterval(timer);
          router.push('/app/settings/pricing');
        }
        return prev - 1;
      });
    }, 1000);

    return () => clearInterval(timer);
  }, [router]);

  return (
    <div className="min-h-screen bg-gradient-to-br from-red-50 to-orange-50 dark:from-gray-900 dark:to-gray-800 flex items-center justify-center p-4">
      <div className="max-w-2xl w-full bg-white dark:bg-gray-800 rounded-3xl shadow-2xl dark:shadow-gray-900/50 p-8 text-center">
        {/* Icône d'annulation */}
        <div className="w-24 h-24 bg-red-100 dark:bg-red-900/30 rounded-full flex items-center justify-center mx-auto mb-6">
          <XCircle className="w-12 h-12 text-red-600 dark:text-red-400" />
        </div>

        {/* Titre */}
        <h1 className="text-4xl font-bold text-gray-900 dark:text-gray-100 mb-4">
          {t('cancel.title')}
        </h1>

                  {/* Cancellation message */}
        <p className="text-xl text-gray-600 dark:text-gray-400 mb-8">
          {t('cancel.subtitle')}
        </p>

        {/* Informations utiles */}
        <div className="bg-gray-50 dark:bg-gray-700/50 rounded-2xl p-6 mb-8">
          <h3 className="text-lg font-semibold text-gray-900 dark:text-gray-100 mb-4">
            {t('cancel.whatHappened')}
          </h3>
          <div className="space-y-3 text-left text-gray-700 dark:text-gray-300">
            <div className="flex items-start gap-3">
              <div className="w-2 h-2 bg-red-400 dark:bg-red-500 rounded-full mt-2 flex-shrink-0"></div>
              <span>{t('cancel.reason1')}</span>
            </div>
            <div className="flex items-start gap-3">
              <div className="w-2 h-2 bg-red-400 dark:bg-red-500 rounded-full mt-2 flex-shrink-0"></div>
              <span>{t('cancel.reason2')}</span>
            </div>
            <div className="flex items-start gap-3">
              <div className="w-2 h-2 bg-red-400 dark:bg-red-500 rounded-full mt-2 flex-shrink-0"></div>
              <span>{t('cancel.reason3')}</span>
            </div>
          </div>
        </div>

        {/* Solutions */}
        <div className="bg-blue-50 dark:bg-blue-900/20 rounded-2xl p-6 mb-8 border border-blue-200 dark:border-blue-800">
          <h3 className="text-lg font-semibold text-gray-900 dark:text-gray-100 mb-4">
            {t('cancel.whatToDo')}
          </h3>
          <div className="space-y-3 text-left text-gray-700 dark:text-gray-300">
            <div className="flex items-center gap-3">
              <RefreshCw className="w-5 h-5 text-blue-600 dark:text-blue-400" />
              <span>{t('cancel.action1')}</span>
            </div>
            <div className="flex items-center gap-3">
              <HelpCircle className="w-5 h-5 text-blue-600 dark:text-blue-400" />
              <span>{t('cancel.action2')}</span>
            </div>
          </div>
        </div>

        {/* Boutons d'action */}
        <div className="flex flex-col sm:flex-row gap-4 justify-center">
          <Link
            href="/app/settings/pricing"
            className="inline-flex items-center px-8 py-4 bg-blue-600 hover:bg-blue-700 dark:bg-blue-600 dark:hover:bg-blue-500 text-white font-semibold rounded-xl transition-all duration-300 shadow-lg hover:shadow-xl"
          >
            <ArrowLeft className="w-5 h-5 mr-2" />
            {t('cancel.backToPricing')}
          </Link>

          <Link
            href="/contact"
            className="inline-flex items-center px-8 py-4 bg-gray-100 dark:bg-gray-700 text-gray-700 dark:text-gray-200 font-semibold rounded-xl hover:bg-surface-hover transition-all duration-300"
          >
            {t('cancel.contactSupport')}
          </Link>
        </div>

        {/* Compte a rebours */}
        <p className="text-sm text-gray-500 dark:text-gray-400 mt-6">
          {t('cancel.redirecting', { seconds: countdown })}
        </p>

        {/* Note de securite */}
        <div className="mt-8 p-4 bg-green-50 dark:bg-green-900/20 rounded-xl border border-green-200 dark:border-green-800">
          <p className="text-sm text-green-800 dark:text-green-300">
            {t('cancel.securityNote')}
          </p>
        </div>
      </div>
    </div>
  );
}
