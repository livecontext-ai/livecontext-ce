import { Workflow, Table2, Store } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, Steps, Step, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Interfaces & apps',
  description:
    'Interfaces turn a LiveContext workflow into a real web app: variable mapping feeds data into the page, action mapping sends user input back, __continue and navigate control the run, and Split turns a page into a carousel.',
  path: '/docs/interfaces',
});

export default function InterfacesPage() {
  return (
    <>
      <DocsHero
        eyebrow="Build"
        title="Interfaces & apps"
        lead="An interface is a sandboxed web page (HTML/CSS/JS) rendered inside your workflow. The workflow is the backend; the interface is the frontend. You feed data into the page with variable mapping, the page sends user input back with action mapping, and by chaining interfaces you turn a workflow into a real app your team, or your customers, can use."
      />

      <DocsProse>
        <h2>What an interface is</h2>
        <p>
          Inside a workflow, an interface is a regular node: it needs at least one incoming edge and
          runs once its predecessors complete or are skipped. It has no ports of its own, it is a
          linear step in the graph. The page itself renders in a sandboxed iframe (<code>allow-scripts</code>{' '}
          only, no <code>allow-same-origin</code>), so it never touches the parent page&apos;s DOM,
          cookies, or storage.
        </p>
        <p>
          An interface does not have to live inside a workflow at all. You can create one{' '}
          <strong>standalone</strong>, a landing page, a calculator, a small game, and it works the
          same way, minus the variable/action mapping to a run.
        </p>

        <h2>Two directions of data</h2>
        <p>
          An interface connects to the workflow through two small maps you configure on the node:
          variable mapping feeds workflow data into the page, and action mapping sends user input back
          out.
        </p>

        <h3>Variable mapping: workflow &rarr; page</h3>
        <p>
          Map a friendly, generic name to a workflow expression. In the HTML template you use{' '}
          <code>{'{{name|default}}'}</code>; in JavaScript you read the same data from a single
          resolved object.
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
          <code>{'{{variable|default}}'}</code> in the HTML template is a simple find-and-replace, not
          an expression language: you can&apos;t put a workflow expression directly in the markup. Map
          it in <strong>variable mapping</strong> first, then reference the friendly name. The default
          after the pipe can&apos;t contain a <code>{'}'}</code> character (the parser stops at the
          first one), so for an object or array default use an empty default (<code>{'{{obj|}}'}</code>)
          and handle the empty case in <code>js_template</code> instead.
        </Callout>
        <p>
          Values are resolved when the page renders: a name that has no match falls back through a
          short chain, first the exact key, then the same key without its <code>type:</code> prefix,
          then a dotted drill into nested objects and arrays (<code>{'{{photo.name}}'}</code>,{' '}
          <code>{'{{images[0]}}'}</code>), then the pipe default, and finally a plain{' '}
          <code>[label]</code> placeholder as a last resort. A step that hasn&apos;t run yet (or
          failed) resolves the same way, through its default.
        </p>
        <p>
          If a form on this same interface was already submitted once (in an earlier epoch of the
          same run), its fields pre-fill: matching <code>name</code> attributes on inputs, textareas
          and selects are populated with the previous submission automatically.
        </p>

        <h3>js_template and window.__RESOLVED_DATA__</h3>
        <p>
          Any <code>{'<script>'}</code> tag you write directly in the HTML template is stripped for
          security, and so is every inline event handler (<code>onclick</code>, <code>onerror</code>,
          &hellip;). All custom JavaScript goes in the node&apos;s <strong>js_template</strong> field,
          which is injected as its own script block and runs after the page renders:
        </p>
        <CodeBlock language="javascript">{`// js_template - runs after the HTML renders
(function () {
  var data = window.__RESOLVED_DATA__;
  var list = document.getElementById('results');
  try {
    (data.results || []).forEach(function (item) {
      var el = document.createElement('div');
      el.textContent = item.name;
      list.appendChild(el);
    });
  } catch (e) {
    // always guard JSON.parse / __RESOLVED_DATA__ access
  }
})();`}</CodeBlock>
        <p>
          <code>window.__RESOLVED_DATA__</code> holds every resolved variable, keyed by its friendly
          mapping name, use it for loops, conditionals, and any DOM manipulation your template can&apos;t
          express with plain substitution. Your <code>js_template</code> script is injected{' '}
          <em>last</em>, after the platform&apos;s own height reporter, navigation gate, broken-image
          fixer and bridge scripts, so a thrown error in your JS never breaks those, the static HTML and
          the resolved variables are already rendered by that point.
        </p>
        <Callout variant="info">
          A file produced by the workflow (a generated image, a PDF, an upload) arrives in{' '}
          <code>window.__RESOLVED_DATA__</code> already rewritten into a ready-to-use URL string, so{' '}
          <code>{'<img src="${data.photo}">'}</code> just works. Because the rewrite replaces the whole
          value with a URL string, don&apos;t expect to still read <code>.name</code> or{' '}
          <code>.mimeType</code> off it inside <code>js_template</code>, map those separately if you
          need them.
        </Callout>
        <Callout variant="warn">
          <strong>The double-<code>result</code> wrapper.</strong> A Code node&apos;s output is exposed
          downstream as <code>{'{{core:<label>.output.result.<field>}}'}</code>, the engine wraps
          whatever your code returned under an extra <code>result</code> key. Mapping{' '}
          <code>{'{"data": "{{core:normalize.output}}"}'}</code> straight into an interface therefore
          resolves to <code>{'{result: {...your object...}}'}</code>, and{' '}
          <code>window.__RESOLVED_DATA__.data</code> ends up holding a silently double-nested{' '}
          <code>result.result</code>. The page then reads <code>data.data.listings</code>, finds
          nothing, and renders an empty state with no error, every run, byte-identical. Map past the
          wrapper instead: <code>{'{"data": "{{core:normalize.output.result}}"}'}</code>.
        </Callout>

        <h3>Action mapping: page &rarr; workflow</h3>
        <p>
          Map a CSS selector to a single target key describing what should happen when the user
          interacts with that element.
        </p>
        <CodeBlock language="json">{`"actionMapping": {
  "#search-form": "trigger:search:submit",
  "#chat-input":  "trigger:chat:message",
  "#next-btn":    "__continue",
  "#to-details":  "interface:details:navigate",
  "#next-page":   "__pagination:next"
}`}</CodeBlock>
        <DocsTable
          head={['Target key', 'What it does']}
          rows={[
            [<code key="a">trigger:label:submit</code>, 'Binds the form submit event, collects every field with a name attribute, fires the trigger with that data.'],
            [<code key="a2">trigger:label:message</code>, 'On a form, same as submit. On an input/textarea, Enter sends {message: value} and clears the field (Shift+Enter still inserts a newline).'],
            [<code key="a3">trigger:label:click</code>, 'Binds a click event and sends the closest form’s data (or none, for a standalone button).'],
            [<code key="a4">interface:label:navigate</code>, 'Switches the displayed page without touching the run. Frontend-only, no backend call.'],
            [<code key="a5">__continue</code>, 'Resolves the interface’s signal and advances the workflow to the next node.'],
            [<code key="a6">{'__pagination:next|prev|first|last'}</code>, 'Moves the carousel/pagination cursor. Frontend-only.'],
          ]}
        />
        <p>
          Only <strong>user-initiated</strong> triggers are legal action targets: <code>manual</code>,{' '}
          <code>form</code>, and <code>chat</code>. For <code>__continue</code>, the binding is a
          form&apos;s <code>submit</code> event when the mapped element is itself a{' '}
          <code>{'<form>'}</code>, otherwise it&apos;s a <code>click</code>; either way the element&apos;s
          closest form data (including files) is collected and sent along, a standalone button with no
          surrounding form sends an empty payload.
        </p>
        <Callout variant="warn">
          The selector is matched against your rendered markup (with a{' '}
          <code>{'[data-action="name"]'}</code> fallback). If nothing matches, the binding is{' '}
          <strong>silently skipped</strong>, no error, the click just does nothing. A submit binding
          specifically needs three things: a real <code>{'<form>'}</code> whose <code>id</code> matches
          the selector, a <code>name</code> attribute on every field you want captured (an{' '}
          <code>id</code> alone is not enough), and a submit button or input inside that form. There is
          no field-rename layer, the name you write in the HTML is the field name downstream.
        </Callout>

        <h2>Blocking vs just displaying</h2>
        <p>
          Whether an interface <em>pauses</em> the workflow is decided by exactly one thing: is{' '}
          <code>__continue</code> one of the action targets?
        </p>
        <ul>
          <li>
            <strong>With <code>__continue</code></strong>: the interface <strong>blocks</strong>. The
            node yields an <code>INTERFACE_SIGNAL</code> and the run waits (<code>AWAITING_SIGNAL</code>)
            until the user clicks continue. This is how you build a wizard.
          </li>
          <li>
            <strong>Without it</strong>: the interface just <strong>displays</strong>. Successors run
            immediately, and the run may reach <code>COMPLETED</code> while the page stays interactive,
            good for a results page the user can keep re-submitting.
          </li>
        </ul>
        <p>
          <code>INTERFACE_SIGNAL</code> is the only <em>conditionally</em> blocking signal in the
          engine, unlike a wait timer, a user approval, or a webhook wait, which always block. If a
          non-blocking interface still has un-run successors and its <code>__continue</code> fires
          after the run already completed, the run reopens (<code>COMPLETED</code>{' '}
          &rarr; <code>RUNNING</code>), executes the remaining successors, and re-finalizes: it stays{' '}
          <code>RUNNING</code> if a new blocking signal appeared, or returns to <code>COMPLETED</code>{' '}
          otherwise.
        </p>

        <h3><code>navigate</code> vs <code>__continue</code></h3>
        <DocsTable
          head={['', '__continue', 'navigate']}
          rows={[
            ['Advances the DAG', 'Yes, backend call, resolves the signal', 'No, frontend-only'],
            ['Target scope', 'Only triggers of this interface’s own DAG', 'Any interface in the workflow, doesn’t need to share a DAG'],
            ['Use it for', 'Wizards, one step at a time', 'Tabs and multi-page apps that share state'],
          ]}
        />

        <h2>Entry interface & Application Mode</h2>
        <p>
          Marking an interface <code>isEntryInterface: true</code> makes it the one shown first in{' '}
          <strong>Application Mode</strong>, a display mode where the workflow canvas is hidden and
          only interface pages show, reached from the &ldquo;Application&rdquo; button on the workflow
          detail view. If no interface is marked as entry, the first one in plan order is used by
          default. When a run completes, the app navigates back to the entry interface so the user can
          submit again.
        </p>

        <h2>Building multi-page apps</h2>
        <Steps>
          <Step n={1} title="Wizard">
            Chain interfaces in the DAG, each with a <code>__continue</code> action. Blocking keeps the
            run <code>RUNNING</code> throughout and advances one page at a time.
          </Step>
          <Step n={2} title="Tabs / multi-page">
            Use <code>navigate</code> so pages share the same run state without advancing the workflow.
          </Step>
          <Step n={3} title="Carousel over a Split">
            Put an interface right after a <a href="/workflows">Split</a> node and it runs once per
            item, each item gets its own signal, and the page renders as a paginated carousel. If the
            interface blocks, every item&apos;s signal must resolve before the run advances past it.
          </Step>
          <Step n={4} title="Fork / merge">
            Several interfaces in parallel behave as an implicit fork; a downstream merge waits for all
            of them (completed or skipped). Blocking interfaces keep the run going until every one&apos;s{' '}
            <code>__continue</code> fires; non-blocking ones auto-advance independently.
          </Step>
        </Steps>
        <p>
          Runs and pages are addressed as <code>(epoch, spawn, item index)</code> triples: each trigger
          fire starts a new epoch and previous results stay browsable, a Split ahead of an interface
          adds the item index as extra pages.
        </p>

        <h2>Sandbox & authoring constraints</h2>
        <p>
          The iframe sandbox allows scripts only, no same-origin access, no popups, no top-level
          navigation. On top of the script-stripping described above, every interface automatically
          gets a small set of injected system scripts: a height reporter so the parent can auto-size
          the iframe, a navigation gate, a broken-image fixer (broken <code>{'<img>'}</code> tags fall
          back to a transparent pixel to preserve layout), an optional auto-fit scaler, the action
          bridge, and the <code>__RESOLVED_DATA__</code> injector.
        </p>
        <DocsTable
          head={['Link type', 'Behavior']}
          rows={[
            ['In-page anchor (#section)', 'Scrolls, allowed.'],
            ['Scheme-less relative link', 'Does nothing, a single embedded page has nowhere to navigate to.'],
            ['External (http/https/mailto/tel, or //host)', 'Gated: the viewer is asked to confirm, then it opens in a new tab.'],
            ['javascript: or empty/# href', 'Blocked.'],
            ['window.open()', 'Gated, allowed only under a genuine user gesture (a real click).'],
          ]}
        />
        <p>Authoring tips that avoid the common layout gotchas:</p>
        <ul>
          <li>
            Include <code>{'<meta name="viewport" content="width=1280">'}</code>. The host does not
            pass a real device width to the iframe, so <code>width=device-width</code> misrenders.
          </li>
          <li>
            Set an explicit background and text color on <code>body</code>, the page does not inherit
            the app&apos;s theme.
          </li>
          <li>
            The platform prepends centering flex CSS to the body, so wrap a full-width or top-aligned
            layout in a single wrapper <code>{'<div>'}</code>.
          </li>
          <li>Design desktop-first, with responsive breakpoints around 1024px, 768px, and 480px.</li>
          <li>Google Fonts and Material Icons load fine through a normal <code>{'<link>'}</code> tag.</li>
        </ul>

        <h2>Files & images in the page</h2>
        <p>
          Files (images, audio, PDFs, and more) live in object storage as a canonical file reference
          with a path, name, MIME type, and size. When an interface renders, every file reference
          reachable through variable mapping is automatically converted into a usable URL, an
          authenticated proxy URL inside the logged-in app, or a signed URL on a marketplace or share
          preview, wherever it lands in <code>{'<img src>'}</code>, <code>{'<a href>'}</code>,{' '}
          <code>{'<video src>'}</code>, or in <code>window.__RESOLVED_DATA__</code>.
        </p>
        <p>
          Map the file into <code>variableMapping</code> under any friendly name, then reference that
          name in the HTML or iterate a list of them in <code>js_template</code>. Its <code>.name</code>,{' '}
          <code>.mimeType</code>, and <code>.size</code> are safe to drill; its raw storage{' '}
          <code>.path</code> is not, it is a bare storage key with no authentication attached.
        </p>
        <p>
          The proxy is scoped to your workspace: any member can view a file it points to, a different
          workspace gets denied. The access token itself is never placed in the iframe&apos;s HTML,
          the embedding component resolves the opaque file URL to a data URI behind the scenes with the
          right auth header attached.
        </p>
        <h3>Uploading files from a form</h3>
        <p>
          An interface form can include <code>{'<input type="file" name="photo">'}</code> like any
          other field. On submit, each file is read in the browser and delegated to the app for
          upload, one request per file, then stored the same way any other file is. The next step
          reads the result as a normal file reference under{' '}
          <code>{'{{trigger:<label>.output.photo}}'}</code> (a few flat sidecar fields, file URL, name,
          size, and content type, are also emitted alongside it for convenience).
        </p>
        <Callout variant="info">
          Any inline value large enough (roughly 64&nbsp;KB and up) that looks like base64-encoded
          binary is automatically promoted to a stored file behind the scenes, so you don&apos;t need
          to think about the difference between a small inline value and a large binary payload
          upstream, both end up addressable the same way downstream.
        </Callout>

        <h2>Interface node outputs</h2>
        <p>
          Every interface node always outputs its <code>interface_id</code>, its{' '}
          <code>action_mapping</code>, whether it <code>is_entry_interface</code>, and its resolved{' '}
          <code>resolved_params</code>. Three more outputs are opt-in:
        </p>
        <DocsTable
          head={['Option', 'Output', 'What you get']}
          rows={[
            [<code key="s">generateScreenshot</code>, <code key="so">screenshot</code>, 'A PNG capture of the rendered page as a file, for a downstream step to attach, email, or store. Best-effort: a capture failure or timeout just omits the field, it never fails the run.'],
            [<code key="p">generatePdf</code>, <code key="po">pdf</code>, 'A PDF export of the rendered page as a file, with pdfFormat (page size) and pdfLandscape (orientation) options.'],
            [<code key="e">exposeRenderedSource</code>, <code key="eo">rendered_html / rendered_css / rendered_js</code>, 'The fully-resolved templates as plain strings, the same substitution used for the page itself, each capped at 256 KB.'],
          ]}
        />
        <h3>Reading what the user submitted</h3>
        <p>
          Downstream steps read a submitted action with{' '}
          <code>{'{{interface:<label>.output.<action_name>.<field>}}'}</code>:
        </p>
        <CodeBlock language="text">{`{{interface:my_form.output.submit.name}}
{{interface:my_form.output.submit.email}}`}</CodeBlock>
        <p>
          Each submission is stamped with a <code>fired_at</code> timestamp automatically, and
          multiple submissions of the same action accumulate rather than overwrite one another. Firing
          a regular action (not <code>__continue</code>) returns immediately and the interface stays
          active and awaiting further input; only <code>__continue</code> resolves the blocking signal.
        </p>

        <h2>Standalone interfaces & publishing</h2>
        <p>
          An interface doesn&apos;t need a workflow behind it, create one standalone for a landing
          page, a calculator, or any self-contained page. The lifecycle is create, get, list, update
          (replace the whole template), patch (a surgical search-and-replace, one template edit per
          call, up to ten patches per call, all-or-nothing), and delete.
        </p>
        <p>
          Publish an interface to the <a href="/marketplace">marketplace</a> with a title and a
          visibility of private (default, not listed), unlisted (reachable by link, not listed), or
          public (goes through platform review before appearing). An interface is its own landing
          page, there is no separate listing page to create. You can charge credits per use, or leave
          it free. Unpublishing marks the listing inactive: people who already acquired it keep their
          copy, only new installs are blocked.
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
