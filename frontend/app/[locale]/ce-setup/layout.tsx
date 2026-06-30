import React from 'react';

// Force dynamic rendering so that NextIntlClientProvider messages are available at runtime
export const dynamic = 'force-dynamic';

export default function CeSetupLayout({ children }: { children: React.ReactNode }) {
  return <>{children}</>;
}
