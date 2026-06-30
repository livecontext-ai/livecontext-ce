declare global {
  interface Window {
    oidc?: {
      getTokenSilently(): Promise<string>;
    };
  }
}

export {};
