import { Workflow, Table2, Store } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, Callout, CodeBlock, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Interfaces & apps',
  description:
    'Interfaces turn a LiveContext workflow into a real web app: variable mapping feeds data into the page, action mapping sends user input back, and __continue, navigate, and entry interfaces let you build wizards and multi-page apps.',
  path: '/docs/interfaces',
});

export default function InterfacesPage() {
  return (
    <>
      <DocsHero
        eyebrow="Build"
        title="Interfaces & apps"
        lead="An interface is a web page rendered inside your workflow. The workflow is the backend; the interface is the frontend. You feed data into the page, the page sends user input back, and by chaining interfaces you turn a workflow into a real app your team - or your customers - can use."
      />

      <DocsProse>
        <h2>Two directions of data</h2>
        <p>
          An interface connects to the workflow through two small maps you configure on the node.
        </p>
        <h3>Variable mapping - workflow → page</h3>
        <p>
          Map a friendly name to a workflow expression. In the template you use the friendly name with
          a default; in JavaScript you read it from a single resolved object.
        </p>
        <CodeBlock language="json">{`"variableMapping": {
  "userName": "{{mcp:fetch_user.output.name}}",
  "results":  "{{mcp:search.output.items}}"
}`}</CodeBlock>
        <CodeBlock language="html">{`<h1>Hello {{userName|there}}</h1>
<div id="results"></div>
<!-- in js_template: -->
<script>const data = window.__RESOLVED_DATA__; /* data.results, data.userName */</script>`}</CodeBlock>
        <Callout variant="warn">
          The <code>{'{{variable|default}}'}</code> in the template is a simple find-and-replace, not an
          expression - you can&apos;t put a workflow expression directly in the HTML. Map it in{' '}
          <strong>variable mapping</strong> first, then use the friendly name. Values are resolved when
          the page renders, so only reference steps that have already run.
        </Callout>

        <h3>Action mapping - page → workflow</h3>
        <p>
          Map a CSS selector to what should happen when the user clicks or submits it. The target can
          be a <strong>trigger</strong> (fire it - starts a new epoch with the form data),{' '}
          <code>__continue</code> (advance the run), <code>navigate</code> (switch to another page), or
          a pagination control.
        </p>
        <CodeBlock language="json">{`"actionMapping": {
  "#search-form": "trigger:search:submit",
  "#next-btn":    "__continue",
  "#to-details":  "interface:details:navigate"
}`}</CodeBlock>
        <Callout variant="warn">
          The selector must match a real element in your HTML - if nothing matches, the binding is{' '}
          <strong>silently skipped</strong> and the click does nothing. Check your selectors against
          the markup.
        </Callout>

        <h2>Blocking vs. just displaying</h2>
        <p>
          Whether an interface <em>pauses</em> the workflow is decided by one thing: is{' '}
          <code>__continue</code> one of the action targets?
        </p>
        <ul>
          <li>
            <strong>With <code>__continue</code></strong> - the interface <strong>blocks</strong>: the
            run waits until the user advances. This is how you build a wizard.
          </li>
          <li>
            <strong>Without it</strong> - the interface just <strong>displays</strong>; the run
            continues (and may finish) while the page stays interactive. Good for a results page the
            user can re-submit to refine.
          </li>
        </ul>

        <h2>Building multi-page apps</h2>
        <ul>
          <li>
            <strong>Wizard</strong> - chain several interfaces, each with a <code>__continue</code>{' '}
            action. The run stops at each page until the user continues.
          </li>
          <li>
            <strong>Tabs</strong> - use <code>navigate</code> to switch between pages that share the
            same run state; <code>navigate</code> does not advance the workflow.
          </li>
          <li>
            <strong>Entry interface</strong> - mark one interface as the entry; in Application Mode
            (where the canvas is hidden and only the pages show) it&apos;s what users see first, and
            the app returns there when a run completes so they can submit again.
          </li>
          <li>
            <strong>Carousel</strong> - put an interface after a <a href="/workflows">Split</a> and
            it renders once per item, with pagination controls to move between them.
          </li>
        </ul>

        <h2>Writing the page</h2>
        <p>
          Interfaces are sandboxed for safety, which shapes how you add behaviour: inline handlers like{' '}
          <code>onclick</code> and any <code>&lt;script&gt;</code> tags in your HTML are{' '}
          <strong>removed</strong>. Put all custom JavaScript in the interface&apos;s{' '}
          <strong>js_template</strong>, which runs after render with the resolved data available on{' '}
          <code>window.__RESOLVED_DATA__</code>:
        </p>
        <CodeBlock language="javascript">{`// js_template - runs after the HTML renders
const data = window.__RESOLVED_DATA__;
const list = document.getElementById('results');
(data.results || []).forEach((item) => {
  const el = document.createElement('div');
  el.textContent = item.name;
  list.appendChild(el);
});`}</CodeBlock>
        <Callout variant="info">
          A file produced by the workflow (say a generated image) arrives in{' '}
          <code>window.__RESOLVED_DATA__</code> as a ready-to-use URL, so{' '}
          <code>{'<img src="${data.photo}">'}</code> just works - no extra handling.
        </Callout>
        <p>
          Links are handled safely too. A link to an external site (an <code>https://</code>,{' '}
          <code>mailto:</code> or <code>tel:</code> address) asks the viewer to confirm, then opens in a
          new tab. In-page anchors like <code>#section</code> still scroll, and bare relative links do
          nothing (an interface is a single page, so there is nowhere to navigate).
        </p>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={Workflow} title="Workflows" href="/workflows">Split, signals, and how a run pauses.</Card>
          <Card icon={Table2} title="Tables &amp; data" href="/tables">Store what your app collects.</Card>
          <Card icon={Store} title="Marketplace" href="/marketplace">Publish your app for others to fork.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
