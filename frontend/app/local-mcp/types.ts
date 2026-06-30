// Types pour les outils MCP locaux

export interface LocalMcpTool {
  id: string;
  userId: string;
  name: string;
  slug: string;
  description: string;
  category: string;
  subcategory: string;
  toolCategory: string;
  toolType: 'LOCAL_COMMAND' | 'LOCAL_PYTHON' | 'LOCAL_NODEJS' | 'LOCAL_EXECUTABLE' | 'LOCAL_API' | 'LOCAL_DATABASE';
  command: string;
  workingDirectory?: string;
  environmentVariables?: string;
  inputSchema: string; // JSON schema
  outputSchema: string; // JSON schema
  parameters: string; // JSON array
  headers?: string; // JSON array
  version: string;
  documentation?: string;
  rateLimit?: string;
  pricing: 'FREE' | 'FREEMIUM' | 'PAID';
  status: 'DRAFT' | 'TESTING' | 'ACTIVE' | 'INACTIVE' | 'ERROR';
  testStatus?: 'PENDING' | 'SUCCESS' | 'ERROR' | 'TIMEOUT';
  testResult?: string;
  lastTestTime?: number;
  testResponseTime?: number;
  isActive: boolean;
  isPublic: boolean;
  executionCount: number;
  successCount: number;
  errorCount: number;
  avgExecutionTime?: number;
  createdAt: number;
  updatedAt: number;
}

export interface McpParameter {
  name: string;
  type: 'string' | 'number' | 'boolean' | 'object' | 'array';
  description?: string;
  required?: boolean;
  defaultValue?: string | number | boolean;
  enum?: string[];
  format?: string;
}

export interface McpHeader {
  name: string;
  value: string;
  description?: string;
}

export interface McpTestResult {
  success: boolean;
  output?: string;
  error?: string;
  executionTime: number;
  testStatus: string;
}

export interface McpToolStats {
  totalTools: number;
  activeTools: number;
  draftTools: number;
  totalExecutions: number;
  avgExecutionTime: number;
}

// Constantes pour les outils locaux MCP
export const LOCAL_MCP_CATEGORIES = [
  {
    id: 'productivity',
    name: 'Productivite',
    description: 'Outils pour ameliorer la productivite',
    subcategories: [
      { id: 'notes', name: 'Prise de notes' },
      { id: 'email', name: 'Email' },
      { id: 'calendar', name: 'Calendrier' },
      { id: 'tasks', name: 'Gestion de tâches' },
      { id: 'messaging', name: 'Messagerie' }
    ]
  },
  {
    id: 'automation',
    name: 'Automatisation',
    description: 'Workflows et automatisation',
    subcategories: [
      { id: 'workflows', name: 'Workflows' },
      { id: 'webhooks', name: 'Webhooks' },
      { id: 'scheduled', name: 'Tâches programmees' },
      { id: 'triggers', name: 'Declencheurs' }
    ]
  },
  {
    id: 'database',
    name: 'Base de Donnees',
    description: 'Outils de gestion de donnees',
    subcategories: [
      { id: 'sql', name: 'SQL' },
      { id: 'nosql', name: 'NoSQL' },
      { id: 'backup', name: 'Sauvegarde' },
      { id: 'migration', name: 'Migration' }
    ]
  },
  {
    id: 'development',
    name: 'Developpement',
    description: 'Outils de developpement',
    subcategories: [
      { id: 'git', name: 'Git' },
      { id: 'containers', name: 'Conteneurs' },
      { id: 'deployment', name: 'Deploiement' },
      { id: 'testing', name: 'Tests' },
      { id: 'monitoring', name: 'Monitoring' }
    ]
  },
  {
    id: 'api',
    name: 'APIs',
    description: 'Integrations API',
    subcategories: [
      { id: 'rest', name: 'REST' },
      { id: 'graphql', name: 'GraphQL' },
      { id: 'soap', name: 'SOAP' },
      { id: 'webhook', name: 'Webhooks' }
    ]
  },
  {
    id: 'data',
    name: 'Donnees',
    description: 'Traitement et analyse',
    subcategories: [
      { id: 'analysis', name: 'Analyse' },
      { id: 'visualization', name: 'Visualisation' },
      { id: 'etl', name: 'ETL' },
      { id: 'scraping', name: 'Scraping' }
    ]
  }
];

export const TOOL_CATEGORIES = [
  'Productivite',
  'Communication', 
  'Automatisation',
  'Base de Donnees',
  'Developpement',
  'APIs',
  'Monitoring',
  'Securite',
  'Analyse',
  'Integration',
  'Autres'
];

export const POPULAR_MCP_TEMPLATES = [
  {
    name: 'Notion - Creer une page',
    slug: 'notion-create-page',
    description: 'Cree une nouvelle page dans votre workspace Notion',
    category: 'productivity',
    subcategory: 'notes',
    toolCategory: 'Productivite',
    toolType: 'LOCAL_PYTHON' as const,
    command: 'python notion_create_page.py',
    inputSchema: JSON.stringify({
      type: 'object',
      properties: {
        title: { type: 'string', description: 'Titre de la page' },
        content: { type: 'string', description: 'Contenu de la page' },
        parent_id: { type: 'string', description: 'ID de la page parent' }
      },
      required: ['title']
    }),
    outputSchema: JSON.stringify({
      type: 'object',
      properties: {
        success: { type: 'boolean' },
        page_id: { type: 'string' },
        url: { type: 'string' }
      }
    }),
    parameters: JSON.stringify([
      { name: 'title', type: 'string', description: 'Titre de la page', required: true },
      { name: 'content', type: 'string', description: 'Contenu de la page' },
      { name: 'parent_id', type: 'string', description: 'ID de la page parent' }
    ]),
    documentation: 'Cet outil utilise l\'API Notion pour creer des pages. Assurez-vous d\'avoir configure votre token Notion.',
    icon: '📝'
  },
  {
    name: 'Gmail - Envoyer email',
    slug: 'gmail-send-email',
    description: 'Envoie un email via l\'API Gmail',
    category: 'productivity',
    subcategory: 'email',
    toolCategory: 'Communication',
    toolType: 'LOCAL_PYTHON' as const,
    command: 'python send_gmail.py',
    inputSchema: JSON.stringify({
      type: 'object',
      properties: {
        to: { type: 'string', description: 'Adresse email du destinataire' },
        subject: { type: 'string', description: 'Sujet de l\'email' },
        body: { type: 'string', description: 'Corps de l\'email' },
        html: { type: 'boolean', description: 'Format HTML' }
      },
      required: ['to', 'subject', 'body']
    }),
    outputSchema: JSON.stringify({
      type: 'object',
      properties: {
        success: { type: 'boolean' },
        message_id: { type: 'string' }
      }
    }),
    parameters: JSON.stringify([
      { name: 'to', type: 'string', description: 'Adresse email du destinataire', required: true },
      { name: 'subject', type: 'string', description: 'Sujet de l\'email', required: true },
      { name: 'body', type: 'string', description: 'Corps de l\'email', required: true },
      { name: 'html', type: 'boolean', description: 'Format HTML', defaultValue: false }
    ]),
    documentation: 'Utilise l\'API Gmail pour envoyer des emails. Configuration OAuth2 requise.',
    icon: '📧'
  },
  {
    name: 'PostgreSQL - Requete',
    slug: 'postgresql-query',
    description: 'Execute une requete sur PostgreSQL',
    category: 'database',
    subcategory: 'sql',
    toolCategory: 'Base de Donnees',
    toolType: 'LOCAL_DATABASE' as const,
    command: 'psql -h localhost -U user -d database -c',
    inputSchema: JSON.stringify({
      type: 'object',
      properties: {
        query: { type: 'string', description: 'Requete SQL a executer' },
        limit: { type: 'number', description: 'Limite de resultats' }
      },
      required: ['query']
    }),
    outputSchema: JSON.stringify({
      type: 'object',
      properties: {
        success: { type: 'boolean' },
        rows: { type: 'array' },
        count: { type: 'number' }
      }
    }),
    parameters: JSON.stringify([
      { name: 'query', type: 'string', description: 'Requete SQL a executer', required: true },
      { name: 'limit', type: 'number', description: 'Limite de resultats', defaultValue: 100 }
    ]),
    documentation: 'Execute des requetes SELECT sur PostgreSQL. Configuration de connexion requise.',
    icon: '🗄️'
  },
  {
    name: 'Docker - Status',
    slug: 'docker-status',
    description: 'Affiche le statut des conteneurs Docker',
    category: 'development',
    subcategory: 'containers',
    toolCategory: 'Developpement',
    toolType: 'LOCAL_COMMAND' as const,
    command: 'docker ps --format "table {{.Names}}\\t{{.Status}}\\t{{.Ports}}"',
    inputSchema: JSON.stringify({
      type: 'object',
      properties: {
        all: { type: 'boolean', description: 'Afficher tous les conteneurs' }
      }
    }),
    outputSchema: JSON.stringify({
      type: 'object',
      properties: {
        success: { type: 'boolean' },
        containers: { type: 'array' }
      }
    }),
    parameters: JSON.stringify([
      { name: 'all', type: 'boolean', description: 'Afficher tous les conteneurs (meme arretes)', defaultValue: false }
    ]),
    documentation: 'Liste les conteneurs Docker avec leur statut. Necessite Docker installe.',
    icon: '🐳'
  },
  {
    name: 'Slack - Notification',
    slug: 'slack-notify',
    description: 'Envoie une notification Slack',
    category: 'productivity',
    subcategory: 'messaging',
    toolCategory: 'Communication',
    toolType: 'LOCAL_API' as const,
    command: 'curl -X POST $SLACK_WEBHOOK -H "Content-Type: application/json" -d',
    inputSchema: JSON.stringify({
      type: 'object',
      properties: {
        message: { type: 'string', description: 'Message a envoyer' },
        channel: { type: 'string', description: 'Canal Slack' },
        username: { type: 'string', description: 'Nom d\'utilisateur' }
      },
      required: ['message']
    }),
    outputSchema: JSON.stringify({
      type: 'object',
      properties: {
        success: { type: 'boolean' },
        timestamp: { type: 'string' }
      }
    }),
    parameters: JSON.stringify([
      { name: 'message', type: 'string', description: 'Message a envoyer', required: true },
      { name: 'channel', type: 'string', description: 'Canal Slack' },
      { name: 'username', type: 'string', description: 'Nom d\'utilisateur' }
    ]),
    documentation: 'Envoie des messages Slack via webhook. Configuration du webhook Slack requise.',
    icon: '💬'
  }
];
