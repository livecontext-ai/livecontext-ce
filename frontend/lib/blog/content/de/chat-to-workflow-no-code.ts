// chat-to-workflow-no-code - de
const content = `Du musst keinen Code schreiben, um eine KI-Automatisierung zu bauen. Du musst nur in klarer Sprache sagen, was passieren soll. Das Werkzeug verwandelt diesen Satz in einen Workflow, den du sehen, ausführen und verändern kannst.

Das ist das ganze Versprechen der No-Code-KI-Automatisierung: die Aufgabe beschreiben, ein funktionierendes System bekommen, die Kontrolle behalten.

## Beginne mit dem Ergebnis, nicht mit den Schritten

Die Gewohnheit, die Menschen von älteren Automatisierungswerkzeugen mitbringen, ist, zuerst in Schritten zu denken. Welcher Auslöser, welcher Knoten, welches Feld wird auf welches abgebildet. Das ist hier verkehrt herum.

Beginne mit dem Ergebnis. Sag, wie "fertig" aussieht.

"Wenn eine Support-E-Mail eingeht, lies sie, entscheide, ob es ein Fehler, eine Abrechnungsfrage oder allgemein ist, entwirf eine Antwort im richtigen Ton und lege den Entwurf in eine Prüfwarteschlange für einen Menschen."

Dieser eine Satz genügt zum Anfangen. Du hast das Ziel und die Gestalt der Arbeit beschrieben. Das Werkzeug füllt die Verrohrung aus.

## Du bekommst einen Graphen, keine Blackbox

Wenn du die Aufgabe beschreibst, baut das Werkzeug einen lesbaren Graphen: einen Auslöser, ein paar Schritte, die Verzweigungen dazwischen. Du kannst ihn ansehen und in einem Durchgang verstehen. Das ist wichtiger, als es klingt.

Viele KI-Werkzeuge verbergen die Arbeit. Du tippst eine Anfrage, etwas passiert, und du drückst die Daumen. Wenn es schiefgeht, hast du nichts, was du prüfen könntest.

Hier siehst du jeden Knoten. Du siehst, wo die E-Mail eintritt, wo die Klassifizierung geschieht, welche Verzweigung eine Abrechnungsfrage nimmt, wo der Entwurf geschrieben wird und wo er auf einen Menschen wartet. Nichts ist bloß angedeutet. Wenn ein Schritt existiert, steht er auf der Arbeitsfläche.

## Verfeinern per Chat oder von Hand

Die erste Fassung ist selten die endgültige. Beim Verfeinern zahlt sich No-Code aus.

Du hast zwei Wege, den Workflow zu ändern, und du kannst sie frei mischen:

- **Weiterchatten.** "Markiere außerdem alles, was eine Rückerstattung erwähnt, als dringend." Das Werkzeug fügt die Verzweigung hinzu und verdrahtet sie.
- **Die Knoten direkt bearbeiten.** Öffne den Klassifizierungsschritt und passe die Kategorien an. Öffne den Entwurfsschritt und straffe den Ton. Benenne eine Verzweigung um. Verschiebe einen Schritt nach vorne.

Chatten ist schnell für strukturelle Änderungen. Direktes Bearbeiten ist präzise für kleine Feinabstimmungen. Keines sperrt dich vom anderen aus. Der Graph ist die Quelle der Wahrheit, und beide Wege schreiben in denselben Graphen.

## Jeder Schritt ist eng gefasst, was ihn günstig hält

Ein Workflow ist nicht ein großer Agent, der alles tut. Er ist eine Reihe kleiner Schritte, und jeder Schritt sieht nur, was er braucht.

Der Klassifizierungsschritt sieht den E-Mail-Text und gibt eine Kategorie zurück. Das ist alles, was er braucht, also ist das alles, was er bekommt. Der Entwurfsschritt sieht die E-Mail und die Kategorie. Der Prüfschritt sieht den Entwurf.

Weil jeder Schritt einen schmalen Ausschnitt des Kontexts statt der ganzen Historie bekommt, bleiben die Tokens klein und die Kosten niedrig. Dieselbe Aufgabe läuft etwa zehnmal günstiger, als alles einem Alleskönner-Agenten zu übergeben und zu hoffen, dass er auf Kurs bleibt. Diese Ersparnis hast du nicht von Hand entworfen. Sie fällt daraus ab, dass du die Aufgabe als eng gefassten Graphen baust.

## Wann du doch zu einem Code-Knoten greifst

No-Code deckt den Großteil der Arbeit ab. Es muss nicht alles abdecken, und so zu tun ist der Punkt, an dem sich diese Werkzeuge einen schlechten Ruf einhandeln.

Greif zu einem Code-Knoten, wenn die Logik wirklich mechanisch und exakt ist:

- Eine Nutzlast in die exakte Struktur umformen, die ein anderer Schritt erwartet.
- Eine präzise Berechnung, eine Datumsrechenregel, ein Schwellenwert ohne Unschärfe.
- Ein Format parsen, das die eingebauten Schritte nicht erkennen.

Das sind die Fälle, in denen ein paar Zeilen Code klarer und verlässlicher sind als ein Absatz Anweisungen an ein Modell. Es geht nicht darum, Code zu vermeiden. Es geht darum, keinen Code für die Teile zu schreiben, die eine Beschreibung besser erledigt. Nutze Sprache für Urteilsvermögen. Nutze einen Code-Knoten für Exaktheit.

## Ein konkretes Beispiel: Triage im Support-Postfach

Gehen wir das Support-Beispiel von Anfang bis Ende durch.

**Auslöser.** Eine neue E-Mail landet im Support-Postfach.

**Klassifizieren.** Ein eng gefasster Agent liest die E-Mail und gibt ein Label zurück: Fehler, Abrechnung oder allgemein. Er sieht die E-Mail und sonst nichts.

**Verzweigen.** Der Graph teilt sich anhand dieses Labels in drei Wege. Das ist eine echte Verzweigung, die du sehen kannst, keine verborgene Entscheidung. Ein Fehler geht einen Weg, Abrechnung einen anderen, allgemein einen dritten.

**Entwerfen.** Auf jeder Verzweigung schreibt ein Schritt eine Antwort im passenden Ton. Die Abrechnungsverzweigung kann zuerst den Kontostatus ziehen. Die Fehlerverzweigung kann einen Link zur Statusseite anhängen.

**Prüfen.** Jeder Entwurf landet in einer Warteschlange. Ein Mensch liest ihn, bearbeitet ihn bei Bedarf und gibt ihn frei. Ohne diese Freigabe erreicht nichts einen Kunden.

**Prüfspur.** Jeder Durchlauf hinterlässt eine Spur: was hereinkam, welches Label es erhielt, welche Verzweigung es nahm, was entworfen wurde, wer freigab.

Das hast du gebaut, indem du es beschrieben hast. Du kannst es lesen, weil es ein Graph ist. Du kannst es ändern, per Chat oder durch Bearbeiten. Und wenn jemand fragt, warum eine bestimmte E-Mail die Antwort bekam, die sie bekam, kannst du genau auf den Weg zeigen, den sie nahm.

Das ist es, was No-Code-KI-Automatisierung bedeuten sollte. Keine Zauberkiste, der du blind vertraust, sondern ein System, das du in Worten beschreibst und dann in Händen hältst.
`;

export default content;
