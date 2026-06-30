import React, { useMemo } from 'react';
import { TestTabProps } from './types';
import { generateCodeExamples } from '../../utils';

const LANGUAGE_OPTIONS = [
  { value: 'curl', label: 'cURL' },
  { value: 'javascript', label: 'JavaScript (fetch)' },
  { value: 'python', label: 'Python (requests)' },
  { value: 'java', label: 'Java (HttpClient)' },
  { value: 'php', label: 'PHP (cURL)' },
  { value: 'nodejs', label: 'Node.js (https)' },
  { value: 'go', label: 'Go (net/http)' },
  { value: 'ruby', label: 'Ruby (net/http)' },
  { value: 'shell', label: 'Shell (bash)' }
] as const;

const LANG_CONFIGS = {
  curl: { color: 'text-green-400', name: 'cURL' },
  javascript: { color: 'text-yellow-400', name: 'JavaScript (fetch)' },
  python: { color: 'text-blue-400', name: 'Python (requests)' },
  java: { color: 'text-orange-500', name: 'Java (HttpClient)' },
  php: { color: 'text-purple-400', name: 'PHP (cURL)' },
  nodejs: { color: 'text-green-500', name: 'Node.js (https)' },
  go: { color: 'text-cyan-400', name: 'Go (net/http)' },
  ruby: { color: 'text-red-400', name: 'Ruby (net/http)' },
  shell: { color: 'text-gray-400', name: 'Shell (bash)' }
} as const;

const TestTab: React.FC<TestTabProps> = ({
  tool,
  toolIndex,
  apiConfig,
  selectedLanguage,
  onLanguageChange
}) => {
  const currentCode = useMemo(() => {
    return generateCodeExamples(tool, apiConfig, selectedLanguage);
  }, [tool, apiConfig, selectedLanguage]);

  const currentConfig = LANG_CONFIGS[selectedLanguage as keyof typeof LANG_CONFIGS] || LANG_CONFIGS.curl;

  const handleCopy = () => {
    navigator.clipboard.writeText(currentCode);
  };

  return (
    <div>
      {/* Preview of requests in different languages */}
      <div className="mb-4">
        <div className="flex items-center justify-between mb-2">
          <label className="block text-sm font-medium text-theme-primary">
            Request Preview
          </label>
          <select
            value={selectedLanguage}
            onChange={(e) => onLanguageChange(e.target.value)}
            className="px-3 py-1 bg-theme-primary border border-theme rounded text-sm text-theme-primary focus:outline-none focus:ring-1 focus:ring-blue-500"
          >
            {LANGUAGE_OPTIONS.map(({ value, label }) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
        </div>

        <div className={`bg-gray-900 ${currentConfig.color} p-3 rounded-lg font-mono text-sm`}>
          <div className="flex items-center justify-between mb-2">
            <span className="text-white font-medium">{currentConfig.name}</span>
            <button
              type="button"
              onClick={handleCopy}
              className="text-xs bg-gray-700 hover:bg-gray-600 text-white px-2 py-1 rounded transition-colors"
            >
              Copy
            </button>
          </div>
          <pre className="whitespace-pre-wrap break-all">{currentCode}</pre>
        </div>
      </div>
    </div>
  );
};

export default TestTab;
