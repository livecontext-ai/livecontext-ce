// from-dataset-to-live-workflow - de
// Translated from the English body; structure identical. Everything inside a
// fenced code block, every {{...}} template, node name, prefix, output field,
// enum value and file:line citation is byte-identical to English; only prose is
// translated. A wrong template string is the one error a reader copies.
const content = `--- TRANSLATION ---
Du hast die Daten gewählt. Jetzt muss es sich selbst ausführen.

Ein qualifizierter Nischen-Datensatz ist träge, bis etwas ihn nach einem Zeitplan liest, auf seiner Grundlage entscheidet und in einer Aktion endet, der ein Mensch vertraut. Dieser Beitrag setzt genau dort an: Der Datensatz ist bereits gewählt. Wie man einen Nischen-Datensatz auswählt und welchen Kontext und welches Budget sein Betrieb kostet, behandeln die Begleitbeiträge (einmal verlinkt, hier nicht erneut ausgeführt). Dieser beginnt, nachdem die Daten gewählt sind, und endet bei einem laufenden Workflow.

Jede Node-Mechanik weiter unten ist mit dem Code und der Dokumentation einer produktiven Workflow-Engine belegt, samt den exakten Strings. Der durchgearbeitete Aufbau in einer Zeile: Ein Schedule-Trigger aktualisiert stündlich, eine HTTP-Anfrage holt das getrackte Listing erneut, eine Code-Node normalisiert die rohe Antwort, ein Table-Lookup plus eine Entscheidung trennt eine nie gesehene SKU von einer bekannten, eine zweite Entscheidung markiert eine wesentliche Preisbewegung, ein User-Approval-Gate sichert den Schreibvorgang, und erst dann feuert ein Alert. Ein idempotenter Baseline-Schreibvorgang bedeutet, dass Wiederholungsläufe nie eine einzige Zeile duplizieren. Für jede Node ist das Muster dasselbe: erst die übertragbare Falle, dann der exakte String dieser Engine.

## Der Graph: acht Nodes, sieben Präfixe

Bevor der Text den Aufbau durchgeht, sieh das Ganze auf einen Blick. Die Engine benennt jede Node mit einem Kategorie-Präfix. Es gibt sieben: \`trigger:\`, \`mcp:\`, \`table:\`, \`agent:\`, \`core:\`, \`note:\` und \`interface:\` (\`LabelNormalizer.java:14-24\`, \`:262-265\`). Die \`core:\`-Familie ist die größte und umfasst Loop, Split, Decision, Switch, Merge, Transform, Wait, Fork, Download File, HTTP Request, Data Input und User Approval (\`LabelNormalizer.java:182\`). Beachte, dass HTTP Request eine \`core:\`-Node ist, keine \`mcp:\`-Node (\`WORKFLOW_NODE_TYPES.md:1559-1594\`).

| # | Node (Rolle) | Was sie im Aufbau tut | Wichtiges Ausgabefeld | Belegt mit |
|---|---|---|---|---|
| 1 | Schedule-Trigger | Feuert stündlich, der Herzschlag | \`triggered_at\`, \`execution_count\` | \`triggers.md:23-27\` |
| 2 | \`core:fetch_listings\` (HTTP) | Frisches Lesen der Live-Quelle | \`data.organic_results[]\` | \`AGENTS.md:371\`; \`nodes.md:66\` |
| 3 | \`core:normalize\` (Code) | Rohes JSON umformen zu \`{sku, price, currency, seen_at}\` | \`result\` (verpackt) | \`CodeNode.java:130-137\` |
| 4 | \`find_rows\` (Baseline-Lookup) | Idempotenz-Sonde per \`sku\` | \`items\`, \`item_count\` | \`ConceptsHelpProvider.java:281\` |
| 5 | \`core:decision\` (neu vs. bekannt) | Aufteilung bei \`item_count == 0\` | \`selected_branch\` | \`nodes.md:29\` |
| 6a | \`insert_row\` (neuer Zweig) | Baseline schreiben | eingefügte Zeile | \`tables.md:52\` |
| 6b | \`core:decision\` (wesentliche Bewegung) | Bewegung über 5% markieren | \`selected_branch\` | \`expressions.md:96\` |
| 7 | \`core:user_approval\` | Menschliches Gate vor dem Schreiben | \`approved\`/\`rejected\`/\`timeout\` | \`nodes.md:39\` |
| 8 | \`mcp:send_alert\` + \`update_row\` | Die echte Aktion, dann der gesicherte Schreibvorgang | gesendet, gemergte Zeile | \`nodes.md:62\`; \`tables.md:49\` |

Die drei Table-Operationen sind auf Builder-Paletten-Kacheln Create Row / Find Rows / Update Row abgebildet (Arten \`create-row\` / \`find\` / \`update-row\`); die im Text genannten Namen \`insert_row\` / \`find_rows\` / \`update_row\` sind die Agent-Tool-Aliase für diese Kacheln.

Jede Node-Ausgabe wird mit einer einheitlichen Form referenziert, unabhängig vom Node-Typ:

\`\`\`
{{type:label.output.field}}
\`\`\`

Das Segment \`.output.\` ist verpflichtend (\`WORKFLOW_NODE_TYPES.md:1650-1660\`; \`expressions.md:9\`). Verschachtelte Felder und Array-Indizierung funktionieren beide (\`expressions.md:28-32\`):

\`\`\`
{{mcp:api_call.output.data.users[0].email}}
\`\`\`

Labels werden über eine feste fünfstufige Pipeline normalisiert: Akzente transliterieren, in Kleinbuchstaben umwandeln, jedes nicht-alphanumerische Zeichen durch einen Unterstrich ersetzen, Wiederholungen zusammenfassen, die Enden trimmen (\`LabelNormalizer.java:55-82\`). Eine Node, die du \`Baseline Lookup\` nennst, wird also so referenziert:

\`\`\`
{{table:baseline_lookup.output.item_count}}
\`\`\`

Wenn ein LLM ein rohes Label mit Leerzeichen innerhalb eines Templates schreibt, normalisiert die Engine es vor der Auswertung automatisch (\`LabelNormalizer.java:496-537\`), weshalb die Leerzeichen die Auflösung nicht brechen. Eine harte Einschränkung bestimmt, was eine Node lesen kann: Sie kann nur ihre Vorfahren referenzieren, also die bereits ausgeführten Nodes. Nachbarn und parallele Zweige können einander nicht sehen, und es gibt keine Vorwärtsreferenz. Die Engine löst ausschließlich aus \`context.stepOutputs\` auf (\`WORKFLOW_NODE_TYPES.md:1617-1644\`).

Der Schedule-Trigger akzeptiert nur einen standardmäßigen fünffeldrigen Cron. Der Builder-Standard \`0 * * * *\` ist stündlich, und Intervall-Kurzschreibweisen wie \`5m\` oder \`1h\` werden rundweg abgelehnt (\`triggers.md:23-27\`). Er emittiert \`triggered_at\` und einen bei eins beginnenden \`execution_count\`, und jedes Feuern öffnet eine neue Epoche (\`EXECUTION_ENGINE.md:15\`).

## Aktualisieren und lesen: der Herzschlag und die echte Form der Antwort

Node 1 ist der Herzschlag. Node 2 ist eine HTTP-Request-Node, die das aktuelle Listing für die eine SKU zieht, die dieser Workflow trackt. Hier hört "aktualisiert sich selbst" auf, ein Slogan zu sein, und beginnt, von einer echten Nutzlast abzuhängen.

Die übertragbare Lektion: Binde an die tatsächliche Antwort, nicht an das deklarierte Schema. Ein deklariertes Schema ist ein Versprechen. Die Leitung ist die Wahrheit, und die beiden widersprechen sich häufiger, als irgendjemand zugibt.

Ein produktiv verifiziertes Beispiel macht es konkret. SerpAPIs \`amazon_search\` liefert Elemente unter \`organic_results[]\`, jedes trägt \`title\`, \`thumbnail\`, \`price\`, \`extracted_price\`, \`rating\`, \`reviews\`, \`badges\`, \`sponsored\` und \`delivery[]\`. Was es nicht trägt, ist ein \`prime\`-Boolean oder ein \`brand\`-Feld. Um zu wissen, ob ein Element mit Prime versendet wird, matchst du \`/prime/i\` gegen das \`delivery[]\`-Array, nicht ein \`prime\`-Feld, das nicht existiert (\`AGENTS.md:371\`). Zugleich listet das deklarierte \`outputSchema\` des Katalogs optimistisch ein Boolean \`prime\` (\`serpapi.json:8879\`), ein \`brand\` (\`serpapi.json:8849\`) und \`delivery\` als ein Objekt (\`serpapi.json:8889\`). Die Live-Nutzlast widerspricht allen dreien. Lies, was ankommt.

Es gibt einen zweiten Grund, warum der Lese-Node nicht blind vertraut werden kann. Eine HTTP-Request-Node behandelt einen 404 oder 500 als Node-Level-Erfolg. Nur ein Transportfehler lässt die Node scheitern (\`nodes.md:66\`). Der folgende Normalisierungsschritt muss sich also gegen einen körperförmigen Fehler wehren, einen Fehler, der innerhalb eines 200 geliefert wird. Nimm nicht an, dass ein Node-Fehler ihn abfängt, denn das wird er nicht.

## Umformen: die Code-Node und die zwei Fallen, die sie leer aussehen lassen

Node 3 ist eine \`core:code\`-Node, die die rohe Antwort in die Form abflacht, die alles Nachgelagerte braucht: \`{sku, price, currency, seen_at}\`. Sie nimmt genau drei Parameter: \`code\`, \`language\` und \`timeoutSeconds\`. Es gibt kein \`input_mapping\`. Sprachen sind \`javascript\`, \`python\`, \`typescript\` und \`bash\`, und \`timeoutSeconds\` wird auf den Bereich 1 bis 120 begrenzt, mit Standardwert 10 (\`CodeNode.java:67-70\`, \`:170-177\`).

Weil \`amazon_search\`-Elemente keine \`sku\` und kein \`currency\`-Feld tragen, leitet normalize sie ab: \`sku\` aus dem Produktbezeichner (der \`asin\` oder aus dem Produktlink geparst) und \`currency\` als Konstante für eine Ein-Marktplatz-Überwachung, da keines ein erstklassiges Feld in der Antwort ist. Hier lebt auch der Schutz gegen den körperförmigen Fehler: Inspiziere den 200-Body auf einen Fehlerschlüssel und bestätige das Array, bevor du mappst. Die WRONG-Version liest \`organic_results\` direkt und lässt einen Fehler-Body nachgelagert weiterfließen; die CORRECT-Version scheitert zuerst laut:

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

Weil normalize \`organic_results[0]\` auswählt, ist \`$output\` ein einzelnes Objekt, kein Array. Das ist wichtig: Eine array-förmige normalize-Ausgabe würde das Einzelwert-Template \`{{core:normalize.output.result.sku}}\` zu nichts auflösen, der \`find_rows\`-Wert des Schutzes wäre leer, \`item_count\` würde bei jedem Lauf 0 lesen, und eine Zeile mit leerer SKU würde stündlich eingefügt, wobei der idempotente Schutz still ausgehebelt wäre. Halte normalize dabei, ein Objekt zu emittieren; wenn du je über viele Listings ausfächern musst, ist das eine \`core:split\`-Node, keine nackte Array-Rückgabe.

Zwei Fallen lassen eine Code-Node ganz ohne Fehler leer aussehen, was die schlimmste Art von Fehler ist, weil es im Log nichts zu verfolgen gibt.

Übertragbare Falle eins ist die Eingabeform. Vorgelagerte Daten kommen nicht an der Wurzel deines Eingabeobjekts an. Sie kommen unter dem Label der Vorgänger-Node keyed an. In dieser Engine injiziert der JavaScript-Wrapper \`const $input = JSON.parse(...)\` und \`let $output = undefined\` (\`CodeNode.java:180-190\`), und die Ausgabe jedes vorgelagerten Schritts wird unter ihrem eigenen Label-Schlüssel platziert, mit entferntem Envelope (\`CodeNode.java:300-319\`; \`OutputUnwrapper.java:178-186\`). Du liest die fetch-Ausgabe also als \`$input.fetch_listings.data.organic_results\` oder \`$input['core:fetch_listings']\`, falls du Klammerzugriff bevorzugst. Du liest nie \`$input.organic_results\`, was undefined ist. Du weist dein Ergebnis \`$output\` zu, und es wird über ein \`__RESULT__\`-stdout-Präfix erfasst und per JSON zurückgeparst (\`CodeNode.java:180-190\`). Python nutzt \`_input\` und \`_output\`, bash nutzt \`INPUT\` und \`OUTPUT\`.

Übertragbare Falle zwei ist die Ausgabeverschachtelung. Viele Engines verpacken das, was du zurückgibst, in einem eigenen Envelope. Hier verpackt die Engine dein \`$output\`-Objekt unter einem zusätzlichen \`result\`-Schlüssel (\`CodeNode.java:130-137\`, \`result.put("result", parsedResult)\`; \`CodeNodeSpec.java:22-26\`). Nachgelagert musst du darüber hinaus bohren:

\`\`\`
{{core:normalize.output.result.sku}}
\`\`\`

Und um das gesamte normalisierte Objekt in einen nachgelagerten Parameter zu mappen, zeigst du auf \`.result\`:

\`\`\`
{"result":"{{core:normalize.output.result}}"}
\`\`\`

Verfehle die Verschachtelung und du bekommst ein stilles doppeltes \`result.result\` und einen leeren Lesevorgang, nie einen Fehler (\`AGENTS.md\` Interface-System-Notiz).

Eine unterstützende Mechanik erklärt, warum das obige Ganzobjekt-Mapping ein alleinstehendes Template sein muss. Ein reines einzelnes \`{{...}}\` gibt den typisierten Wert zurück, eine Number, eine Map oder eine List. Derselbe Ausdruck, eingebettet in umgebenden Text, wird zu einem String gezwungen, wobei Maps automatisch als JSON kodiert werden (\`expressions.md:72-74\`). Objekt-typisierte Parameter müssen daher ein einzelnes Template sein, nie in Text eingenäht.

## Die Correct-versus-Wrong-Tabelle, die sonst niemand hat

Jede Zeile nennt die allgemeine Falle in einfachen Worten; die exakten falschen und korrekten Strings für diese Engine stehen unmittelbar darunter eingezäunt, sodass der Ein-Token-Unterschied lesbar ist, ohne ein langes Template in eine Tabellenzelle zu quetschen.

| Node / Operation | Allgemeine Falle (übertragbar) | Belegt mit |
|---|---|---|
| Code-Node-Feldlesen | Zurückgegebenes Objekt sitzt unter einem Envelope | \`CodeNode.java:130-137\` |
| Code-Node-Ganzobjekt-Mapping | Envelope muss beim Mapping des Ganzobjekts einbezogen werden | \`AGENTS.md\` GOTCHA |
| Code-Node-Eingabelesen | Eingabe ist per Vorgänger-Label keyed, nicht an der Wurzel | \`CodeNode.java:300-319\` |
| Table-where-Spalte | Spalte ist der bloße gespeicherte Name | \`CrudRepository.java:369-372\` |
| Numerischer Schwellwert | Ein Filter, der numerisch aussieht, kann als Text vergleichen | \`CrudRepository.java:378-416\` |
| Objektparameter bauen | Manche Transforms stringifizieren Objekte | \`AGENTS.md\` finding #2 |

Code-Node-Feldlesen:

\`\`\`
WRONG:   {{core:normalize.output.sku}}
CORRECT: {{core:normalize.output.result.sku}}
\`\`\`

Code-Node-Ganzobjekt-Mapping:

\`\`\`
WRONG:   {"result":"{{core:normalize.output}}"}
CORRECT: {"result":"{{core:normalize.output.result}}"}
\`\`\`

Code-Node-Eingabelesen:

\`\`\`
WRONG:   $input.organic_results
CORRECT: $input.fetch_listings.data.organic_results
\`\`\`

Table-where-Spalte:

\`\`\`
WRONG:   {column:'data.sku', operator:'=', value:'ABC-123'}
CORRECT: {column:'sku', operator:'=', value:'ABC-123'}
\`\`\`

Numerischer Schwellwert (die Rechnung in einer \`core:decision\` machen, nicht in der Query):

\`\`\`
WRONG:   {column:'price', operator:'>', value:9}
CORRECT: compare in core:decision (SpEL, numeric)
\`\`\`

Objektparameter bauen:

\`\`\`
WRONG:   assemble the object in a core:transform mapping
CORRECT: assemble it in a core:code node ($output keeps JSON types)
\`\`\`

Die Transform-Zeile verbrennt Leute, die es nie vermuten. Eine \`core:transform\`-Node stringifiziert Objektwerte. Ein Objekt, das du in einem Transform-Ausdruck zusammensetzt, erreicht einen nachgelagerten objekt-typisierten Tool-Parameter als String und erzeugt einen Provider-Fehler wie \`expected map, actual string\` (\`AGENTS.md\` workflow-builder finding #2). Objekt-typisierte Werte müssen stattdessen in einer \`core:code\`-Node gebaut werden, wo \`$output\`-Felder ihre echten JSON-Typen durch das Ganzwert-Template behalten.

Die Table-where-Spalte-Zeile ist ebenfalls wert, verinnerlicht zu werden. Nutzerdaten leben in einer einzigen JSONB-\`data\`-Spalte, und die where-Spalte ist der bloße Name. Ein führendes \`data.\`-Präfix wird sowohl zur Build-Zeit als auch zur Laufzeit automatisch entfernt, und eine gepunktete Spalte wird ansonsten vom Sanitizer abgelehnt, sodass das Entfernen verpflichtend statt kosmetisch ist (\`CrudRepository.java:369-372\`; \`SqlSanitizer.java:46\`). Der reservierte Name \`id\` wird über \`id::text\` auf den Primärschlüssel der Zeile abgebildet, nicht auf ein JSONB-Feld.

## Entscheiden: wo der Vergleich tatsächlich stattfindet

Node 5 ist die Entscheidungsschicht, und sie verbirgt die überraschendste einzelne Mechanik im Aufbau.

Die übertragbare Falle: Ein Filter, der numerisch aussieht, kann als Text vergleichen, und Text-Ordnung ist nicht Zahlen-Ordnung. In dieser Engine vergleichen Table-CRUD-where-Klauseln alles als Text. Gespeicherte Spalten werden über \`jsonb_extract_path_text(data, :col)\` gelesen, der Primärschlüssel über \`id::text\`, und der gebundene Wert läuft durch \`.toString()\` (\`CrudRepository.java:378-416\`). Zugleich ist der SpEL-Vergleich innerhalb einer Entscheidungsbedingung numerisch (\`expressions.md:96\`). Gleich aussehender \`>\`-Operator, zwei verschiedene Welten.

| Wo der Vergleich läuft | Vergleichstyp | Zuverlässige Operatoren | Operatoren, die in die Irre führen | Belegt mit |
|---|---|---|---|---|
| Table-CRUD-where-Klausel | Textuell / lexikografisch | \`=\`, \`!=\`, \`IN\`, \`IS NULL\`, \`IS NOT NULL\`, \`LIKE\` | \`>\`, \`<\`, \`>=\`, \`<=\` | \`CrudRepository.java:378-416\` |
| \`core:decision\` (SpEL) | Numerisch | alle Vergleichsoperatoren | keiner für Zahlen | \`expressions.md:96\` |

Die Folge ist ein echter latenter Bug. In einer where-Klausel schließt \`amount > 9\` \`'100'\` aus, weil \`'1'\` vor \`'9'\` sortiert. Und \`id > 5\` überspringt still die ids 10 bis 99 (\`WorkflowBuilderHelpModule.java:258-262\`). Ordnungsoperatoren sind in einer where-Klausel nur dann sicher, wenn die lexikalische Ordnung zufällig der Absicht entspricht, also bei nullgepolsterten Strings oder ISO-Daten in der Form \`yyyy-MM-dd\` (\`WorkflowBuilderHelpModule.java:262\`). Es gibt keinen numerisch castenden Ordnungsoperator, nach dem man greifen könnte; ein numerisch bewusster Vergleich ist zum Zeitpunkt des Schreibens ein bekannter, aber nicht ausgelieferter Fix.

Die "hat sich der Preis um mehr als 5% bewegt"-Rechnung gehört also in Node 6b, eine \`core:decision\`, nicht in die Query. Sie braucht den vorherigen Preis, der im \`find_rows\`-Ergebnis liegt: \`find_rows\` gibt \`items[]\` zurück, und jede gematchte Zeile exponiert ihre abgeflachten Felder, sodass der Baseline-Preis bei \`items[0].price\` liegt (\`ConceptsHelpProvider.java:281\`; Array-Indizierung gemäß \`expressions.md:28-32\`). Da der gespeicherte Wert über denselben Text-Pfad wie jedes JSONB-Lesen zurückkam, muss Arithmetik ihn casten: Umschließe beide Operanden mit \`double()\`, bevor du subtrahierst. Die Bedingung:

\`\`\`
{{ (double(core:normalize.output.result.price) - double(table:baseline_lookup.output.items[0].price)) / double(table:baseline_lookup.output.items[0].price) > 0.05 }}
\`\`\`

Eine Entscheidung aktiviert genau einen Zweig. Die erste wahre Bedingung gewinnt, und der Rest wird SKIPPED. Ihre Ports sind \`if\`, \`elseif_N\` und \`else\` (\`nodes.md:29\`; \`WORKFLOW_NODE_TYPES.md:411-418\`).

Eine strukturelle Regel bindet den Graphen zusammen. Kanten sind schlichte \`{from, to}\`-Datensätze mit einem optionalen \`:port\`-Suffix, und Zweigbedingungen leben nie auf der Kante. Sie leben in der \`cores[]\`-Node, als \`decisionConditions\` oder \`switchCases\` (\`WORKFLOW_NODE_TYPES.md:33-40\`, \`:349-361\`). Zwei Konsequenzen folgen allein aus der Kantentopologie. Mehrere bedingungslose Kanten aus einer Quelle bilden einen impliziten Fork, der alle Zweige parallel ausführt. Mehrere Kanten in eine Node bilden einen impliziten AND-Merge, der wartet, bis jeder Vorgänger aufgelöst ist, ob COMPLETED oder SKIPPED (\`WORKFLOW_NODE_TYPES.md:1008-1010\`, \`:1053-1056\`, \`:925-940\`).

## Der idempotente Schreibschutz, gezeichnet als echter Teilgraph

Ein sich selbst aktualisierender Trigger feuert dasselbe Lesen stündlich. Ohne Schutz fügt er die Baseline derselben SKU jede Stunde ein, und die Tabelle füllt sich mit Duplikaten. Das allgemeine Muster, das dies auf jeder Engine behebt: erst finden, auf der Anzahl entscheiden, dann nur schreiben, wenn das Element neu ist. Füge nie bedingungslos ein, wenn dasselbe Element erneut geholt werden kann.

Diese Engine hat kein Upsert und keine Truncate-Operation, was genau der Grund ist, warum der Schutz verpflichtend statt optional ist (\`tables.md:49\`; \`CrudRepository.java\` \`deleteRows\` erfordert ein validiertes where).

| Schritt | Node | Genommener Zweig / Port | Wirkung auf die Tabelle | Belegt mit |
|---|---|---|---|---|
| 1 | \`find_rows\` per \`sku\` | (speist die Entscheidung) | liest, schreibt nichts | \`ConceptsHelpProvider.java:281\` |
| 2 | \`core:decision\` auf item_count | \`if\` (wahr) = nie gesehen | noch keine | \`WorkflowBuilderHelpModule.java:252-254\` |
| 3a | \`insert_row\` (Baseline) | auf dem \`if\`-Zweig | eine neue Zeile geschrieben | \`tables.md:52\` |
| 3b | wesentliche-Änderung-Entscheidung | auf dem \`else\`-Zweig | noch keine | \`nodes.md:29\` |
| 4 | \`update_row\` (nach Freigabe) | approved-Port | benannte JSONB-Schlüssel gemergt | \`tables.md:49\` |

Die zwei exakten Strings des Schutzes, eingezäunt, damit die Templates ganz bleiben:

\`\`\`
find_rows {column:'sku', operator:'=', value:'{{core:normalize.output.result.sku}}'}
\`\`\`

\`\`\`
{{table:baseline_lookup.output.item_count == 0}}
\`\`\`

Die Sonde, die das funktionieren lässt, ist \`find_rows\`, das \`items[]\` (die gefundenen Zeilen) und \`item_count\` (die Anzahl) exponiert. Ein \`item_count\` von 0 ist das "noch nicht verarbeitet"-Signal, das die Tabelle in geteilten Speicher über Läufe hinweg verwandelt (\`ConceptsHelpProvider.java:281\`). Der Finden-dann-Entscheiden-Schutz macht einen sich aktualisierenden Workflow sicher (\`AGENTS.md\` \`dedupe_idempotent_write\`).

Der Schreibvorgang auf dem Pfad der bekannten SKU ist ein \`update_row\`, das sowohl ein where als auch eine nicht-leere set-Map erfordert und nur die benannten JSONB-Schlüssel über \`data || jsonb_build_object\` mergt (\`tables.md:49\`). Es ist ein partieller Merge, kein Ersatz, sodass es Felder, die du auslässt, nicht auf null setzt.

Eine Mandanten-Falle wird einen Nachmittag verschwenden, wenn du sie nicht kennst. Das MCP-\`table\`-Tool läuft unter dem Mandanten des Chat-Nutzers, nicht dem des Workflow-Besitzers. Jede CRUD-Query ist mit \`AND tenant_id = :tenant_id\` eingegrenzt, sodass das Tool 0 Zeilen zeigen kann, während das eigene \`find_rows\` des Workflows die echten Daten sieht (\`AGENTS.md\`). Um eine workflow-eigene Tabelle zu inspizieren oder zu leeren, führe die Operation von innerhalb dieses Workflows aus, im korrekten Mandanten.

## Erst absichern, dann handeln

Node 7 ist die menschliche Prüfung vor dem unumkehrbaren Schritt. Das allgemeine Prinzip: Setze ein blockierendes Gate vor jede Aktion, die du nicht rückgängig machen kannst, und mache es deterministisch darin, was als Nächstes geschieht.

In dieser Engine ist das Gate ein \`USER_APPROVAL\`-Signal. Die Node liefert AWAITING_SIGNAL und der Lauf pausiert. USER_APPROVAL ist immer blockierend, anders als ein Interface-Signal, das nur blockiert, wenn \`__continue\` gemappt ist (\`EXECUTION_ENGINE.md:15\`; \`INTERFACE_NODE_GUIDE.md:783-787\`). Die Node hat drei benannte Resume-Ports, \`approved\`, \`rejected\` und \`timeout\`, und sie routet deterministisch nach der getroffenen Entscheidung (\`nodes.md:39\`; \`WorkflowHelpProvider.java:665\`). Der Standard-Timeout ist 24 Stunden, wenn nicht gesetzt (\`nodes.md:39\`).

Weil eine Aktualisierung stündlich feuert, sind zwei Fragen relevant. Erstens: Was passiert, wenn die Freigabe zweimal gefeuert wird? Nichts Schlimmes. Die Auflösung ist Claim-vor-Verarbeitung: \`resolveSignal()\` gibt bei einem bereits aufgelösten Signal false zurück, sodass eine erneut gefeuerte Freigabe den DAG nie doppelt vorantreibt (\`INTERFACE_NODE_GUIDE.md:1008\`). Zweitens: Was passiert mit dem nächsten geplanten Feuern, während ein Mensch auf der Entscheidung sitzt? Jedes Feuern öffnet eine neue Epoche, vorherige Epochen-Ergebnisse bleiben bestehen und durchsuchbar, und ein blockierendes Signal verschiebt den Trigger-Zyklus-Reset, bis es aufgelöst ist (\`EXECUTION_ENGINE.md:15\`). Die Aktualisierung trampelt nicht über eine ausstehende Entscheidung.

Auf dem \`approved\`-Port feuert die echte Aktion. Das kann eine erstklassige Send-Email-Node oder jede angebundene \`mcp:\`-Integration sein (\`nodes.md:62\`), gefolgt vom gesicherten \`update_row\`. Auf den \`rejected\`- und \`timeout\`-Ports wird nichts geschrieben und nichts gesendet.

## Beweise jeden Zweig, bevor du ihn live nennst

Die Testregel ist nicht verhandelbar: Übe jeden Zweig gegen einen live laufenden Orchestrator aus und verfolge parallel das Service-Log. Eine grüne Antwort mit einem Stacktrace im Log ist ein Fehlschlag, kein Bestehen (\`AGENTS.md\` Feature Development Flow Schritt 4). "Es gab 200 zurück" ist kein Beweis, dass der Zweig funktioniert hat.

| Szenario | Auslösebedingung | Erwarteter Zweig / Signal | Bestehen-Assertion | Fehlschlag-Signal |
|---|---|---|---|---|
| Neue-SKU-Einfügung | SKU ohne Baseline-Zeile | \`if\`-Zweig, \`insert_row\` | genau eine Zeile eingefügt | Duplikatzeile oder Stacktrace im Log |
| Keine-Änderung | Bekannte SKU, Preis innerhalb 5% | wesentliche Entscheidung \`else\` | kein Flag, keine Freigabe, kein Alert | irgendein Alert oder Pause |
| Wesentliche Änderung | Bekannte SKU, Bewegung über 5% | Lauf PAUSIERT bei AWAITING_SIGNAL | Status AWAITING_SIGNAL USER_APPROVAL | Lauf schließt ohne Pausieren ab |
| Freigabe-Ports | Jeden der drei Ports auflösen | approved / rejected / timeout | approved schreibt + alarmiert; andere tun keines | Schreibvorgang bei rejected/timeout |
| Wiederholungs-Idempotenz | Den Schedule zweimal feuern | Schutz blockiert die zweite Einfügung | Zeilenanzahl stabil | Zeilenanzahl wächst |

Führe alle fünf aus, bevor du dem Graphen vertraust. Das Szenario der wesentlichen Änderung sollte sichtbar pausieren; wenn es abschließt, liegt deine Schwellwert-Rechnung in der falschen Schicht, wahrscheinlich eine lexikografische where-Klausel, die vorgibt, numerisch zu sein.

Drei Lektionen tragen zu jeder Engine, auf der du als Nächstes baust. Ausgabeverschachtelung: Bohre bis \`{{core:normalize.output.result.sku}}\`, nie \`{{core:normalize.output.sku}}\`, weil Plattformen das verpacken, was du zurückgibst. Textueller Vergleich: Berechne die 5%-Bewegung in einer \`core:decision\`, nicht in der \`find_rows\`-where-Klausel, weil dieser Vergleich lexikalisch ist. Stringifizierte Objekte: Baue typisierte Werte in einer \`core:code\`-Node, nicht in einer \`core:transform\`, die sie zu Strings abflacht. Und der Finden-dann-Entscheiden-Schutz ist das Muster, das einen sich selbst aktualisierenden Workflow überall sicher macht, denn ein Schedule, der handelt, ist nur so vertrauenswürdig wie seine Verteidigung dagegen, sich selbst zu wiederholen.
`;

export default content;
