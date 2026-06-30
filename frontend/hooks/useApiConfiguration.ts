import { useState, useCallback } from 'react';
import { ApiConfigurationRequest } from '@/types/apiConfiguration';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { useAuthGuard } from '@/hooks/useAuthGuard';

/**
 * Determine automatiquement le format de reponse base sur le contenu
 */
const determineResponseFormat = (example: string): string => {
  if (!example || example.trim() === '') {
    return 'JSON'; // Valeur par defaut
  }

  const trimmedExample = example.trim();

  // Detecter le format HTML
  if (trimmedExample.includes('<html') || trimmedExample.includes('<HTML') ||
      trimmedExample.includes('<body') || trimmedExample.includes('<BODY')) {
    return 'HTML';
  }

  // Detecter le format CSV (contient des virgules et des sauts de ligne)
  if (trimmedExample.includes(',') && trimmedExample.includes('\n')) {
    // Verifier si cela ressemble a du CSV (pas de crochets JSON)
    if (!trimmedExample.includes('{') && !trimmedExample.includes('[')) {
      return 'CSV';
    }
  }

  // Detecter le format XML
  if (trimmedExample.includes('<?xml') || trimmedExample.includes('<xml') ||
      (trimmedExample.includes('<') && trimmedExample.includes('>'))) {
    // Verifier que ce n'est pas du HTML (pas de balises HTML communes)
    if (!trimmedExample.includes('<html') && !trimmedExample.includes('<body') &&
        !trimmedExample.includes('<div') && !trimmedExample.includes('<span')) {
      return 'XML';
    }
  }

  // Detecter le format TEXT (contient principalement du texte)
  if (!trimmedExample.includes('{') && !trimmedExample.includes('[') &&
      !trimmedExample.includes('<') && !trimmedExample.includes(',')) {
    return 'TEXT';
  }

  // Par defaut, considerer comme JSON si contient des accolades ou crochets
  if (trimmedExample.includes('{') || trimmedExample.includes('[')) {
    return 'JSON';
  }

  // Si rien ne correspond, utiliser BINARY
  return 'BINARY';
};

export const useApiConfiguration = () => {
  const { isAuthenticated, isLoading: authLoading, getAccessTokenSilently } = useAuthGuard();
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<any | null>(null);

  const processApiConfiguration = useCallback(async (
    configuration: ApiConfigurationRequest,
    userId: string
  ) => {
    setIsLoading(true);
    setError(null);
    setResult(null);

    try {
      const accessToken = await getAccessTokenSilently();
      const response = await unifiedApiService.processApiConfiguration(configuration, userId, accessToken);

      // Si l'API est sauvegardee avec succes, envoyer les reponses des outils
      if (response.success && response.data) {
        await sendToolResponsesAfterApiSuccess(configuration, response.data, userId, undefined, undefined);
      }

      setResult(response);
      return response;
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to process API configuration';
      setError(errorMessage);
      throw err;
    } finally {
      setIsLoading(false);
    }
  }, [getAccessTokenSilently]);

  // Fonction pour envoyer les reponses des outils apres succes de l'API
  const sendToolResponsesAfterApiSuccess = useCallback(async (
    originalConfig: ApiConfigurationRequest,
    apiResult: any,
    userId: string,
    accessToken?: string,
    onProgress?: (current: number, total: number, currentTool?: string) => void
  ) => {
    if (!apiResult.tools || !Array.isArray(apiResult.tools)) {
      return;
    }


    const totalTools = originalConfig.mcpTools.length;
    let currentToolIndex = 0;

    // Pour chaque outil dans la configuration originale
    for (const tool of originalConfig.mcpTools) {
      currentToolIndex++;

      try {
        // Mettre a jour le progres
        if (onProgress) {
          onProgress(currentToolIndex, totalTools, tool.name);
        }

        // Trouver l'outil correspondant dans le resultat de l'API
        const savedTool = apiResult.tools.find((t: any) => t.name === tool.name);

        if (!savedTool || !savedTool.id) {
          continue;
        }

        // Determiner le format automatiquement
        const exampleContent = tool.response?.success ? JSON.stringify(tool.response.success) : '{}';
        const format = determineResponseFormat(exampleContent);

        // Preparer les donnees de reponse (seulement les champs requis)
        const responseData = {
          tool_id: savedTool.id,
          example: exampleContent,
          status_code: 200
        };


        // Envoyer la reponse avec le token d'authentification
        await unifiedApiService.createToolResponse(savedTool.id, responseData);


      } catch (error) {
        // Continue avec les autres outils meme si un echoue
      }
    }

  }, []);

  const getApiById = useCallback(async (apiId: string) => {
    setIsLoading(true);
    setError(null);

    try {
      const response = await unifiedApiService.getApiById(apiId);
      setResult(response);
      return response;
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to get API';
      setError(errorMessage);
      throw err;
    } finally {
      setIsLoading(false);
    }
  }, []);

  const getAllApis = useCallback(async () => {
    setIsLoading(true);
    setError(null);

    try {
      const response = await unifiedApiService.getUserApis();
      return response;
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to get APIs';
      setError(errorMessage);
      throw err;
    } finally {
      setIsLoading(false);
    }
  }, []);

  const clearError = useCallback(() => {
    setError(null);
  }, []);

  const clearResult = useCallback(() => {
    setResult(null);
  }, []);

  // Version avec callback de progres pour les reponses
  const processApiConfigurationWithProgress = useCallback(async (
    configuration: ApiConfigurationRequest,
    userId: string,
    onProgress?: (current: number, total: number, currentTool?: string) => void
  ) => {
    setIsLoading(true);
    setError(null);
    setResult(null);

    try {
      // Verifier que l'utilisateur est authentifie
      if (!isAuthenticated) {
        throw new Error('User must be authenticated to submit API');
      }

      // Attendre que l'authentification soit completement chargee
      if (authLoading) {
        await new Promise(resolve => setTimeout(resolve, 500));
      }

      const accessToken = await getAccessTokenSilently();

      const response = await unifiedApiService.processApiConfiguration(configuration, userId, accessToken);

      // 🚨 CRITIQUE : Si le service retourne success: false, lancer une exception !
      if (!response.success) {
        throw new Error(response.error || 'API configuration failed');
      }

      // Si l'API est sauvegardee avec succes, envoyer les reponses des outils
      if (response.success && response.data) {
        await sendToolResponsesAfterApiSuccess(configuration, response.data, userId, accessToken, onProgress);
      } else {
      }

      setResult(response);
      return response;
    } catch (err) {
      if (err instanceof Error) {
      }
      const errorMessage = err instanceof Error ? err.message : 'Failed to process API configuration';
      setError(errorMessage);
      throw err;
    } finally {
      setIsLoading(false);
    }
  }, [sendToolResponsesAfterApiSuccess, isAuthenticated, authLoading]);

  return {
    isLoading,
    error,
    result,
    processApiConfiguration,
    processApiConfigurationWithProgress,
    getApiById,
    getAllApis,
    clearError,
    clearResult
  };
};
