// from-dataset-to-live-workflow - de
const content = `Ein Datensatz wird in dem Moment nützlich, in dem seinetwegen etwas geschieht. Bis dahin ist er eine Datei. Das ist die Gestalt, die wir nutzen, um eine statische Nischenquelle in einen Workflow zu verwandeln, der von selbst läuft und in einer echten Aktion endet.

Um es konkret zu halten, zieht sich ein Beispiel durch alle fünf Schritte: eine wöchentliche Lieferantenpreisliste, die einen Prüf- und Alarm-Workflow auslösen soll. Jeden Montag schicken deine Lieferanten ein aktualisiertes Preisblatt. Heute öffnet jemand jedes einzelne, überfliegt es und meldet sich beim Einkäufer, wenn etwas gesprungen ist. Genau das ist die Art Fron, die sich selbst erledigen sollte.

## Schritt 1: Wähle eine Quelle mit Herzschlag

Wähle Daten, die sich in einem Zeitplan aktualisieren, den du vorhersagen kannst. Ein wöchentlicher Export, eine öffentliche Seite, die sich jeden Morgen auffrischt, ein Postfach, das jeden Montag einen Bericht empfängt. Der Herzschlag ist es, was dir erlaubt, die Auffrischung zu automatisieren, statt Zeilen von Hand zu kopieren. Wenn sich die Quelle nie ändert, brauchst du keinen Workflow, sondern eine Nachschlagetabelle. Spar dir die Mühe.

Sei genau beim Herzschlag. Nicht bloß "wöchentlich", sondern "trifft per E-Mail jeden Montag vor 9 Uhr ein, eine CSV pro Lieferant." Diese Genauigkeit entscheidet über deinen Auslöser. Eine Datei, die in einem Postfach landet, legt einen E-Mail-Auslöser nahe. Eine Seite, die sich auffrischt, legt einen geplanten Abruf nahe. Ein System, das sich melden kann, legt einen Webhook nahe.

**Durchgearbeitetes Beispiel.** Die Lieferantenpreislisten treffen jeden Montagmorgen als E-Mail-Anhänge ein. Das ist ein sauberer, vorhersehbarer Herzschlag. Der Auslöser ist "neue E-Mail von einem bekannten Lieferanten mit einem Preislisten-Anhang." Niemand muss daran denken, etwas zu starten.

## Schritt 2: Einmal normalisieren, am Rand

Rohquellen sind unordentlich. Spaltennamen driften, Daten kommen in drei Formaten, dieselbe Entität erscheint in zwei Schreibweisen, ein Lieferant nennt es "Stückpreis" und ein anderer "Preis/Stk." Erledige die Bereinigung an einer Stelle, genau dort, wo die Daten eintreten, damit jeder nachgelagerte Schritt eine einzige einheitliche Gestalt sieht. Ein kleiner Normalisierungsschritt am Anfang zahlt sich vielfach aus. Alles danach wird einfacher, weil es der Eingabe vertrauen kann.

Lege zuerst die kanonische Gestalt fest, dann bilde jede Quelle darauf ab. Für die Preislisten könnte die kanonische Zeile lauten: Lieferant, Artikelnummer, Beschreibung, Stückpreis, Währung, Gültigkeitsdatum. Wie auch immer das Blatt eines Lieferanten aussieht, der Normalisierungsschritt gibt diese Gestalt aus und sonst nichts. Nachgelagerte Knoten sehen das rohe Durcheinander nie.

**Durchgearbeitetes Beispiel.** Lieferant A liefert eine Excel-Datei mit einer Spalte "Kosten" in Euro. Lieferant B liefert eine CSV mit "Listenpreis" in Dollar. Der Normalisierungsschritt liest jede, rechnet in eine gemeinsame Währung um, parst die Daten und gibt für jeden Lieferanten dieselben sechs sauberen Felder aus. Von hier an weiß und kümmert den Workflow nicht, von welchem Lieferanten eine Zeile stammt.

## Schritt 3: An der Entscheidung verzweigen, nicht an den Daten

Der Sinn des Workflows ist eine Entscheidung. Modelliere diese Entscheidung ausdrücklich. Wenn ein Wert eine Schwelle überschreitet, leite auf den einen Weg. Sonst leite auf den anderen. Teile eine Liste und behandle jedes Element parallel, wenn die Elemente unabhängig sind. Verzweige in getrennte Wege, wenn zwei Dinge gleichzeitig geschehen sollen. Halte die Verzweigung lesbar. Ein Graph, dem dein ganzes Team auf einen Blick folgen kann, ist mehr wert als ein cleverer, den nur sein Autor versteht.

Die Falle hier ist, an den Rohdaten zu verzweigen statt an der Entscheidung. Es interessiert dich nicht, dass der Preis 12,40 beträgt. Es interessiert dich, ob er seit letzter Woche stärker gestiegen ist als deine Toleranz. Also berechne die Entscheidung, dann verzweige daran.

**Durchgearbeitetes Beispiel.** Für jede normalisierte Zeile schlägt der Workflow den Preis der Vorwoche für dieselbe Artikelnummer nach, berechnet die prozentuale Änderung und verzweigt: Ist der Anstieg über fünf Prozent, leite auf den Weg "markieren"; sonst markiere sie als geprüft und mach weiter. Weil jede Artikelnummer unabhängig ist, wird die Liste geteilt und die Zeilen werden parallel geprüft, sodass ein tausendzeiliges Blatt trotzdem in einem Durchgang abgearbeitet ist.

## Schritt 4: In einer Aktion enden, mit einem Menschen dort, wo es zählt

Der letzte Knoten sollte etwas tun: die Benachrichtigung senden, die Zeile aktualisieren, das Ticket anlegen, die Bestellung vorbereiten. Wenn die Aktion riskant oder unumkehrbar ist, halte zuerst zur Freigabe an. Der Durchlauf wartet, bis eine Person abzeichnet, und setzt dann genau dort fort, wo er stehen blieb. Günstige, umkehrbare Aktionen können unbeaufsichtigt laufen. Teure oder einbahnige Aktionen bekommen ein menschliches Tor.

**Durchgearbeitetes Beispiel.** Markierte Preissprünge werden in einer Zusammenfassung gesammelt und an den Einkäufer geschickt: Lieferant, Artikelnummer, alter Preis, neuer Preis, prozentuale Änderung. Ist ein Sprung groß genug, um eine bereits laufende Bestellung automatisch anzuhalten, stoppt der Workflow an einem Freigabeschritt und wartet auf die Bestätigung des Einkäufers, bevor er irgendetwas anfasst. Die alltäglichen senden einfach den Alarm.

## Schritt 5: Das Ergebnis protokollieren, damit der nächste Durchlauf klüger ist

Schreibe das Ergebnis zurück. Eine Tabelle, die der Workflow liest und aktualisiert, wird zu einem gemeinsamen Gedächtnis: Sie merkt sich, was sie bereits verarbeitet hat, sodass der nächste Durchlauf Dubletten überspringt und nur das anfasst, was neu ist. Sie ist auch die Quelle für den Vergleich der nächsten Woche.

**Durchgearbeitetes Beispiel.** Jede verarbeitete Zeile wird in eine Preistabelle geschrieben, verschlüsselt nach Lieferant und Artikelnummer, mit dem Gültigkeitsdatum. Genau diese Tabelle liest Schritt 3, um die "Änderung seit letzter Woche" zu berechnen. Der Workflow speist sich selbst. Er gibt dir außerdem eine saubere Prüfspur: welche Preise sich wann änderten und wer die Reaktion freigab.

## Häufige Fallstricke

- **Kein echter Herzschlag.** Eine Quelle zu automatisieren, die sich selten ändert, fügt bewegliche Teile ohne Gegenwert hinzu. Bestätige die Taktung, bevor du baust.
- **An drei Stellen normalisieren.** Wenn zwei Knoten die Daten je auf ihre eigene Art bereinigen, driften sie und widersprechen sich. Normalisiere einmal, am Rand.
- **An Rohwerten verzweigen.** Berechne die Entscheidung, dann verzweige an der Entscheidung. Schwellenwerte, die in fünf verschiedenen Knoten vergraben sind, lassen sich später unmöglich ändern.
- **Kein menschliches Tor bei unumkehrbaren Aktionen.** Eine Bestellung bei einem fehlerhaften Parse automatisch zu senden, ist der Weg, auf dem sich Automatisierung einen schlechten Ruf einhandelt. Sperre die einbahnigen Schritte mit einem Tor.
- **Das Zurückschreiben vergessen.** Ohne Gedächtnis verarbeitet jeder Durchlauf alles neu und kann keine Änderung erkennen. Das Protokoll ist nicht optional, es ist das, was die Schleife funktionieren lässt.
- **Text vergleichen, als wäre er eine Zahl.** Speichere Preise in einer einheitlichen numerischen Gestalt und vergleiche sie als Zahlen, damit ein Sprung von 9 auf 100 als Anstieg gelesen wird, nicht als Fall.

Das ist das ganze Muster. Eine Quelle mit Herzschlag, ein sauberer Rand, eine ausdrückliche Entscheidung, eine echte Aktion und ein Gedächtnis. Verdrahte diese fünf zusammen und der Datensatz ist keine Datei mehr, die du prüfst. Er wird zu einem System, das für dich arbeitet.
`;

export default content;
