// cap-ai-agent-cost-budgets - de
const content = `Die meisten Horrorgeschichten über KI-Kosten teilen eine gemeinsame Ursache: einen Agenten ohne Obergrenze. Er lief in Schleifen, er wiederholte, er schleppte einen riesigen Kontext herum, und niemand fand es heraus, bis es die Rechnung tat. Die Abhilfe ist kein klügeres Modell. Es ist ein hartes Budget für jeden Agenten, durchgesetzt bevor die Ausgabe geschieht, nicht danach.

## Warum unbegrenzte Agenten ein finanzielles Risiko sind

Ein autonomer Agent entscheidet seinen eigenen nächsten Schritt. Das ist die Stärke, und es ist auch die Gefahr. Drei Versagensmuster verwandeln eine normale Aufgabe in einen offenen Hahn.

**Schleifen.** Der Agent versucht etwas, es klappt nicht, er versucht eine Variante, und er macht weiter. Ohne Grenze verbrennt er Aufrufe auf der Jagd nach einem Ziel, das er nicht erreichen kann.

**Wiederholungen.** Ein wackliges Werkzeug oder ein Ratenlimit löst eine Wiederholung aus. Wiederholungen stapeln sich. Was wie ein Aufruf aussah, wird zu zwanzig, jeder zahlt die vollen Kontextkosten.

**Lange Kontexte.** Jeder Modellaufruf sendet die ganze bisherige Unterhaltung erneut. Eine Aufgabe, die Historie ansammelt, zahlt bei jedem Schritt mehr als beim vorherigen. Der letzte Aufruf in einem langen Durchlauf kann ein Vielfaches des ersten kosten.

Keines davon ist selten. Es ist das normale Verhalten eines Systems, dem man ein Ziel und keine Obergrenze gibt. Ein Budget verwandelt dieses offene Risiko in eine bekannte, gedeckelte Zahl.

## Was ein Budget pro Agent deckeln sollte

Ein Budget ist nur nützlich, wenn es die Arbeit stoppt, sobald es erreicht ist. Es sollte die Dinge deckeln, die tatsächlich Kosten und Laufzeit treiben:

- **Gesamtausgabe.** Eine harte Obergrenze in Credits oder Tokens. Wenn der Agent sie erreicht, stoppt er. Keine Überschreitung, kein "nur noch ein bisschen mehr".
- **Anzahl der Modellaufrufe.** Deckelt die Schleife direkt. Ein Agent, der keinen einundzwanzigsten Aufruf machen kann, kann nicht ewig schleifen.
- **Werkzeugaufrufe.** Manche Werkzeuge kosten Geld oder treffen externe Kontingente. Deckle, wie oft ein Agent nach ihnen greifen kann.
- **Echtzeit-Laufzeit.** Ein festgefahrener Agent sollte nicht eine Stunde laufen. Setze ein Zeitlimit.

Die Regel, die ein Budget echt macht: Ist die Obergrenze erreicht, hält der Agent an und der Workflow behandelt es. Er macht nicht stillschweigend weiter, und er scheitert nicht leise. Er stoppt, und der Durchlauf hält fest, dass er stoppte, weil er sein Budget erreichte.

## Grenze die Werkzeuge und Daten ein, die ein Agent berühren kann

Budget ist die halbe Antwort. Eingrenzung ist die andere Hälfte, und sie senkt die Kosten, bevor überhaupt eine Obergrenze nötig ist.

Ein Agent, der alles sehen kann, wird versuchen, alles zu nutzen. Gib ihm die ganze Datenbank und er überlegt über die ganze Datenbank, und du bezahlst die Tokens. Gib ihm nur die Werkzeuge und die Daten, die der Schritt braucht, und er bleibt schon von der Bauart her klein.

Für einen Klassifizierungsschritt heißt das den Nachrichtentext und ein Werkzeug, das ein Label zurückgibt. Sonst nichts. Für einen Entwurfsschritt die Nachricht und die Kategorie. Ein eng eingegrenzter Agent ist bei jedem Aufruf günstiger, weil sein Kontext klein ist, und er ist sicherer, weil er nicht in Daten oder Aktionen abschweifen kann, die nicht seine Aufgabe sind.

Eingrenzung verkleinert den Wirkungskreis. Das Budget deckelt, was übrig bleibt. Du willst beides.

## Setze Budgets je Agent und je Durchlauf

Eine Zahl genügt nicht. Du brauchst Budgets auf zwei Ebenen.

**Je Agent.** Jeder Schritt bekommt seine eigene, auf seine Aufgabe zugeschnittene Obergrenze. Eine schnelle Klassifizierung sollte ein winziges Budget haben. Ein Rechercheschritt, der mehrere Dokumente liest, bekommt mehr. Jeden Agenten auf seine tatsächliche Arbeit zuzuschneiden bedeutet, dass ein gieriger Schritt nicht die ganze Zuteilung ausgeben kann.

**Je Durchlauf.** Der gesamte Workflow bekommt ebenfalls eine Obergrenze. Selbst wenn jeder einzelne Agent innerhalb seines eigenen Budgets bleibt, kann ein Durchlauf, der sich in Hunderte paralleler Verzweigungen auffächert, sich summieren. Eine Obergrenze auf Durchlaufebene schützt gegen die Summe, nicht nur gegen die Teile.

Zusammen geben sie dir einen vorhersehbaren Rahmen: einen bekannten schlimmsten Fall je Schritt und einen bekannten schlimmsten Fall für den Durchlauf. Das ist es, was "KI-Kosten" aus einer Überraschung in einen Posten verwandelt, mit dem du planen kannst.

## Überwache die Ausgaben je Agent und je Werkzeug

Budgets stoppen entlaufende Kosten. Überwachung sagt dir, wo die Kosten tatsächlich wohnen, damit du sie feinjustieren kannst.

Verfolge die Ausgaben in feiner Körnung:

- **Je Agent.** Welcher Schritt kostet am meisten? Oft ist es ein Knoten, der mehr tut als nötig, zu viel Kontext trägt oder ein größeres Modell nutzt, als die Aufgabe verlangt.
- **Je Werkzeug.** Welche Werkzeugaufrufe dominieren? Eine einzelne teure externe API, die bei jedem Element aufgerufen wird, kann still zum Großteil der Rechnung werden.
- **Je Durchlauf.** Was kostet ein typischer Durchlauf, und was kostet ein schlechter? Die Lücke zwischen ihnen ist der Ort, an dem sich deine Schleifen und Wiederholungen verstecken.

Mit diesem Blick justierst du gezielt. Kürze den Kontext eines Schritts. Setze ihn auf ein günstigeres Modell herab, wo die Qualität es erlaubt. Füge eine Entdopplungsplanke hinzu, damit ein Werkzeug nicht zweimal für dasselbe Element aufgerufen wird. Jede Änderung ist messbar, weil du die Zahl sich bewegen siehst.

## Bring es zusammen

Entlaufende KI-Kosten sind ein Entwurfsproblem, kein Modellierungsproblem. Du löst es strukturell.

Grenze jeden Agenten auf die Werkzeuge und Daten ein, die seine Aufgabe braucht. Gib jedem Agenten ein hartes Budget, das er nicht überschreiten kann. Setze eine Obergrenze auf den gesamten Durchlauf. Beobachte die Ausgaben je Agent und je Werkzeug, und justiere dort, wohin das Geld tatsächlich fließt.

Tu das, und Kosten hören auf, das zu sein, was dich am Ausliefern hindert. Sie werden zu einer Zahl, die du absichtlich setzt, automatisch durchsetzt und Posten für Posten verteidigen kannst.
`;

export default content;
