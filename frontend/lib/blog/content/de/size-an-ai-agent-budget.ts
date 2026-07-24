// size-an-ai-agent-budget - de
// Translated from the English body; the structure must stay identical to it.
// Formulas and code samples are fenced on purpose: an inline code span over
// ~45 chars overflows the page on a phone. The hedges are load-bearing here.
const content = `Ein Begleitartikel argumentiert, dass die meisten Agent-Budgets Zahlen sind, die noch nie einen einzigen Aufruf verweigert haben, und arbeitet die Durchsetzungsmechanik durch: woraus ein Budget-Objekt besteht, warum eine Obergrenze innerhalb eines Laufs immer nur den Aufruf *nach* dem teuren stoppen kann, und was jeder Stack tatsächlich durchsetzen kann. Dieser hier beantwortet die Frage, die als Nächstes kommt. Sie sind überzeugt, dass die Obergrenze echt sein sollte. Welche Zahl schreiben Sie in das Feld?

Die kurze Antwort lautet: Sie können sie nicht per Intuition wählen, denn die Größe, die Sie beschränken, ist rechtsschief, superlinear in der Iterationsanzahl und erstreckt sich über drei Größenordnungen hinweg über verschiedene Schritttypen. Die lange Antwort ist der Rest dieses Artikels: ein reproduzierbares erzeugendes Modell, ein abgeleiteter Sicherheitsfaktor, eine Untergrenze, unterhalb derer eine Geldobergrenze überhaupt nicht durchsetzbar ist, und der Stichprobenumfang, den Sie brauchen, bevor Sie ein Tail-Quantil nennen dürfen.

**Offenlegung.** Die Implementierungskonstanten und der unten beschriebene Reservierungsmechanismus stammen aus LiveContexts \`agent-service\`, der Plattform, zu der dieser Blog gehört. Lesen Sie sie als die Entscheidungen eines einzelnen Systems, überprüfbar in dessen Community-Edition-Quellcode, nicht als erhobene Praxis aus dem Feld. Preise sind illustrative Katalog-Momentaufnahmen; die Methode ist preisunabhängig.

## Ein Pro-Schritt-Budget bemessen, das Sie berechnen können

Ein Schritt, der \`n = k+1\` Modelliterationen mit \`k\` Tool-Aufrufen ausführt, hat erwartete Kosten, die vom festen Prompt \`P0\`, der Eingabenutzlast \`I\`, den pro Tool-Ergebnis zurückgegebenen Tokens \`r\`, der Ausgabe \`O\` pro Durchlauf und einem Akkumulationsterm proportional zu \`n(n-1)/2\` bestimmt werden. Das erzeugende Modell für jede Zeile unten:

\`\`\`
prompt_i = (P0 + I) + (i-1) * (O_turn + r)      i = 1..n
\`\`\`

**Tabelle 4a: Parameter der Schritt-Archetypen.** Dies sind **konstruierte Parametersätze, keine gemessenen Produktions-Traces**, veröffentlicht, damit jede abgeleitete Spalte reproduziert werden kann.

| Schritt-Archetyp | P0 + I | r pro Tool-Ergebnis | O pro Durchlauf | n |
|---|---|---|---|---|
| Klassifizieren | 1,000 | n/a | 30 | 1 |
| Retrieval-gestützter Entwurf | 2,000 | 6,000 | 60 Tool-Durchlauf, 500 final | 2 |
| Multi-Tool-Recherche | 2,500 | 3,000 | 80 Tool-Durchlauf, 800 final | 7 |
| Langdokument-Zusammenfassung | 120,300 | n/a | 1,500 | 1 |
| Browser-Schritt | 1,800 | 8,000 | 120 Aktions-Durchlauf, 250 final | 13 |

**Tabelle 4b: Bemessung pro Schritt.** Die Preise stammen aus einer Katalog-Momentaufnahme des Repos und sind illustrativ, nicht die aktuellen Anbieterpreise. Die Methode ist preisunabhängig. Die letzte Spalte verwendet ein pauschales S=3 als illustrativen Faktor; der nächste Abschnitt ersetzt es durch einen abgeleiteten.

| Schritt-Archetyp | Modellklasse, Listenpreis ($/1M ein, aus) | Tokens ein / aus | Iterationen n | Erwartete Kosten | Größte Einzeliteration (x erste) | Budget bei S=3 (pauschal) |
|---|---|---|---|---|---|---|
| Klassifizieren | flash-lite-Klasse, 0.25 / 1.50 | 1,000 / 30 | 1 | $0.00030 | 1.0x | $0.0009 |
| Retrieval-gestützter Entwurf | haiku-Klasse, 1.00 / 5.00 | 10,060 / 560 | 2 | $0.01286 | 4.6x | $0.0386 |
| Multi-Tool-Recherche | sonnet-Klasse, 3.00 / 15.00 | 82,180 / 1,280 | 7 (6 Tool-Aufrufe) | $0.2657 | 8.6x | $0.797 |
| Langdokument-Zusammenfassung | flash-Klasse, 0.30 / 2.50 | 120,300 / 1,500 | 1 | $0.0398 | 1.0x | $0.1195 |
| Browser-Schritt | gpt-5.4-Klasse, 2.50 / 15.00 | 656,760 / 1,690 | 13 (12 Aktionen, 8,000-Token-Snapshots) | $1.667 | 40x | $5.00 |

Das Verhältnis von 40x zwischen erster und letzter Iteration beim Browser-Schritt bestimmt das Design: eine Projektion über den gleitenden Durchschnitt unterschätzt die tödliche Iteration um mehr als eine Größenordnung. Deshalb braucht eine Projektion einen Worst-Case-Zweig, der das Wachstumsmuster vollständig ignoriert, wie der Begleitartikel herleitet.

### Der Sicherheitsfaktor wird abgeleitet, nicht geraten

\`\`\`
S = (n_q / n_p50) ^ alpha        where alpha = dlogC / dlogn

alpha in [1, ~2.3]: it approaches 1 for single-shot
steps, approaches 2 for accumulation-dominated steps,
and exceeds 2 when the first iteration is cheap
relative to the accumulated context.

alpha:  classify ~1.0 | long-doc ~1.0 | RAG draft 1.77
        multi-tool research 1.81 | browser 2.03
\`\`\`

Ein Schritt, dessen p99 doppelt so viele Tool-Aufrufe verwendet wie sein p50, braucht ein S von etwa 2.0, wenn er ein Single-Shot-Schritt ist, aber 3.4 bis 4.1, wenn er tool-lastig ist. Wer "2x" rät, unterdimensioniert systematisch genau die Schritte, die Reserve brauchen. Diese Alphas sind Tangenten am Arbeitspunkt; wer die Sekante über einen beobachteten n-Bereich misst, erhält einen etwas größeren Wert. Sekantenprüfung gegen das exakte Modell: Recherche von n 7 auf 14 kostet 3.66x (Tangente sagt 2^1.81 = 3.51 voraus); Browser von n 13 auf 26 kostet 4.06x (Tangente sagt 2^2.03 = 4.08 voraus).

Korollar: **eine Verdopplung der erlaubten Iterationen vervierfacht die Geldobergrenze in etwa.** "Lass uns die maximalen Iterationen ein bisschen anheben" ist eine 4x-Budgetentscheidung.

Das macht eine Iterationsobergrenze auch zu einer schlechten Geldobergrenze. Bei einem Plattform-Standardwert von 100 maximalen Iterationen liegt die Obergrenze des Browser-Archetyps bei 40,374,000 Prompt-Tokens = $101.11 für einen Schritt (gegenüber $1.667 erwartet), und die des Recherche-Archetyps bei 15,496,000 Tokens = $46.62 (gegenüber $0.266). Als berechnete Datenpunkte statt als Bereich: das 7.7-Fache des erwarteten n lässt beim Browser-Schritt 61x Geld-Spielraum; das 14.3-Fache lässt 175x beim Recherche-Schritt.

### Die Durchsetzbarkeits-Untergrenze

Weil die Projektion pro Agent zwei Stichproben benötigt und sich selbst verweigert, wenn eine Iteration \`budget/3\` überschreitet, muss das Granularitätsverhältnis erfüllen:

\`\`\`
g = B_step / cost_of_one_iteration  >=  3
\`\`\`

Die Untergrenze für ein Pro-Schritt-Budget liegt also beim 3-Fachen der Worst-Case-Iteration. Gegen die unbeschränkte Worst-Case-Iteration des Modells erreicht keines der fünf Budgets diese Schwelle: Klassifizieren $0.0009 gegenüber Untergrenze $1.04 (g = 0.003), RAG-Entwurf $0.0386 gegenüber $1.56 (g = 0.074), Recherche $0.797 gegenüber $4.68 (g = 0.51), Langdokument $0.1195 gegenüber $1.39 (g = 0.26), Browser $5.00 gegenüber $8.76 (g = 1.71).

**Tabelle 5: Die Durchsetzbarkeits-Untergrenze** (illustrative Katalogpreise und Kontextfenster; setzen Sie Ihre eigenen ein)

| Modellklasse | Worst-Case-Iteration, unbeschränkter Kontext | Minimal durchsetzbares Budget (3x) | Worst-Case-Iteration unter 30K/2K-Zulassungsgrenzen | Minimal durchsetzbares Budget unter Grenzen |
|---|---|---|---|---|
| flash-lite | $0.348 | $1.04 | $0.0105 | $0.032 |
| haiku | $0.520 | $1.56 | $0.040 | $0.120 |
| flash | $0.464 | $1.39 | $0.014 | $0.042 |
| sonnet | $1.560 | $4.68 | $0.120 | $0.360 |
| gpt-5.4 | $2.920 | $8.76 | $0.105 | $0.315 |

Jede Geldobergrenze pro Schritt unterhalb der Untergrenze aus der Unbeschränkt-Spalte ist Buchhaltung, keine Durchsetzung. Die Lösung sind **Zulassungsgrenzen für die Eingaben**, kein größeres Budget: Begrenzt man den zugelassenen Prompt auf 30K Tokens und \`max_tokens\` auf 2K, fällt die Untergrenze um das 13- bis 33-Fache.

Aber Zulassungsgrenzen verändern das Kostenprofil des Schritts selbst, also muss \`B_step\` unter ihnen neu hergeleitet werden, und die Grenzen müssen überhaupt erst mit dem Schritt vereinbar sein:

- **Recherche-Schritt**: wie beschrieben vereinbar. Sein größter Iterations-Prompt liegt bei etwa 21K, unter der 30K-Grenze, sein Budget bleibt also unverändert und g steigt von 0.51 auf 6.6.
- **Browser-Schritt**: durchbricht 30K etwa bei Iteration 4 (jeder Snapshot fügt 8,120 Tokens hinzu). Kürzt man auf die letzten drei Snapshots, fällt die größte Iteration auf etwa 26K, die erwarteten Kosten auf $0.754, das S=3-Budget auf $2.26 und g auf 21.5.
- **Langdokument-Schritt**: eine 30K-Prompt-Grenze weist ihn schlicht ab, da seine einzige Iteration 120K Tokens umfasst. Begrenzt man den zugelassenen Prompt auf seine eigene Eingabegröße, bleibt g = 2.8, unter der Untergrenze. Sein n ist auf 1 fixiert, die Stellschraube ist dort also die Eingabegröße selbst, nicht eine Geldobergrenze.

Die Zwei-Regime-Regel: Schritte unterhalb des Übergangspunkts sind konstruktionsbedingt beschränkt (n fixiert auf 1 oder 2, kleines \`I\`) und sollten über die Begrenzung der Eingaben kontrolliert werden; nur bei den Schritten darüber leistet eine Geldobergrenze echte Arbeit. **Eingaben bei billigen Schritten begrenzen, Geld bei teuren.**

Eine Klarstellung dazu, was "standardmäßig aus" in dieser Implementierung bedeutet, denn man versteht es leicht falsch herum. Die Worst-Case-Obergrenze ist **immer aktiv** für jedes Modell, dessen Katalogzeile ein Kontextfenster und maximale Ausgabe-Tokens enthält: beide Guards nehmen bedingungslos \`max(growth, lastDelta*2, worstCase)\`. Was standardmäßig ausgeschaltet ausgeliefert wird, ist das separate fail-**closed**-Verhalten für Modelle, denen *diese Metadaten fehlen* (\`requireCtxWindow\`). Der dokumentierte Grund ist ein Migrationsfenster: ältere Preis-Momentaufnahmen ohne diese Spalten würden sonst jeden Chat-Durchlauf verweigern.

### Quantile, Stichproben und sich aufschaukelnde Fehlabbrüche

Die Wahl des Quantils ist die Wahl der Fehlabbruchrate. Wenn \`B_step\` das q-Quantil der beobachteten legitimen Kosten ist, beträgt die Fehlabbruchrate pro Schritt konstruktionsbedingt exakt \`1-q\`. Das ist eine Produktentscheidung, keine statistische.

Bringen Sie das mit dem Sicherheitsfaktor in Einklang, bevor Sie beides verwenden: die obige S-Formel ist der Schätzer, den Sie einsetzen, wenn der *Kosten*-Tail nicht messbar, der *n*-Tail aber bekannt ist. Das q, das Sie für das Quantil wählen, und das \`n_q\`, das Sie in S einspeisen, müssen dasselbe Quantil sein. Für einen Schritt mit festem n ist \`n_q / n_p50 = 1\` und S degeneriert zu 1, das Quantil muss dann stattdessen aus der Varianz der Eingabegröße kommen.

Stichprobenumfang, bevor Sie ein Tail-Quantil nennen können: \`1/sqrt(n(1-q))\` ist der relative Standardfehler der *Überschreitungsanzahl* im Tail, eine Schätzung dieser Anzahl auf plus/minus 30% braucht also \`n ~ 11/(1-q)\`. p90 braucht etwa 111 Läufe, p95 etwa 220, p99 etwa 1,100, p99.5 etwa 2,200. Behandeln Sie diese als untere Schranken: der Fehler auf dem Dollar-*Wert* des Quantils hängt von der Dichte im Tail ab, und bei einer rechtsschiefen Kostenverteilung ist er deutlich schlechter. Unterhalb von etwa 200 Läufen können Sie ein p99 nicht redlich behaupten und sollten stattdessen vom strukturellen Worst Case her dimensionieren.

Fehlabbrüche pro Schritt schaukeln sich auf. Bei k Schritten, jeder auf sein eigenes p99 begrenzt, und unter der Annahme unabhängiger Kosten pro Schritt sowie dass jeder Lauf alle k Schritte ausführt, beträgt der Anteil der Läufe, die irgendwo an eine Obergrenze stoßen, \`1 - q^k\`:

\`\`\`
k = 3   ->  3.0%
k = 10  ->  9.6%
k = 20  -> 18.2%

A p95 per-step cap across 10 steps kills 40.1% of runs.

To hit a 1% run-level target:  q_step = (1 - target)^(1/k)
  k=3  -> p99.666 | k=10 -> p99.900 | k=50 -> p99.980
\`\`\`

Positive Korrelation über Schritte hinweg (eine überdimensionierte Eingabe auf Lauf-Ebene, die mehrere gleichzeitig aufbläht) senkt die tatsächliche Rate, behandeln Sie diese Werte also als das pessimistische Ende.

Dimensionieren Sie anhand der Verteilung, nicht des Mittelwerts: die Kosten pro Schritt sind rechtsschief, der Mittelwert liegt etwa bei p70, und eine Dimensionierung danach tötet ungefähr 30% der legitimen Schritt-Ausführungen, was \`1 - 0.7^k\` der Läufe entspricht.

Erfassen Sie fünf Felder pro Schritt-Ausführung: Prompt-Tokens, Completion-Tokens, Anzahl der Tool-Aufrufe, Modell-ID, terminaler Stop-Grund. Vier der fünf sind genau das, was ein Guard vor der Iteration ohnehin verbraucht: wer durchsetzen kann, kann also auch messen.

Zur Kalibrierung von n gibt es zwei unabhängige Anker: Anthropics eigene Skalierungsregeln (einfache Faktensuche 1 Agent mit 3 bis 10 Tool-Aufrufen; direkte Vergleiche 2 bis 4 Subagenten mit je 10 bis 15 Aufrufen; komplexe Recherche mehr als 10 Subagenten), und eine durchschnittliche Trajektorie zur Behebung eines GitHub-Issues, die nach 40 Schritten einen Kontext-Spitzenwert von 48.4K Tokens erreicht, mit etwa 1.0M über die Trajektorie akkumulierten Tokens ([arXiv 2509.23586](https://arxiv.org/html/2509.23586v1)). Gemessen daran tötet der viel berichtete Standardwert von 25 Supersteps (weiterhin der Schema-Standard der langgraph-sdk, ungefähr 12 Tool-Aufrufe in einer ReAct-Schleife) echte Arbeit, während eine 200-Schritt-Grenze nichts bewirkt.

**Das Vorgehen:**

1. Erfassen Sie Schritt-Ausführungen mit den fünf Feldern.
2. Berechnen Sie das erforderliche Quantil pro Schritt aus Ihrem Ziel auf Lauf-Ebene: \`q_step = (1 - target)^(1/k)\`.
3. Prüfen Sie, ob Ihre Stichprobe das trägt: Sie brauchen mindestens \`11/(1-q_step)\` Läufe, was bei k=10 und einem Ziel von 1% auf Lauf-Ebene ungefähr 11,000 sind. **Falls nicht, hören Sie hier auf und dimensionieren Sie stattdessen vom beschränkten strukturellen Worst Case her (Tabelle 5).** Nutzen Sie das Quantil, das Sie schätzen *können*, nur um zu erkennen, dass die strukturelle Obergrenze viel zu locker ist, nicht um die Obergrenze zu setzen. Das ist der Normalfall, und so zu tun, als wäre es anders, ist genau der Weg, auf dem eine p95-dimensionierte Obergrenze am Ende 40% der Läufe tötet.
4. Falls die Stichprobe es doch trägt, messen Sie alpha durch Regression von \`log(cost)\` auf \`log(n)\` und setzen Sie \`B_step\` auf \`q_step\`.
5. Prüfen Sie \`g >= 3\` gegen die **beschränkte** Worst-Case-Iteration. Schlägt das fehl, fügen Sie Zulassungsgrenzen hinzu, statt das Budget anzuheben, und leiten Sie \`B_step\` unter diesen Grenzen neu her.
6. Setzen Sie die Lauf-Obergrenze (nächster Abschnitt).
7. Überfüttern Sie einen Schritt absichtlich und bestätigen Sie, dass der Stop-Grund auslöst.

## Lauf-Obergrenzen, Fan-out und warum das Summieren der Schritt-Obergrenzen falsch ist

Die korrekte Lauf-Schranke bezieht sich auf Knoten-**Ausführungen** auf dem Worst-Case-Pfad, nicht auf Knoten:

\`\`\`
B_run = max over execution paths P of  sum over nodes v in P of  m_v * B_v

m_v = M inside a split of width M
    = L for a loop body
    = 1 otherwise

Exclusive branches contribute max, not sum.
\`\`\`

Das Summieren der Obergrenzen pro Schritt ist auf drei Arten falsch, und sie weisen in entgegengesetzte Richtungen:

1. **Es unterzählt um exakt M auf dem aufgefächerten Teilgraphen.** Eine 3-Knoten-Pipeline mit einer Summe von $0.837 hat einen realen Worst Case von $41.78, wenn die letzten beiden Knoten in einem 50 breiten Split liegen.
2. **Es überzählt exklusive Zweige**, die niemals beide ausgeführt werden können.
3. **Es ist statistisch unerreichbar.** Für 10 unabhängige lognormale Schritte mit \`p99/p50 = 3\`, jeder auf das 3-Fache seines Medians begrenzt, liegt die Summe der Obergrenzen bei etwa dem 1.88-Fachen des echten p99 der Lauf-Gesamtsumme. Behandeln Sie diesen Multiplikator als richtungsweisend: reale Schritt-Kosten sind teilweise korreliert, was den Abstand verkleinert. Eine Obergrenze, die im Grunde nicht auslösen kann, kann auch kein moderates strukturelles Versagen abfangen.

**Pooling.** Ein unabhängiger Fan-out braucht eine *kleinere* relative Reserve, um den Faktor \`sqrt(M)\`:

\`\`\`
S_run = 1 + (S_step - 1) / sqrt(M)

S_step = 3:  M=5 -> 1.89 | M=10 -> 1.63 | M=50 -> 1.28 | M=200 -> 1.14
\`\`\`

Durchgerechnet: 50 Zweige des Recherche-Schritts (Mittelwert $0.2657), naiv als \`M * B_step\` dimensioniert, ergeben $39.85, die gepoolte Obergrenze liegt aber bei $17.00, eine 2.3x engere Lauf-Obergrenze bei gleichem Risiko. Nennen Sie die Unabhängigkeitsannahme laut und deutlich: wird die Kosten eines Zweigs von einer Eigenschaft auf Lauf-Ebene bestimmt, etwa einer überdimensionierten Eingabe, die an jeden Zweig verteilt wird, sind die Kosten vollständig korreliert und die Ersparnis verschwindet vollständig.

Obergrenzen pro Schritt und pro Lauf haben unterschiedliche Aufgaben, und das bestimmt ihre Größe. Die Schritt-Obergrenze ist justiert, soll gelegentlich auslösen und beschneidet die Ausgabe eines Schritts. Die Lauf-Obergrenze ist ein Schutzschalter, der ungefähr nie auslösen sollte, und jedes Auslösen ist ein Vorfall, der untersucht gehört (M explodiert, eine Schleife ist wieder in den Fan-out eingetreten, eine Eingabe war 100x so groß wie normal).

**Fan-out braucht Zulassungskontrolle, keine Unterbrechung.** Eine als laufende Prüfung durchgesetzte Lauf-Obergrenze tötet Zweige mitten im Flug und liefert eine nichtdeterministische, unvollständige Ergebnismenge: 50 Zweige zu je $0.836 unter einer Lauf-Obergrenze von $10 schließen 11 von 50 ab; unter $20 sind es 23 von 50; und welche gewinnen, hängt von der Startreihenfolge ab. Reserviert man vor dem Spawnen den vollen Betrag \`M * b\`, wird daraus ein "Ausführung verweigert", was explizit und wiederholbar ist.

Der Reservierungsmechanismus, wie in \`BudgetReservationService\` implementiert:

- Beim Spawnen wird der angeforderte Betrag des Kindes atomar auf **jedem Vorfahren** in der Aufruferkette innerhalb einer Transaktion reserviert. Die erste Verweigerung wirft, die Transaktion setzt also die Aktualisierung jedes früheren Vorfahren zurück. Es existiert keine manuelle Kompensation.
- Die damit erkaufte Invariante: die Summe von \`consumed\` über alle Nachfahren von A bleibt in jeder Tiefe innerhalb des Budgets von A, ohne einen Baumdurchlauf zur Laufzeit auf dem heißen Pfad.
- Jede Reservierung pro Vorfahr ist ein einzelnes bedingtes SQL-UPDATE ohne SELECT-dann-UPDATE, es gibt also kein TOCTOU. Es erhöht die reservierte Spalte nur, wenn das freie Budget des Vorfahren die Anforderung deckt:

  \`\`\`
  free = credit_budget - credits_consumed - credits_reserved
  UPDATE ... SET credits_reserved = credits_reserved + :req
   WHERE id = :ancestor
     AND (credit_budget IS NULL OR free >= :req)
  \`\`\`

  Über den Erfolg entscheidet, dass die zurückgegebene Zeilenzahl 1 ist. Ein unbegrenzter Vorfahr trifft mit einem No-op-Schreibvorgang zu und gibt ebenfalls 1 zurück.
- Bemessung der Reservierung: eine explizite Anforderung gewinnt (negative werden abgelehnt); andernfalls ist der Standard das **minimale freie Budget über alle Vorfahren hinweg**, oder null, wenn jeder Vorfahr unbegrenzt ist.
- Die Abrechnung durchläuft dieselbe Kette einmal bei Beendigung des Kindes und erstattet pro Vorfahr die gehaltene Reservierung und bucht die tatsächlichen Kosten in einer einzigen Aktualisierung, wobei \`consumed\` und \`consumed_from_subagents\` mit demselben Delta in derselben Transaktion geschrieben werden, sodass die Invariante konstruktionsbedingt hält. Die Reservierungsspalten sind auf ORM-Ebene als nicht aktualisierbar markiert, damit ein Dirty-Flush sie nicht stillschweigend überschreiben kann.
- Ausgelaufene Reservierungen werden **beim Hochfahren** aufgeräumt, nicht per Timeout: ein zustandsloser Worker kann keine Reservierung besitzen, die älter ist als er selbst, also ist jede beim Start vorhandene, von null verschiedene gehaltene Reservierung definitionsgemäß verwaist und wird in einer Aktualisierung gelöscht. Das Aufräumen darf den Start niemals scheitern lassen.
- Die Aufruferkette selbst liegt auf einem reservierten Schlüssel der Credentials-Map, nächster zuerst, fehlt bei Wurzelaufrufen (die Kaskade ist an der Wurzel also ein No-op) und wird vom spawnenden Agenten für jedes Kind vorangestellt.

Unabhängiger Beleg dafür, dass die Sperre und nicht die Zahl eine Obergrenze echt macht: in einem kontrollierten Experiment, berichtet in einem Preprint von 2026, das 63 Vorfälle katalogisiert ([arXiv 2606.04056](https://arxiv.org/abs/2606.04056)), überschritt ein nicht abgesicherter asyncio-Python-Budgetzähler die Obergrenze 30 von 30 Mal, während ein korrekt gesperrter Python-Zähler und ein affin typisiertes Budget in Rust jeweils 0 von 30 Mal überschritten.

Die daraus folgende Dimensionierungsvorgabe für den Eltern-Agenten: um M Kinder zu spawnen, die je die Obergrenze b halten, braucht der Eltern-Agent zum Spawn-Zeitpunkt ein **freies** Budget von mindestens \`M * b\`, nicht die erwartete Gesamtsumme. Für den Recherche-Archetyp bei M=50 und b=$0.797 braucht der Eltern-Agent $39.85 frei, obwohl der Lauf tatsächlich etwa $13.3 kosten wird. Dimensioniert man einen Eltern-Agenten anhand der erwarteten Ausgaben, werden nur \`1/S\` der Zweige finanziert: bei S=3 finanziert ein freies Budget von $13.29, geteilt durch eine Reservierung von $0.797, 16 von 50 Spawns und verweigert 34.

## Logs lesen: vom Symptom zur falschen Dimension

**Tabelle 6: Symptom und die Budget-Dimension, die falsch ist**

| Was Sie sehen | Welche Dimension falsch ist | Bestätigendes Signal | Wo es behandelt wird |
|---|---|---|---|
| Unsichtbarer Abbruch: normal wirkende Abschlüsse, systematisch kürzere Ausgabe | Terminale Antwort / Observability | Die Verteilung der Stop-Gründe zeigt partielle Stopps, während der persistierte Status COMPLETED lautet | Begleitartikel, der Moment des Aufpralls |
| Zu eng: Budget stoppt bei Iteration 2 oder 3 | Bemessungsquantil / Sicherheitsfaktor | Die Token-Zahlen der abgebrochenen Läufe liegen nahe p50, die Eingaben wirken gewöhnlich | Der Sicherheitsfaktor wird abgeleitet |
| Dekorativ: null Budget-Stopps über ein langes Zeitfenster | Größe der Obergrenze | Keine Verweigerungen im beobachteten Fenster; höchste beobachtete Lauf-Kosten weit unter der Obergrenze | Begleitartikel, der Eingangstest |
| Überschreitung / späte Obergrenze: die realisierten Kosten übersteigen die Obergrenze im Tail um einen konstanten, modellgroßen Betrag | Durchsetzungspunkt / Projektion | Am schlimmsten genau dort, wo der Schritt am teuersten ist | Begleitartikel, zur Ein-Iterations-Lücke |
| Falsche Scope-Bindung: Verweigerungen liegen zu ~100% auf der groben Ebene | Scope / Reihenfolge der Guards | Obergrenzen pro Schritt binden nie | Begleitartikel, die fünf Teile eines Budget-Objekts |
| Fan-out-Aushungerung: N Zweige gespawnt, weniger als N Ausführungen | Eltern-Dimensionierung / Reservierungspolitik | Verweigerungen werden der Reservierung des Eltern-Agenten zugeschrieben, nicht dem Budget des Kindes | Lauf-Obergrenzen und Fan-out |
| Reservierungsleck: über Tage steigende Verweigerungen, gleiche Eingaben und gleiches M | Lebenszyklus der Reservierung | Gehaltene Reservierungen werden nie abgerechnet | Lauf-Obergrenzen und Fan-out |
| Falsche Einheit: identische Iterationszahlen, Kostenspanne über eine Größenordnung | Einheit | Die mittleren Kosten pro Iteration spannen etwa 430x über die Archetypen ($0.00030 Klassifizieren gegenüber $0.128 Browser) | Ein Pro-Schritt-Budget bemessen |

Ein konfiguriertes Budget ist kein durchgesetztes, und dafür gibt es ausgelieferte Belege. LiteLLM akzeptierte \`max_budget\` und \`budget_duration\` für Modelle, die dynamisch über seine API hinzugefügt wurden, persistierte die Werte und setzte sie nie durch, während dieselbe Konfiguration in der Startdatei funktionierte ([Issue #25799](https://github.com/BerriAI/litellm/issues/25799), durch einen späteren PR geschlossen). Ein verwandter Defekt betraf Budgets, die nach Ablauf ihrer Dauer nie zurückgesetzt wurden ([#25495](https://github.com/BerriAI/litellm/issues/25495)). Prüfen Sie die Durchsetzung in einem Test. Setzen Sie sie nicht aus dem Vorhandensein eines Feldes voraus.
`;

export default content;
