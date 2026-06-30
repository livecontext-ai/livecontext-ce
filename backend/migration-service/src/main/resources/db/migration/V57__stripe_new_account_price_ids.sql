-- V57: Switch Stripe provider_price_id to the new Livecontext account (acct_1TJtmp1MnvbO0ZY3)
-- Old account is decommissioned. These UPDATEs are idempotent: running on a DB that already
-- has the new IDs (manually applied) is a no-op.

UPDATE auth.price SET provider_price_id='price_1TJu021MnvbO0ZY3V4TC9R2M' WHERE plan_id=(SELECT id FROM auth.plan WHERE code='STARTER')             AND cadence='monthly';
UPDATE auth.price SET provider_price_id='price_1TJu031MnvbO0ZY3EfphGLd4' WHERE plan_id=(SELECT id FROM auth.plan WHERE code='STARTER')             AND cadence='yearly';
UPDATE auth.price SET provider_price_id='price_1TJu031MnvbO0ZY3IIcD0eeb' WHERE plan_id=(SELECT id FROM auth.plan WHERE code='PRO')                 AND cadence='monthly';
UPDATE auth.price SET provider_price_id='price_1TJu031MnvbO0ZY32BZ1Wdzz' WHERE plan_id=(SELECT id FROM auth.plan WHERE code='PRO')                 AND cadence='yearly';
UPDATE auth.price SET provider_price_id='price_1TJu041MnvbO0ZY3mWhg8Svq' WHERE plan_id=(SELECT id FROM auth.plan WHERE code='TEAM')                AND cadence='monthly';
UPDATE auth.price SET provider_price_id='price_1TJu041MnvbO0ZY3IGfoL5gt' WHERE plan_id=(SELECT id FROM auth.plan WHERE code='TEAM')                AND cadence='yearly';
UPDATE auth.price SET provider_price_id='price_1TJu041MnvbO0ZY315E9GtUI' WHERE plan_id=(SELECT id FROM auth.plan WHERE code='ENTERPRISE_BASIC')    AND cadence='monthly';
UPDATE auth.price SET provider_price_id='price_1TJu051MnvbO0ZY3DBxmlTrT' WHERE plan_id=(SELECT id FROM auth.plan WHERE code='ENTERPRISE_BASIC')    AND cadence='yearly';
UPDATE auth.price SET provider_price_id='price_1TJu051MnvbO0ZY359X5raS5' WHERE plan_id=(SELECT id FROM auth.plan WHERE code='ENTERPRISE_STANDARD') AND cadence='monthly';
UPDATE auth.price SET provider_price_id='price_1TJu051MnvbO0ZY33VeATH5g' WHERE plan_id=(SELECT id FROM auth.plan WHERE code='ENTERPRISE_STANDARD') AND cadence='yearly';
UPDATE auth.price SET provider_price_id='price_1TJu061MnvbO0ZY3f9IgiLVD' WHERE plan_id=(SELECT id FROM auth.plan WHERE code='ENTERPRISE_PREMIUM')  AND cadence='monthly';
UPDATE auth.price SET provider_price_id='price_1TJu061MnvbO0ZY3XJggVlmw' WHERE plan_id=(SELECT id FROM auth.plan WHERE code='ENTERPRISE_PREMIUM')  AND cadence='yearly';
UPDATE auth.price SET provider_price_id='price_1TJu061MnvbO0ZY339G2QNOV' WHERE plan_id=(SELECT id FROM auth.plan WHERE code='ENTERPRISE_ULTIMATE') AND cadence='monthly';
UPDATE auth.price SET provider_price_id='price_1TJu071MnvbO0ZY3hurAws9i' WHERE plan_id=(SELECT id FROM auth.plan WHERE code='ENTERPRISE_ULTIMATE') AND cadence='yearly';
UPDATE auth.price SET provider_price_id='price_1TJu071MnvbO0ZY3lDJ60M1v' WHERE plan_id=(SELECT id FROM auth.plan WHERE code='CREDIT_PACK')         AND cadence='monthly';
UPDATE auth.price SET provider_price_id='price_1TJu081MnvbO0ZY3CXZwEHRJ' WHERE plan_id=(SELECT id FROM auth.plan WHERE code='CREDIT_PACK')         AND cadence='yearly';
UPDATE auth.price SET provider_price_id='price_1TJu081MnvbO0ZY3zuTdw4ir' WHERE plan_id=(SELECT id FROM auth.plan WHERE code='CREDIT_PACK_TEAM')    AND cadence='monthly';
UPDATE auth.price SET provider_price_id='price_1TJu081MnvbO0ZY3NMnpMn6x' WHERE plan_id=(SELECT id FROM auth.plan WHERE code='CREDIT_PACK_TEAM')    AND cadence='yearly';
