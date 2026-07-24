// the-niche-data-advantage - fr
// Translated from the English body; structure identical. The evidence-register
// markers (cited / derived / "my judgment") and every concession are load-bearing:
// do not turn a hedge into a confident claim. Fenced formulas stay fenced.
const content = `## La sélection de données comme obligation de rafraîchissement, chiffrée

Vous n'acquérez pas des lignes. Vous prenez en charge une obligation de rafraîchissement. Un seul paramètre mesuré, r, la fraction annuelle de vos enregistrements qui deviennent faux, fixe quatre choses à la fois : le coût de maintenance, la cadence de rafraîchissement, le terme de maintenance dans le seuil de rentabilité construire-versus-acheter, et combien de temps la copie volée par un concurrent reste utile. Un paramètre pilote quatre décisions, mais le Résultat 4 ci-dessous montre que vous devez le mesurer par champ, et seulement après avoir acheté deux prérequis : un oracle indépendant et un coût de vérification par enregistrement connu k.

Cet article chiffre la thèse des données de niche au lieu de la louer, et il défend d'abord la thèse opposée, aussi fermement que les preuves le permettent. Il corrige aussi le slogan retiré du blog, « cent lignes que vous comprenez valent mieux qu'un million auxquelles vous vous fiez à moitié », qui tel qu'écrit est faux : la dernière section donne la condition sous laquelle il tient, et montre que cette condition disparaît généralement.

Contrat de preuve. Chaque affirmation est l'une de trois choses : citée avec un lien, dérivée avec l'arithmétique sur la page, ou étiquetée mon jugement. Les nombres travaillés (N, k, r, les précisions) sont des hypothèses illustratives, signalées à chaque usage, pas des mesures. Là où la recherche n'a rien donné, l'article le dit plutôt que d'estimer.

Délimitation du périmètre. Le coût du contexte, l'application et le dimensionnement du budget, les schémas de piste d'audit, la rétention d'audit, et la transformation d'un jeu de données qualifié en workflow opérationnel sont des articles compagnons. Celui-ci porte sur la sélection des données et son économie, et il s'arrête à la décision d'acquérir.

Lecteur cible : un fondateur ou responsable technique qui choisit dans quelles données investir avant de construire un agent ou une automatisation par-dessus.

## Le meilleur argument contre (à lire en premier)

La thèse du fossé des données propriétaires est contestée, et les sceptiques détiennent la meilleure base de preuves.

Lambrecht and Tucker, [Can Big Data Protect a Firm from Competition?](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2705530) (2015), font passer les données au test VRIN et constatent qu'elles échouent généralement : les mégadonnées sont rarement inimitables ou rares, des substituts existent, et la ressource rare est la boîte à outils managériale autour des données, pas les données. Leurs contre-exemples sont des entrants (Airbnb, Uber, Tinder) qui battent des acteurs installés qui détenaient déjà les données pertinentes.

Casado and Lauten, [The Empty Promise of Data Moats](https://a16z.com/the-empty-promise-of-data-moats/) (a16z, 2019), soutiennent que les effets de réseau de données sont généralement des effets d'échelle de données, et que les effets d'échelle saturent. Dans leur cas de chatbot de support, au-delà d'environ 40% des requêtes collectées, plus de données n'ajoute aucun avantage, et la couverture d'intention plafonne près de 40% : elle n'atteint jamais la pleine automatisation.

Varian, [NBER WP 24839](https://www.nber.org/papers/w24839) (2018), note que la précision statistique évolue comme la racine carrée de la taille de l'échantillon, il faut donc quatre fois plus de données pour diviser par deux votre erreur, et que les ensembles d'entraînement et de test d'ImageNet étaient fixés pendant les années des plus grands gains de précision, de sorte que ces gains ne peuvent pas être attribués à davantage de données.

Hestness et al., [arXiv:1712.00409](https://arxiv.org/abs/1712.00409), constatent que l'erreur de généralisation décroît selon une loi de puissance en fonction de la taille du jeu de données avec des exposants entre -0.07 et -0.35. Puisque le multiple de données pour diviser l'erreur par deux est 2^(1/beta) :

\`\`\`
beta = 0.07 -> 10x data cuts error 14.9%; halving needs ~19,972x data
beta = 0.15 -> 10x data cuts error 29.2%; halving needs ~102x data
beta = 0.35 -> 10x data cuts error 55.3%; halving needs ~7x data
\`\`\`

Cela coupe dans les deux sens : des exposants plats signifient que l'avantage de 100x d'un concurrent apporte peu, mais que votre 3x n'apporte presque rien.

Chiou and Tucker, [NBER WP 23815](https://www.nber.org/papers/w23815) (2017), exploitent des réductions de rétention induites par l'UE (Bing de 18 mois à 6, Yahoo de 13 à 3) et constatent peu de dégradation mesurable de la précision de recherche, concluant que « la possession de données historiques confère moins d'avantage en part de marché qu'on ne le suppose parfois ». Allcott, Castillo, Gentzkow, Musolff and Salz, [NBER WP 33410](https://www.nber.org/papers/w33410) (2025), constatent que l'élimination des frictions de demande double la part de Bing tandis que les mandats de partage de données ont de faibles effets. Le fossé, c'était la distribution et les valeurs par défaut.

En face à face, le grand corpus générique continue de gagner. [Li et al.](https://arxiv.org/html/2305.05862) rapportent que GPT-4 bat BloombergGPT (50B de paramètres, 363B de tokens financiers propriétaires plus 345B généraux, selon l'[article BloombergGPT](https://arxiv.org/pdf/2303.17564)) sur ConvFinQA 0-shot 76.48% contre 43.41%, FiQA-SA 5-shot 88.11% contre 75.07%, et Financial PhraseBank 5-shot 0.97 contre 0.51 F1. [Nori et al.](https://arxiv.org/abs/2311.16452) battent Med-PaLM 2 sur les neuf jeux de données MultiMedQA en utilisant GPT-4 générique plus du prompting, sans pré-entraînement ni fine-tuning spécifique au domaine. Et Ovadia et al., [Fine-Tuning or Retrieval?](https://arxiv.org/html/2312.05934v3), constatent que le RAG bat systématiquement le fine-tuning pour l'injection de connaissances (Mistral 7B sur une tâche d'actualité : base 0.481, RAG 0.875, fine-tune 0.504). Si la valeur de vos données se réalise dans une fenêtre de contexte, quiconque obtient les documents capture la même valeur sans aucun entraînement.

Deux résultats de robustesse attaquent la moitié « un million de lignes auxquelles vous vous fiez à moitié » du slogan. [Subramanyam, Chen and Grossman](https://arxiv.org/abs/2510.03313) mesurent des exposants de qualité d'environ 0.173 (traduction automatique) et 0.401 (modélisation causale du langage), tous deux bien en dessous de 1, de sorte que la taille effective du jeu de données décroît de façon sublinéaire avec la qualité. [Muennighoff et al.](https://arxiv.org/abs/2305.16264) (NeurIPS 2023) constatent que, sous un budget de calcul fixe avec des données contraintes, jusqu'à quatre époques de tokens répétés sont quasi indiscernables de données uniques fraîches.

Le petit côté échoue sur sa propre arithmétique. Le CI à 95% sur une proportion à p=0.5 est 1.96*sqrt(p(1-p)/n) : n=100 donne plus ou moins 9.80 points, n=1,000 donne 3.10, n=1,000,000 donne 0.098. Et P(zero occurrences) = (1-rate)^n, donc 100 lignes curées ont 36.6% de chances de ne contenir aucune instance d'un mode de défaillance de fréquence 1%. Il vous faut environ 299 lignes pour être sûr à 95% de voir une fois un événement 1-sur-100, environ 2,995 pour un 1-sur-1,000. Les petites données que vous comprenez ne peuvent pas voir leur propre queue.

Derrière tout cela se trouve le [Bitter Lesson](http://www.incompleteideas.net/IncIdeas/BitterLesson.html) de Sutton (2019), et deux échecs coûteux. IBM a assemblé l'un des plus grands corpus de santé propriétaires via environ $4B d'acquisitions (Merge environ $1B, [Truven $2.6B](https://techcrunch.com/2016/02/18/ibm-acquiring-truven-health-analytics-for-2-6-billion-and-adding-it-to-watson-health), plus Phytel et Explorys) et a [vendu Watson Health à Francisco Partners en 2022](https://www.fiercehealthcare.com/tech/ibm-sells-watson-health-assets-to-investment-firm-francisco-partners) pour un montant rapporté de ~$1.065B. Zillow a fermé Zillow Offers en November 2021 après une perte de $422M du segment Homes au T3 2021 (8-K T3 2021), le PDG invoquant l'imprévisibilité de la prévision des prix de l'immobilier ([AI Incident Database 149](https://incidentdatabase.ai/cite/149/)).

| Argument | Résultat mesuré | Source | Ce que cela ne tranche pas |
|---|---|---|---|
| Les données échouent au VRIN | Données rarement rares ou inimitables ; les entrants battent les acteurs installés détenteurs de données | Lambrecht and Tucker 2015 | Si les événements non publiés de première partie ont des substituts |
| Les effets d'échelle saturent | Couverture marginale plate au-delà d'environ ~40% des requêtes collectées ; la couverture d'intention plafonne près de 40% | Casado and Lauten 2019 | Les jeux de données dont la valeur est la fraîcheur, pas la couverture |
| Précision en racine carrée | 4x de données pour diviser par deux l'erreur d'estimation | Varian, NBER 24839 | La récupération, où la précision n'est pas le mécanisme |
| Rendements en loi de puissance | Exposants d'erreur -0.07 à -0.35 | Hestness et al. 2017 | Tout ce qui est hors entraînement de modèle |
| Réductions de rétention inoffensives | Bing de 18 à 6 mois, aucune perte de précision mesurable | Chiou and Tucker, NBER 23815 | Petits corpus opérationnels sans substitut d'échelle |
| La distribution est le fossé | Supprimer les frictions de demande double la part de Bing | Allcott et al., NBER 33410 | Marchés sans canal de placement par défaut |
| Le générique bat le domaine | GPT-4 devant BloombergGPT sur 3 des 3 tâches citées | Li et al. 2305.05862 | L'extraction structurée, où le même article montre les modèles fine-tunés gagnants |
| La récupération bat le fine-tuning | Mistral 7B : 0.875 RAG contre 0.504 fine-tune | Ovadia et al. 2312.05934 | Si les documents eux-mêmes sont obtenables |

## Ce que ces preuves ne couvrent pas

Presque toute la base anti-fossé concerne le pré-entraînement de modèles à l'échelle frontière. Le lecteur cible n'entraîne rien ; il sélectionne des données pour une fenêtre de contexte ou une réponse d'outil. Que 363 milliards de tokens financiers propriétaires n'aient pas réussi à battre GPT-4 dit peu de chose sur le fait que 40,000 lignes internes bien structurées fassent une bonne entrée d'agent.

Le problème miroir joue contre ma thèse avec une force égale : presque chaque gros gain de curation mesuré est aussi un résultat de corpus d'entraînement. [FineWeb-Edu](https://arxiv.org/abs/2406.17557) a retiré environ 91% de FineWeb (15T à 1.3T tokens) et a fait passer MMLU de 33% à 37% et ARC de 46% à 57% avec un budget fixe de 350B tokens, égalant le MMLU du corpus complet avec environ 10x moins de tokens que C4 et Dolma. [LIMA](https://arxiv.org/abs/2305.11206), [AlpaGasus](https://arxiv.org/abs/2307.08701) et DataComp sont aussi des résultats d'entraînement. Les transférer à la récupération est une hypothèse, et aucune étude localisée ne mesure les deux régimes sur la même tâche.

La seule étude à grande échelle côté récupération pointe dans l'autre sens, et cet article ne peut pas passer à côté. [Nourbakhsh et al., "When Retrieval Doesn't Help"](https://arxiv.org/abs/2606.04127), une étude RAG biomédicale portant sur 5 modèles, 10 jeux de données de QA, 4 méthodes de récupération et 4 corpus, a constaté que la récupération ne donnait que 1 à 2 points de plus qu'une base sans récupération, et que les sources curées par des experts ne faisaient pas mieux que les sources de profanes. La contrainte déterminante était la capacité limitée du modèle à utiliser les preuves récupérées, pas la qualité du corpus. C'est la seule mesure localisée dans le régime réel du lecteur, elle est spécifique à la curation, et sa conclusion est que la curation n'a rien apporté. Le domaine est biomédical, donc son transfert à d'autres tâches de récupération est lui-même non mesuré, mais c'est une preuve dans le bon régime et la thèse doit être rétrogradée au rang d'hypothèse devant elle.

Un résultat côté récupération soutient bien la curation. La précision du RAG suit un U inversé, culminant autour de 10 à 20 passages sur Natural Questions et chutant au-delà de 40 pour Gemma-7B, Gemma-2-9B, Mistral-Nemo-12B et Gemini-1.5-Pro ([arXiv:2410.05983](https://arxiv.org/html/2410.05983v1), ICLR 2025). Les dégâts viennent des négatifs difficiles, des documents presque pertinents qui obtiennent un score élevé et ne contiennent pas la réponse. La curation gagne sa place en supprimant les voisins erronés plausibles. Que ce mécanisme se transfère est un jugement non testé, pas une réponse à Nourbakhsh.

La lacune que le lecteur veut le plus voir comblée est vide. Je n'ai trouvé aucune mesure publique, méthodologiquement transparente, de ce qu'apporte la curation d'un corpus privé. Le contenu des fournisseurs affirme 95 à 99% de précision sans base de référence, méthodologie ni taille d'échantillon, ce que cet article ne citera pas. Je n'ai pas non plus trouvé un seul cas mesuré du jeu de données de niche d'une petite organisation battant un corpus générique dans un contexte d'agent en production.

La Superficial Alignment Hypothesis de LIMA est une arme contre ma thèse : la connaissance vient presque entièrement du pré-entraînement, et les petits ensembles curés enseignent le format et le style. Selon cette lecture, un corpus de niche curé achète du formatage, pas de la compréhension. La thèse ne peut donc pas être défendue sur le volume ou la connaissance. Si elle survit, elle survit sur la fraîcheur, la couverture d'une surface de décision spécifique, et le coût, qui sont mesurables, et que le reste de cet article instrumente.

## Le seul paramètre qui compte : r, et ce qu'il vous coûte

Mesurez-le, ne le citez pas, et mesurez-le selon un plan à deux instants : échantillonnez des enregistrements vérifiés à t0, revérifiez-les à t0+delta face à un oracle indépendant, comptez les champs modifiés, r = -ln(1-p)*365/delta_days. Rapportez l'intervalle de confiance : à p=0.3, n=100, le CI sur r va d'environ 21% à 39%, ce qui se propage dans chaque chiffre dérivé ci-dessous (Mb d'environ $7,400 à $15,400, un seuil construire-bat-rien d'environ 723 à 1,059 plutôt qu'un nombre unique et confiant). La critique du petit échantillon ci-dessus s'applique aussi à votre propre r.

Modèle, dérivé ici. Sous un risque constant, un enregistrement vérifié à t=0 est encore correct avec une probabilité A(t) = e^(-lambda*t), où lambda = -ln(1-r). Pour tenir un plancher de précision dans le pire cas A_floor, rafraîchissez tous les T ans :

\`\`\`
lambda        = -ln(1 - r)
T             = ln(1/A_floor) / lambda
passes / year = lambda / ln(1/A_floor)
maintenance   = N * k * lambda / ln(1/A_floor)
\`\`\`

Une vérification de cohérence : la décroissance mensuelle de contacts citée à 2.1% donne 12 * -ln(1-0.021) = 0.2547, et -ln(1-0.225) = 0.2549. Elles circulent comme des chiffres distincts et sont le même chiffre composé, à trois décimales près.

**Résultat 1.** Si vous rafraîchissez exactement à la cadence qui tient A_floor, la précision moyenne sur le cycle est (1-A_floor)/ln(1/A_floor), qui ne dépend que du plancher, pas de r. Un plancher de 95% donne toujours une moyenne de 97.48%, un plancher de 90% 94.91%, un plancher de 99% 99.50%. Le taux de changement fixe le prix du plancher, jamais la qualité que vous obtenez pour ce prix.

**Résultat 2.** Le nombre de passes par an est lambda/ln(1/A_floor), donc par rapport à un plancher de 90%, un plancher de 95% coûte 2.05x, un plancher de 99% 10.48x, un plancher de 99.9% 105.31x. Choisissez le plancher à partir du coût d'une mauvaise décision.

**Résultat 3.** La revérification continue doit se faire du plus ancien d'abord. Une revérification aléatoire au taux v atteint une moyenne en régime permanent de v/(v+lambda) mais n'a aucun plancher du tout : les âges des enregistrements sont distribués exponentiellement, donc une queue d'enregistrements est arbitrairement périmée quoi que vous dépensiez. Le plus-ancien-d'abord équivaut au traitement par lots et borne le pire cas ; l'aléatoire non.

**Résultat 4, le plus grand levier.** Mesurez r par champ. Un jeu de données 80% stable (r=2%) et 20% volatil (r=30%) coûte 6.954 passes complètes par an de façon uniforme, contre 0.2*6.954 + 0.8*0.394 = 1.706 équivalents-passe en segmenté, soit une économie de 4.08x à un plancher identique de 95%. Ceci suppose que le coût de vérification évolue avec la fraction des champs touchés ; une composante fixe par enregistrement (récupération, appariement, changement de contexte) réduit l'économie vers 1x.

Réserve sur le modèle : le risque constant est une simplification, et il est testable. Tracez la courbe de survie sur un axe logarithmique ; si elle n'est pas droite, ajustez une Weibull S(t) = exp(-(t/eta)^k), donnant T = eta*(ln(1/A_floor))^(1/k). Les données de link-rot de Pew sont concentrées au début, ce qui est le cas k<1 (un risque décroissant, forte perte précoce). Sous k<1, l'exponentielle sous-estime la perte précoce et surestime la survie tardive, donc le premier rafraîchissement doit venir plus tôt que T.

Pour les sources que vous ne contrôlez pas, le [When Online Content Disappears](https://www.pewresearch.org/data-labs/2024/05/17/when-online-content-disappears/) (2024) de Pew est le seul ancrage externe propre que j'ai trouvé : 38% des pages qui existaient en 2013 avaient disparu en October 2023, mais 8% des pages de 2023 avaient déjà disparu en un an. Le risque moyen sur dix ans est -ln(0.62)/10 = 0.0478/yr, mais le risque de première année directement observé est de 8%. Utilisez 8% pour régler la cadence sur les sources fraîches.

Un avertissement de provenance : le chiffre de 22.5% par an sur les contacts B2B remonte à MarketingSherpa via la [Database Decay Simulation de HubSpot](https://www.hubspot.com/database-decay), répliqué par des fournisseurs de lead-gen ayant un intérêt commercial et sans méthodologie ni taille d'échantillon publiées. Appliquez-le aux listes de contacts B2B et à rien d'autre. Les taux de décroissance pour les catalogues de produits, les prix, les corpus réglementaires, la documentation géospatiale et technique semblent non publiés. Le tableau est un modèle où brancher votre propre r.

| Taux de changement annuel r | lambda | Jours entre rafraîchissements, plancher 95% | Jours, plancher 90% | Passes complètes/an, plancher 95% | Demi-vie d'une copie unique |
|---|---|---|---|---|---|
| 2% | 0.0202 | 927 | 1,904 | 0.39 | 34.3 ans |
| 5% | 0.0513 | 365 | 750 | 1.00 | 13.5 ans |
| 10% | 0.1054 | 178 | 365 | 2.05 | 6.58 ans |
| 22.5% (contacts B2B uniquement) | 0.2549 | 73.5 | 151 | 4.97 | 2.72 ans |
| 30% | 0.3567 | 52.5 | 108 | 6.95 | 1.94 ans |
| 60% | 0.9163 | 20.4 | 42.0 | 17.87 | 0.76 ans |

## La grille d'évaluation : sept lignes, deux barrières

Symboles utilisés ci-dessous et définis ici : D est le nombre de décisions par an, v est la valeur nette par décision correcte (l'écart entre un bon et un mauvais choix, de sorte que le coût d'erreur est déjà à l'intérieur), et D_be est le volume de seuil de rentabilité construire-versus-rien (Cb/H+Mb)/(v*(Ab-A0)) dérivé dans la section suivante. Le seuil de la ligne 3 utilise la valeur annuelle des décisions, écrite D fois v.

| Critère | Test que vous pouvez faire cette semaine | Seuil | Score 0-3 |
|---|---|---|---|
| 1. Énumérabilité | Deux échantillons indépendants par deux voies, chevauchement m, Chapman estimator | 3 si couverture >=95% ; 2 si 90-95% ; 1 si 75-90% et vous pouvez nommer le segment exclu ; 0 si pas de N-hat | |
| 2. Vérifiabilité (BARRIÈRE) | Nommer l'oracle indépendant ; mesurer k et les minutes par enregistrement | Réussi si k <= 1% de v et <= 10 min/enregistrement | réussi/échoué |
| 3. Coût de décroissance abordable | Revérifier les enregistrements à deux instants, annualiser en r, calculer la maintenance en % de D fois v | 3 si <=5% ; 2 si 5-15% ; 1 si 15-30% ; 0 si >30% ou r non mesuré | |
| 4. Battement de cœur | Les 12 dernières versions publiées, coefficient de variation des écarts entre publications | 3 si CV <=0.25 et écart max <=2x la médiane ; 2 si CV <=0.5 ou vous contrôlez le tirage ; 1 si CV <=1.0 ou écarts irréguliers mais bornés ; 0 si pas d'historique de versions | |
| 5. Lien décisionnel (BARRIÈRE) | Nommer la décision, l'acteur, la valeur par défaut, D par an ; mesurer le taux de divergence à 90 jours | Réussi si D >= D_be et divergence >= 2% | réussi/échoué |
| 6. Non-substituabilité | Chiffrer la réplication complète la moins chère en jours de travail qualifié | 3 si la réplication est légalement bloquée (nommer le droit d'accès) ; 2 si >180 jours ; 1 si 30-180 jours ; 0 si <30 jours ou un fournisseur la liste comme un SKU | |
| 7. Intégrité de jointure | Tenter la jointure sur un échantillon de 500 lignes, mesurer le taux exact de correspondance de clé primaire | 3 si >=98% ; 2 si 95-98% ; 1 si 90-95% ; 0 si <90% | |

**La ligne 1** utilise le Chapman estimator N-hat = ((n1+1)(n2+1)/(m+1)) - 1 : n1=300, n2=250, m=180 donne 416, donc détenir 380 lignes représente 91.3% de couverture. Chapman suppose une capturabilité égale, mais les entités manquantes sont systématiquement les plus récentes et les plus éloignées, ce qui biaise N-hat vers le bas. Donc N-hat est une borne inférieure de l'univers et le chiffre de couverture une borne supérieure. Refaites la recapture en la restreignant aux entités vues pour la première fois au cours des 12 derniers mois, comme second chiffre requis.

**La ligne 2 est une barrière** car sans oracle vous ne pouvez pas mesurer r, donc les lignes 1 et 3 sont sans réponse. k est aussi le multiplicande dans la formule de maintenance, donc ce seul nombre chiffre toute l'obligation. Notez que k = $0.40 dans l'exemple travaillé implique une vérification quasi automatisée (environ une minute par enregistrement à $25.23/hr) ; la barrière elle-même tolère 10 min/enregistrement, soit $4.20, un ordre de grandeur plus haut.

**La ligne 3, travaillée :** N=4,000, k=$0.40, r=30%, un plancher de 95% donne 6.95 passes et $11,126/yr ; segmenter à 20% de champs volatils donne $2,729. Les deux évoluent linéairement avec le k supposé.

**La ligne 4** rend mesurable le « change selon un rythme que vous pouvez apprendre » du post retiré : une source dont l'intervalle de publication est plus variable que votre T requis rend le plancher inapplicable quel que soit le budget.

**La ligne 5 élimine la plupart des candidats.** Nommez la décision, l'acteur, la valeur par défaut et D par an, et mesurez le taux de divergence à 90 jours (à quelle fréquence les données auraient changé le choix). En dessous de 2%, les données ne font pas bouger les décisions, un échec franc. Je n'ai trouvé aucune étude mesurant la divergence en production, donc les 2% sont mon jugement.

**La ligne 6** se combine avec la demi-vie de la copie unique ln2/lambda, mais calculez-la par segment de champ : un concurrent copie les 80% stables (demi-vie de 34.3 ans à r=2%) et re-dérive le cinquième volatil, de sorte que la demi-vie au niveau du jeu de données surestime la défendabilité. Rapportez le chiffre du segment stable.

**La ligne 7** compte parce que les précisions se multiplient : un jeu de données précis à 95% joint à 90% délivre 85.5% de précision effective. Appliquez le facteur de jointure à la fois à votre build et à tout jeu de données fournisseur, puisqu'il dégrade tout jeu de données externe joint à vos clés.

Règle de notation, mon jugement : deux barrières réussi/échoué, cinq lignes notées 0-3 pour un maximum de 15, investissez à 11 avec les deux barrières franchies. Les tests sont exécutables et l'arithmétique derrière les lignes 1, 3 et 7 est sur la page. Chaque seuil numérique dans la colonne des seuils est mon jugement, calibré sur l'expérience, ni dérivé ni cité ; déplacez-les. La véritable fonction de l'instrument est de forcer sept mesures qui prennent environ une semaine.

Appliqué aux exemples interchangeables des posts retirés : la ponctualité d'un transporteur sur une ligne, chaque permis de commerce d'une métropole, les taux de remboursement d'un payeur, et le prix et le stock de 40 SKUs vérifiés deux fois par jour diffèrent énormément sur les lignes 3, 4 et 6. L'ensemble de SKUs a un lambda de plusieurs centaines par an (r fixé à essentiellement 100%, car r est une fraction bornée en dessous de 1 ; utilisez lambda directement quand le changement est plus rapide qu'annuel) et une demi-vie de copie en jours. Le registre des permis a un r proche de zéro et est trivialement copiable.

## Ce que les données coûtent réellement

Chaque chiffre porte son niveau de provenance.

| Élément | Fournisseur | Prix publié | Provenance |
|---|---|---|---|
| Bande passante proxy résidentiel | [Bright Data](https://brightdata.com/pricing/proxy-network/residential-proxies) | $8/GB PAYG, $5/GB au palier $1,999/mo | Récupération de page primaire |
| Proxies datacenter / ISP | Bright Data | $1.30-$1.80 et $0.90-$1.40 par IP par mois | Récupération de page primaire |
| Scraping par palier de difficulté | [Zyte](https://docs.zyte.com/zyte-api/pricing.html) | 5 paliers HTTP et 5 paliers navigateur ; ~$0.13-$1.27 et ~$1.01-$16.08 pour 1,000 | Structure de paliers primaire ; tarifs rapportés par agrégateur |
| Option capture d'écran | Zyte | $0.002 chacune | Docs primaires |
| Outillage d'étiquetage | SageMaker Ground Truth | $0.08 / $0.04 / $0.02 par objet (paliers 1-50k / 50-100k / >100k) ; 500 objets/mo gratuits les deux premiers mois | Rapporté par agrégateur, possiblement obsolète, non publié actuellement par AWS |
| Outillage d'étiquetage | Labelbox | $0.10 par Labelbox Unit, 1 LBU par ligne étiquetée | Rapporté par agrégateur |
| Étiquetage | [Scale AI](https://scale.com/pricing) | Aucun tarif entreprise publié ; palier gratuit uniquement | Récupération de page primaire |
| Main-d'œuvre d'annotation US | [ZipRecruiter](https://www.ziprecruiter.com/Salaries/Data-Annotation-Salary) | ~$25.23/hr ($52,488/yr) ; offshore ~$2 à $5-12/hr | Primaire ; offshore rapporté par agrégateur |
| Données de contact B2B | [Vendr](https://www.vendr.com/buyer-guides/zoominfo) | Médiane ZoomInfo $33,500/yr sur 1,566 achats, fourchette $7,200-$155,550 | Données de transaction vérifiées |
| Données de marché | [Databento](https://databento.com/pricing) | $199 / $1,750 / $4,500 par mois | Récupération de page primaire |
| Flux étroits à usage unique | [Massive](https://massive.com/pricing) | NYSE Order Imbalances $49/mo ; European Consumer Spending by Merchant $99/mo | Récupération de page primaire |
| Annonces de place de marché | [AWS Data Exchange](https://aws.amazon.com/data-exchange/pricing/) | Fixé par le fournisseur ; $0.023/GB/mo de stockage, $0.04167/hr d'octrois de données | Récupération de page primaire |
| Annonces de place de marché | Snowflake Marketplace | Par mois, par requête ou hybride ; annonces réelles $100-$1,500/mo | Docs fournisseur plus secondaire |
| Licence de données d'entraînement | News Corp / OpenAI ; Reddit / Google | >$250M sur 5 ans ; ~$60M/yr (Reddit S-1 : $203M au total) | Rapports de presse corroborés |
| Revue juridique de la méthode d'acquisition | Votre conseil | Indicatif : revue plus DPIA, de cinq chiffres bas à moyens en une fois plus traitement continu | Mon jugement, aucun chiffre transacté localisé |

Deux chiffres dérivés, avec les hypothèses visibles. Un corpus de niche d'un million de pages à un supposé 200KB par page (mon hypothèse) fait 200GB : environ $1,600 de bande passante résidentielle Bright Data au tarif public, contre environ $130 via Zyte au palier HTTP 1 ou environ $16,080 au palier navigateur 5, deux ordres de grandeur d'écart, décidés par le palier dans lequel tombe la cible. Étiqueter 100,000 enregistrements aux paliers Ground Truth ci-dessus fait 50,000*$0.08 + 50,000*$0.04 = $6,000 d'outillage (l'allocation de 500 gratuits est négligeable, et ces paliers par objet sont possiblement obsolètes, plus publiés par AWS), ou 100,000*$0.10 = $10,000 en Labelbox units, tous deux hors main-d'œuvre humaine, la ligne la plus importante.

Le trou honnête : je n'ai trouvé aucun prix de marché intermédiaire transacté pour licencier ou construire un jeu de données de domaine de 10,000 à 100,000 lignes. La fourchette publiée va d'environ $0.01 par étiquette à $250M par accord, soit à peu près dix ordres de grandeur, le milieu étant non documenté. Il n'existe pas non plus de référence publique pour k, le coût par enregistrement vérifié, l'entrée à laquelle le seuil de rentabilité ci-dessous est le plus sensible.

## Construire, acheter, ou ne rien faire

Le genre compare construire contre acheter et ne teste jamais la troisième option. Ne rien faire a une valeur nette positive, D*v*A0, et le modèle ci-dessous bat les deux alternatives à tout volume sous le seuil de rentabilité avec ces entrées.

\`\`\`
Nothing = D*v*A0
Buy     = D*v*Av - L
Build   = D*v*Ab - (Cb/H + Mb)

Build beats buy     when D > (Cb/H + Mb - L) / (v*(Ab - Av))
Build beats nothing when D > (Cb/H + Mb)     / (v*(Ab - A0))
Buy   beats nothing when D > L               / (v*(Av - A0))
\`\`\`

Cb est l'acquisition unique, H l'horizon d'amortissement, Mb la maintenance par période, L la licence par période, v la valeur nette par décision correcte (déjà l'écart entre juste et faux, donc le coût d'erreur est à l'intérieur ; si vous préférez une valeur brute plus un coût d'erreur séparé c, remplacez v par v+c).

Entrées travaillées, toutes des hypothèses illustratives, pas des mesures : N=4,000, Cb=$30,000, H=3 ans, L=$18,000/yr, v=$60, Ab=0.95, Av=0.78, A0=0.55, r=30%, k=$0.40. Mb=$11,100/yr en est dérivé (4,000*$0.40*6.95 passes à un plancher de 95%), ce qui n'est pas la même chose que connu indépendamment : il hérite du k supposé, et k n'a aucune référence publique. Un écart Ab-A0 de 40 points est optimiste ; un écart plus petit et plus réaliste relève les trois seuils de rentabilité et élargit la plage où ne rien faire gagne.

Seuils de rentabilité à k=$0.40 : construire bat acheter au-dessus de (10,000+11,100-18,000)/(60*0.17) = 304/yr ; construire bat ne rien faire au-dessus de 21,100/(60*0.40) = 879 ; acheter bat ne rien faire au-dessus de 18,000/(60*0.23) = 1,304.

Ceux-ci basculent selon k. À k=$0.40 la bande d'achat est vide (borne supérieure 304 en dessous de la borne inférieure 1,304), donc acheter est dominé. Mais la barrière de la ligne 2 tolère 10 min/enregistrement, ce qui à $25.23/hr fait $4.20. À k=$4.20, Mb=$116,800 : construire-bat-rien passe de 879 à 5,283, et la bande d'achat s'ouvre d'environ 1,304 à 10,667. La bande s'ouvre dès que k dépasse environ $0.75. Donc « acheter est dominé à tout volume » ne tient que sous une vérification quasi automatisée. Ce n'est pas un résultat général, et il est retiré pour la vérification manuelle.

| Décisions/an | Ne rien faire | Acheter à $18,000/yr | Construire ($30k sur 3 ans + $11.1k/yr) | Gagnant |
|---|---|---|---|---|
| 294 | $9,702 | -$4,241 | -$4,342 | Ne rien faire |
| 879 | $29,007 | $23,137 | $29,003 | Ne rien faire (point de croisement) |
| 1,304 | $43,032 | $43,027 | $53,228 | Construire |
| 2,000 | $66,000 | $75,600 | $92,900 | Construire |

| Précision fournisseur Av | Borne inférieure bande d'achat | Borne supérieure bande d'achat | Bande (k=$0.40) |
|---|---|---|---|
| 0.78 | 1,304 | 304 | Vide |
| 0.85 | 1,000 | 517 | Vide |
| 0.90 | 857 | 1,033 | Ouverte, 857-1,033 |
| 0.93 | 789 | 2,583 | Ouverte, 789-2,583 |

Acheter est juste précisément quand le fournisseur est presque aussi précis que vous le seriez sur votre propre surface, ce qui est une question sur votre sous-ensemble, pas sur leur marketing. Échantillonnez 200 enregistrements fournisseur à l'intérieur de votre niche et mesurez Av avant de signer. La sensibilité à v évolue comme 1/v : à $6 au lieu de $60, construire ne bat acheter qu'au-dessus de 3,100/(6*0.17) = environ 3,040/yr.

Le modèle omet aussi l'option qui domine généralement dans le régime de ce lecteur : achetez le gros copiable et ne construisez que la colonne de résultat que personne ne peut scraper. Dans une fenêtre de contexte vous détenez les deux, donc il y a rarement une raison de choisir un jeu de données plutôt que l'autre.

Maintenant la boucle de cadence. Votre avantage sur un concurrent est l'écart de précision moyenne d'une cadence plus rapide, où la précision moyenne sur l'intervalle T est (1-e^(-lambda*T))/(lambda*T). À r=30%, un rafraîchissement mensuel donne une moyenne de 98.53%, l'annuel donne 84.11%, un écart de 14.4 points. Le coût incrémental du mensuel par rapport à l'annuel est de 11 passes supplémentaires, 11*4,000*$0.40 = $17,600/yr, donc l'écart de cadence ne devient rentable qu'au-dessus de 17,600/(60*0.144) = environ 2,034 décisions/an, au-dessus des deux seuils de rentabilité travaillés. Et ce n'est pas un fossé de données : un concurrent qui dote la même chaîne de rafraîchissement l'efface. La chose défendable est une cadence opérationnelle, un fait de recrutement et d'outillage, pas un fait de données.

## Quatre façons dont cela tourne mal après l'achat

| Mode de défaillance | Symptôme que vous verrez réellement | Méthode de détection | Seuil de déclenchement |
|---|---|---|---|
| Illusion de couverture | Backtest correct, mauvaise performance en direct sur les nouveaux cas, écart qui se creuse | Capture-recapture (ligne 1) sur les entités vues pour la première fois au cours des 12 derniers mois | Couverture des nouvelles entités plus de 15pp en dessous de l'ensemble |
| Périmé-mais-fiable | Réponses confiantes bâties sur des champs que personne n'a touchés depuis des années | Obsolescence pondérée par lecture : fraction des lectures tombant sur des lignes plus anciennes que T | Plus de 5% des lectures au-delà de la cadence plancher |
| Dérive décisionnelle | Pipeline au vert, données qui se mettent à jour, l'action de personne ne change | Taux de divergence à 90 jours (ligne 5) | En dessous de 2%, éliminez le jeu de données |
| Falaise de maintenance | k bondit, une passe de rafraîchissement échoue en silence, une source se met à vous bloquer, un champ signifie quelque chose de nouveau | Concentration des sources, k d'une année sur l'autre, et taux de blocage des sources | Une seule source >50% des lignes, k en hausse >25% en glissement annuel, ou une source scrapée qui vous refuse |

À mon avis, le manque dans un chiffre de couverture n'est pas aléatoire : il se concentre dans les entités les plus récentes, les plus petites, les plus éloignées, exactement le segment sur lequel porte la décision. Si vos voies d'échantillonnage sont corrélées à l'âge des entités, exécutez la ligne 1 séparément sur les 12 derniers mois pour le savoir.

La pondération par lecture compte parce que les 5% de lignes chaudes sont généralement celles qui sont consultées (mon hypothèse, testable via la mesure pondérée par lecture elle-même) ; si ce sont aussi les volatiles, la fraîcheur pondérée par enregistrement vous flatte. Ajoutez une colonne verified_at ou aucun des modèles de cet article ne peut être exécuté. La dérive décisionnelle survit le plus longtemps parce que chaque tableau de bord affiche une bonne santé. Une source qui se met à vous refuser est à la fois une falaise de maintenance et un signal juridique. Les seuils de ces lignes sont mon jugement ; le risque de base pour les sources web non contrôlées est le 8% de première année de Pew.

## Où la thèse de niche tient, et où elle ne tient pas

Ma contribution, offerte comme un jugement : la défendabilité est proportionnelle au coût de rafraîchissement, et les données propriétaires en elles-mêmes ne sont pas un fossé. Le point de Lambrecht and Tucker tient : la ressource rare est la boîte à outils opérationnelle autour des données. Ce qui peut être défendable est une cadence de rafraîchissement maintenue enroulée autour d'une boucle de décision fermée, et seulement tant qu'aucun concurrent ne dote la même chaîne. C'est une course au recrutement, pas un avantage de données. « Trouver des données peu coûteuses à maintenir » et « trouver des données défendables » sont donc des instructions opposées, et la plupart des fondateurs reçoivent les deux.

Énonçons le tableau de score clairement. L'anti-thèse a deux échecs documentés (Watson Health, Zillow) et six axes empiriques. La pro-thèse a zéro cas de production nommé dans le régime de ce lecteur : aucune mesure transparente de ce qu'apporte la curation d'un corpus privé, et la seule étude de récupération dans le bon régime a constaté qu'elle n'apporte rien. Les échecs de cette catégorie ne sont pas publiés, donc l'échantillon est sélectionné par survie. Traitez la thèse comme une hypothèse que cet article instrumente, pas comme un résultat qu'il prouve. Son test de falsification : mesurez la divergence et le gain de précision effective sur votre propre surface ; si le gain est dans le bruit, la thèse a échoué pour vous.

Quatre conditions sous lesquelles elle pourrait survivre.

1. **Les données enregistrent une décision que vous seul prenez, pas un corps de connaissances.** L'objet défendable est la boucle fermée de décision, résultat, enregistrement étiqueté, parce que la colonne de résultat ne peut pas être scrapée, seulement gagnée. C'est la seule condition cohérente avec toutes les preuves ci-dessus : aucune revendication de faits rares, aucune dépendance à l'échelle, non déductible de texte public.
2. **Observation de première partie d'événements qui ne laissent aucune trace publique jointe.** Un événement que vous observez laisse tout de même une empreinte chez votre contrepartie, un courtier, ou un processeur (le flux de dépenses marchandes à $99/mo ci-dessus est exactement de la donnée de transaction revendue). Mais personne d'autre ne détient l'enregistrement joint de l'événement, du contexte et du résultat sous votre clé. Cette jointure est l'objet défendable, pas l'événement.
3. **Forte décroissance, comprise comme un coût récurrent plutôt qu'une barrière.** Un ensemble à décroissance rapide ne peut pas être volé une seule fois, seulement maintenu, il n'est donc défendable que tant que vous tenez l'écart de cadence, qu'un concurrent peut vous ravir par recrutement. À r=30%, un instantané unique est faux à 23.5% en 9 mois, mais un concurrent qui construit lui aussi une machine de rafraîchissement ne perd rien.
4. **Assez petit pour être vérifié exhaustivement.** À 4,000 enregistrements et k=$0.40, un plancher de 99% à r=30% coûte environ $56,800/yr ; à 400,000 enregistrements c'est environ $5.68M et personne ne l'achète. Les deux évoluent avec le k supposé.

Là où elle ne tient pas : (a) un fournisseur la vend comme un SKU (louez-la, voir les flux à $49 à $99/mo ci-dessus) ; (b) faible décroissance plus sources publiques (votre copie et la leur vieillissent ensemble, vous concourez donc sur la distribution, là où les expériences naturelles disent que le fossé était réellement) ; (c) en dessous du volume de décisions du seuil de rentabilité ; (d) aucun oracle indépendant (vous ne pouvez pas mesurer r, donc vous ne pouvez rien chiffrer ici) ; (e) la tâche est du raisonnement ou de la sémantique plutôt que de la consultation et de l'extraction structurée (GPT-4 devant BloombergGPT, prompting générique devant Med-PaLM 2) ; (f) divergence en dessous de 2% ; (g) la méthode d'acquisition est contractuellement ou légalement interdite à la source, donc chiffrez le conseil juridique avant le scraper.

Enfin, le slogan retiré, gardé comme une condition. La précision effective est c*A_small + (1-c)*A0 seulement si vous vous rabattez sur la base de référence hors de la surface curée. Dans une fenêtre de contexte vous détenez généralement les deux ensembles, donc la précision effective est c*A_small + (1-c)*A_big, qui vaut au moins A_big pour tout c>0 : le petit ensemble propre n'est jamais pire, et il n'y a aucun seuil du tout. Un seuil n'existe que là où les deux sont mutuellement exclusifs, ce qu'en récupération ils sont rarement. Sous cette exclusivité, avec A_small=0.99, A0=0.55 et A_big=0.60 (tous supposés), la couverture de seuil de rentabilité est (0.60-0.55)/(0.99-0.55) = 11.4% ; à A_big=0.65 elle double à 22.7%. Donc la réponse est la couverture, pas le nombre de lignes, et elle est dominée par le degré de qualité déjà atteint par la base de référence générique sur votre surface, que vous pouvez mesurer.

Le travail de la semaine : achetez les deux prérequis, un oracle et un coût par enregistrement k ; mesurez r par champ avec son intervalle de confiance ; exécutez les sept lignes ; calculez vos trois seuils de rentabilité avec k varié sur la plage qu'impliquent vos propres coûts de main-d'œuvre. Ensuite seulement, décidez. Si le jeu de données se qualifie, [from-dataset-to-live-workflow](/blog/from-dataset-to-live-workflow) couvre ce qui vient ensuite.
`;

export default content;
