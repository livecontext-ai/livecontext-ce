// cap-ai-agent-cost-budgets - fr
// Translated from the English body; the structure must stay identical to it.
// Formulas and code samples are fenced on purpose: an inline code span over
// ~45 chars overflows the page on a phone. The hedges are load-bearing here.
const content = `## Une alerte n'est pas un plafond

Un moniteur est asynchrone et a posteriori : il vous dit ce que vous avez déjà dépensé, il ne peut donc pas être la couche d'application. Un plafond est synchrone et pré-exécution : il refuse l'appel suivant. La réconciliation et la télémétrie des raisons d'arrêt gardent leur importance, mais pour dimensionner le plafond et détecter celui qui est trop serré, pas pour arrêter le travail.

Voici le test à faire avant de lire la suite, et il ne nécessite aucun seuil : extrayez les enregistrements de refus de votre plafond configuré sur la dernière fenêtre d'observation. A-t-il déjà refusé quoi que ce soit ? Un nombre qui n'a jamais rien refusé n'est pas un contrôle, c'est un commentaire.

Les plafonds au niveau du fournisseur sont des filets de sécurité, pas une application de première ligne :

- La limite de dépense projet et organisation d'OpenAI est un **budget souple par défaut** : elle notifie et les requêtes continuent de passer. Un plafond dur existe, mais sous forme d'option distincte à activer, qui renvoie alors un HTTP 429 jusqu'à ce que la limite soit relevée ou réinitialisée ([guide des limites de dépense](https://developers.openai.com/api/docs/guides/spend-limits)).
- La [Spend Limits API](https://platform.claude.com/docs/en/manage-claude/spend-limits-api) d'Anthropic est réservée à Claude Enterprise, explicitement indisponible pour les organisations Claude Platform (Console), et ne prend en charge que \`monthly\` comme période (réinitialisation à 00 UTC le premier du mois calendaire). Elle plafonne l'usage des sièges humains, pas la dépense API des agents.
- La documentation d'Anthropic disqualifie elle aussi la dépense fournisseur comme garde-fou : \`period_to_date_spend\` « peut se lire "0" si la lecture de la dépense est temporairement indisponible ; traitez-la comme informative, non transactionnelle ».
- Anthropic applique bien un plafond mensuel par palier d'usage (Start $500, Build $1,000, Scale $200,000, Custom sans plafond) qui suspend l'usage de l'API jusqu'au mois suivant ([limites de débit](https://platform.claude.com/docs/en/api/rate-limits)). Un vrai plafond, mais à l'échelle de l'organisation et mensuel : une seule exécution emballée peut le consommer et transformer un bug de coût en panne pour toute l'organisation.

Le coût par étape croît de façon super-linéaire parce que le contexte s'accumule, et c'est pourquoi compter les étapes ne borne pas les dollars. Cette dérivation vit dans l'article compagnon sur le modèle de coût. Pour les seules entrées de dimensionnement, Anthropic rapporte que les agents consomment environ 4x les tokens d'un chat et les systèmes multi-agents environ 15x ([multi-agent research system](https://www.anthropic.com/engineering/multi-agent-research-system)).

**Divulgation.** Les détails d'implémentation, constantes et messages de refus ci-dessous proviennent de l'\`agent-service\` de LiveContext, la plateforme à laquelle appartient ce blog. Lisez-les comme les choix d'un système donné, vérifiables dans sa source community-edition, et non comme une pratique de terrain relevée par enquête.

## Les cinq parties d'un objet budget

Un budget n'est pas un nombre. C'est un objet à cinq parties, et un budget auquel il manque l'une d'elles échoue d'une manière spécifique et diagnosticable.

**1. Portée.** Le niveau auquel le registre est tenu. Quatre existent dans ce système livré : solde locataire/compte (macro), agent/étape (micro), \`parent_reservation\` (un ancêtre de la chaîne d'appel refuse de financer l'engendrement d'un enfant), et par exécution/par époque. Un refus qui ne nomme pas la portée déclenchée est indébogable.

**2. Unité.** Dollars, tokens, ou simples compteurs (tours, supersteps, appels d'outils). Les compteurs flottent en termes monétaires. Seuls les tokens ou l'argent constituent un budget.

**3. Point d'application.** Réconciliation a posteriori, projection avant itération, réservation avant engendrement, ou plafond d'admission sur les entrées. Chacun a une borne de dépassement différente (Tableau 1).

**4. Politique de réservation.** Le fait que le budget soit décrémenté après coup ou retenu avant le début du travail. C'est la seule partie qui rend sûr un fan-out parallèle.

**5. Réponse terminale.** Ce que reçoit l'appelant à l'instant où le plafond est atteint. Cinq comportements distincts existent sur le terrain et ils ne sont pas interchangeables.

**Tableau 1 : Points d'application et leur borne de dépassement**

| Point d'application | Quand il s'exécute | Ce qu'il peut refuser | Dépassement dans le pire cas | Sûr pour un fan-out parallèle ? |
|---|---|---|---|---|
| Réconciliation / alerte a posteriori | Après le règlement de l'appel | Rien | Non borné | Non (détection, pas application) |
| Projection avant itération | Avant le prochain appel modèle | L'itération suivante | Une itération (jusqu'à 40x la première itération sur une étape navigateur) | Non |
| Réservation avant engendrement | Avant le démarrage d'un enfant | L'enfant entier | Zéro pour l'enfant | Oui |
| Plafond d'admission sur les entrées | Avant l'assemblage du prompt | Contexte / sortie surdimensionnés | Borne l'itération elle-même | Oui (se compose avec les autres) |

Deux décisions de conception que l'on traite comme de la configuration mais qui appartiennent à l'objet :

**L'ordre des gardes est une conception de portée.** Cette implémentation exécute exactement deux gardes, \`TenantBudgetGuard\` puis \`AgentBudgetGuard\`, premier-refus-gagne avec court-circuit, pour deux raisons documentées : l'épuisement du locataire rend le budget agent sans objet, et la garde locataire est placée en premier comme rejet précoce avant l'aller-retour de réservation de crédits en aval.

**La période est une décision de dimensionnement.** Un accumulateur cumulatif fait du plafond un total à vie, de sorte qu'un agent de longue durée approche silencieusement l'épuisement au fil des mois. Des réinitialisations hebdomadaires ou mensuelles font du même nombre un taux. Les réinitialisations peuvent être résolues paresseusement au démarrage de l'exécution, via une mise à jour compare-and-set plutôt que par un ordonnanceur (modes \`BudgetResolver\` : cumulative, weekly, monthly ; les valeurs inconnues sont traitées comme cumulative).

Un piège sémantique à vérifier dans votre propre stack : l'aide de paramètre d'outil destinée aux agents de cette plateforme indique encore « Chaque itération LLM coûte 1 crédit » alors que la garde compare une projection monétaire à ce même champ \`credit_budget\`. Deux autres chaînes d'aide nuancent la chose en « au moins un crédit » et « plus d'un crédit en pratique », si bien que les documents se contredisent aussi entre eux. Une règle empirique dans la documentation et une comparaison monétaire dans le code, c'est une classe de bugs, pas une coquille de formulation.

## Vous ne pouvez pas arrêter l'appel que vous êtes déjà en train de faire

La consommation de tokens n'est connue qu'après la fin d'un appel. Aucun budget en cours d'exécution ne peut empêcher un seul appel coûteux de faire sauter le plafond ; il ne peut empêcher que le suivant. Le pire cas réalisé est donc **le budget plus une itération**, et non le budget. Dites-le clairement au lieu de laisser entendre un plafond dur.

La formule de filtrage telle qu'énoncée dans le fichier de fixtures inter-langages partagé :

\`\`\`
projectedNext = max(
    growthProj,
    lastDeltaProj * LAST_DELTA_SAFETY_FACTOR,
    worstCaseSingleIter
)
deny iff (runCostSoFar + projectedNext > balance)
      OR (runCostSoFar >= balance)

LAST_DELTA_SAFETY_FACTOR = 2.0
RATE_DIVISOR             = 1000
ROUND_DECIMALS           = 6 (HALF_UP, per subterm)
\`\`\`

Deux réserves avant de la copier. Les deux gardes Java implémentent \`>=\` sur la comparaison de projection, pas \`>\` ; le jumeau JS implémente \`>\`. À l'égalité exacte du total projeté, ils divergent, et aucun cas de fixture ne se situe sur cette frontière. Et la comparaison à portée agent ne comporte pas deux termes mais quatre :

\`\`\`
totalProjected = consumedBeforeRun
               + creditsReserved
               + runCostSoFar
               + nextProjected
deny iff totalProjected >= totalBudget
\`\`\`

\`creditsReserved\` correspond aux crédits actuellement verrouillés par des sous-agents en vol : la boucle propre d'un parent est donc bridée par ce que retiennent ses enfants.

Chaque branche de projection est non redondante :

- \`growthProj\` (tokens moyens par itération terminée) attrape une montée régulière.
- \`lastDeltaProj\` (delta de la dernière itération multiplié par 2) attrape une rafale qu'une moyenne dilue.
- \`worstCaseSingleIter\` (fenêtre de contexte complète multipliée par la sortie maximale complète aux tarifs du modèle) est invariant au profil de croissance et attrape un saut en marche d'escalier dès l'itération 1.

C'est la branche du pire cas qui fait le vrai travail. À une tarification de classe opus (15 / 75 USD par 1M) avec un contexte de 200K et une sortie maximale de 64K :

\`\`\`
worstCaseSingleIter = 200 * 15 + 64 * 75
                    = 3,000 + 4,800
                    = 7,800 credits      (1 credit = $0.001)
\`\`\`

Tout solde inférieur à 7,800 crédits est protégé contre cette itération en rafale par la branche du pire cas et par rien d'autre.

La seconde condition de refus, \`runCostSoFar >= balance\`, est logiquement redondante : dès que la projection est positive, la première condition la couvre déjà. Elle n'existe que pour que le refus nomme le vrai mode de défaillance au lieu d'apparaître comme un dépassement de projection.

La formule de coût, pour la reproductibilité :

\`\`\`
inputCost  = inputRate  * promptTokens     / 1000
outputCost = outputRate * completionTokens / 1000
total      = inputCost + outputCost + fixedCost
\`\`\`

Les tarifs sont en USD par 1M de tokens ; le \`/1000\` convertit vers une unité de crédit où 1 crédit = $0.001. Arrondissez chaque sous-terme à 6 décimales avant de sommer, sinon deux implémentations de la même formule divergeront.

Trois contraintes honnêtes sur ce mécanisme :

**La garde par agent a besoin de deux itérations terminées.** Avec un seul échantillon, \`lastDelta == runCost == growth\`, donc \`lastDelta * 2 = 2 * runCost\`, et toute première itération consommant plus que \`budget/3\` s'auto-refuserait l'itération 2, même quand l'appel suivant est légitimement petit. La garde locataire n'a pas ce verrou : elle projette dès l'itération 1, où growth et lastDelta valent tous deux zéro, si bien que seule la branche du pire cas y contraint. C'est voulu, et c'est pourquoi le plafond de l'itération 1 appartient à la branche du pire cas.

**L'obsolescence aggrave l'écart.** Un solde rechargé toutes les 5 itérations (retombant à chaque itération quand les tarifs de coût ne sont pas fiables) ajoute une fenêtre d'obsolescence par-dessus l'écart de projection d'une itération. Une variante adaptative rafraîchit à chaque itération dès que le rythme de consommation dépasse 70% du solde.

**Le repli en cas de modèle inconnu est une vraie décision, avec un historique de bug.** Échouer de façon pessimiste sur les tarifs (repli sur le palier le plus élevé, 15 / 75 USD par 1M) mais indulgente sur le plafond (laisser la fenêtre de contexte à null pour que \`worstCase\` renvoie null et que la garde retombe sur growth seul). Un repli antérieur à 0.015 / 0.075 contournait silencieusement la garde dans son intégralité.

Les commentaires de la garde elle-même portent l'aveu : une couche de réservation atomique par tour a été prototypée puis annulée, parce qu'un dépassement d'au plus une itération a été jugé acceptable en échange d'un chemin d'appel plus simple. Et la pré-vérification est explicitement « un instantané, non faisant autorité » : la réconciliation post-exécution s'exécute toujours, et les deux peuvent diverger.

## Le moment de l'impact : ce que reçoit réellement l'appelant

Une interruption budgétaire est classée \`PARTIAL\`, et non \`FAILURE\`, dans le contrat de raison d'arrêt de cette plateforme : sortie exploitable mais tronquée. Elle ne lève pas d'exception, et une interruption budgétaire ayant produit des tokens est persistée avec le statut d'exécution \`COMPLETED\`, si bien que seule la colonne \`stop_reason\` porte le détail. Deux nuances, parce que les demi-vérités sont ici la façon dont un plafond trop serré reste invisible : une interruption budgétaire à zéro token est persistée en \`FAILED\`, et l'agrégation quotidienne des métriques compte bien chaque exécution arrêtée par le budget dans son décompte d'échecs. Ce qui est véritablement invisible, c'est la forme du dommage, pas son existence. Si vous ne surveillez que les taux d'erreur, un plafond trop serré refait surface des mois plus tard sous forme de régression de qualité.

**Tableau 2 : Où chaque raison d'arrêt est décidée (6 des 10 valeurs du contrat)**

| Raison d'arrêt | Catégorie terminale | Où elle est décidée | Ce que l'appelant doit faire |
|---|---|---|---|
| \`MAX_ITERATIONS\` | partial | A posteriori, après la sortie de boucle | Traiter la sortie comme tronquée ; augmenter n ou le budget |
| \`TIMEOUT\` | partial | A posteriori, après la sortie de boucle | Travail actif, au-delà du temps mural ; reprendre ou élargir |
| \`BUDGET_EXHAUSTED\` | partial | Garde avant itération, avant l'appel | Lire \`budgetScope\` (\`tenant\`, \`agent\`, \`parent_reservation\`, \`browser\`), décider recharge ou redimensionnement |
| \`LOOP_DETECTED\` | partial | En cours d'itération, après l'analyse des appels d'outils | Inspecter la signature répétée ; la tâche est mal formée |
| \`STOPPED_BY_USER\` | partial | Canal d'annulation | Conserver la sortie partielle |
| \`INACTIVITY_TIMEOUT\` | failure | Chien de garde, pas la boucle ; une passe ultérieure reclasse \`STOPPED_BY_USER\` | Devenu silencieux, a dû être tué ; enquêter sur le blocage |

\`BUDGET_EXHAUSTED\` est la seule valeur portant un tableau de portées. Un arrêt budgétaire qui ne vous dit pas quel plafond s'est déclenché vous oblige à deviner.

Le refus ne devrait pas être une exception. Une implémentation viable sort de la boucle et enregistre des métadonnées structurées : la raison d'arrêt, plus \`budgetScope\`, plus une chaîne \`denialReason\` nommant la branche de projection déclenchée :

\`\`\`
tenant balance X would be exceeded
(run=A + next=B [growth=..., lastDelta=..., worstCase=...])
\`\`\`

Utilisez les mêmes clés sur les chemins synchrone et streaming pour que les métriques ne puissent pas diverger.

Sur l'ensemble du terrain examiné, cinq comportements terminaux existent et ils ne sont pas interchangeables :

1. **Exception** : \`MaxTurnsExceeded\` (OpenAI Agents SDK), \`GraphRecursionError\` (LangGraph), \`UsageLimitExceeded\` (Pydantic AI), \`ModelCallLimitExceededError\` (LangChain).
2. **Résultat typé branchable** : le \`stop_reason\` d'AutoGen sur le \`TaskResult\`, le sous-type \`error_max_budget_usd\` du Claude Agent SDK, le \`exit_behavior='end'\` de LangChain avec un message AI injecté.
3. **Troncature silencieuse avec HTTP 200** : le \`max_tokens\` d'Anthropic positionne \`stop_reason: "max_tokens"\` et renvoie un succès ([Messages API](https://platform.claude.com/docs/en/api/messages)).
4. **Rejet HTTP 429** : la limite dure optionnelle d'OpenAI. Anthropic ne documente le 429 que pour \`rate_limit_error\` et place les problèmes de facturation en 402, si bien qu'aucun code de statut n'est documenté pour son plafond de dépense mensuel par palier ; vérifiez ce point contre vos propres logs.
5. **Réponse dégradée au mieux** : le \`max_iter\` de CrewAI, où l'agent « doit fournir sa meilleure réponse » ([CrewAI agents](https://docs.crewai.com/en/concepts/agents)).

Un conflit de sémantique à vérifier dans votre propre stack : les [budgets d'itération de LiteLLM](https://docs.litellm.ai/docs/a2a_iteration_budgets) renvoient un 429 avec le type d'erreur \`budget_exceeded\`, or par convention HTTP le 429 signifie « réessayez plus tard ». Pour un plafond organisationnel à réinitialisation temporelle, c'est défendable, puisque attendre finit bien par rendre la requête satisfiable. Pour un budget par exécution ou par agent, c'est faux : attendre ne le satisfait jamais, et la logique de réessai standard des SDK va marteler le mur. LiteLLM est ici le seul cas confirmé, pas une classe démontrée. Vérifiez ce que fait la politique de réessai de votre client face à un 429.

Ce qui devrait survivre à l'arrêt est l'autre moitié du contrat. Le [Claude Agent SDK](https://code.claude.com/docs/en/agent-sdk/agent-loop) est ce qui se rapproche le plus d'une conception de référence : le champ \`result\` (la réponse finale) n'est présent que sur le sous-type \`success\`, mais chaque sous-type d'erreur porte toujours \`total_cost_usd\`, \`usage\`, \`num_turns\` et \`session_id\`. Vous perdez la réponse, pas la session. Notez l'asymétrie : une \`query()\` en un coup lève une exception après avoir produit le résultat d'erreur, tandis qu'une session à entrée en streaming reste vivante.

Pourquoi cela compte commercialement, d'après un [rapport d'incident](https://github.com/anthropics/claude-code/issues/68430) : les seules options de l'opérateur étaient de « le laisser tourner et le regarder brûler le budget de session sur une boucle récursive qui n'aboutira jamais » ou de « le tuer et tout perdre, y compris le travail légitime accompli par les premiers agents ». Un plafond qui jette le travail partiel transforme un problème de coût en problème de perte totale, ce qui est précisément la raison pour laquelle les opérateurs désactivent les plafonds.

Un refus côté parent devrait suivre la même règle : non pas une erreur levée mais un résultat d'échec synthétisé nommant l'ancêtre et la portée.

\`\`\`
Cannot spawn child 'X': ancestor agent <id> has
insufficient free budget for reservation N
(scope=parent_reservation, BUDGET_EXHAUSTED)
\`\`\`

Enfin, rendez le plafond introspectable depuis l'intérieur de l'agent. La forme de réponse livrée :

\`\`\`
budget.{ unlimited, total, consumed,
         consumed_own, consumed_from_subagents,
         reserved_for_subagents, free,
         reset_mode, last_reset }

free = max(total - consumed - reserved_for_subagents, 0)
\`\`\`

Sur la branche unlimited, \`total\` et \`free\` sont null et \`reserved_for_subagents\` est renvoyé à 0. La règle explicite : si \`free\` est inférieur au budget d'un enfant, l'engendrement échoue avec \`scope=parent_reservation\`.

## Ce que chaque stack peut et ne peut pas appliquer

**Tableau 3 : Ce que chaque stack peut réellement appliquer** (limité aux plateformes examinées ; Google ADK et LlamaIndex ne l'ont pas été)

| Stack | Unité appliquée | Valeur par défaut | Comportement au plafond | Propage aux sous-agents ? |
|---|---|---|---|---|
| [Claude Agent SDK](https://code.claude.com/docs/en/agent-sdk/python) | USD par exécution (\`max_budget_usd\`), plus les tours | Les deux illimités | Sous-type de résultat typé \`error_max_budget_usd\` / \`error_max_turns\`, session préservée | \`usage\` exclut les tokens des sous-agents ; \`total_cost_usd\` les inclut |
| Anthropic Messages API | Tokens (\`max_tokens\`) | Aucune valeur par défaut ; vous devez la définir | HTTP 200 avec \`stop_reason: "max_tokens"\`, tronqué | N/A |
| OpenAI (compte) | USD par mois | Souple par défaut | Notification, ou 429 si la limite dure est activée | N/A |
| [OpenAI Agents SDK](https://openai.github.io/openai-agents-python/running_agents/) | Tours ([\`DEFAULT_MAX_TURNS = 10\`](https://github.com/openai/openai-agents-python/blob/main/src/agents/run_config.py)) | 10 | Lève \`MaxTurnsExceeded\` | Non documenté |
| [LangGraph](https://docs.langchain.com/oss/python/langgraph/graph-api) | Supersteps (\`recursion_limit\`) | Documentation contradictoire : 1000 depuis la v1.0.6 dans le runtime de graphe OSS, 25 dans le schéma \`Config\` du SDK et les retours de terrain | Lève \`GraphRecursionError\` | Deux bugs de propagation documentés (ci-dessous) |
| [LangChain middleware](https://reference.langchain.com/python/langchain/agents/middleware/model_call_limit/ModelCallLimitMiddleware) | Comptage d'appels uniquement, aucun budget en tokens ou en coût | Les deux limites à \`None\` | Configurable : \`exit_behavior='end'\` injecte un message, \`'error'\` lève | Non applicable |
| [Pydantic AI](https://pydantic.dev/docs/ai/api/pydantic-ai/usage/) | Tokens, requêtes, appels d'outils | \`request_limit=50\`, limites de tokens à \`None\` | Lève \`UsageLimitExceeded\` ; vérification pré-vol optionnelle | Non documenté |
| AutoGen ([conditions](https://microsoft.github.io/autogen/stable/reference/python/autogen_agentchat.conditions.html), [teams](https://microsoft.github.io/autogen/stable/reference/python/autogen_agentchat.teams.html)) | Tokens (\`TokenUsageTermination\`) | Valeurs par défaut d'équipe : \`termination_condition=None\`, \`max_turns=None\` | \`TaskResult\` typé avec une chaîne \`stop_reason\` | Portée équipe |
| [CrewAI](https://docs.crewai.com/en/concepts/agents) | Itérations (\`max_iter\`) | La documentation dit 20, la source dit 25 | L'agent « doit fournir sa meilleure réponse » | Non documenté |

Cinq choses que ce tableau dit et que la prose enfouirait :

**Presque tout est non borné par défaut.** Les \`max_turns\` et \`max_budget_usd\` du Claude Agent SDK sont tous deux sans limite ; les [équipes AutoGen](https://microsoft.github.io/autogen/stable/reference/python/autogen_agentchat.teams.html) énoncent platement que la discussion de groupe « tournera indéfiniment » ; les limites de dépense par siège Enterprise d'Anthropic sont illimitées par défaut lorsqu'aucune valeur par défaut n'existe à un quelconque niveau (les plafonds de palier de l'API, à l'inverse, s'appliquent toujours).

**Le seul bouton de coût de l'enquête sans valeur par défaut est le \`max_tokens\` d'Anthropic**, que le schéma de la Messages API vous impose de définir explicitement. C'est aussi le seul dont le dépassement renvoie un HTTP 200 avec du contenu tronqué. Le schéma documente désormais aussi le fait de le mettre à 0 pour préchauffer le cache de prompt, donc obligatoire ne veut pas dire plafond significatif.

**Le seul plafond en dollars par exécution de l'enquête est appliqué contre une estimation.** La page de suivi des coûts d'Anthropic avertit que \`total_cost_usd\`, le chiffre exact auquel \`max_budget_usd\` est comparé, consiste en des « estimations côté client, et non des données de facturation faisant autorité » calculées à partir d'une table de prix embarquée à la compilation, et dit : « Ne facturez pas les utilisateurs finaux et ne déclenchez pas de décisions financières à partir de ces champs. » Il est de plus évalué entre les tours, si bien que la dépense peut excéder la limite configurée d'un tour. C'est exactement la même garantie « budget plus une itération », dans le produit le mieux conçu du domaine.

**LangChain n'a aucun budget en tokens ni en coût.** \`ModelCallLimitMiddleware\` et \`ToolCallLimitMiddleware\` plafonnent des comptages d'appels, tous deux avec la valeur par défaut \`None\`, et un mainteneur a [confirmé le manque de budget en tokens en juillet 2026](https://forum.langchain.com/t/a-proposal-to-add-token-usage-budgets-to-langchain-agents-via-a-new-middleware-since-the-existing-limiters-only-cap-call-count-not-tokens/4147). Son paramètre \`exit_behavior\` reste néanmoins le mode de défaillance configurable le plus propre du domaine, et il mérite d'être copié.

**Pydantic AI est le seul stack doté d'une vérification pré-vol** : \`count_tokens_before_request\` (par défaut \`False\`) appelle l'API de comptage de tokens du fournisseur pour rejeter une requête hors budget avant qu'elle ne soit facturée. Il embarque aussi un piège : \`request_limit\` vaut silencieusement 50 par défaut, si bien que définir \`input_tokens_limit\` seul hérite d'un plafond de 50 requêtes à moins de passer \`request_limit=None\`.

**La propagation est la première façon dont un plafond devient décoratif.** Deux cas documentés : [LangChain deepagents #1698](https://github.com/langchain-ai/deepagents/issues/1698), où \`SubAgentMiddleware\` invoquait les sous-agents sans le paramètre \`config\`, si bien qu'ils tournaient toujours à la limite de récursion par défaut quel que soit un parent réglé à 150 ; et [langgraphjs #1524](https://github.com/langchain-ai/langgraphjs/issues/1524), où le \`recursionLimit\` de \`withConfig\` est silencieusement ignoré et où le message d'erreur qui en résulte vous dit de définir la clé même qui est ignorée.

Deux pièges de mesure qui défont silencieusement du code de budget naïf, tous deux issus du [document de suivi des coûts d'Anthropic](https://code.claude.com/docs/en/agent-sdk/cost-tracking) : le champ \`usage\` ne compte que la boucle de premier niveau et exclut les tokens des sous-agents (alors que \`total_cost_usd\` et \`model_usage\` les incluent), et les appels d'outils parallèles émettent plusieurs messages assistant partageant un même id de message avec un usage identique, si bien qu'un compteur qui somme l'usage par message compte double et se déclenche trop tôt. Dédupliquez par id.

Les limites de débit ne sont pas des limites de dépense et peuvent récompenser le chemin coûteux : les tokens d'entrée mis en cache sont facturés à 10% mais ne comptent pas dans les limites de tokens d'entrée par minute sur la plupart des modèles, et \`max_tokens\` n'entre pas du tout dans les limites de tokens de sortie par minute ([limites de débit](https://platform.claude.com/docs/en/api/rate-limits)).

## Les gardes de boucle bornent n ; les budgets bornent le coût étant donné n

Un détecteur de boucle et un budget répondent à des questions différentes. Le détecteur borne le nombre d'itérations qui ont lieu ; le budget borne ce que ces itérations peuvent coûter. Aucun ne se substitue à l'autre.

Seuils réels issus d'un détecteur livré, avec deux conditions de déclenchement indépendantes :

| Condition | Clé | Échelons d'escalade | Arrêt dur |
|---|---|---|---|
| Appels identiques | nom d'outil + arguments triés, hachés | avertissement à 5 | 15 |
| Appels consécutifs | total des appels d'outils, toute signature | 15, 25, 35 | 40 |

Le plafond consécutif est délibérément élevé pour que des opérations de lot légitimes ne soient pas tuées. Les deux arrêts durs sont configurables par agent, et les échelons intermédiaires sont **dérivés** (avertissement identique = \`ceil(stop/3)\` min 2 ; échelons consécutifs = \`ceil(stop * 3/8)\`, \`5/8\`, \`7/8\`) pour que l'échelle de sévérité reste monotone à n'importe quelle valeur personnalisée, avec des arrêts minimaux imposés.

L'échelle n'est pas qu'une affaire de journalisation : chaque échelon injecte un message dans le contexte de l'agent avant l'arrêt, en escaladant d'une note informative jusqu'à « 1 itération restante, ARRÊTEZ les outils, RÉPONDEZ MAINTENANT » puis à la terminaison. L'intention de conception affichée est que les motifs répétitifs devraient être automatisés sous forme de workflows plutôt que bouclés.

La lacune de couverture qu'il faut nommer : ce détecteur ne compte que quatre noms d'outils. Tout autre appel d'outil est invisible pour les deux compteurs, si bien qu'une boucle sur un outil non suivi ne produit jamais de \`LOOP_DETECTED\`. Vérifiez la couverture équivalente dans votre propre stack avant de faire confiance à une garde de boucle.

Ne comptez pas sur le modèle pour remarquer son propre gaspillage. RedundancyBench a annoté 200 trajectoires (filtrées parmi les exécutions réussies collectées) avec plus de 8,000 étapes annotées, et la meilleure détection automatisée au niveau de l'étape des étapes redondantes a obtenu 24.88% (72.50% au niveau de la trajectoire) ([arXiv 2605.29893](https://arxiv.org/abs/2605.29893)). Le plafond doit être mécanique.

Autres valeurs par défaut de bornage d'exécution issues de la même implémentation, à titre de point de repère : itérations maximales 100, délai d'exécution 3600 s, 16,000 tokens maximum par tour, et un chien de garde d'inactivité de 5 minutes dont la surcharge par agent n'accepte que 0 (désactivé) ou de 10 à 7200 secondes, de sorte qu'une valeur égarée ne puisse pas armer un chien de garde à l'échelle de la seconde.

Le temps mural mérite une ligne comme plafond de dernier recours. Un incident documenté a consommé 4 millions de tokens en moins de 5 minutes ([claude-code #68619](https://github.com/anthropics/claude-code/issues/68619)), plus vite que n'aurait réagi tout échantillonnage par tour ou par rafraîchissement de solde. C'est une inférence tirée d'un seul incident, pas une bonne pratique sourcée, mais l'arithmétique est difficile à contester.

## Le test d'un vrai plafond

Six points, chacun répondable à partir de vos propres logs :

1. Un refus nomme-t-il la portée qui s'est déclenchée ?
2. La vérification est-elle synchrone et antérieure à l'appel suivant ?
3. La réponse terminale est-elle typée, non réessayable, et porte-t-elle le registre des coûts plus une poignée de reprise ?
4. Le plafond se propage-t-il aux sous-agents, prouvé par un test qui fixe une limite parent et vérifie qu'un enfant en hérite ?
5. Le ratio de granularité \`g\`, le budget divisé par l'itération du pire cas bornée, vaut-il au moins 3 ? L'article compagnon sur le dimensionnement dérive ce plancher et montre que la plupart des plafonds monétaires par étape ne l'atteignent pas.
6. Le plafond a-t-il déjà réellement refusé, sur la fenêtre observée ?

La garantie honnête : un budget en cours d'exécution borne le coût à **le budget plus une itération**, pas au budget. Une réservation avant engendrement est le seul mécanisme à dépassement nul, et il ne couvre que l'enfant.

Si la même formule existe dans deux runtimes, la parité vaut l'effort d'ingénierie. Un fichier de fixtures partagé, contenant des cas nommés et consommé à la fois par un test paramétré JUnit et par un lanceur de tests Node, est le moyen le moins coûteux d'empêcher les deux de diverger, et l'arrondi doit être aligné sous-terme par sous-terme. Notez la limite : une fixture ne couvre que les cas qu'elle contient. Une fixture qui amorce des tarifs explicites n'exerce jamais le chemin de repli modèle-inconnu d'un côté ni de l'autre, ce qui est exactement là où les deux implémentations décrites ici ont divergé d'un ordre de grandeur, et une fixture qui n'instancie que la garde locataire ne remarque jamais que les deux gardes agent utilisent des opérateurs de comparaison différents.

Énoncez ce qui n'est pas connu. Aucun taux de base publié n'existe sur la fréquence à laquelle les agents en production s'emballent. Le catalogue le plus solide décline explicitement toute prétention à la prévalence et ne revendique que l'existence et la récurrence dans des projets développés indépendamment. Raisonnez à partir du mécanisme et de l'ordre de grandeur plutôt que d'inventer une fréquence.

Et soyez réaliste sur l'ordre de grandeur. D'après les lignes d'incidents du même catalogue de 2026 ([arXiv 2606.04056](https://arxiv.org/abs/2606.04056)), les dépassements documentés se regroupent entre quelques centaines et quelques milliers de dollars : environ $2,150 de dépense non voulue dans un cas, $235 en quatre jours par un seul utilisateur, un dépassement de 70% au-delà d'un budget d'optimiseur. Comparez cela à l'anecdote d'emballement la plus republiée du domaine, [« We spent $47,000 running AI agents »](https://todatabeyond.substack.com/p/we-spent-47000-running-ai-agents), qui ne nomme aucune entreprise, ne produit ni facture, ni dépôt, ni configuration, ni logs, et qui a ensuite été amplifiée sous une seconde signature et via une douzaine d'articles SEO se citant les uns les autres. Ses propres chiffres hebdomadaires sont $127, $891, $6,240 et $18,400, dont la somme fait $25,658, et non $47,000, et une montée de coût sur quatre semaines contredit la « boucle de 11 jours » du même article. Le vrai profil de risque est silencieux, récurrent, et de l'ordre de quelques milliers de dollars.
`;

export default content;
