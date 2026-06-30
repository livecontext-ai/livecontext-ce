import { McpTool } from '../types';

// Templates MCP complets avec plusieurs outils par API
export const MCP_API_TEMPLATES = {
  'postgresql-api': {
    name: 'PostgreSQL REST API',
    description: 'Complete API to manage a PostgreSQL database',
    category: 'Database',
    subcategory: 'SQL',
    baseUrl: 'https://your-postgres-api.com/api/v1',
    tools: [
      {
        id: 'pg-select',
        name: 'select_records',
        description: 'Select records with criteria',
        method: 'GET' as const,
        endpoint: '/tables/{table}/records',
        toolCategory: 'Database',
        pathParameters: [
          { name: 'table', type: 'string', description: 'Nom de la table', required: true }
        ],
        queryParameters: [
          { name: 'where', type: 'string', description: 'Conditions WHERE (ex: id=1)', required: false },
          { name: 'limit', type: 'number', description: 'Maximum number of results', required: false },
          { name: 'offset', type: 'number', description: 'Offset for pagination', required: false },
          { name: 'orderBy', type: 'string', description: 'Tri (ex: created_at DESC)', required: false }
        ],
        headers: [
          { name: 'Authorization', value: 'Bearer {{token}}', description: 'Token d\'authentification' }
        ],
        response: {
          type: 'json' as const,
          description: 'List of found records',
          success: {
            data: [{ id: 1, name: 'exemple' }],
            count: 1,
            total: 100
          },
          error: { error: 'Table not found', code: 404 }
        }
      },
      {
        id: 'pg-insert',
        name: 'insert_record',
        description: 'Insert a new record',
        method: 'POST' as const,
        endpoint: '/tables/{table}/records',
        toolCategory: 'Database',
        pathParameters: [
          { name: 'table', type: 'string', description: 'Nom de la table', required: true }
        ],
        headers: [
          { name: 'Authorization', value: 'Bearer {{token}}', description: 'Token d\'authentification' },
          { name: 'Content-Type', value: 'application/json', description: 'Type de contenu' }
        ],
        bodyParams: [
          { name: 'data', value: '{"name": "Nouveau nom", "email": "test@example.com"}', type: 'text', description: 'Donnees a inserer (JSON)' }
        ],
        response: {
          type: 'json' as const,
          description: 'Enregistrement cree avec son ID',
          success: { id: 123, created_at: '2024-01-01T12:00:00Z' },
          error: { error: 'Validation failed', details: ['name is required'] }
        }
      },
      {
        id: 'pg-update',
        name: 'update_record',
        description: 'Mettre a jour un enregistrement existant',
        method: 'PUT' as const,
        endpoint: '/tables/{table}/records/{id}',
        toolCategory: 'Database',
        pathParameters: [
          { name: 'table', type: 'string', description: 'Nom de la table', required: true },
          { name: 'id', type: 'number', description: 'ID de l\'enregistrement', required: true }
        ],
        headers: [
          { name: 'Authorization', value: 'Bearer {{token}}', description: 'Token d\'authentification' },
          { name: 'Content-Type', value: 'application/json', description: 'Type de contenu' }
        ],
        bodyParams: [
          { name: 'data', value: '{"name": "Modified name"}', type: 'text', description: 'Data to modify (JSON)' }
        ],
        response: {
          type: 'json' as const,
          description: 'Enregistrement mis a jour',
          success: { id: 123, updated_at: '2024-01-01T12:00:00Z', changes: 1 },
          error: { error: 'Record not found', code: 404 }
        }
      },
      {
        id: 'pg-delete',
        name: 'delete_record',
        description: 'Supprimer un enregistrement',
        method: 'DELETE' as const,
        endpoint: '/tables/{table}/records/{id}',
        toolCategory: 'Database',
        pathParameters: [
          { name: 'table', type: 'string', description: 'Nom de la table', required: true },
          { name: 'id', type: 'number', description: 'ID de l\'enregistrement', required: true }
        ],
        headers: [
          { name: 'Authorization', value: 'Bearer {{token}}', description: 'Token d\'authentification' }
        ],
        response: {
          type: 'json' as const,
          description: 'Confirmation de suppression',
          success: { deleted: true, id: 123 },
          error: { error: 'Record not found', code: 404 }
        }
      },
      {
        id: 'pg-execute',
        name: 'execute_query',
        description: 'Executer une requete SQL personnalisee',
        method: 'POST' as const,
        endpoint: '/execute',
        toolCategory: 'Database',
        headers: [
          { name: 'Authorization', value: 'Bearer {{token}}', description: 'Token d\'authentification' },
          { name: 'Content-Type', value: 'application/json', description: 'Type de contenu' }
        ],
        bodyParams: [
          { name: 'query', value: 'SELECT * FROM users WHERE created_at > $1', type: 'text', description: 'Requete SQL' },
          { name: 'params', value: '["2024-01-01"]', type: 'text', description: 'Parametres de la requete (array JSON)' }
        ],
        response: {
          type: 'json' as const,
          description: 'Resultat de la requete',
          success: { rows: [], rowCount: 0, command: 'SELECT' },
          error: { error: 'SQL syntax error', position: 15 }
        }
      }
    ]
  },

  'mongodb-api': {
    name: 'MongoDB REST API',
    description: 'API complete pour gerer une base MongoDB',
    category: 'Database',
    subcategory: 'NoSQL',
    baseUrl: 'https://your-mongodb-api.com/api/v1',
    tools: [
      {
        id: 'mongo-find',
        name: 'find_documents',
        description: 'Rechercher des documents dans une collection',
        method: 'GET' as const,
        endpoint: '/collections/{collection}/documents',
        toolCategory: 'Database',
        pathParameters: [
          { name: 'collection', type: 'string', description: 'Nom de la collection', required: true }
        ],
        queryParameters: [
          { name: 'filter', type: 'string', description: 'Filtre MongoDB (JSON)', required: false },
          { name: 'limit', type: 'number', description: 'Limite de resultats', required: false },
          { name: 'skip', type: 'number', description: 'Documents a ignorer', required: false },
          { name: 'sort', type: 'string', description: 'Tri (JSON)', required: false }
        ],
        headers: [
          { name: 'Authorization', value: 'Bearer {{token}}', description: 'Token d\'authentification' }
        ],
        response: {
          type: 'json' as const,
          description: 'Documents trouves',
          success: { documents: [], count: 0, hasMore: false },
          error: { error: 'Collection not found', code: 404 }
        }
      },
      {
        id: 'mongo-insert',
        name: 'insert_document',
        description: 'Inserer un nouveau document',
        method: 'POST' as const,
        endpoint: '/collections/{collection}/documents',
        toolCategory: 'Database',
        pathParameters: [
          { name: 'collection', type: 'string', description: 'Nom de la collection', required: true }
        ],
        headers: [
          { name: 'Authorization', value: 'Bearer {{token}}', description: 'Token d\'authentification' },
          { name: 'Content-Type', value: 'application/json', description: 'Type de contenu' }
        ],
        bodyParams: [
          { name: 'document', value: '{"name": "Test", "status": "active"}', type: 'text', description: 'Document a inserer' }
        ],
        response: {
          type: 'json' as const,
          description: 'Document cree avec _id',
          success: { insertedId: '507f1f77bcf86cd799439011', acknowledged: true },
          error: { error: 'Document validation failed' }
        }
      },
      {
        id: 'mongo-aggregate',
        name: 'aggregate_pipeline',
        description: 'Executer un pipeline d\'agregation',
        method: 'POST' as const,
        endpoint: '/collections/{collection}/aggregate',
        toolCategory: 'Database',
        pathParameters: [
          { name: 'collection', type: 'string', description: 'Nom de la collection', required: true }
        ],
        headers: [
          { name: 'Authorization', value: 'Bearer {{token}}', description: 'Token d\'authentification' },
          { name: 'Content-Type', value: 'application/json', description: 'Type de contenu' }
        ],
        bodyParams: [
          { name: 'pipeline', value: '[{"$match": {"status": "active"}}, {"$group": {"_id": "$category", "count": {"$sum": 1}}}]', type: 'text', description: 'Pipeline d\'agregation' }
        ],
        response: {
          type: 'json' as const,
          description: 'Resultats de l\'agregation',
          success: { results: [], totalDocs: 0 },
          error: { error: 'Invalid pipeline stage' }
        }
      }
    ]
  },

  'stripe-api': {
    name: 'Stripe Payment API',
    description: 'API complete pour gerer les paiements Stripe',
    category: 'Business',
    subcategory: 'Payment',
    baseUrl: 'https://api.stripe.com/v1',
    tools: [
      {
        id: 'stripe-create-customer',
        name: 'create_customer',
        description: 'Creer un nouveau client',
        method: 'POST' as const,
        endpoint: '/customers',
        toolCategory: 'Payment',
        headers: [
          { name: 'Authorization', value: 'Bearer {{stripe_secret_key}}', description: 'Cle secrete Stripe' },
          { name: 'Content-Type', value: 'application/x-www-form-urlencoded', description: 'Type de contenu' }
        ],
        bodyParams: [
          { name: 'email', value: 'customer@example.com', type: 'text', description: 'Email du client' },
          { name: 'name', value: 'John Doe', type: 'text', description: 'Nom du client' },
          { name: 'phone', value: '+33123456789', type: 'text', description: 'Telephone (optionnel)' }
        ],
        response: {
          type: 'json' as const,
          description: 'Client cree',
          success: { id: 'cus_123456789', email: 'customer@example.com', created: 1234567890 },
          error: { error: { type: 'invalid_request_error', message: 'Invalid email' } }
        }
      },
      {
        id: 'stripe-create-payment',
        name: 'create_payment_intent',
        description: 'Creer une intention de paiement',
        method: 'POST' as const,
        endpoint: '/payment_intents',
        toolCategory: 'Payment',
        headers: [
          { name: 'Authorization', value: 'Bearer {{stripe_secret_key}}', description: 'Cle secrete Stripe' },
          { name: 'Content-Type', value: 'application/x-www-form-urlencoded', description: 'Type de contenu' }
        ],
        bodyParams: [
          { name: 'amount', value: '2000', type: 'text', description: 'Montant en centimes' },
          { name: 'currency', value: 'eur', type: 'text', description: 'Devise (eur, usd, etc.)' },
          { name: 'customer', value: 'cus_123456789', type: 'text', description: 'ID du client' }
        ],
        response: {
          type: 'json' as const,
          description: 'Intention de paiement creee',
          success: { id: 'pi_123456789', client_secret: 'pi_123_secret_456', status: 'requires_payment_method' },
          error: { error: { type: 'invalid_request_error', message: 'Invalid amount' } }
        }
      },
      {
        id: 'stripe-get-payment',
        name: 'get_payment_intent',
        description: 'Recuperer une intention de paiement',
        method: 'GET' as const,
        endpoint: '/payment_intents/{payment_intent_id}',
        toolCategory: 'Payment',
        pathParameters: [
          { name: 'payment_intent_id', type: 'string', description: 'ID de l\'intention de paiement', required: true }
        ],
        headers: [
          { name: 'Authorization', value: 'Bearer {{stripe_secret_key}}', description: 'Cle secrete Stripe' }
        ],
        response: {
          type: 'json' as const,
          description: 'Details de l\'intention de paiement',
          success: { id: 'pi_123456789', amount: 2000, currency: 'eur', status: 'succeeded' },
          error: { error: { type: 'invalid_request_error', message: 'No such payment_intent' } }
        }
      }
    ]
  },

  'sendgrid-email-api': {
    name: 'SendGrid Email API',
    description: 'API complete pour envoyer des emails via SendGrid',
    category: 'Communication',
    subcategory: 'Email',
    baseUrl: 'https://api.sendgrid.com/v3',
    tools: [
      {
        id: 'sendgrid-send-email',
        name: 'send_email',
        description: 'Envoyer un email simple',
        method: 'POST' as const,
        endpoint: '/mail/send',
        toolCategory: 'Email',
        headers: [
          { name: 'Authorization', value: 'Bearer {{sendgrid_api_key}}', description: 'Cle API SendGrid' },
          { name: 'Content-Type', value: 'application/json', description: 'Type de contenu' }
        ],
        bodyParams: [
          { 
            name: 'email_data', 
            value: JSON.stringify({
              personalizations: [{
                to: [{ email: 'recipient@example.com', name: 'Recipient Name' }],
                subject: 'Hello from SendGrid'
              }],
              from: { email: 'sender@example.com', name: 'Sender Name' },
              content: [{
                type: 'text/html',
                value: '<h1>Hello World!</h1><p>This is a test email.</p>'
              }]
            }, null, 2), 
            type: 'text', 
            description: 'Donnees completes de l\'email (JSON)'
          }
        ],
        response: {
          type: 'json' as const,
          description: 'Email envoye avec succes',
          success: { message: 'Email sent successfully', id: 'msg_123456789' },
          error: { errors: [{ message: 'Invalid email address', field: 'personalizations.0.to.0.email' }] }
        }
      },
      {
        id: 'sendgrid-send-template',
        name: 'send_template_email',
        description: 'Envoyer un email avec template',
        method: 'POST' as const,
        endpoint: '/mail/send',
        toolCategory: 'Email',
        headers: [
          { name: 'Authorization', value: 'Bearer {{sendgrid_api_key}}', description: 'Cle API SendGrid' },
          { name: 'Content-Type', value: 'application/json', description: 'Type de contenu' }
        ],
        bodyParams: [
          { 
            name: 'template_email', 
            value: JSON.stringify({
              personalizations: [{
                to: [{ email: 'recipient@example.com' }],
                dynamic_template_data: {
                  name: 'John Doe',
                  product: 'Premium Plan'
                }
              }],
              from: { email: 'sender@example.com' },
              template_id: 'd-123456789abcdef'
            }, null, 2), 
            type: 'text', 
            description: 'Email avec template et donnees dynamiques'
          }
        ],
        response: {
          type: 'json' as const,
          description: 'Email template envoye',
          success: { message: 'Template email sent', id: 'msg_template_123' },
          error: { errors: [{ message: 'Template not found', field: 'template_id' }] }
        }
      }
    ]
  },

  'slack-api': {
    name: 'Slack Workspace API',
    description: 'API complete pour gerer un workspace Slack',
    category: 'Communication',
    subcategory: 'Messaging',
    baseUrl: 'https://slack.com/api',
    tools: [
      {
        id: 'slack-send-message',
        name: 'send_message',
        description: 'Envoyer un message dans un canal',
        method: 'POST' as const,
        endpoint: '/chat.postMessage',
        toolCategory: 'Messaging',
        headers: [
          { name: 'Authorization', value: 'Bearer {{slack_bot_token}}', description: 'Token bot Slack' },
          { name: 'Content-Type', value: 'application/json', description: 'Type de contenu' }
        ],
        bodyParams: [
          { name: 'channel', value: '#general', type: 'text', description: 'Canal ou ID utilisateur' },
          { name: 'text', value: 'Hello from API!', type: 'text', description: 'Message a envoyer' },
          { name: 'username', value: 'API Bot', type: 'text', description: 'Nom du bot (optionnel)' }
        ],
        response: {
          type: 'json' as const,
          description: 'Message envoye',
          success: { ok: true, ts: '1234567890.123456', channel: 'C1234567890' },
          error: { ok: false, error: 'channel_not_found' }
        }
      },
      {
        id: 'slack-create-channel',
        name: 'create_channel',
        description: 'Creer un nouveau canal',
        method: 'POST' as const,
        endpoint: '/conversations.create',
        toolCategory: 'Messaging',
        headers: [
          { name: 'Authorization', value: 'Bearer {{slack_bot_token}}', description: 'Token bot Slack' },
          { name: 'Content-Type', value: 'application/json', description: 'Type de contenu' }
        ],
        bodyParams: [
          { name: 'name', value: 'new-project', type: 'text', description: 'Nom du canal (sans #)' },
          { name: 'is_private', value: 'false', type: 'text', description: 'Canal prive (true/false)' }
        ],
        response: {
          type: 'json' as const,
          description: 'Canal cree',
          success: { ok: true, channel: { id: 'C1234567890', name: 'new-project' } },
          error: { ok: false, error: 'name_taken' }
        }
      },
      {
        id: 'slack-get-users',
        name: 'get_users',
        description: 'Lister les utilisateurs du workspace',
        method: 'GET' as const,
        endpoint: '/users.list',
        toolCategory: 'Messaging',
        queryParameters: [
          { name: 'limit', type: 'number', description: 'Nombre max d\'utilisateurs', required: false },
          { name: 'cursor', type: 'string', description: 'Curseur pour pagination', required: false }
        ],
        headers: [
          { name: 'Authorization', value: 'Bearer {{slack_bot_token}}', description: 'Token bot Slack' }
        ],
        response: {
          type: 'json' as const,
          description: 'Liste des utilisateurs',
          success: { ok: true, members: [], response_metadata: { next_cursor: '' } },
          error: { ok: false, error: 'invalid_auth' }
        }
      }
    ]
  }
};

// Fonction pour obtenir un template complet
export function getMcpTemplate(templateId: string) {
  return MCP_API_TEMPLATES[templateId as keyof typeof MCP_API_TEMPLATES];
}

// Liste des templates disponibles pour l'UI
export const AVAILABLE_MCP_TEMPLATES = Object.keys(MCP_API_TEMPLATES).map(key => ({
  id: key,
  name: MCP_API_TEMPLATES[key as keyof typeof MCP_API_TEMPLATES].name,
  description: MCP_API_TEMPLATES[key as keyof typeof MCP_API_TEMPLATES].description,
  category: MCP_API_TEMPLATES[key as keyof typeof MCP_API_TEMPLATES].category,
  toolsCount: MCP_API_TEMPLATES[key as keyof typeof MCP_API_TEMPLATES].tools.length
}));
