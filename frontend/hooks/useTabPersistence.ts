import { useState, useEffect } from 'react';

type TabId = string;

interface UseTabPersistenceOptions {
  apiId: string;
  defaultTab: TabId;
  validTabs: TabId[];
  storageKey?: string;
}

/**
 * Hook personnalise pour gerer la persistance des onglets dans localStorage
 * @param apiId - ID de l'API pour creer une cle unique
 * @param defaultTab - Onglet par defaut
 * @param validTabs - Liste des onglets valides
 * @param storageKey - Cle personnalisee pour le localStorage (optionnel)
 * @returns [currentTab, setCurrentTab] - etat et setter pour l'onglet actuel
 */
export const useTabPersistence = ({
  apiId,
  defaultTab,
  validTabs,
  storageKey
}: UseTabPersistenceOptions): [TabId, (tabId: TabId) => void] => {
  const key = storageKey || `api-tab-${apiId}`;
  
  const [currentTab, setCurrentTabState] = useState<TabId>(() => {
    // Initialiser avec l'onglet sauvegarde ou l'onglet par defaut
    if (typeof window !== 'undefined') {
      const savedTab = localStorage.getItem(key);
      if (savedTab && validTabs.includes(savedTab)) {
        return savedTab;
      }
    }
    return defaultTab;
  });

  // Fonction pour changer d'onglet et sauvegarder
  const setCurrentTab = (tabId: TabId) => {
    setCurrentTabState(tabId);
    if (typeof window !== 'undefined') {
      localStorage.setItem(key, tabId);
    }
  };

  // Effet pour gerer le changement d'API
  useEffect(() => {
    if (apiId && typeof window !== 'undefined') {
      const savedTab = localStorage.getItem(key);
      if (savedTab && validTabs.includes(savedTab)) {
        setCurrentTabState(savedTab);
      } else {
        setCurrentTabState(defaultTab);
      }
    }
  }, [apiId, key, validTabs, defaultTab]);

  return [currentTab, setCurrentTab];
};

export default useTabPersistence;
