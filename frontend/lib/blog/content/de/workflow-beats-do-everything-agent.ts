// workflow-beats-do-everything-agent - de
const content = `Die Vorführung eines einzelnen autonomen Agenten ist immer beeindruckend. Du gibst ihm ein Ziel, er denkt nach, er ruft Werkzeuge auf, er kommt mit einer Antwort zurück. Dann setzt du ihn in Produktion ein, die Rechnung trifft ein, die Ergebnisse schwanken, und niemand kann dir sagen, warum er tat, was er tat.

Das Problem ist nicht das Modell. Das Problem ist die Gestalt. Ein Agent, der alles tut, ist für die meiste echte Arbeit die falsche Gestalt.

## Kosten: Der Kontext ist der Zähler

Jedes Mal, wenn ein Agent das Modell aufruft, sendet er seinen Kontext erneut. Die Anweisungen, die Historie, jedes Werkzeugergebnis bisher. Ein Alleskönner-Agent sammelt all das in einer langen Unterhaltung an, und der Kontext wächst mit jedem Schritt.

Du bezahlst diesen Kontext bei jedem Aufruf. Eine Aufgabe mit zehn Schritten kostet nicht zehn kleine Aufrufe. Sie kostet zehn Aufrufe, die jeder einen wachsenden Haufen von allem Vorherigen mitschleppen.

Ein Workflow zerlegt die Aufgabe in eng gefasste Schritte und speist jeden nur mit dem, was er braucht. Der Klassifizierungsschritt sieht die Nachricht. Der Entwurfsschritt sieht die Nachricht und die Kategorie. Der Sendeschritt sieht den freigegebenen Entwurf. Kein Schritt schleppt die ganze Historie mit.

Speise jeden Agenten mit einem schmalen Ausschnitt statt mit dem ganzen Protokoll, und die Tokenzahl fällt stark. In der Praxis läuft dieselbe Aufgabe etwa zehnmal günstiger. Das ist kein Trick. Es ist die direkte Folge davon, nicht für das erneute Senden von Kontext zu bezahlen, den ein Schritt nie nutzt.

## Kontrolle: Deterministisches Verzweigen gegen freies Improvisieren

Ein Alleskönner-Agent entscheidet seinen eigenen Weg zur Laufzeit. Manchmal nimmt er den richtigen. Manchmal erfindet er einen neuen. Du vertraust darauf, dass ein probabilistisches System jedes Mal dieselbe Weichenstellung trifft, und das wird es nicht.

Ein Workflow macht die Weichenstellung ausdrücklich. Eine Abrechnungsfrage geht die Abrechnungsverzweigung hinunter, weil der Graph es so sagt, nicht weil dem Modell in diesem Durchlauf danach war. Das unscharfe Urteil (ist das Abrechnung oder ein Fehler?) geschieht weiterhin innerhalb eines Schritts. Die strukturelle Entscheidung (was mit einem Abrechnungsposten geschieht) liegt fest.

Diese Trennung ist der ganze Sinn. Lass das Modell das tun, was nur ein Modell kann: lesen und urteilen. Lass es nicht die Teile improvisieren, die verlässlich sein müssen.

## Prüfbarkeit: Ein Weg, auf den du zeigen kannst

Wenn ein Agent alles in einer einzigen Schleife tut, ist die Aufzeichnung eine Wand aus Überlegungen und Werkzeugaufrufen. Zu rekonstruieren, was tatsächlich geschah, ist Archäologie.

Ein Workflow gibt dir einen Durchlauf, den du lesen kannst. Hier ist die Eingabe. Hier ist die Verzweigung, die er nahm. Hier ist, was jeder Schritt empfing und zurückgab. Hier sind die Kosten jedes Schritts. Hier ist, wer vor dem Senden freigab. Wenn jemand fragt, warum ein Kunde eine bestimmte Antwort bekam, antwortest du aus der Spur, statt zu raten.

## Fehlersuche: Eine begrenzte Oberfläche

Ein großer Agent, der scheitert, gibt dir ein einziges riesiges Versagen zum Anstarren. War es der Plan, ein schlechtes Werkzeugergebnis, eine verlorene Anweisung zwanzig Züge zuvor? Du kannst es nicht isolieren, weil alles einen Kontext teilt.

Ein Workflow scheitert an einem Knoten. Der Entwurfsschritt erzeugte den falschen Ton, also öffnest du den Entwurfsschritt. Seine Eingaben liegen genau dort. Du änderst diesen Schritt, führst neu aus und lässt den Rest unberührt. Klein, begrenzt und wiederholbar, so wie normale Software-Fehlersuche funktioniert.

## Sei fair: Wann ein einzelner Agent die richtige Wahl ist

Eng gefasste Workflows sind nicht immer die Antwort, und so zu tun ist eine eigene Art von Hype.

Greif zu einem einzelnen autonomen Agenten, wenn:

- **Die Aufgabe wirklich offen ist.** Erkundende Recherche oder Fehlersuche, bei der der nächste Zug ganz vom letzten Ergebnis abhängt. Du kannst die Verzweigungen nicht im Voraus zeichnen, weil sie noch nicht existieren.
- **Der Weg kurz und günstig ist.** Ein einmaliges Nachschlagen oder ein schneller Entwurf braucht keinen Graphen. Ein Graph wäre Mehraufwand.
- **Du die Gestalt noch entdeckst.** Am Anfang lass einen Agenten umherstreifen und beobachte, was er tatsächlich tut. Die stabilen Teile dieses Verhaltens sind genau das, was du später in einen Workflow hebst.

Die ehrliche Regel: Wenn du die Schritte zeichnen kannst, baue einen Workflow. Wenn du sie wirklich noch nicht zeichnen kannst, ist ein Agent das richtige Werkzeug, vorerst.

## Der Hybrid: Der Workflow orchestriert, Agenten übernehmen die unscharfen Teile

Die besten Produktionssysteme sind nicht das eine oder das andere. Sie sind ein Workflow mit Agenten darin.

Der Workflow besitzt die Struktur: die Auslöser, die Verzweigungen, die Zusammenführungen, die Freigaben, die Wiederholungen, das Budget je Schritt. Er ist deterministisch dort, wo Determinismus zählt.

Innerhalb einzelner Knoten übernehmen Agenten die Arbeit, die Urteilsvermögen braucht: diese Nachricht klassifizieren, diese Antwort entwerfen, diese Felder extrahieren, dieses Dokument zusammenfassen. Jeder dieser Agenten ist eng gefasst. Er bekommt eine klare Eingabe, einen kleinen Werkzeugsatz, ein Budget, das er nicht überschreiten kann, und gibt eine klare Ausgabe an den folgenden Schritt zurück.

Du bekommst das Kostenprofil eng gefasster Schritte, die Verlässlichkeit ausdrücklicher Verzweigung und die Überlegung eines Modells genau dort, wo Überlegung hilft. Der Agent erledigt die unscharfe Teilaufgabe. Der Workflow erledigt alles Übrige, und er wird als App ausgeliefert, die du ausführen, überwachen und an jemand anderen übergeben kannst.

Fang damit an zu fragen, welche Teile deiner Arbeit wirklich Urteilsvermögen brauchen. Verpacke jene in eng gefasste Agenten. Verdrahte den Rest als Graphen. Das ist die Gestalt, die den Kontakt mit der Produktion übersteht.
`;

export default content;
