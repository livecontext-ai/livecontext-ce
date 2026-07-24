// size-an-ai-agent-budget - fr
// Translated from the English body; the structure must stay identical to it.
// Formulas and code samples are fenced on purpose: an inline code span over
// ~45 chars overflows the page on a phone. The hedges are load-bearing here.
const content = `Un article compagnon soutient que la plupart des budgets d'agent sont des nombres qui n'ont jamais refusé un seul appel, et détaille la mécanique d'application : de quoi est fait un objet budget, pourquoi un plafond en cours d'exécution ne peut jamais arrêter que l'appel qui suit celui qui coûte cher, et ce que chaque stack sait réellement faire respecter. Celui-ci répond à la question qui vient ensuite. Vous êtes convaincu que le plafond doit être réel. Quel nombre mettez-vous dans la case ?

La réponse courte, c'est que vous ne pouvez pas le choisir à l'intuition, parce que la quantité que vous bornez est asymétrique à droite, superlinéaire en nombre d'itérations, et s'étale sur trois ordres de grandeur selon les types d'étape. La réponse longue est le reste de cet article : un modèle générateur que vous pouvez reproduire, un facteur de sécurité dérivé, un plancher en dessous duquel un plafond monétaire ne peut pas être appliqué du tout, et la taille d'échantillon nécessaire avant d'avoir le droit de citer un quantile de queue.

**Divulgation.** Les constantes d'implémentation et le mécanisme de réservation décrits ci-dessous proviennent de l'\`agent-service\` de LiveContext, la plateforme à laquelle appartient ce blog. Lisez-les comme les choix d'un système, vérifiables dans sa source community edition, et non comme une pratique de terrain issue d'une enquête. Les prix sont des instantanés de catalogue illustratifs ; la méthode est indépendante des prix.

## Dimensionner un budget par étape que vous pouvez calculer

Une étape qui exécute \`n = k+1\` itérations de modèle avec \`k\` appels d'outil a un coût attendu piloté par le prompt fixe \`P0\`, la charge utile d'entrée \`I\`, les tokens \`r\` retournés par résultat d'outil, la sortie \`O\` par tour, et un terme d'accumulation proportionnel à \`n(n-1)/2\`. Le modèle générateur de chaque ligne ci-dessous :

\`\`\`
prompt_i = (P0 + I) + (i-1) * (O_turn + r)      i = 1..n
\`\`\`

**Tableau 4a : paramètres d'archétype par étape.** Ce sont des **jeux de paramètres construits, pas des traces de production mesurées**, publiés pour que chaque colonne dérivée puisse être reproduite.

| Archétype d'étape | P0 + I | r par résultat d'outil | O par tour | n |
|---|---|---|---|---|
| Classification | 1,000 | n/a | 30 | 1 |
| Rédaction augmentée par récupération | 2,000 | 6,000 | 60 tour outil, 500 final | 2 |
| Recherche multi-outils | 2,500 | 3,000 | 80 tour outil, 800 final | 7 |
| Résumé de document long | 120,300 | n/a | 1,500 | 1 |
| Étape navigateur | 1,800 | 8,000 | 120 tour action, 250 final | 13 |

**Tableau 4b : dimensionnement par étape.** Les prix proviennent d'un instantané de catalogue du dépôt et sont illustratifs, ce ne sont pas des tarifs fournisseur en direct. La méthode est indépendante des prix. La dernière colonne utilise un S=3 plat comme facteur illustratif ; la section suivante le remplace par un facteur dérivé.

| Archétype d'étape | Classe de modèle, tarif catalogue ($/1M entrée, sortie) | Tokens entrée / sortie | Itérations n | Coût attendu | Plus grande itération unique (x première) | Budget à S=3 (plat) |
|---|---|---|---|---|---|---|
| Classification | classe flash-lite, 0.25 / 1.50 | 1,000 / 30 | 1 | $0.00030 | 1.0x | $0.0009 |
| Rédaction augmentée par récupération | classe haiku, 1.00 / 5.00 | 10,060 / 560 | 2 | $0.01286 | 4.6x | $0.0386 |
| Recherche multi-outils | classe sonnet, 3.00 / 15.00 | 82,180 / 1,280 | 7 (6 appels d'outil) | $0.2657 | 8.6x | $0.797 |
| Résumé de document long | classe flash, 0.30 / 2.50 | 120,300 / 1,500 | 1 | $0.0398 | 1.0x | $0.1195 |
| Étape navigateur | classe gpt-5.4, 2.50 / 15.00 | 656,760 / 1,690 | 13 (12 actions, instantanés de 8,000 tokens) | $1.667 | 40x | $5.00 |

Le rapport de 40x entre la première et la dernière itération sur l'étape navigateur dicte la conception : une projection par moyenne mobile sous-estime l'itération fatale de plus d'un ordre de grandeur. C'est pourquoi une projection a besoin d'une branche pire cas qui ignore entièrement le motif de croissance, comme le dérive l'article compagnon.

### Le facteur de sécurité est dérivé, pas deviné

\`\`\`
S = (n_q / n_p50) ^ alpha        avec alpha = dlogC / dlogn

alpha dans [1, ~2.3] : proche de 1 pour une étape en un
seul appel, proche de 2 pour une étape dominée par
l'accumulation, et supérieur à 2 quand la première
itération est bon marché face au contexte accumulé.

alpha :  classification ~1.0 | document long ~1.0
         rédaction RAG 1.77 | recherche multi-outils 1.81
         navigateur 2.03
\`\`\`

Une étape dont le p99 utilise deux fois plus d'appels d'outil que son p50 a besoin d'un S d'environ 2.0 si elle est en un seul appel, mais de 3.4 à 4.1 si elle est riche en outils. Deviner « 2x » sous-dimensionne systématiquement exactement les étapes qui ont besoin de marge. Ces alphas sont des tangentes au point de fonctionnement ; un lecteur qui mesure la sécante sur une plage de n observée obtiendra un nombre légèrement plus grand. Vérification par la sécante contre le modèle exact : recherche n 7 à 14 coûte 3.66x (la tangente prédit 2^1.81 = 3.51) ; navigateur n 13 à 26 coûte 4.06x (la tangente prédit 2^2.03 = 4.08).

Corollaire : **doubler le nombre d'itérations autorisées quadruple approximativement le plafond monétaire.** « Augmentons un peu le nombre max d'itérations » est une décision budgétaire à 4x.

Cela fait aussi d'un plafond d'itérations un mauvais plafond monétaire. Avec une valeur par défaut de plateforme de 100 itérations max, le plafond de l'archétype navigateur est de 40,374,000 tokens de prompt = $101.11 pour une seule étape (contre $1.667 attendus), et celui de l'archétype recherche de 15,496,000 tokens = $46.62 (contre $0.266). En tant que points de données calculés plutôt que plage : 7.7x le n attendu laisse 61x de marge monétaire sur l'étape navigateur ; 14.3x en laisse 175x sur l'étape recherche.

### Le plancher d'applicabilité

Parce que la projection par agent a besoin de deux échantillons, et se refuse d'elle-même quand une itération dépasse \`budget/3\`, le rapport de granularité doit satisfaire :

\`\`\`
g = B_step / cost_of_one_iteration  >=  3
\`\`\`

le plancher d'un budget par étape est donc de 3x l'itération pire cas. Face à l'itération pire cas non bornée du modèle, aucun des cinq budgets ne l'atteint : classification $0.0009 contre un plancher de $1.04 (g = 0.003), rédaction RAG $0.0386 contre $1.56 (g = 0.074), recherche $0.797 contre $4.68 (g = 0.51), document long $0.1195 contre $1.39 (g = 0.26), navigateur $5.00 contre $8.76 (g = 1.71).

**Tableau 5 : le plancher d'applicabilité** (prix de catalogue et fenêtres de contexte illustratifs ; substituez les vôtres)

| Classe de modèle | Itération pire cas, contexte non borné | Budget minimum applicable (3x) | Itération pire cas sous plafonds d'admission 30K/2K | Budget minimum applicable sous plafonds |
|---|---|---|---|---|
| flash-lite | $0.348 | $1.04 | $0.0105 | $0.032 |
| haiku | $0.520 | $1.56 | $0.040 | $0.120 |
| flash | $0.464 | $1.39 | $0.014 | $0.042 |
| sonnet | $1.560 | $4.68 | $0.120 | $0.360 |
| gpt-5.4 | $2.920 | $8.76 | $0.105 | $0.315 |

Tout plafond monétaire par étape inférieur au plancher de la colonne non bornée relève de la comptabilité, pas de l'application. Le remède, ce sont des **plafonds d'admission sur les entrées**, pas un budget plus grand : plafonner le prompt admis à 30K tokens et \`max_tokens\` à 2K fait chuter le plancher d'un facteur 13 à 33.

Mais les plafonds d'admission modifient le profil de coût de l'étape elle-même, donc \`B_step\` doit être redérivé sous ces plafonds, et les plafonds doivent d'abord être compatibles avec l'étape :

- **Étape recherche** : compatible telle quelle. Son plus grand prompt d'itération fait environ 21K, sous le plafond de 30K, donc son budget survit inchangé et g passe de 0.51 à 6.6.
- **Étape navigateur** : franchit les 30K vers l'itération 4 environ (chaque instantané ajoute 8,120 tokens). En ne gardant que les trois derniers instantanés, la plus grande itération tombe à environ 26K, le coût attendu à $0.754, le budget à S=3 à $2.26, et g à 21.5.
- **Étape document long** : un plafond de prompt à 30K la refuse purement et simplement, puisque son itération unique fait 120K tokens. Plafonner le prompt admis à sa propre taille d'entrée laisse encore g = 2.8, sous le plancher. Son n est fixé à 1, donc le levier de contrôle est ici la taille d'entrée elle-même, pas un plafond monétaire.

La règle des deux régimes : les étapes sous le point de bascule sont bornées par construction (n fixé à 1 ou 2, \`I\` petit) et doivent être contrôlées en plafonnant les entrées ; celles au-dessus sont les seules où un plafond monétaire fait un vrai travail. **Plafonnez les entrées sur les étapes bon marché, plafonnez l'argent sur les étapes chères.**

Une précision sur ce que « désactivé par défaut » signifie dans cette implémentation, parce qu'il est facile de le comprendre à l'envers. Le plafond pire cas est **toujours actif** pour tout modèle dont la ligne de catalogue porte une fenêtre de contexte et un nombre max de tokens de sortie : les deux gardes prennent \`max(growth, lastDelta*2, worstCase)\` inconditionnellement. Ce qui est livré désactivé par défaut, c'est le comportement séparé de fermeture (fail-**closed**) pour les modèles *dépourvus* de ces métadonnées (\`requireCtxWindow\`). La raison documentée est une fenêtre de migration : les instantanés de tarification hérités sans ces colonnes refuseraient sinon chaque tour de conversation.

### Quantiles, échantillons et faux arrêts qui se composent

Choisir le quantile, c'est choisir le taux de faux arrêts. Si \`B_step\` est le quantile q du coût légitime observé, le taux de faux arrêts par étape vaut exactement \`1-q\` par construction. C'est une décision produit, pas une décision statistique.

Réconciliez cela avec le facteur de sécurité avant d'utiliser les deux : la formule de S ci-dessus est l'estimateur qu'on utilise quand la queue des *coûts* est immesurable mais que la queue des *n* est connue. Le q que vous choisissez pour le quantile et le \`n_q\` que vous injectez dans S doivent être le même quantile. Pour une étape à n fixe, \`n_q / n_p50 = 1\` et S dégénère en 1 ; le quantile doit alors venir de la variance de la taille d'entrée.

Taille d'échantillon avant de pouvoir citer un quantile de queue : \`1/sqrt(n(1-q))\` est l'erreur standard relative du *nombre de dépassements* dans la queue, donc une estimation de ce nombre à plus ou moins 30% demande \`n ~ 11/(1-q)\`. Le p90 demande environ 111 exécutions, le p95 environ 220, le p99 environ 1,100, le p99.5 environ 2,200. Traitez ces valeurs comme des bornes inférieures : l'erreur sur la *valeur* en dollars du quantile dépend de la densité dans la queue, et pour une distribution de coûts asymétrique à droite elle est nettement pire. En dessous d'environ 200 exécutions, vous ne pouvez pas honnêtement revendiquer un p99, et vous devriez plutôt dimensionner à partir du pire cas structurel.

Les faux arrêts par étape se composent. Avec k étapes plafonnées chacune à son propre p99, et en supposant des coûts par étape indépendants et que chaque exécution parcourt les k étapes, la fraction d'exécutions qui touchent un plafond quelque part vaut \`1 - q^k\` :

\`\`\`
k = 3   ->  3.0%
k = 10  ->  9.6%
k = 20  -> 18.2%

Un plafond par étape au p95 sur 10 étapes tue 40.1% des exécutions.

Pour viser 1% au niveau exécution :  q_step = (1 - target)^(1/k)
  k=3  -> p99.666 | k=10 -> p99.900 | k=50 -> p99.980
\`\`\`

Une corrélation positive entre étapes (une entrée surdimensionnée au niveau de l'exécution qui en gonfle plusieurs d'un coup) abaisse le taux réel, donc traitez ces chiffres comme l'extrémité pessimiste.

Dimensionnez sur la distribution, pas sur la moyenne : le coût par étape est asymétrique à droite, la moyenne se situe autour du p70, et dimensionner dessus tue à peu près 30% des exécutions d'étape légitimes, soit \`1 - 0.7^k\` des exécutions.

Collectez cinq champs par exécution d'étape : tokens de prompt, tokens de complétion, nombre d'appels d'outil, id du modèle, raison d'arrêt terminale. Quatre des cinq sont exactement ce qu'une garde pré-itération consomme déjà : si vous pouvez appliquer, vous pouvez mesurer.

Pour calibrer n, deux points d'ancrage indépendants : les règles de dimensionnement d'Anthropic elles-mêmes (recherche de faits simple : 1 agent avec 3 à 10 appels d'outil ; comparaisons directes : 2 à 4 sous-agents avec 10 à 15 appels chacun ; recherche complexe : plus de 10 sous-agents), et une trajectoire moyenne de correction d'issue GitHub atteignant un pic de contexte de 48.4K tokens après 40 étapes, avec environ 1.0M tokens accumulés sur la trajectoire ([arXiv 2509.23586](https://arxiv.org/html/2509.23586v1)). Au regard de ces repères, la valeur par défaut de 25 supersteps largement rapportée (toujours la valeur par défaut du schéma langgraph-sdk, environ 12 appels d'outil dans une boucle ReAct) tue du travail réel, alors qu'un plafond à 200 étapes ne fait rien.

**La procédure :**

1. Collectez les exécutions d'étape avec les cinq champs.
2. Calculez le quantile par étape requis à partir de votre cible au niveau exécution : \`q_step = (1 - target)^(1/k)\`.
3. Vérifiez si votre échantillon le supporte : il vous faut au moins \`11/(1-q_step)\` exécutions, soit à k=10 et une cible de 1% au niveau exécution environ 11,000. **Si ce n'est pas le cas, arrêtez-vous ici et dimensionnez plutôt à partir du pire cas structurel borné (tableau 5).** N'utilisez le quantile que vous *pouvez* estimer que pour détecter que le plafond structurel est bien trop lâche, pas pour fixer le plafond. C'est le cas courant, et prétendre le contraire est la façon dont un plafond dimensionné au p95 finit par tuer 40% des exécutions.
4. Si l'échantillon le supporte, mesurez alpha en régressant \`log(cost)\` sur \`log(n)\`, et fixez \`B_step\` à \`q_step\`.
5. Vérifiez \`g >= 3\` contre l'itération pire cas **bornée**. En cas d'échec, ajoutez des plafonds d'admission plutôt que de relever le budget, et redérivez \`B_step\` sous ces plafonds.
6. Fixez le plafond d'exécution (section suivante).
7. Sur-alimentez délibérément une étape et confirmez que la raison d'arrêt se déclenche.

## Plafonds d'exécution, fan-out, et pourquoi sommer les plafonds d'étape est faux

La bonne borne d'exécution porte sur les **exécutions** de nœuds le long du chemin pire cas, pas sur les nœuds :

\`\`\`
B_run = max sur les chemins d'exécution P de
        somme sur les nœuds v de P de  m_v * B_v

m_v = M à l'intérieur d'un split de largeur M
    = L pour un corps de boucle
    = 1 sinon

Les branches exclusives contribuent en max, pas en somme.
\`\`\`

Sommer les plafonds par étape est faux de trois façons, et elles pointent dans des directions opposées :

1. **Cela sous-compte d'exactement M sur le sous-graphe éclaté.** Un pipeline de 3 nœuds dont la somme fait $0.837 a un vrai pire cas de $41.78 quand les deux derniers nœuds sont à l'intérieur d'un split de largeur 50.
2. **Cela sur-compte les branches exclusives** qui ne peuvent jamais s'exécuter toutes les deux.
3. **C'est statistiquement inatteignable.** Pour 10 étapes lognormales indépendantes avec \`p99/p50 = 3\`, chacune plafonnée à 3x sa médiane, la somme des plafonds se situe autour de 1.88x le vrai p99 du total de l'exécution. Traitez ce multiplicateur comme indicatif : les coûts réels des étapes sont partiellement corrélés, ce qui réduit l'écart. Un plafond qui ne peut essentiellement pas se déclencher ne peut pas non plus attraper une défaillance structurelle modérée.

**Mutualisation.** Un fan-out indépendant a besoin d'une marge relative *plus petite*, d'un facteur \`sqrt(M)\` :

\`\`\`
S_run = 1 + (S_step - 1) / sqrt(M)

S_step = 3:  M=5 -> 1.89 | M=10 -> 1.63 | M=50 -> 1.28 | M=200 -> 1.14
\`\`\`

Exemple chiffré : 50 branches de l'étape recherche (moyenne $0.2657) dimensionnées naïvement en \`M * B_step\` = $39.85, alors que le plafond mutualisé est de $17.00, soit un plafond d'exécution 2.3x plus serré à risque égal. Énoncez l'hypothèse d'indépendance haut et fort : si le coût d'une branche est piloté par une propriété au niveau exécution, par exemple une entrée surdimensionnée diffusée à chaque branche, les coûts sont totalement corrélés et l'économie disparaît entièrement.

Les plafonds par étape et par exécution ont des rôles différents, et c'est ce qui fixe leurs tailles. Le plafond d'étape est réglé finement, il est censé se déclencher de temps en temps, et il tronque la sortie d'une seule étape. Le plafond d'exécution est un coupe-circuit qui ne devrait approximativement jamais se déclencher, et chaque déclenchement est un incident à investiguer (M a explosé, une boucle est repassée par le fan-out, une entrée valait 100x la normale).

**Le fan-out a besoin d'un contrôle d'admission, pas d'interception.** Un plafond d'exécution appliqué comme une vérification en cours de route tue des branches en plein vol et produit un ensemble de résultats partiels non déterministe : 50 branches à $0.836 chacune sous un plafond d'exécution de $10 en terminent 11 sur 50 ; sous $20, 23 sur 50 ; et lesquelles gagnent dépend de l'ordre de démarrage. Réserver la totalité de \`M * b\` avant de lancer les branches transforme cela en « refus de démarrer », ce qui est explicite et peut être relancé.

Le mécanisme de réservation, tel qu'implémenté dans \`BudgetReservationService\` :

- Au moment du spawn, le montant demandé par l'enfant est réservé atomiquement sur **chaque ancêtre** de la chaîne d'appel, dans une seule transaction. Le premier refus lève une exception, donc la transaction annule la mise à jour de tous les ancêtres précédents. Aucune compensation manuelle n'existe.
- L'invariant obtenu : la somme des \`consumed\` sur tous les descendants de A reste dans le budget de A à chaque profondeur, sans parcours d'arbre à l'exécution sur le chemin chaud.
- Chaque réservation par ancêtre est un unique UPDATE SQL conditionnel, sans SELECT-puis-UPDATE, donc il n'y a pas de TOCTOU. Il incrémente la colonne reserved seulement quand le budget libre de l'ancêtre couvre la demande :

  \`\`\`
  free = credit_budget - credits_consumed - credits_reserved
  UPDATE ... SET credits_reserved = credits_reserved + :req
   WHERE id = :ancestor
     AND (credit_budget IS NULL OR free >= :req)
  \`\`\`

  Le succès est décidé par un nombre de lignes retournées égal à 1. Un ancêtre illimité satisfait la condition avec une écriture sans effet et retourne lui aussi 1.
- Dimensionnement de la réservation : une demande explicite l'emporte (une valeur négative est rejetée) ; sinon la valeur par défaut est le **minimum du budget libre sur tous les ancêtres**, ou zéro si tous les ancêtres sont illimités.
- Le règlement parcourt la même chaîne une fois à la terminaison de l'enfant et, par ancêtre, rembourse la réservation détenue et comptabilise le coût réel en une seule mise à jour, en écrivant \`consumed\` et \`consumed_from_subagents\` avec le même delta dans la même transaction, de sorte que l'invariant tient par construction. Les colonnes de réservation sont marquées non modifiables au niveau de l'ORM, pour qu'un flush accidentel ne puisse pas les réécrire silencieusement.
- Les réservations fuitées sont balayées **au démarrage**, pas par un timeout : un worker sans état ne peut posséder aucune réservation antérieure à lui, donc toute réservation détenue non nulle présente au démarrage est orpheline par définition et est effacée en une seule mise à jour. Le balayage ne doit jamais faire échouer le démarrage.
- La chaîne d'appel elle-même vit sur une clé réservée de la map de credentials, du plus proche au plus lointain, absente pour les invocations racine (la cascade est donc sans effet à la racine) et préfixée par l'agent qui lance le spawn pour chaque enfant.

Une preuve indépendante que c'est le verrou, et non le nombre, qui rend un plafond réel : dans une expérience contrôlée rapportée par un preprint de 2026 recensant 63 incidents ([arXiv 2606.04056](https://arxiv.org/abs/2606.04056)), un compteur de budget Python asyncio sujet aux courses a dépassé le plafond 30 fois sur 30, tandis qu'un compteur Python correctement verrouillé et un budget Rust à typage affine ont dépassé 0 fois sur 30 chacun.

La contrainte de dimensionnement du parent qui en découle : pour lancer M enfants détenant chacun un plafond b, le parent a besoin d'un budget **libre** d'au moins \`M * b\` au moment du spawn, pas du total attendu. Pour l'archétype recherche à M=50 et b=$0.797, le parent a besoin de $39.85 libres alors même que l'exécution coûtera en réalité environ $13.3. Dimensionnez un parent sur la dépense attendue et seule \`1/S\` des branches sera financée : à S=3, un budget libre de $13.29 divisé par une réservation de $0.797 finance 16 spawns sur 50 et en refuse 34.

## Lire les logs : du symptôme à la mauvaise dimension

**Tableau 6 : du symptôme à la dimension de budget qui est fausse**

| Ce que vous voyez | Quelle dimension est fausse | Signal de confirmation | Où c'est traité |
|---|---|---|---|
| Arrêt invisible : complétions d'apparence normale, sortie systématiquement plus courte | Réponse terminale / observabilité | La distribution des raisons d'arrêt montre des arrêts partiels alors que le statut persisté indique COMPLETED | Article compagnon, le moment de l'impact |
| Trop serré : le budget arrête à l'itération 2 ou 3 | Quantile de dimensionnement / facteur de sécurité | Les comptes de tokens des exécutions tuées se situent près du p50, les entrées ont l'air ordinaires | Le facteur de sécurité est dérivé |
| Décoratif : zéro arrêt budgétaire sur une longue fenêtre | Ordre de grandeur du plafond | Aucun refus dans la fenêtre observée ; coût d'exécution max observé très en dessous du plafond | Article compagnon, le test d'ouverture |
| Dépassement / plafond tardif : le coût réalisé dépasse le plafond d'un montant constant, à l'échelle du modèle, dans la queue | Point d'application / projection | Le pire exactement là où l'étape est la plus chère | Article compagnon, sur l'écart d'une itération |
| Mauvais rattachement de portée : ~100% des refus au niveau grossier | Portée / ordre des gardes | Les plafonds par étape ne mordent jamais | Article compagnon, les cinq parties d'un objet budget |
| Famine de fan-out : N branches lancées, moins de N exécutions | Dimensionnement du parent / politique de réservation | Refus attribués à la réservation du parent, pas au budget de l'enfant | Plafonds d'exécution et fan-out |
| Fuite de réservation : refus croissants au fil des jours, mêmes entrées et même M | Cycle de vie des réservations | Réservations détenues jamais réglées | Plafonds d'exécution et fan-out |
| Mauvaise unité : nombres d'itérations identiques, coûts étalés sur un ordre de grandeur | Unité | Le coût moyen par itération s'étale sur ~430x entre archétypes ($0.00030 classification contre $0.128 navigateur) | Dimensionner un budget par étape |

Un budget configuré n'est pas un budget appliqué, et il en existe des preuves livrées en production. LiteLLM acceptait \`max_budget\` et \`budget_duration\` sur les modèles ajoutés dynamiquement via son API, persistait les valeurs, et ne les appliquait jamais, alors que la configuration identique dans le fichier de démarrage fonctionnait ([issue #25799](https://github.com/BerriAI/litellm/issues/25799), fermée par une PR ultérieure). Un défaut jumeau concernait des budgets qui ne se réinitialisaient jamais après l'expiration de leur durée ([#25495](https://github.com/BerriAI/litellm/issues/25495)). Vérifiez l'application par un test. Ne la présumez pas à partir de la présence d'un champ.
`;

export default content;
