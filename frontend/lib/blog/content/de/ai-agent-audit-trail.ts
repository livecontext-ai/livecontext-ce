// ai-agent-audit-trail - de
// Translated from the English body; structure identical to it. Legal scope
// statements and every "at least 6 months"/"out of scope"/"not legal advice" are
// load-bearing and must not be strengthened or softened. Fenced code stays fenced.
const content = `I've read the source. Comparing against the German translation, I found the issues to fix (notably the untranslated opening "An audit trail" and a grammatically broken subordinate clause). Here is the corrected German body.

Ein Audit Trail ist kein längeres Log. Es ist ein anderes Artefakt, mit einem anderen Leser, einem anderen Schreibvertrag und einer anderen Uhr. Dieser Beitrag veröffentlicht ein kopierbares Schema auf Run-Ebene und Step-Ebene, in dem jedes Feld vier Dinge zugleich trägt: seinen Datentyp, seine Kardinalitätsklasse, ob es personenbezogene Daten enthalten darf und den Grund für seine Existenz. Ein begleitender Artikel führt die Speicherarithmetik durch, die aus dem Retention-Tiering eine abgeleitete Entscheidung macht, ordnet die tatsächlichen rechtlichen Pflichten zu und behandelt die Löschanfrage, die mit einem über Jahre aufbewahrten Trail kollidiert.

Die durchgängig zitierte Referenzimplementierung ist die eigene Plattform dieses Blogs. Echte Spaltennamen, echte Migrationen, echte Bugs.

## Der Leser, für den Sie schreiben, sind nicht Sie und nicht jetzt

Ein Dashboard wird von seinem Autor gelesen, innerhalb von Minuten, während der Vorfall noch im Arbeitsgedächtnis ist. Ein Trail wird von einem gleichgültigen oder feindseligen Dritten gelesen, Monate später, der keine Rückfrage stellen kann. Dieser Unterschied erzeugt jede Entscheidung weiter unten.

Zwei Invarianten folgen daraus, und fast niemand schreibt sie auf:

1. **Audit-Records werden niemals gesampelt.**
2. **Content-Felder werden innerhalb ihres Retention-Fensters niemals degradiert.**

Der Rest ist Designurteil, und die Speicherkosten dieses Urteils sind Arithmetik.

Sagen wir das Unbequeme gleich vorab: **kein Instrument spezifiziert dieses Schema.** Außer EU AI Act Art. 12(3), der auf genau einen Annex-III-Unterpunkt zutrifft (point 1(a), remote biometric identification, und nicht auf biometric verification), spezifiziert nichts von dem hier Geprüften (der AI Act, ISO/IEC 42001, NIST AI RMF, SOC 2) ein Log-Schema, Feldtypen, Kardinalitätsgrenzen oder eine Sampling-Strategie. Das Schema unten ist ein Ingenieursurteil mit dem Ziel, die *Zwecke* zu erfüllen, die das Gesetz in Art. 12(2)(a) bis (c) benennt, sowie das Erklärbarkeitsrecht in Art. 86. Es ist kein Compliance-Artefakt, und ich werde es nicht als solches verkaufen.

Jedes Feld in einem brauchbaren Trail trägt vier Dinge zugleich: seinen Datentyp und seine Nullability, die Frage oder Pflicht, die es erzwingt, seine Kardinalitätsklasse und seine Retention-Klasse, einschließlich der Frage, ob es gesampelt oder degradiert werden darf. Keine veröffentlichte Quelle füllt alle vier Ecken. [Die GenAI-Konventionen von OpenTelemetry](https://github.com/open-telemetry/semantic-conventions-genai) haben Typen, aber keine Pflichten und standardmäßig keinen Content; [ARMOs minimal brauchbarer Audit Trail](https://www.armosec.io/blog/minimum-viable-audit-trail/) hat Pflichten und Feldnamen, aber keine Typen; das AI-Act-Cluster hat das Gesetz und räumt ein, dass es keine Felder spezifiziert.

Zwei Anmerkungen zur Abgrenzung. Der Trail ist **linear in den Steps, nicht quadratisch**: Sie bezahlen das Modell dafür, den akkumulierten Context in jeder Runde erneut zu senden, speichern aber jede Nachricht einmal, sodass ein Run mit sechs Steps unabhängig vom Context-Wachstum ~27 Zeilen umfasst (die quadratische Seite gehört zum Artikel über das Kostenmodell). Und \`stop_reason\` und \`terminal_category\` erscheinen hier rein als aufzuzeichnende Felder; die Taxonomie und das Cap-Verhalten gehören zum Artikel über die Budget-Durchsetzung.

## Ein Observability-Dashboard ist kein Audit Trail

Die Vermengung teilt sich sauber nach Artikeltitel: Observability-betitelte Beiträge verkaufen Traces als den Audit Trail; Audit-betitelte Beiträge erwähnen selten, dass das Standard-Schema standardmäßig keinen Content aufzeichnet.

Dieser Default ist der Kernbefund. Die GenAI-Semantic-Conventions setzen Prompts, Completions, System-Instructions, Tool-Arguments und Tool-Results alle auf Requirement-Level \`Opt-In\`, und die Position der Spec ist, dass Instrumentierungen "SHOULD NOT capture them by default", wobei Option 1 lautet "[Default] Don't record instructions, inputs, or outputs." Also ist "wir haben OTel-Tracing, also haben wir einen Audit Trail" ab Werk falsch: Was Sie haben, sind Modell, Token-Counts, Latenz und Finish-Reason, nichts von dem Material, das eine Entscheidung rekonstruiert.

Das Einschalten ist schwieriger, als es aussieht. In [opentelemetry-python-contrib](https://github.com/open-telemetry/opentelemetry-python-contrib/blob/main/util/opentelemetry-util-genai/src/opentelemetry/util/genai/utils.py) ist der Capture-Schalter kein Boolean:

\`\`\`
OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT
  = NO_CONTENT | SPAN_ONLY | EVENT_ONLY | SPAN_AND_EVENT
  unset            -> NO_CONTENT
  invalid value    -> warning, then NO_CONTENT

# second gate, barely documented:
OTEL_SEMCONV_STABILITY_OPT_IN must select the GenAI
experimental mode, or get_content_capturing_mode() raises.
\`\`\`

Nur die Capture-Variable zu setzen reicht nicht. (Verifiziert nur für die Python-contrib-Pakete; andere Sprach-SDKs können sich im Flag-Namen, den Enum-Werten oder darin unterscheiden, ob das zweite Gate überhaupt existiert.)

Unterdessen ist gängiger Observability-Rat auf zwei voneinander unabhängige Weisen audit-fatal. Für Features mit hohem Volumen über ~1,000 requests/second: [reduzieren Sie das Call-Envelope-Sampling auf 10-20% und reservieren Sie das volle Token-Level-Capture für explizite Debug-Sessions](https://www.braintrust.dev/articles/llm-call-observability); und [bereinigen oder maskieren Sie Content, bevor er das Backend erreicht](https://mlflow.org/articles/setting-up-llm-observability-pipelines-in-2026/). Ein Zehn-Prozent-Sample ist nutzlos, wenn die Entscheidung, die Sie verteidigen müssen, in den neunzig Prozent liegt, die Sie verworfen haben.

| Dimension | Observability-Dashboard | Audit Trail |
|---|---|---|
| Konsument | Der Autor, Minuten später | Ein gleichgültiger oder feindseliger Dritter, Monate später |
| Lese-Latenz | Sekunden bis Stunden | Monate bis Jahre |
| Sampling | Erwartet (10-20%, oder tail-based) | Verboten |
| Content-Default | Aus (OTel-GenAI-Content ist Opt-In) | An, innerhalb seines Retention-Fensters |
| Schreibvertrag | Fire-and-forget, Fehler geloggt | Selbe Transaktion, Fehler lässt die Operation fehlschlagen |
| Ordnungsquelle | Zeitstempel, resampled | Vom Writer zugewiesene Sequenz |
| Mutability | Per Design veränderlich (Reprocessing, verworfene Felder bei Backend-Upgrade) | Append-only, idealerweise hash-chained |
| Retention-Treiber | Wie lange eine Regression interessant bleibt (Tage) | Eine Pflicht oder ein Dispute-Horizont (Monate bis Jahre) |
| Fehlermodus | Sie debuggen langsamer | Sie können die Frage nicht beantworten |

Der Schreibvertrag ist das, dessen Fehlgriff am billigsten ist. Diese Plattform hält beide Haltungen, jede korrekt für ihr Artefakt. Der Agent-Observability-Schreibvorgang ist ein Fire-and-forget-HTTP-POST (\`AgentClient.recordObservability\`), dessen Fehler abgefangen und auf WARN als "non-critical" geloggt wird: Der Run wird trotzdem abgerechnet und liefert zurück, die Audit-Zeile geht einfach verloren. Das Feature-Flag-Audit (\`V173__flag_flip_audit.sql\`) formuliert den gegenteiligen Vertrag in seinem Migration-Header: selbe Transaktion, kein \`REQUIRES_NEW\`, kein async, kein \`AFTER_COMMIT\`-Listener (das würde ein Rennen mit einem JVM-Kill eingehen), und wenn der Audit-Insert wirft, wird das Flag nicht umgelegt.

Die Konsequenz der Best-Effort-Wahl ist der Fehlermodus, der in Ordnung aussieht, bis Sie ihn brauchen: **die Trail-Abdeckung wird mit der Systemgesundheit korreliert**, sodass sie genau während der Vorfälle ausdünnt, deren Erklärung von Ihnen verlangt werden wird.

## Das Schema auf Run-Ebene

Eine Zeile pro Run. Dies ist der Header, den ein Auditor zuerst liest.

| Feld | Typ | Null | Kardinalität | Personenbezug | Warum es existiert |
|---|---|---|---|---|---|
| \`run_id\` | uuid | nein | hoch | nein | Join-Key für jede Child-Zeile. Bei **Dispatch** prägen, nicht bei INSERT. |
| \`trail_seq\` | bigint (dedizierte Sequenz) | nein | hoch | nein | Ordnung, die Clock-Skew und Schreibvorgänge in derselben Millisekunde übersteht. |
| \`prev_row_hmac\` | bytea(32) | ja | hoch | nein | Manipulationsnachweis: deckt den eigenen Content plus den HMAC der Vorzeile ab. |
| \`tenant_id\`, \`organization_id\` | text / uuid | nein | mittel | indirekt | Scope-Key für Löschung und Zugriffskontrolle. |
| \`actor_subject_ref\` | text (pseudonymes Token) | ja | hoch | **ja** | "Wer hat gefragt." Auflösbar zu einer Identität nur über ein separat gehaltenes Mapping. |
| \`parent_run_id\` | uuid | ja | hoch | nein | Welcher Run diesen erzeugt hat. |
| \`caller_agent_id\` | uuid | ja | mittel | nein | Welcher Agent ihn erzeugt hat. |
| \`depth\` | int2 | nein | niedrig | nein | Zyklenerkennung und Baumordnung. |
| \`caller_tool_call_id\` | text | ja | hoch | nein | Der exakte Call im Parent, der das Child erzeugt hat. |
| \`trigger_source\` | enum | nein | **niedrig** | nein | manual / chat / webhook / schedule / datasource / workflow / error. Entscheidet, ob ein Mensch dafür verantwortlich ist, dass der Run existiert. |
| \`started_at\`, \`ended_at\` | timestamptz | nein / ja | hoch | nein | Zwei Zeitstempel, nicht einer plus eine Dauer. |
| \`status\` | enum | nein | niedrig | nein | Die Behauptung, deren Verteidigung von Ihnen verlangt wird: Ist dieser Run gelungen. |
| \`stop_reason\` | text (roher Enum-String) | ja | niedrig | nein | Wörtlich gespeichert für Forensik. |
| \`terminal_category\` | enum | ja | niedrig | nein | Materialisiert, nicht zur Lesezeit abgeleitet. |
| \`billed_provider\`, \`billed_model\` | text | nein | niedrig | nein | Wofür Ihnen berechnet wurde. |
| \`executed_provider\`, \`executed_model\` | text | ja | niedrig | nein | Was tatsächlich lief. Sie können abweichen. |
| \`model_snapshot\` | jsonb (\`_v\`-keyed) | ja | mittel | nein | Preisliste und Modellkonfiguration, eingefroren bei Ausführungsbeginn. |
| \`prompt_tokens\`, \`completion_tokens\`, \`cache_creation_tokens\`, \`cache_read_tokens\`, \`reasoning_tokens\` | int4 x5 | nein (default 0) | hoch | nein | Fünf Zähler, nicht ein Gesamtwert: sie werden unterschiedlich bepreist. |
| \`cost_settled\` | numeric(15,4) | ja | hoch | nein | Der tatsächlich berechnete Betrag, zur Schreibzeit materialisiert. |
| \`system_prompt_hash\` | bytea(32) | ja | hoch | nein | Referenz, niemals der Text. |
| \`build_sha\` | text(40) | ja | niedrig | nein | Lag dieser Run vor dem Fix. |
| \`config_snapshot\` | jsonb | ja | mittel | vielleicht | Geltende Policy, inklusive ob eine Freigabe erforderlich war. |
| \`approval_ref\` | uuid | **ja** | hoch | nein | NULL bedeutet "keine Freigabe durch die geltende Policy erforderlich". |
| \`iteration_count\`, \`tool_call_count\` | int4 | nein | hoch | nein | Form des Runs, ohne seine Steps zu lesen. |

Elf davon brauchen mehr als einen Satz.

**Prägen Sie \`run_id\` bei Dispatch.** Ein echter Bug: MCP-seitige Task-Claim-Zeilen wurden geschrieben, bevor die Execution-Zeile existierte, sodass eine Hibernate-generierte id \`task_id\` still NULL ließ. Der Fix reicht eine explizite Execution-id durch den Dispatch-Call durch und verwendet sie als Primary Key (\`AgentObservabilityRequest.executionId\`, im Code dokumentiert als "stable correlation ID minted at dispatch").

**Der Sub-Agent-Call-Tree braucht vier Felder, nicht eines:** Parent-Run, Caller-Agent, Depth und den exakten Tool-Call im Parent. Lassen Sie eines davon weg, und ein Multi-Agent-Run liest sich als unordnbarer flacher Haufen.

**Zwei Zeitstempel, nicht einer plus eine Dauer.** Eine Dauer kann nicht gegen eine externe Ereignis-Timeline abgeglichen werden. Dies ist auch die einzige Feldform, die der AI Act selbst benennt: Art. 12(3)(a) verlangt "recording of the period of each use of the system (start date and time and end date and time of each use)".

**Berechnetes und ausgeführtes Modell können abweichen.** Ein Routing-Layer kann ein berechnetes \`(provider, model)\`-Paar an ein anderes Ausführungsziel senden und dabei die berechnete Identität in der Response bewahren (\`V365__create_model_execution_links.sql\`). Ein Trail, der nur eines aufzeichnet, ist falsch darüber, was den Output erzeugt hat.

**\`model_snapshot\`** friert die Preisliste bei Ausführungsbeginn ein:

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

Ungefähr 260 bytes, etwa 905 MB/year bei 10k runs/day, etwa ein Dollar pro Jahr an Block-Storage. Es existiert, damit Kosten eine Modell-Deprecation mitten im Run und rückwirkende Preisänderungen überleben, und es ist das Feld, das Ingenieure zuerst streichen und am härtesten bereuen.

**\`cost_settled\` wird zur Schreibzeit materialisiert.** Die Neuberechnung aus Tokens mal Preis zur Lesezeit ist der *Fallback*, den \`model_snapshot\` ermöglicht, nicht der Record; jede spätere Abweichung ist selbst ein Befund.

**\`terminal_category\` wird materialisiert gespeichert, obwohl es** aus \`stop_reason\` ableitbar ist, derzeit durch generierten Contract-Code (\`AgentStopReason.valueOfOrError(x).terminal()\`). Codegen ändert sich; ein in sieben Jahren lesbarer Trail kann nicht vom Build dieses Monats abhängen, sonst re-klassifizieren sich alte Zeilen still selbst.

**\`build_sha\`** (~40 bytes) ist das am häufigsten fehlende und am häufigsten benötigte Feld. Falle: \`.git\` liegt meist nicht im Docker-Build-Context, sodass die laufende Version einen statischen Platzhalter meldet, sofern der Commit nicht als Build-Arg übergeben wird.

**Speichern Sie niemals den System-Prompt-Text pro Run.** Bei 10k runs/day ist ein 6 KB System-Prompt 20.89 GB/year an reiner Duplizierung, und diese Plattform speichert ihn bis zu dreimal pro Run (die Spalte \`agent_executions.system_prompt TEXT\`, eine Kopie in \`agent_config_snapshot\` JSONB und nochmals als SYSTEM-Rollen-Zeile in \`agent_execution_messages\`), sodass 20.89 GB/year die Untergrenze ist, nicht das Gesamt. Speichern Sie jeden distinkten Prompt einmal pro Version, referenzieren Sie per Hash. Es ist aber nicht der größte vermeidbare Posten: der duplizierte Tool-Result-Speicher (im Begleitartikel zur Retention quantifiziert) ist 83.55 GB/year, viermal größer. Diese beiden, 83.55 GB/year an Tool-Results und dann 20.89 GB/year an System-Prompts, sind die einzigen vermeidbaren Posten über 10 GB/year in diesem Modell.

**\`trail_seq\` kommt aus einer dedizierten Sequenz, nicht aus \`created_at\`.** Es übersteht Clock-Skew, einen Restore in eine andere Zeitzone und zwei in derselben Millisekunde geschriebene Zeilen. Lücken sind akzeptabel und sollten als solche dokumentiert werden; Monotonie ist die zugesicherte Eigenschaft. \`V169__trigger_lifecycle_invariants.sql\` zeigt das Muster: es ordnet History nach \`(trigger_id, trigger_type, seq DESC)\` und hält einen \`created_at DESC\`-Index nur für die Time-Window-Ops-Query.

**\`prev_row_hmac\` ist die Grenze** zwischen einem Observability-Log und einem Audit Trail. Der HMAC jeder Zeile deckt den eigenen Content plus den der Vorzeile ab, sodass eine stille Bearbeitung oder Löschung die Kette bricht. Der Header von \`V195__create_organization_audit_event.sql\` dieser Plattform listet ihn als bewusst aus diesem MVP weggelassen, neben einer Retention-Bereinigung unter einem verteilten Lock, einem WORM-Mirror und einer Append-only-Rollentrennung. Diese Liste dient zugleich als Reifegrad-Checkliste.

## Das Schema auf Step-Ebene

Eine Zeile pro LLM-Turn, Tool-Call, Entscheidung oder Signal. Step-Zeilen übersteigen Run-Zeilen etwa im Verhältnis 26 zu 1 und tragen die gesamte Payload, sodass ihr Retention- und Personenbezugs-Profil vollständig anders ist.

| Feld | Typ | Null | Kardinalität | Personenbezug | Warum es existiert |
|---|---|---|---|---|---|
| \`run_id\` | uuid | nein | hoch | nein | Parent-Join-Key. |
| \`tenant_id\`, \`organization_id\` | text / uuid | nein | mittel | indirekt | Auf **jeder** Child-Zeile, für org-skopierte Löschung. |
| \`step_seq\` | int4 (writer-assigned) | nein | hoch | nein | Deterministische Ordnung. Niemals aus \`created_at\` abgeleitet. |
| \`iteration_seq\` | int4 (writer-assigned) | nein | mittel | nein | Zu welchem LLM-Turn dies gehört. |
| \`parallel_index\` | int2 | **ja** | niedrig | nein | NULL bedeutet sequenziell. Unterscheidet einen nebenläufigen Batch von einer kausalen Kette. |
| \`step_kind\` | enum | nein | niedrig | nein | llm_turn / tool_call / decision / signal / message. |
| \`tool_name\` | text | ja | **niedrig** | nein | Das GROUP BY für "was tut dieser Agent tatsächlich". |
| \`tool_call_id\` | text | ja | hoch | nein | Korreliert Request mit Result über Retries und Umordnungen hinweg. |
| \`args_digest\` | bytea(32) | ja | hoch | nein* | Eine erzeugte Payload beweisen oder widerlegen, ohne sie aufzubewahren. |
| \`result_digest\` | bytea(32) | ja | hoch | nein* | Dasselbe, für Results. |
| \`content_length\` | int4 | ja | hoch | nein | Wie groß die Payload **war**, aufbewahrt, nachdem sie weg ist. |
| \`payload_ref\` | uuid | ja | hoch | nur Pointer | Ausgelagerter Blob oberhalb der Inline-Schwelle. |
| \`content\` | text | ja | hoch | **ja** | Inline-Payload, auf der kurzen Uhr. |
| \`error_code\` | enum | ja | niedrig | nein | Maschinenlesbare Fehlerklasse. Volles Fenster. |
| \`error_message\` | text | ja | hoch | **ja** | Freitext. Payload-Uhr. |
| \`branch_taken\` | text (Port-Label) | ja | niedrig | nein | Welcher ausgehenden Kante der Run gefolgt ist. |
| \`skip_reason\` | text | ja | niedrig | nein | Warum ein Node **nicht** lief. |
| \`skip_source_node\` | text | ja | mittel | nein | Welche vorgelagerte Entscheidung ihn übersprang. |
| \`redaction_applied\` | int2 (Bitmaske) | nein | niedrig | nein | Welche Redaction-Regeln gefeuert haben. |
| \`prompt_tokens\`, \`completion_tokens\`, ... | int4 | **ja** | hoch | nein | Nur geschrieben, wenn ungleich null, sodass NULL seine Bedeutung behält. |
| \`duration_ms\` | int8 | ja | hoch | nein | Schreibt ein Run-Level-Timeout dem Step zu, der das Budget verbraucht hat. |

\\* Ein Digest ist nur dann keine personenbezogene Angabe, wenn der Payload-Raum nicht aufzählbar ist (siehe die Einschränkung unten).

Die fünf Token-Zähler sind auf dem Run-Header NOT NULL default 0 (ein Run hat immer einen Gesamtwert), aber auf Step-Zeilen nullable, wo NULL "nicht anwendbar" bedeutet (eine Tool-Call-Zeile hat keine Tokens), nicht null. Summieren Sie Steps gegen den Header unter Beachtung dieser Regel, sonst widersprechen sich die beiden.

**\`parallel_index\` kostet vier bytes** und verhindert den schlimmsten Trail-Fehler: die Rekonstruktion einer kausalen Kette aus einem parallelen Batch, was schlimmer ist als eine Lücke, weil es selbstsicher falsch ist.

**\`args_digest\` und \`result_digest\` sind der Angelpunkt des Retention-Designs.** 32 B pro Digest; die 6 Tool-Call-Zeilen tragen zwei, die 14 Message-Zeilen tragen einen, also 832 bytes pro Run, 2.83 GB/year bei 10k runs/day. Behalten Sie den Digest für das volle Pflichtfenster, die Payload auf einer kurzen Uhr: Wenn jemand ein Dokument vorlegt und behauptet, der Agent habe es gesehen, beweist oder widerlegt der Digest dies bei null aufbewahrter Payload.

Die Einschränkung, unverblümt: **bei einem kleinen aufzählbaren Eingaberaum (eine Postleitzahl, ein Geburtsdatum) ist der Digest re-identifizierbar** und muss mit einem separat gehaltenen Key gesalzen werden. Die Regel lautet "veröffentliche niemals einen ungesalzenen Digest eines Feldes mit niedriger Entropie", nicht "Digests sind nicht personenbezogen". Die [EDPB-Pseudonymisierungsleitlinien](https://www.edpb.europa.eu/system/files/2025-01/edpb_guidelines_202501_pseudonymisation_en.pdf) halten fest, dass einfaches Hashing ohne Domänentrennung und Zugriffskontrolle unzureichend ist (Konsultationsentwurf Januar 2025).

**\`content_length\` wird bedingungslos vor der Entscheidung gesetzt, inline zu speichern, auszulagern oder zu truncaten**, was einem künftigen Leser sagt, dass Truncation stattgefunden hat und wie viel er nicht sieht (\`AgentObservabilityService\`, \`CONTENT_INLINE_THRESHOLD = 8192\`):

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

**Trennen Sie \`error_code\` von \`error_message\`.** Freitext-Nachrichten sind nicht abfragbar, instabil über Library-Upgrades hinweg und geben routinemäßig die Eingabe wieder, die den Fehler verursacht hat, was sie zum riskantesten Personenbezugs-Feld im Trail macht, während sie wie Diagnostik aussehen. Der Code wird für das volle Fenster aufbewahrt; die Nachricht läuft auf der Payload-Uhr.

**\`branch_taken\` macht den Trail auf Papier abspielbar** statt durch Neuausführung; in einer Workflow-Engine sind die Ports ein geschlossenes Set niedriger Kardinalität pro Node-Art (\`if\` / \`else\` / \`elseif_N\`, \`case_N\` / \`default\`, \`body\` / \`iterate\` / \`exit\`, \`branch_N\`). Zeichnen Sie auch auf, warum ein Node **nicht** lief: \`skip_reason\` plus \`skip_source_node\` machen das Negative zu einem erstrangigen Fakt, sodass ein übersprungener Branch von einem nie erreichten unterscheidbar ist.

**\`redaction_applied\` sind zwei bytes**, die drei Zustände trennen, die ein bloßer Trail vermengt: Payload sauber, Payload redigiert oder Redactor deaktiviert. Ohne dies ist ein sauber aussehender Trail beweisrechtlich wertlos. Der \`ToolCallRedactor\` dieser Plattform ist zweischichtig (ein Regex auf Secret-Feldnamen plus eine Credential-Tool-Allowlist, die den gesamten Argument-Body leert) und persistiert keinen Marker dafür, welche Schicht gefeuert hat; das ist die Lücke, die dieses Feld schließt.

## Der Freigabe-Record ist seine eigene Zeile, und sein schwierigstes Feld ist, was der Mensch gesehen hat

Human-in-the-loop ist das eine, das der AI Act für die von ihm erfassten Systeme aufzählt, und das eine, für das OTel kein Attribut hat. Art. 12(3)(d) verlangt für Annex-III-point-1(a)-Systeme "the identification of the natural persons involved in the verification of the results", auf die in Art. 14(5) verwiesen wird.

Ein brauchbarer Freigabe-Record (das \`orchestrator.workflow_signal_waits\` dieser Plattform):

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

**Das Feld, das niemand aufzeichnet, ist, was der Freigebende tatsächlich gesehen hat.** \`approval_context\` ist das Context-Template des Nodes, gerendert gegen den Ausführungskontext **eingefroren im Moment der Pause**, mit dem Signal persistiert und dann wörtlich in den resolved Node-Output zurückgegeben, sodass es den Übergang von awaiting zu resolved übersteht (Migration \`V373\`, die \`approval_context\` zur Signal-Wait-Tabelle hinzufügt).

**\`approval_ref\` auf der Run-Zeile ist nullable, und NULL muss "keine Freigabe war durch die geltende Policy erforderlich" bedeuten**, ein anderer Fakt als "Freigabestatus unbekannt". Das setzt voraus, dass die Policy-Version aus \`config_snapshot\` wiederherstellbar ist.

**Identitäts-Defaults müssen sichtbar von echten Identitäten unterscheidbar sein.** Hier fällt \`resolved_by\` auf das Literal \`"system"\` zurück, wenn es im Node-Output null ist, und auf \`"api"\`, wenn der vorgelagerte User-Header fehlt. In Ordnung, solange nie ein Mensch \`api\` heißen kann.

**Die Dimensionierung einer Identitätsspalte ist ein Audit-Anliegen.** \`resolved_by\` war \`VARCHAR(100)\`, bis föderierte Identifier der Form \`b:org:user\` (~120 chars) es überliefen, die Resolve-Transaktion zurückrollten und Freigaben für immer in \`CLAIMED\` stecken ließen, ununterscheidbar von echt ausstehenden (\`V191__signal_waits_widen_resolved_by.sql\`).

**Delegierte Freigaben brauchen ihr eigenes Zustellungs-Ledger.** \`orchestrator.approval_channel_deliveries\`: ein Einmal-Callback-Token (\`VARCHAR(64) UNIQUE\`), Status (\`PENDING\`, \`SENT\`, \`FAILED\`, \`RESOLVED\`, \`CANCELLED\`), der tatsächlich gesendete Nachrichtentext, eine Allowlist erlaubter User und \`UNIQUE (signal_wait_id, channel)\` als Replay-Schutz. Identität ist dann ein namespaced String wie \`telegram:<fromId>\`.

**Aufgezeichnete Absicht ist keine durchgesetzte Kontrolle, und der Trail sollte nichts anderes suggerieren.** Hier wird \`approverRoles\` in der Signal-Config aufgezeichnet und dem Freigebenden angezeigt, aber der In-App-Resolve-Endpoint erzwingt nur den Run-Scope, nicht die Rollenzugehörigkeit. Wenn Ihr Trail eine Rolle aufzeichnet, die er nicht geprüft hat, sagen Sie es in der Dokumentation des Feldes.
`;

export default content;
