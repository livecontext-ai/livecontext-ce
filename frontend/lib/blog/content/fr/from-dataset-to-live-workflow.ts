// from-dataset-to-live-workflow - fr
// Translated from the English body; structure identical. Everything inside a
// fenced code block, every {{...}} template, node name, prefix, output field,
// enum value and file:line citation is byte-identical to English; only prose is
// translated. A wrong template string is the one error a reader copies.
const content = `Vous avez choisi les données. Maintenant, elles doivent tourner toutes seules.

Un jeu de données de niche qualifié reste inerte tant que rien ne le lit selon une cadence, ne tranche à partir de lui, et n'aboutit à une action à laquelle un humain se fiera. Cet article démarre exactement là : le jeu de données est déjà choisi. Comment sélectionner un jeu de données de niche, et quel contexte et quel budget son exécution coûte, sont traités dans les articles compagnons (liés une fois, non ré-argumentés ici). Celui-ci commence après le choix des données et s'arrête à un workflow qui tourne.

Chaque mécanique de nœud ci-dessous est citée depuis le code et la documentation d'un moteur de workflow de production, avec les chaînes exactes. Le build travaillé en une ligne : un déclencheur planifié rafraîchit toutes les heures, une requête HTTP re-récupère l'annonce suivie, un nœud de code normalise la réponse brute, une recherche en table plus une décision séparent un SKU jamais vu d'un SKU connu, une seconde décision signale un mouvement de prix significatif, une porte d'approbation humaine protège l'écriture, et alors seulement une alerte se déclenche. Une écriture de baseline idempotente signifie que les réexécutions ne dupliquent jamais une seule ligne. Pour chaque nœud, le schéma est le même : d'abord le piège portable, ensuite la chaîne exacte de ce moteur.

## Le graphe : huit nœuds, sept préfixes

Avant que la prose ne parcoure le build, voyez le tout d'un coup. Le moteur nomme chaque nœud avec un préfixe de catégorie. Il y en a sept : \`trigger:\`, \`mcp:\`, \`table:\`, \`agent:\`, \`core:\`, \`note:\`, et \`interface:\` (\`LabelNormalizer.java:14-24\`, \`:262-265\`). La famille \`core:\` est la plus grande, couvrant Loop, Split, Decision, Switch, Merge, Transform, Wait, Fork, Download File, HTTP Request, Data Input, et User Approval (\`LabelNormalizer.java:182\`). Notez que HTTP Request est un nœud \`core:\`, pas un nœud \`mcp:\` (\`WORKFLOW_NODE_TYPES.md:1559-1594\`).

| # | Nœud (rôle) | Ce qu'il fait dans le build | Champ de sortie clé | Cité depuis |
|---|---|---|---|---|
| 1 | Déclencheur planifié | Se déclenche toutes les heures, le battement de cœur | \`triggered_at\`, \`execution_count\` | \`triggers.md:23-27\` |
| 2 | \`core:fetch_listings\` (HTTP) | Lecture fraîche de la source live | \`data.organic_results[]\` | \`AGENTS.md:371\`; \`nodes.md:66\` |
| 3 | \`core:normalize\` (code) | Remet le JSON brut en forme vers \`{sku, price, currency, seen_at}\` | \`result\` (enveloppé) | \`CodeNode.java:130-137\` |
| 4 | \`find_rows\` (recherche de baseline) | Sonde d'idempotence par \`sku\` | \`items\`, \`item_count\` | \`ConceptsHelpProvider.java:281\` |
| 5 | \`core:decision\` (nouveau vs connu) | Sépare sur \`item_count == 0\` | \`selected_branch\` | \`nodes.md:29\` |
| 6a | \`insert_row\` (branche nouvelle) | Écrit la baseline | ligne insérée | \`tables.md:52\` |
| 6b | \`core:decision\` (mouvement significatif) | Signale un mouvement au-dessus de 5 % | \`selected_branch\` | \`expressions.md:96\` |
| 7 | \`core:user_approval\` | Porte humaine avant l'écriture | \`approved\`/\`rejected\`/\`timeout\` | \`nodes.md:39\` |
| 8 | \`mcp:send_alert\` + \`update_row\` | La vraie action, puis l'écriture protégée | envoyé, ligne fusionnée | \`nodes.md:62\`; \`tables.md:49\` |

Les trois opérations de table correspondent aux tuiles de la palette du builder Create Row / Find Rows / Update Row (types \`create-row\` / \`find\` / \`update-row\`) ; les noms \`insert_row\` / \`find_rows\` / \`update_row\` de la prose sont les alias d'outils-agent de ces tuiles.

Chaque sortie de nœud se référence avec une forme uniforme unique, quel que soit le type de nœud :

\`\`\`
{{type:label.output.field}}
\`\`\`

Le segment \`.output.\` est obligatoire (\`WORKFLOW_NODE_TYPES.md:1650-1660\`; \`expressions.md:9\`). Les champs imbriqués et l'indexation de tableau fonctionnent tous les deux (\`expressions.md:28-32\`) :

\`\`\`
{{mcp:api_call.output.data.users[0].email}}
\`\`\`

Les labels se normalisent via un pipeline fixe en cinq étapes : translittérer les accents, mettre en minuscules, remplacer chaque caractère non alphanumérique par un underscore, réduire les répétitions, rogner les extrémités (\`LabelNormalizer.java:55-82\`). Ainsi un nœud que vous nommez \`Baseline Lookup\` se référence comme :

\`\`\`
{{table:baseline_lookup.output.item_count}}
\`\`\`

Si un LLM rédige un label brut avec des espaces à l'intérieur d'un template, le moteur le normalise automatiquement avant l'évaluation (\`LabelNormalizer.java:496-537\`), c'est pourquoi les espaces ne cassent pas la résolution. Une contrainte dure régit ce que tout nœud peut lire : il ne peut référencer que ses ancêtres, les nœuds qui se sont déjà exécutés. Les pairs et les branches parallèles ne se voient pas entre eux, et il n'y a pas de référence en avant. Le moteur résout uniquement depuis \`context.stepOutputs\` (\`WORKFLOW_NODE_TYPES.md:1617-1644\`).

Le déclencheur planifié prend uniquement un cron standard à cinq champs. La valeur par défaut du builder \`0 * * * *\` est horaire, et un raccourci d'intervalle comme \`5m\` ou \`1h\` est rejeté d'emblée (\`triggers.md:23-27\`). Il émet \`triggered_at\` et un \`execution_count\` en base un, et chaque déclenchement ouvre une nouvelle epoch (\`EXECUTION_ENGINE.md:15\`).

## Rafraîchir et lire : le battement de cœur et la vraie forme de la réponse

Le nœud 1 est le battement de cœur. Le nœud 2 est un nœud HTTP Request qui récupère l'annonce courante pour le seul SKU que ce workflow suit. C'est là que « se rafraîchit tout seul » cesse d'être un slogan et se met à dépendre d'un payload réel.

La leçon portable : liez-vous à la réponse réelle, pas au schéma déclaré. Un schéma déclaré est une promesse. Le fil est la vérité, et ils sont en désaccord plus souvent que personne ne l'admet.

Un exemple vérifié en production le rend concret. Le \`amazon_search\` de SerpAPI renvoie les items sous \`organic_results[]\`, chacun portant \`title\`, \`thumbnail\`, \`price\`, \`extracted_price\`, \`rating\`, \`reviews\`, \`badges\`, \`sponsored\`, et \`delivery[]\`. Ce qu'il ne porte pas, c'est un booléen \`prime\` ou un champ \`brand\`. Pour savoir si un item est expédié en Prime, vous faites correspondre \`/prime/i\` contre le tableau \`delivery[]\`, pas un champ \`prime\` qui n'existe pas (\`AGENTS.md:371\`). Pendant ce temps, le \`outputSchema\` déclaré du catalogue liste avec optimisme un booléen \`prime\` (\`serpapi.json:8879\`), un \`brand\` (\`serpapi.json:8849\`), et \`delivery\` comme un objet (\`serpapi.json:8889\`). Le payload live contredit les trois. Lisez ce qui arrive.

Il y a une seconde raison pour laquelle le nœud de lecture ne peut pas être cru aveuglément. Un nœud HTTP Request traite un 404 ou un 500 comme un succès au niveau du nœud. Seule une erreur de transport fait échouer le nœud (\`nodes.md:66\`). Donc l'étape de normalisation qui suit doit se défendre contre une erreur en forme de corps, une erreur livrée à l'intérieur d'un 200. Ne supposez pas qu'un échec de nœud l'attrapera, car il ne le fera pas.

## Remettre en forme : le nœud de code, et les deux pièges qui le font paraître vide

Le nœud 3 est un nœud \`core:code\` qui aplatit la réponse brute vers la forme dont tout l'aval a besoin : \`{sku, price, currency, seen_at}\`. Il prend exactement trois paramètres : \`code\`, \`language\`, et \`timeoutSeconds\`. Il n'y a pas de \`input_mapping\`. Les langages sont \`javascript\`, \`python\`, \`typescript\`, et \`bash\`, et \`timeoutSeconds\` est borné à la plage 1 à 120, avec une valeur par défaut de 10 (\`CodeNode.java:67-70\`, \`:170-177\`).

Comme les items \`amazon_search\` ne portent aucun champ \`sku\` ni \`currency\`, la normalisation les dérive : \`sku\` depuis l'identifiant produit (le \`asin\`, ou parsé depuis le lien produit), et \`currency\` comme une constante pour une surveillance mono-marketplace, puisque ni l'un ni l'autre n'est un champ de première classe dans la réponse. C'est aussi là que vit le garde-fou contre l'erreur en forme de corps : inspectez le corps du 200 pour une clé d'erreur et confirmez le tableau avant de mapper. La version WRONG lit \`organic_results\` directement et laisse un corps d'erreur s'écouler vers l'aval ; la version CORRECT échoue bruyamment d'abord :

\`\`\`
const res = $input.fetch_listings && $input.fetch_listings.data;
if (!res || res.error || !Array.isArray(res.organic_results)) {
  throw new Error("bad body: " + JSON.stringify($input).slice(0, 300));
}
const top = res.organic_results[0];
$output = {
  sku: top.asin,
  price: top.extracted_price,
  currency: "USD",
  seen_at: new Date().toISOString()
};
\`\`\`

Comme la normalisation prend \`organic_results[0]\`, \`$output\` est un objet unique, pas un tableau. Cela compte : une sortie de normalisation en forme de tableau ferait que le template à valeur unique \`{{core:normalize.output.result.sku}}\` ne résoudrait vers rien, la valeur \`find_rows\` du garde-fou serait vide, \`item_count\` lirait 0 à chaque exécution, et une ligne à SKU vide serait insérée toutes les heures avec le garde-fou idempotent silencieusement mis en échec. Gardez la normalisation qui émet un seul objet ; si vous avez un jour besoin de vous déployer sur de nombreuses annonces, c'est un nœud \`core:split\`, pas un simple retour de tableau.

Deux pièges font qu'un nœud de code paraît vide sans aucune erreur, ce qui est le pire type d'échec parce qu'il n'y a rien dans le log à poursuivre.

Le premier piège portable est la forme de l'entrée. Les données amont n'arrivent pas à la racine de votre objet d'entrée. Elles arrivent indexées par le label du nœud prédécesseur. Sur ce moteur, le wrapper JavaScript injecte \`const $input = JSON.parse(...)\` et \`let $output = undefined\` (\`CodeNode.java:180-190\`), et la sortie de chaque étape amont est placée sous sa propre clé de label avec son enveloppe retirée (\`CodeNode.java:300-319\`; \`OutputUnwrapper.java:178-186\`). Donc vous lisez la sortie de fetch comme \`$input.fetch_listings.data.organic_results\`, ou \`$input['core:fetch_listings']\` si vous préférez l'accès par crochets. Vous ne lisez jamais \`$input.organic_results\`, qui est undefined. Vous assignez votre résultat à \`$output\`, et il est capturé via un préfixe stdout \`__RESULT__\` puis reparsé en JSON (\`CodeNode.java:180-190\`). Python utilise \`_input\` et \`_output\`, bash utilise \`INPUT\` et \`OUTPUT\`.

Le deuxième piège portable est l'imbrication de la sortie. Beaucoup de moteurs enveloppent ce que vous retournez dans une enveloppe qui leur est propre. Ici, le moteur enveloppe votre objet \`$output\` sous une clé \`result\` supplémentaire (\`CodeNode.java:130-137\`, \`result.put("result", parsedResult)\`; \`CodeNodeSpec.java:22-26\`). En aval, vous devez forer au-delà :

\`\`\`
{{core:normalize.output.result.sku}}
\`\`\`

Et pour mapper l'objet normalisé entier dans un paramètre aval, vous pointez sur \`.result\` :

\`\`\`
{"result":"{{core:normalize.output.result}}"}
\`\`\`

Trompez-vous sur l'imbrication et vous obtenez un double \`result.result\` silencieux et une lecture vide, jamais une erreur (\`AGENTS.md\` note Interface System).

Une mécanique de soutien explique pourquoi le mappage d'objet entier ci-dessus doit être un template solitaire. Un pur \`{{...}}\` unique renvoie la valeur typée, un Number, une Map, ou une List. La même expression noyée dans une prose environnante est coercée en String, avec les Maps auto-encodées en JSON (\`expressions.md:72-74\`). Les paramètres de type objet doivent donc être un template unique, jamais cousus dans du texte.

## Le tableau correct-contre-faux que personne d'autre n'a

Chaque ligne énonce le piège général en mots simples ; les chaînes exactes fausse et correcte pour ce moteur sont encadrées immédiatement en dessous, de sorte que la différence d'un seul token soit lisible sans entasser un long template dans une cellule de tableau.

| Nœud / opération | Piège général (portable) | Cité depuis |
|---|---|---|
| Lecture de champ nœud-de-code | L'objet retourné est sous une enveloppe | \`CodeNode.java:130-137\` |
| Mappage d'objet entier nœud-de-code | L'enveloppe doit être incluse au mappage de l'objet entier | \`AGENTS.md\` GOTCHA |
| Lecture d'entrée nœud-de-code | L'entrée est indexée par label prédécesseur, pas la racine | \`CodeNode.java:300-319\` |
| Colonne where de table | La colonne est le nom stocké nu | \`CrudRepository.java:369-372\` |
| Seuil numérique | Un filtre qui paraît numérique peut comparer comme du texte | \`CrudRepository.java:378-416\` |
| Construire un param objet | Certaines transforms stringifient les objets | \`AGENTS.md\` finding #2 |

Lecture de champ nœud-de-code :

\`\`\`
WRONG:   {{core:normalize.output.sku}}
CORRECT: {{core:normalize.output.result.sku}}
\`\`\`

Mappage d'objet entier nœud-de-code :

\`\`\`
WRONG:   {"result":"{{core:normalize.output}}"}
CORRECT: {"result":"{{core:normalize.output.result}}"}
\`\`\`

Lecture d'entrée nœud-de-code :

\`\`\`
WRONG:   $input.organic_results
CORRECT: $input.fetch_listings.data.organic_results
\`\`\`

Colonne where de table :

\`\`\`
WRONG:   {column:'data.sku', operator:'=', value:'ABC-123'}
CORRECT: {column:'sku', operator:'=', value:'ABC-123'}
\`\`\`

Seuil numérique (faites le calcul dans un \`core:decision\`, pas la requête) :

\`\`\`
WRONG:   {column:'price', operator:'>', value:9}
CORRECT: compare in core:decision (SpEL, numeric)
\`\`\`

Construire un param objet :

\`\`\`
WRONG:   assemble the object in a core:transform mapping
CORRECT: assemble it in a core:code node ($output keeps JSON types)
\`\`\`

La ligne transform brûle les gens qui ne s'en méfient jamais. Un nœud \`core:transform\` stringifie les valeurs d'objet. Un objet que vous assemblez à l'intérieur d'une expression de transform atteint un paramètre d'outil de type objet en aval comme une String, produisant une erreur de fournisseur comme \`expected map, actual string\` (\`AGENTS.md\` workflow-builder finding #2). Les valeurs de type objet doivent plutôt être construites dans un nœud \`core:code\`, où les champs \`$output\` conservent leurs vrais types JSON à travers le template à valeur entière.

La ligne colonne-where de table vaut aussi la peine d'être intériorisée. Les données utilisateur vivent dans une unique colonne JSONB \`data\`, et la colonne where est le nom nu. Un préfixe \`data.\` de tête est auto-retiré à la fois au moment du build et à l'exécution, et une colonne pointée est sinon rejetée par le sanitizer, donc le retrait est obligatoire plutôt que cosmétique (\`CrudRepository.java:369-372\`; \`SqlSanitizer.java:46\`). Le nom réservé \`id\` correspond à la clé primaire de la ligne via \`id::text\`, pas un champ JSONB.

## Décider : là où la comparaison a réellement lieu

Le nœud 5 est la couche de décision, et il cache la mécanique la plus surprenante du build.

Le piège portable : un filtre qui paraît numérique peut comparer comme du texte, et l'ordre du texte n'est pas l'ordre des nombres. Sur ce moteur, les clauses where du CRUD de table comparent tout comme du texte. Les colonnes stockées sont lues via \`jsonb_extract_path_text(data, :col)\`, la clé primaire via \`id::text\`, et la valeur liée passe par \`.toString()\` (\`CrudRepository.java:378-416\`). Pendant ce temps, la comparaison SpEL à l'intérieur d'une condition de décision est numérique (\`expressions.md:96\`). Opérateur \`>\` d'apparence identique, deux mondes différents.

| Où la comparaison s'exécute | Type de comparaison | Opérateurs fiables | Opérateurs qui trompent | Cité depuis |
|---|---|---|---|---|
| Clause where du CRUD de table | Textuel / lexicographique | \`=\`, \`!=\`, \`IN\`, \`IS NULL\`, \`IS NOT NULL\`, \`LIKE\` | \`>\`, \`<\`, \`>=\`, \`<=\` | \`CrudRepository.java:378-416\` |
| \`core:decision\` (SpEL) | Numérique | tous les opérateurs de comparaison | aucun pour les nombres | \`expressions.md:96\` |

La conséquence est un vrai bug latent. Dans une clause where, \`amount > 9\` exclut \`'100'\`, parce que \`'1'\` se trie avant \`'9'\`. Et \`id > 5\` saute silencieusement les ids 10 à 99 (\`WorkflowBuilderHelpModule.java:258-262\`). Les opérateurs d'ordre sont sûrs dans une clause where uniquement quand l'ordre lexical se trouve correspondre à l'intention, ce qui signifie des chaînes remplies de zéros à gauche ou des dates ISO en forme \`yyyy-MM-dd\` (\`WorkflowBuilderHelpModule.java:262\`). Il n'y a pas d'opérateur d'ordre à cast numérique vers lequel se tourner ; une comparaison consciente du numérique est un correctif connu mais non livré au moment de l'écriture.

Donc le calcul « le prix a-t-il bougé de plus de 5 % » appartient au nœud 6b, un \`core:decision\`, pas à la requête. Il a besoin du prix précédent, qui vit dans le résultat \`find_rows\` : \`find_rows\` renvoie \`items[]\`, et chaque ligne correspondante expose ses champs aplatis, donc le prix de baseline est à \`items[0].price\` (\`ConceptsHelpProvider.java:281\`; indexation de tableau selon \`expressions.md:28-32\`). Comme la valeur stockée est revenue par le même chemin texte que toute lecture JSONB, l'arithmétique doit la caster : enveloppez les deux opérandes dans \`double()\` avant de soustraire. La condition :

\`\`\`
{{ (double(core:normalize.output.result.price) - double(table:baseline_lookup.output.items[0].price)) / double(table:baseline_lookup.output.items[0].price) > 0.05 }}
\`\`\`

Une décision active exactement une branche. La première condition vraie l'emporte, et le reste devient SKIPPED. Ses ports sont \`if\`, \`elseif_N\`, et \`else\` (\`nodes.md:29\`; \`WORKFLOW_NODE_TYPES.md:411-418\`).

Une règle structurelle noue le graphe ensemble. Les edges sont de simples enregistrements \`{from, to}\` avec un suffixe \`:port\` optionnel, et les conditions de branche ne vivent jamais sur l'edge. Elles vivent dans le nœud \`cores[]\`, en tant que \`decisionConditions\` ou \`switchCases\` (\`WORKFLOW_NODE_TYPES.md:33-40\`, \`:349-361\`). Deux conséquences découlent de la topologie des edges à elle seule. Plusieurs edges non conditionnés sortant d'une source forment un Fork implicite, exécutant toutes les branches en parallèle. Plusieurs edges entrant dans un nœud forment un AND-merge implicite qui attend que chaque prédécesseur se résolve, qu'il soit COMPLETED ou SKIPPED (\`WORKFLOW_NODE_TYPES.md:1008-1010\`, \`:1053-1056\`, \`:925-940\`).

## Le garde-fou d'écriture idempotente, dessiné en vrai sous-graphe

Un déclencheur auto-rafraîchissant lance la même lecture toutes les heures. Sans garde-fou, il insère la baseline du même SKU toutes les heures, et la table se remplit de doublons. Le schéma général qui corrige cela sur n'importe quel moteur : chercher d'abord, décider sur le compte, puis écrire uniquement quand l'item est nouveau. N'insérez jamais sans condition quand le même item peut être re-récupéré.

Ce moteur n'a pas d'upsert ni d'opération de truncate, c'est précisément pourquoi le garde-fou est obligatoire plutôt qu'optionnel (\`tables.md:49\`; \`CrudRepository.java\` \`deleteRows\` exige un where validé).

| Étape | Nœud | Branche / port pris | Effet sur la table | Cité depuis |
|---|---|---|---|---|
| 1 | \`find_rows\` par \`sku\` | (alimente la décision) | lit, n'écrit rien | \`ConceptsHelpProvider.java:281\` |
| 2 | \`core:decision\` sur item_count | \`if\` (vrai) = jamais vu | rien encore | \`WorkflowBuilderHelpModule.java:252-254\` |
| 3a | \`insert_row\` (baseline) | sur la branche \`if\` | une nouvelle ligne écrite | \`tables.md:52\` |
| 3b | décision de changement significatif | sur la branche \`else\` | rien encore | \`nodes.md:29\` |
| 4 | \`update_row\` (après approbation) | port approved | clés JSONB nommées fusionnées | \`tables.md:49\` |

Les deux chaînes exactes du garde-fou, encadrées pour que les templates restent entiers :

\`\`\`
find_rows {column:'sku', operator:'=', value:'{{core:normalize.output.result.sku}}'}
\`\`\`

\`\`\`
{{table:baseline_lookup.output.item_count == 0}}
\`\`\`

La sonde qui fait fonctionner cela est \`find_rows\`, qui expose \`items[]\` (les lignes trouvées) et \`item_count\` (le compte). Un \`item_count\` de 0 est le signal « pas encore traité » qui transforme la table en mémoire partagée entre les exécutions (\`ConceptsHelpProvider.java:281\`). Le garde-fou chercher-puis-décider est ce qui rend un workflow rafraîchissant sûr (\`AGENTS.md\` \`dedupe_idempotent_write\`).

L'écriture sur le chemin du SKU connu est un \`update_row\`, qui exige à la fois un where et une map set non vide, et fusionne uniquement les clés JSONB nommées via \`data || jsonb_build_object\` (\`tables.md:49\`). C'est une fusion partielle, pas un remplacement, donc il ne mettra pas à null les champs que vous omettez.

Un piège de locataire vous fera perdre un après-midi si vous ne le connaissez pas. L'outil MCP \`table\` s'exécute sous le locataire de l'utilisateur du chat, pas celui du propriétaire du workflow. Chaque requête CRUD est cadrée avec \`AND tenant_id = :tenant_id\`, donc l'outil peut afficher 0 ligne pendant que le propre \`find_rows\` du workflow voit les vraies données (\`AGENTS.md\`). Pour inspecter ou effacer une table possédée par un workflow, exécutez l'opération depuis l'intérieur de ce workflow, dans le bon locataire.

## Verrouiller, puis agir

Le nœud 7 est le contrôle humain avant l'étape irréversible. Le principe général : mettez une porte bloquante avant toute action que vous ne pouvez pas défaire, et rendez-la déterministe sur ce qui se passe ensuite.

Sur ce moteur, la porte est un signal \`USER_APPROVAL\`. Le nœud cède AWAITING_SIGNAL et l'exécution se met en pause. USER_APPROVAL est toujours bloquant, contrairement à un signal d'interface, qui ne bloque que lorsque \`__continue\` est mappé (\`EXECUTION_ENGINE.md:15\`; \`INTERFACE_NODE_GUIDE.md:783-787\`). Le nœud a trois ports de reprise nommés, \`approved\`, \`rejected\`, et \`timeout\`, et il route de façon déterministe selon la décision prise (\`nodes.md:39\`; \`WorkflowHelpProvider.java:665\`). Le timeout par défaut est de 24 heures quand il n'est pas défini (\`nodes.md:39\`).

Comme un rafraîchissement se déclenche toutes les heures, deux questions comptent. D'abord, que se passe-t-il si l'approbation est déclenchée deux fois ? Rien de mal. La résolution est réclamer-avant-traiter : \`resolveSignal()\` renvoie false sur un signal déjà résolu, donc une approbation re-déclenchée ne fait jamais doublement avancer le DAG (\`INTERFACE_NODE_GUIDE.md:1008\`). Ensuite, qu'arrive-t-il au prochain déclenchement planifié pendant qu'un humain est posé sur la décision ? Chaque déclenchement ouvre une nouvelle epoch, les résultats de l'epoch précédente persistent et restent consultables, et un signal bloquant diffère la réinitialisation du cycle de déclenchement jusqu'à sa résolution (\`EXECUTION_ENGINE.md:15\`). Le rafraîchissement ne piétine pas une décision en attente.

Sur le port \`approved\`, la vraie action se déclenche. Cela peut être un nœud Send Email de première classe ou toute intégration \`mcp:\` connectée (\`nodes.md:62\`), suivi du \`update_row\` protégé. Sur les ports \`rejected\` et \`timeout\`, rien n'est écrit et rien n'est envoyé.

## Prouver chaque branche avant de la déclarer live

La règle de test n'est pas négociable : exercez chaque branche contre un orchestrateur live et suivez le log du service en parallèle. Une réponse verte avec une stacktrace dans le log est un échec, pas une réussite (\`AGENTS.md\` Feature Development Flow step 4). « Ça a renvoyé 200 » n'est pas la preuve que la branche a fonctionné.

| Scénario | Condition de déclenchement | Branche / signal attendu | Assertion de réussite | Signal d'échec |
|---|---|---|---|---|
| Insertion nouveau SKU | SKU sans ligne de baseline | branche \`if\`, \`insert_row\` | exactement une ligne insérée | ligne dupliquée, ou stacktrace dans le log |
| Pas de changement | SKU connu, prix à 5 % près | décision significative \`else\` | aucun flag, aucune approbation, aucune alerte | toute alerte ou pause |
| Changement significatif | SKU connu, mouvement au-dessus de 5 % | l'exécution PAUSE à AWAITING_SIGNAL | statut AWAITING_SIGNAL USER_APPROVAL | l'exécution se termine sans pause |
| Ports d'approbation | Résoudre chacun des trois ports | approved / rejected / timeout | approved écrit + alerte ; les autres ne font ni l'un ni l'autre | écriture sur rejected/timeout |
| Idempotence de réexécution | Déclencher le planning deux fois | le garde-fou bloque la seconde insertion | compte de lignes stable | le compte de lignes grandit |

Exécutez les cinq avant de faire confiance au graphe. Le scénario de changement significatif devrait visiblement mettre en pause ; s'il se termine, votre calcul de seuil est dans la mauvaise couche, probablement une clause where lexicographique se faisant passer pour numérique.

Trois leçons se transportent vers tout moteur sur lequel vous construirez ensuite. Imbrication de sortie : forez jusqu'à \`{{core:normalize.output.result.sku}}\`, jamais \`{{core:normalize.output.sku}}\`, parce que les plateformes enveloppent ce que vous retournez. Comparaison textuelle : calculez le mouvement de 5 % dans un \`core:decision\`, pas dans la clause where de \`find_rows\`, parce que cette comparaison est lexicale. Objets stringifiés : construisez les valeurs typées dans un nœud \`core:code\`, pas un \`core:transform\` qui les aplatit en chaînes. Et le garde-fou chercher-puis-décider est le schéma qui rend un workflow auto-rafraîchissant sûr partout, parce qu'un planning qui agit n'est fiable qu'à hauteur de sa défense contre sa propre répétition.
`;

export default content;
