// workflow-beats-do-everything-agent - de
// Translated from the English body; keep the structure identical to it
// (10 h2, 6 tables, 5 fenced formula blocks, 21 source links). The formulas are
// fenced on purpose: an inline formula over ~45 chars overflows the page on a phone.
const content = `## Die Zahl, die ich gelöscht habe

Eine frühere Version dieses Artikels behauptete, ein eng abgegrenzter Workflow laufe "etwa zehnmal günstiger" als ein Alleskönner-Agent. Diese Zahl hatte keine Herleitung, keine Annahmen und keine Quelle hinter sich, also ist sie weg.

Es gibt keine veröffentlichte Quelle, mit der sie sich ersetzen ließe. Kein Anbieter-Benchmark, kein Paper und kein Trace misst dieselbe Aufgabe, einmal als abgegrenzte Pipeline und einmal als autonomer Agent implementiert, mit Kosten und Erfolgsquote auf beiden Seiten instrumentiert. Der kanonische Beitrag dieser Kategorie, Anthropics [Building Effective Agents](https://www.anthropic.com/engineering/building-effective-agents), enthält null Kostenangaben; die Behandlung des Themas umfasst zwei Sätze: "Agentic systems often trade latency and cost for better task performance, and you should consider when this tradeoff makes sense" und "The autonomous nature of agents means higher costs, and the potential for compounding errors." Der zweite Satz behauptet die These dieses Artikels, ohne eine Zahl daran zu hängen.

Die kursierenden Multiplikatoren sind nicht austauschbar. "3-10x" kursiert als Aussage über die Anzahl der LLM-Aufrufe und "5-30x" als Aussage über Tokens pro Aufgabe, keiner von beiden mit nachvollziehbarer Primärquelle. Die einzige Zahl mit sichtbaren Annahmen, [12,4x aus einem dev.to-Beitrag](https://dev.to/awxglobal/why-your-llm-agent-costs-10x-more-than-your-estimate-4o78), ist aus einem 800-Token-System-Prompt aufgebaut, der bei jedem Aufruf erneut gesendet wird, 4 Runden pro Anfrage und 3,5 Tool-Aufrufen mit je 250 Tokens Overhead, gemessen gegen eine Baseline, die nur den Prompt des Nutzers und die Antwort des Modells zählt. Die 12,4x sind daher eine Aussage über das Verhältnis von Prompt- und Tool-Overhead bei fester Rundenzahl, nicht über eine gesamte Aufgabe; ändert man die Rundenzahl, verschiebt sich das Vielfache. Das ist die eine prüfbare Herleitung des Genres, und die Prüfung zeigt, dass sie nicht misst, was die anderen beiden Spannen messen. Die Framework-Beiträge, die tatsächlich Architekturen vergleichen ([Sashido](https://www.sashido.io/en/blog/agentic-workflows-roi-without-expensive-agents), [LindleyLabs](https://lindleylabs.com/blog/agent-or-pipeline-a-decision-framework-for-ai-engineers), [Retool](https://retool.com/resources/ai-workflows-vs-agents)), drucken Formeln und Entscheidungsbäume ohne belegte Variablen.

Es gibt in diesem Feld eine echte, belegte 10x, und sie ist nicht die Behauptung, die ich gelöscht habe: Anthropics [Cache-Read-Multiplikator beträgt exakt 0,1x des Basis-Inputs](https://platform.claude.com/docs/en/about-claude/pricing), ein gecachter Input-Token ist also genau zehnmal günstiger als ein ungecachter. Das gilt für die Cached-Prefix-Komponente der Input-Tokens, mehr nicht.

Regel für den Rest dieses Texts: Jedes hier gedruckte Verhältnis ist auf der Seite selbst aus genannten Annahmen hergeleitet. Keines ist zitiert.

## Wohin das Geld fließt: zwei Kostenfunktionen, eine davon quadratisch

Zuerst der Mechanismus, damit jede spätere Zahl durch Nachrechnen widerlegbar ist.

Die Messages API ist zustandslos. Der Aufruf \`i\` eines Agenten trägt daher \`In_i = B + (i-1)g\`, wobei \`B = S + T + P0\` der System-Prompt, die Tool-Definitionen und die initiale Nutzlast sind und \`g = a + r\` das Wachstum pro Runde ist (Assistant-Output plus das zurückgehängte Tool-Ergebnis). Summiert über N Aufrufe:

\`\`\`
I(N) = N*B + g*N*(N-1)/2
\`\`\`

Der erste Term ist die Prefix-Steuer, linear in N. Der zweite ist die Akkumulations-Steuer, quadratisch in N. Ein in Runde \`i\` erzeugter Token wird noch \`(N - i)\` weitere Male als Input gelesen.

Die Kosten des abgegrenzten Workflows lauten:

\`\`\`
C_wf = sum over k of [ p_in^k*(s_k + t_k + d_k) + p_out^k*o_k ]
\`\`\`

Linear in K, weil Schritt k seine deklarierten Eingaben \`d_k\` erhält und niemals das Transkript der Schritte 1 bis k-1. Man beachte das \`^k\` am Preis: Ein Workflow kann jeden Schritt ohne Zusatzkosten an ein anderes Modell routen. Ein Agent mit einer einzigen Schleife zahlt ein vollständiges Cache-Neuschreiben seines akkumulierten Prefix, sobald er mitten im Gespräch das Modell wechselt, und legt sich in der Praxis daher für die gesamte Schleife auf ein Modell fest. Routing pro Aufruf innerhalb einer Agent-Architektur erfordert eine Sub-Agent-Grenze, die selbst eine Scoping-Entscheidung ist und pro Sub-Agent ein frisches Prefix kostet.

Die Zerlegungsschranke ist exakt und nicht rhetorisch. Zerlegt man eine Schleife mit N Aufrufen in K abgegrenzte Segmente, teilt das den Akkumulationsterm exakt durch K, denn \`K * g*(N/K)^2/2 = g*N^2/(2K)\`. Eine dreistufige Aufteilung deckelt die Akkumulationsersparnis bei 3x. Jede behauptete 10x aus einem dreistufigen Workflow stammt aus der Prefix-Steuer, der Nutzlastbreite oder dem Modell-Routing, nicht daraus, dass die Quadratik gebrochen wurde.

Tool-Definitionen sind ein realer Bestandteil von B. Anthropic berichtet, dass ein Setup mit fünf MCP-Servern (GitHub, Slack, Sentry, Grafana, Splunk) [rund 55.000 Tokens an Definitionen verbraucht](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool), bevor das Modell überhaupt zu arbeiten beginnt. Zum Listenpreis sind das $0.275 pro ungecachtem Aufruf auf Opus 4.8, und diese Zahl hält die Token-Anzahl über die am Ende dieses Texts beschriebene Tokenizer-Grenze hinweg konstant, betrachten Sie sie also als Untergrenze und nicht als Schätzung.

## Das durchgerechnete Beispiel: Support-Triage, jede Annahme ausgedruckt

Die Aufgabe: ein Ticket klassifizieren, das Konto nachschlagen, die Wissensdatenbank durchsuchen, eine Antwort entwerfen, sie prüfen.

| Parameter | Symbol | Wert Agent | Wert Workflow | Woher er stammt |
|---|---|---|---|---|
| System-Prompt | S | 1.500 Tok | 250-600 Tok pro Schritt | Annahme: ein Alleskönner-Prompt gegen vier enge |
| Tool-Definitionen | T | 6 Tools, 900 Tok | 30 Tok pro Schritt, 120 Tok gesamt | Annahme; kein Anbieter veröffentlicht eine Zahl pro Tool |
| Initiale Nutzlast | P0 | 600 Tok | 600 Tok (Ticket-Text) | Dasselbe Ticket auf beiden Seiten |
| Prefix | B = S+T+P0 | 3.000 Tok | n/a (pro Schritt) | Summe der obigen Werte |
| Wachstum pro Runde | g = a + r | 1.000 Tok | 0 (kein Transkript mitgeführt) | a=300 Output, r=700 Tool-Ergebnis |
| Aufrufe / Schritte | N / K | 8 Aufrufe | 4 LLM-Schritte + 2 deterministische | Einschätzung dessen, was die Aufgabe braucht |
| Preis | p_in / p_out | $3.00 / $15.00 pro MTok | gleich | [Sonnet 4.6 Listenpreis](https://platform.claude.com/docs/en/about-claude/pricing), verifiziert 2026-07-22 |

Jede dieser Token-Zahlen ist eine genannte Annahme, keine Messung. Keine stammt von einem Token-Zähl-Endpunkt.

**Agent-Aufstellung, N=8.** Der Input wächst pro Aufruf um exakt g = 1.000, die Tabelle ist also eine arithmetische Folge von 3.000 bis 10.000; nur die Endpunkte und die zweite Zeile sind informativ.

| Aufruf | Input-Tokens | Kumulierter Input | Output-Tokens | Laufende Kosten |
|---|---|---|---|---|
| 1 | 3.000 | 3.000 | 300 | $0.0135 |
| 2 | 4.000 | 7.000 | 300 | $0.0300 |
| ... | jeweils +1.000 | | 300 | |
| 8 | 10.000 | 52.000 | 300 | $0.1920 |

Die Gesamtsumme von 52.000 stimmt mit der geschlossenen Form überein: \`8*3,000 + 1,000*(8*7/2) = 24,000 + 28,000\`. Kosten: 52.000 x $3/MTok = $0.156 Input, plus 2.400 x $15/MTok = $0.036 Output. **$0.192 pro Ticket.**

**Workflow-Aufstellung, dieselbe Aufgabe.** Die Spalten für Prefix und Nutzlast sind getrennt ausgewiesen, denn aus dieser Trennung ergeben sich die beiden unten genannten Hebel.

| Schritt | LLM? | Modell | System | Tool-Defs | Deklarierte Daten | Input | Output | Schrittkosten |
|---|---|---|---|---|---|---|---|---|
| Klassifizieren | ja | Sonnet 4.6 | 250 | 30 | 600 | 880 | 60 | $0.00354 |
| Konto nachschlagen | nein | (deterministisch) | 0 | 0 | 0 | 0 | 0 | $0 |
| Wissensdatenbank-Abruf | nein | (deterministisch) | 0 | 0 | 0 | 0 | 0 | $0 |
| Suchanfrage bauen | ja | Sonnet 4.6 | 250 | 30 | 70 | 350 | 25 | $0.00143 |
| Entwurf | ja | Sonnet 4.6 | 600 | 30 | 1.810 | 2.440 | 400 | $0.01332 |
| Prüfung | ja | Sonnet 4.6 | 600 | 30 | 450 | 1.080 | 80 | $0.00444 |
| **Gesamt** | | | **1.700** | **120** | **2.930** | **4.750** | **565** | **$0.02273** |

Die deklarierten Daten des Entwurfsschritts sind Ticket 600 + Label 60 + Kontofakten 250 + die 3 besten Auszüge aus der Wissensdatenbank 900 = 1.810.

Diese Aufstellung bepreist ausschließlich Modell-Tokens. Im gehosteten Produkt trägt ein terminaler Workflow-Knoten zusätzlich pauschal 1 Credit ($0.001), was für diese 6 Knoten $0.006 hinzufügt und den Workflow auf $0.0287 hebt. Jedes Verhältnis unten ist der reine Token-Vergleich; die Variante der Kennzahl mit gehosteten Kosten wird dort genannt, wo sie zuerst ins Gewicht fällt.

**Kennzahl für diese Konfiguration: 8,4x** ($0.192 / $0.02273), oder 6,7x, sobald die gehostete Gebühr pro Knoten einbezogen wird. Das Verhältnis allein bei den Input-Tokens beträgt 10,9x (52.000 / 4.750). Das Verhältnis bei den Output-Tokens liegt nur bei 4,2x (2.400 / 565), und genau das zieht den gemischten Wert unter 10x: Beide Architekturen müssen tatsächlich dieselbe Antwort von rund 400 Tokens schreiben.

Zwei Hebel überleben das Caching, sind durch es aber geschrumpft. Die **Prefix-Steuer**: ungecacht sendet der Agent sein 3.000-Token-Alleskönner-Prefix achtmal (24.000 Tokens) gegen die insgesamt 1.820 Tokens an System-Prompts und Tool-Definitionen des Workflows, 13,2x. Mit inkrementellem Caching fällt die Prefix-Komponente des Agenten auf \`1.25B + 0.1(N-1)B\` = 5.850 effektive Tokens, und der Hebel sinkt auf 3,2x. Die **Nutzlastbreite**, auf Momentaufnahme-Basis: Bei seinem letzten Aufruf enthält der Input des Agenten 7.600 Tokens an akkumulierter Nutzlast und Tool-Transkript (600 initial plus 7 x 1.000 Wachstum) gegen den breitesten einzelnen Schritt des Workflows, dessen deklarierter Input 1.810 Tokens umfasst, 4,2x. Dieser Vergleich wechselt die Rechnungsgrundlage, sobald Caching aktiv ist, denn die älteren Deltas des Agenten werden zu 0,1x erneut gelesen: Auf kumulierter Basis effektiver Tokens kostet das Nutzlastwachstum des Agenten \`1.25*1,000*7 + 0.1*1,000*21\` = 10.850 Tokens gegen die 2.930 Tokens deklarierter Daten des Workflows, 3,7x. Der Mechanismus hinter beidem ist, dass eine deklarierte Eingabe eine Projektion über die Rohbeobachtung ist.

## Das Verhältnis ist eine Funktion von N, und N ist das ganze Argument

Unter den Annahmen des Beispiels betragen die Agent-Kosten \`0.0015N^2 + 0.012N\` Dollar. Probe bei N=8: $0.096 + $0.096 = $0.192.

| N (Agent-Aufrufe) | Agent-Kosten | Workflow-Kosten | Verhältnis | Wofür dieses N steht |
|---|---|---|---|---|
| 2 | $0.030 | $0.0227 | 1,3x | Kurzschluss: "Spam, eskalieren" |
| 4 | $0.072 | $0.0227 | 3,2x | Minimale Tool-Nutzung, keine Wiederholungen |
| 6 | $0.126 | $0.0227 | 5,5x | Eine Abfrage wiederholt |
| 8 | $0.192 | $0.0227 | 8,4x | Das durchgerechnete Beispiel |
| 12 | $0.360 | $0.0227 | 15,8x | Agent erkundet die Wissensdatenbank |
| 20 | $0.840 | $0.0227 | 37,0x | Abschweifen, oder ein wirklich schwieriges Ticket |

Löst man \`0.0015N^2 + 0.012N = 0.022725R\`: Das Verhältnis erreicht 10 bei N = 8,94 Aufrufen und 3 bei N = 3,84 Aufrufen. Ein Kostenverhältnis ohne Angabe von N zu nennen, ist bedeutungslos.

Eine Fairness-Bedingung zu dieser Tabelle: Die Zeilen mit hohem N sind nur dann legitim, wenn die Aufgabe wirklich so viele Aufrufe braucht. Ein Agent, der 20 Aufrufe für das braucht, was ein Workflow in 4 erledigt, schweift ab, und das ist ein Befund über die Leistungsfähigkeit, der als solcher begründet werden muss und nicht in eine Kostentabelle geschmuggelt werden darf.

## Den Agenten sauber cachen, dann vergleichen

Veröffentlichte Verhältnisse von Workflow zu Agent vergleichen in der Regel gegen einen ungecachten Agenten. Im Caching versickert der größte Teil des Abstands, bepreisen Sie es also vor dem Vergleich.

Anthropics [Caching-Multiplikatoren](https://platform.claude.com/docs/en/build-with-claude/prompt-caching) sind exakt: 5-Minuten-Cache-Write = 1,25x Basis-Input, 1-Stunden-Write = 2x, Cache-Read = 0,1x. Auch der Break-even ist veröffentlicht: Der 5-Minuten-Cache rechnet sich nach einem einzigen Read (1,25 + 0,1 = 1,35x gegen 2x ungecacht); der 1-Stunden-Cache braucht zwei Reads (2 + 0,2 = 2,2x gegen 3x).

Mit inkrementellem Multi-Turn-Caching wird der effektive Input des Agenten zu:

\`\`\`
1.25B + 0.1(N-1)B + 0.1g(N-2)(N-1)/2 + 1.25g(N-1)
\`\`\`

Der quadratische Koeffizient fällt von \`p_in*g/2\` auf \`0.1*p_in*g/2\`, ein exakter 10x-Rabatt auf genau den Term, den der Workflow geschlagen hat.

Bei N=8 beträgt der effektive Input pro Aufruf 3.750 / 1.550 / 1.650 / 1.750 / 1.850 / 1.950 / 2.050 / 2.150 = 16.700 Tokens gegen 52.000 ungecacht. Das sind $0.0501 Input plus $0.036 Output = **$0.0861**. Caching senkt die Agent-Kosten um 55% und lässt die Kennzahl von 8,4x auf **3,8x** einbrechen.

Man beachte, was bei aktivem Caching dominiert: der 1,25x-Write des Deltas jeder Runde, \`1.25 * 1,000 * 7 = 8,750\` der 16.700 effektiven Tokens, 52%. Der verbleibende Workflow-Vorteil liegt in der Prefix-Steuer und der Nutzlastbreite, nicht in der Quadratik des erneuten Sendens.

Caching ebnet den Abstand ein, ohne ihn zu schließen. Bei N=20 kostet der gecachte Agent $0.241 gegen $0.840 ungecacht, immer noch das 10,6-Fache des Workflows, denn 19 Cache-Writes zu 1,25x plus 20 Runden generierter Inhalte sind nicht reduzierbar.

Der Workflow schöpft hier fast nichts vom selben Vorteil ab. Das minimale cachefähige Prefix auf Sonnet 4.6 beträgt 1.024 Tokens (gegen die Caching-Dokumentation verifiziert am 2026-07-22; es sind 512 bei Fable 5 und Mythos 5, 2.048 bei Opus 4.7 sowie 4.096 bei Opus 4.6, Opus 4.5 und Haiku 4.5). Das stabile Prefix jedes Workflow-Schritts besteht hier aus System-Prompt plus Tool-Definitionen, 280 bis 630 Tokens, bei jedem dieser Modelle unterhalb der Schwelle. Prefixe unterhalb des Minimums scheitern lautlos: Es wird kein Fehler zurückgegeben, und sowohl \`cache_creation_input_tokens\` als auch \`cache_read_input_tokens\` stehen auf 0. Beachten Sie, dass das Routing eines Schritts auf Haiku 4.5 dessen Schwelle auf 4.096 anhebt, die unten beschriebene geroutete Konfiguration ist also weiter von der Cachefähigkeit entfernt, nicht näher dran.

Die umsetzbare Korrektur hat einen veröffentlichten Break-even. Konsolidieren Sie das Prefix eines Schritts mit hohem Volumen oberhalb des cachefähigen Minimums für das Modell, auf dem er läuft, und setzen Sie den Breakpoint dahinter, sodass jeder Lauf dieses Schritts zu 0,1x liest. Bei der 5-Minuten-TTL rechnet sich das ab der zweiten Anfrage, und da [Cache-Reads die TTL kostenlos auffrischen](https://platform.claude.com/docs/en/build-with-claude/prompt-caching), bleibt ein Schritt, der mindestens alle fünf Minuten getroffen wird, unbegrenzt warm zum Write-Preis.

Eines leistet Caching nicht: Gecachte Tokens [belegen weiterhin das Kontextfenster](https://platform.claude.com/docs/en/build-with-claude/context-windows). Es ändert, was Sie für diese Tokens zahlen, nicht ob sie zählen. Es rettet niemanden vor der Erschöpfung des Kontexts oder vor Context Rot.

## Das Vier-Felder-Raster, und wo eine echte 10x tatsächlich wohnt

Routet man Klassifizierung, Suchanfrage und Prüfung auf Haiku 4.5 ($1/$5) und nur den Entwurf auf Sonnet 4.6, sinkt der Workflow auf $0.0165 pro Ticket (Klassifizierung $0.00118 + Suchanfrage $0.000475 + Entwurf $0.01332 + Prüfung $0.00148).

| | Workflow, gleiches Modell ($0.0227) | Workflow, geroutet ($0.0165) |
|---|---|---|
| **Agent, ungecacht ($0.192)** | 8,4x | 11,7x |
| **Agent, gecacht ($0.0861)** | 3,8x | 5,2x |

Meine Voreinstellung ist die obere rechte Zelle umgedreht: den Agenten cachen, den Workflow routen und mit 5,2x rechnen. Unterhalb von etwa N=4 würde ich den Workflow gar nicht erst bauen, weil das Verhältnis unter 3x liegt und die Baukosten sich nicht amortisieren (siehe den Schlussabschnitt); oberhalb von etwa N=12 trifft die Quadratik die Entscheidung für Sie.

Ein Agent mit einer einzigen Schleife muss sein eines festgelegtes Modell bei jedem Aufruf laufen lassen. Ein Opus-4.8-Agent ($5/$25) ist kein Eins-zu-eins-Tausch derselben Token-Zahlen, denn Opus 4.7 und neuere verwenden einen neueren Tokenizer, der für denselben Text etwa 30% mehr Tokens erzeugt. Wendet man diesen Aufschlag an: rund 67.600 Input und 3.120 Output, also $0.338 + $0.078 = $0.416, gegen die $0.0165 des gerouteten Workflows, also 25,3x. Das ist ein Routing-Argument, kein Kontextfenster-Argument.

Die pauschale Behauptung allein aus Kontextdisziplin, hergeleitet: Mit gecachtem Agenten und beiden Architekturen auf einem Modell liegt das Verhältnis bei 2,8x bei N=6, 3,8x bei N=8 und 5,8x bei N=12. Also grob 3x bis 6x über eine plausible N-Spanne, und alles darüber ist eine Caching- oder Routing-Entscheidung, die als solche benannt werden muss.

Die Preisstruktur der Anbieter macht Routing vorhersagbar. Jedes aktuelle Anthropic-Modell bepreist Output mit exakt dem 5-Fachen des Inputs (Opus 4.8 $5/$25, Sonnet 4.6 $3/$15, Haiku 4.5 $1/$5). Jedes aktuelle OpenAI-Modell bepreist Output mit dem 6-Fachen des Inputs (gpt-5.6-sol $5.00/$30.00, gpt-5.4 $2.50/$15.00, gpt-5.4-mini $0.75/$4.50), mit gpt-5.4-nano als einziger Ausnahme bei 6,25x ($0.20/$1.25). [DeepSeek](https://api-docs.deepseek.com/quick_start/pricing) bepreist Output mit exakt dem Doppelten des Cache-Miss-Inputs (deepseek-v4-flash $0.14/$0.28, deepseek-v4-pro $0.435/$0.87). Innerhalb eines Anbieters bestimmt das Verhältnis von Input zu Output das Kostenprofil stärker als die Modellwahl. Und die Batch-Stufe ist ein fünfter, ausschließlich dem Workflow zugänglicher Hebel für Schritte ohne Latenzanforderung: pauschal 50% Rabatt auf Input und Output bei Anthropic und OpenAI, der halbe Standardtarif bei Gemini, bei Anthropic kombinierbar mit den Caching-Multiplikatoren.

## Wo dieses Modell bricht

| Bedingung | Wirkung auf das Verhältnis | Größenordnung hier | Warum es passiert |
|---|---|---|---|
| Kurze Aufgaben (N<4) | Bricht ein, kann sich umkehren | 1,3x bei N=2 | Agent kürzt ab; Workflow läuft immer seinen festen Pfad |
| Output-dominierte Aufgabe | Nähert sich 1 | 2,2x für einen 5.000-Wörter-Bericht bei N=8 | Beide Architekturen schreiben dasselbe Ergebnis |
| Großer geteilter Kontext | Kann sich umkehren | 5D gegen 1,95D bei einem 50k-Dokument | Workflow sendet pro Schritt erneut, sofern er das Dokument nicht vorher cacht |
| Parallelisierbare Breitensuche in der Recherche | Begünstigt Multi-Agent | +90,2% in einer Anbieter-Evaluation | Autonomie erkauft Abdeckung, die die Pipeline nicht aufzählen kann |
| Tool-Suche (verzögertes Laden) | Verkleinert den Prefix-Vorteil | Anbieterangabe >85% weniger Definitionen | Agent schöpft die Prefix-Ersparnis ohne Neuarchitektur ab |
| Toolset > 30-50 Tools | Begünstigt Workflow, bei der Korrektheit | nicht bepreist | Trefferquote der Tool-Auswahl verschlechtert sich |
| Modellabhängiger Tool-Aufschlag | Verschiebt B | 290 bis 804 Tok je nach Modell | Feste System-Prompt-Kosten vor jedem Schema |
| Serverseitige Tool-Gebühren | Außerhalb des Token-Modells | $10 pro 1.000 Websuchen | Abrechnung pro Aufruf, nicht pro Token |
| Tokenizer-Wechsel / Preisrückkehr | Entwertet datierte Zahlen | ~30% mehr Tokens; $2/$10 auf $3/$15 | Neuer Tokenizer ab Opus 4.7; Einführungspreis für Sonnet 5 endet am 31. Aug. 2026 |

Vier davon verdienen eine Ausführung.

**Output-dominierte Aufgaben.** Ein Bericht von 5.000 Wörtern sind rund 6.700 Output-Tokens, $0.1005 zu $15/MTok auf beiden Seiten. Hält man die Inputs des Beispiels fest (Agent $0.156, Workflow $0.01425), beträgt das Verhältnis $0.2565 / $0.1145 = 2,2x, und es fällt weiter, je größer das Ergebnis wird.

**Großer geteilter Kontext** ist der Umkehrfall. Braucht jeder Schritt dasselbe Dokument mit 50k Tokens, sendet ein fünfstufiger Workflow es fünfmal erneut (5D), während ein gecachter Agent mit acht Aufrufen 1,25D + 7 x 0,1D = 1,95D zahlt. Der Workflow gewinnt nur, wenn er das Dokument voranstellt, vor die schrittspezifische Anweisung, und es cacht (1,25D + 4 x 0,1D = 1,65D).

**Parallelisierbare Recherche ist der stärkste veröffentlichte Fall gegen diese gesamte These.** Anthropic berichtet, dass ein Multi-Agent-System, ein Claude Opus 4 als Leitinstanz, das Claude Sonnet 4 Subagenten orchestriert, [den Einzelagenten Claude Opus 4 um 90,2% übertraf](https://www.anthropic.com/engineering/multi-agent-research-system) in ihrer internen Recherche-Evaluation, und im selben Beitrag, dass Agenten rund das 4-Fache der Tokens einer Chat-Interaktion verbrauchen, während Multi-Agent-Systeme rund das 15-Fache verbrauchen. Das ist Autonomie, die einen großen Qualitätsgewinn zu einem großen Kostenvielfachen erkauft, und es ist zugleich eine Agent-Architektur, die Modell-Routing pro Schritt über eine Sub-Agent-Grenze hinweg betreibt. Anthropics eigene Vorbedingung ist die ehrliche Einordnung: Es lohnt sich nur, wenn der Wert der Aufgabe den Multiplikator deckt, und es passt schlecht dorthin, wo alle Agenten denselben Kontext benötigen oder die Arbeit viele Abhängigkeiten hat.

**Tool-Suche** ist speziell gegen das Prefix-Steuer-Argument der stärkste Einwand. Anthropic gibt an, verzögertes Laden von Tools [reduziere den Tool-Definitions-Kontext typischerweise um über 85%](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool), da nur die 3-5 benötigten Tools geladen werden, was einem Alleskönner-Agenten erlaubt, einen Großteil der Prefix-Ersparnis abzuschöpfen, ohne neu architektiert zu werden. Das ist eine Anbieterangabe ohne offengelegte Methodik und sollte auch als solche behandelt werden. Anthropics eigener Auslöser: Tool-Suche ab 10 oder mehr Tools einsetzen, oder wenn die Definitionen 10k Tokens überschreiten. Dieselbe Seite gibt an, dass die Trefferquote der Tool-Auswahl abnimmt, sobald man 30-50 verfügbare Tools überschreitet, was dem Scoping-Argument ein Zuverlässigkeitsstandbein gibt, das überhaupt nicht von der Token-Rechnung abhängt.

## Kosten pro erfolgreichem Lauf, und die Bedingung, die das Argument umkehrt

Der Vergleich, auf den es wirklich ankommt, ist \`C/q\`: Kosten geteilt durch die Erfolgsquote der jeweiligen Architektur. Eine Wiederholungsrate von 20% beim Workflow multipliziert dessen Kosten mit 1,2 und senkt die Kennzahl von 8,4x auf 7,0x. Ein Agent, der sich stattdessen im Kontext selbst fängt, zahlt ein paar zusätzliche, quadratisch bepreiste Runden.

Die Konsistenz über wiederholte Läufe bricht schneller ein als die Kennzahl zur Genauigkeit. Im ursprünglichen Paper zu [tau-bench retail](https://arxiv.org/abs/2406.12045) von 2024 waren die damals besten Function-Calling-Agenten so inkonsistent, dass pass^8 unter 25% fiel. Produktion bedeutet, dieselbe Aufgabe viele Male auszuführen, also ist pass^k die richtige Metrik, nicht pass@1, und dieser strukturelle Punkt trägt hier, nicht irgendein absoluter Wert aus 2024.

Der Erfolg zerfällt außerdem mit der Aufgabenlänge auf eine Weise, die Scope-Reduktion in der Zuverlässigkeit superlinear macht. [Toby Ords Halbwertszeitmodell](https://www.tobyord.com/writing/half-life) sagt voraus, dass der Horizont für 80% Erfolg etwa 1/3 des 50%-Horizonts beträgt, 90% etwa 1/7 und 99% etwa 1/70; der Autor stellt ausdrücklich klar, dass das Modell an eine einzige Aufgabensammlung angepasst ist und seine Allgemeingültigkeit unbekannt ist. [METRs Messungen](https://arxiv.org/abs/2503.14499) zeigen 80%-Zeithorizonte, die rund 5-mal kürzer sind als die 50%-Horizonte, also steiler als die 3x des Halbwertszeitmodells, sodass die beiden den Effekt eher einklammern, als einander zu bestätigen. Und das Versagen ist strukturell, nicht bloß ein niedrigerer Wert: Die [HORIZON-Studie](https://arxiv.org/html/2604.11978v1) mit über 3.100 Trajektorien schreibt 72,5% der Fehlschläge prozessbezogenen Ursachen zu (Umgebungsfehler, Anweisungsfehler, Planungsfehler, akkumulierte Historie) und berichtet einen abrupten Übergang von teilweiser Robustheit zu nahezu systematischem Versagen. Dieselbe Studie argumentiert, dass Zerlegung allein nicht die Lösung ist: Sie fordert hierarchische Planung und Verifikation zur Ausführungszeit, nicht bloß das Aufteilen der Aufgabe.

Das stärkste Gegenmodell ist das von [Zartis](https://www.zartis.com/ai-agent-cost-optimisation-why-token-cost-is-the-wrong-number-to-optimise/):

\`\`\`
total_cost_per_task = (token_cost + infrastructure_cost) / reliability_rate
                      + failure_rate * human_remediation_cost
\`\`\`

Ihr durchgerechnetes Beispiel macht eine pro Aufruf 5-mal teurere Architektur ($0.05 gegen $0.01) insgesamt 5,7-mal günstiger (~$8,835/Tag gegen ~$50,100/Tag), sobald die Zuverlässigkeit von 70% auf 95% steigt. Ihre Token-Zahlen, Stundensätze und Behebungsminuten sind die genannten Annahmen jenes Artikels, keine Messungen, und ihre beiden Architekturen unterscheiden sich in der Kontextbreite, nicht in der Autonomie. Die Struktur des Arguments hält dennoch.

Lösen wir es mit diesen Zahlen. Workflow $0.0227, gecachter Agent $0.0861, Differenz $0.0634 pro Ticket. Kostet ein Fehlschlag \`H\` an menschlicher Nachbearbeitung und übertrifft die Erfolgsquote des Agenten die des Workflows um \`dq\`, gewinnt der Agent, wenn \`dq * H > 0.0634\`. Bei einem Analysten für $100/Stunde und 10 Minuten pro Nachbearbeitung ist H = $16.67, ein Erfolgsquoten-Vorsprung von 0,38 Prozentpunkten genügt also. Bei 5 Minuten und $80/Stunde ist H = $6.67 und die Schwelle liegt bei 0,95 Punkten. Sagen wir es unumwunden: **Bei jeder nicht trivialen Rate menschlicher Nachbearbeitung hört das Token-Verhältnis auf, der entscheidende Term zu sein.** Die 3,8x, auf denen dieser Break-even aufgebaut ist, sind ein Rundungsfehler gegen einen Unterschied von einem Punkt in der Erfolgsquote, und selbst die ungecachten 8,4x brauchen nur 1,02 Punkte Vorsprung, um zu kippen (Differenz $0.1693 gegen H = $16.67). Das schneidet in beide Richtungen, und es ist der Grund, warum das Zuverlässigkeitsargument für Scoping (kürzere Horizonte, weniger Tools, verifizierte Verträge zwischen den Schritten) schwerer wiegt als das Kostenargument, das dieser ganze Artikel gerade hergeleitet hat.

Mehr auszugeben kauft auch keine Genauigkeit. Auf [GAIA](https://hal.cs.princeton.edu/gaia) kostete ein Agent mit o3 Medium $2,828.54 für 28,48% Genauigkeit, während Gemini 2.0 Flash $7.80 für 32,73% kostete. Im selben [Evaluationsprogramm](https://arxiv.org/abs/2510.11977) senkte höherer Reasoning-Aufwand die Genauigkeit in der Mehrheit der 36 getesteten Modell-Benchmark-Kombinationen.

## N ist ein Ergebnis, kein Eingabewert

Alles Obige behandelt N als Parameter. In der Produktion ist es emergent, und deshalb hat das Verhältnis einen langen Ausläufer.

Der Ausläufer ist kein allmählicher Anstieg, sondern eine Sprungfunktion, und genau das macht ihn schwer abzusichern. Ein Produktionsvorfall hat diese Form dokumentiert: Ein Lauf tuckerte mehrere Iterationen lang mit jeweils rund 70k Prompt- und 700 Completion-Tokens dahin, billig genug, dass eine auf Durchschnitten beruhende Projektion die nächste Iteration weiter durchwinkte, und dann schoss eine einzelne Iteration auf etwa 2M Tokens hoch. Ein gleitender Durchschnitt verdünnt genau das.

Die Schranke, die es abfängt, mittelt überhaupt nicht:

\`\`\`
projectedNext       = max(growth projection, last delta x 2, worstCaseSingleIter)
worstCaseSingleIter = cost(full context window, full max output)
\`\`\`

Dieser zweite Term ist invariant gegenüber dem Wachstumsmuster, und darin liegt der ganze Sinn. Bepreist auf einer Opus-Klasse-Zeile mit 200k Kontext und 64k maximalem Output zu $15/$75 pro MTok, kostet eine Worst-Case-Iteration 200.000 x $15/MTok + 64.000 x $75/MTok = $3.00 + $4.80 = $7.80. Eine einzelne Iteration dieses Modells kann plausibel mehr kosten als ein kleines Kontoguthaben, also löst die Schranke schon bei der ersten Iteration der Tuckerphase aus, statt auf den Durchschnitt zu wetten.

Die Kostenschätzung irrt aus demselben Grund ins Teure statt ins Billige: Ein Modell ohne Preiszeile fällt auf $15/$75 pro MTok zurück, die höchste Stufe im Snapshot, weil ein früherer Fallback nahe null die Budget-Schranke lautlos komplett umging.

Das Ende eines Laufs muss klassifiziert werden, bevor sich Kosten pro Erfolg überhaupt berechnen lassen. Eine Produktionstaxonomie zählt exakt 10 Stop-Gründe in 3 terminalen Kategorien auf: Erfolg (COMPLETED); teilweise (MAX_ITERATIONS, TIMEOUT, BUDGET_EXHAUSTED, LOOP_DETECTED, STOPPED_BY_USER), definiert als "sauber beendet, aber die Aufgabe nicht wie geplant abgeschlossen, Ausgabe ist brauchbar, aber abgeschnitten oder vorzeitig"; und Fehlschlag (CANCELLED, NO_TOOLS, ERROR, INACTIVITY_TIMEOUT). TIMEOUT und INACTIVITY_TIMEOUT landen bewusst in verschiedenen Kategorien: über ein Wanduhr-Budget hinaus zu arbeiten ist teilweise, innerhalb des Watchdog-Fensters weder Token noch Thinking, Tool-Aufruf oder Tool-Ergebnis zu produzieren ist ein Fehlschlag.

Der deterministische Schritt als Anker macht den Vergleich greifbar. Ein terminaler Workflow-Knoten kostet, ob abgeschlossen oder fehlgeschlagen, pauschal 1 Credit ($0.001) im gehosteten Produkt; nur übersprungene Knoten sind kostenlos, und die selbst gehostete Variante schreibt für die Beobachtbarkeit dieselbe 1-Credit-Zeile ins Ledger, zieht sie aber nie ab, weil das Guthaben unbegrenzt ist. Zum Listenpreis von Sonnet 4.6 mit $3/MTok entspricht ein Credit 333 Tokens Input zum Listenpreis; im gehosteten Produkt macht die 1,8-fache Cloud-LLM-Marge daraus etwa 185 Tokens, sodass jeder Prompt oberhalb von grob 186 Tokens mehr kostet als ein ganzer deterministischer Schritt. Nur 4 von rund 60 Knotentypen der Palette (Agent, Classify, Guardrail, Browser Agent) rufen überhaupt ein LLM auf.

## So rechnen Sie das mit Ihren eigenen Zahlen durch

1. **Messen Sie die Tokens.** Jede Zahl im obigen Beispiel ist eine Annahme. Ersetzen Sie sie durch den Token-Zähl-Endpunkt des Anbieters, angewandt auf echten Ticket-Text, echte Tool-Schemata und einen echten Auszug aus der Wissensdatenbank.
2. **Messen Sie N aus vorhandenen Traces, schätzen Sie es nicht.** Das Verhältnis ist grob quadratisch in N, ein falsches N ist also ein quadrierter Fehler in der Kennzahl.
3. **Klassifizieren Sie einen Monat beendeter Läufe nach Stop-Grund und terminaler Kategorie**, bevor Sie irgendeine Zahl zu Kosten pro Erfolg nennen. Teilweise und fehlgeschlagene Enden haben unterschiedliche Nachbearbeitungskosten, und nur eine der drei Kategorien zählt als erfolgreicher Lauf.

Zwei Dinge, die dieses Modell nicht enthält und die auch nicht daraus abgeleitet werden sollten. Es sagt nichts über die Ausgabequalität: Es bepreist Tokens, und hinter keiner Zahl darin steht eine Messung der Erfolgsquote. Und es ignoriert die Entwicklungskosten, den Term, der diese Entscheidungen in der Praxis meist entscheidet. Meine eigene Schätzung, wie alles hier als Annahme deklariert: Ein fünfstufiger Workflow mit deklarierten Verträgen zwischen den Schritten kostet rund drei Entwicklertage zum Bauen und einen halben Tag pro Monat an Wartung, gegen einen halben Tag, um einen Agenten an sechs Tools zu hängen. Zu denselben $100/Stunde wie oben bei der Nachbearbeitung sind das etwa $2,000 mehr im Voraus und etwa $400/Monat mehr laufend. Gegen die Differenz von $0.0634 pro Ticket beim gecachten Agenten braucht allein die Vorab-Lücke rund 31.500 Tickets zur Amortisation, und die Wartungslücke zusätzlich rund 6.300 Tickets pro Monat. Unterhalb dieses Volumens ist die Zeile der Break-even-Tabelle, auf der Sie stehen, diejenige, die sagt: Bauen Sie den Agenten.
`;

export default content;
