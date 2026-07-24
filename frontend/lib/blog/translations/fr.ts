import type { BlogTranslation } from '../i18n';
import theNicheDataAdvantage from '../content/fr/the-niche-data-advantage';
import chatToWorkflowNoCode from '../content/fr/chat-to-workflow-no-code';
import fromDatasetToLiveWorkflow from '../content/fr/from-dataset-to-live-workflow';
import workflowBeatsDoEverythingAgent from '../content/fr/workflow-beats-do-everything-agent';
import capAiAgentCostBudgets from '../content/fr/cap-ai-agent-cost-budgets';
import sizeAnAiAgentBudget from '../content/fr/size-an-ai-agent-budget';
import aiAgentAuditTrail from '../content/fr/ai-agent-audit-trail';
import aiAgentAuditLogRetention from '../content/fr/ai-agent-audit-log-retention';

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
    "the-niche-data-advantage": { title: "L'avantage des données de niche, chiffré", excerpt: "Les preuves contre le fossé sont plus fortes que celles pour. Alors cet article chiffre la thèse des données de niche au lieu de la louer : d'abord le meilleur argument contre, puis un seul paramètre à mesurer, une grille à sept lignes, et un seuil de rentabilité construire, acheter, ou ne rien faire.", coverAlt: "Un ordinateur portable affichant un tableau de bord analytique avec des graphiques, une carte et des indicateurs", content: theNicheDataAdvantage },
    "chat-to-workflow-no-code": { title: "Du chat au workflow : l'automatisation IA no-code", excerpt: "Décrivez la tâche en langage courant et obtenez un workflow que vous pouvez voir, exécuter et modifier. Aucun nœud à câbler à la main, aucune boîte noire.", coverAlt: "Une main tapant un message sur un téléphone affichant une conversation de chat", content: chatToWorkflowNoCode },
    "from-dataset-to-live-workflow": { title: "Du jeu de données au workflow vivant, nœud par nœud", excerpt: "Un graphe réel sur un moteur de production : une veille de prix planifiée qui rafraîchit, décide et protège une écriture. Avec les chaînes de template exactes qui se résolvent, celles qui échouent en silence, et le garde-fou idempotent qui l'empêche de dupliquer des lignes.", coverAlt: "Une main dessinant un diagramme de workflow fait de boîtes reliées et de flèches sur un tableau blanc", content: fromDatasetToLiveWorkflow },
    "workflow-beats-do-everything-agent": { title: "Ce que coûte vraiment un workflow cadré face à un agent bon à tout faire", excerpt: "Nous avons supprimé notre propre affirmation « 10x moins cher » parce qu'elle n'avait aucune dérivation. Voici le modèle de coût à la place : deux fonctions, une quadratique, un grand livre de triage chiffré, et les conditions où le ratio tombe à 1,3x ou s'inverse.", coverAlt: "Un unique bras robotique sur un socle, représentant un agent autonome", content: workflowBeatsDoEverythingAgent },
    "cap-ai-agent-cost-budgets": { title: "Le budget qui arrête vraiment l'agent", excerpt: "La plupart des budgets d'agent sont un nombre qui n'a jamais refusé un seul appel. Ce dont un vrai plafond est fait, pourquoi il ne peut jamais arrêter que l'appel suivant, et ce que chaque pile applique réellement.", coverAlt: "Des pièces éparpillées sur un bureau à côté d'un carnet et d'un stylo pour la budgétisation", content: capAiAgentCostBudgets },
    "size-an-ai-agent-budget": { title: "Comment dimensionner un budget d'agent réellement applicable", excerpt: "La moitié dimensionnement : un modèle générateur reproductible, un facteur de sécurité dérivé, le plancher sous lequel un plafond monétaire n'est plus applicable du tout, et le nombre d'exécutions nécessaires avant de pouvoir citer un p99.", coverAlt: "Des mains utilisant une calculatrice à côté de graphiques imprimés lors d'une analyse de données", content: sizeAnAiAgentBudget },
    "ai-agent-audit-trail": { title: "La piste d'audit d'un agent : un schéma de champs à copier", excerpt: "Une piste d'audit n'est pas un log plus long, c'est un autre artefact avec un autre lecteur. Un schéma copiable au niveau du run et de l'étape, où chaque champ porte son type, sa cardinalité, son drapeau donnée personnelle et sa raison d'exister.", coverAlt: "Une loupe et une calculatrice posées sur des documents imprimés", content: aiAgentAuditTrail },
    "ai-agent-audit-log-retention": { title: "Combien de temps garder la piste d'audit d'un agent, et ce que vous devez vraiment", excerpt: "L'arithmétique de stockage qui fait de la rétention une décision dérivée, l'échelonnement qui en découle, et une carte honnête des obligations de journalisation de l'EU AI Act, y compris la part où la plupart des agents sont hors périmètre.", coverAlt: "Une main dessinant un diagramme de workflow fait de boîtes reliées et de flèches sur un tableau blanc", content: aiAgentAuditLogRetention },
  },
};
