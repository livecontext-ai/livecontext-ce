// the-niche-data-advantage - de
// Translated from the English body; structure identical. The evidence-register
// markers (cited / derived / "my judgment") and every concession are load-bearing:
// do not turn a hedge into a confident claim. Fenced formulas stay fenced.
const content = `Reviewing the translation against the source. The content, numbers, formulas, tables, links, and evidence-register markers are all faithfully preserved with no em-dashes/en-dashes. The one substantive defect is the term for "curation": the translator inconsistently used the wrong word "Kurierung" (= curing/healing) in four places while correctly using "Kuratieren/kuratiert" elsewhere. Fixing those to the correct German term "Kuratierung". Everything else checks out.

## Datenauswahl als Aktualisierungspflicht, bepreist

Sie erwerben keine Zeilen. Sie übernehmen eine Aktualisierungspflicht. Ein einziger gemessener Parameter, r, der jährliche Anteil Ihrer Datensätze, die falsch werden, legt vier Dinge gleichzeitig fest: die Wartungskosten, den Aktualisierungstakt, den Wartungsterm im Break-even zwischen Bauen und Kaufen, und wie lange die gestohlene Kopie eines Wettbewerbers nützlich bleibt. Ein Parameter treibt vier Entscheidungen, aber Ergebnis 4 weiter unten zeigt, dass Sie ihn pro Feld messen müssen, und zwar erst nach dem Kauf zweier Voraussetzungen: eines unabhängigen Orakels und bekannter Verifizierungskosten k pro Datensatz.

Dieser Beitrag bepreist die Nischendaten-These, statt sie zu loben, und er argumentiert zuerst den Gegenfall, so hart es die Evidenz erlaubt. Er korrigiert außerdem den eingestellten Slogan des Blogs, „hundert Zeilen, die Sie verstehen, schlagen eine Million, denen Sie halb vertrauen", der so geschrieben falsch ist: der letzte Abschnitt gibt die Bedingung an, unter der er gilt, und zeigt, dass diese Bedingung meist verschwindet.

Evidenzvertrag. Jede Behauptung ist eines von drei Dingen: mit einem Link zitiert, mit der Arithmetik auf der Seite hergeleitet, oder als mein Urteil gekennzeichnet. Die durchgerechneten Zahlen (N, k, r, die Genauigkeiten) sind illustrative Annahmen, bei jeder Verwendung markiert, keine Messungen. Wo die Recherche nichts ergab, sagt der Artikel das, statt zu schätzen.

Themenabgrenzung. Die Kosten des Kontexts, Budgetdurchsetzung und -dimensionierung, Audit-Trail-Schemata, Audit-Aufbewahrung, und das Überführen eines qualifizierten Datensatzes in einen laufenden Workflow sind Begleitbeiträge. Dieser hier handelt von der Datenauswahl und ihrer Ökonomie, und er endet bei der Entscheidung zur Beschaffung.

Zielleser: ein technischer Gründer oder eine Führungskraft, die entscheidet, in welche Daten investiert werden soll, bevor ein Agent oder eine Automatisierung darauf aufgebaut wird.

## Der stärkste Fall dagegen (lesen Sie das zuerst)

Die These vom proprietären Datenburggraben ist umstritten, und die Skeptiker haben die bessere Evidenzbasis.

Lambrecht and Tucker, [Kann Big Data ein Unternehmen vor Wettbewerb schützen?](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2705530) (2015), unterziehen Daten dem VRIN-Test und stellen fest, dass sie ihn meist nicht bestehen: Big Data ist selten nicht-imitierbar oder selten, es gibt Substitute, und die knappe Ressource ist das Management-Instrumentarium rund um die Daten, nicht die Daten selbst. Ihre Gegenbeispiele sind Newcomer (Airbnb, Uber, Tinder), die etablierte Anbieter schlugen, welche die relevanten Daten bereits besaßen.

Casado and Lauten, [Das leere Versprechen der Datenburggräben](https://a16z.com/the-empty-promise-of-data-moats/) (a16z, 2019), argumentieren, dass Datennetzwerkeffekte meist Datenskaleneffekte sind, und Skaleneffekte sättigen. In ihrem Support-Chatbot-Fall bringt jenseits von etwa 40% der gesammelten Anfragen mehr Daten keinen Vorteil, und die Intent-Abdeckung nähert sich asymptotisch 40%: sie erreicht überhaupt nie volle Automatisierung.

Varian, [NBER WP 24839](https://www.nber.org/papers/w24839) (2018), merkt an, dass statistische Präzision mit der Quadratwurzel der Stichprobengröße skaliert, sodass Sie die vierfache Datenmenge brauchen, um Ihren Fehler zu halbieren, und dass ImageNets Trainings- und Testsätze über die Jahre der größten Genauigkeitsgewinne hinweg fest blieben, sodass diese Gewinne nicht mehr Daten zugeschrieben werden können.

Hestness et al., [arXiv:1712.00409](https://arxiv.org/abs/1712.00409), stellen fest, dass der Generalisierungsfehler als Potenzgesetz mit der Datensatzgröße fällt, mit Exponenten zwischen -0.07 und -0.35. Da das Datenvielfache zur Halbierung des Fehlers 2^(1/beta) beträgt:

\`\`\`
beta = 0.07 -> 10x data cuts error 14.9%; halving needs ~19,972x data
beta = 0.15 -> 10x data cuts error 29.2%; halving needs ~102x data
beta = 0.35 -> 10x data cuts error 55.3%; halving needs ~7x data
\`\`\`

Das schneidet in beide Richtungen: flache Exponenten bedeuten, dass der 100x-Vorteil eines Wettbewerbers wenig bringt, aber Ihr 3x bringt fast nichts.

Chiou and Tucker, [NBER WP 23815](https://www.nber.org/papers/w23815) (2017), nutzen EU-veranlasste Kürzungen der Aufbewahrungsfristen (Bing 18 Monate auf 6, Yahoo 13 auf 3) und finden kaum messbare Verschlechterung der Suchgenauigkeit, mit dem Schluss, dass „der Besitz historischer Daten weniger Vorteil beim Marktanteil verschafft, als manchmal angenommen wird." Allcott, Castillo, Gentzkow, Musolff and Salz, [NBER WP 33410](https://www.nber.org/papers/w33410) (2025), stellen fest, dass die Beseitigung von Nachfragefriktionen Bings Anteil verdoppelt, während Datenweitergabe-Auflagen geringe Effekte haben. Der Burggraben waren Distribution und Voreinstellungen.

Direkt gegeneinander gewinnt der große generische Korpus weiter. [Li et al.](https://arxiv.org/html/2305.05862) berichten, dass GPT-4 BloombergGPT (50B Parameter, 363B proprietäre Finanz-Tokens plus 345B allgemeine, laut dem [BloombergGPT-Paper](https://arxiv.org/pdf/2303.17564)) schlägt: bei ConvFinQA 0-shot 76.48% gegen 43.41%, FiQA-SA 5-shot 88.11% gegen 75.07%, und Financial PhraseBank 5-shot 0.97 gegen 0.51 F1. [Nori et al.](https://arxiv.org/abs/2311.16452) schlagen Med-PaLM 2 auf allen neun MultiMedQA-Datensätzen mit generischem GPT-4 plus Prompting, ohne domänenspezifisches Pretraining oder Fine-Tuning. Und Ovadia et al., [Fine-Tuning oder Retrieval?](https://arxiv.org/html/2312.05934v3), finden, dass RAG bei der Wissensinjektion konsistent das Fine-Tuning schlägt (Mistral 7B bei einer Aktuelle-Ereignisse-Aufgabe: Basis 0.481, RAG 0.875, Fine-Tune 0.504). Wenn der Wert Ihrer Daten in einem Kontextfenster realisiert wird, erfasst jeder, der die Dokumente beschafft, denselben Wert ohne einen einzigen Trainingslauf.

Zwei Robustheitsergebnisse greifen die Hälfte des Slogans an, „eine Million Zeilen, denen Sie halb vertrauen". [Subramanyam, Chen and Grossman](https://arxiv.org/abs/2510.03313) messen Qualitätsexponenten von etwa 0.173 (maschinelle Übersetzung) und 0.401 (kausale Sprachmodellierung), beide deutlich unter 1, sodass die effektive Datensatzgröße mit der Qualität sublinear abnimmt. [Muennighoff et al.](https://arxiv.org/abs/2305.16264) (NeurIPS 2023) finden, dass unter festem Rechenbudget bei begrenzten Daten bis zu vier Epochen wiederholter Tokens von frischen einzigartigen Daten kaum zu unterscheiden sind.

Die kleine Seite scheitert an ihrer eigenen Arithmetik. Das 95%-CI auf einen Anteil bei p=0.5 ist 1.96*sqrt(p(1-p)/n): n=100 ergibt plus/minus 9.80 Punkte, n=1,000 ergibt 3.10, n=1,000,000 ergibt 0.098. Und P(zero occurrences) = (1-rate)^n, sodass 100 kuratierte Zeilen eine Chance von 36.6% haben, null Instanzen eines Fehlermodus mit 1% Häufigkeit zu enthalten. Sie brauchen etwa 299 Zeilen, um zu 95% sicher zu sein, ein 1-in-100-Ereignis einmal zu sehen, etwa 2,995 für ein 1-in-1,000. Kleine Daten, die Sie verstehen, können ihren eigenen Verteilungsschwanz nicht sehen.

Hinter all dem steht Suttons [Bitter Lesson](http://www.incompleteideas.net/IncIdeas/BitterLesson.html) (2019), und zwei teure Fehlschläge. IBM baute einen der größten proprietären Gesundheitskorpora durch Übernahmen im Wert von rund $4B auf (Merge etwa $1B, [Truven $2.6B](https://techcrunch.com/2016/02/18/ibm-acquiring-truven-health-analytics-for-2-6-billion-and-adding-it-to-watson-health), plus Phytel und Explorys) und [verkaufte Watson Health 2022 an Francisco Partners](https://www.fiercehealthcare.com/tech/ibm-sells-watson-health-assets-to-investment-firm-francisco-partners) für berichtete ~$1.065B. Zillow schloss Zillow Offers im November 2021 nach einem Verlust von $422M im Homes-Segment im Q3 2021 (Q3 2021 8-K), wobei der CEO die Unvorhersehbarkeit bei der Prognose von Immobilienpreisen anführte ([AI Incident Database 149](https://incidentdatabase.ai/cite/149/)).

| Argument | Gemessenes Ergebnis | Quelle | Was es nicht klärt |
|---|---|---|---|
| Daten scheitern am VRIN | Daten selten selten oder nicht-imitierbar; Newcomer schlagen datenhaltende Etablierte | Lambrecht and Tucker 2015 | Ob unveröffentlichte First-Party-Ereignisse Substitute haben |
| Skaleneffekte sättigen | Grenzabdeckung flach jenseits von ~40% der gesammelten Anfragen; Intent-Abdeckung nähert sich asymptotisch 40% | Casado and Lauten 2019 | Datensätze, deren Wert Aktualität ist, nicht Abdeckung |
| Quadratwurzel-Präzision | 4x Daten, um den Schätzfehler zu halbieren | Varian, NBER 24839 | Retrieval, wo Präzision nicht der Mechanismus ist |
| Potenzgesetz-Erträge | Fehlerexponenten -0.07 bis -0.35 | Hestness et al. 2017 | Alles außerhalb des Modelltrainings |
| Kürzungen der Aufbewahrung harmlos | Bing 18 auf 6 Monate, kein messbarer Genauigkeitsverlust | Chiou and Tucker, NBER 23815 | Kleine operative Korpora ohne Skalen-Substitut |
| Distribution ist der Burggraben | Beseitigung von Nachfragefriktionen verdoppelt Bing-Anteil | Allcott et al., NBER 33410 | Märkte ohne Kanal für Standardplatzierung |
| Generisch schlägt Domäne | GPT-4 über BloombergGPT bei 3 von 3 zitierten Aufgaben | Li et al. 2305.05862 | Strukturierte Extraktion, wo dasselbe Paper zeigt, dass Fine-Tuning-Modelle gewinnen |
| Retrieval schlägt Fine-Tuning | Mistral 7B: 0.875 RAG vs 0.504 Fine-Tune | Ovadia et al. 2312.05934 | Ob die Dokumente selbst beschaffbar sind |

## Was diese Evidenz nicht abdeckt

Nahezu die gesamte Anti-Burggraben-Basis betrifft das Modell-Pretraining im Frontier-Maßstab. Der Zielleser trainiert nichts; er wählt Daten für ein Kontextfenster oder eine Tool-Antwort aus. Dass 363 Milliarden proprietäre Finanz-Tokens GPT-4 nicht schlagen konnten, sagt wenig darüber aus, ob 40,000 gut strukturierte interne Zeilen einen guten Agenten-Input ergeben.

Das Spiegelproblem trifft meine These mit gleicher Wucht: fast jeder große gemessene Kuratierungserfolg ist ebenfalls ein Trainingskorpus-Ergebnis. [FineWeb-Edu](https://arxiv.org/abs/2406.17557) entfernte rund 91% von FineWeb (15T auf 1.3T Tokens) und hob MMLU von 33% auf 37% und ARC von 46% auf 57% bei einem festen 350B-Token-Budget an, wobei es das Voll-Korpus-MMLU mit etwa 10x weniger Tokens als C4 und Dolma erreichte. [LIMA](https://arxiv.org/abs/2305.11206), [AlpaGasus](https://arxiv.org/abs/2307.08701) und DataComp sind ebenfalls Trainingsergebnisse. Sie auf Retrieval zu übertragen ist eine Annahme, und keine gefundene Studie misst beide Regime an derselben Aufgabe.

Die eine groß angelegte Studie auf der Retrieval-Seite weist in die andere Richtung, und dieser Artikel darf nicht an ihr vorbeigehen. [Nourbakhsh et al., "When Retrieval Doesn't Help"](https://arxiv.org/abs/2606.04127), eine biomedizinische RAG-Studie über 5 Modelle, 10 QA-Datensätze, 4 Retrieval-Methoden und 4 Korpora, fand, dass Retrieval nur 1 bis 2 Punkte gegenüber einer Baseline ohne Retrieval brachte, und dass expertenkuratierte Quellen nicht besser abschnitten als Laienquellen. Die bindende Beschränkung war die begrenzte Fähigkeit des Modells, abgerufene Evidenz zu nutzen, nicht die Korpusqualität. Es ist die einzige gefundene Messung im tatsächlichen Regime des Lesers, sie ist kuratierungsspezifisch, und ihr Befund lautet, dass Kuratierung nichts brachte. Die Domäne ist biomedizinisch, sodass ihre Übertragung auf andere Retrieval-Aufgaben selbst ungemessen ist, aber sie ist Evidenz im richtigen Regime, und die These muss vor ihr zu einer Hypothese herabgestuft werden.

Ein Ergebnis auf der Retrieval-Seite stützt Kuratierung sehr wohl. Die RAG-Genauigkeit folgt einem umgekehrten U, mit einem Höhepunkt bei etwa 10 bis 20 Passagen bei Natural Questions und einem Abfall jenseits von 40 über Gemma-7B, Gemma-2-9B, Mistral-Nemo-12B und Gemini-1.5-Pro ([arXiv:2410.05983](https://arxiv.org/html/2410.05983v1), ICLR 2025). Der Schaden kommt von Hard Negatives, Beinahe-Treffer-Dokumenten, die hoch bewertet werden und die Antwort nicht enthalten. Kuratierung verdient ihren Unterhalt, indem sie plausible falsche Nachbarn entfernt. Ob dieser Mechanismus übertragbar ist, ist ungeprüftes Urteil, keine Antwort auf Nourbakhsh.

Die Lücke, die der Leser am dringendsten geschlossen sehen möchte, ist leer. Ich habe keine öffentliche, methodisch transparente Messung dessen gefunden, was das Kuratieren eines privaten Korpus einbringt. Anbieterinhalte behaupten 95 bis 99% Genauigkeit ohne Baseline, Methodik oder Stichprobengröße, was dieser Artikel nicht zitieren wird. Ebenso wenig fand ich einen einzigen gemessenen Fall, in dem der Nischendatensatz einer kleinen Organisation einen generischen Korpus in einer produktiven Agenten-Umgebung schlägt.

LIMAs Superficial Alignment Hypothesis ist eine Waffe gegen meine These: Wissen stammt fast vollständig aus dem Pretraining, und kleine kuratierte Sätze lehren Format und Stil. In dieser Lesart bringt ein kuratierter Nischenkorpus Formatierung, nicht Verständnis. Die These lässt sich also nicht über Volumen oder Wissen verteidigen. Wenn sie überlebt, überlebt sie über Aktualität, Abdeckung einer bestimmten Entscheidungsoberfläche und Kosten, die messbar sind und die der Rest dieses Artikels instrumentiert.

## Der einzige Parameter, der zählt: r, und was er Sie kostet

Messen Sie ihn, zitieren Sie ihn nicht, und messen Sie ihn als Zwei-Zeitpunkt-Design: eine Stichprobe von zum Zeitpunkt t0 verifizierten Datensätzen, prüfen Sie sie bei t0+delta erneut gegen ein unabhängiges Orakel, zählen Sie geänderte Felder, r = -ln(1-p)*365/delta_days. Berichten Sie das Konfidenzintervall: bei p=0.3, n=100 läuft das CI auf r grob von 21% bis 39%, was sich in jede hergeleitete Zahl weiter unten fortpflanzt (Mb von etwa $7,400 bis $15,400, eine Schwelle für Bauen-schlägt-nichts von grob 723 bis 1,059 statt einer einzelnen selbstsicheren Zahl). Die Kleinstichproben-Kritik von oben gilt auch für Ihr eigenes r.

Modell, hier hergeleitet. Unter einer konstanten Hazardrate ist ein bei t=0 verifizierter Datensatz mit Wahrscheinlichkeit A(t) = e^(-lambda*t) noch korrekt, wobei lambda = -ln(1-r). Um eine Worst-Case-Genauigkeitsuntergrenze A_floor zu halten, aktualisieren Sie alle T Jahre:

\`\`\`
lambda        = -ln(1 - r)
T             = ln(1/A_floor) / lambda
passes / year = lambda / ln(1/A_floor)
maintenance   = N * k * lambda / ln(1/A_floor)
\`\`\`

Eine Konsistenzprüfung: die zitierte monatliche Kontaktverfallsrate von 2.1% ergibt 12 * -ln(1-0.021) = 0.2547, und -ln(1-0.225) = 0.2549. Sie kursieren als getrennte Zahlen und sind dieselbe Zahl, aufgezinst, auf drei Dezimalstellen.

**Ergebnis 1.** Wenn Sie genau im Takt aktualisieren, der A_floor hält, ist die mittlere Genauigkeit über den Zyklus (1-A_floor)/ln(1/A_floor), was nur von der Untergrenze abhängt, nicht von r. Eine 95%-Untergrenze mittelt immer 97.48%, eine 90%-Untergrenze 94.91%, eine 99%-Untergrenze 99.50%. Die Änderungsrate legt den Preis der Untergrenze fest, nie die Qualität, die Sie dafür bekommen.

**Ergebnis 2.** Durchläufe pro Jahr sind lambda/ln(1/A_floor), sodass relativ zu einer 90%-Untergrenze eine 95%-Untergrenze 2.05x kostet, eine 99%-Untergrenze 10.48x, eine 99.9%-Untergrenze 105.31x. Wählen Sie die Untergrenze anhand der Kosten einer falschen Entscheidung.

**Ergebnis 3.** Rollierende Neuverifizierung muss nach dem ältesten zuerst erfolgen. Zufälliges Rollieren mit Rate v erreicht einen stationären Mittelwert von v/(v+lambda), hat aber überhaupt keine Untergrenze: die Datensatzalter sind exponentiell verteilt, sodass ein Ausläufer von Datensätzen beliebig veraltet ist, egal wie viel Sie ausgeben. Ältestes-zuerst ist äquivalent zu Batch und begrenzt den Worst Case; zufällig tut das nicht.

**Ergebnis 4, der größte Hebel.** Messen Sie r pro Feld. Ein Datensatz, der zu 80% stabil (r=2%) und zu 20% volatil (r=30%) ist, kostet einheitlich 6.954 vollständige Durchläufe/Jahr, gegenüber 0.2*6.954 + 0.8*0.394 = 1.706 Durchlauf-Äquivalenten segmentiert, eine Einsparung von 4.08x bei identischer 95%-Untergrenze. Dies setzt voraus, dass die Verifizierungskosten mit dem Anteil der berührten Felder skalieren; eine feste Komponente pro Datensatz (Abruf, Abgleich, Kontextwechsel) schrumpft die Einsparung Richtung 1x.

Modellvorbehalt: konstante Hazardrate ist eine Vereinfachung, und sie ist testbar. Tragen Sie die Überlebenskurve auf einer Log-Achse auf; ist sie nicht gerade, passen Sie eine Weibull-Kurve S(t) = exp(-(t/eta)^k) an, was T = eta*(ln(1/A_floor))^(1/k) ergibt. Pews Link-Rot-Daten sind früh gewichtet, das ist der Fall k<1 (eine abnehmende Hazardrate, hoher früher Verlust). Bei k<1 unterschätzt die Exponentialverteilung den frühen Verlust und überschätzt das späte Überleben, sodass die erste Aktualisierung früher als T kommen muss.

Für Quellen, die Sie nicht kontrollieren, ist Pews [When Online Content Disappears](https://www.pewresearch.org/data-labs/2024/05/17/when-online-content-disappears/) (2024) der einzige saubere externe Anker, den ich fand: 38% der Seiten, die 2013 existierten, waren bis October 2023 verschwunden, aber 8% der 2023-Seiten waren schon innerhalb eines Jahres weg. Die Zehn-Jahres-Durchschnitts-Hazardrate ist -ln(0.62)/10 = 0.0478/yr, aber die direkt beobachtete Erstjahres-Hazardrate ist 8%. Verwenden Sie 8% für die Taktfestlegung bei frischen Quellen.

Eine Herkunftswarnung: die B2B-Kontaktzahl von 22.5% pro Jahr geht auf MarketingSherpa via [HubSpots Database Decay Simulation](https://www.hubspot.com/database-decay) zurück, repliziert von Lead-Gen-Anbietern mit kommerziellem Interesse und ohne veröffentlichte Methodik oder Stichprobengröße. Wenden Sie sie auf B2B-Kontaktlisten an und auf sonst nichts. Verfallsraten für Produktkataloge, Preise, regulatorische Korpora, Geo- und technische Dokumentation erscheinen unveröffentlicht. Die Tabelle ist ein Modell, in das Sie Ihr eigenes r einsetzen.

| Jährliche Änderungsrate r | lambda | Tage zwischen Aktualisierungen, 95%-Untergrenze | Tage, 90%-Untergrenze | Vollständige Durchläufe/Jahr, 95%-Untergrenze | Halbwertszeit einer einmaligen Kopie |
|---|---|---|---|---|---|
| 2% | 0.0202 | 927 | 1,904 | 0.39 | 34.3 yr |
| 5% | 0.0513 | 365 | 750 | 1.00 | 13.5 yr |
| 10% | 0.1054 | 178 | 365 | 2.05 | 6.58 yr |
| 22.5% (nur B2B-Kontakte) | 0.2549 | 73.5 | 151 | 4.97 | 2.72 yr |
| 30% | 0.3567 | 52.5 | 108 | 6.95 | 1.94 yr |
| 60% | 0.9163 | 20.4 | 42.0 | 17.87 | 0.76 yr |

## Die Scorecard: sieben Zeilen, zwei Gates

Unten verwendete und hier definierte Symbole: D ist Entscheidungen pro Jahr, v ist der Nettowert pro korrekter Entscheidung (der Ausschlag zwischen einer richtigen und einer falschen Entscheidung, sodass die Fehlerkosten bereits darin enthalten sind), und D_be ist das Break-even-Volumen zwischen Bauen und Nichtstun (Cb/H+Mb)/(v*(Ab-A0)), hergeleitet im nächsten Abschnitt. Die Schwelle von Zeile 3 nutzt den jährlichen Entscheidungswert, geschrieben D mal v.

| Kriterium | Test, den Sie diese Woche durchführen können | Schwelle | Punktzahl 0-3 |
|---|---|---|---|
| 1. Aufzählbarkeit | Zwei unabhängige Stichproben über zwei Wege, Überlappung m, Chapman estimator | 3 wenn Abdeckung >=95%; 2 wenn 90-95%; 1 wenn 75-90% und Sie das ausgeschlossene Segment benennen können; 0 wenn kein N-hat | |
| 2. Verifizierbarkeit (GATE) | Benennen Sie das unabhängige Orakel; messen Sie k und Minuten pro Datensatz | Bestanden, wenn k <= 1% von v und <= 10 min/record | bestanden/durchgefallen |
| 3. Verfall-Bezahlbarkeit | Datensätze zu zwei Zeitpunkten neu verifizieren, auf r annualisieren, Wartung als % von D mal v berechnen | 3 wenn <=5%; 2 wenn 5-15%; 1 wenn 15-30%; 0 wenn >30% oder r ungemessen | |
| 4. Herzschlag | Letzte 12 veröffentlichte Versionen, Variationskoeffizient der Abstände zwischen Veröffentlichungen | 3 wenn CV <=0.25 und maximaler Abstand <=2x Median; 2 wenn CV <=0.5 oder Sie den Abruf kontrollieren; 1 wenn CV <=1.0 oder Abstände unregelmäßig, aber begrenzt; 0 wenn keine Versionshistorie | |
| 5. Entscheidungskopplung (GATE) | Entscheidung, Akteur, Standardwert, D pro Jahr benennen; 90-Tage-Divergenzrate messen | Bestanden, wenn D >= D_be und Divergenz >= 2% | bestanden/durchgefallen |
| 6. Nicht-Substituierbarkeit | Die günstigste vollständige Replikation in Tagen qualifizierter Arbeit bepreisen | 3 wenn Replikation rechtlich gesperrt ist (das Zugriffsrecht benennen); 2 wenn >180 Tage; 1 wenn 30-180 Tage; 0 wenn <30 Tage oder ein Anbieter sie als SKU listet | |
| 7. Join-Integrität | Den Join an einer 500-Zeilen-Stichprobe versuchen, exakte Primärschlüssel-Trefferquote messen | 3 wenn >=98%; 2 wenn 95-98%; 1 wenn 90-95%; 0 wenn <90% | |

**Zeile 1** verwendet den Chapman estimator N-hat = ((n1+1)(n2+1)/(m+1)) - 1: n1=300, n2=250, m=180 ergibt 416, sodass das Halten von 380 Zeilen 91.3% Abdeckung ist. Chapman setzt gleiche Fangbarkeit voraus, aber die fehlenden Entitäten sind systematisch die neuesten und entlegensten, was N-hat nach unten verzerrt. N-hat ist also eine untere Schranke für das Universum und die Abdeckungszahl eine obere Schranke. Führen Sie das Recapture erneut aus, beschränkt auf Entitäten, die erstmals in den letzten 12 Monaten gesehen wurden, als erforderliche zweite Zahl.

**Zeile 2 ist ein Gate**, denn ohne ein Orakel können Sie r nicht messen, sodass die Zeilen 1 und 3 unbeantwortbar sind. k ist zudem der Multiplikand in der Wartungsformel, sodass diese eine Zahl die gesamte Verpflichtung bepreist. Beachten Sie, dass k = $0.40 im durchgerechneten Beispiel eine nahezu automatisierte Verifizierung impliziert (etwa eine Minute pro Datensatz bei $25.23/hr); das Gate selbst toleriert 10 min/record, also $4.20, eine Größenordnung höher.

**Zeile 3, durchgerechnet:** N=4,000, k=$0.40, r=30%, 95%-Untergrenze ergibt 6.95 Durchläufe und $11,126/yr; die Segmentierung auf 20% volatile Felder ergibt $2,729. Beide skalieren linear mit dem angenommenen k.

**Zeile 4** macht das „ändert sich in einem Rhythmus, den Sie lernen können" des eingestellten Beitrags messbar: eine Quelle, deren eigenes Veröffentlichungsintervall variabler ist als Ihr erforderliches T, macht die Untergrenze bei jedem Aufwand nicht durchsetzbar.

**Zeile 5 killt die meisten Kandidaten.** Benennen Sie die Entscheidung, den Akteur, den Standardwert und D pro Jahr, und messen Sie die 90-Tage-Divergenzrate (wie oft die Daten die Entscheidung geändert hätten). Unter 2% bewegen die Daten keine Entscheidungen, ein hartes Durchfallen. Ich fand keine Studie, die die Divergenz in Produktion misst, also ist die 2% mein Urteil.

**Zeile 6** passt zur Halbwertszeit einer einmaligen Kopie ln2/lambda, aber berechnen Sie sie pro Feldsegment: ein Wettbewerber kopiert die stabilen 80% (Halbwertszeit 34.3 Jahre bei r=2%) und leitet das volatile Fünftel neu her, sodass die Halbwertszeit auf Datensatzebene die Verteidigbarkeit überzeichnet. Berichten Sie die Zahl des stabilen Segments.

**Zeile 7** ist wichtig, weil sich Genauigkeiten multiplizieren: ein zu 95% genauer Datensatz, der zu 90% gejoint wird, liefert 85.5% effektive Genauigkeit. Wenden Sie den Join-Faktor sowohl auf Ihren Eigenbau als auch auf jeden Anbieter-Datensatz an, da er jeden externen Datensatz verschlechtert, der auf Ihre Schlüssel gejoint wird.

Bewertungsregel, mein Urteil: zwei Gates bestanden/durchgefallen, fünf Zeilen mit 0-3 bewertet für maximal 15, investieren bei 11 mit beiden bestandenen Gates. Die Tests sind durchführbar und die Arithmetik hinter den Zeilen 1, 3 und 7 steht auf der Seite. Jede numerische Grenze in der Schwellenspalte ist mein Urteil, an Erfahrung kalibriert, nicht hergeleitet oder zitiert; verschieben Sie sie. Die eigentliche Funktion des Instruments ist es, sieben Messungen zu erzwingen, die etwa eine Woche dauern.

Angewandt auf die austauschbaren Beispiele der eingestellten Beiträge: Pünktlichkeit für einen Frachtführer auf einer Strecke, jede Handelserlaubnis in einem Ballungsraum, die Erstattungssätze eines Kostenträgers, sowie Preis und Bestand für 40 SKUs, zweimal täglich geprüft, unterscheiden sich enorm bei den Zeilen 3, 4 und 6. Der SKU-Satz hat ein lambda in den Hunderten pro Jahr (r faktisch bei 100% festgesetzt, weil r ein Bruch unter 1 ist; verwenden Sie lambda direkt, wenn die Änderung schneller als jährlich ist) und eine Kopie-Halbwertszeit in Tagen. Das Erlaubnisregister hat ein r nahe null und ist trivial kopierbar.

## Was Daten tatsächlich kosten

Jede Zahl trägt ihre Herkunftsstufe.

| Position | Anbieter | Veröffentlichter Preis | Herkunft |
|---|---|---|---|
| Residential-Proxy-Bandbreite | [Bright Data](https://brightdata.com/pricing/proxy-network/residential-proxies) | $8/GB PAYG, $5/GB in der $1,999/mo-Stufe | Primärer Seitenabruf |
| Datacenter-/ISP-Proxys | Bright Data | $1.30-$1.80 und $0.90-$1.40 pro IP pro Monat | Primärer Seitenabruf |
| Scraping nach Schwierigkeitsstufe | [Zyte](https://docs.zyte.com/zyte-api/pricing.html) | 5 HTTP- und 5 Browser-Stufen; ~$0.13-$1.27 und ~$1.01-$16.08 pro 1,000 | Stufenstruktur primär; Preise aggregator-berichtet |
| Screenshot-Add-on | Zyte | $0.002 pro Stück | Primärdokumentation |
| Labeling-Werkzeuge | SageMaker Ground Truth | $0.08 / $0.04 / $0.02 pro Objekt (Stufen 1-50k / 50-100k / >100k); 500 Objekte/Monat kostenlos für die ersten zwei Monate | Aggregator-berichtet, möglicherweise veraltet, derzeit nicht von AWS veröffentlicht |
| Labeling-Werkzeuge | Labelbox | $0.10 pro Labelbox Unit, 1 LBU pro gelabelter Zeile | Aggregator-berichtet |
| Labeling | [Scale AI](https://scale.com/pricing) | Kein Enterprise-Tarif veröffentlicht; nur kostenlose Stufe | Primärer Seitenabruf |
| US-Annotationsarbeit | [ZipRecruiter](https://www.ziprecruiter.com/Salaries/Data-Annotation-Salary) | ~$25.23/hr ($52,488/yr); offshore ~$2 bis $5-12/hr | Primär; offshore aggregator-berichtet |
| B2B-Kontaktdaten | [Vendr](https://www.vendr.com/buyer-guides/zoominfo) | ZoomInfo Median $33,500/yr über 1,566 Käufe, Spanne $7,200-$155,550 | Verifizierte Transaktionsdaten |
| Marktdaten | [Databento](https://databento.com/pricing) | $199 / $1,750 / $4,500 pro Monat | Primärer Seitenabruf |
| Schmale Einzweck-Feeds | [Massive](https://massive.com/pricing) | NYSE Order Imbalances $49/mo; European Consumer Spending by Merchant $99/mo | Primärer Seitenabruf |
| Marktplatz-Angebote | [AWS Data Exchange](https://aws.amazon.com/data-exchange/pricing/) | Anbieter-festgelegt; $0.023/GB/mo Speicher, $0.04167/hr Datengewährungen | Primärer Seitenabruf |
| Marktplatz-Angebote | Snowflake Marketplace | Pro Monat, Query oder hybrid; reale Angebote $100-$1,500/mo | Anbieterdokumentation plus sekundär |
| Lizenzierung von Trainingsdaten | News Corp / OpenAI; Reddit / Google | >$250M über 5 Jahre; ~$60M/yr (Reddit S-1: $203M aggregiert) | Bestätigte Presseberichterstattung |
| Rechtliche Prüfung der Beschaffungsmethode | Ihr Rechtsberater | Indikativ: Prüfung plus DPIA, niedriger bis mittlerer fünfstelliger Betrag einmalig plus laufende Bearbeitung | Mein Urteil, keine tatsächliche Transaktionszahl gefunden |

Zwei hergeleitete Zahlen, mit sichtbaren Annahmen. Ein Nischenkorpus mit einer Million Seiten bei angenommenen 200KB pro Seite (meine Annahme) sind 200GB: etwa $1,600 an Bright Data Residential-Bandbreite zum Listenpreis, gegenüber etwa $130 über Zyte bei HTTP-Stufe 1 oder etwa $16,080 bei Browser-Stufe 5, zwei Größenordnungen auseinander, entschieden dadurch, in welche Stufe das Ziel fällt. Das Labeling von 100,000 Datensätzen zu den obigen Ground-Truth-Stufen sind 50,000*$0.08 + 50,000*$0.04 = $6,000 an Werkzeugen (die 500-kostenlos-Freimenge ist unerheblich, und diese Pro-Objekt-Stufen sind möglicherweise veraltet, nicht mehr von AWS veröffentlicht), oder 100,000*$0.10 = $10,000 an Labelbox-Units, beide ohne menschliche Arbeit, den größeren Posten.

Die ehrliche Lücke: Ich fand keinen tatsächlich gehandelten Mittelmarkt-Preis für das Lizenzieren oder Bauen eines Domänen-Datensatzes mit 10,000 bis 100,000 Zeilen. Die veröffentlichte Spanne reicht von etwa $0.01 pro Label bis $250M pro Deal, rund zehn Größenordnungen, wobei die Mitte undokumentiert ist. Es gibt zudem keinen öffentlichen Benchmark für k, die Kosten pro verifiziertem Datensatz, die Eingabe, auf die der Break-even weiter unten am empfindlichsten reagiert.

## Bauen, kaufen oder nichts tun

Das Genre vergleicht Bauen gegen Kaufen und prüft nie die dritte Option. Nichtstun hat einen positiven Nettowert, D*v*A0, und das Modell unten schlägt beide Alternativen bei jedem Volumen unterhalb des Break-even mit diesen Eingaben.

\`\`\`
Nothing = D*v*A0
Buy     = D*v*Av - L
Build   = D*v*Ab - (Cb/H + Mb)

Build beats buy     when D > (Cb/H + Mb - L) / (v*(Ab - Av))
Build beats nothing when D > (Cb/H + Mb)     / (v*(Ab - A0))
Buy   beats nothing when D > L               / (v*(Av - A0))
\`\`\`

Cb ist die einmalige Beschaffung, H der Amortisationshorizont, Mb die Wartung pro Periode, L die Lizenz pro Periode, v der Nettowert pro korrekter Entscheidung (bereits der Ausschlag zwischen richtig und falsch, sodass die Fehlerkosten darin enthalten sind; wenn Sie Bruttowert plus separate Fehlerkosten c bevorzugen, ersetzen Sie v durch v+c).

Durchgerechnete Eingaben, allesamt illustrative Annahmen, keine Messungen: N=4,000, Cb=$30,000, H=3 Jahre, L=$18,000/yr, v=$60, Ab=0.95, Av=0.78, A0=0.55, r=30%, k=$0.40. Mb=$11,100/yr wird daraus hergeleitet (4,000*$0.40*6.95 Durchläufe bei einer 95%-Untergrenze), was nicht dasselbe ist wie unabhängig bekannt: es erbt das angenommene k, und k hat keinen öffentlichen Benchmark. Eine Ab-A0-Lücke von 40 Punkten ist optimistisch; eine kleinere, realistischere Lücke hebt alle drei Break-evens an und verbreitert den Bereich, in dem Nichtstun gewinnt.

Break-evens bei k=$0.40: Bauen schlägt Kaufen oberhalb von (10,000+11,100-18,000)/(60*0.17) = 304/yr; Bauen schlägt Nichtstun oberhalb von 21,100/(60*0.40) = 879; Kaufen schlägt Nichtstun oberhalb von 18,000/(60*0.23) = 1,304.

Diese kippen mit k. Bei k=$0.40 ist das Kaufband leer (obere Grenze 304 unter unterer Grenze 1,304), sodass Kaufen dominiert wird. Aber das Gate von Zeile 2 toleriert 10 min/record, was bei $25.23/hr $4.20 sind. Bei k=$4.20, Mb=$116,800: Bauen-schlägt-nichts verschiebt sich von 879 auf 5,283, und das Kaufband öffnet sich auf grob 1,304 bis 10,667. Das Band öffnet sich, sobald k etwa $0.75 übersteigt. Also gilt „Kaufen wird bei jedem Volumen dominiert" nur bei nahezu automatisierter Verifizierung. Es ist kein allgemeines Ergebnis, und es wird für die manuelle Verifizierung zurückgezogen.

| Entscheidungen/yr | Nichtstun | Kaufen bei $18,000/yr | Bauen ($30k über 3yr + $11.1k/yr) | Gewinner |
|---|---|---|---|---|
| 294 | $9,702 | -$4,241 | -$4,342 | Nichtstun |
| 879 | $29,007 | $23,137 | $29,003 | Nichtstun (Kreuzungspunkt) |
| 1,304 | $43,032 | $43,027 | $53,228 | Bauen |
| 2,000 | $66,000 | $75,600 | $92,900 | Bauen |

| Anbietergenauigkeit Av | Kaufband untere Grenze | Kaufband obere Grenze | Band (k=$0.40) |
|---|---|---|---|
| 0.78 | 1,304 | 304 | Leer |
| 0.85 | 1,000 | 517 | Leer |
| 0.90 | 857 | 1,033 | Offen, 857-1,033 |
| 0.93 | 789 | 2,583 | Offen, 789-2,583 |

Kaufen ist genau dann richtig, wenn der Anbieter fast so genau ist, wie Sie es auf Ihrer eigenen Oberfläche wären, was eine Frage Ihrer Teilmenge ist, nicht seines Marketings. Ziehen Sie eine Stichprobe von 200 Anbieter-Datensätzen innerhalb Ihrer Nische und messen Sie Av vor der Unterschrift. Die Empfindlichkeit gegenüber v skaliert wie 1/v: bei $6 statt $60 schlägt Bauen das Kaufen nur oberhalb von 3,100/(6*0.17) = etwa 3,040/yr.

Das Modell lässt zudem die Option aus, die im Regime dieses Lesers meist dominiert: kaufen Sie die kopierbare Masse und bauen Sie nur die Ergebnisspalte, die niemand scrapen kann. In einem Kontextfenster halten Sie beides, sodass es selten überhaupt einen Grund gibt, einen Datensatz dem anderen vorzuziehen.

Nun die Taktschleife. Ihr Vorsprung gegenüber einem Wettbewerber ist die Lücke in der mittleren Genauigkeit durch einen schnelleren Takt, wobei die mittlere Genauigkeit über das Intervall T (1-e^(-lambda*T))/(lambda*T) ist. Bei r=30% ergibt monatliche Aktualisierung einen Mittelwert von 98.53%, jährliche 84.11%, eine Lücke von 14.4 Punkten. Die zusätzlichen Kosten von monatlich gegenüber jährlich sind 11 zusätzliche Durchläufe, 11*4,000*$0.40 = $17,600/yr, sodass sich die Taktlücke nur oberhalb von 17,600/(60*0.144) = etwa 2,034 Entscheidungen/yr auszahlt, oberhalb beider durchgerechneter Break-evens. Und es ist kein Datenburggraben: ein Wettbewerber, der dieselbe Aktualisierungs-Pipeline besetzt, löscht ihn aus. Das Verteidigbare ist ein Betriebstakt, eine Tatsache von Einstellung und Werkzeugen, keine Datentatsache.

## Vier Wege, wie das nach dem Kauf schiefgeht

| Fehlermodus | Symptom, das Sie tatsächlich sehen | Erkennungsmethode | Auslöseschwelle |
|---|---|---|---|
| Abdeckungsillusion | Backtest in Ordnung, Live-Leistung bei neuen Fällen schlecht, Lücke wird größer | Capture-Recapture (Zeile 1) auf Entitäten, die erstmals in den letzten 12 Monaten gesehen wurden | Abdeckung neuer Entitäten mehr als 15pp unter Gesamtwert |
| Veraltet, aber vertraut | Selbstsichere Antworten, aufgebaut auf Feldern, die seit Jahren niemand angefasst hat | Lesegewichtete Veraltung: Anteil der Lesezugriffe auf Zeilen, die älter als T sind | Mehr als 5% der Lesezugriffe jenseits des Untergrenzen-Takts |
| Entscheidungsdrift | Grüne Pipeline, aktualisierte Daten, niemandes Handeln ändert sich | 90-Tage-Divergenzrate (Zeile 5) | Unter 2%, den Datensatz verwerfen |
| Wartungsklippe | k springt, ein Aktualisierungsdurchlauf schlägt still fehl, eine Quelle beginnt Sie zu blockieren, ein Feld bedeutet etwas Neues | Quellenkonzentration, k im Jahresvergleich, und Quellen-Blockrate | Eine einzelne Quelle >50% der Zeilen, k um >25% im Jahresvergleich gestiegen, oder eine gescrapte Quelle, die Sie abweist |

Nach meinem Urteil ist der Fehlbetrag in einer Abdeckungszahl nicht zufällig: er konzentriert sich in den neuesten, kleinsten, entlegensten Entitäten, genau dem Segment, um das es bei der Entscheidung geht. Wenn Ihre Stichprobenwege mit dem Entitätsalter korrelieren, führen Sie Zeile 1 separat für die letzten 12 Monate aus, um es herauszufinden.

Lesegewichtung ist wichtig, weil die heißen 5% der Zeilen meist die konsultierten sind (meine Annahme, testbar über das lesegewichtete Maß selbst); wenn sie auch die volatilen sind, schmeichelt Ihnen datensatzgewichtete Aktualität. Fügen Sie eine verified_at-Spalte hinzu, sonst lässt sich keines der Modelle in diesem Artikel ausführen. Entscheidungsdrift überlebt am längsten, weil jedes Dashboard gesund aussieht. Eine Quelle, die anfängt Sie abzuweisen, ist zugleich eine Wartungsklippe und ein rechtliches Signal. Die Schwellen in diesen Zeilen sind mein Urteil; die Basis-Hazardrate für unkontrollierte Web-Quellen ist Pews Erstjahres-8%.

## Wo die Nischen-These hält und wo nicht

Mein Beitrag, als Urteil vorgelegt: Verteidigbarkeit ist proportional zu den Aktualisierungskosten, und proprietäre Daten sind für sich genommen kein Burggraben. Der Punkt von Lambrecht and Tucker steht: die knappe Ressource ist das Betriebs-Instrumentarium rund um die Daten. Verteidigbar sein könnte ein gepflegter Aktualisierungstakt, gewickelt um eine geschlossene Entscheidungsschleife, und nur so lange, wie kein Wettbewerber dieselbe Pipeline besetzt. Das ist ein Wettlauf um Einstellungen, kein Datenvorteil. „Finde Daten, die günstig zu pflegen sind" und „finde Daten, die verteidigbar sind" sind daher gegensätzliche Anweisungen, und den meisten Gründern werden beide in die Hand gedrückt.

Sagen Sie den Punktestand klar. Die Anti-These hat zwei dokumentierte Fehlschläge (Watson Health, Zillow) und sechs empirische Stränge. Die Pro-These hat null benannte Produktionsfälle im Regime dieses Lesers: keine transparente Messung dessen, was das Kuratieren eines privaten Korpus einbringt, und die eine zielgenaue Retrieval-Studie fand, dass es nichts einbringt. Fehlschläge dieser Klasse bleiben unveröffentlicht, sodass die Stichprobe durch Survivorship selektiert ist. Behandeln Sie die These als eine Hypothese, die dieser Artikel instrumentiert, nicht als ein Ergebnis, das er beweist. Ihr Falsifikationstest: messen Sie Divergenz und den Zuwachs an effektiver Genauigkeit auf Ihrer eigenen Oberfläche; liegt der Zuwachs im Rauschen, ist die These für Sie gescheitert.

Vier Bedingungen, unter denen sie überleben könnte.

1. **Die Daten erfassen eine Entscheidung, die nur Sie treffen, keinen Wissensbestand.** Das verteidigbare Objekt ist die geschlossene Schleife aus Entscheidung, Ergebnis, gelabeltem Datensatz, weil die Ergebnisspalte nicht gescrapt, sondern nur erarbeitet werden kann. Dies ist die einzige Bedingung, die mit aller obigen Evidenz vereinbar ist: keine Behauptung seltener Fakten, kein Verlass auf Skalierung, nicht aus öffentlichem Text ableitbar.
2. **First-Party-Beobachtung von Ereignissen, die keine gejointe öffentliche Spur hinterlassen.** Ein Ereignis, das Sie beobachten, hinterlässt dennoch einen Fußabdruck bei Ihrer Gegenpartei, einem Broker oder einem Zahlungsabwickler (der $99/mo-Merchant-Spend-Feed oben ist genau weiterverkaufte Transaktionsdaten). Aber niemand sonst hält den gejointen Datensatz aus Ereignis, Kontext und Ergebnis unter Ihrem Schlüssel. Dieser Join ist das verteidigbare Objekt, nicht das Ereignis.
3. **Hoher Verfall, verstanden als wiederkehrende Kosten statt als Barriere.** Ein schnell verfallender Satz kann nicht einmalig gestohlen, nur gepflegt werden, sodass er nur verteidigbar ist, solange Sie die Taktlücke halten, die ein Wettbewerber weg-einstellen kann. Bei r=30% ist ein einmaliger Snapshot innerhalb von 9 Monaten zu 23.5% falsch, aber ein Wettbewerber, der ebenfalls eine Aktualisierungsmaschine baut, verliert nichts.
4. **Klein genug, um erschöpfend zu verifizieren.** Bei 4,000 Datensätzen und k=$0.40 kostet eine 99%-Untergrenze bei r=30% etwa $56,800/yr; bei 400,000 Datensätzen sind es etwa $5.68M und niemand kauft das. Beide skalieren mit dem angenommenen k.

Wo sie nicht hält: (a) ein Anbieter verkauft sie als SKU (mieten Sie sie, siehe die $49- bis $99/mo-Feeds oben); (b) niedriger Verfall plus öffentliche Quellen (Ihre Kopie und deren altern gemeinsam, sodass Sie über Distribution konkurrieren, wo die natürlichen Experimente sagen, dass der Burggraben tatsächlich lag); (c) unterhalb des Break-even-Entscheidungsvolumens; (d) kein unabhängiges Orakel (Sie können r nicht messen, also können Sie hier nichts bepreisen); (e) die Aufgabe ist Schlussfolgern oder Semantik statt Nachschlagen und strukturierter Extraktion (GPT-4 über BloombergGPT, generisches Prompting über Med-PaLM 2); (f) Divergenz unter 2%; (g) die Beschaffungsmethode ist an der Quelle vertraglich oder rechtlich untersagt, also bepreisen Sie den Rechtsberater vor dem Scraper.

Schließlich der eingestellte Slogan, als Bedingung beibehalten. Die effektive Genauigkeit ist c*A_small + (1-c)*A0 nur, wenn Sie außerhalb der kuratierten Oberfläche auf die Baseline zurückfallen. In einem Kontextfenster halten Sie meist beide Sätze, sodass die effektive Genauigkeit c*A_small + (1-c)*A_big ist, was für jedes c>0 mindestens A_big ist: der kleine saubere Satz ist nie schlechter, und es gibt überhaupt keine Schwelle. Eine Schwelle existiert nur dort, wo sich die beiden gegenseitig ausschließen, was sie bei Retrieval selten tun. Unter dieser Ausschließlichkeit, mit A_small=0.99, A0=0.55 und A_big=0.60 (alle angenommen), ist die Break-even-Abdeckung (0.60-0.55)/(0.99-0.55) = 11.4%; bei A_big=0.65 verdoppelt sie sich auf 22.7%. Die Antwort ist also Abdeckung, nicht Zeilenzahl, und sie wird davon dominiert, wie gut die generische Baseline auf Ihrer Oberfläche bereits ist, was Sie messen können.

Die Arbeit der Woche: kaufen Sie die zwei Voraussetzungen, ein Orakel und Kosten k pro Datensatz; messen Sie r pro Feld mit seinem Konfidenzintervall; führen Sie die sieben Zeilen durch; berechnen Sie Ihre drei Break-evens mit k, variiert über die Spanne, die Ihre eigenen Arbeitskosten implizieren. Erst dann entscheiden Sie. Wenn der Datensatz sich qualifiziert, behandelt [from-dataset-to-live-workflow](/blog/from-dataset-to-live-workflow) was als Nächstes passiert.
`;

export default content;
