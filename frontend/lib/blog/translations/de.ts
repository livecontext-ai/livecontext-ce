import type { BlogTranslation } from '../i18n';
import theNicheDataAdvantage from '../content/de/the-niche-data-advantage';
import chatToWorkflowNoCode from '../content/de/chat-to-workflow-no-code';
import fromDatasetToLiveWorkflow from '../content/de/from-dataset-to-live-workflow';
import workflowBeatsDoEverythingAgent from '../content/de/workflow-beats-do-everything-agent';
import smallDataSharpDecisions from '../content/de/small-data-sharp-decisions';
import capAiAgentCostBudgets from '../content/de/cap-ai-agent-cost-budgets';
import aiAgentAuditTrail from '../content/de/ai-agent-audit-trail';

export const deBlog: BlogTranslation = {
  ui: {
    eyebrow: "Notizen aus der Praxis", blogTitle: "Blog", lead: "Notizen zu Nischendaten und den Automatisierungen, die darauf aufbauen. Warum schmale Datensätze breite schlagen und wie man eine Quelle in einen Workflow verwandelt, der sich selbst betreibt.", latest: "Neueste", readThePost: "Beitrag lesen", readMore: "Weiterlesen", allPosts: "Alle Beiträge", minRead: "Min. Lesezeit", by: "Von", and: "und", ctaTitle: "Verwandle deine Nischendaten in eine funktionierende Automatisierung", ctaText: "Beschreibe die Aufgabe im Chat, und LiveContext baut den Workflow vor deinen Augen.", startFree: "Kostenlos starten", metaTitle: "Blog - LiveContext", metaDescription: "Notizen aus der Praxis zu Nischendaten und den Automatisierungen, die darauf aufbauen: warum schmale Datensätze breite schlagen und wie man eine Quelle in einen Workflow verwandelt, der sich selbst betreibt.",
  },
  posts: {
    "the-niche-data-advantage": { title: "Der Vorteil von Nischendaten", excerpt: "Big Data ist Massenware. Die Teams, die nützliche Automatisierungen ausliefern, gewinnen mit kleinen, scharfen Datensätzen, die sich fast niemand die Mühe macht zu strukturieren.", coverAlt: "Ein Laptop zeigt ein Analyse-Dashboard mit Diagrammen, einer Karte und Kennzahlen", content: theNicheDataAdvantage },
    "chat-to-workflow-no-code": { title: "Vom Chat zum Workflow: No-Code-KI-Automatisierung", excerpt: "Beschreibe die Aufgabe in klarer Sprache und erhalte einen Workflow, den du sehen, ausführen und verändern kannst. Keine Knoten von Hand zu verdrahten, keine Blackbox.", coverAlt: "Eine Hand tippt eine Nachricht auf einem Telefon, das eine Chat-Unterhaltung zeigt", content: chatToWorkflowNoCode },
    "from-dataset-to-live-workflow": { title: "Vom Datensatz zum laufenden Workflow", excerpt: "Eine Gestalt in fünf Schritten, um eine statische Nischenquelle in einen Workflow zu verwandeln, der sich selbst auffrischt und in einer echten Aktion endet.", coverAlt: "Eine Hand zeichnet ein Workflow-Diagramm aus verbundenen Kästen und Pfeilen auf einem Whiteboard", content: fromDatasetToLiveWorkflow },
    "workflow-beats-do-everything-agent": { title: "Warum ein Workflow einen Alleskönner-Agenten schlägt", excerpt: "Ein eng gefasster Workflow läuft weit günstiger, bleibt prüfbar und scheitert seltener als ein großer autonomer Agent. Hier ist, wann du welches nutzt.", coverAlt: "Ein einzelner Roboterarm auf einem Ständer, der einen autonomen Agenten darstellt", content: workflowBeatsDoEverythingAgent },
    "small-data-sharp-decisions": { title: "Kleine Daten, scharfe Entscheidungen", excerpt: "Bessere Entscheidungen brauchen selten mehr Daten. Ein kleiner, vertrauenswürdiger Datensatz, der zu einer Wahl führt, schlägt einen riesigen, der das Signal begräbt.", coverAlt: "Hände nutzen einen Taschenrechner neben gedruckten Diagrammen bei der Datenanalyse", content: smallDataSharpDecisions },
    "cap-ai-agent-cost-budgets": { title: "Wie du deckelst, was ein KI-Agent ausgeben kann", excerpt: "Unbegrenzte Agenten sind ein finanzielles Risiko. Gib jedem ein hartes Budget, das er nicht überschreiten kann, und grenze die Werkzeuge und Daten ein, die er berühren darf.", coverAlt: "Münzen verstreut auf einem Schreibtisch neben einem Notizbuch und Stift für die Budgetplanung", content: capAiAgentCostBudgets },
    "ai-agent-audit-trail": { title: "Die Prüfspur, die jeder KI-Agent braucht", excerpt: "Eine funktionierende Vorführung genügt nicht. Protokolliere Eingaben, Werkzeugaufrufe, Ausgaben, Kosten und jede Entscheidung, damit du Fehler suchen, Compliance nachweisen und Vertrauen gewinnen kannst.", coverAlt: "Eine Lupe und ein Taschenrechner liegen auf gedruckten Dokumenten", content: aiAgentAuditTrail },
  },
};
