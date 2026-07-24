// ai-agent-audit-log-retention - de
// Translated from the English body; structure identical to it. Legal scope
// statements and every "at least 6 months"/"out of scope"/"not legal advice" are
// load-bearing and must not be strengthened or softened. Fenced code stays fenced.
const content = `Looking at the source, the German translation has a spurious duplicate \`## Die Rechnung...\` heading inserted before the intro (making 6 level-2 headings instead of 5), plus two grammar errors. Everything else checks out against the counts, tables, links, and legal force. Here is the corrected body.

Ein Begleitartikel veröffentlicht das Feld-Schema auf Run- und Schritt-Ebene, das dieser hier bepreist: Die Feldnamen, Typen und Kardinalitätsklassen, auf die sich die Tabellen unten beziehen, sind dort definiert. Dieses Stück beantwortet die drei Fragen, die das Schema offenlässt. Wie viele Bytes kostet der Trail tatsächlich? Wie lange muss jedes Feld aufbewahrt werden? Und trifft irgendetwas davon rechtlich auf Sie zu, was für die meisten Leser nicht der Fall ist.

Die durchgehend zitierte Referenzimplementierung ist die eigene Plattform dieses Blogs: echte Spaltennamen, echte Migrationen, echte Bugs.

## Die Rechnung, damit die Staffelung abgeleitet und nicht behauptet ist

Alles Folgende ist ein **Modell**, keine Messung. Die Eingaben sind angegeben, damit Sie es mit Ihren eigenen Zahlen erneut durchrechnen können. Zeilengrößen sind analytisch, abgeleitet aus DDL-Spaltentypen plus dokumentiertem Postgres-Overhead; echte Tabellen laufen ungefähr 10-25% größer, sobald Fillfactor, freier Speicher und Bloat einbezogen sind, lesen Sie also jede abgeleitete Zahl unten als "+10 bis 25% in Produktion" (die pauschale Sieben-Jahres-Full-Capture-Zahl, 1,68 TB im Modell, liegt am oberen Ende dieses Bereichs bei etwa 2,1 TB).

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

Die Metadaten-only-Zahl, von der der Rest des Modells skaliert, ist 9,05 KB/Run, abgeleitet als:

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

| Erfassungsebene | Bytes/Run | MB/Tag @10k Runs | GB/Jahr | GB über 7 Jahre | Komprimierte GB/Jahr |
|---|---|---|---|---|---|
| Nur Metadaten | 9.05 KB | 88.38 | 31.50 | 220.51 | 31.50 (unkomprimiert in PG; archiviert sich gut) |
| Metadaten + Digests (~832 B/Run) | 9.86 KB | 96.29 | 34.33 | 240.31 | 34.33 |
| Vollerfassung | 70.43 KB | 687.78 | 245.16 | 1,716 (1.68 TB) | 92.6-117 |

Vollerfassung ist das 7,8-fache von Nur-Metadaten. Kompression nimmt 2,5-3,5x auf Payloads oberhalb von Postgres' ~2 kB (2048-Byte) TOAST-Schwelle an, ein typischer veröffentlichter Bereich statt einer Messung an diesem Korpus, sodass die komprimierte Vollerfassungszahl 92,6 bis 117 GB/Jahr umspannt, je nachdem, wo in diesem Bereich Sie landen.

Eine Eingabe dominiert das Ergebnis:

| Mittleres Tool-Ergebnis | KB/Run (Vollerfassung) | GB/Jahr @10k Runs/Tag | Agent-Form, die hier lebt |
|---|---|---|---|
| 1 KB | 34.43 | 119.84 | Klassifizierung, Routing, kurze API-Abfragen |
| 4 KB | 70.43 | 245.16 | Gemischte Tool-Nutzung, das Modell oben |
| 8 KB | 118.43 | 412.24 | Dokumentenerstellung, Multi-Record-CRUD |
| 20 KB | 262.43 | 913.50 | Suche, Datei-Lesen, SQL-lastige Agenten |

Prompts und Completions machen 20% der Payload bei einem mittleren Tool-Ergebnis von 4 KB aus (12,8 KB von 61,38 KB) und fallen bei 20 KB auf etwa 5% (12,8 KB von 253,38 KB), Tool-Ergebnisse sind also der Ort, an dem sich Staffelung auszahlt. **Wenn Sie eine Sache staffeln, staffeln Sie Tool-Ergebnisse.**

Nun die Umkehrung, die den ganzen Abschnitt motiviert. 245 GB/Jahr sind etwa **$235/Jahr** an gp3-Blockspeicher, **$68/Jahr** auf S3 Standard, **$12/Jahr** auf Glacier Instant Retrieval; nur Metadaten sind etwa $30/Jahr. (Listenpreise us-east-1 in Größenordnungen, ohne Request- und Retrieval-Gebühren; die kalten Ebenen nehmen nahezu null Lesevolumen an.) **Niemand kürzt seinen Trail, um $200 zu sparen.**

Was die Dollarzahl verbirgt, sind die wirklichen Kosten: **98,55 Millionen Zeilen/Jahr** (689,85 Millionen über sieben Jahre) an Löschfläche, Indexpflege und Wiederherstellungszeit, plus die Tatsache, dass jedes aufbewahrte Byte an Prompt und Tool-Ergebnis Haftung ist. Gestalten Sie die Staffelung um Blast Radius und Zeilenzahl herum.

Bei 1M Runs/Tag beißt die operative Obergrenze weit vor der Speicherrechnung: ~54M Index-Inserts/Tag, 9,86 Milliarden Zeilen/Jahr, 23,94 TB/Jahr an Vollerfassung und ungefähr 140 Stunden, um ein Jahr logisch bei 50 MB/s wiederherzustellen. Die Skelett-Ebene ist das, was einen Trail *wiederherstellbar* hält, nicht nur bezahlbar.

Eine kostenlose Einsparung, gefunden durch das Lesen des Schemas statt des Codes: **das Tool-Ergebnis wird häufig zweimal persistiert**, einmal als Inhalt der Tool-Call-Zeile und erneut als Inhalt der entsprechenden Tool-Role-Message-Zeile. Speichern Sie die Payload einmal und lassen Sie die Message-Zeile denselben \`payload_ref\` tragen, und die Payload sinkt von 61,38 KB auf 37,38 KB pro Run, von 245,16 GB/Jahr auf 161,61 GB/Jahr. Jeder Trail mit sowohl einer Tool-Call-Tabelle als auch einer Message-Tabelle hat diese Form. (Die Beobachtung auf Schema-Ebene ist solide; die genaue Überlappungsrate in Produktion wurde nicht gemessen.)

## Aufbewahrungsebenen, jede gerechtfertigt durch die Entscheidung, die sie stützt

| Ebene | Inhalt | Fenster | GB/Jahr | Frage, die sie beantwortet | Gesampelt oder degradiert? |
|---|---|---|---|---|---|
| 0 Skelett | Run-Header ohne allen Text; Schritt-Metadaten (\`step_seq\`, \`tool_name\`, \`branch_taken\`, Status, \`stop_reason\`, Dauern, Token-Zählungen, \`content_length\`, alle Digests) | Volles Pflichtfenster (7 Jahre modelliert) | 31.50 | Fand dieser Run statt, wann, wer löste ihn aus, was tat er, wie verzweigte er, was kostete er | **Nie** |
| 1 Digests und Codes | \`args_digest\`, \`result_digest\`, \`error_code\`, \`redaction_applied\`, \`model_snapshot\` | 12-24 Monate | 34.33 | Beweisen oder widerlegen, dass der Agent ein erzeugtes Dokument sah; einen strittigen Run zu den geltenden Preisen neu kalkulieren | **Nie** |
| 2 Tool-Argumente und -Ergebnisse | \`content\`, \`payload_ref\` für Tool-Schritte | 30-90 Tage heiß, dann gesampelt | ~80% der Payload-Bytes | Eine Live-Regression debuggen; eine Kundenbeschwerde beantworten | Ja, nach dem heißen Fenster |
| 3 Prompts und Completions | Message-Inhalt | 30 Tage, **plus 100% der fehlgeschlagenen oder guardrail-ausgelösten Runs jeden Alters** | siehe unten | Die Argumentation einer strittigen Entscheidung rekonstruieren | Nur ungleichmäßig |
| 4 Prompt-Templates | System-Prompts, Prompt-Text nach Version | Für immer (Kilobytes) | ~0 | Welche Prompt-Version lief | Nie nach einer Pro-Run-Uhr |

Ebene 0 über sieben Jahre sind 220,51 GB, etwa **$10,60/Jahr** auf Glacier Instant Retrieval (220,51 GB x $0,004/GB-Monat x 12). Das beantwortet die meisten Prüferfragen, während null Bytes personenbezogener Daten aufbewahrt werden.

Die Sampling-Regel von Ebene 3 ist diejenige, über die zu streiten sich lohnt, und der Regler berührt nur je die Ebenen 2 und 3 (Invariante 1: Audit-Datensätze werden nie gesampelt). Bei einer angenommenen Fehlerrate von 8% behält das Aufbewahren aller Fehler plus 5% der Erfolge 12,6% der Runs (0,08 + 0,92 x 0,05 = 0,126). Angewandt allein auf die Payload-Ebenen (Vollerfassung minus der 31,50-Skelett- und 2,83-Digest-Ebenen, also 210,83 GB/Jahr), behält das 26,56 GB/Jahr an Payload; mit den Ebenen 0 und 1 auf 100% gehalten, fällt das residente Volldetail von 245,16 auf etwa **60,9 GB/Jahr** (31,50 + 2,83 + 26,56), während jeder Run behalten wird, nach dem irgendjemand tatsächlich fragen wird. Gleichmäßiges Sampling optimiert für die Runs, die niemand untersucht.

Kombinierter Plan, pro Ebene:

\`\`\`
30 days full capture:   20.15 GB gp3           $19.34
365 days digests:       34.33 GB S3 Standard    $9.47
7 years skeleton:      220.51 GB Glacier IR    $10.58
resident total:        274.99 GB             ~ $39/year
\`\`\`

Das sind 274,99 GB resident gegenüber 1,68 TB für pauschale Vollerfassung über sieben Jahre gehalten, eine 6,2-fache Reduktion, ungefähr $39/Jahr gegenüber $1.647/Jahr an pauschalem gp3. Die Einsparung, die zählt, ist nicht das Geld: **nur 30 Tage Payload mit personenbezogenen Daten sind je in Reichweite eines Löschbegehrens statt sieben Jahre.**

Heiß-plus-kalt ist die Form, die Regulierer bereits kodifizieren. PCI DSS 4.0 Anforderung 10.5.1 verlangt 12 Monate mit den jüngsten 3 sofort verfügbar; SEC Rule 17a-4 sechs Jahre mit den ersten zwei leicht zugänglich. (Beides wie angegeben bestätigbar.)

Das zu benennende Anti-Pattern: die weit verbreitete **progressive Degradationsleiter**, die Prompt- und Completion-Inhalt nach dem ersten Jahr verwirft und ab dem dritten Jahr nur Metadaten behält. Sie degradiert Inhalt genau über das Fenster, in dem ein Prüfer ihn braucht, und lässt ein Unternehmen "sieben Jahre Audit-Logs" behaupten, während nichts aufbewahrt wird, das eine einzelne Entscheidung erklärt.

## Was Sie tatsächlich schulden, und warum es wahrscheinlich nichts ist

| Instrument | Artikel / Kontrolle | Bindet wen | Was es tatsächlich verlangt | Aufbewahrung | Spezifiziert Felder? |
|---|---|---|---|---|---|
| EU AI Act | [Art. 12(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-12) | Hochrisiko-**Systeme** (Design-Anforderung) | Systeme "shall technically allow for the automatic recording of events (logs) over the lifetime of the system" | n/a | **Nein** |
| EU AI Act | Art. 12(2)(a)-(c) | wie oben | Nur die *Zwecke*: Risiko nach Art. 79(1) oder wesentliche Änderung; Post-Market-Monitoring nach Art. 72; Betriebsüberwachung nach Art. 26(5) | n/a | **Nein** |
| EU AI Act | Art. 12(3)(a)-(d) | **nur Annex III Punkt 1(a)** (biometrische Fernidentifizierung) | Zeitraum jeder Nutzung; überprüfte Referenzdatenbank; Eingabedaten, deren Suche zu einem Treffer führte; Identifizierung der Personen, die Ergebnisse überprüfen | n/a | **Ja, der einzige Ort** |
| EU AI Act | [Art. 19(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-19) | **Anbieter** | Art. 12(1)-Logs aufbewahren "to the extent such logs are under their control" | **mindestens 6 Monate** | Nein |
| EU AI Act | [Art. 26(6)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-26) | **Betreiber** | Dieselbe Pflicht, dieselbe Begrenzung, separate Uhr | **mindestens 6 Monate** | Nein |
| EU AI Act | [Art. 18(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-18) | Anbieter | Technische Dokumentation, QMS-Dokumentation, Entscheidungen benannter Stellen, EU-Konformitätserklärung | **10 Jahre** nach Inverkehrbringen oder Inbetriebnahme | n/a |
| EU AI Act | [Art. 86](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-86) | Betreiber | "Clear and meaningful explanations of the role of the AI system in the decision-making procedure and the main elements of the decision taken" | n/a | **Nein** |
| ISO/IEC 42001 | die Ereignis-Logging-Kontrolle in Annex A | Freiwillig | Ereignis-Logs plus Überwachungsdatensätze, die zeigen, dass Logging betriebsbereit ist | keine vorgeschrieben | **Nein** |
| NIST AI RMF | MEASURE 2.8, MANAGE 2.4, MANAGE 4.3 | Freiwillig | Historien und Audit-Logs instrumentieren und pflegen; Materialien für forensische, regulatorische und rechtliche Prüfung bewahren; Vorfall- und System-Änderungsdatenbanken pflegen | keine vorgeschrieben | **Nein** |
| SOC 2 | 2017 TSC (2022 überarbeitete Points of Focus) | Vertraglich | Generischer Nachweis der Kontrollumgebung, angewandt auf Ihren Agenten | kriterienbasiert, kein Zeitraum | **Nein** |
| HIPAA | [45 CFR 164.316(b)(2)(i)](https://www.govinfo.gov/content/pkg/CFR-2023-title45-vol2/xml/CFR-2023-title45-vol2-sec164-316.xml) | Betroffene Einheiten | Erforderliche Dokumentation aufbewahren | **6 Jahre** | Nein |

Drei Aufspaltungen, die die meisten Zusammenfassungen falsch machen.

**Art. 12(1) ist eine Design-Anforderung an das System. Art. 19(1) setzt eine Sechs-Monats-Untergrenze für den Anbieter. Art. 26(6) setzt eine separate, parallele Sechs-Monats-Untergrenze für den Betreiber.** Sechs Monate werden zweifach von zwei verschiedenen Parteien geschuldet, nicht eine gemeinsame Uhr, beide mit derselben Begrenzung, "to the extent such logs are under their control".

**Sechs Monate sind die LOG-Untergrenze; zehn Jahre sind die DOKUMENTATIONS-Untergrenze.** Art. 18(1) und Art. 19(1) sind zwei verschiedene Regime, die routinemäßig vermengt werden.

**Die Pflicht, die tatsächlich pro Entscheidung Erklärbarkeit erzwingt, ist Art. 86, nicht Art. 12.** Eine betroffene Person, die einer Entscheidung unterliegt, die der Betreiber auf Grundlage der Ausgabe eines Hochrisiko-Systems nach Annex III (außer Punkt 2) trifft, die Rechtswirkungen erzeugt oder sie in ähnlich erheblicher Weise auf eine Art betrifft, die sie als nachteilige Auswirkung auf ihre Gesundheit, Sicherheit oder Grundrechte ansieht, hat ein Recht auf Erklärungen der Rolle des KI-Systems und der Hauptelemente der Entscheidung. Art. 86(3) macht dies subsidiär zu anderem Unionsrecht.

**Und nun die ehrliche Antwort für die meisten Leser: vollständig außerhalb des Anwendungsbereichs von Art. 12/19/26(6).** Hochrisiko bedeutet Art. 6(1) (Sicherheitskomponente eines Annex-I-Produkts, das eine Konformitätsbewertung durch Dritte erfordert) oder Art. 6(2) (die acht Bereiche von [Annex III](https://ai-act-service-desk.ec.europa.eu/en/ai-act/annex-3)). Ein Coding-Assistent, ein interner Recherche- oder Support-Agent, ein Dokumentenerstellungs-Agent fällt in keinen davon.

Das "es sei denn", das Leute erwischt, ist Annex III **Punkt 4** (Rekrutierung und Auswahl, gezielte Stellenanzeigen, Filtern von Bewerbungen, Bewerten von Kandidaten, Entscheidungen über Arbeitsbedingungen, Beförderung, Kündigung, Aufgabenzuweisung basierend auf Verhalten oder Merkmalen, Leistungsüberwachung) und **Punkt 5** (eine Teilliste seiner vier Unterpunkte, die zwei, die Entwickler am häufigsten erwischen: (b) Bewertung der Kreditwürdigkeit und Kredit-Scoring ausgenommen Betrugserkennung, und (c) Risikobewertung und Preisgestaltung für Lebens- und Krankenversicherung; die anderen zwei, (a) behördliche Bewertung der Anspruchsberechtigung für wesentliche öffentliche Unterstützungsleistungen und -dienste einschließlich Gesundheitsversorgung, und (d) Triage und Disposition von Notrufen, erwischen Govtech- und leistungsnahe Agenten).

Selbst ein Annex-III-System kann über die Ausnahme in [Art. 6(3)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-6) entkommen (enge prozedurale Aufgabe; Verbesserung einer zuvor abgeschlossenen menschlichen Tätigkeit; Erkennen von Mustern ohne Ersetzen der vorherigen menschlichen Bewertung; eine vorbereitende Aufgabe), aber **nie, wenn es Profiling natürlicher Personen durchführt**. Und Art. 6(4) sorgt dafür, dass die Ausstiegsluke ihren eigenen Papierkram erzeugt: die Bewertung vor dem Inverkehrbringen dokumentieren, plus eine Registrierungspflicht nach Art. 49(2).

Zwei Fallen für Entwickler. Einen Agenten rein für den internen Gebrauch zu bauen macht Sie nicht bloß zum Betreiber: [Art. 3(11)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-3) definiert Inbetriebnahme als Bereitstellung zur ersten Nutzung "or for own use", sodass ein internes Hochrisiko-System Art. 19, Art. 26(6) und Art. 18 gleichzeitig schulden kann. [Art. 25(1)(c)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-25) tut dasselbe mit jedem, der die Zweckbestimmung eines Allzweckmodells ändert, sodass das System zum Hochrisiko-System wird.

Die Sanktionsexponierung für Logging-Pflichten ist die mittlere Stufe, nicht die Schlagzeile: [Art. 99(4)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-99) beträgt bis zu EUR 15.000.000 oder 3% des weltweiten Jahresumsatzes, je nachdem, was höher ist. Er deckt Art. 16, 22, 23, 24, 26, 31, 33, 34 und 50 ab; Art. 19 ist selbst nicht aufgeführt, sodass der Verstoß eines Anbieters gegen die Log-Aufbewahrung über Art. 16(e) erreicht wird, der die Art.-19-Pflicht importiert, während der des Betreibers direkt Art. 26 ist. Die Stufe von 35 Millionen / 7% ist verbotenen Praktiken nach Art. 5 vorbehalten.

**Der Zeitplan hat sich verschoben.** Der Digital Omnibus zu KI verschiebt die Anwendungstermine für Hochrisiko auf den **2. Dezember 2027** für eigenständige (Annex III) Hochrisiko-Systeme und den **2. August 2028** für in regulierte Produkte eingebettete Hochrisiko-KI, laut dem [Rat der EU](https://www.eeas.europa.eu/delegations/chile/artificial-intelligence-council-gives-final-green-light-simplify-and-streamline-rules_en). Verfahrensstand Ende Juli 2026: EP-Plenarabstimmung 16. Juni 2026, Annahme durch den Rat 29. Juni 2026, unterzeichnet 8. Juli 2026, Veröffentlichung im Amtsblatt ausstehend ([EP Legislative Train](https://www.europarl.europa.eu/legislative-train/package-digital-package/file-digital-omnibus-on-ai)). Jeder Artikel, der noch den 2. August 2026 für Hochrisiko zitiert, ist veraltet. Der Omnibus ändert die Artikel 12, 19 oder 26(6) im vereinbarten Text nicht, wie jede veröffentlichte Analyse davon berichtet; die Sechs-Monats-Untergrenze ist unverändert. Bestätigen Sie es anhand des Amtsblatt-Textes, sobald er veröffentlicht ist.

Alt-Systeme können vollständig entkommen: [Art. 111(2)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-111) wendet die Verordnung auf Hochrisiko-Systeme, die vor der Umstellung in Verkehr gebracht wurden, nur an, wenn sie anschließend erheblichen Änderungen ihres Designs unterliegen; behördliche Betreiber haben bis zum 2. August 2030 Zeit.

Zwei Pflichten beißen unabhängig von der Risikostufe: [Art. 4](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-4) (KI-Kompetenz, anwendbar seit dem 2. Februar 2025, für Anbieter und Betreiber) und [Art. 50(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-50) (Anbieter müssen Systeme so gestalten, dass natürliche Personen informiert werden, dass sie mit einer KI interagieren, es sei denn, es ist offensichtlich), was ab dem 2. August 2026 gilt, zehn Tage nach Veröffentlichung dieses Stücks. Die Inhaltskennzeichnung nach Art. 50(2) erhält eine Übergangsfrist bis zum 2. Dezember 2026 für bereits auf dem Markt befindliche Systeme. Der Omnibus mildert Art. 4 von der Sicherstellung eines ausreichenden Kompetenzniveaus zur Förderung dessen Entwicklung unter den Mitarbeitern; das Datum 2. Februar 2025 ist unverändert, und bis zur Amtsblatt-Veröffentlichung ist der ursprüngliche Wortlaut noch das Bindende.

Und die Standards, die spezifizieren würden, *wie* Art. 12 zu erfüllen ist, existieren noch nicht: [CEN-CENELEC JTC 21](https://www.cencenelec.eu/news-events/news/2025/brief-news/2025-10-23-ai-standardization/) entwickelt noch die Standards zu Kapitel III Abschnitt 2, mit im Oktober 2025 beschlossenen Beschleunigungsmaßnahmen, die eine Verfügbarkeit um Q4 2026 anvisieren. Bis dahin ist es eine gesetzliche Pflicht ohne technische Spezifikation dahinter.

Auch die freiwilligen Rahmenwerke geben Ihnen kein Schema. [ISO/IEC 42001](https://www.iso.org/standard/81230.html) ist freiwillig (ISO zertifiziert keine Organisationen; akkreditierte Stellen tun das), und seine Annex-A-Kontrolle A.6.2.8, "AI system recording of event logs", schreibt weder eine Aufbewahrungsdauer noch eine Feldliste vor. [NIST AI RMF](https://www.nist.gov/itl/ai-risk-management-framework) ist ausdrücklich freiwillig und verhaltensbezogen. SOC 2 verwendet die Trust Services Criteria von 2017 mit 2022 überarbeiteten Points of Focus, und es wurden keine KI-spezifischen Kriterien herausgegeben, sodass ein Prüfer generischen Nachweis der Kontrollumgebung testet, angewandt auf Ihren Agenten.

Colorado ist eine Zeile wert, wenn Sie Einstellungen oder folgenreiche Entscheidungen berühren. SB 26-189 wurde laut der [Bill-Seite](https://leg.colorado.gov/bills/sb26-189) am 14. Mai 2026 unterzeichnet, wirksam ab dem 1. Januar 2027; es hebt den Colorado AI Act von 2024 auf und erlässt ihn neu. Der Anwendungsbereich ist automatisierte Entscheidungstechnologie, die in folgenreichen Entscheidungen eingesetzt wird (Bildung, Beschäftigung, Wohnen, Finanz/Kreditvergabe, Versicherung, Gesundheitsversorgung, wesentliche staatliche Dienste). Entwickler und Betreiber müssen Compliance-Aufzeichnungen mindestens drei Jahre aufbewahren, für Betreiber laufend ab dem Datum der folgenreichen Entscheidung.

**Die Anti-Theater-Schlussfolgerung.** Wenn Sie außerhalb des Anwendungsbereichs sind, bauen Sie den Trail für die Fragen, die Ihnen tatsächlich gestellt werden: ein Kundenstreit, eine Vorfallprüfung, ein Rechnungsstreit, eine Sicherheitsuntersuchung. Dimensionieren Sie die Skelett-Ebene für die längste plausible künftige Pflicht, weil sie 31,50 GB/Jahr kostet. Lassen Sie dann sechs Monate eine Untergrenze sein, die Sie zufällig überschreiten, statt eines Arbeitsprogramms. Dies ist keine Rechtsberatung, und keines der oben genannten Aufbewahrungsregime sollte auf eine einzige Zahl reduziert werden, die auf Sie zutrifft.

## Personenbezogene Daten: der Trail, den Sie jahrelang behalten, und das Löschbegehren, das Sie morgen bekommen

**Eine pseudonyme Akteur-Referenz nimmt den Trail nicht aus dem Anwendungsbereich der GDPR.** Recital 26 behandelt Daten, die einer Person unter Verwendung zusätzlicher Informationen zugeordnet werden könnten, als personenbezogene Daten. Speichern Sie einen Token, der nur über eine separat kontrollierte Zuordnungstabelle zu einer Identität auflöst, und behaupten Sie nicht, der Trail sei anonym.

**Die Sechs-Monats-Untergrenze hat im selben Satz eine Obergrenze.** Art. 19(1) und Art. 26(6) enden beide mit "unless provided otherwise in the applicable Union or national law, in particular in Union law on the protection of personal data". Alles für immer aufzubewahren ist nicht die konforme Antwort, es ist ein separater Verstoß.

**Die Design-Antwort ist der Digest-Pivot:** die lange Ebene hält Hashes, Codes, Zählungen und Klassifizierungen, keine Payload. Das ist es, was ein Sieben-Jahres-Skelett verteidigbar macht statt zu einer Sieben-Jahres-Haftung.

**Setzen Sie \`tenant_id\` und \`organization_id\` auf jede Kindzeile, nicht nur auf die Elternzeile.** Löschung läuft als org-scoped DELETEs pro Tabelle; Zeilen, die nur eine \`execution_id\` tragen, brauchen einen Join, und jede Zeile, deren Eltern bereits weg sind, überlebt als unerreichbarer Orphan, der noch personenbezogene Daten hält. Der \`WorkspaceDataPurger\` dieser Plattform gibt ein org-scoped DELETE gegen \`agent_execution_tool_calls\` aus, gekeyt auf \`organization_id\` (und Äquivalente), was nur funktioniert, weil \`V210\` die Spalte zu allen fünf Agent-Runtime-Tabellen hinzufügte und vier davon backfillte (\`agent_tasks\`-Zeilen bleiben by design NULL, ein personenbezogener Scope).

**Teilen Sie den Trail in eine löschbare operative Schicht und eine nicht löschbare Ledger-Schicht auf**, und lassen Sie die Löschung nur die erste betreffen. Die Referenzimplementierung löscht 31 deklarierte org-scoped Tabellen (\`PURGED_ORG_SCOPED_TABLES\`) plus die Agent-Execution-Kindtabellen, die sie direkt trifft (Messages, Tool-Calls, Iterationen), während sie \`auth.credit_ledger\`, \`auth.usage_cycle\`, \`auth.credit_reconciliation_log\` oder \`auth.organization_audit_event\` nie berührt, und behält die Organisationszeile als Tombstone, damit Ledger-Referenzen gültig bleiben. Ein Coverage-Test bestätigt sowohl das Org-Scoping jeder Anweisung als auch das Nicht-Löschen der aufbewahrten Tabellen. Die ehrliche Grenze: das überlebende Ledger beweist noch, dass die Runs eines Subjekts existierten und was sie kosteten, sodass dies Minimierung nur erfüllt, wenn das Ledger keine Payload und nur pseudonyme Identifikatoren trägt.

**Löschung, die nicht löscht.** Wenn große Payloads in Objektspeicher ausgelagert werden und die Zeile einen Pointer behält, **verwaist das Löschen der Zeile den Blob**. Die personenbezogenen Daten überleben das Löschbegehren, unreferenziert und daher unsichtbar für jede spätere Prüfung dessen, was Sie halten. Der obige Purger dokumentiert genau diesen Orphan in seinem eigenen Javadoc: er löscht die \`storage.storage\`-Zeilen, aber nicht die zugrunde liegenden S3/MinIO-Objekte. Lösung: Machen Sie den Payload-Speicher zum Löschziel und die Zeile zum Pointer, und gleichen Sie Orphans nach einem Zeitplan ab.

**Entscheiden Sie, ob Redaktion beim Schreiben oder beim Lesen erfolgt, und halten Sie fest, welche.** Ein Redaktor, der nur läuft, wenn Zeilen einem Prüfer angezeigt werden, lässt rohe Zugangsdaten in den gespeicherten Tool-Argumenten sitzen (der aktuelle Stand hier: \`ToolCallRedactor\` ist ein Read-Path-Filter). Ein Redaktor zur Schreibzeit zerstört Beweise, die Sie brauchen könnten. Was auch immer Sie wählen, \`redaction_applied\` ist das, was die Wahl prüfbar macht.

**Das ungelöste Muster, das umzusetzen sich lohnt:** gelöschten Inhalt tombstonen, während dessen Digest aufbewahrt wird, sodass die manipulationssichere Kette eine Löschung überlebt und ein späterer Leser noch feststellen kann, dass etwas da war, wie groß es war und dass es unter einem Rechtsbegehren entfernt statt verloren wurde.

## Zwei Fehler, die es wegzugestalten gilt, und was mit OpenTelemetry zu tun ist

**Aufbewahrung, die Sie nicht rückwirkend verlängern können.** An dem Tag, an dem Sie entdecken, dass das Fenster länger ist als Ihr Purge-Cron, sind die Daten weg. Ein Team hier, das ein Lifecycle-Audit-Log von 30 auf 365 Tage anhob, traf beim ersten Purge danach auf einen 12-fachen Rückstau, und das war die *glückliche* Richtung. Setzen Sie die Skelett-Ebene am ersten Tag auf die längste plausible Pflicht; bei 31,50 GB/Jahr ist sie die billigste Versicherung im System. (Verwandt: ein dokumentierter Aufbewahrungskommentar, der "30d default" sagte, während der \`@Value\`-Default des Dienstes 365 war, ist die Art, wie dokumentierte und konfigurierte Aufbewahrung stillschweigend auseinanderdriften.)

**Fehler im Query-Pfad, die einen Trail unbrauchbar statt falsch machen.** Detailzeilen sind nicht der Query-Pfad: aggregieren Sie die niedrigkardinalen Dimensionen vorab in Rollups, gekeyt auf \`(tenant, date, provider, model)\` und \`(tenant, tool_name)\`. Postgres indiziert Fremdschlüssel nicht automatisch: eine 18k-Zeilen, 39 MB große Tool-Call-Tabelle hier, deren einziger Index ihr Primärschlüssel war, führte bei jedem Aggregat-Lesevorgang einen Full-Scan durch, bis \`V341\` einen \`CONCURRENTLY\`-Btree auf \`execution_id\` hinzufügte. Und unpaginierte Lesevorgänge von MB-großen Payload-Zeilen sind eine OOM-Form: begrenzen Sie die Seite (100 ist ein vernünftiges hartes Maximum) und geben Sie \`total\` / \`shown\` / \`truncated\` zurück, damit einem Leser gesagt wird, wenn ältere Zeilen verworfen wurden, statt stillschweigend einen partiellen Trail zu sehen.

Die Kardinalitätsregel, die aus den Schema-Tabellen folgt: **niedrigkardinale Felder** (\`status\`, \`stop_reason\`, \`provider\`, \`model\`, \`tool_name\`, \`trigger_source\`, \`branch_taken\`) sind das, wonach jede Frage gruppiert, und gehören in Rollups; **hochkardinale Felder** (\`run_id\`, \`tool_call_id\`, Digests) sind Join-Keys, die Btree-Indizes brauchen und nie in einen Rollup-Key eintreten dürfen.

### Das OpenTelemetry-Urteil

**Pinnen Sie noch kein Audit-Schema darauf.** Null \`gen_ai.*\`-Attribute sind Stable (99 Development, 0 Stable im Live-Registry), das [GenAI-semconv-Repo](https://github.com/open-telemetry/semantic-conventions-genai) hat keine Releases und keine Tags, und die Konventionen zogen aus dem Haupt-semantic-conventions-Repo weg, das nun jedes \`gen_ai.*\`-Attribut auf der [Legacy-Registry-Seite](https://opentelemetry.io/docs/specs/semconv/registry/attributes/gen-ai/) als "Deprecated" rendert, als Artefakt des Umzugs. Ein falsches Signal in beide Richtungen.

Umbenennungen haben Schemata bereits einmal gebrochen:

\`\`\`
gen_ai.system              -> gen_ai.provider.name (now absent)
gen_ai.usage.prompt_tokens -> gen_ai.usage.input_tokens
gen_ai.usage.completion_tokens -> gen_ai.usage.output_tokens
gen_ai.prompt / gen_ai.completion
   -> gen_ai.input.messages / gen_ai.output.messages
\`\`\`

OTel hat **kein Attribut** für eine menschliche Freigabe, eine Akteur- oder Principal-Identität, eine Policy- oder Guardrail-Entscheidung, eine Aufbewahrungsklasse oder monetäre Kosten (nur Token-Zählungen, kein \`gen_ai.cost.*\`). Das sind genau die audit-tragenden Felder, weshalb der Trail Ihre Tabelle ist und nicht Ihr Tracing-Backend.

Zwei Felder lohnen sich, wortwörtlich zu übernehmen, weil sie billig sind und echte Audit-Fragen beantworten: **\`gen_ai.prompt.name\` plus \`gen_ai.prompt.version\`** beweisen, welche Prompt-Version lief, ohne deren Text zu speichern, und **\`gen_ai.conversation.compacted\`** beantwortet, ob das Modell die volle Historie oder eine Zusammenfassung sah. Beachten Sie auch, dass \`gen_ai.provider.name\` ein Telemetrie-Format-Diskriminator ist, der auf einen Proxy zeigen kann, kein Beweis dafür, welcher Anbieter die Daten verarbeitete, und dass \`gen_ai.conversation.id\` nicht aus einer UUID, Trace-ID oder einem Content-Hash fabriziert werden darf, sodass es in vielen Trails legitim fehlt.

Span-Limits kürzen einen Trail stillschweigend: \`OTEL_SPAN_ATTRIBUTE_COUNT_LIMIT\` ist standardmäßig 128. Abgeflachte per-Message indizierte Attribute (die OpenInference-Form \`llm.input_messages.<i>.message.*\`) können das bei einer langen Konversation überschreiten, während ein einzelnes strukturiertes \`gen_ai.input.messages\` ein Attribut kostet. Das ist abgeleitete Rechnung, kein dokumentierter Vorfall. Strukturierte Attributwerte werden auch noch nicht universell auf Spans unterstützt, sodass dasselbe logische Feld in einem Backend ein JSON-String und in einem anderen ein Objekt ist.

Die eigene Produktionsempfehlung der Spezifikation ist die hier vertretene Architektur: Inhalt in externem Speicher mit separaten Zugriffskontrollen speichern und Referenzen auf Spans aufzeichnen, und den Upload-Hook "regardless of the span sampling decision" aufrufen. **Samplen Sie die Traces, samplen Sie nie die Beweise.** Das ist \`payload_ref\` plus Digest unter anderem Namen.

Schlussregel: **emittieren Sie OTel für das Dashboard, besitzen Sie eine Tabelle für den Trail, verbinden Sie sie über \`run_id\`, und halten Sie die beiden Aufbewahrungsuhren getrennt.**
`;

export default content;
