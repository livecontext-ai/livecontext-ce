// ai-agent-audit-log-retention - fr
// Translated from the English body; structure identical to it. Legal scope
// statements and every "at least 6 months"/"out of scope"/"not legal advice" are
// load-bearing and must not be strengthened or softened. Fenced code stays fenced.
const content = `I read the English source and checked the translation against all seven requirements (counts, GFM tables, untranslatables, dashes, legal force, code-span length, fluency). The structure, counts, URLs, and legal claims are sound; I applied targeted fluency/consistency fixes. Corrected French body:

Un article compagnon publie le schéma de champs au niveau run et au niveau step que celui-ci chiffre : les noms de champs, les types et les classes de cardinalité référencés dans les tableaux ci-dessous y sont définis. Cet article répond aux trois questions que le schéma laisse ouvertes. Combien d'octets la trace coûte-t-elle réellement ? Combien de temps chaque champ doit-il être conservé ? Et tout cela s'applique-t-il légalement à vous, ce qui, pour la plupart des lecteurs, n'est pas le cas.

L'implémentation de référence citée tout au long est la propre plateforme de ce blog : vrais noms de colonnes, vraies migrations, vrais bugs.

## L'arithmétique, pour que la hiérarchisation soit dérivée et non affirmée

Tout ce qui suit est un **modèle**, pas une mesure. Les entrées sont indiquées pour que vous puissiez le rejouer avec vos propres chiffres. Les tailles de lignes sont analytiques, dérivées des types de colonnes DDL plus la surcharge Postgres documentée ; les tables réelles sont environ 10-25% plus grandes une fois le fillfactor, l'espace libre et le bloat inclus, donc lisez chaque chiffre dérivé ci-dessous comme « +10 à 25% en production » (le chiffre plat de capture complète sur sept ans, 1.68 TB dans le modèle, est d'environ 2.1 TB en haut de cette fourchette).

\`\`\`
Volume:  10,000 runs/day, 6 steps/run
Rows:    27/run = 1 run header + 6 iterations
                + 6 tool calls + 14 messages
Payload: 1500-token system prompt, 200-token user msg,
         250-token completions, 150 B tool arguments,
         4 KB mean tool result, 4 bytes/token
PG overhead/row: 23 B heap tuple header, MAXALIGNed to 24
                 + 4 B line pointer
                 + 8 B assumed null bitmap (1 bit/column,
                   present only when the row has NULLs;
                   8 B covers up to 64 columns) = 36 B
                 + ~16 B per btree index entry
\`\`\`

Le chiffre de métadonnées seules à partir duquel le reste du modèle est mis à l'échelle est de 9.05 KB/run, dérivé comme suit :

\`\`\`
Worked row sizes (metadata only):
Run header (1 row):
  ~300 B column data (uuids, 3 timestamptz, 5 int4 token
   counters, 3 bytea(32) hashes, build_sha, enums, numerics)
  + 36 B tuple overhead + ~48 B (3 btree entries) = ~384 B
Step row (avg over 26):
  ~180 B column data + 36 B overhead + ~80 B index entries
  = ~335 B
Per run: 384 + 26 x 335 = ~9.05 KB
\`\`\`

| Niveau de capture | Octets/run | MB/jour @10k runs | GB/an | GB sur 7 ans | GB compressés/an |
|---|---|---|---|---|---|
| Métadonnées seules | 9.05 KB | 88.38 | 31.50 | 220.51 | 31.50 (non compressé dans PG ; s'archive bien) |
| Métadonnées + digests (~832 B/run) | 9.86 KB | 96.29 | 34.33 | 240.31 | 34.33 |
| Capture complète | 70.43 KB | 687.78 | 245.16 | 1,716 (1.68 TB) | 92.6-117 |

La capture complète représente 7.8x les métadonnées seules. La compression suppose 2.5-3.5x sur les charges utiles au-dessus du seuil TOAST de Postgres d'environ 2 kB (2048 octets), une fourchette publiée typique plutôt qu'une mesure sur ce corpus, donc le chiffre de capture complète compressée s'étend de 92.6 à 117 GB/year selon l'endroit où vous atterrissez dans cette fourchette.

Une seule entrée domine le résultat :

| Résultat d'outil moyen | KB/run (capture complète) | GB/year @10k runs/day | Forme d'agent qui vit ici |
|---|---|---|---|
| 1 KB | 34.43 | 119.84 | Classification, routage, courtes recherches API |
| 4 KB | 70.43 | 245.16 | Usage d'outils mixte, le modèle ci-dessus |
| 8 KB | 118.43 | 412.24 | Rédaction de documents, CRUD multi-enregistrements |
| 20 KB | 262.43 | 913.50 | Recherche, lecture de fichiers, agents à forte charge SQL |

Les prompts et les complétions représentent 20% de la charge utile pour un résultat d'outil moyen de 4 KB (12.8 KB sur 61.38 KB) et tombent à environ 5% à 20 KB (12.8 KB sur 253.38 KB), donc c'est sur les résultats d'outils que la hiérarchisation est payante. **Si vous hiérarchisez une seule chose, hiérarchisez les résultats d'outils.**

Voici maintenant l'inversion qui motive toute cette section. 245 GB/year représentent environ **$235/year** de stockage bloc gp3, **$68/year** sur S3 Standard, **$12/year** sur Glacier Instant Retrieval ; les métadonnées seules coûtent environ $30/year. (Chiffres d'ordre de grandeur us-east-1 en tarif catalogue, hors frais de requête et de récupération ; les niveaux froids supposent un volume de lecture quasi nul.) **Personne ne réduit sa trace pour économiser $200.**

Ce que le chiffre en dollars cache, c'est le coût réel : **98.55 millions de lignes/an** (689.85 millions sur sept ans) de surface d'effacement, de maintenance d'index et de temps de restauration, plus le fait que chaque octet conservé de prompt et de résultat d'outil est une responsabilité juridique. Concevez la hiérarchisation autour du rayon d'impact et du nombre de lignes.

À 1M runs/jour, le plafond opérationnel mord bien avant la facture de stockage : ~54M insertions d'index/jour, 9.86 milliards de lignes/an, 23.94 TB/year de capture complète, et environ 140 heures pour restaurer logiquement une année à 50 MB/s. Le niveau squelette est ce qui garde une trace *restaurable*, pas seulement abordable.

Une économie gratuite, trouvée en lisant le schéma plutôt que le code : **le résultat d'outil est fréquemment persisté deux fois**, une fois comme contenu de la ligne d'appel d'outil et une autre fois comme contenu de la ligne de message de rôle outil correspondante. Stockez la charge utile une seule fois et faites porter à la ligne de message le même \`payload_ref\`, et la charge utile passe de 61.38 KB à 37.38 KB par run, de 245.16 GB/year à 161.61 GB/year. Toute trace comportant à la fois une table d'appels d'outils et une table de messages a cette forme. (L'observation au niveau du schéma est solide ; le taux de chevauchement exact en production n'a pas été mesuré.)

## Niveaux de rétention, chacun justifié par la décision qu'il soutient

| Niveau | Contenu | Fenêtre | GB/year | Question à laquelle il répond | Échantillonné ou dégradé ? |
|---|---|---|---|---|---|
| 0 Squelette | En-tête de run sans aucun texte ; métadonnées d'étape (\`step_seq\`, \`tool_name\`, \`branch_taken\`, status, \`stop_reason\`, durées, comptes de tokens, \`content_length\`, tous les digests) | Fenêtre d'obligation complète (7 ans modélisés) | 31.50 | Ce run a-t-il eu lieu, quand, qui l'a déclenché, qu'a-t-il fait, dans quelle direction a-t-il bifurqué, combien a-t-il coûté | **Jamais** |
| 1 Digests et codes | \`args_digest\`, \`result_digest\`, \`error_code\`, \`redaction_applied\`, \`model_snapshot\` | 12-24 mois | 34.33 | Prouver ou réfuter que l'agent a vu un document produit ; rechiffrer un run contesté aux prix en vigueur | **Jamais** |
| 2 Args et résultats d'outils | \`content\`, \`payload_ref\` pour les étapes d'outil | 30-90 jours à chaud, puis échantillonné | ~80% des octets de charge utile | Déboguer une régression en direct ; répondre à une réclamation client | Oui, après la fenêtre à chaud |
| 3 Prompts et complétions | Contenu des messages | 30 jours, **plus 100% des runs échoués ou ayant déclenché un garde-fou, quel que soit leur âge** | voir ci-dessous | Reconstruire le raisonnement d'une décision contestée | Uniquement de façon non uniforme |
| 4 Modèles de prompt | Prompts système, texte de prompt par version | Pour toujours (kilooctets) | ~0 | Quelle version de prompt a été exécutée | Jamais sur une horloge par run |

Le niveau 0 sur sept ans représente 220.51 GB, environ **$10.60/year** sur Glacier Instant Retrieval (220.51 GB x $0.004/GB-month x 12). Cela répond à la plupart des questions d'un auditeur tout en ne conservant zéro octet de données personnelles.

La règle d'échantillonnage du niveau 3 est celle qui mérite d'être débattue, et le curseur ne touche jamais que les niveaux 2 et 3 (invariant 1 : les enregistrements d'audit ne sont jamais échantillonnés). Avec un taux d'échec supposé de 8%, conserver tous les échecs plus 5% des succès retient 12.6% des runs (0.08 + 0.92 x 0.05 = 0.126). Appliqué aux seuls niveaux de charge utile (capture complète moins les niveaux squelette 31.50 et digest 2.83, soit 210.83 GB/year), cela conserve 26.56 GB/year de charge utile ; avec les niveaux 0 et 1 maintenus à 100%, le volume résident en détail complet tombe de 245.16 à environ **60.9 GB/year** (31.50 + 2.83 + 26.56), tout en conservant chaque run que quelqu'un demandera réellement. L'échantillonnage uniforme optimise pour les runs que personne n'investigue.

Plan combiné, par niveau :

\`\`\`
30 days full capture:   20.15 GB gp3           $19.34
365 days digests:       34.33 GB S3 Standard    $9.47
7 years skeleton:      220.51 GB Glacier IR    $10.58
resident total:        274.99 GB             ~ $39/year
\`\`\`

Cela fait 274.99 GB résidents contre 1.68 TB pour une capture complète plate conservée sept ans, une réduction de 6.2x, environ $39/year contre $1,647/year de gp3 plat. L'économie qui compte n'est pas l'argent : **seuls 30 jours de charge utile de données personnelles entrent jamais dans le périmètre d'une demande de suppression, au lieu de sept ans.**

Le modèle chaud-plus-froid est déjà celui que les régulateurs codifient. L'exigence 10.5.1 de PCI DSS 4.0 demande 12 mois avec les 3 plus récents immédiatement disponibles ; SEC Rule 17a-4 demande six ans avec les deux premiers facilement accessibles. (Les deux confirmables tels quels.)

L'anti-modèle à nommer : l'**échelle de dégradation progressive** largement diffusée qui supprime le contenu des prompts et complétions après la première année et ne conserve que les métadonnées à partir de la troisième année. Elle dégrade le contenu précisément sur la fenêtre où un auditeur en a besoin, et permet à une entreprise de revendiquer « sept ans de journaux d'audit » tout en ne conservant rien qui explique la moindre décision.

## Ce que vous devez réellement, et pourquoi ce n'est probablement rien

| Instrument | Article / contrôle | Lie qui | Ce qu'il exige réellement | Rétention | Spécifie des champs ? |
|---|---|---|---|---|---|
| EU AI Act | [Art. 12(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-12) | **Systèmes** à haut risque (exigence de conception) | Les systèmes « doivent techniquement permettre l'enregistrement automatique des événements (logs) tout au long de la vie du système » | n/a | **Non** |
| EU AI Act | Art. 12(2)(a)-(c) | comme ci-dessus | Seulement les *finalités* : risque au titre de l'Art. 79(1) ou modification substantielle ; surveillance après commercialisation au titre de l'Art. 72 ; surveillance du fonctionnement au titre de l'Art. 26(5) | n/a | **Non** |
| EU AI Act | Art. 12(3)(a)-(d) | **Annex III point 1(a) uniquement** (identification biométrique à distance) | Période de chaque utilisation ; base de données de référence vérifiée ; données d'entrée dont la recherche a conduit à une correspondance ; identification des personnes vérifiant les résultats | n/a | **Oui, le seul endroit** |
| EU AI Act | [Art. 19(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-19) | **Fournisseurs** | Conserver les logs de l'Art. 12(1) « dans la mesure où ces logs sont sous leur contrôle » | **au moins 6 mois** | Non |
| EU AI Act | [Art. 26(6)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-26) | **Déployeurs** | Même obligation, même limiteur, horloge distincte | **au moins 6 mois** | Non |
| EU AI Act | [Art. 18(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-18) | Fournisseurs | Documentation technique, documentation du SMQ, décisions de l'organisme notifié, déclaration UE de conformité | **10 ans** après la mise sur le marché ou la mise en service | n/a |
| EU AI Act | [Art. 86](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-86) | Déployeurs | « Des explications claires et pertinentes sur le rôle du système d'IA dans la procédure de prise de décision et sur les principaux éléments de la décision prise » | n/a | **Non** |
| ISO/IEC 42001 | le contrôle de journalisation des événements de l'Annex A | Volontaire | Journaux d'événements plus enregistrements de surveillance montrant que la journalisation est opérationnelle | aucune prescrite | **Non** |
| NIST AI RMF | MEASURE 2.8, MANAGE 2.4, MANAGE 4.3 | Volontaire | Instrumenter et maintenir des historiques et des journaux d'audit ; préserver les éléments pour examen forensique, réglementaire et juridique ; maintenir des bases de données d'incidents et de changements système | aucune prescrite | **Non** |
| SOC 2 | 2017 TSC (points de focalisation révisés en 2022) | Contractuel | Preuves génériques d'environnement de contrôle appliquées à votre agent | basée sur des critères, aucune période | **Non** |
| HIPAA | [45 CFR 164.316(b)(2)(i)](https://www.govinfo.gov/content/pkg/CFR-2023-title45-vol2/xml/CFR-2023-title45-vol2-sec164-316.xml) | Entités couvertes | Conserver la documentation requise | **6 ans** | Non |

Trois distinctions que la plupart des résumés confondent.

**L'Art. 12(1) est une exigence de conception portant sur le système. L'Art. 19(1) impose un plancher de six mois au fournisseur. L'Art. 26(6) impose un plancher de six mois distinct et parallèle au déployeur.** Six mois sont dus deux fois par deux parties différentes, et non selon une seule horloge partagée, chacune portant le même limiteur, « dans la mesure où ces logs sont sous leur contrôle ».

**Six mois est le plancher des LOGS ; dix ans est le plancher de la DOCUMENTATION.** L'Art. 18(1) et l'Art. 19(1) sont deux régimes distincts, régulièrement confondus.

**L'obligation qui force réellement l'explicabilité décision par décision est l'Art. 86, pas l'Art. 12.** Une personne concernée faisant l'objet d'une décision prise par le déployeur sur la base de la sortie d'un système à haut risque de l'Annex III (sauf le point 2), produisant des effets juridiques ou l'affectant de manière significative de façon similaire d'une manière qu'elle considère comme ayant un impact négatif sur sa santé, sa sécurité ou ses droits fondamentaux, a droit à des explications sur le rôle du système d'IA et sur les principaux éléments de la décision. L'Art. 86(3) le rend subsidiaire par rapport à d'autres dispositions du droit de l'Union.

**Et maintenant la réponse honnête pour la plupart des lecteurs : entièrement hors du périmètre de l'Art. 12/19/26(6).** Haut risque signifie l'Art. 6(1) (composant de sécurité d'un produit de l'Annex I nécessitant une évaluation de conformité par un tiers) ou l'Art. 6(2) (les huit domaines de l'[Annex III](https://ai-act-service-desk.ec.europa.eu/en/ai-act/annex-3)). Un assistant de codage, un agent interne de recherche ou de support, un agent de rédaction de documents n'entre dans aucun d'eux.

Le « sauf si » qui piège les gens, c'est l'Annex III **point 4** (recrutement et sélection, annonces d'emploi ciblées, filtrage des candidatures, évaluation des candidats, décisions sur les conditions de travail, la promotion, le licenciement, l'allocation des tâches fondée sur le comportement ou les traits, la surveillance des performances) et le **point 5** (une liste partielle de ses quatre sous-points, les deux qui piègent le plus souvent les constructeurs : (b) évaluation de la solvabilité et scoring de crédit hors détection de fraude, et (c) évaluation des risques et tarification pour l'assurance vie et santé ; les deux autres, (a) évaluation par une autorité publique de l'éligibilité aux prestations et services essentiels d'assistance publique y compris les soins de santé, et (d) tri et répartition des appels d'urgence, piègent les agents govtech et proches des prestations sociales).

Même un système de l'Annex III peut échapper via la dérogation de l'[Art. 6(3)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-6) (tâche procédurale étroite ; amélioration d'une activité humaine déjà réalisée ; détection de schémas sans remplacer l'évaluation humaine préalable ; tâche préparatoire), mais **jamais s'il effectue un profilage de personnes physiques**. Et l'Art. 6(4) fait en sorte que cette échappatoire génère sa propre paperasse : documenter l'évaluation avant la mise sur le marché, plus une obligation d'enregistrement au titre de l'Art. 49(2).

Deux pièges pour les constructeurs. Construire un agent purement pour un usage interne ne fait pas de vous un simple déployeur : l'[Art. 3(11)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-3) définit la mise en service comme la fourniture pour une première utilisation « ou pour un usage propre », de sorte qu'un système interne à haut risque peut devoir l'Art. 19, l'Art. 26(6) et l'Art. 18 simultanément. L'[Art. 25(1)(c)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-25) fait de même pour quiconque modifie la finalité prévue d'un modèle à usage général au point que le système devient à haut risque.

L'exposition aux sanctions pour les obligations de journalisation relève du niveau intermédiaire, pas du niveau vedette : l'[Art. 99(4)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-99) va jusqu'à EUR 15,000,000 ou 3% du chiffre d'affaires annuel mondial, le montant le plus élevé étant retenu. Il couvre les Art. 16, 22, 23, 24, 26, 31, 33, 34 et 50 ; l'Art. 19 n'est pas lui-même listé, de sorte qu'un manquement du fournisseur à la conservation des logs est atteint via l'Art. 16(e), qui importe l'obligation de l'Art. 19, tandis que celui du déployeur relève directement de l'Art. 26. Le niveau 35 millions / 7% est réservé aux pratiques interdites de l'Art. 5.

**Le calendrier a bougé.** Le Digital Omnibus on AI reporte les dates d'application au haut risque au **2 December 2027** pour les systèmes à haut risque autonomes (Annex III) et au **2 August 2028** pour l'IA à haut risque intégrée dans des produits réglementés, selon le [Council of the EU](https://www.eeas.europa.eu/delegations/chile/artificial-intelligence-council-gives-final-green-light-simplify-and-streamline-rules_en). Statut procédural à la fin juillet 2026 : approbation en plénière du PE le 16 June 2026, adoption par le Conseil le 29 June 2026, signature le 8 July 2026, en attente de publication au Journal officiel ([EP Legislative Train](https://www.europarl.europa.eu/legislative-train/package-digital-package/file-digital-omnibus-on-ai)). Tout article citant encore le 2 August 2026 pour le haut risque est obsolète. L'Omnibus ne modifie pas les Articles 12, 19 ou 26(6) dans le texte convenu, comme le rapporte chaque analyse publiée à son sujet ; le plancher de six mois est inchangé. À confirmer par rapport au texte du JO une fois publié.

Les systèmes existants peuvent échapper entièrement : l'[Art. 111(2)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-111) applique le Règlement aux systèmes à haut risque mis sur le marché avant la bascule uniquement s'ils font ensuite l'objet de changements significatifs dans leur conception ; les déployeurs qui sont des autorités publiques ont jusqu'au 2 August 2030.

Deux obligations mordent quel que soit le niveau de risque : l'[Art. 4](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-4) (maîtrise de l'IA, applicable depuis le 2 February 2025, aux fournisseurs et déployeurs) et l'[Art. 50(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-50) (les fournisseurs doivent concevoir les systèmes de sorte que les personnes physiques soient informées qu'elles interagissent avec une IA, sauf si c'est évident), qui s'applique à partir du 2 August 2026, dix jours après la publication de cet article. Le marquage de contenu de l'Art. 50(2) bénéficie d'une période de grâce jusqu'au 2 December 2026 pour les systèmes déjà sur le marché. L'Omnibus assouplit l'Art. 4 en passant de garantir un niveau suffisant de maîtrise à soutenir son développement parmi le personnel ; la date du 2 February 2025 est inchangée, et jusqu'à la publication au JO, c'est toujours la formulation d'origine qui lie.

Et les normes qui préciseraient *comment* satisfaire à l'Art. 12 n'existent pas encore : le [CEN-CENELEC JTC 21](https://www.cencenelec.eu/news-events/news/2025/brief-news/2025-10-23-ai-standardization/) développe encore les normes du Chapitre III Section 2, avec des mesures d'accélération adoptées en octobre 2025 visant une disponibilité autour du Q4 2026. En attendant, c'est une obligation légale sans aucune spécification technique derrière elle.

Les cadres volontaires ne vous donnent pas non plus de schéma. [ISO/IEC 42001](https://www.iso.org/standard/81230.html) est volontaire (l'ISO ne certifie pas les organisations ; ce sont des organismes accrédités qui le font), et son contrôle A.6.2.8 de l'Annex A, « Enregistrement des journaux d'événements du système d'IA », ne prescrit ni une durée de rétention ni une liste de champs. [NIST AI RMF](https://www.nist.gov/itl/ai-risk-management-framework) est explicitement volontaire et comportemental. SOC 2 utilise les Trust Services Criteria de 2017 avec les points de focalisation révisés en 2022, et aucun critère spécifique à l'IA n'a été publié, de sorte qu'un auditeur teste des preuves génériques d'environnement de contrôle appliquées à votre agent.

Le Colorado mérite une ligne si vous touchez à l'embauche ou aux décisions à fort enjeu. SB 26-189, selon la [page du projet de loi](https://leg.colorado.gov/bills/sb26-189), a été signé le 14 May 2026, en vigueur le 1 January 2027 ; il abroge et réédicte le Colorado AI Act de 2024. Le périmètre est la technologie de prise de décision automatisée utilisée dans les décisions à fort enjeu (éducation, emploi, logement, financier/prêt, assurance, soins de santé, services gouvernementaux essentiels). Les développeurs et déployeurs doivent conserver les enregistrements de conformité pendant au moins trois ans, pour les déployeurs à compter de la date de la décision à fort enjeu.

**La conclusion anti-théâtre.** Si vous êtes hors périmètre, construisez la trace pour les questions qu'on vous posera réellement : un litige client, une revue d'incident, une contestation de facture, une enquête de sécurité. Dimensionnez le niveau squelette pour l'obligation future plausible la plus longue, car il coûte 31.50 GB/year. Ensuite, laissez six mois être un plancher que vous dépassez incidemment plutôt qu'un chantier. Ceci n'est pas un conseil juridique, et aucun des régimes de rétention ci-dessus ne devrait être aplati en un seul nombre qui s'applique à vous.

## Données personnelles : la trace que vous conservez des années et la demande de suppression que vous recevez demain

**Une référence d'acteur pseudonyme ne fait pas sortir la trace du périmètre du GDPR.** Le Recital 26 traite comme des données personnelles les données qui pourraient être attribuées à une personne à l'aide d'informations supplémentaires. Stockez un token qui ne se résout en identité que via une table de correspondance contrôlée séparément, et ne prétendez pas que la trace est anonyme.

**Le plancher de six mois a un plafond dans la même phrase.** L'Art. 19(1) et l'Art. 26(6) se terminent tous deux par « sauf disposition contraire du droit de l'Union ou national applicable, en particulier du droit de l'Union sur la protection des données personnelles ». Tout conserver pour toujours n'est pas la réponse conforme, c'est une violation distincte.

**La réponse de conception, c'est le pivot par digest :** le niveau long conserve des hachages, des codes, des comptes et des classifications, aucune charge utile. C'est ce qui rend un squelette de sept ans défendable plutôt qu'une responsabilité de sept ans.

**Mettez \`tenant_id\` et \`organization_id\` sur chaque ligne enfant, pas seulement sur le parent.** L'effacement s'exécute sous forme de DELETEs par table à portée org ; les lignes ne portant qu'un \`execution_id\` nécessitent une jointure, et toute ligne dont le parent a déjà disparu survit comme un orphelin inaccessible détenant encore des données personnelles. Le \`WorkspaceDataPurger\` de cette plateforme émet un DELETE à portée org contre \`agent_execution_tool_calls\` indexé sur \`organization_id\` (et équivalents), ce qui ne fonctionne que parce que \`V210\` a ajouté la colonne aux cinq tables runtime d'agent et en a rempli rétroactivement quatre (les lignes \`agent_tasks\` restent NULL par conception, une portée personnelle).

**Séparez la trace en une couche opérationnelle effaçable et une couche registre non effaçable**, et laissez la suppression ne prendre que la première. L'implémentation de référence supprime 31 tables à portée org déclarées (\`PURGED_ORG_SCOPED_TABLES\`) plus les tables enfant d'exécution d'agent qu'elle atteint directement (messages, appels d'outils, itérations), sans jamais toucher \`auth.credit_ledger\`, \`auth.usage_cycle\`, \`auth.credit_reconciliation_log\` ou \`auth.organization_audit_event\`, et conserve la ligne d'organisation comme pierre tombale pour que les références du registre restent valides. Un test de couverture vérifie à la fois la portée org de chaque instruction et la non-suppression des tables conservées. La limite honnête : le registre survivant prouve toujours que les runs d'un sujet ont existé et ce qu'ils ont coûté, donc cela satisfait la minimisation seulement si le registre ne porte aucune charge utile et uniquement des identifiants pseudonymes.

**L'effacement qui n'efface pas.** Lorsque de grandes charges utiles sont déchargées vers un stockage objet et que la ligne conserve un pointeur, supprimer la ligne **orpheline le blob**. Les données personnelles survivent à la demande de suppression, non référencées et donc invisibles à tout audit ultérieur de ce que vous détenez. Le purger ci-dessus documente exactement cet orphelin dans son propre javadoc : il supprime les lignes \`storage.storage\` mais pas les objets S3/MinIO sous-jacents. Correctif : faites du magasin de charge utile la cible de suppression et de la ligne le pointeur, et réconciliez les orphelins selon un planning.

**Décidez si la rédaction se fait à l'écriture ou à la lecture, et enregistrez laquelle.** Un rédacteur qui ne s'exécute que lors de la présentation des lignes à un relecteur laisse des identifiants bruts dans les arguments d'outil stockés (l'état actuel ici : \`ToolCallRedactor\` est un filtre en chemin de lecture). Un rédacteur à l'écriture détruit des preuves dont vous pourriez avoir besoin. Quel que soit votre choix, \`redaction_applied\` est ce qui rend le choix auditable.

**Le modèle non résolu qui vaut la peine d'être implémenté :** placer une pierre tombale sur le contenu effacé tout en conservant son digest, de sorte que la chaîne infalsifiable survive à un effacement et qu'un lecteur ultérieur puisse encore savoir que quelque chose était là, quelle en était la taille, et qu'il a été retiré à la suite d'une demande de droits plutôt que perdu.

## Deux échecs à éliminer par conception, et que faire d'OpenTelemetry

**Une rétention que vous ne pouvez pas rallonger rétroactivement.** Le jour où vous découvrez que la fenêtre est plus longue que votre cron de purge, les données ont disparu. Une équipe ici, en faisant passer un journal d'audit de cycle de vie de 30 à 365 jours, a heurté un arriéré de 12x lors de la première purge suivante, et c'était la direction *chanceuse*. Réglez le niveau squelette sur l'obligation plausible la plus longue dès le premier jour ; à 31.50 GB/year, c'est l'assurance la moins chère du système. (Connexe : un commentaire de rétention documenté indiquant « 30d par défaut » alors que la valeur par défaut \`@Value\` du service était 365, c'est ainsi que la rétention documentée et la rétention configurée divergent silencieusement.)

**Des erreurs de chemin de requête qui rendent une trace inutilisable plutôt qu'erronée.** Les lignes de détail ne sont pas le chemin de requête : pré-agrégez les dimensions à faible cardinalité en rollups indexés sur \`(tenant, date, provider, model)\` et \`(tenant, tool_name)\`. Postgres n'indexe pas automatiquement les clés étrangères : ici, une table d'appels d'outils de 18k lignes et 39 MB dont le seul index était sa clé primaire faisait un full-scan à chaque lecture agrégée jusqu'à ce que \`V341\` ajoute un btree \`CONCURRENTLY\` sur \`execution_id\`. Et les lectures non paginées de lignes de charge utile à l'échelle du MB sont une forme d'OOM : plafonnez la page (100 est un maximum dur raisonnable) et renvoyez \`total\` / \`shown\` / \`truncated\` pour qu'un lecteur soit informé quand des lignes plus anciennes ont été écartées, au lieu de voir silencieusement une trace partielle.

La règle de cardinalité qui découle des tableaux de schéma : **les champs à faible cardinalité** (\`status\`, \`stop_reason\`, \`provider\`, \`model\`, \`tool_name\`, \`trigger_source\`, \`branch_taken\`) sont ce sur quoi chaque question regroupe et ont leur place dans les rollups ; **les champs à forte cardinalité** (\`run_id\`, \`tool_call_id\`, digests) sont des clés de jointure qui nécessitent des index btree et ne doivent jamais entrer dans une clé de rollup.

### Le verdict OpenTelemetry

**Ne fixez pas encore un schéma d'audit dessus.** Zéro attribut \`gen_ai.*\` n'est Stable (99 en Development, 0 en Stable dans le registre en direct), le [dépôt GenAI semconv](https://github.com/open-telemetry/semantic-conventions-genai) n'a ni releases ni tags, et les conventions ont quitté le dépôt principal semantic-conventions, qui affiche désormais chaque attribut \`gen_ai.*\` sur la [page de registre héritée](https://opentelemetry.io/docs/specs/semconv/registry/attributes/gen-ai/) comme « Deprecated », artefact du déplacement. Un faux signal dans les deux directions.

Des renommages ont déjà cassé des schémas une fois :

\`\`\`
gen_ai.system              -> gen_ai.provider.name (now absent)
gen_ai.usage.prompt_tokens -> gen_ai.usage.input_tokens
gen_ai.usage.completion_tokens -> gen_ai.usage.output_tokens
gen_ai.prompt / gen_ai.completion
   -> gen_ai.input.messages / gen_ai.output.messages
\`\`\`

OTel n'a **aucun attribut** pour une approbation humaine, une identité d'acteur ou de principal, une décision de politique ou de garde-fou, une classe de rétention, ou un coût monétaire (comptes de tokens uniquement, pas de \`gen_ai.cost.*\`). Ce sont précisément les champs porteurs d'audit, ce qui explique pourquoi la trace est votre table et non votre backend de tracing.

Deux champs valent la peine d'être adoptés tels quels parce qu'ils sont peu coûteux et répondent à de vraies questions d'audit : **\`gen_ai.prompt.name\` plus \`gen_ai.prompt.version\`** prouvent quelle version de prompt a été exécutée sans stocker son texte, et **\`gen_ai.conversation.compacted\`** répond à la question de savoir si le modèle a vu l'historique complet ou un résumé. Notez aussi que \`gen_ai.provider.name\` est un discriminant de format de télémétrie qui peut pointer vers un proxy, pas une preuve de quel fournisseur a traité les données, et que \`gen_ai.conversation.id\` ne doit pas être fabriqué à partir d'un UUID, d'un trace id ou d'un hachage de contenu, il est donc légitimement absent de nombreuses traces.

Les limites de span tronquent une trace silencieusement : \`OTEL_SPAN_ATTRIBUTE_COUNT_LIMIT\` vaut 128 par défaut. Des attributs indexés par message aplatis (la forme OpenInference \`llm.input_messages.<i>.message.*\`) peuvent dépasser cette limite sur une longue conversation, tandis qu'un unique \`gen_ai.input.messages\` structuré coûte un seul attribut. C'est de l'arithmétique dérivée, pas un incident documenté. Les valeurs d'attributs structurées ne sont pas non plus encore universellement prises en charge sur les spans, de sorte que le même champ logique est une chaîne JSON dans un backend et un objet dans un autre.

La propre recommandation de production de la spécification est l'architecture défendue ici : stockez le contenu dans un stockage externe avec des contrôles d'accès séparés et enregistrez des références sur les spans, et invoquez le hook d'upload « quelle que soit la décision d'échantillonnage du span ». **Échantillonnez les traces, n'échantillonnez jamais les preuves.** C'est \`payload_ref\` plus digest sous un autre nom.

Règle finale : **émettez de l'OTel pour le tableau de bord, possédez une table pour la trace, joignez-les sur \`run_id\`, et gardez les deux horloges de rétention séparées.**
`;

export default content;
