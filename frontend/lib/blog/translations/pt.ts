import type { BlogTranslation } from '../i18n';
import theNicheDataAdvantage from '../content/pt/the-niche-data-advantage';
import chatToWorkflowNoCode from '../content/pt/chat-to-workflow-no-code';
import fromDatasetToLiveWorkflow from '../content/pt/from-dataset-to-live-workflow';
import workflowBeatsDoEverythingAgent from '../content/pt/workflow-beats-do-everything-agent';
import smallDataSharpDecisions from '../content/pt/small-data-sharp-decisions';
import capAiAgentCostBudgets from '../content/pt/cap-ai-agent-cost-budgets';
import aiAgentAuditTrail from '../content/pt/ai-agent-audit-trail';

export const ptBlog: BlogTranslation = {
  ui: {
    eyebrow: "Notas de terreno", blogTitle: "Blog", lead: "Notas sobre dados de nicho e as automatizações construídas sobre eles. Porque é que os conjuntos de dados estreitos vencem os amplos, e como transformar uma fonte num workflow que se executa a si próprio.", latest: "Mais recente", readThePost: "Ler o artigo", readMore: "Ler mais", allPosts: "Todos os artigos", minRead: "min de leitura", by: "Por", and: "e", ctaTitle: "Transforme os seus dados de nicho numa automatização a funcionar", ctaText: "Descreva a tarefa no chat e o LiveContext constrói o workflow à sua frente.", startFree: "Comece grátis", metaTitle: "Blog - LiveContext", metaDescription: "Notas de terreno sobre dados de nicho e as automatizações construídas sobre eles: porque é que os conjuntos de dados estreitos vencem os amplos, e como transformar uma fonte num workflow que se executa a si próprio.",
  },
  posts: {
    "the-niche-data-advantage": { title: "A vantagem dos dados de nicho", excerpt: "Os big data são um bem comum. As equipas que lançam automatizações úteis vencem com conjuntos de dados pequenos e precisos que quase ninguém se dá ao trabalho de estruturar.", coverAlt: "Um portátil a mostrar um painel de análise com gráficos, um mapa e métricas", content: theNicheDataAdvantage },
    "chat-to-workflow-no-code": { title: "Do chat ao workflow: automatização de IA no-code", excerpt: "Descreva a tarefa em linguagem simples e obtenha um workflow que consegue ver, executar e alterar. Sem nós para ligar à mão, sem caixa preta.", coverAlt: "Uma mão a escrever uma mensagem num telemóvel que mostra uma conversa de chat", content: chatToWorkflowNoCode },
    "from-dataset-to-live-workflow": { title: "Do conjunto de dados ao workflow em funcionamento", excerpt: "Uma forma em cinco passos para transformar uma fonte de nicho estática num workflow que se atualiza a si próprio e termina numa ação real.", coverAlt: "Uma mão a desenhar num quadro branco um diagrama de workflow com caixas ligadas e setas", content: fromDatasetToLiveWorkflow },
    "workflow-beats-do-everything-agent": { title: "Porque é que um workflow vence um agente que faz tudo", excerpt: "Um workflow delimitado corre muito mais barato, mantém-se auditável e falha menos do que um grande agente autónomo. Eis quando usar cada um.", coverAlt: "Um único braço robótico num suporte, a representar um agente autónomo", content: workflowBeatsDoEverythingAgent },
    "small-data-sharp-decisions": { title: "Dados pequenos, decisões precisas", excerpt: "Melhores decisões raramente precisam de mais dados. Um conjunto de dados pequeno e de confiança que corresponde a uma escolha vence um gigante que enterra o sinal.", coverAlt: "Mãos a usar uma calculadora ao lado de gráficos impressos enquanto analisam dados", content: smallDataSharpDecisions },
    "cap-ai-agent-cost-budgets": { title: "Como limitar o que um agente de IA pode gastar", excerpt: "Agentes sem limites são um risco financeiro. Dê a cada um um orçamento rígido que não pode exceder, e delimite as ferramentas e os dados que pode tocar.", coverAlt: "Moedas espalhadas numa secretária ao lado de um caderno e uma caneta para orçamentar", content: capAiAgentCostBudgets },
    "ai-agent-audit-trail": { title: "O registo de auditoria de que todo o agente de IA precisa", excerpt: "Uma demonstração que funciona não chega. Registe entradas, chamadas a ferramentas, saídas, custo e cada decisão para poder depurar, provar conformidade e ganhar confiança.", coverAlt: "Uma lupa e uma calculadora pousadas sobre documentos impressos", content: aiAgentAuditTrail },
  },
};
