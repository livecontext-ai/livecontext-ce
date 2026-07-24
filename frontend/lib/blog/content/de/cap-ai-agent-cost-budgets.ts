// cap-ai-agent-cost-budgets - de
// Translated from the English body; the structure must stay identical to it.
// Formulas and code samples are fenced on purpose: an inline code span over
// ~45 chars overflows the page on a phone. The hedges are load-bearing here.
const content = `## Ein Alert ist keine Obergrenze

Ein Monitor ist asynchron und nachgelagert: Er sagt Ihnen, was Sie bereits ausgegeben haben, und kann deshalb nicht die durchsetzende Schicht sein. Eine Obergrenze ist synchron und der Ausführung vorgelagert: Sie verweigert den nächsten Aufruf. Abrechnungsabgleich und Stop-Reason-Telemetrie bleiben wichtig, aber für die Dimensionierung der Obergrenze und das Erkennen einer zu engen Grenze, nicht für das Stoppen der Arbeit.

Hier ist der Test, den Sie durchführen sollten, bevor Sie weiterlesen, und er braucht keinen Schwellenwert: Rufen Sie die Ablehnungsdatensätze für Ihre konfigurierte Obergrenze über das letzte Beobachtungsfenster ab. Hat sie jemals etwas verweigert? Eine Zahl, die noch nie etwas abgelehnt hat, ist keine Kontrolle, sie ist ein Kommentar.

Obergrenzen auf Provider-Ebene sind Auffangnetze, keine Durchsetzung in erster Instanz:

- Das Ausgabenlimit von OpenAI für Projekt und Organisation ist standardmäßig ein **weiches Budget**: Es benachrichtigt, und die Requests laufen weiter. Eine harte Obergrenze existiert, aber als separater Opt-in-Schalter, der dann HTTP 429 zurückgibt, bis das Limit angehoben wird oder sich zurücksetzt ([Leitfaden zu Ausgabenlimits](https://developers.openai.com/api/docs/guides/spend-limits)).
- Die [Spend Limits API](https://platform.claude.com/docs/en/manage-claude/spend-limits-api) von Anthropic gilt nur für Claude Enterprise, steht Claude-Platform-Organisationen (Console) ausdrücklich nicht zur Verfügung und unterstützt \`monthly\` als einzige Periode (Reset um 00 UTC am Ersten des Kalendermonats). Sie begrenzt die Nutzung menschlicher Seats, nicht die API-Ausgaben von Agents.
- Anthropics Dokumentation disqualifiziert Provider-Ausgaben zudem als Gate: \`period_to_date_spend\` "may read as '0' if the spend reading is temporarily unavailable; treat it as informational, not transactional."
- Anthropic erzwingt tatsächlich eine monatliche Obergrenze pro Usage Tier (Start $500, Build $1,000, Scale $200,000, Custom ohne Limit), die die API-Nutzung bis zum Folgemonat pausiert ([Rate Limits](https://platform.claude.com/docs/en/api/rate-limits)). Eine echte Obergrenze, aber organisationsweit und monatlich: Ein einziger entgleister Run kann sie aufbrauchen und aus einem Kostenfehler einen organisationsweiten Ausfall machen.

Die Kosten pro Schritt wachsen überlinear, weil sich Kontext ansammelt, und genau deshalb begrenzt das Zählen von Schritten keine Dollarbeträge. Diese Herleitung steht im begleitenden Artikel zum Kostenmodell. Nur als Eingangsgrößen für die Dimensionierung: Anthropic berichtet, dass Agents ungefähr das 4-Fache der Token eines Chats verbrauchen und Multi-Agent-Systeme ungefähr das 15-Fache ([Multi-Agent Research System](https://www.anthropic.com/engineering/multi-agent-research-system)).

**Offenlegung.** Die Implementierungsdetails, Konstanten und Ablehnungsmeldungen weiter unten stammen aus dem \`agent-service\` von LiveContext, der Plattform, zu der dieser Blog gehört. Lesen Sie sie als die Entscheidungen eines einzelnen Systems, nachprüfbar in dessen Community-Edition-Quellcode, nicht als erhobene Praxis aus dem Feld.

## Die fünf Bestandteile eines Budget-Objekts

Ein Budget ist keine Zahl. Es ist ein Objekt mit fünf Bestandteilen, und ein Budget, dem einer davon fehlt, versagt auf eine spezifische, diagnostizierbare Weise.

**1. Scope.** Die Ebene, auf der das Konto geführt wird. In diesem ausgelieferten System existieren vier: Tenant-/Account-Guthaben (Makro), Agent/Schritt (Mikro), \`parent_reservation\` (ein Vorfahre in der Aufruferkette verweigert die Finanzierung eines Kind-Spawns) und pro Run/pro Epoch. Eine Ablehnung, die nicht benennt, welcher Scope ausgelöst hat, ist nicht debugbar.

**2. Einheit.** Dollar, Token oder bloße Zählwerte (Turns, Supersteps, Tool-Aufrufe). Zählwerte schwanken in Geldbeträgen. Nur Token oder Geld ergeben ein Budget.

**3. Durchsetzungspunkt.** Nachgelagerter Abgleich, Projektion vor der Iteration, Reservierung vor dem Spawn oder Zulassungsgrenze für Eingaben. Jeder hat eine andere Überschreitungsgrenze (Tabelle 1).

**4. Reservierungsrichtlinie.** Ob das Budget nachträglich dekrementiert oder vor Beginn der Arbeit gehalten wird. Das ist der einzige Bestandteil, der paralleles Fan-out sicher macht.

**5. Terminale Antwort.** Was der Aufrufer in dem Moment erhält, in dem die Obergrenze erreicht wird. In der Praxis existieren fünf verschiedene Verhaltensweisen, und sie sind nicht austauschbar.

**Tabelle 1: Durchsetzungspunkte und ihre Überschreitungsgrenze**

| Durchsetzungspunkt | Wann er läuft | Was er verweigern kann | Überschreitung im schlimmsten Fall | Sicher für paralleles Fan-out? |
|---|---|---|---|---|
| Nachgelagerter Abgleich / Alerting | Nach Abschluss des Aufrufs | Nichts | Unbegrenzt | Nein (Erkennung, keine Durchsetzung) |
| Projektion vor der Iteration | Vor dem nächsten Modellaufruf | Die nächste Iteration | Eine Iteration (bis zum 40-Fachen der ersten Iteration bei einem Browser-Schritt) | Nein |
| Reservierung vor dem Spawn | Bevor ein Kind startet | Das gesamte Kind | Null für das Kind | Ja |
| Zulassungsgrenze für Eingaben | Bevor der Prompt zusammengesetzt wird | Übergroßen Kontext / übergroße Ausgabe | Begrenzt die Iteration selbst | Ja (kombinierbar mit den anderen) |

Zwei Entwurfsentscheidungen, die man als Konfiguration behandelt, die aber zum Objekt gehören:

**Die Reihenfolge der Guards ist Scope-Design.** Diese Implementierung führt genau zwei Guards aus, \`TenantBudgetGuard\` und danach \`AgentBudgetGuard\`, nach dem Prinzip erste Ablehnung gewinnt mit Short-Circuit, aus zwei dokumentierten Gründen: Eine Tenant-Erschöpfung macht das Agent-Budget gegenstandslos, und der Tenant-Guard steht an erster Stelle als früher Reject vor dem nachgelagerten Roundtrip zur Guthabenreservierung.

**Die Periode ist eine Dimensionierungsentscheidung.** Ein kumulativer Zähler macht die Obergrenze zu einer Gesamtsumme über die gesamte Lebensdauer, sodass ein langlebiger Agent sich über Monate hinweg still der Erschöpfung nähert. Wöchentliche oder monatliche Resets machen aus derselben Zahl eine Rate. Resets können zu Beginn der Ausführung lazy per Compare-and-Set-Update aufgelöst werden statt durch einen Scheduler (\`BudgetResolver\`-Modi: cumulative, weekly, monthly; unbekannte Werte werden als cumulative behandelt).

Eine semantische Falle, die Sie im eigenen Stack prüfen sollten: Die agentenseitige Hilfe zu den Tool-Parametern dieser Plattform sagt weiterhin "Each LLM iteration costs 1 credit", während der Guard eine monetäre Projektion gegen genau dieses Feld \`credit_budget\` vergleicht. Zwei andere Hilfetexte relativieren das als "at least one credit" und "more than 1 credit in practice", sodass sich die Dokumente auch untereinander widersprechen. Eine Faustregel in der Dokumentation und ein monetärer Vergleich im Code sind eine Fehlerklasse, keine Formulierungskleinigkeit.

## Sie können den Aufruf, den Sie gerade machen, nicht stoppen

Der Tokenverbrauch ist erst bekannt, nachdem ein Aufruf abgeschlossen ist. Kein Budget innerhalb eines Runs kann verhindern, dass ein einzelner teurer Aufruf die Obergrenze sprengt; es kann nur den nächsten verhindern. Der realisierte schlimmste Fall ist deshalb **Budget plus eine Iteration**, nicht Budget. Sagen Sie das klar, statt eine harte Obergrenze zu suggerieren.

Die Gating-Formel, wie sie in der geteilten sprachübergreifenden Fixture-Datei steht:

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

Zwei Vorbehalte, bevor Sie das kopieren. Die beiden Java-Guards implementieren beim Projektionsvergleich \`>=\`, nicht \`>\`; das JS-Pendant implementiert \`>\`. Bei exakter Gleichheit der projizierten Summe widersprechen sie sich, und kein Fixture-Fall liegt auf dieser Grenze. Und der Vergleich auf Agent-Scope besteht nicht aus zwei, sondern aus vier Termen:

\`\`\`
totalProjected = consumedBeforeRun
               + creditsReserved
               + runCostSoFar
               + nextProjected
deny iff totalProjected >= totalBudget
\`\`\`

\`creditsReserved\` sind Credits, die derzeit von laufenden Sub-Agents gebunden sind, sodass die eigene Schleife eines Parents durch das gedrosselt wird, was seine Kinder halten.

Kein Projektionszweig ist redundant:

- \`growthProj\` (durchschnittliche Token pro abgeschlossener Iteration) erfasst einen stetigen Anstieg.
- \`lastDeltaProj\` (Delta der letzten Iteration mal 2) erfasst einen Ausbruch, den ein Durchschnitt verwässert.
- \`worstCaseSingleIter\` (volles Kontextfenster mal volle maximale Ausgabe zu den Raten des Modells) ist invariant gegenüber dem Wachstumsmuster und erfasst einen sprunghaften Anstieg in Iteration 1.

Der Worst-Case-Zweig leistet die eigentliche Arbeit. Bei Preisen der Opus-Klasse (15 / 75 USD pro 1M) mit 200K Kontext und 64K maximaler Ausgabe:

\`\`\`
worstCaseSingleIter = 200 * 15 + 64 * 75
                    = 3,000 + 4,800
                    = 7,800 credits      (1 credit = $0.001)
\`\`\`

Jedes Guthaben unterhalb von 7,800 Credits ist gegen diese Ausbruchsiteration durch den Worst-Case-Zweig geschützt, und durch nichts sonst.

Die zweite Ablehnungsbedingung, \`runCostSoFar >= balance\`, ist logisch redundant: Sobald die Projektion positiv ist, deckt die erste Bedingung sie bereits ab. Sie existiert einzig, damit die Ablehnung den tatsächlichen Fehlermodus benennt, statt als Projektionsüberschreitung zu erscheinen.

Die Kostenformel, zur Reproduzierbarkeit:

\`\`\`
inputCost  = inputRate  * promptTokens     / 1000
outputCost = outputRate * completionTokens / 1000
total      = inputCost + outputCost + fixedCost
\`\`\`

Die Raten sind USD pro 1M Token; das \`/1000\` rechnet in eine Credit-Einheit um, bei der 1 Credit = $0.001 ist. Runden Sie jeden Teilterm vor dem Summieren auf 6 Nachkommastellen, sonst driften zwei Implementierungen derselben Formel auseinander.

Drei ehrliche Einschränkungen dieses Mechanismus:

**Der Guard pro Agent braucht zwei abgeschlossene Iterationen.** Bei einer einzigen Stichprobe gilt \`lastDelta == runCost == growth\`, also \`lastDelta * 2 = 2 * runCost\`, und jede erste Iteration, die mehr als \`budget/3\` verbraucht, würde Iteration 2 selbst dann ablehnen, wenn der nächste Aufruf berechtigterweise klein ist. Der Tenant-Guard hat kein solches Gate: Er projiziert ab Iteration 1, wo growth und lastDelta beide null sind, sodass dort nur der Worst-Case-Zweig bindet. Das ist so beabsichtigt, und deshalb gehört die Obergrenze für Iteration 1 zum Worst-Case-Zweig.

**Veraltete Daten vergrößern die Lücke.** Ein Guthaben, das alle 5 Iterationen neu abgerufen wird (und bei jeder Iteration, wenn die Kostenraten unzuverlässig sind), fügt zur Projektionslücke von einer Iteration noch ein Staleness-Fenster hinzu. Eine adaptive Variante aktualisiert bei jeder Iteration, sobald die Burn Rate 70% des Guthabens übersteigt.

**Der Fallback für unbekannte Modelle ist eine echte Entscheidung mit Fehlerhistorie.** Bei den Raten pessimistisch scheitern (Rückfall auf die höchste Stufe, 15 / 75 USD pro 1M), aber bei der Obergrenze nachsichtig sein (Kontextfenster null lassen, sodass \`worstCase\` null zurückgibt und der Guard auf reines Wachstum zurückfällt). Ein früherer Fallback von 0.015 / 0.075 umging den Guard still und vollständig.

Die Kommentare des Guards selbst enthalten das Eingeständnis: Eine atomare Reservierungsschicht pro Turn wurde prototypisch gebaut und wieder zurückgenommen, weil eine Überschreitung von höchstens einer Iteration im Tausch gegen einen einfacheren Aufrufpfad als akzeptabel beurteilt wurde. Und die Vorabprüfung ist ausdrücklich "a snapshot, not authoritative": Der Abgleich nach der Ausführung läuft weiterhin, und beide können sich widersprechen.

## Der Moment des Aufpralls: was der Aufrufer tatsächlich bekommt

Ein Budget-Kill wird im Stop-Reason-Vertrag dieser Plattform als \`PARTIAL\` klassifiziert, nicht als \`FAILURE\`: verwertbare, aber abgeschnittene Ausgabe. Er wirft keine Exception, und ein Budget-Kill, der Token produziert hat, wird mit dem Ausführungsstatus \`COMPLETED\` persistiert, sodass nur die Spalte \`stop_reason\` das Detail trägt. Zwei Präzisierungen, weil Halbwahrheiten hier genau der Weg sind, auf dem eine zu enge Obergrenze unsichtbar bleibt: Ein Budget-Kill mit null Token wird als \`FAILED\` persistiert, und die tägliche Metrik-Aggregation zählt jeden budgetbedingt gestoppten Run sehr wohl in ihre Fehleranzahl. Wirklich unsichtbar ist die Form des Schadens, nicht die Tatsache des Schadens. Wer nur Fehlerraten beobachtet, sieht eine zu enge Obergrenze Monate später als Qualitätsregression auftauchen.

**Tabelle 2: Wo jeder Stop Reason entschieden wird (6 der 10 Werte des Vertrags)**

| Stop Reason | Terminale Kategorie | Wo entschieden wird | Was der Aufrufer tun muss |
|---|---|---|---|
| \`MAX_ITERATIONS\` | partial | Nachgelagert, nach Verlassen der Schleife | Ausgabe als abgeschnitten behandeln; n oder Budget erhöhen |
| \`TIMEOUT\` | partial | Nachgelagert, nach Verlassen der Schleife | Aktiv arbeitend, über die Wanduhrzeit hinaus; fortsetzen oder erweitern |
| \`BUDGET_EXHAUSTED\` | partial | Guard vor der Iteration, vor dem Aufruf | \`budgetScope\` lesen (\`tenant\`, \`agent\`, \`parent_reservation\`, \`browser\`), Aufstockung vs. Neudimensionierung entscheiden |
| \`LOOP_DETECTED\` | partial | Mitten in der Iteration, nach dem Parsen der Tool-Aufrufe | Die wiederholte Signatur prüfen; die Aufgabe ist fehlerhaft formuliert |
| \`STOPPED_BY_USER\` | partial | Cancel-Kanal | Teilausgabe behalten |
| \`INACTIVITY_TIMEOUT\` | failure | Watchdog, nicht die Schleife; ein Nachlauf reklassifiziert \`STOPPED_BY_USER\` | Verstummt, musste getötet werden; den Hänger untersuchen |

\`BUDGET_EXHAUSTED\` ist der einzige Wert, der ein Scopes-Array trägt. Ein Budget-Stopp, der nicht sagt, welche Obergrenze ausgelöst hat, zwingt zum Raten.

Eine Ablehnung sollte keine Exception sein. Eine brauchbare Implementierung bricht aus der Schleife aus und protokolliert strukturierte Metadaten: den Stop Reason, dazu \`budgetScope\` und einen \`denialReason\`-String, der benennt, welcher Projektionszweig ausgelöst hat:

\`\`\`
tenant balance X would be exceeded
(run=A + next=B [growth=..., lastDelta=..., worstCase=...])
\`\`\`

Verwenden Sie auf dem synchronen und dem Streaming-Pfad dieselben Keys, damit die Metriken nicht auseinanderdriften.

Im untersuchten Feld existieren fünf terminale Verhaltensweisen, und sie sind nicht austauschbar:

1. **Exception**: \`MaxTurnsExceeded\` (OpenAI Agents SDK), \`GraphRecursionError\` (LangGraph), \`UsageLimitExceeded\` (Pydantic AI), \`ModelCallLimitExceededError\` (LangChain).
2. **Typisiertes, verzweigbares Ergebnis**: AutoGens \`stop_reason\` am \`TaskResult\`, der Subtyp \`error_max_budget_usd\` des Claude Agent SDK, LangChains \`exit_behavior='end'\` mit einer eingefügten AI-Nachricht.
3. **Stilles Abschneiden mit HTTP 200**: Anthropics \`max_tokens\` setzt \`stop_reason: "max_tokens"\` und gibt Erfolg zurück ([Messages API](https://platform.claude.com/docs/en/api/messages)).
4. **Ablehnung mit HTTP 429**: OpenAIs hartes Opt-in-Limit. Anthropic dokumentiert 429 nur für \`rate_limit_error\` und ordnet Abrechnungsprobleme 402 zu, sodass für die monatliche Ausgabengrenze pro Tier kein Statuscode dokumentiert ist; prüfen Sie das gegen Ihre eigenen Logs.
5. **Bestmögliche, verschlechterte Antwort**: CrewAIs \`max_iter\`, wo der Agent "must provide its best answer" ([CrewAI agents](https://docs.crewai.com/en/concepts/agents)).

Ein Semantikkonflikt, den Sie im eigenen Stack prüfen sollten: [LiteLLMs Iterationsbudgets](https://docs.litellm.ai/docs/a2a_iteration_budgets) geben 429 mit dem Fehlertyp \`budget_exceeded\` zurück, und nach HTTP-Konvention bedeutet 429 "später erneut versuchen". Für eine zeitlich zurückgesetzte Organisationsgrenze ist das vertretbar, denn Warten macht den Request irgendwann tatsächlich erfüllbar. Für ein Budget pro Run oder pro Agent ist es falsch: Warten erfüllt es nie, und die Standard-Retry-Logik eines SDK rennt gegen die Wand. LiteLLM ist hier der eine bestätigte Einzelfall, keine nachgewiesene Klasse. Prüfen Sie, was die Retry-Policy Ihres Clients mit einem 429 macht.

Was den Stopp überleben sollte, ist die andere Hälfte des Vertrags. Das [Claude Agent SDK](https://code.claude.com/docs/en/agent-sdk/agent-loop) kommt einem Referenzdesign am nächsten: Das Feld \`result\` (die endgültige Antwort) existiert nur beim Subtyp \`success\`, aber jeder Fehler-Subtyp trägt weiterhin \`total_cost_usd\`, \`usage\`, \`num_turns\` und \`session_id\`. Sie verlieren die Antwort, nicht die Session. Beachten Sie die Asymmetrie: Ein einmaliges \`query()\` wirft nach der Ausgabe des Fehlerergebnisses, während eine Session mit Streaming-Eingabe am Leben bleibt.

Warum das kommerziell zählt, aus einem [Incident Report](https://github.com/anthropics/claude-code/issues/68430): Die einzigen Optionen des Betreibers waren, es "let it run and watch it burn the session budget on a recursive loop that will never succeed" oder "kill it and lose everything, including legitimate work completed by early agents." Eine Obergrenze, die Teilarbeit verwirft, verwandelt ein Kostenproblem in ein Totalverlustproblem, und genau deshalb schalten Betreiber Obergrenzen ab.

Eine Ablehnung auf Parent-Seite sollte derselben Regel folgen: kein geworfener Fehler, sondern ein synthetisiertes Fehlerergebnis, das den Vorfahren und den Scope benennt.

\`\`\`
Cannot spawn child 'X': ancestor agent <id> has
insufficient free budget for reservation N
(scope=parent_reservation, BUDGET_EXHAUSTED)
\`\`\`

Machen Sie die Obergrenze schließlich aus dem Agent heraus inspizierbar. Die ausgelieferte Antwortform:

\`\`\`
budget.{ unlimited, total, consumed,
         consumed_own, consumed_from_subagents,
         reserved_for_subagents, free,
         reset_mode, last_reset }

free = max(total - consumed - reserved_for_subagents, 0)
\`\`\`

Im unbegrenzten Zweig sind \`total\` und \`free\` null, und \`reserved_for_subagents\` wird als 0 zurückgegeben. Die explizite Regel: Liegt \`free\` unter dem Budget eines Kindes, schlägt der Spawn mit \`scope=parent_reservation\` fehl.

## Was jeder Stack durchsetzen kann und was nicht

**Tabelle 3: Was jeder Stack tatsächlich durchsetzen kann** (begrenzt auf die untersuchten Plattformen; Google ADK und LlamaIndex waren nicht dabei)

| Stack | Durchgesetzte Einheit | Standardwert | Verhalten an der Obergrenze | Vererbt sich an Sub-Agents? |
|---|---|---|---|---|
| [Claude Agent SDK](https://code.claude.com/docs/en/agent-sdk/python) | USD pro Run (\`max_budget_usd\`), dazu Turns | Beides unbegrenzt | Typisierter Ergebnis-Subtyp \`error_max_budget_usd\` / \`error_max_turns\`, Session bleibt erhalten | \`usage\` schließt Subagent-Token aus; \`total_cost_usd\` schließt sie ein |
| Anthropic Messages API | Token (\`max_tokens\`) | Kein Standard; Sie müssen ihn setzen | HTTP 200 mit \`stop_reason: "max_tokens"\`, abgeschnitten | Nicht zutreffend |
| OpenAI (Account) | USD pro Monat | Standardmäßig weich | Benachrichtigung, oder 429 bei aktiviertem hartem Limit | Nicht zutreffend |
| [OpenAI Agents SDK](https://openai.github.io/openai-agents-python/running_agents/) | Turns ([\`DEFAULT_MAX_TURNS = 10\`](https://github.com/openai/openai-agents-python/blob/main/src/agents/run_config.py)) | 10 | Wirft \`MaxTurnsExceeded\` | Nicht dokumentiert |
| [LangGraph](https://docs.langchain.com/oss/python/langgraph/graph-api) | Supersteps (\`recursion_limit\`) | Dokumentation widersprüchlich: 1000 seit v1.0.6 in der OSS-Graph-Runtime, 25 im SDK-\`Config\`-Schema und in Feldberichten | Wirft \`GraphRecursionError\` | Zwei dokumentierte Vererbungsfehler (unten) |
| [LangChain middleware](https://reference.langchain.com/python/langchain/agents/middleware/model_call_limit/ModelCallLimitMiddleware) | Nur Aufrufzähler, kein Token- oder Kostenbudget | Beide Limits \`None\` | Konfigurierbar: \`exit_behavior='end'\` fügt eine Nachricht ein, \`'error'\` wirft | Nicht anwendbar |
| [Pydantic AI](https://pydantic.dev/docs/ai/api/pydantic-ai/usage/) | Token, Requests, Tool-Aufrufe | \`request_limit=50\`, Token-Limits \`None\` | Wirft \`UsageLimitExceeded\`; optionale Vorabprüfung | Nicht dokumentiert |
| AutoGen ([conditions](https://microsoft.github.io/autogen/stable/reference/python/autogen_agentchat.conditions.html), [teams](https://microsoft.github.io/autogen/stable/reference/python/autogen_agentchat.teams.html)) | Token (\`TokenUsageTermination\`) | Team-Standards: \`termination_condition=None\`, \`max_turns=None\` | Typisiertes \`TaskResult\` mit einem \`stop_reason\`-String | Auf das Team begrenzt |
| [CrewAI](https://docs.crewai.com/en/concepts/agents) | Iterationen (\`max_iter\`) | Dokumentation sagt 20, Quellcode sagt 25 | Agent "must provide its best answer" | Nicht dokumentiert |

Fünf Dinge, die diese Tabelle sagt und die im Fließtext untergehen würden:

**Fast alles ist standardmäßig unbegrenzt.** \`max_turns\` und \`max_budget_usd\` des Claude Agent SDK sind beide ohne Limit; [AutoGen teams](https://microsoft.github.io/autogen/stable/reference/python/autogen_agentchat.teams.html) sagen unumwunden, der Gruppenchat "will run indefinitely"; Anthropics Enterprise-Seat-Ausgabenlimits sind standardmäßig unbegrenzt, wenn auf keiner Ebene ein Standard existiert (die Tier-Obergrenzen der API gelten dagegen immer).

**Der einzige Kostenregler in der Erhebung ohne Standardwert ist Anthropics \`max_tokens\`**, den das Schema der Messages API zwingend explizit verlangt. Er ist zugleich der einzige, dessen Überschreitung HTTP 200 mit abgeschnittenem Inhalt zurückgibt. Das Schema dokumentiert inzwischen auch, ihn auf 0 zu setzen, um den Prompt-Cache aufzuwärmen, sodass verpflichtend nicht gleichbedeutend mit sinnvoller Obergrenze ist.

**Die einzige Dollar-Obergrenze pro Run in der Erhebung wird gegen eine Schätzung durchgesetzt.** Anthropics Seite zum Kosten-Tracking warnt, dass \`total_cost_usd\`, genau die Größe, gegen die \`max_budget_usd\` verglichen wird, aus "client-side estimates, not authoritative billing data" besteht, berechnet aus einer zum Build-Zeitpunkt mitgelieferten Preistabelle, und sagt: "Do not bill end users or trigger financial decisions from these fields." Sie wird zudem zwischen Turns ausgewertet, sodass die Ausgaben das konfigurierte Limit um einen Turn überschreiten können. Das ist dieselbe Garantie "Budget plus eine Iteration", im am besten entworfenen Produkt des Felds.

**LangChain hat überhaupt kein Token- oder Kostenbudget.** \`ModelCallLimitMiddleware\` und \`ToolCallLimitMiddleware\` begrenzen Aufrufzähler, beide stehen standardmäßig auf \`None\`, und ein Maintainer hat [die Token-Budget-Lücke im Juli 2026 bestätigt](https://forum.langchain.com/t/a-proposal-to-add-token-usage-budgets-to-langchain-agents-via-a-new-middleware-since-the-existing-limiters-only-cap-call-count-not-tokens/4147). Sein Parameter \`exit_behavior\` ist gleichwohl der sauberste konfigurierbare Fehlermodus im Feld und nachahmenswert.

**Pydantic AI ist der einzige Stack mit einer Vorabprüfung**: \`count_tokens_before_request\` (Standard \`False\`) ruft die Token-Zähl-API des Providers auf, um einen Request über Budget abzulehnen, bevor er abgerechnet wird. Es bringt zugleich eine Falle mit: \`request_limit\` hat still den Standardwert 50, sodass das alleinige Setzen von \`input_tokens_limit\` eine Obergrenze von 50 Requests erbt, sofern Sie nicht \`request_limit=None\` übergeben.

**Vererbung ist der häufigste Weg, auf dem eine Obergrenze dekorativ wird.** Zwei dokumentierte Fälle: [LangChain deepagents #1698](https://github.com/langchain-ai/deepagents/issues/1698), wo \`SubAgentMiddleware\` Subagents ohne den Parameter \`config\` aufrief, sodass sie stets mit dem Standard-Rekursionslimit liefen, unabhängig von einem auf 150 gesetzten Parent; und [langgraphjs #1524](https://github.com/langchain-ai/langgraphjs/issues/1524), wo \`recursionLimit\` in \`withConfig\` still ignoriert wird und die resultierende Fehlermeldung Ihnen sagt, Sie sollten genau den Key setzen, der ignoriert wird.

Zwei Messfallen, die naiven Budget-Code still aushebeln, beide aus [Anthropics Dokument zum Kosten-Tracking](https://code.claude.com/docs/en/agent-sdk/cost-tracking): Das Feld \`usage\` zählt nur die oberste Schleife und schließt Subagent-Token aus (während \`total_cost_usd\` und \`model_usage\` sie einschließen), und parallele Tool-Aufrufe erzeugen mehrere Assistant-Nachrichten, die sich eine Message-ID mit identischem Usage teilen, sodass ein Zähler, der Usage pro Nachricht summiert, doppelt zählt und zu früh auslöst. Nach ID deduplizieren.

Rate Limits sind keine Ausgabenlimits und können den teuren Pfad belohnen: Gecachte Input-Token werden mit 10% abgerechnet, zählen bei den meisten Modellen aber nicht auf die Input-Token-pro-Minute-Limits an, und \`max_tokens\` fließt überhaupt nicht in die Output-Token-pro-Minute-Limits ein ([Rate Limits](https://platform.claude.com/docs/en/api/rate-limits)).

## Loop Guards begrenzen n; Budgets begrenzen die Kosten bei gegebenem n

Ein Loop-Detektor und ein Budget beantworten verschiedene Fragen. Der Detektor begrenzt, wie viele Iterationen stattfinden; das Budget begrenzt, was diese Iterationen kosten dürfen. Keines ersetzt das andere.

Reale Schwellenwerte aus einem ausgelieferten Detektor, mit zwei unabhängigen Auslösebedingungen:

| Bedingung | Schlüssel | Eskalationsstufen | Harter Stopp |
|---|---|---|---|
| Identische Aufrufe | Tool-Name + sortierte Argumente, gehasht | Warnung bei 5 | 15 |
| Aufeinanderfolgende Aufrufe | Gesamtzahl Tool-Aufrufe, beliebige Signatur | 15, 25, 35 | 40 |

Die Obergrenze für aufeinanderfolgende Aufrufe ist bewusst hoch, damit legitime Batch-Operationen nicht abgebrochen werden. Beide harten Stopps sind pro Agent konfigurierbar, und die Zwischenstufen sind **abgeleitet** (Warnung bei identischen Aufrufen = \`ceil(stop/3)\`, mindestens 2; Stufen bei aufeinanderfolgenden Aufrufen = \`ceil(stop * 3/8)\`, \`5/8\`, \`7/8\`), sodass die Schweregradleiter bei jedem benutzerdefinierten Wert monoton bleibt, mit erzwungenen Mindeststopps.

Die Leiter ist nicht bloß Logging: Jede Stufe fügt vor dem Stopp eine Nachricht in den Kontext des Agents ein, eskalierend von einem informativen Hinweis über "1 iteration left, STOP tools, RESPOND NOW" bis zur Terminierung. Die erklärte Entwurfsabsicht ist, dass sich wiederholende Muster als Workflows automatisiert und nicht in Schleifen ausgeführt werden sollten.

Die Abdeckungslücke, die benannt gehört: Dieser Detektor zählt nur vier Tool-Namen. Jeder andere Tool-Aufruf ist für beide Zähler unsichtbar, sodass eine Schleife über ein nicht erfasstes Tool nie \`LOOP_DETECTED\` erzeugt. Prüfen Sie die entsprechende Abdeckung in Ihrem eigenen Stack, bevor Sie einem Loop Guard vertrauen.

Verlassen Sie sich nicht darauf, dass das Modell seine eigene Verschwendung bemerkt. RedundancyBench annotierte 200 Trajektorien (gefiltert aus den gesammelten erfolgreichen Runs) mit über 8,000 annotierten Schritten, und die beste automatisierte Erkennung redundanter Schritte auf Schrittebene erreichte 24.88% (72.50% auf Trajektorienebene) ([arXiv 2605.29893](https://arxiv.org/abs/2605.29893)). Die Obergrenze muss mechanisch sein.

Weitere Standardwerte zur Run-Begrenzung aus derselben Implementierung, als Bezugspunkt: maximale Iterationen 100, Ausführungs-Timeout 3600 s, maximal 16,000 Token pro Turn und ein 5-Minuten-Inaktivitäts-Watchdog, dessen Override pro Agent nur 0 (deaktiviert) oder 10 bis 7200 Sekunden akzeptiert, sodass ein versehentlicher Wert keinen Watchdog im Sekundenbereich scharfschalten kann.

Die Wanduhr verdient eine Zeile als Obergrenze der letzten Instanz. Ein dokumentierter Vorfall verbrauchte 4 Millionen Token in unter 5 Minuten ([claude-code #68619](https://github.com/anthropics/claude-code/issues/68619)), schneller als jede Abtastung pro Turn oder jede Guthabenaktualisierung reagieren würde. Das ist eine Schlussfolgerung aus einem einzelnen Vorfall, keine belegte Best Practice, aber der Rechnung ist schwer zu widersprechen.

## Der Test für eine echte Obergrenze

Sechs Punkte, jeder aus Ihren eigenen Logs beantwortbar:

1. Benennt eine Ablehnung den Scope, der ausgelöst hat?
2. Ist die Prüfung synchron und vor dem nächsten Aufruf?
3. Ist die terminale Antwort typisiert, nicht wiederholbar, und trägt sie das Kostenkonto plus einen Resume-Handle?
4. Vererbt sich die Obergrenze an Sub-Agents, belegt durch einen Test, der ein Parent-Limit setzt und prüft, dass ein Kind es erbt?
5. Ist das Granularitätsverhältnis \`g\`, also das Budget geteilt durch die begrenzte Worst-Case-Iteration, mindestens 3? Der begleitende Artikel zur Dimensionierung leitet diese Untergrenze her und zeigt, dass die meisten Geldobergrenzen pro Schritt sie verfehlen.
6. Hat die Obergrenze im beobachteten Zeitfenster jemals tatsächlich abgelehnt?

Die ehrliche Garantie: Ein Budget innerhalb eines Runs begrenzt die Kosten auf **das Budget plus eine Iteration**, nicht auf das Budget. Eine Reservierung vor dem Spawn ist der einzige Mechanismus ohne Überschreitung, und er deckt nur das Kind ab.

Wenn dieselbe Formel in zwei Laufzeitumgebungen existiert, lohnt es sich, Parität zu erarbeiten. Eine geteilte Fixture-Datei mit benannten Fällen, die sowohl von einem parametrisierten JUnit-Test als auch von einem Node-Testrunner konsumiert wird, ist der billigste Weg, das Auseinanderdriften der beiden zu verhindern, und die Rundung muss Teilterm für Teilterm übereinstimmen. Beachten Sie die Grenze: Ein Fixture deckt nur die Fälle ab, die es enthält. Ein Fixture, das explizite Raten vorgibt, durchläuft auf keiner der beiden Seiten je den Fallback-Pfad für unbekannte Modelle, und genau dort wichen die beiden hier beschriebenen Implementierungen um eine Größenordnung voneinander ab; und ein Fixture, das nur den Tenant-Guard instanziiert, bemerkt nie, dass die beiden Agent-Guards unterschiedliche Vergleichsoperatoren verwenden.

Benennen Sie, was nicht bekannt ist. Es existiert keine veröffentlichte Basisrate dafür, wie oft Produktions-Agents entgleisen. Der stärkste Katalog bestreitet ausdrücklich jede Aussage zur Häufigkeit und behauptet nur Existenz und Wiederkehr über unabhängig entwickelte Projekte hinweg. Argumentieren Sie aus Mechanismus und Größenordnung, statt eine Häufigkeit zu erfinden.

Und seien Sie realistisch bei der Größenordnung. Laut den Vorfallszeilen im selben Katalog von 2026 ([arXiv 2606.04056](https://arxiv.org/abs/2606.04056)) häufen sich dokumentierte Überschreitungen im Bereich von einigen hundert bis wenigen tausend Dollar: rund $2,150 unbeabsichtigte Ausgaben in einem Fall, $235 in vier Tagen durch einen einzelnen Nutzer, eine Überschreitung von 70% über ein Optimierer-Budget hinaus. Vergleichen Sie das mit der am häufigsten weiterverbreiteten Anekdote des Felds, ["We spent $47,000 running AI agents"](https://todatabeyond.substack.com/p/we-spent-47000-running-ai-agents), die kein Unternehmen nennt und weder Rechnung noch Repo, Konfiguration oder Logs vorlegt, und die anschließend unter einem zweiten Autorennamen und über ein Dutzend sich gegenseitig zitierender SEO-Beiträge verstärkt wurde. Ihre eigenen Wochenzahlen lauten $127, $891, $6,240 und $18,400, was sich zu $25,658 summiert, nicht zu $47,000, und ein vierwöchiger Kostenanstieg widerspricht der "11-day loop" im selben Beitrag. Das reale Risikoprofil ist still, wiederkehrend und im mittleren vierstelligen Bereich.
`;

export default content;
