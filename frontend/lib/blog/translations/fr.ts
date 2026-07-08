import type { BlogTranslation } from '../i18n';
import theNicheDataAdvantage from '../content/fr/the-niche-data-advantage';
import chatToWorkflowNoCode from '../content/fr/chat-to-workflow-no-code';
import fromDatasetToLiveWorkflow from '../content/fr/from-dataset-to-live-workflow';
import workflowBeatsDoEverythingAgent from '../content/fr/workflow-beats-do-everything-agent';
import smallDataSharpDecisions from '../content/fr/small-data-sharp-decisions';
import capAiAgentCostBudgets from '../content/fr/cap-ai-agent-cost-budgets';
import aiAgentAuditTrail from '../content/fr/ai-agent-audit-trail';

export const frBlog: BlogTranslation = {
  ui: {
    eyebrow: "Notes de terrain",
    blogTitle: "Blog",
    lead: "Des notes sur les données de niche et les automatisations bâties par-dessus. Pourquoi les jeux de données étroits battent les larges, et comment transformer une source en un workflow qui tourne tout seul.",
    latest: "Derniers articles",
    readThePost: "Lire l'article",
    readMore: "Lire la suite",
    allPosts: "Tous les articles",
    minRead: "min de lecture",
    by: "Par",
    and: "et",
    ctaTitle: "Transformez vos données de niche en une automatisation qui fonctionne",
    ctaText: "Décrivez la tâche en discutant et LiveContext construit le workflow sous vos yeux.",
    startFree: "Commencer gratuitement",
    metaTitle: "Blog - LiveContext",
    metaDescription: "Des notes de terrain sur les données de niche et les automatisations bâties par-dessus : pourquoi les jeux de données étroits battent les larges, et comment transformer une source en un workflow qui tourne tout seul.",
  },
  posts: {
    "the-niche-data-advantage": { title: "L'avantage des données de niche", excerpt: "Le big data est une commodité. Les équipes qui livrent des automatisations utiles gagnent sur de petits jeux de données précis que presque personne ne prend la peine de structurer.", coverAlt: "Un ordinateur portable affichant un tableau de bord analytique avec des graphiques, une carte et des indicateurs", content: theNicheDataAdvantage },
    "chat-to-workflow-no-code": { title: "Du chat au workflow : l'automatisation IA no-code", excerpt: "Décrivez la tâche en langage courant et obtenez un workflow que vous pouvez voir, exécuter et modifier. Aucun nœud à câbler à la main, aucune boîte noire.", coverAlt: "Une main tapant un message sur un téléphone affichant une conversation de chat", content: chatToWorkflowNoCode },
    "from-dataset-to-live-workflow": { title: "Du jeu de données au workflow vivant", excerpt: "Une structure en cinq étapes pour transformer une source de niche statique en un workflow qui se rafraîchit tout seul et se termine par une action réelle.", coverAlt: "Une main dessinant un diagramme de workflow fait de boîtes reliées et de flèches sur un tableau blanc", content: fromDatasetToLiveWorkflow },
    "workflow-beats-do-everything-agent": { title: "Pourquoi un workflow bat un agent bon à tout faire", excerpt: "Un workflow cadré tourne bien moins cher, reste auditable, et échoue moins qu'un gros agent autonome. Voici quand utiliser chacun.", coverAlt: "Un unique bras robotique sur un socle, représentant un agent autonome", content: workflowBeatsDoEverythingAgent },
    "small-data-sharp-decisions": { title: "Petites données, décisions précises", excerpt: "De meilleures décisions ont rarement besoin de plus de données. Un petit jeu de données fiable qui se rattache à un choix bat un jeu géant qui enfouit le signal.", coverAlt: "Des mains utilisant une calculatrice à côté de graphiques imprimés lors d'une analyse de données", content: smallDataSharpDecisions },
    "cap-ai-agent-cost-budgets": { title: "Comment plafonner ce qu'un agent IA peut dépenser", excerpt: "Les agents sans limite sont un risque financier. Donnez à chacun un budget strict qu'il ne peut pas dépasser, et cadrez les outils et les données qu'il peut toucher.", coverAlt: "Des pièces éparpillées sur un bureau à côté d'un carnet et d'un stylo pour la budgétisation", content: capAiAgentCostBudgets },
    "ai-agent-audit-trail": { title: "La piste d'audit dont chaque agent IA a besoin", excerpt: "Une démo qui marche ne suffit pas. Consignez les entrées, les appels d'outils, les sorties, le coût et chaque décision pour pouvoir déboguer, prouver la conformité et gagner la confiance.", coverAlt: "Une loupe et une calculatrice posées sur des documents imprimés", content: aiAgentAuditTrail },
  },
};
