import { Workflow, Database, LayoutPanelLeft } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card, Steps, Step, CodeBlock } from '../_components';

export const metadata = docsMetadata({
  title: 'Expressions & variables',
  description:
    'The {{ }} template syntax used across workflows and interfaces: the six output-resolving prefixes, label normalization, nested paths and array indexing, ~44 built-in functions, SpEL operators and collection selection/projection, split/loop/find item aliases, $vars workflow variables, and the code-node double-result gotcha.',
  path: '/docs/expressions',
});

export default function ExpressionsPage() {
  return (
    <>
      <DocsHero
        eyebrow="Reference"
        title="Expressions & variables"
        lead="Workflows and interfaces share one template syntax: {{ ... }}. Inside the braces you reference another node's output, navigate nested paths and array indexes, call built-in functions, and use the full set of comparison, logical, and collection operators."
      />

      <DocsProse>
        <h2>The basic pattern</h2>
        <p>
          Every node&apos;s output is reachable with the same shape:
        </p>
        <CodeBlock language="text">{`{{prefix:label.output.field}}`}</CodeBlock>
        <p>
          <code>prefix</code> identifies the kind of node, <code>label</code> is the node&apos;s name (normalized,
          see below), and <code>.output.</code> is the segment that leads into what the node produced. This
          form is the recommended and unambiguous one. Writing a reference without <code>.output.</code> (for
          example <code>{'{{trigger:start.items}}'}</code> or <code>{'{{agent:a.response}}'}</code>) still
          resolves, because the resolver transparently unwraps the missing <code>output</code> wrapper, but{' '}
          <code>.output.</code> is the form to write and to teach.
        </p>

        <h2>Six output-resolving prefixes</h2>
        <p>
          A node&apos;s label always carries one of seven prefixes. Six of them resolve to real data; the
          seventh, <code>note:</code>, is a valid label prefix that always resolves to <code>null</code> (a
          note produces no output).
        </p>
        <DocsTable
          head={['Prefix', 'Resolves to', 'Node types']}
          rows={[
            [<code key="p">trigger:</code>, 'Trigger output', 'Webhook, chat, schedule, form, datasource, manual, and workflow triggers'],
            [<code key="p">mcp:</code>, 'Tool / API step output', 'Tool and API-call action nodes'],
            [<code key="p">agent:</code>, 'AI step output', 'Agent, Guardrail, Classify'],
            [<code key="p">core:</code>, 'Control-flow node output', 'Decision, Switch, Loop, Split, Merge, Transform, Wait, Fork, and the rest of the core nodes'],
            [<code key="p">table:</code>, 'CRUD operation output', 'Table CRUD nodes (find, read, insert, update, delete rows)'],
            [<code key="p">interface:</code>, 'Interface data', 'Action data and user input captured by an interface node'],
            [<code key="p">note:</code>, <em key="n">always null</em>, 'Note nodes (documentation only, no execution)'],
          ]}
        />
        <Callout variant="info">
          These seven prefixes are the single source of truth used to build every node key. See the{' '}
          <a href="/nodes">Node reference</a> for what each node type actually puts under <code>.output.</code>.
        </Callout>

        <h2>Label normalization</h2>
        <p>
          A node&apos;s human-readable label is turned into the key used in <code>{'{{prefix:label...}}'}</code>{' '}
          by one canonical rule, applied in order:
        </p>
        <Steps>
          <Step n={1} title="Trim">Leading/trailing whitespace is removed.</Step>
          <Step n={2} title="Transliterate accents">Accented characters are converted to their ASCII base form.</Step>
          <Step n={3} title="Lowercase">The whole string is lowercased.</Step>
          <Step n={4} title="Replace non-alphanumerics">Every character that isn&apos;t <code>a-z0-9</code> becomes an underscore.</Step>
          <Step n={5} title="Collapse & trim underscores">Repeated underscores collapse to one; leading/trailing underscores are stripped.</Step>
        </Steps>
        <DocsTable
          head={['Label', 'Normalized key']}
          rows={[
            ['My Label', <code key="a">my_label</code>],
            ['If / else', <code key="a">if_else</code>],
            ['Step-123', <code key="a">step_123</code>],
            ['Entree IDs', <code key="a">entree_ids</code>],
          ]}
        />
        <p>
          Applied to a full reference: <code>coreKey(&quot;Check Status&quot;)</code> becomes{' '}
          <code>core:check_status</code>, <code>mcpKey(&quot;API Call&quot;)</code> becomes{' '}
          <code>mcp:api_call</code>, <code>triggerKey(&quot;My Webhook&quot;)</code> becomes{' '}
          <code>trigger:my_webhook</code>. If you write a reference using the human-readable label with
          spaces, for example <code>{'{{mcp:Fetch Profile.output.data}}'}</code>, it is rewritten to the
          normalized key <code>{'{{mcp:fetch_profile.output.data}}'}</code> before evaluation, and if the
          normalized key doesn&apos;t match anything, the resolver retries with the raw label as a fallback.
        </p>

        <h2>Nested paths & array indexing</h2>
        <p>A reference can walk into nested objects and index into lists, and both combine in a single token:</p>
        <CodeBlock language="text">{`{{mcp:fetch.output.user.name}}
{{mcp:fetch.output.items[0]}}
{{core:split.output.edges[0].node.text}}`}</CodeBlock>
        <p>
          Dotted paths walk nested maps (<code>user.name</code>); <code>key[N]</code> indexes into a list
          after resolving the key. An out-of-range index, or indexing something that isn&apos;t a list,
          resolves to <code>null</code>. SpEL-native collection access also works directly inside{' '}
          <code>{'{{ }}'}</code>: a list index <code>{'items[0].name'}</code> or a map key{' '}
          <code>{"['key']"}</code>.
        </p>

        <h2>Split / loop / find item aliases</h2>
        <p>
          Inside a Split body, two short forms give you the current item and its position without needing
          the node&apos;s label:
        </p>
        <DocsTable
          head={['Short form', 'Canonical unified form', 'Meaning']}
          rows={[
            [<code key="s">{'{{item}}'}</code>, <code key="s">{'{{core:split.output.current_item}}'}</code>, 'The current item'],
            [<code key="s">{'{{item.field}}'}</code>, <code key="s">{'{{core:split.output.current_item.field}}'}</code>, 'A field on the current item'],
            [<code key="s">{'{{index}}'}</code>, <code key="s">{'{{core:split.output.current_index}}'}</code>, '0-based position in the list'],
            ['-', <code key="s">{'{{core:split.output.items}}'}</code>, 'The full list being split'],
          ]}
        />
        <p>
          <code>current_item</code> and <code>current_index</code> are aliases for the same thing:{' '}
          <code>{'{{current_item}}'}</code> and <code>{'{{current_item.field}}'}</code> both resolve
          identically to <code>{'{{item}}'}</code> / <code>{'{{item.field}}'}</code>.
        </p>
        <p>
          A Loop node exposes its 0-based counter as <code>{'{{core:loop.output.iteration}}'}</code> (0 on first entry). Two
          label-less metadata shortcuts also work anywhere inside <code>core:</code>:{' '}
          <code>{'{{core:index}}'}</code> (current item index) and <code>{'{{core:iteration}}'}</code> (current
          iteration). Use the colon form: the equivalent dotted form (<code>{'{{core.index}}'}</code>) is not
          routed to this shortcut and resolves to <code>null</code>.
        </p>
        <p>
          A CRUD <strong>Find</strong> operation spawns per matched row the same way: the current row is{' '}
          <code>{'{{table:find.output.current_item.field}}'}</code>, its index is{' '}
          <code>{'{{table:find.output.current_index}}'}</code>. See{' '}
          <a href="/tables">Tables &amp; data</a> for the CRUD node set.
        </p>
        <Callout variant="info">
          Legacy short forms without <code>.output.</code>, such as <code>{'{{core:label.item}}'}</code> or{' '}
          <code>{'{{core:label.current_item}}'}</code>, still resolve through a backwards-compatibility path,
          but the forms above are the ones to write.
        </Callout>

        <h2>Built-in functions</h2>
        <p>
          Around <strong>44 built-in functions</strong> are available as bare calls inside{' '}
          <code>{'{{ }}'}</code>, for example <code>{'{{uppercase(mcp:fetch.output.name)}}'}</code>. Names are
          lowercase and chosen not to collide with common field names.
        </p>

        <h3>Type casting</h3>
        <DocsTable
          head={['Function', 'Behavior']}
          rows={[
            [<code key="f">int(val)</code>, 'Casts to integer. Null-safe: returns 0 on null or unparseable input.'],
            [<code key="f">long(val)</code>, 'Casts to long. Same null-safety as int.'],
            [<code key="f">double(val)</code>, 'Casts to double. Same null-safety as int.'],
            [<code key="f">float(val)</code>, 'Casts to float. Same null-safety as int.'],
            [<code key="f">string(val)</code>, 'Casts to string. Returns "" on null.'],
            [<code key="f">bool(val)</code>, 'Casts to boolean. Treats true/1/yes/on as true; returns false on null.'],
          ]}
        />

        <h3>Utility</h3>
        <DocsTable
          head={['Function', 'Behavior']}
          rows={[
            [<code key="f">size(val)</code>, 'Length of a string, collection, map, or array.'],
            [<code key="f">len(val)</code>, 'Alias of size().'],
            [<code key="f">typeof(val)</code>, 'Returns one of: null, string, int, double, bool, list, map, array.'],
            [<code key="f">default(val, fallback)</code>, 'fallback when val is null, an empty string, an empty collection, or an empty map.'],
            [<code key="f">coalesce(a, b, ...)</code>, 'First argument that is non-null and non-empty-string.'],
            [<code key="f">ifempty(val, fallback)</code>, 'fallback only when val is null or an empty string (unlike default(), does not treat empty collections/maps as empty).'],
            [<code key="f">isnull(val)</code>, 'True when val is null.'],
            [<code key="f">isempty(val)</code>, 'True when val is null or empty.'],
          ]}
        />

        <h3>Math</h3>
        <DocsTable
          head={['Function', 'Behavior']}
          rows={[
            [<code key="f">abs(val)</code>, 'Absolute value.'],
            [<code key="f">round(val, decimals)</code>, 'Rounds to N decimals; decimals ≤ 0 rounds to the nearest whole number.'],
            [<code key="f">floor(val)</code>, 'Rounds down.'],
            [<code key="f">ceil(val)</code>, 'Rounds up.'],
            [<code key="f">min(a, b)</code>, 'Smaller of two values.'],
            [<code key="f">max(a, b)</code>, 'Larger of two values.'],
            [<code key="f">pow(base, exp)</code>, 'Exponentiation.'],
            [<code key="f">sqrt(val)</code>, 'Square root.'],
          ]}
        />

        <h3>String</h3>
        <DocsTable
          head={['Function', 'Behavior']}
          rows={[
            [<code key="f">uppercase(val)</code>, 'Upper-cases a string.'],
            [<code key="f">lowercase(val)</code>, 'Lower-cases a string.'],
            [<code key="f">capitalize(val)</code>, 'Capitalizes the first letter.'],
            [<code key="f">trim(val)</code>, 'Strips leading/trailing whitespace.'],
            [<code key="f">truncate(val, max, suffix)</code>, 'Cuts a string to max length; suffix defaults to "...".'],
            [<code key="f">padleft(val, len, pad)</code>, 'Pads on the left to len characters.'],
            [<code key="f">padright(val, len, pad)</code>, 'Pads on the right to len characters.'],
            [<code key="f">replace(val, search, repl)</code>, 'Replaces all occurrences of search with repl.'],
            [<code key="f">substring(val, start, end)</code>, 'Extracts a substring.'],
            [<code key="f">split(val, delim)</code>, 'Splits into a list; delim defaults to ",".'],
            [<code key="f">join(coll, delim)</code>, 'Joins a collection into a string.'],
            [<code key="f">startswith(val, prefix)</code>, 'True when val starts with prefix.'],
            [<code key="f">endswith(val, suffix)</code>, 'True when val ends with suffix.'],
            [<code key="f">contains(val, needle)</code>, 'Works on both strings and collections.'],
            [<code key="f">matches(val, regex)</code>, 'True when val matches a regular expression.'],
            [<code key="f">length(val)</code>, 'A universal length function, same as size().'],
          ]}
        />

        <h3>Date &amp; format</h3>
        <DocsTable
          head={['Function', 'Behavior']}
          rows={[
            [<code key="f">formatdate(value, pattern)</code>, 'Formats a date/epoch-millis in UTC; pattern defaults to yyyy-MM-dd. Uppercase letters DD/YYYY/YY in a pattern are normalized to dd/yyyy/yy.'],
            [<code key="f">formatnumber(val, decimals)</code>, 'Formats a number with a fixed decimal count; decimals defaults to 2.'],
            [<code key="f">formatcurrency(val, code)</code>, 'Formats a number as currency; code defaults to EUR.'],
            [<code key="f">now()</code>, 'Current UTC datetime as ISO text, second precision.'],
            [<code key="f">today()</code>, 'Current UTC date as ISO text.'],
          ]}
        />

        <h3>JSON</h3>
        <p>
          <code>json(val)</code> parses a JSON string into a typed map/list/number/boolean/string. It is
          idempotent: an already-typed map, collection, array, number, or boolean is returned unchanged;{' '}
          <code>null</code> stays <code>null</code>; a blank string becomes <code>null</code>.{' '}
          <code>fromjson</code> is a straight alias of <code>json</code>. <code>tojson(val)</code> serializes
          a map/list/scalar to a compact JSON string, the inverse of <code>json()</code>, so{' '}
          <code>json(tojson(val))</code> round-trips (a <code>null</code> input serializes to the literal
          string <code>&quot;null&quot;</code>).
        </p>
        <CodeBlock language="text">{`{{json(mcp:fetch.output.body)}}
{{json('{"responseModalities":["IMAGE"]}')}}`}</CodeBlock>
        <p>
          A typical use is wrapping a raw string field so a tool parameter that expects an object gets typed
          data instead of a literal string. Parsing has hard caps: 256 KB for a single string, 64 levels of
          nesting, 2 MB for the whole document. On a malformed non-blank string, <code>json()</code> /{' '}
          <code>fromjson()</code> raise a parse error shown in the inspector rather than silently returning
          garbage.
        </p>
        <Callout variant="info">
          46 function names are registered, but two are aliases (<code>len</code> for <code>size</code>,{' '}
          <code>fromjson</code> for <code>json</code>), which is why the effective count is ~44 distinct
          functions.
        </Callout>

        <h2>Operators, ternary &amp; collection selection/projection</h2>
        <p>
          Inside <code>{'{{ }}'}</code> you also have the standard Spring Expression Language (SpEL)
          operator set: arithmetic <code>+ - * / %</code>, comparison <code>== != &lt; &gt; &lt;= &gt;=</code>,
          logical <code>&amp;&amp; || !</code>, and the ternary <code>cond ? a : b</code>.
        </p>
        <p>Collection operators run end-to-end over lists resolved from node output:</p>
        <DocsTable
          head={['Operator', 'Name', 'Example', 'Result']}
          rows={[
            [<code key="o">{'.?[predicate]'}</code>, 'Selection (filter)', <code key="o">{'{{users.?[age >= 18]}}'}</code>, 'A filtered list'],
            [<code key="o">{'.![expr]'}</code>, 'Projection (map)', <code key="o">{'{{users.![name]}}'}</code>, 'A mapped list'],
            [<code key="o">{'.^[predicate]'}</code>, 'First match', <code key="o">{"{{headers.^[name == 'From'].value}}"}</code>, 'A single scalar'],
            [<code key="o">{'.$[predicate]'}</code>, 'Last match', <code key="o">{"{{items.$[type == 'a'].id}}"}</code>, 'A single scalar'],
          ]}
        />
        <CodeBlock language="text">{`{{nums.?[#this > 10]}}
{{users.?[age >= 18 and active == true]}}
{{users.?[age >= 18].![name]}}
{{users.?[age >= 18].size()}}`}</CodeBlock>
        <p>
          Selection, projection, and first/last-match chain together (<code>{'{{users.?[age >= 18].![name]}}'}</code>
          {' '}filters then maps), and standard collection methods such as <code>.size()</code> work on the
          result. A selection with no matches returns an empty list, never <code>null</code>.
        </p>
        <Callout variant="warn">
          For safety, dangerous SpEL surfaces are disabled: type references, constructors, and bean
          references are blocked, and instance method calls are restricted to a safe allow-list of
          String/Collection/Map/Number helpers. Calls like <code>getClass()</code>, <code>forName()</code>, or{' '}
          <code>exec()</code> are not reachable from an expression.
        </Callout>

        <h2>$vars: workflow variables</h2>
        <p>
          A workflow can define named variables and reference them from any node with{' '}
          <code>{'{{$vars.name}}'}</code> (n8n-style) or the alias <code>{'{{vars:name}}'}</code>, both
          normalized to the same internal lookup before evaluation. Deep navigation into a JSON-typed
          variable works the same way as any other reference:
        </p>
        <CodeBlock language="text">{`{{$vars.apiKey}}
{{vars:config}}
{{$vars.config.api.url}}`}</CodeBlock>
        <p>
          Workflow variables are stored per run and delivered alongside the rest of the run&apos;s context, so
          they resolve the same way in a node parameter, a condition, or an interface mapping.
        </p>

        <h2>Pure vs mixed evaluation</h2>
        <p>
          When a parameter&apos;s whole trimmed value is exactly one <code>{'{{ ... }}'}</code> block (nothing
          before or after it, no nested <code>{'{{'}</code>), the engine returns the evaluated{' '}
          <strong>typed</strong> object, not text, a number stays a number, a list stays a list. This is the
          only way to pass a real object (a map or a list) into a parameter that expects one.
        </p>
        <p>
          Anything else, an expression embedded in surrounding text, or more than one block, is resolved
          block-by-block and stitched into a single string. In that string context a resolved map or list is
          embedded as valid JSON (<code>{'{"a":1}'}</code>), not a Java-style dump, so it stays parseable
          inside a larger JSON template. An expression that evaluates to <code>null</code> is replaced with
          an empty string. In a condition, a whole-expression <code>null</code> result makes the condition
          false.
        </p>

        <h2>Two different defaults: interface pipe vs default()</h2>
        <p>
          Interfaces and workflow node inputs use the same <code>{'{{ }}'}</code> braces but two different
          fallback mechanisms, and they are not interchangeable:
        </p>
        <DocsTable
          head={['Context', 'Fallback syntax', 'Notes']}
          rows={[
            ['Interface template', <code key="d">{'{{name|fallback}}'}</code>, 'A simple identifier plus one optional pipe-default. No expressions, no functions, not Handlebars.'],
            ['Workflow node input / condition', <code key="d">{"default(var, 'x')"}</code>, 'The SpEL path has no pipe fallback operator, so writing name|fallback here does not act as a default.'],
          ]}
        />
        <Callout variant="warn">
          Using the interface pipe form (<code>{'{{name|fallback}}'}</code>) in a workflow node parameter
          does not do what it looks like: SpEL has no <code>|</code> fallback operator, so an invalid parse
          falls through to <code>null</code> (empty string in a string context). Use{' '}
          <code>{"default(var, 'x')"}</code> instead.
        </Callout>
        <p>
          A file reference resolved into an interface template (a value shaped like{' '}
          <code>{'{path, mimeType}'}</code>) is rendered as just its storage path, so{' '}
          <code>{'<img src="{{photo}}">'}</code> gets a usable URL fragment directly. See{' '}
          <a href="/interfaces">Interfaces &amp; apps</a> for the full templating model.
        </p>

        <h2>Gotchas</h2>
        <Callout variant="warn">
          <strong>Code node double-result wrapper.</strong> A <code>core:code</code> node&apos;s returned
          value is re-exposed downstream as <code>{'{{core:<label>.output.result.<field>}}'}</code>, the
          engine wraps whatever you return under an extra <code>result</code> key. A mapping written as{' '}
          <code>{'{"result":"{{core:normalize.output}}"}'}</code> silently produces a double{' '}
          <code>result.result</code> and reads as empty. Map past the wrapper instead:{' '}
          <code>{'{"result":"{{core:normalize.output.result}}"}'}</code>.
        </Callout>
        <p>
          <strong>Legacy namespace aliases.</strong> In addition to the modern <code>type:</code> prefixes,
          the resolver still recognizes a handful of legacy dotted forms: <code>steps.</code>,{' '}
          <code>triggers.</code>, <code>data.</code>, <code>current_item.</code>, and <code>mcps.</code>. Write
          the modern prefixed form; these exist only for backwards compatibility.
        </p>
        <p>
          <strong>Ordering operators are numeric here, unlike table filters.</strong> Inside a{' '}
          <code>{'{{ }}'}</code> expression, <code>{'{{amount > 9}}'}</code> compares numerically as you&apos;d
          expect. This is a different rule from a Table CRUD <code>where</code> filter, which compares
          lexicographically as text, don&apos;t conflate the two. See{' '}
          <a href="/tables">Tables &amp; data</a> for CRUD filter semantics.
        </p>

        <h2>The in-builder expression editor</h2>
        <p>
          Wherever a parameter accepts an expression, the builder&apos;s expression editor offers categorized
          autocomplete (including <code>json()</code> / <code>fromjson()</code> / <code>tojson()</code>) and a
          syntax guide popover covering variables, type casting, arithmetic, comparison, logical operators,
          math, string functions, utility functions, date/number formatting, collection access, and the
          ternary operator, each with a runnable example. The interface-mapping inspector has its own
          focused function-help popover for the utility functions most useful when wiring data into a page.
        </p>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={Workflow} title="Workflows" href="/workflows">Where node labels and outputs come from.</Card>
          <Card icon={Database} title="Tables & data" href="/tables">CRUD nodes, Find item aliases, and where filters differ from expressions.</Card>
          <Card icon={LayoutPanelLeft} title="Interfaces & apps" href="/interfaces">The pipe-default template model used inside a page.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
