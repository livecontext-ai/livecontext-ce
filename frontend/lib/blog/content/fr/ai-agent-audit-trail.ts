// ai-agent-audit-trail - fr
// Translated from the English body; structure identical to it. Legal scope
// statements and every "at least 6 months"/"out of scope"/"not legal advice" are
// load-bearing and must not be strengthened or softened. Fenced code stays fenced.
const content = `Un journal d'audit n'est pas un log plus long. C'est un artefact différent, avec un lecteur différent, un contrat d'écriture différent et une horloge différente. Cet article publie un schéma copiable au niveau du run et au niveau de l'étape dans lequel chaque champ porte quatre choses à la fois : son type de donnée, sa classe de cardinalité, sa capacité à contenir des données personnelles, et la raison de son existence. Un article compagnon fait le calcul de stockage qui transforme l'échelonnement de rétention en une décision dérivée, cartographie les obligations légales réelles, et traite la demande de suppression qui entre en collision avec un journal conservé pendant des années.

L'implémentation de référence citée tout au long est la propre plateforme de ce blog. De vrais noms de colonnes, de vraies migrations, de vrais bugs.

## Le lecteur pour qui vous écrivez n'est pas vous, et pas maintenant

Un tableau de bord est lu par son auteur, en quelques minutes, l'incident encore présent en mémoire de travail. Un journal est lu par un tiers indifférent ou hostile, des mois plus tard, qui ne peut poser aucune question de suivi. Cette différence engendre chacune des décisions ci-dessous.

Deux invariants en découlent, et presque personne ne les écrit :

1. **Les enregistrements d'audit ne sont jamais échantillonnés.**
2. **Les champs de contenu ne sont jamais dégradés à l'intérieur de leur fenêtre de rétention.**

Le reste relève du jugement de conception, et le coût de stockage de ce jugement est de l'arithmétique.

Énonçons d'emblée la chose inconfortable : **aucun instrument ne spécifie ce schéma.** En dehors de l'Art. 12(3) de l'EU AI Act, qui s'applique à exactement un sous-point de l'Annexe III (point 1(a), identification biométrique à distance, et non à la vérification biométrique), rien de ce qui est passé en revue ici (l'AI Act, ISO/IEC 42001, NIST AI RMF, SOC 2) ne spécifie de schéma de log, de types de champs, de limites de cardinalité ou de stratégie d'échantillonnage. Le schéma ci-dessous est un jugement d'ingénierie visant à satisfaire les *finalités* que la loi nomme à l'Art. 12(2)(a) à (c) et le droit à l'explicabilité de l'Art. 86. Ce n'est pas un artefact de conformité et je ne le vendrai pas comme tel.

Chaque champ d'un journal utilisable porte quatre choses à la fois : son type de donnée et sa nullabilité, la question ou l'obligation qui l'impose, sa classe de cardinalité, et sa classe de rétention incluant sa capacité à être échantillonné ou dégradé. Aucune source publiée ne remplit les quatre coins. [Les conventions GenAI d'OpenTelemetry](https://github.com/open-telemetry/semantic-conventions-genai) ont les types mais pas les obligations et pas de contenu par défaut ; [le journal d'audit minimum viable d'ARMO](https://www.armosec.io/blog/minimum-viable-audit-trail/) a les obligations et les noms de champs mais pas les types ; l'ensemble AI Act a la loi et concède qu'il ne spécifie aucun champ.

Deux notes anti-recouvrement. Le journal est **linéaire en étapes, pas quadratique** : vous payez le modèle pour renvoyer le contexte accumulé à chaque tour mais stockez chaque message une seule fois, donc un run de six étapes fait ~27 lignes indépendamment de la croissance du contexte (le versant quadratique appartient à l'article sur le modèle de coût). Et \`stop_reason\` et \`terminal_category\` apparaissent ici purement comme des champs à enregistrer ; la taxonomie et le comportement de plafonnement appartiennent à l'article sur l'application des budgets.

## Un tableau de bord d'observabilité n'est pas un journal d'audit

La confusion se scinde nettement selon le titre de l'article : les articles intitulés « observabilité » vendent les traces comme le journal d'audit ; les articles intitulés « audit » mentionnent rarement que le schéma standard n'enregistre aucun contenu par défaut.

Ce défaut est le constat phare. Les conventions sémantiques GenAI rendent les prompts, complétions, instructions système, arguments d'outils et résultats d'outils tous de niveau d'exigence \`Opt-In\`, et la position de la spécification est que les instrumentations « SHOULD NOT capture them by default », l'option 1 étant « [Default] Don't record instructions, inputs, or outputs. » Donc « nous avons du tracing OTel, donc nous avons un journal d'audit » est faux d'emblée : ce que vous avez, c'est le modèle, le décompte de tokens, la latence et la raison de fin, rien de la matière qui reconstitue une décision.

L'activer est plus difficile qu'il n'y paraît. Dans [opentelemetry-python-contrib](https://github.com/open-telemetry/opentelemetry-python-contrib/blob/main/util/opentelemetry-util-genai/src/opentelemetry/util/genai/utils.py), l'interrupteur de capture n'est pas un booléen :

\`\`\`
OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT
  = NO_CONTENT | SPAN_ONLY | EVENT_ONLY | SPAN_AND_EVENT
  unset            -> NO_CONTENT
  invalid value    -> warning, then NO_CONTENT

# second gate, barely documented:
OTEL_SEMCONV_STABILITY_OPT_IN must select the GenAI
experimental mode, or get_content_capturing_mode() raises.
\`\`\`

Définir la seule variable de capture ne suffit pas. (Vérifié pour les paquets Python contrib uniquement ; les SDK d'autres langages peuvent différer par le nom du drapeau, les valeurs d'enum, ou l'existence même du second verrou.)

Pendant ce temps, les conseils d'observabilité grand public sont fatals à l'audit de deux manières indépendantes. Pour les fonctionnalités à fort volume au-dessus de ~1,000 requêtes/seconde, [réduire l'échantillonnage de l'enveloppe d'appel à 10-20% et réserver la capture complète au niveau du token à des sessions de débogage explicites](https://www.braintrust.dev/articles/llm-call-observability) ; et [nettoyer ou masquer le contenu avant qu'il n'atteigne le backend](https://mlflow.org/articles/setting-up-llm-observability-pipelines-in-2026/). Un échantillon de dix pour cent est inutile lorsque la décision que vous devez défendre se trouve dans les quatre-vingt-dix pour cent que vous avez abandonnés.

| Dimension | Tableau de bord d'observabilité | Journal d'audit |
|---|---|---|
| Consommateur | L'auteur, quelques minutes plus tard | Un tiers indifférent ou hostile, des mois plus tard |
| Latence de lecture | Secondes à heures | Mois à années |
| Échantillonnage | Attendu (10-20%, ou basé sur la queue) | Interdit |
| Contenu par défaut | Désactivé (le contenu GenAI d'OTel est Opt-In) | Activé, dans sa fenêtre de rétention |
| Contrat d'écriture | Fire-and-forget, échec journalisé | Même transaction, l'échec fait échouer l'opération |
| Source de l'ordre | Horodatages, ré-échantillonnés | Séquence assignée par l'écrivain |
| Mutabilité | Mutable par conception (retraitement, champs abandonnés lors d'une mise à niveau du backend) | Append-only, idéalement chaîné par hash |
| Moteur de rétention | Combien de temps une régression reste intéressante (jours) | Une obligation ou un horizon de litige (mois à années) |
| Mode d'échec | Vous déboguez plus lentement | Vous ne pouvez pas répondre à la question |

Le contrat d'écriture est la chose la moins chère à rater. Cette plateforme tient les deux positions, chacune correcte pour son artefact. L'écriture d'observabilité de l'agent est un POST HTTP fire-and-forget (\`AgentClient.recordObservability\`) dont l'échec est capturé et journalisé en WARN comme « non-critical » : le run facture et retourne quand même, la ligne d'audit est simplement perdue. L'audit de feature-flag (\`V173__flag_flip_audit.sql\`) énonce le contrat opposé dans l'en-tête de sa migration : même transaction, pas de \`REQUIRES_NEW\`, pas d'async, pas d'écouteur \`AFTER_COMMIT\` (cela ferait la course avec un kill de la JVM), et si l'insertion d'audit lève une exception, le flag n'est pas basculé.

La conséquence du choix best-effort est le mode d'échec qui semble correct jusqu'à ce que vous en ayez besoin : **la couverture du journal devient corrélée à la santé du système**, si bien qu'elle s'amincit précisément pendant les incidents qu'on vous demandera d'expliquer.

## Le schéma au niveau du run

Une ligne par run. C'est l'en-tête qu'un auditeur lit en premier.

| Champ | Type | Null | Cardinalité | Données personnelles | Raison d'existence |
|---|---|---|---|---|---|
| \`run_id\` | uuid | non | haute | non | Clé de jointure pour chaque ligne enfant. Frappez à la **dispatch**, pas à l'INSERT. |
| \`trail_seq\` | bigint (séquence dédiée) | non | haute | non | Ordre qui survit à la dérive d'horloge et aux écritures dans la même milliseconde. |
| \`prev_row_hmac\` | bytea(32) | oui | haute | non | Preuve d'altération : couvre son propre contenu plus le HMAC de la ligne précédente. |
| \`tenant_id\`, \`organization_id\` | text / uuid | non | moyenne | indirecte | Clé de portée pour l'effacement et le contrôle d'accès. |
| \`actor_subject_ref\` | text (jeton pseudonyme) | oui | haute | **oui** | « Qui a demandé. » Ne se résout en identité que via un mappage détenu séparément. |
| \`parent_run_id\` | uuid | oui | haute | non | Quel run a engendré celui-ci. |
| \`caller_agent_id\` | uuid | oui | moyenne | non | Quel agent l'a engendré. |
| \`depth\` | int2 | non | basse | non | Détection de cycle et ordonnancement de l'arbre. |
| \`caller_tool_call_id\` | text | oui | haute | non | L'appel exact dans le parent qui a engendré l'enfant. |
| \`trigger_source\` | enum | non | **basse** | non | manual / chat / webhook / schedule / datasource / workflow / error. Décide si un humain est responsable de l'existence du run. |
| \`started_at\`, \`ended_at\` | timestamptz | non / oui | haute | non | Deux horodatages, pas un plus une durée. |
| \`status\` | enum | non | basse | non | La revendication qu'on vous demandera de défendre : ce run a-t-il réussi. |
| \`stop_reason\` | text (chaîne d'enum brute) | oui | basse | non | Stocké verbatim pour l'analyse forensique. |
| \`terminal_category\` | enum | oui | basse | non | Matérialisé, pas dérivé au moment de la lecture. |
| \`billed_provider\`, \`billed_model\` | text | non | basse | non | Ce qui vous a été facturé. |
| \`executed_provider\`, \`executed_model\` | text | oui | basse | non | Ce qui a réellement tourné. Ils peuvent différer. |
| \`model_snapshot\` | jsonb (clé \`_v\`) | oui | moyenne | non | Grille tarifaire et config de modèle figées au démarrage de l'exécution. |
| \`prompt_tokens\`, \`completion_tokens\`, \`cache_creation_tokens\`, \`cache_read_tokens\`, \`reasoning_tokens\` | int4 x5 | non (défaut 0) | haute | non | Cinq compteurs, pas un total : ils se tarifent différemment. |
| \`cost_settled\` | numeric(15,4) | oui | haute | non | Le montant réellement facturé, matérialisé à l'écriture. |
| \`system_prompt_hash\` | bytea(32) | oui | haute | non | Référence, jamais le texte. |
| \`build_sha\` | text(40) | oui | basse | non | Ce run précédait-il le correctif. |
| \`config_snapshot\` | jsonb | oui | moyenne | peut-être | Politique en vigueur, y compris si une approbation était requise. |
| \`approval_ref\` | uuid | **oui** | haute | non | NULL signifie « aucune approbation requise par la politique en vigueur ». |
| \`iteration_count\`, \`tool_call_count\` | int4 | non | haute | non | Forme du run sans lire ses étapes. |

Onze d'entre eux nécessitent plus d'une phrase.

**Frappez \`run_id\` à la dispatch.** Un vrai bug : les lignes de task-claim côté MCP étaient écrites avant que la ligne d'exécution n'existe, de sorte qu'un id généré par Hibernate laissait \`task_id\` silencieusement NULL. Le correctif passe un id d'exécution explicite à travers l'appel de dispatch et l'utilise comme clé primaire (\`AgentObservabilityRequest.executionId\`, documenté dans le code comme « stable correlation ID minted at dispatch »).

**L'arbre d'appels des sous-agents a besoin de quatre champs, pas un :** run parent, agent appelant, profondeur, et l'appel d'outil exact dans le parent. Retirez-en un seul et un run multi-agent se lit comme un tas plat impossible à ordonner.

**Deux horodatages, pas un plus une durée.** Une durée ne peut pas être réconciliée avec une chronologie d'événements externes. C'est aussi la seule forme de champ que l'AI Act lui-même nomme : l'Art. 12(3)(a) exige « recording of the period of each use of the system (start date and time and end date and time of each use) ».

**Le modèle facturé et le modèle exécuté peuvent différer.** Une couche de routage peut envoyer une paire facturée \`(provider, model)\` vers une cible d'exécution différente tout en préservant l'identité facturée dans la réponse (\`V365__create_model_execution_links.sql\`). Un journal n'en enregistrant qu'un seul se trompe sur ce qui a produit la sortie.

**\`model_snapshot\`** fige la grille tarifaire au démarrage de l'exécution :

\`\`\`json
{
  "_v": 1,
  "provider": "anthropic",
  "model_id": "claude-opus-4-8",
  "price_input": 5.0,
  "price_output": 25.0,
  "credits_input": 1.0,
  "credits_output": 5.0,
  "canonical_id": "anthropic/claude-opus-4-8",
  "bundle_version": 41,
  "markup": 1.2,
  "captured_at": "2026-07-22T09:14:03Z"
}
\`\`\`

Environ 260 bytes, environ 905 MB/year à 10k runs/day, environ un dollar par an de stockage bloc. Il existe pour que le coût survive à une dépréciation de modèle en cours de run et à des révisions rétroactives de prix, et c'est le champ que les ingénieurs coupent en premier et regrettent le plus fort.

**\`cost_settled\` est matérialisé à l'écriture.** Le recalcul à partir des tokens fois le prix au moment de la lecture est le *repli* que \`model_snapshot\` permet, pas l'enregistrement ; toute divergence ultérieure est elle-même un constat.

**\`terminal_category\` est stocké matérialisé bien qu'il soit dérivable** de \`stop_reason\`, actuellement par du code de contrat généré (\`AgentStopReason.valueOfOrError(x).terminal()\`). Le codegen change ; un journal lisible dans sept ans ne peut pas dépendre du build de ce mois-ci, sinon d'anciennes lignes se reclassent silencieusement elles-mêmes.

**\`build_sha\`** (~40 bytes) est le champ le plus souvent manquant et le plus souvent nécessaire. Piège : \`.git\` n'est généralement pas dans le contexte de build Docker, de sorte que la version en cours d'exécution rapporte un placeholder statique à moins que le commit ne soit passé comme argument de build.

**Ne stockez jamais le texte du prompt système par run.** À 10k runs/day, un prompt système de 6 KB représente 20.89 GB/year de pure duplication, et cette plateforme le stocke jusqu'à trois fois par run (la colonne \`agent_executions.system_prompt TEXT\`, une copie dans le JSONB \`agent_config_snapshot\`, et de nouveau comme ligne de rôle SYSTEM dans \`agent_execution_messages\`), donc 20.89 GB/year est le plancher, pas le total. Stockez chaque prompt distinct une seule fois par version, référencez par hash. Ce n'est pourtant pas le plus gros poste évitable : le stockage dupliqué des résultats d'outils (quantifié dans l'article compagnon sur la rétention) est de 83.55 GB/year, quatre fois plus gros. Ces deux-là, 83.55 GB/year de résultats d'outils puis 20.89 GB/year de prompts système, sont les seuls postes évitables au-dessus de 10 GB/year dans ce modèle.

**\`trail_seq\` provient d'une séquence dédiée, pas de \`created_at\`.** Elle survit à la dérive d'horloge, à une restauration dans un autre fuseau horaire, et à deux lignes écrites dans la même milliseconde. Les trous sont acceptables et devraient être documentés comme tels ; la monotonie est la propriété affirmée. \`V169__trigger_lifecycle_invariants.sql\` montre le motif : il ordonne l'historique par \`(trigger_id, trigger_type, seq DESC)\` et ne garde un index \`created_at DESC\` que pour la requête d'opérations par fenêtre temporelle.

**\`prev_row_hmac\` est la frontière** entre un log d'observabilité et un journal d'audit. Le HMAC de chaque ligne couvre son propre contenu plus celui de la précédente, de sorte qu'une édition ou une suppression silencieuse brise la chaîne. L'en-tête de \`V195__create_organization_audit_event.sql\` de cette plateforme le liste comme délibérément omis de ce MVP, aux côtés d'une purge de rétention sous verrou distribué, d'un miroir WORM et d'une séparation de rôle append-only. Cette liste sert aussi de checklist de maturité.

## Le schéma au niveau de l'étape

Une ligne par tour de LLM, appel d'outil, décision ou signal. Les lignes d'étape dépassent en nombre les lignes de run d'environ 26 pour 1 et portent toute la charge utile, de sorte que leur profil de rétention et de données personnelles est entièrement différent.

| Champ | Type | Null | Cardinalité | Données personnelles | Raison d'existence |
|---|---|---|---|---|---|
| \`run_id\` | uuid | non | haute | non | Clé de jointure parent. |
| \`tenant_id\`, \`organization_id\` | text / uuid | non | moyenne | indirecte | Sur **chaque** ligne enfant, pour l'effacement à portée d'organisation. |
| \`step_seq\` | int4 (assigné par l'écrivain) | non | haute | non | Ordre déterministe. Jamais dérivé de \`created_at\`. |
| \`iteration_seq\` | int4 (assigné par l'écrivain) | non | moyenne | non | À quel tour de LLM ceci appartient. |
| \`parallel_index\` | int2 | **oui** | basse | non | NULL signifie séquentiel. Distingue un lot concurrent d'une chaîne causale. |
| \`step_kind\` | enum | non | basse | non | llm_turn / tool_call / decision / signal / message. |
| \`tool_name\` | text | oui | **basse** | non | Le GROUP BY pour « que fait réellement cet agent ». |
| \`tool_call_id\` | text | oui | haute | non | Corrèle la requête avec le résultat à travers les retries et les réordonnancements. |
| \`args_digest\` | bytea(32) | oui | haute | non* | Prouver ou réfuter une charge utile produite sans la conserver. |
| \`result_digest\` | bytea(32) | oui | haute | non* | Idem, pour les résultats. |
| \`content_length\` | int4 | oui | haute | non | Quelle **était** la taille de la charge utile, conservée après sa disparition. |
| \`payload_ref\` | uuid | oui | haute | pointeur seulement | Blob déporté au-dessus du seuil inline. |
| \`content\` | text | oui | haute | **oui** | Charge utile inline, sur l'horloge courte. |
| \`error_code\` | enum | oui | basse | non | Classe d'échec lisible par machine. Fenêtre complète. |
| \`error_message\` | text | oui | haute | **oui** | Texte libre. Horloge de la charge utile. |
| \`branch_taken\` | text (étiquette de port) | oui | basse | non | Quelle arête sortante le run a suivie. |
| \`skip_reason\` | text | oui | basse | non | Pourquoi un nœud n'a **pas** tourné. |
| \`skip_source_node\` | text | oui | moyenne | non | Quelle décision en amont l'a sauté. |
| \`redaction_applied\` | int2 (bitmask) | non | basse | non | Quelles règles de caviardage se sont déclenchées. |
| \`prompt_tokens\`, \`completion_tokens\`, ... | int4 | **oui** | haute | non | Écrits uniquement lorsque non nuls, si bien que NULL garde son sens. |
| \`duration_ms\` | int8 | oui | haute | non | Attribue un timeout au niveau du run à l'étape qui a consommé le budget. |

\\* Un digest n'est pas une donnée personnelle uniquement lorsque l'espace de la charge utile n'est pas énumérable (voir la mise en garde ci-dessous).

Les cinq compteurs de tokens sont NOT NULL défaut 0 sur l'en-tête du run (un run a toujours un total) mais nullables sur les lignes d'étape, où NULL signifie « non applicable » (une ligne d'appel d'outil n'a pas de tokens), pas zéro. Sommez les étapes contre l'en-tête en gardant cette règle à l'esprit, sinon les deux ne concordent pas.

**\`parallel_index\` coûte quatre bytes** et prévient le pire échec de journal : reconstruire une chaîne causale à partir d'un lot parallèle, ce qui est pire qu'un trou parce que c'est faux avec assurance.

**\`args_digest\` et \`result_digest\` sont le pivot de la conception de rétention.** 32 B par digest ; les 6 lignes d'appel d'outil en portent deux, les 14 lignes de message en portent un, soit 832 bytes par run, 2.83 GB/year à 10k runs/day. Gardez le digest pour toute la fenêtre d'obligation, la charge utile sur une horloge courte : quand quelqu'un produit un document et prétend que l'agent l'a vu, le digest le prouve ou le réfute avec zéro charge utile conservée.

La mise en garde, tout simplement : **pour un petit espace d'entrée énumérable (un code postal, une date de naissance) le digest est ré-identifiable** et doit être salé avec une clé détenue séparément. La règle est « ne jamais publier un digest non salé d'un champ à faible entropie », pas « les digests sont non-personnels ». Les [lignes directrices de l'EDPB sur la pseudonymisation](https://www.edpb.europa.eu/system/files/2025-01/edpb_guidelines_202501_pseudonymisation_en.pdf) considèrent qu'un simple hachage sans séparation de domaine ni contrôle d'accès est insuffisant (projet de consultation de January 2025).

**\`content_length\` est défini inconditionnellement avant la décision d'inliner, de déporter ou de tronquer**, ce qui est ce qui indique à un lecteur futur que la troncature a eu lieu et quelle quantité il ne voit pas (\`AgentObservabilityService\`, \`CONTENT_INLINE_THRESHOLD = 8192\`) :

\`\`\`
length = content.length()          # set FIRST, always
if length > 8192:
    id = storage.saveText(content) # payload_ref
    content = content[:500] + "...[truncated]"
else:
    keep inline
# if the offload throws: fall back to an inline prefix
# with NO storage id, which MUST be distinguishable
# from a successful offload.
\`\`\`

**Séparez \`error_code\` de \`error_message\`.** Les messages en texte libre sont non interrogeables, instables d'une mise à niveau de bibliothèque à l'autre, et répercutent couramment l'entrée qui a causé l'échec, ce qui en fait le champ de données personnelles le plus à risque du journal tout en ayant l'air d'un diagnostic. Le code se conserve toute la fenêtre ; le message va sur l'horloge de la charge utile.

**\`branch_taken\` rend le journal rejouable sur papier** plutôt que par ré-exécution ; dans un moteur de workflow, les ports forment un ensemble fermé de basse cardinalité par type de nœud (\`if\` / \`else\` / \`elseif_N\`, \`case_N\` / \`default\`, \`body\` / \`iterate\` / \`exit\`, \`branch_N\`). Enregistrez aussi pourquoi un nœud n'a **pas** tourné : \`skip_reason\` plus \`skip_source_node\` font du négatif un fait de première classe, de sorte qu'une branche sautée se distingue d'une branche jamais atteinte.

**\`redaction_applied\` fait deux bytes** et sépare trois états qu'un journal nu confond : charge utile propre, charge utile caviardée, ou caviardeur désactivé. Sans lui, un journal d'apparence propre est sans valeur probante. Le \`ToolCallRedactor\` de cette plateforme est à deux couches (une regex de nom de champ secret plus une allowlist d'outils de type identifiant qui blanchit tout le corps de l'argument) et ne persiste aucun marqueur indiquant quelle couche s'est déclenchée ; c'est la lacune que ce champ comble.

## L'enregistrement d'approbation est sa propre ligne, et son champ le plus difficile est ce que l'humain a vu

L'humain dans la boucle est la seule chose que l'AI Act énumère pour les systèmes qu'il couvre, et la seule chose pour laquelle OTel n'a aucun attribut. L'Art. 12(3)(d) exige, pour les systèmes de l'Annexe III point 1(a), « the identification of the natural persons involved in the verification of the results » mentionnés à l'Art. 14(5).

Un enregistrement d'approbation utilisable (le \`orchestrator.workflow_signal_waits\` de cette plateforme) :

\`\`\`
signal_type, signal_config jsonb, status, resolution,
resolution_data jsonb, approval_context text,
expires_at, created_at, claimed_at, claimed_by,
resolved_at, resolved_by,
UNIQUE (run_id, node_id, item_id, epoch)

signal_config = { type, approverRoles, requiredApprovals,
                  timeoutMs, receivedApprovals, delegation,
                  continuationMode }
\`\`\`

**Le champ que personne n'enregistre est ce que l'approbateur a réellement vu.** \`approval_context\` est le template de contexte du nœud rendu contre le contexte d'exécution **figé au moment de la pause**, persisté avec le signal, puis ré-émis verbatim dans la sortie du nœud résolu pour qu'il survive à la transition en-attente-vers-résolu (migration \`V373\`, qui ajoute \`approval_context\` à la table signal-wait).

**\`approval_ref\` sur la ligne du run est nullable, et NULL doit signifier « aucune approbation n'était requise par la politique en vigueur »**, un fait différent de « statut d'approbation inconnu ». Cela exige que la version de la politique soit récupérable depuis \`config_snapshot\`.

**Les valeurs par défaut d'identité doivent être visiblement distinguables des identités réelles.** Ici \`resolved_by\` se replie sur le littéral \`"system"\` lorsqu'il est null dans la sortie du nœud, et sur \`"api"\` lorsque l'en-tête utilisateur en amont est absent. Correct, tant qu'aucun humain ne peut jamais être nommé \`api\`.

**Dimensionner une colonne d'identité est une préoccupation d'audit.** \`resolved_by\` était \`VARCHAR(100)\` jusqu'à ce que des identifiants fédérés de la forme \`b:org:user\` (~120 chars) le débordent, annulant la transaction de résolution et laissant les approbations coincées en \`CLAIMED\` pour toujours, indistinguables de celles réellement en attente (\`V191__signal_waits_widen_resolved_by.sql\`).

**Les approbations déléguées ont besoin de leur propre registre de livraison.** \`orchestrator.approval_channel_deliveries\` : un jeton de callback à usage unique (\`VARCHAR(64) UNIQUE\`), un statut (\`PENDING\`, \`SENT\`, \`FAILED\`, \`RESOLVED\`, \`CANCELLED\`), le texte du message réellement envoyé, une allowlist d'utilisateurs autorisés, et \`UNIQUE (signal_wait_id, channel)\` comme garde-fou contre le rejeu. L'identité est alors une chaîne à espace de noms telle que \`telegram:<fromId>\`.

**L'intention enregistrée n'est pas un contrôle appliqué, et le journal ne devrait pas le laisser entendre.** Ici \`approverRoles\` est enregistré dans la config du signal et affiché à l'approbateur, mais le endpoint de résolution in-app n'applique que la portée du run, pas l'appartenance à un rôle. Si votre journal enregistre un rôle qu'il n'a pas vérifié, dites-le dans la documentation du champ.
`;

export default content;
