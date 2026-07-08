import type { BlogTranslation } from '../i18n';
import theNicheDataAdvantage from '../content/es/the-niche-data-advantage';
import chatToWorkflowNoCode from '../content/es/chat-to-workflow-no-code';
import fromDatasetToLiveWorkflow from '../content/es/from-dataset-to-live-workflow';
import workflowBeatsDoEverythingAgent from '../content/es/workflow-beats-do-everything-agent';
import smallDataSharpDecisions from '../content/es/small-data-sharp-decisions';
import capAiAgentCostBudgets from '../content/es/cap-ai-agent-cost-budgets';
import aiAgentAuditTrail from '../content/es/ai-agent-audit-trail';

export const esBlog: BlogTranslation = {
  ui: {
    eyebrow: "Notas de campo", blogTitle: "Blog", lead: "Notas sobre datos de nicho y las automatizaciones que se construyen sobre ellos. Por qué los conjuntos de datos estrechos le ganan a los amplios, y cómo convertir una fuente en un flujo de trabajo que corre por sí solo.", latest: "Lo último", readThePost: "Leer el artículo", readMore: "Leer más", allPosts: "Todos los artículos", minRead: "min de lectura", by: "Por", and: "y", ctaTitle: "Convierte tus datos de nicho en una automatización que funciona", ctaText: "Describe la tarea en el chat y LiveContext construye el flujo de trabajo ante tus ojos.", startFree: "Empieza gratis", metaTitle: "Blog - LiveContext", metaDescription: "Notas de campo sobre datos de nicho y las automatizaciones que se construyen sobre ellos: por qué los conjuntos de datos estrechos le ganan a los amplios, y cómo convertir una fuente en un flujo de trabajo que corre por sí solo.",
  },
  posts: {
    "the-niche-data-advantage": { title: "La ventaja de los datos de nicho", excerpt: "El big data es un producto genérico. Los equipos que lanzan automatizaciones útiles ganan con conjuntos de datos pequeños y precisos que casi nadie se molesta en estructurar.", coverAlt: "Un portátil que muestra un panel de análisis con gráficos, un mapa y métricas", content: theNicheDataAdvantage },
    "chat-to-workflow-no-code": { title: "Del chat al flujo de trabajo: automatización con IA sin código", excerpt: "Describe la tarea en lenguaje sencillo y obtén un flujo de trabajo que puedes ver, ejecutar y cambiar. Sin nodos que conectar a mano, sin caja negra.", coverAlt: "Una mano escribiendo un mensaje en un teléfono que muestra una conversación de chat", content: chatToWorkflowNoCode },
    "from-dataset-to-live-workflow": { title: "Del conjunto de datos al flujo de trabajo en vivo", excerpt: "Una forma en cinco pasos para convertir una fuente de nicho estática en un flujo de trabajo que se refresca solo y termina en una acción real.", coverAlt: "Una mano dibujando en una pizarra un diagrama de flujo de trabajo con cajas conectadas y flechas", content: fromDatasetToLiveWorkflow },
    "workflow-beats-do-everything-agent": { title: "Por qué un flujo de trabajo le gana a un agente que lo hace todo", excerpt: "Un flujo de trabajo acotado corre mucho más barato, se mantiene auditable, y falla menos que un gran agente autónomo. Aquí tienes cuándo usar cada uno.", coverAlt: "Un único brazo robótico sobre un soporte, que representa un agente autónomo", content: workflowBeatsDoEverythingAgent },
    "small-data-sharp-decisions": { title: "Datos pequeños, decisiones precisas", excerpt: "Las mejores decisiones rara vez necesitan más datos. Un conjunto pequeño y fiable que se traduce en una elección le gana a uno gigantesco que entierra la señal.", coverAlt: "Manos usando una calculadora junto a gráficos impresos mientras analizan datos", content: smallDataSharpDecisions },
    "cap-ai-agent-cost-budgets": { title: "Cómo limitar cuánto puede gastar un agente de IA", excerpt: "Los agentes sin límite son un riesgo financiero. Dale a cada uno un presupuesto firme que no pueda superar, y acota las herramientas y los datos que puede tocar.", coverAlt: "Monedas esparcidas sobre un escritorio junto a un cuaderno y un bolígrafo para hacer presupuestos", content: capAiAgentCostBudgets },
    "ai-agent-audit-trail": { title: "El registro de auditoría que todo agente de IA necesita", excerpt: "Una demo que funciona no basta. Registra entradas, llamadas a herramientas, salidas, coste, y cada decisión para poder depurar, demostrar cumplimiento, y ganarte la confianza.", coverAlt: "Una lupa y una calculadora apoyadas sobre documentos impresos", content: aiAgentAuditTrail },
  },
};
