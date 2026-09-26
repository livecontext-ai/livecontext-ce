import { Workflow, Table2, Store, Share2 } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, Steps, Step, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Interfaces & apps',
  description:
    'Interfaces turn a LiveContext workflow into a real web app: variable mapping feeds data into the page, action mapping sends user input back, and the Applications page lists and shares your apps.',
  path: '/docs/interfaces',
});

export default function InterfacesPage() {
  return (
    <>
      <DocsHero
        eyebrow="Build"
        title="Interfaces & apps"
        lead="An interface is a web page (HTML, CSS, and JavaScript) rendered inside your workflow. The workflow is the backend and the interface is the frontend: you feed data into the page with variable mapping, the page sends user input back with action mapping, and by chaining interfaces you turn a workflow into an app your team, or your customers, can use."
      />

      <DocsProse>
        <h2>What an interface is</h2>
        <p>
          Inside a workflow, an interface is a regular node: it needs at least one incoming edge and
          runs once its predecessors complete or are skipped. It has no ports of its own, it is a
          linear step in the graph. The page itself renders in a sandboxed iframe, separate from the
          rest of the app, and it does not inherit the app&apos;s styles or theme.
        </p>
        <p>
          An interface does not have to live inside a workflow at all. You can create one{' '}
          <strong>standalone</strong> (a landing page, a calculator, a small game) and it works the
          same way, minus the variable and action mapping to a run. Your interfaces are listed on the{' '}
          <strong>Interfaces</strong> page, which is not shown in the sidebar by default: open it from the
          sidebar&apos;s overflow menu, or tick it in the customize panel behind that menu to pin it.
        </p>

        <h2>Two directions of data</h2>
        <p>
          An interface connects to the workflow through two small maps you configure on the node:
          variable mapping feeds workflow data into the page, and action mapping sends user input back
          out.
        </p>

        <h3>Variable mapping: workflow to page</h3>
        <p>
          Map a friendly, generic name to a workflow expression. In the HTML template you use{' '}
          <code>{'{{name|default}}'}</code>; in JavaScript you read the same data from a single
          resolved object.
        </p>
        <CodeBlock language="json" title="variableMapping">{`"variableMapping": {
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
          and so on). All custom JavaScript goes in the node&apos;s <strong>js_template</strong> field,
          which is injected as its own script block and runs after the page renders:
        </p>
        <CodeBlock language="javascript" title="js_template">{`(function () {
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
          mapping name. Use it for loops, conditionals, and any DOM manipulation your template can&apos;t
          express with plain substitution. Your <code>js_template</code> script is injected{' '}
          <em>last</em>, after the platform&apos;s own scripts, so a thrown error in your JS never breaks
          them: the static HTML and the resolved variables are already rendered by that point.
        </p>
        <Callout variant="info">
          A file produced by the workflow (a generated image, a PDF, an upload) arrives in{' '}
          <code>window.__RESOLVED_DATA__</code> already rewritten into a ready-to-use URL string, so{' '}
          <code>{'<img src="${data.photo}">'}</code> just works. Because the rewrite replaces the whole
          value with a URL string, don&apos;t expect to still read <code>.name</code> or{' '}
          <code>.mimeType</code> off it inside <code>js_template</code>: map those separately if you
          need them.
        </Callout>

        <h3 id="feeding-a-page-from-a-code-step">Feeding a page from a Code step</h3>
        <p>
          A Code step&apos;s output is exposed downstream under an extra <code>result</code> key:
          whatever your code returned is read as{' '}
          <code>{'{{core:<label>.output.result.<field>}}'}</code>. If you map the whole output into an
          interface, the page receives that wrapper too.
        </p>
        <Callout variant="warn" title="Map past the result wrapper">
          Mapping <code>{'{"data": "{{core:normalize.output}}"}'}</code> gives the page{' '}
          <code>{'{result: {...your object...}}'}</code>, so <code>window.__RESOLVED_DATA__.data</code>{' '}
          holds a nested <code>result</code>. A script that reads <code>data.listings</code> finds
          nothing and renders its empty state, with no error, on every run. Map past the wrapper
          instead: <code>{'{"data": "{{core:normalize.output.result}}"}'}</code>.
        </Callout>

        <h3>Action mapping: page to workflow</h3>
        <p>
          Map a CSS selector to a single target key describing what should happen when the user
          interacts with that element.
        </p>
        <CodeBlock language="json" title="actionMapping">{`"actionMapping": {
  "#search-form": "trigger:search:submit",
  "#chat-input":  "trigger:chat:message",
  "#next-btn":    "__continue",
  "#to-details":  "interface:details:navigate",
  "#next-page":   "__pagination:next"
}`}</CodeBlock>
        <DocsTable
          caption="Action mapping target keys"
          rowHeaders
          head={['Target key', 'What it does']}
          rows={[
            [<code key="a">trigger:label:submit</code>, 'Binds the form submit event, collects every field with a name attribute, fires the trigger with that data.'],
            [<code key="a2">trigger:label:message</code>, 'On a form, same as submit. On an input or textarea, Enter sends {message: value} and clears the field (Shift+Enter still inserts a newline).'],
            [<code key="a3">trigger:label:click</code>, 'Binds a click event and sends the closest form’s data (or none, for a standalone button).'],
            [<code key="a4">interface:label:navigate</code>, 'Switches the displayed page without touching the run. Frontend-only, no backend call.'],
            [<code key="a5">__continue</code>, 'Resolves the interface’s signal and advances the workflow to the next node.'],
            [<code key="a6">{'__pagination:next|prev'}</code>, 'Moves to the next or previous page of the application. Frontend-only.'],
          ]}
        />
        <p>
          Only <strong>user-initiated</strong> triggers are legal action targets: <code>manual</code>,{' '}
          <code>form</code>, and <code>chat</code>. For <code>__continue</code>, the binding is a
          form&apos;s <code>submit</code> event when the mapped element is itself a{' '}
          <code>{'<form>'}</code>, otherwise it&apos;s a <code>click</code>; either way the element&apos;s
          closest form data (including files) is collected and sent along, and a standalone button with
          no surrounding form sends an empty payload.
        </p>
        <Callout variant="warn">
          The selector is matched against your rendered markup (with a{' '}
          <code>{'[data-action="name"]'}</code> fallback). If nothing matches, the binding is{' '}
          <strong>silently skipped</strong>: no error, the click just does nothing. A submit binding
          specifically needs three things: a real <code>{'<form>'}</code> whose <code>id</code> matches
          the selector, a <code>name</code> attribute on every field you want captured (an{' '}
          <code>id</code> alone is not enough), and a submit button or input inside that form. There is
          no field-rename layer: the name you write in the HTML is the field name downstream.
        </Callout>

        <h2 id="blocking-vs-just-displaying">Blocking vs just displaying</h2>
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
            which suits a results page the user can keep re-submitting.
          </li>
        </ul>
        <p>
          <code>INTERFACE_SIGNAL</code> is the only <em>conditionally</em> blocking signal in the
          engine, unlike a wait timer, a user approval, or a webhook wait, which always block. If a
          non-blocking interface still has un-run successors and its <code>__continue</code> fires
          after the run already completed, the run reopens (<code>COMPLETED</code> to{' '}
          <code>RUNNING</code>), executes the remaining successors, and re-finalizes: it stays{' '}
          <code>RUNNING</code> if a new blocking signal appeared, or returns to <code>COMPLETED</code>{' '}
          otherwise.
        </p>

        <h3><code>navigate</code> vs <code>__continue</code></h3>
        <DocsTable
          caption="navigate compared with __continue"
          rowHeaders
          head={['Aspect', '__continue', 'navigate']}
          rows={[
            ['Advances the workflow', 'Yes, backend call, resolves the signal', 'No, frontend-only'],
            ['Target scope', 'Only triggers of this interface’s own DAG', 'Any interface in the workflow, doesn’t need to share a DAG'],
            ['Use it for', 'Wizards, one step at a time', 'Tabs and multi-page apps that share state'],
          ]}
        />

        <h2>The Application view</h2>
        <p>
          When a run reaches an interface, the workflow&apos;s side panel gets an{' '}
          <strong>Application</strong> tab next to the workflow view: the workflow canvas is hidden and
          only the interface pages show, one after the other. Which page you see is decided like this:
        </p>
        <ul>
          <li>
            <strong>Page order</strong> follows the canvas from left to right: move an interface node on
            the canvas to move its page.
          </li>
          <li>
            <strong>First page</strong>: the interface with <strong>Entry Interface</strong> turned on
            (<code>isEntryInterface: true</code>), otherwise the leftmost interface on the canvas.
          </li>
          <li>
            <strong>While a run moves</strong>: the view follows the interface the run most recently
            reached (running or waiting for the user). Once the run is finished, it stays on the last
            interface that completed; it does not jump back to the entry page.
          </li>
        </ul>

        <h2>Building multi-page apps</h2>
        <Steps>
          <Step n={1} title="Wizard">
            Chain interfaces in the DAG, each with a <code>__continue</code> action. Blocking keeps the
            run <code>RUNNING</code> throughout and advances one page at a time.
          </Step>
          <Step n={2} title="Tabs and multi-page">
            Use <code>navigate</code> so pages share the same run state without advancing the workflow.
          </Step>
          <Step n={3} title="Carousel over a Split">
            Put an interface right after a <a href="/nodes">Split</a> node and it runs once per item:
            each item gets its own signal, and the page renders as a paginated carousel. If the
            interface blocks, every item&apos;s signal must resolve before the run advances past it.
          </Step>
          <Step n={4} title="Fork and merge">
            Several interfaces in parallel behave as an implicit fork; a downstream merge waits for all
            of them (completed or skipped). Blocking interfaces keep the run going until every one&apos;s{' '}
            <code>__continue</code> fires; non-blocking ones auto-advance independently.
          </Step>
        </Steps>
        <p>
          Runs and pages are addressed as <code>(epoch, spawn, item index)</code> triples: each trigger
          fire starts a new epoch and previous results stay browsable, and a Split ahead of an interface
          adds the item index as extra pages.
        </p>

        <h3>Build a two-page app</h3>
        <p>
          This example shows the same data on two pages, a summary and a details page, and lets the user
          switch between them without re-running anything.
        </p>
        <Steps>
          <Step n={1} title="Produce the data">
            In a new workflow, add a <strong>Manual</strong> trigger followed by a Code step labelled{' '}
            <code>Load</code> that returns the data your pages show.
          </Step>
          <Step n={2} title="Add the first page">
            Connect an interface labelled <code>Home</code> after <code>Load</code>. Map its data past the
            Code wrapper (<code>{'"data": "{{core:load.output.result}}"'}</code>), give it a button with{' '}
            <code>id=&quot;to-details&quot;</code>, map <code>#to-details</code> to{' '}
            <code>interface:details:navigate</code>, and turn on <strong>Entry Interface</strong>.
          </Step>
          <Step n={3} title="Add the second page">
            Connect a second interface labelled <code>Details</code> after <code>Load</code> as well, and
            place it to the right of <code>Home</code> on the canvas. Map the same data, and map a{' '}
            <code>#to-home</code> button to <code>interface:home:navigate</code>.
          </Step>
          <Step n={4} title="Run it">
            Run the workflow and open the <strong>Application</strong> tab. <code>Home</code> opens
            first; the buttons switch pages instantly because <code>navigate</code> never calls the
            backend. Neither page uses <code>__continue</code>, so the run completes and both pages stay
            interactive.
          </Step>
        </Steps>

        <h2>Rendering and authoring constraints</h2>
        <p>
          On top of the script stripping described above, every interface automatically gets a small
          set of injected system scripts: a height reporter so the parent can auto-size the iframe, a
          navigation gate, a broken-image fixer (broken <code>{'<img>'}</code> tags fall back to a
          transparent pixel to preserve layout), an optional auto-fit scaler, the action bridge, and the{' '}
          <code>__RESOLVED_DATA__</code> injector. Links behave like this:
        </p>
        <DocsTable
          caption="How links inside an interface behave"
          rowHeaders
          head={['Link type', 'Behavior']}
          rows={[
            ['In-page anchor (#section)', 'Scrolls, allowed.'],
            ['Scheme-less relative link', 'Does nothing: a single embedded page has nowhere to navigate to.'],
            ['External (http/https/mailto/tel, or //host)', 'Gated: the viewer is asked to confirm, then it opens in a new tab.'],
            ['javascript: or empty/# href', 'Blocked.'],
            ['window.open()', 'Gated, allowed only under a genuine user gesture (a real click).'],
          ]}
        />
        <p>Authoring tips that avoid the common layout gotchas:</p>
        <ul>
          <li>
            Include a fixed-width viewport tag, for example{' '}
            <code>{'<meta name="viewport" content="width=1280">'}</code>, whose width matches the
            interface&apos;s format (see below; 1280 when no format is set). The host does not pass a real
            device width to the iframe, so <code>width=device-width</code> misrenders.
          </li>
          <li>
            Set an explicit background and text color on <code>body</code>: the page does not inherit
            the app&apos;s theme.
          </li>
          <li>
            A <strong>fragment</strong> template (one that does not start with{' '}
            <code>{'<!DOCTYPE html>'}</code> or <code>{'<html>'}</code>) gets a small base stylesheet and
            centering CSS on <code>body</code> in the app&apos;s previews, so wrap a full-width or
            top-aligned layout in a single wrapper <code>{'<div>'}</code>. A complete HTML document gets
            nothing injected and keeps its own body layout.
          </li>
          <li>Design desktop-first, with responsive breakpoints around 1024px, 768px, and 480px.</li>
          <li>Google Fonts and Material Icons load fine through a normal <code>{'<link>'}</code> tag.</li>
        </ul>

        <h3>Interface format</h3>
        <p>
          An interface can declare a <strong>Format</strong>: the shape it is designed for. The format
          sets the dimensions of every screenshot, video, and preview of that interface. Pick a preset or
          enter a custom <code>WIDTHxHEIGHT</code> (each side between 16 and 2160 pixels).
        </p>
        <DocsTable
          caption="Interface format presets"
          rowHeaders
          head={['Preset', 'Size (px)']}
          rows={[
            ['classic', '1280 x 800'],
            ['widescreen', '1920 x 1080'],
            ['vertical', '1080 x 1920'],
            ['square', '1080 x 1080'],
            ['portrait', '1080 x 1350'],
            ['mobile', '390 x 844'],
            ['tablet', '820 x 1180'],
            ['desktop', '1440 x 900'],
            ['banner', '1500 x 500'],
            ['social_card', '1200 x 630'],
            ['a4_portrait', '794 x 1123'],
            ['a4_landscape', '1123 x 794'],
          ]}
        />
        <p>
          Leaving the format unset is not the same as <code>classic</code>: with no format, a screenshot
          captures the whole page at 1280 pixels wide however tall it is, while <code>classic</code>{' '}
          captures exactly 1280 x 800 and crops anything below. Leave it unset for a long dashboard or
          report you want captured whole.
        </p>

        <h2>Files & images in the page</h2>
        <p>
          When an interface renders, every file reference reachable through variable mapping is
          automatically converted into a usable URL wherever it lands: in <code>{'<img src>'}</code>,{' '}
          <code>{'<a href>'}</code>, <code>{'<video src>'}</code>, or in{' '}
          <code>window.__RESOLVED_DATA__</code>. Map the file under any friendly name, then reference that
          name in the HTML or iterate a list of them in <code>js_template</code>. Its <code>.name</code>,{' '}
          <code>.mimeType</code>, and <code>.size</code> are safe to drill; its raw storage{' '}
          <code>.path</code> is not. See <a href="/files">Files &amp; storage</a> for the file object
          itself.
        </p>
        <p>
          Files are scoped to your workspace: any member can view a file the page points to, and a
          different workspace is denied. Your access token is never placed in the page&apos;s HTML.
        </p>
        <h3>Uploading files from a form</h3>
        <p>
          An interface form can include <code>{'<input type="file" name="photo">'}</code> like any
          other field. On submit, the file is uploaded and stored the same way any other file is. The
          next step reads it as a normal file reference under{' '}
          <code>{'{{trigger:<label>.output.photo}}'}</code> (a few flat sidecar fields, file URL, name,
          size, and content type, are also emitted alongside it for convenience).
        </p>

        <h2>Interface node outputs</h2>
        <p>
          Every interface node always outputs its <code>interface_id</code>, its{' '}
          <code>action_mapping</code>, whether it <code>is_entry_interface</code>, and its resolved{' '}
          <code>resolved_params</code>. Four more outputs are opt-in, each turned on with a switch on the
          node. They are best-effort: a capture failure leaves the output absent without failing the
          run.
        </p>
        <DocsTable
          caption="Optional interface node outputs"
          rowHeaders
          head={['Switch', 'Output', 'What you get']}
          rows={[
            ['Generate screenshot', <code key="so">screenshot</code>, 'A PNG capture of the rendered page as a file, sized by the interface format, for a downstream step to attach, email, or store.'],
            ['Generate PDF', <code key="po">pdf</code>, 'A PDF export of the rendered page as a file. PDF page size is A4 (default) or Letter, with a Landscape option. The interface format does not apply to the PDF.'],
            ['Generate video', <code key="vo">video</code>, 'An MP4 recording of the page’s animation. Recording stops when the page sets window.__DONE__ = true, or at the maximum duration (default 30 s, 5 to 120 s in the editor). Options: Video format (Auto uses the interface format, or Vertical, Horizontal, Square), Render mode (Smooth, frame by frame, the default; or Real-time), and Frame rate (24, 30 by default, or 60).'],
            ['Expose rendered source', <code key="eo">rendered_html / rendered_css / rendered_js</code>, 'The page source as plain strings, each capped at 256K characters. Variables are filled in the HTML only; the CSS and JS come back exactly as written.'],
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

        <h2>Editing an interface with the assistant</h2>
        <p>
          The assistant can create, read, list, update (replace a whole template), patch, and delete
          interfaces. A patch edits one template (HTML, CSS, or JS) with an ordered list of search and
          replace edits, applied all or nothing. After 10 consecutive successful patches on the same
          interface the assistant is told to stop and ask you what you want changed; an edit that fails
          to match writes nothing and does not count.
        </p>

        <h2>Applications</h2>
        <p>
          A workflow with interfaces becomes an <strong>application</strong> when you publish it to the{' '}
          <a href="/marketplace">marketplace</a>. The <strong>Applications</strong> page lists the apps you
          published and the ones you installed from the marketplace. Like <strong>Interfaces</strong>, it
          is not shown in the sidebar by default: open it from the sidebar&apos;s overflow menu.
        </p>
        <ul>
          <li>
            Filter with <strong>All</strong>, <strong>Installed</strong>, or <strong>Published</strong>,
            and by visibility (<strong>Public</strong>, <strong>Private</strong>).
          </li>
          <li>
            Sort by <strong>Last executed</strong>, <strong>Recently added</strong>, or{' '}
            <strong>Name</strong>, mark favorites, and organize apps in folders.
          </li>
          <li>Open an app to use it full page, with its pages in the Application view.</li>
          <li>
            Select one published app to <strong>Update</strong> it (re-run the publish wizard) or create
            a <strong>Share link</strong>.
          </li>
        </ul>

        <h3>Share an application with a link</h3>
        <Steps>
          <Step n={1} title="Select the app">
            On the <strong>Applications</strong> page, select exactly one app you published.
          </Step>
          <Step n={2} title="Create the link">
            Click <strong>Share link</strong> in the selection bar and copy the link.
          </Step>
          <Step n={3} title="Manage it later">
            Your links are listed under <strong>Settings</strong> &gt; <strong>Public Access</strong>{' '}
            (<strong>Applications</strong> tab) and in the <strong>Shared</strong> tab of the
            notification bell, where you can copy or revoke them.
          </Step>
        </Steps>
        <p>
          Anyone with the link can open and use the app without an account. The app runs under your
          account, so treat the link like a key and revoke it when you no longer need it.
        </p>
        <Callout title="Plan limit">
          On the managed cloud, the number of active share links is capped by plan, across every kind of
          shared link (chats, forms, conversations, and applications): Free 5, Starter 20, Pro 50, Team
          100, Enterprise 200. Self-hosted Community Edition has no cap.
        </Callout>
        <Callout variant="info" title="No embed code">
          Interfaces and applications can&apos;t be embedded on another website. To put LiveContext on
          your own site, give an agent a <strong>Chat Widget</strong> trigger (see{' '}
          <a href="/agents">Agents</a>).
        </Callout>

        <h3>Publishing a standalone interface</h3>
        <p>
          A standalone interface can be published to the marketplace too, with a title and a visibility
          of private (default, not listed), unlisted (reachable by link, not listed), or public (goes
          through platform review before appearing). An interface is its own landing page, there is no
          separate listing page to create. You can charge credits per use, or leave it free.
        </p>

        <h2>Troubleshooting</h2>
        <DocsTable
          caption="Interface troubleshooting"
          rowHeaders
          head={['Symptom', 'Cause', 'Fix']}
          rows={[
            [
              'The page shows its empty state on every run, with no error',
              <>
                The whole output of a Code step is mapped, so the data sits one level deeper, under{' '}
                <code>result</code>.
              </>,
              <>
                Map past the wrapper: <code>{'{{core:<label>.output.result}}'}</code>. See{' '}
                <a href="#feeding-a-page-from-a-code-step">Feeding a page from a Code step</a>.
              </>,
            ],
            [
              'Clicking a mapped button or link does nothing',
              <>
                The selector in action mapping matches no element in the rendered page (nor any{' '}
                <code>data-action</code> attribute), so the binding is skipped without an error.
              </>,
              <>
                Make the selector match your markup exactly, for example <code>#next-btn</code> for{' '}
                <code>{'<button id="next-btn">'}</code>.
              </>,
            ],
            [
              <>A <code>submit</code> action never fires</>,
              <>
                The selector points at the submit button. A <code>submit</code> binding listens on the element it
                matches, and only a <code>{'<form>'}</code> receives the submit event.
              </>,
              <>Map the form&apos;s own <code>id</code>, and keep the submit button inside that form.</>,
            ],
            [
              'A field is missing from the submitted data',
              <>
                Only fields with a <code>name</code> attribute are collected. An <code>id</code> alone is not enough.
              </>,
              <>Add <code>name</code> to every input, select, and textarea you want downstream.</>,
            ],
            [
              <>
                Code in a <code>{'<script>'}</code> tag or an <code>onclick</code> attribute never runs
              </>,
              'Script tags and inline event handlers are removed from the HTML template for security.',
              <>
                Move the code to <code>js_template</code> and attach listeners there with{' '}
                <code>addEventListener</code>.
              </>,
            ],
            [
              'Later steps run before the user has done anything on the page',
              <>
                The interface has no <code>__continue</code> action, so it only displays and does not pause the run.
              </>,
              <>
                Map a button or form to <code>__continue</code>. See{' '}
                <a href="#blocking-vs-just-displaying">Blocking vs just displaying</a>.
              </>,
            ],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={2}>
          <Card icon={Workflow} title="Workflows" href="/workflows">Split, signals, and how a run pauses.</Card>
          <Card icon={Table2} title="Tables & data" href="/tables">Store what your app collects.</Card>
          <Card icon={Store} title="Marketplace" href="/marketplace">Publish your app for others to install.</Card>
          <Card icon={Share2} title="Public access & sharing" href="/public-access">Manage share links and public entry points.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
