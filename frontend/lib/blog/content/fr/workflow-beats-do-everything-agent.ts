// workflow-beats-do-everything-agent - fr
// Translated from the English body; keep the structure identical to it
// (10 h2, 6 tables, 5 fenced formula blocks, 21 source links). The formulas are
// fenced on purpose: an inline formula over ~45 chars overflows the page on a phone.
const content = `## Le chiffre que j'ai supprimé

Une version antérieure de cet article affirmait qu'un workflow cadré revient « environ dix fois moins cher » qu'un agent qui fait tout. Ce chiffre n'avait aucune dérivation, aucune hypothèse et aucune source derrière lui, il a donc disparu.

Il n'existe aucune source publiée pour le remplacer. Aucun benchmark d'éditeur, article de recherche ou trace ne mesure le même travail implémenté une fois sous forme de pipeline cadré et une fois sous forme d'agent autonome, avec le coût et le taux de succès instrumentés des deux côtés. La référence du genre, [Building Effective Agents](https://www.anthropic.com/engineering/building-effective-agents) d'Anthropic, ne contient aucun chiffre de coût ; son traitement du sujet tient en deux phrases : « Les systèmes agentiques échangent souvent latence et coût contre de meilleures performances sur la tâche, et il faut se demander quand ce compromis a du sens » et « La nature autonome des agents implique des coûts plus élevés, et un risque d'erreurs qui se cumulent ». La seconde phrase affirme la thèse de cet article sans y attacher le moindre chiffre.

Les multiplicateurs qui circulent ne sont pas interchangeables. « 3-10x » circule comme une affirmation sur le nombre d'appels LLM et « 5-30x » comme une affirmation sur les tokens par tâche, ni l'un ni l'autre avec une source primaire traçable. Le seul chiffre dont les hypothèses sont visibles, [12.4x tiré d'un billet dev.to](https://dev.to/awxglobal/why-your-llm-agent-costs-10x-more-than-your-estimate-4o78), est construit à partir d'un prompt système de 800 tokens renvoyé à chaque appel, de 4 tours par requête et de 3.5 appels d'outil à 250 tokens de surcoût chacun, face à une référence qui ne compte que le prompt de l'utilisateur et la réponse du modèle. Son 12.4x est donc un énoncé sur le ratio de surcoût prompt-et-outils à un nombre de tours fixé, pas sur un travail entier ; changez le nombre de tours et le multiple bouge. C'est la seule dérivation du genre que vous puissiez auditer, et l'auditer montre qu'elle ne mesure pas ce que mesurent les deux autres fourchettes. Les billets de type « framework » qui comparent réellement les formes ([Sashido](https://www.sashido.io/en/blog/agentic-workflows-roi-without-expensive-agents), [LindleyLabs](https://lindleylabs.com/blog/agent-or-pipeline-a-decision-framework-for-ai-engineers), [Retool](https://retool.com/resources/ai-workflows-vs-agents)) impriment des formules et des arbres de décision sans aucune variable renseignée.

Il existe bien un 10x authentique et sourcé dans ce domaine, et ce n'est pas l'affirmation que j'ai supprimée : le [multiplicateur de lecture de cache d'Anthropic vaut exactement 0.1x l'entrée de base](https://platform.claude.com/docs/en/about-claude/pricing), donc un token d'entrée en cache est précisément dix fois moins cher qu'un token non mis en cache. Cela s'applique à la composante « préfixe en cache » des tokens d'entrée, rien de plus.

Règle pour la suite de cet article : chaque ratio imprimé ici est dérivé sur la page à partir d'hypothèses explicites. Aucun n'est cité.

## Où part l'argent : deux fonctions de coût, une quadratique

Le mécanisme d'abord, pour que chaque chiffre qui suit soit réfutable par simple inspection.

L'API Messages est sans état. L'appel \`i\` d'un agent transporte donc \`In_i = B + (i-1)g\`, où \`B = S + T + P0\` est le prompt système, les définitions d'outils et la charge utile initiale, et où \`g = a + r\` est la croissance par tour (la sortie de l'assistant plus le résultat d'outil réinjecté). En sommant sur N appels :

\`\`\`
I(N) = N*B + g*N*(N-1)/2
\`\`\`

Le premier terme est la taxe de préfixe, linéaire en N. Le second est la taxe d'accumulation, quadratique en N. Un token produit au tour \`i\` est relu en entrée \`(N - i)\` fois de plus.

Le coût du workflow cadré vaut :

\`\`\`
C_wf = sum over k of [ p_in^k*(s_k + t_k + d_k) + p_out^k*o_k ]
\`\`\`

Linéaire en K, parce que l'étape k reçoit ses entrées déclarées \`d_k\` et jamais la transcription des étapes 1 à k-1. Notez le \`^k\` sur le prix : un workflow peut router chaque étape vers un modèle différent sans pénalité. Un agent à boucle unique paie une réécriture complète du cache de son préfixe accumulé chaque fois qu'il change de modèle en cours de conversation, donc en pratique il fige un seul modèle pour toute la boucle. Le routage appel par appel dans une architecture d'agent exige une frontière de sous-agent, qui est elle-même une décision de cadrage et coûte un nouveau préfixe par sous-agent.

La borne de décomposition est exacte, pas rhétorique. Découper une boucle de N appels en K segments cadrés divise le terme d'accumulation par exactement K, puisque \`K * g*(N/K)^2/2 = g*N^2/(2K)\`. Un découpage en trois étapes plafonne l'économie d'accumulation à 3x. Tout 10x revendiqué pour un workflow à trois étapes provient de la taxe de préfixe, de la largeur de charge utile ou du routage de modèle, pas d'une rupture de la quadratique.

Les définitions d'outils sont une composante réelle de B. Anthropic rapporte qu'une installation MCP à cinq serveurs (GitHub, Slack, Sentry, Grafana, Splunk) [consomme environ 55,000 tokens de définitions](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool) avant que le modèle ne fasse le moindre travail. Au tarif public, cela fait $0.275 par appel non mis en cache sur Opus 4.8, et ce chiffre maintient le nombre de tokens constant de part et d'autre de la frontière de tokenizer décrite à la fin de cet article : à traiter comme un plancher plutôt que comme une estimation.

## L'exemple chiffré : triage du support, toutes les hypothèses affichées

Le travail : classer un ticket, retrouver le compte, chercher dans la KB, rédiger une réponse, la relire.

| Paramètre | Symbole | Valeur agent | Valeur workflow | D'où cela vient |
|---|---|---|---|---|
| Prompt système | S | 1,500 tok | 250-600 tok par étape | Hypothèse : un prompt qui fait tout contre quatre prompts étroits |
| Définitions d'outils | T | 6 outils, 900 tok | 30 tok par étape, 120 tok au total | Hypothèse ; aucun éditeur ne publie de chiffre par outil |
| Charge utile initiale | P0 | 600 tok | 600 tok (texte du ticket) | Le même ticket des deux côtés |
| Préfixe | B = S+T+P0 | 3,000 tok | n/a (par étape) | Somme de ce qui précède |
| Croissance par tour | g = a + r | 1,000 tok | 0 (aucune transcription transportée) | a=300 en sortie, r=700 de résultat d'outil |
| Appels / étapes | N / K | 8 appels | 4 étapes LLM + 2 déterministes | Jugement sur ce que le travail exige |
| Prix | p_in / p_out | $3.00 / $15.00 par MTok | identique | [Tarif public de Sonnet 4.6](https://platform.claude.com/docs/en/about-claude/pricing), vérifié le 2026-07-22 |

Chacun de ces comptes de tokens est une hypothèse déclarée, pas une mesure. Aucun ne provient d'un endpoint de comptage de tokens.

**Grand livre de l'agent, N=8.** L'entrée croît d'exactement g = 1,000 par appel, la table est donc une progression arithmétique de 3,000 à 10,000 ; seuls les extrémités et la deuxième ligne sont informatifs.

| Appel | Tokens d'entrée | Entrée cumulée | Tokens de sortie | Coût cumulé |
|---|---|---|---|---|
| 1 | 3,000 | 3,000 | 300 | $0.0135 |
| 2 | 4,000 | 7,000 | 300 | $0.0300 |
| ... | +1,000 à chaque fois | | 300 | |
| 8 | 10,000 | 52,000 | 300 | $0.1920 |

Le total de 52,000 correspond à la forme close : \`8*3,000 + 1,000*(8*7/2) = 24,000 + 28,000\`. Coût : 52,000 x $3/MTok = $0.156 d'entrée, plus 2,400 x $15/MTok = $0.036 de sortie. **$0.192 par ticket.**

**Grand livre du workflow, même travail.** Les colonnes de préfixe et de charge utile sont séparées, parce que c'est de cette séparation que viennent les deux leviers ci-dessous.

| Étape | LLM ? | Modèle | Système | Défs d'outils | Données déclarées | Entrée | Sortie | Coût de l'étape |
|---|---|---|---|---|---|---|---|---|
| Classification | oui | Sonnet 4.6 | 250 | 30 | 600 | 880 | 60 | $0.00354 |
| Recherche du compte | non | (déterministe) | 0 | 0 | 0 | 0 | 0 | $0 |
| Récupération KB | non | (déterministe) | 0 | 0 | 0 | 0 | 0 | $0 |
| Construction de la requête KB | oui | Sonnet 4.6 | 250 | 30 | 70 | 350 | 25 | $0.00143 |
| Rédaction | oui | Sonnet 4.6 | 600 | 30 | 1,810 | 2,440 | 400 | $0.01332 |
| Relecture | oui | Sonnet 4.6 | 600 | 30 | 450 | 1,080 | 80 | $0.00444 |
| **Total** | | | **1,700** | **120** | **2,930** | **4,750** | **565** | **$0.02273** |

Les données déclarées de l'étape de rédaction valent ticket 600 + label 60 + faits du compte 250 + les 3 meilleurs extraits KB 900 = 1,810.

Ce grand livre ne chiffre que les tokens du modèle. Sur le produit hébergé, un nœud de workflow terminal porte en plus un forfait de 1 crédit ($0.001), ce qui ajoute $0.006 pour ces 6 nœuds et porte le workflow à $0.0287. Tous les ratios ci-dessous sont la comparaison en tokens seuls ; la version « coût hébergé » du chiffre phare est indiquée là où elle compte pour la première fois.

**Chiffre phare pour cette configuration : 8.4x** ($0.192 / $0.02273), ou 6.7x une fois incluse la redevance hébergée par nœud. Le ratio sur les seuls tokens d'entrée est de 10.9x (52,000 / 4,750). Le ratio sur les tokens de sortie n'est que de 4.2x (2,400 / 565), et c'est ce qui tire le chiffre agrégé sous 10x : les deux formes doivent réellement rédiger la même réponse d'environ 400 tokens.

Deux leviers survivent au cache, mais amoindris par lui. La **taxe de préfixe** : sans cache, l'agent renvoie huit fois son préfixe « fait tout » de 3,000 tokens (24,000 tokens) contre les 1,820 tokens de prompts système et de définitions d'outils du workflow au total, soit 13.2x. Avec un cache incrémental, la composante de préfixe de l'agent tombe à \`1.25B + 0.1(N-1)B\` = 5,850 tokens effectifs, et le levier retombe à 3.2x. La **largeur de charge utile**, sur une base instantanée : à son dernier appel, l'entrée de l'agent contient 7,600 tokens de charge utile accumulée et de transcription d'outils (600 initiaux plus 7 x 1,000 de croissance) contre l'étape la plus large du workflow, dont l'entrée déclarée est de 1,810 tokens, soit 4.2x. Cette comparaison change de base comptable dès que le cache est actif, parce que les deltas plus anciens de l'agent sont relus à 0.1x : sur une base cumulée en tokens effectifs, la croissance de charge utile de l'agent coûte \`1.25*1,000*7 + 0.1*1,000*21\` = 10,850 tokens contre les 2,930 tokens de données déclarées du workflow, soit 3.7x. Le mécanisme derrière les deux est qu'une entrée déclarée est une projection de l'observation brute.

## Le ratio est une fonction de N, et N est tout l'argument

Sous les hypothèses de l'exemple, le coût de l'agent vaut \`0.0015N^2 + 0.012N\` dollars. Vérification à N=8 : $0.096 + $0.096 = $0.192.

| N (appels agent) | Coût agent | Coût workflow | Ratio | Ce que représente ce N |
|---|---|---|---|---|
| 2 | $0.030 | $0.0227 | 1.3x | Court-circuit : « spam, escalade » |
| 4 | $0.072 | $0.0227 | 3.2x | Usage minimal des outils, aucun retry |
| 6 | $0.126 | $0.0227 | 5.5x | Une recherche relancée |
| 8 | $0.192 | $0.0227 | 8.4x | L'exemple chiffré |
| 12 | $0.360 | $0.0227 | 15.8x | L'agent explore la KB |
| 20 | $0.840 | $0.0227 | 37.0x | Errance, ou un ticket réellement difficile |

En résolvant \`0.0015N^2 + 0.012N = 0.022725R\` : le ratio vaut 10 à N = 8.94 appels et vaut 3 à N = 3.84 appels. Citer un ratio de coût sans citer N n'a aucun sens.

Une contrainte d'équité sur cette table : les lignes à N élevé ne sont légitimes que si le travail exige réellement autant d'appels. Un agent qui prend 20 appels pour faire ce qu'un workflow fait en 4 manifeste de l'errance, ce qui est un constat de compétence et doit être argumenté comme tel, pas glissé en douce dans une table de coûts.

## Mettez correctement l'agent en cache, ensuite comparez

Les ratios workflow contre agent publiés comparent en général avec un agent sans cache. C'est dans le cache que part l'essentiel de l'écart : chiffrez-le avant de comparer.

Les [multiplicateurs de cache](https://platform.claude.com/docs/en/build-with-claude/prompt-caching) d'Anthropic sont exacts : écriture de cache 5 minutes = 1.25x l'entrée de base, écriture 1 heure = 2x, lecture de cache = 0.1x. Le seuil de rentabilité est publié lui aussi : le cache 5 minutes est rentable dès une seule lecture (1.25 + 0.1 = 1.35x contre 2x sans cache) ; le cache 1 heure demande deux lectures (2 + 0.2 = 2.2x contre 3x).

Avec un cache incrémental multi-tours, l'entrée effective de l'agent devient :

\`\`\`
1.25B + 0.1(N-1)B + 0.1g(N-2)(N-1)/2 + 1.25g(N-1)
\`\`\`

Le coefficient quadratique passe de \`p_in*g/2\` à \`0.1*p_in*g/2\`, une remise d'exactement 10x sur précisément le terme que le workflow battait.

À N=8, l'entrée effective par appel donne 3,750 / 1,550 / 1,650 / 1,750 / 1,850 / 1,950 / 2,050 / 2,150 = 16,700 tokens contre 52,000 sans cache. Soit $0.0501 d'entrée plus $0.036 de sortie = **$0.0861**. Le cache réduit le coût de l'agent de 55% et fait tomber le chiffre phare de 8.4x à **3.8x**.

Notez ce qui domine une fois le cache actif : l'écriture à 1.25x du delta de chaque tour, \`1.25 * 1,000 * 7 = 8,750\` des 16,700 tokens effectifs, soit 52%. L'avantage restant du workflow, c'est la taxe de préfixe et la largeur de charge utile, pas la quadratique de renvoi.

Le cache aplatit l'écart sans le refermer. À N=20, l'agent avec cache coûte $0.241 contre $0.840 sans cache, soit encore 10.6x le workflow, parce que 19 écritures de cache à 1.25x plus 20 tours de contenu généré sont incompressibles.

Le workflow ne capte ici presque rien du même bénéfice. Le préfixe minimum cachable sur Sonnet 4.6 est de 1,024 tokens (vérifié dans la documentation du cache le 2026-07-22 ; il est de 512 sur Fable 5 et Mythos 5, de 2,048 sur Opus 4.7, et de 4,096 sur Opus 4.6, Opus 4.5 et Haiku 4.5). Le préfixe stable de chaque étape de workflow est ici son prompt système plus ses définitions d'outils, 280 à 630 tokens, sous le seuil sur chacun de ces modèles. Les préfixes sous le minimum échouent silencieusement : aucune erreur n'est renvoyée et \`cache_creation_input_tokens\` comme \`cache_read_input_tokens\` valent 0. Notez que router une étape vers Haiku 4.5 relève son seuil à 4,096 : la configuration routée ci-dessous est donc plus loin d'être cachable, pas plus près.

Le correctif actionnable a un seuil de rentabilité publié. Consolidez le préfixe d'une étape à fort volume au-dessus du minimum cachable du modèle sur lequel elle tourne et placez le breakpoint après lui, pour que chaque exécution de cette étape lise à 0.1x. Sur le TTL de 5 minutes, c'est rentable dès la deuxième requête, et [les lectures de cache rafraîchissent le TTL gratuitement](https://platform.claude.com/docs/en/build-with-claude/prompt-caching) : une étape sollicitée au moins toutes les cinq minutes reste chaude indéfiniment au prix de l'écriture.

Une chose que le cache ne fait pas : les tokens en cache [occupent toujours la fenêtre de contexte](https://platform.claude.com/docs/en/build-with-claude/context-windows). Il change ce que vous payez pour ces tokens, pas le fait qu'ils comptent. Il ne sauve personne de l'épuisement du contexte ni de la dégradation du contexte.

## La grille à quatre cases, et où se trouve vraiment un 10x réel

Router la classification, la requête KB et la relecture vers Haiku 4.5 ($1/$5) et la seule rédaction vers Sonnet 4.6 fait tomber le workflow à $0.0165 par ticket (classification $0.00118 + requête KB $0.000475 + rédaction $0.01332 + relecture $0.00148).

| | Workflow, même modèle ($0.0227) | Workflow, routé ($0.0165) |
|---|---|---|
| **Agent, sans cache ($0.192)** | 8.4x | 11.7x |
| **Agent, avec cache ($0.0861)** | 3.8x | 5.2x |

Mon choix par défaut est la case en haut à droite inversée : mettre l'agent en cache, router le workflow, et tabler sur 5.2x. En dessous d'environ N=4, je ne construirais pas le workflow du tout, parce que le ratio est sous 3x et que le coût de construction ne se rembourse pas (voir la section finale) ; au-dessus d'environ N=12, la quadratique décide à votre place.

Un agent à boucle unique doit faire tourner son unique modèle figé à chaque appel. Un agent Opus 4.8 ($5/$25) n'est pas un remplacement à l'identique des mêmes comptes de tokens, parce qu'Opus 4.7 et les suivants utilisent un tokenizer plus récent qui produit environ 30% de tokens en plus pour le même texte. En appliquant cette majoration : environ 67,600 en entrée et 3,120 en sortie, soit $0.338 + $0.078 = $0.416, contre les $0.0165 du workflow routé, soit 25.3x. C'est un argument de routage, pas un argument de fenêtre de contexte.

L'affirmation générale tirée de la seule discipline de contexte, dérivée : avec l'agent en cache et les deux formes sur un même modèle, le ratio vaut 2.8x à N=6, 3.8x à N=8 et 5.8x à N=12. Donc environ 3x à 6x sur une plage de N plausible, et tout ce qui dépasse relève d'une décision de cache ou de routage, qui doit être énoncée comme telle.

La structure tarifaire des éditeurs rend le routage prévisible. Tous les modèles Anthropic actuels facturent la sortie à exactement 5x l'entrée (Opus 4.8 $5/$25, Sonnet 4.6 $3/$15, Haiku 4.5 $1/$5). Tous les modèles OpenAI actuels facturent la sortie à 6x l'entrée (gpt-5.6-sol $5.00/$30.00, gpt-5.4 $2.50/$15.00, gpt-5.4-mini $0.75/$4.50), à la seule exception de gpt-5.4-nano à 6.25x ($0.20/$1.25). [DeepSeek](https://api-docs.deepseek.com/quick_start/pricing) facture la sortie à exactement 2x l'entrée en cache-miss (deepseek-v4-flash $0.14/$0.28, deepseek-v4-pro $0.435/$0.87). Au sein d'un même éditeur, le mélange entrée:sortie pilote le profil de coût davantage que le choix du modèle. Et le palier batch est un cinquième levier, réservé au workflow, pour les étapes non sensibles à la latence : une remise forfaitaire de 50% sur l'entrée comme sur la sortie chez Anthropic et OpenAI, la moitié du tarif standard chez Gemini, cumulable avec les multiplicateurs de cache chez Anthropic.

## Où ce modèle casse

| Condition | Effet sur le ratio | Ampleur ici | Pourquoi cela arrive |
|---|---|---|---|
| Travaux courts (N<4) | S'effondre, peut s'inverser | 1.3x à N=2 | L'agent court-circuite ; le workflow exécute toujours son chemin fixe |
| Travail dominé par la sortie | Tend vers 1 | 2.2x pour un rapport de 5,000 mots à N=8 | Les deux formes rédigent le même livrable |
| Gros contexte partagé | Peut s'inverser | 5D contre 1.95D sur un document de 50k | Le workflow le renvoie à chaque étape sauf s'il met le document en cache d'abord |
| Recherche parallélisable en largeur | Favorise le multi-agent | +90.2% sur une éval d'un éditeur | L'autonomie achète une couverture que le pipeline ne peut pas énumérer |
| Recherche d'outils (chargement différé) | Réduit l'avantage de préfixe | l'éditeur annonce >85% de définitions en moins | L'agent capte l'économie de préfixe sans être ré-architecturé |
| Panoplie > 30-50 outils | Favorise le workflow, sur la justesse | non chiffré | La précision de sélection d'outil se dégrade |
| Surcoût d'outils dépendant du modèle | Décale B | 290 à 804 tok selon les modèles | Coût fixe de prompt système avant tout schéma |
| Facturation d'outils côté serveur | Hors du modèle en tokens | $10 pour 1,000 recherches web | Facturation à l'appel, pas au token |
| Changement de tokenizer / retour au tarif plein | Invalide les chiffres datés | ~30% de tokens en plus ; $2/$10 vers $3/$15 | Nouveau tokenizer à partir d'Opus 4.7 ; le tarif d'introduction de Sonnet 5 prend fin le 31 août 2026 |

Quatre d'entre elles méritent d'être développées.

**Travaux dominés par la sortie.** Un rapport de 5,000 mots fait environ 6,700 tokens de sortie, $0.1005 à $15/MTok des deux côtés. En gardant les entrées de l'exemple (agent $0.156, workflow $0.01425), le ratio est de $0.2565 / $0.1145 = 2.2x, et il continue de baisser à mesure que le livrable grossit.

**Le gros contexte partagé** est le cas d'inversion. Si chaque étape a besoin du même document de 50k tokens, un workflow à cinq étapes le renvoie cinq fois (5D) alors qu'un agent à huit appels avec cache paie 1.25D + 7 x 0.1D = 1.95D. Le workflow ne l'emporte que s'il place le document en premier, avant l'instruction propre à l'étape, et le met en cache (1.25D + 4 x 0.1D = 1.65D).

**La recherche parallélisable est le cas publié le plus fort contre l'ensemble de cette thèse.** Anthropic rapporte qu'un système multi-agent, un Claude Opus 4 chef orchestrant des sous-agents Claude Sonnet 4, [a dépassé un Claude Opus 4 en agent unique de 90.2%](https://www.anthropic.com/engineering/multi-agent-research-system) sur leur éval de recherche interne, et dans le même billet que les agents consomment environ 4x les tokens d'une interaction de chat tandis que les systèmes multi-agents en consomment environ 15x. C'est l'autonomie qui achète un gros gain de qualité pour un gros multiple de coût, et c'est aussi une architecture d'agent qui fait du routage de modèle étape par étape à travers une frontière de sous-agent. La condition préalable posée par Anthropic elle-même est le cadrage honnête : cela ne paie que quand la valeur de la tâche couvre le multiplicateur, et cela convient mal quand tous les agents ont besoin du même contexte ou quand le travail comporte de nombreuses dépendances.

**La recherche d'outils** est le contre-argument le plus fort contre la thèse de la taxe de préfixe en particulier. Anthropic indique que le chargement différé des outils [réduit typiquement le contexte de définitions d'outils de plus de 85%](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool), en ne chargeant que les 3-5 outils nécessaires, ce qui permet à un agent qui fait tout de capter l'essentiel de l'économie de préfixe sans être ré-architecturé. C'est une affirmation d'éditeur sans méthodologie divulguée, et elle doit être traitée comme telle. Le déclencheur donné par Anthropic : utilisez la recherche d'outils à partir de 10 outils, ou quand les définitions dépassent 10k tokens. La même page indique que la précision de sélection d'outil se dégrade dès que l'on dépasse 30-50 outils disponibles, ce qui donne à l'argument de cadrage un pied dans la fiabilité qui ne dépend pas du tout de l'arithmétique des tokens.

## Coût par exécution réussie, et la condition qui inverse l'argument

La comparaison qui compte vraiment est \`C/q\` : le coût divisé par le taux de succès propre à chaque forme. Un taux de réexécution du workflow de 20% multiplie son coût par 1.2 et ramène le chiffre phare de 8.4x à 7.0x. Un agent qui se rattrape dans le contexte paie à la place quelques tours supplémentaires, facturés quadratiquement.

La régularité sur des exécutions répétées s'effondre plus vite que la justesse affichée. Dans l'article [tau-bench retail](https://arxiv.org/abs/2406.12045) original de 2024, les meilleurs agents à appel de fonctions de l'époque étaient assez irréguliers pour que pass^8 tombe sous 25%. La production consiste à exécuter le même travail de nombreuses fois : pass^k est donc la bonne métrique, pas pass@1, et c'est ce point structurel qui vaut ici, pas un quelconque taux absolu de 2024.

Le succès décroît aussi avec la longueur de la tâche d'une manière qui rend la réduction de périmètre surlinéaire en fiabilité. [Le modèle de demi-vie de Toby Ord](https://www.tobyord.com/writing/half-life) prédit que l'horizon de succès à 80% vaut environ 1/3 de l'horizon à 50%, celui à 90% environ 1/7 et celui à 99% environ 1/70 ; l'auteur dit explicitement que le modèle est ajusté sur une seule suite de tâches et que sa généralité est inconnue. [Les mesures de METR](https://arxiv.org/abs/2503.14499) montrent des horizons temporels à 80% environ 5x plus courts que les horizons à 50%, ce qui est plus abrupt que le 3x du modèle de demi-vie : les deux encadrent l'effet plutôt qu'ils ne se confirment. Et l'échec est structurel, pas seulement un score plus bas : [l'étude HORIZON](https://arxiv.org/html/2604.11978v1) portant sur plus de 3,100 trajectoires attribue 72.5% des échecs à des causes de niveau processus (erreur d'environnement, erreur d'instruction, erreur de planification, historique accumulé) et rapporte une transition abrupte d'une robustesse partielle vers un échec quasi systématique. Cette même étude soutient que la décomposition seule n'est pas la solution : elle appelle à une planification hiérarchique et à une vérification au moment de l'exécution, pas simplement à un découpage de la tâche.

Le modèle adverse le plus fort est [celui de Zartis](https://www.zartis.com/ai-agent-cost-optimisation-why-token-cost-is-the-wrong-number-to-optimise/) :

\`\`\`
total_cost_per_task = (token_cost + infrastructure_cost) / reliability_rate
                      + failure_rate * human_remediation_cost
\`\`\`

Leur exemple chiffré rend une architecture 5x plus chère par appel ($0.05 contre $0.01) 5.7x moins chère au total (~$8,835/jour contre ~$50,100/jour) dès que la fiabilité passe de 70% à 95%. Leurs comptes de tokens, taux horaires et minutes de remédiation sont les hypothèses déclarées de leur article, pas des mesures, et leurs deux architectures diffèrent par la largeur de contexte plutôt que par l'autonomie. La structure de l'argument tient malgré tout.

Résolvez-le sur ces chiffres. Workflow $0.0227, agent avec cache $0.0861, delta $0.0634 par ticket. Si un échec coûte \`H\` en remédiation humaine et que le taux de succès de l'agent dépasse celui du workflow de \`dq\`, l'agent gagne quand \`dq * H > 0.0634\`. Avec un analyste à $100/heure et 10 minutes par remédiation, H = $16.67 : un avantage de 0.38 point de pourcentage sur le taux de succès suffit. À 5 minutes et $80/heure, H = $6.67 et le seuil est de 0.95 point. Disons-le clairement : **dès que le taux de remédiation humaine n'est pas négligeable, le ratio de tokens cesse d'être le terme décisif.** Le 3.8x sur lequel ce seuil a été construit est une erreur d'arrondi face à un point de différence de taux de succès, et même le 8.4x sans cache n'exige qu'un avantage de 1.02 point pour basculer (delta $0.1693 face à H = $16.67). Cela coupe dans les deux sens, et c'est la raison pour laquelle l'argument de fiabilité en faveur du cadrage (horizons plus courts, moins d'outils, contrats inter-étapes vérifiés) compte davantage que l'argument de coût que tout cet article vient de dériver.

Dépenser plus n'achète pas non plus de justesse. Sur [GAIA](https://hal.cs.princeton.edu/gaia), un agent utilisant o3 Medium a coûté $2,828.54 pour 28.48% de justesse tandis que Gemini 2.0 Flash a coûté $7.80 pour 32.73%. Dans le même [programme d'évaluation](https://arxiv.org/abs/2510.11977), un effort de raisonnement plus élevé a réduit la justesse dans la majorité des 36 configurations modèle-benchmark testées.

## N est un résultat, pas une entrée

Tout ce qui précède traite N comme un paramètre. En production, il est émergent, et c'est pourquoi le ratio a une longue traîne.

La traîne n'est pas une montée graduelle, c'est une fonction en escalier, et c'est ce qui la rend difficile à garder. Un incident de production en garde la trace : une exécution a tourné en régime de croisière pendant plusieurs itérations à environ 70k tokens de prompt et 700 de complétion chacune, assez bon marché pour qu'une projection fondée sur la moyenne continue d'approuver la suivante, puis une seule itération a explosé à environ 2M tokens. Une moyenne mobile dilue précisément cela.

La borne qui l'attrape ne fait aucune moyenne :

\`\`\`
projectedNext       = max(growth projection, last delta x 2, worstCaseSingleIter)
worstCaseSingleIter = cost(full context window, full max output)
\`\`\`

Ce second terme est invariant au profil de croissance, et c'est tout l'intérêt. Chiffré sur une ligne de classe Opus à 200k de contexte et 64k de sortie maximale à $15/$75 par MTok, une itération dans le pire cas vaut 200,000 x $15/MTok + 64,000 x $75/MTok = $3.00 + $4.80 = $7.80. Une seule itération de ce modèle peut plausiblement coûter plus qu'un petit solde de compte : le garde-fou se déclenche donc dès la première itération de croisière au lieu de parier sur la moyenne.

L'estimation de coût échoue vers le cher plutôt que vers le bon marché pour la même raison : un modèle sans ligne tarifaire retombe sur $15/$75 par MTok, le palier le plus élevé de l'instantané, parce qu'un repli antérieur proche de zéro contournait silencieusement le garde-fou budgétaire tout entier.

La fin d'une exécution doit être classée avant même de pouvoir calculer un coût par succès. Une taxonomie de production énumère exactement 10 raisons d'arrêt réparties en 3 catégories terminales : succès (COMPLETED) ; partiel (MAX_ITERATIONS, TIMEOUT, BUDGET_EXHAUSTED, LOOP_DETECTED, STOPPED_BY_USER), défini comme « terminé proprement mais sans avoir achevé la tâche comme prévu, la sortie est exploitable mais tronquée ou prématurée » ; et échec (CANCELLED, NO_TOOLS, ERROR, INACTIVITY_TIMEOUT). TIMEOUT et INACTIVITY_TIMEOUT tombent délibérément dans des catégories différentes : dépasser un budget d'horloge est partiel, ne produire aucun token, aucune réflexion, aucun appel d'outil ni aucun résultat d'outil dans la fenêtre du watchdog est un échec.

L'ancrage de l'étape déterministe rend la comparaison concrète. Un nœud de workflow terminal, réussi ou échoué, coûte un forfait de 1 crédit ($0.001) sur le produit hébergé ; seuls les nœuds ignorés sont gratuits, et l'auto-hébergé enregistre la même ligne de 1 crédit dans le grand livre pour l'observabilité mais ne la déduit jamais, parce que le solde est illimité. Au tarif public de $3/MTok de Sonnet 4.6, un crédit vaut 333 tokens d'entrée au prix public ; sur le produit hébergé, la marge LLM cloud de 1.8x ramène cela à environ 185 tokens, si bien que tout prompt au-delà d'environ 186 tokens coûte plus cher qu'une étape déterministe entière. Seuls 4 des quelque 60 types de nœuds de la palette (Agent, Classify, Guardrail, Browser Agent) invoquent réellement un LLM.

## Comment refaire ce calcul sur vos propres chiffres

1. **Mesurez les tokens.** Chaque compte de l'exemple ci-dessus est une hypothèse. Remplacez-les par l'endpoint de comptage de tokens du fournisseur, sur du vrai texte de ticket, de vrais schémas d'outils et un vrai extrait de KB.
2. **Mesurez N à partir de traces existantes, ne l'estimez pas.** Le ratio est à peu près quadratique en N : un N faux est une erreur au carré sur le chiffre phare.
3. **Classez un mois d'exécutions terminées par raison d'arrêt et par catégorie terminale** avant de citer le moindre chiffre de coût par succès. Les fins partielles et les fins en échec ont des coûts de remédiation différents, et une seule des trois catégories compte comme une exécution réussie.

Deux choses que ce modèle ne contient pas, et qu'il ne faut pas non plus en déduire. Il ne dit rien de la qualité de la sortie : il chiffre des tokens, et aucune mesure de taux de succès ne se cache derrière le moindre chiffre qu'il contient. Et il ignore le coût d'ingénierie, qui est le terme qui tranche la plupart de ces arbitrages en pratique. Mon estimation personnelle, énoncée comme une hypothèse au même titre que tout le reste ici : un workflow à cinq étapes avec des contrats inter-étapes déclarés coûte environ trois jours-ingénieur à construire et une demi-journée par mois à maintenir, contre une demi-journée pour câbler un agent à six outils. Au même tarif de $100/heure utilisé plus haut pour la remédiation, cela fait environ $2,000 de plus au départ et environ $400/mois de plus en récurrent. Face au delta de $0.0634 par ticket de l'agent avec cache, le seul écart initial demande environ 31,500 tickets pour être remboursé, et l'écart de maintenance en demande environ 6,300 par mois en plus. En dessous de ce volume, la ligne de la table de seuil de rentabilité sur laquelle vous vous trouvez est celle qui dit : construisez l'agent.
`;

export default content;
