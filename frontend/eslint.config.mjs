import nextCoreWebVitals from 'eslint-config-next/core-web-vitals';
import nextTypescript from 'eslint-config-next/typescript';

const restrictedEditionEnvSelectors = [
  {
    selector:
      "MemberExpression[object.object.name='process'][object.property.name='env'][property.name='NEXT_PUBLIC_AUTH_MODE']",
    message:
      "Do not read NEXT_PUBLIC_AUTH_MODE directly. Import IS_CE or IS_CLOUD from '@/lib/edition'.",
  },
  {
    selector:
      "MemberExpression[object.object.name='process'][object.property.name='env'][property.name='NEXT_PUBLIC_APP_EDITION']",
    message:
      "Do not read NEXT_PUBLIC_APP_EDITION directly. Import IS_CE or IS_CLOUD from '@/lib/edition'.",
  },
  {
    selector:
      "MemberExpression[object.object.name='process'][object.property.name='env'][computed=true][property.value=/^NEXT_PUBLIC_(AUTH_MODE|APP_EDITION)$/]",
    message:
      "Do not read NEXT_PUBLIC_AUTH_MODE or NEXT_PUBLIC_APP_EDITION via bracket access. Import IS_CE or IS_CLOUD from '@/lib/edition'.",
  },
  {
    selector:
      "VariableDeclarator[init.object.name='process'][init.property.name='env'] > ObjectPattern > Property[key.name=/^NEXT_PUBLIC_(AUTH_MODE|APP_EDITION)$/]",
    message:
      "Do not destructure NEXT_PUBLIC_AUTH_MODE or NEXT_PUBLIC_APP_EDITION from process.env. Import IS_CE or IS_CLOUD from '@/lib/edition'.",
  },
];

export default [
  ...nextCoreWebVitals,
  ...nextTypescript,
  {
    ignores: [
      '.next/**',
      'coverage/**',
      'playwright-report/**',
      'test-results/**',
      'node_modules/**',
    ],
  },
  {
    rules: {
      '@typescript-eslint/no-explicit-any': 'warn',
      '@typescript-eslint/no-require-imports': 'warn',
      '@typescript-eslint/no-unused-vars': 'warn',
      '@typescript-eslint/no-empty-object-type': 'warn',
      '@typescript-eslint/no-non-null-asserted-optional-chain': 'warn',
      'prefer-const': 'warn',
      'react/no-unescaped-entities': 'warn',
      'react/display-name': 'warn',
      'react/jsx-no-duplicate-props': 'warn',
      'react/jsx-no-undef': 'warn',
      'react-hooks/capitalized-calls': 'warn',
      'react-hooks/component-hook-factories': 'warn',
      'react-hooks/config': 'warn',
      'react-hooks/error-boundaries': 'warn',
      'react-hooks/exhaustive-deps': 'warn',
      'react-hooks/exhaustive-effect-dependencies': 'warn',
      'react-hooks/fbt': 'warn',
      'react-hooks/gating': 'warn',
      'react-hooks/globals': 'warn',
      'react-hooks/hooks': 'warn',
      'react-hooks/immutability': 'warn',
      'react-hooks/incompatible-library': 'warn',
      'react-hooks/invariant': 'warn',
      'react-hooks/memo-dependencies': 'warn',
      'react-hooks/memoized-effect-dependencies': 'warn',
      'react-hooks/no-deriving-state-in-effects': 'warn',
      'react-hooks/preserve-manual-memoization': 'warn',
      'react-hooks/purity': 'warn',
      'react-hooks/refs': 'warn',
      'react-hooks/rule-suppression': 'warn',
      'react-hooks/rules-of-hooks': 'warn',
      'react-hooks/set-state-in-effect': 'warn',
      'react-hooks/set-state-in-render': 'warn',
      'react-hooks/static-components': 'warn',
      'react-hooks/syntax': 'warn',
      'react-hooks/todo': 'warn',
      'react-hooks/unsupported-syntax': 'warn',
      'react-hooks/use-memo': 'warn',
      'react-hooks/void-use-memo': 'warn',
      '@next/next/no-assign-module-variable': 'warn',
      '@next/next/no-html-link-for-pages': 'warn',
    },
  },
  {
    files: ['**/*.{ts,tsx}'],
    ignores: ['lib/edition/**', 'e2e/**', '**/*.test.ts', '**/*.test.tsx'],
    rules: {
      'no-restricted-syntax': ['error', ...restrictedEditionEnvSelectors],
    },
  },
];
