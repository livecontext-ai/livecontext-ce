import { Workflow, Database, LayoutPanelLeft } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card, Steps, Step, CodeBlock } from '../_components';

export const metadata = docsMetadata({
  title: 'Expressions & variables',
  description:
    'The {{ }} template syntax used in workflows and interfaces: node references, label normalization, paths, every built-in function, operators, split item aliases, and workspace variables.',
  path: '/docs/expressions',
});

export default function ExpressionsPage() {
  return (
    <>
      <DocsHero
        eyebrow="Reference"
        title="Expressions & variables"
        lead="Workflows and interfaces share one template syntax: {{ ... }}. Inside the braces you reference another node's output, navigate nested paths and array indexes, call built-in functions, use operators, and read workspace variables."
      />

      <DocsProse>
        <h2>The basic pattern</h2>
        <p>Every node&apos;s output is reachable with the same shape:</p>
        <CodeBlock title="Reference pattern">{`{{prefix:label.output.field}}`}</CodeBlock>
        <p>
          <code>prefix</code> identifies the kind of node, <code>label</code> is the node&apos;s name
          (normalized, see below), and <code>.output.</code> leads into what the node produced. A reference
          without <code>.output.</code> (for example <code>{'{{trigger:start.items}}'}</code>) still resolves
          for backward compatibility, but <code>.output.</code> is the form to write.
        </p>

        <h2>Node prefixes</h2>
        <p>
          A node key carries one of seven prefixes. Six resolve to data; <code>note:</code> always resolves to{' '}
          <code>null</code> because a note produces no output.
        </p>
        <DocsTable
          caption="Node prefixes in expressions"
          rowHeaders
          head={['Prefix', 'Resolves to', 'Node types']}
          rows={[
            [<code key="p">trigger:</code>, 'Trigger output', 'Webhook, chat, schedule, form, table, manual, workflow, and error triggers'],
            [<code key="p">mcp:</code>, 'Integration step output', 'Catalog API and tool steps'],
            [<code key="p">agent:</code>, 'AI step output', 'Agent, Browser Agent, Guardrail, Classify, Generate'],
            [<code key="p">core:</code>, 'Core node output', 'If / else, Switch, While, Split, Merge, Transform, Wait, Fork, Code, and the other core nodes'],
            [<code key="p">table:</code>, 'Table operation output', 'Find, get, create, update, and delete rows'],
            [<code key="p">interface:</code>, 'Interface data', 'Action data and user input captured by an interface node'],
            [<code key="p">note:</code>, <em key="n">always null</em>, 'Notes (documentation only, never executed)'],
          ]}
        />
        <p>
          See the <a href="/nodes">Node reference</a> for what each node type puts under <code>.output.</code>.
        </p>

        <h2>Label normalization</h2>
        <p>
          A node&apos;s label is turned into the key used in <code>{'{{prefix:label...}}'}</code> by one rule,
          applied in order:
        </p>
        <Steps>
          <Step n={1} title="Trim">Leading and trailing whitespace is removed.</Step>
          <Step n={2} title="Transliterate accents">Accented characters become their ASCII base letter.</Step>
          <Step n={3} title="Lowercase">The whole string is lowercased.</Step>
          <Step n={4} title="Replace other characters">Every character that isn&apos;t <code>a-z0-9</code> becomes an underscore.</Step>
          <Step n={5} title="Collapse and trim underscores">Repeated underscores collapse to one; leading and trailing underscores are removed.</Step>
        </Steps>
        <DocsTable
          caption="Label normalization examples"
          rowHeaders
          head={['Label', 'Normalized key']}
          rows={[
            ['My Label', <code key="a">my_label</code>],
            ['If / else', <code key="a">if_else</code>],
            ['Step-123', <code key="a">step_123</code>],
            ['Entrée IDs', <code key="a">entree_ids</code>],
          ]}
        />
        <p>
          So a Core node labelled &ldquo;Check Status&rdquo; is <code>core:check_status</code>, and an
          integration step labelled &ldquo;API Call&rdquo; is <code>mcp:api_call</code>. A reference written
          with the human-readable label, such as <code>{'{{mcp:Fetch Profile.output.data}}'}</code>, is
          rewritten to <code>{'{{mcp:fetch_profile.output.data}}'}</code> before evaluation.
        </p>

        <h2>Nested paths and array indexing</h2>
        <CodeBlock title="Paths">{`{{mcp:fetch.output.user.name}}
{{mcp:fetch.output.items[0]}}
{{core:split.output.edges[0].node.text}}`}</CodeBlock>
        <p>
          Dotted paths walk nested objects; <code>key[N]</code> indexes into a list. An out-of-range index, or
          indexing something that isn&apos;t a list, resolves to <code>null</code>. <code>.length</code> works
          as a property on lists and objects, and on a list of objects <code>list.field</code> returns the
          list of that field&apos;s values.
        </p>

        <h2>Split item aliases</h2>
        <p>Inside a Split body, short forms give you the current item and its position:</p>
        <DocsTable
          caption="Split item aliases"
          rowHeaders
          head={['Short form', 'Full form', 'Meaning']}
          rows={[
            [<code key="s">{'{{item}}'}</code>, <code key="s">{'{{core:split.output.current_item}}'}</code>, 'The current item'],
            [<code key="s">{'{{item.field}}'}</code>, <code key="s">{'{{core:split.output.current_item.field}}'}</code>, 'A field of the current item'],
            [<code key="s">{'{{index}}'}</code>, <code key="s">{'{{core:split.output.current_index}}'}</code>, '0-based position in the list'],
            ['none', <code key="s">{'{{core:split.output.items}}'}</code>, 'The full list being split (also readable after the split)'],
          ]}
        />
        <p>
          <code>item</code> is an alias of <code>current_item</code>, and <code>index</code> is an alias of{' '}
          <code>current_index</code>: <code>{'{{current_item.field}}'}</code> and{' '}
          <code>{'{{item.field}}'}</code> resolve identically. These values exist only inside the split body.
          A While node exposes its 0-based pass counter as <code>{'{{core:loop.output.iteration}}'}</code>.
        </p>
        <Callout variant="info">
          A table <strong>Find Rows</strong> node does not spawn per row: it returns the matching rows as{' '}
          <code>items</code> (with <code>item_count</code>). To process each row, add a Split on{' '}
          <code>{'{{table:find_rows.output.items}}'}</code> and use <code>{'{{item}}'}</code> in its body. See{' '}
          <a href="/tables">Tables &amp; data</a>.
        </Callout>

        <h2>Workspace variables ($vars)</h2>
        <p>
          Variables are reusable values (URLs, IDs, settings) shared by every workflow of a workspace. You
          manage them in <strong>Settings &gt; Credentials &amp; Variables</strong>, on the{' '}
          <strong>Variables</strong> tab, and reference them with <code>{'{{$vars.name}}'}</code> or the
          alias <code>{'{{vars:name}}'}</code>.
        </p>
        <Steps>
          <Step n={1} title="Open the Variables tab">
            Go to <strong>Settings &gt; Credentials &amp; Variables</strong> and select <strong>Variables</strong>.
          </Step>
          <Step n={2} title="Add a variable">
            Click <strong>Add variable</strong>. Enter a <strong>Name</strong> (letters, digits, and underscores,
            not starting with a digit, at most 64 characters), pick a <strong>Type</strong> (Text, Number,
            Boolean, or JSON), and enter the <strong>Value</strong>.
          </Step>
          <Step n={3} title="Reference it">
            Use <strong>Copy reference</strong> in the list, or type <code>{'{{$vars.name}}'}</code> in any node
            field.
          </Step>
        </Steps>
        <CodeBlock title="Variable references">{`{{$vars.apiUrl}}
{{vars:config}}
{{$vars.config.api.url}}`}</CodeBlock>
        <p>
          Variables belong to the workspace you are working in (your personal space or an organization), so
          every workflow of that workspace can read them. A JSON variable can be navigated like any other
          object. Values are stored encrypted and fetched once when a run starts; a run paused for more than
          10 minutes may see updated values when it resumes. Deleting a variable makes references to it
          resolve as empty. Your plan may limit how many variables you can create.
        </p>
        <Callout variant="warn" title="Secret variables">
          Check <strong>Hide value (secret)</strong> to hide a value: it is stored encrypted and never
          displayed again, neither in Settings nor to agents. To change it, enter a new value. Workflows still
          use it when they run, so its resolved value can appear in run outputs: do not route a secret into a
          field whose output others can read.
        </Callout>

        <h2>Whole value vs text</h2>
        <p>
          When a field&apos;s whole value is exactly one <code>{'{{ ... }}'}</code> block, the result keeps
          its <strong>type</strong>: a number stays a number, a list stays a list, an object stays an object.
          This is the only way to pass a real object or list into a field that expects one.
        </p>
        <p>
          When the expression is embedded in text, or there are several blocks, each block is resolved and
          the result is a <strong>string</strong>. In that case a resolved object or list is written as JSON (
          <code>{'{"a":1}'}</code>), and a block that resolves to <code>null</code> becomes an empty string. In a
          condition, a <code>null</code> result counts as false.
        </p>
        <CodeBlock title="Typed vs text">{`{{mcp:fetch.output.items}}              → a list
Found {{size(mcp:fetch.output.items)}} items  → a string, "Found 3 items"`}</CodeBlock>

        <Callout variant="warn" title="Errors resolve to empty, silently">
          If an expression cannot be evaluated (a syntax error, a wrong number of function arguments, a
          comparison between incompatible types), it resolves to <code>null</code>, which is an empty string
          in text and false in a condition. No error is shown. The one exception is <code>json()</code> /{' '}
          <code>fromjson()</code> on malformed JSON, which reports a parse error in the inspector. When a field
          comes out empty, check the expression before the data.
        </Callout>

        <h2>Built-in functions</h2>
        <p>
          46 function names are available as plain calls inside <code>{'{{ }}'}</code>, for example{' '}
          <code>{'{{uppercase(mcp:fetch.output.name)}}'}</code>. Two are aliases (<code>len</code> of{' '}
          <code>size</code>, <code>fromjson</code> of <code>json</code>). Names are case-insensitive (
          <code>formatDate</code> and <code>formatdate</code> are the same function). <code>matches</code> is not among them: it is an
          operator (see below).
        </p>
        <Callout variant="warn" title="Pass every argument">
          Each function takes exactly the arguments shown. Leaving one out makes the expression fail and
          resolve to empty. Where a default is listed, pass <code>null</code> to get it, for example{' '}
          <code>{'truncate(x, 20, null)'}</code>.
        </Callout>

        <h3>Type conversion</h3>
        <DocsTable
          caption="Type conversion functions"
          rowHeaders
          head={['Function', 'Behavior', 'Example']}
          rows={[
            [<code key="f">int(value)</code>, 'Converts to a whole number; a decimal string is truncated. 0 for null or unparseable input.', <code key="e">int(&apos;42.9&apos;) → 42</code>],
            [<code key="f">long(value)</code>, 'Like int, for large whole numbers.', <code key="e">long(&apos;9000000000&apos;) → 9000000000</code>],
            [<code key="f">double(value)</code>, 'Converts to a decimal number. 0.0 for null or unparseable input.', <code key="e">double(&apos;3.5&apos;) → 3.5</code>],
            [<code key="f">float(value)</code>, 'Like double, lower precision.', <code key="e">float(&apos;3.5&apos;) → 3.5</code>],
            [<code key="f">string(value)</code>, 'Converts to text. Empty string for null.', <code key="e">string(42) → &apos;42&apos;</code>],
            [<code key="f">bool(value)</code>, 'true for true, a non-zero number, or the text true, 1, yes, on (any case). false for null.', <code key="e">bool(&apos;yes&apos;) → true</code>],
          ]}
        />

        <h3>Checks and fallbacks</h3>
        <DocsTable
          caption="Check and fallback functions"
          rowHeaders
          head={['Function', 'Behavior', 'Example']}
          rows={[
            [<code key="f">size(value)</code>, 'Length of a text, list, object, or array. 0 for null and for anything else (numbers, booleans).', <code key="e">size(&apos;abc&apos;) → 3</code>],
            [<code key="f">len(value)</code>, 'Alias of size.', <code key="e">len(mcp:fetch.output.items)</code>],
            [<code key="f">length(value)</code>, 'Like size, but a number or boolean is measured as text.', <code key="e">length(12345) → 5</code>],
            [<code key="f">typeof(value)</code>, 'One of null, string, int, double, bool, list, map, array.', <code key="e">typeof(3.5) → &apos;double&apos;</code>],
            [<code key="f">default(value, fallback)</code>, 'fallback when value is null, an empty string, an empty list, or an empty object.', <code key="e">default(trigger:form.output.form_data.city, &apos;Paris&apos;)</code>],
            [<code key="f">ifempty(value, fallback)</code>, 'fallback only when value is null or an empty string (an empty list or object is kept).', <code key="e">ifempty(x, &apos;n/a&apos;)</code>],
            [<code key="f">coalesce(a, b, ...)</code>, 'Any number of arguments. Returns the first one that is not null and not an empty string (an empty list or object counts as a value).', <code key="e">coalesce(core:poll_2.output.data.url, core:poll_1.output.data.url)</code>],
            [<code key="f">isnull(value)</code>, 'true when value is null.', <code key="e">isnull(x)</code>],
            [<code key="f">isempty(value)</code>, 'true when value is null, an empty string, list, object, or array.', <code key="e">isempty(mcp:fetch.output.items)</code>],
          ]}
        />
        <p>
          <code>coalesce</code> is the tool for polling: read the value from every attempt, newest first, and
          you get the latest one that is not empty.
        </p>

        <h3>Math</h3>
        <DocsTable
          caption="Math functions"
          rowHeaders
          head={['Function', 'Behavior', 'Example']}
          rows={[
            [<code key="f">abs(value)</code>, 'Absolute value, as a decimal number.', <code key="e">abs(-3) → 3.0</code>],
            [<code key="f">round(value, decimals)</code>, 'Rounds to decimals places; decimals 0 or less returns a whole number.', <code key="e">round(19.956, 2) → 19.96</code>],
            [<code key="f">floor(value)</code>, 'Rounds down to a whole number.', <code key="e">floor(2.7) → 2</code>],
            [<code key="f">ceil(value)</code>, 'Rounds up to a whole number.', <code key="e">ceil(2.1) → 3</code>],
            [<code key="f">min(a, b)</code>, 'Smaller of two values, as a decimal number.', <code key="e">min(1, 2) → 1.0</code>],
            [<code key="f">max(a, b)</code>, 'Larger of two values, as a decimal number.', <code key="e">max(1, 2) → 2.0</code>],
            [<code key="f">pow(base, exponent)</code>, 'base raised to exponent, as a decimal number.', <code key="e">pow(2, 10) → 1024.0</code>],
            [<code key="f">sqrt(value)</code>, 'Square root, as a decimal number.', <code key="e">sqrt(9) → 3.0</code>],
          ]}
        />

        <h3>Text</h3>
        <DocsTable
          caption="Text functions"
          rowHeaders
          head={['Function', 'Behavior', 'Example']}
          rows={[
            [<code key="f">uppercase(value)</code>, 'Upper-cases the text. Empty string for null.', <code key="e">uppercase(&apos;ab&apos;) → &apos;AB&apos;</code>],
            [<code key="f">lowercase(value)</code>, 'Lower-cases the text. Empty string for null.', <code key="e">lowercase(&apos;AB&apos;) → &apos;ab&apos;</code>],
            [<code key="f">capitalize(value)</code>, 'Upper-cases the first character and lower-cases all the rest.', <code key="e">capitalize(&apos;hELLO wORLD&apos;) → &apos;Hello world&apos;</code>],
            [<code key="f">trim(value)</code>, 'Removes leading and trailing whitespace.', <code key="e">trim(&apos;  a  &apos;) → &apos;a&apos;</code>],
            [<code key="f">truncate(value, max, suffix)</code>, 'Cuts the text to max characters, suffix included; suffix is ... when null.', <code key="e">truncate(&apos;Hello world&apos;, 8, null) → &apos;Hello...&apos;</code>],
            [<code key="f">padleft(value, length, pad)</code>, 'Pads on the left to length characters; pad is a space when null.', <code key="e">padleft(7, 3, &apos;0&apos;) → &apos;007&apos;</code>],
            [<code key="f">padright(value, length, pad)</code>, 'Pads on the right to length characters; pad is a space when null.', <code key="e">padright(&apos;ab&apos;, 4, &apos;.&apos;) → &apos;ab..&apos;</code>],
            [<code key="f">replace(value, search, replacement)</code>, 'Replaces every occurrence of search (plain text, not a pattern).', <code key="e">replace(&apos;a-b-c&apos;, &apos;-&apos;, &apos;/&apos;) → &apos;a/b/c&apos;</code>],
            [<code key="f">substring(value, start, end)</code>, 'Characters from start (inclusive) to end (exclusive); out-of-range indexes are clamped; end null means the end of the text.', <code key="e">substring(&apos;abcdef&apos;, 1, 3) → &apos;bc&apos;</code>],
            [<code key="f">split(value, delimiter)</code>, 'Splits into a list on a plain-text delimiter; , when null.', <code key="e">split(&apos;a,b,c&apos;, &apos;,&apos;) → [a, b, c]</code>],
            [<code key="f">join(list, delimiter)</code>, 'Joins a list into text; , when null.', <code key="e">join(mcp:fetch.output.tags, &apos;, &apos;)</code>],
            [<code key="f">startswith(value, prefix)</code>, 'true when the text starts with prefix.', <code key="e">startswith(&apos;invoice-12&apos;, &apos;invoice&apos;) → true</code>],
            [<code key="f">endswith(value, suffix)</code>, 'true when the text ends with suffix.', <code key="e">endswith(&apos;report.pdf&apos;, &apos;.pdf&apos;) → true</code>],
            [<code key="f">contains(value, search)</code>, 'On a list: true when it holds that exact element. Otherwise: true when the text contains search.', <code key="e">contains(&apos;hello&apos;, &apos;ell&apos;) → true</code>],
          ]}
        />
        <Callout variant="warn" title="Use matches as an operator">
          To test a regular expression, write <code>matches</code> between the value and the pattern:{' '}
          <code>{"{{trigger:form.output.form_data.email matches '[^@]+@[^@]+'}}"}</code>. The pattern must
          match the <strong>whole</strong> text: <code>{"'abc' matches 'b'"}</code> is false, while{' '}
          <code>{"'abc' matches '.*b.*'"}</code> is true. The function form <code>matches(value, pattern)</code>{' '}
          does not work inside <code>{'{{ }}'}</code> and resolves to empty.
        </Callout>

        <h3>Dates and formatting</h3>
        <DocsTable
          caption="Date and formatting functions"
          rowHeaders
          head={['Function', 'Behavior', 'Example']}
          rows={[
            [<code key="f">now()</code>, 'Current UTC date and time as ISO text, to the second, without a time zone suffix. When the seconds are zero they are left out (2026-09-24T14:05).', <code key="e">now() → &apos;2026-09-24T14:05:09&apos;</code>],
            [<code key="f">today()</code>, 'Current UTC date as ISO text.', <code key="e">today() → &apos;2026-09-24&apos;</code>],
            [<code key="f">formatdate(value, pattern)</code>, 'Formats a date with a Java date pattern (yyyy-MM-dd when null). See below for accepted values.', <code key="e">formatdate(now(), &apos;dd/MM/yyyy HH:mm&apos;)</code>],
            [<code key="f">formatnumber(value, decimals)</code>, 'Formats with exactly decimals decimal places (2 when null), with grouping separators. 0 for null.', <code key="e">formatnumber(1234.5, 2)</code>],
            [<code key="f">formatcurrency(value, code)</code>, 'Formats as an amount in the ISO currency code (EUR when null). An unknown code gives the number followed by the code.', <code key="e">formatcurrency(19.9, &apos;USD&apos;)</code>],
          ]}
        />
        <p>
          <code>formatdate</code> accepts a number of epoch milliseconds (formatted in UTC), an ISO date (
          <code>2026-09-24</code>), or an ISO date and time <strong>without a time zone</strong> (
          <code>2026-09-24T14:05:09</code>). Any other value, including an ISO timestamp that ends in{' '}
          <code>Z</code> or an offset such as <code>+02:00</code>, is returned <strong>unchanged</strong>,
          without an error. In the pattern, <code>DD</code>, <code>YYYY</code>, and <code>YY</code> are read as{' '}
          <code>dd</code>, <code>yyyy</code>, and <code>yy</code>. The separators used by{' '}
          <code>formatnumber</code> and <code>formatcurrency</code> follow the server&apos;s settings, not
          your language.
        </p>

        <h3>JSON</h3>
        <DocsTable
          caption="JSON functions"
          rowHeaders
          head={['Function', 'Behavior', 'Example']}
          rows={[
            [<code key="f">json(value)</code>, 'Parses JSON text into a typed object, list, number, boolean, or string. An already-typed value is returned unchanged; blank text gives null.', <code key="e">json(mcp:fetch.output.body)</code>],
            [<code key="f">fromjson(value)</code>, 'Alias of json.', <code key="e">fromjson(&apos;[1,2]&apos;)</code>],
            [<code key="f">tojson(value)</code>, 'Writes a value as compact JSON text; null gives the text null.', <code key="e">tojson(mcp:fetch.output.user)</code>],
          ]}
        />
        <p>
          A typical use of <code>json()</code> is turning a text field into typed data for a parameter that
          expects an object. Parsing is limited to 256 KB per string value, 64 levels of nesting, and 2 MB per document.
          Unlike every other error, malformed JSON is reported in the inspector instead of resolving to empty.
        </p>
        <CodeBlock title="json() examples">{`{{json(mcp:fetch.output.body)}}
{{json('{"responseModalities":["IMAGE"]}')}}`}</CodeBlock>

        <h2>Operators and collection filters</h2>
        <p>
          Expressions use the Spring Expression Language (SpEL) operators: arithmetic{' '}
          <code>+ - * / %</code>, comparison <code>== != &lt; &gt; &lt;= &gt;=</code>, logical{' '}
          <code>&amp;&amp; || !</code> (or <code>and</code>, <code>or</code>, <code>not</code>), the ternary{' '}
          <code>cond ? a : b</code>, and <code>matches</code> for regular expressions.
        </p>
        <Callout variant="warn" title="Compare numbers as numbers">
          <code>{'{{amount > 9}}'}</code> compares numerically only when both sides are numbers. Values that
          arrive as text (form fields, CSV columns, many API fields) are compared as text, where{' '}
          <code>&apos;100&apos;</code> is smaller than <code>&apos;9&apos;</code>. Convert first:{' '}
          <code>{'{{int(trigger:form.output.form_data.amount) > 9}}'}</code>.
        </Callout>
        <DocsTable
          caption="Collection operators"
          rowHeaders
          head={['Operator', 'Name', 'Example', 'Result']}
          rows={[
            [<code key="o">{'.?[predicate]'}</code>, 'Selection (filter)', <code key="o">{'{{users.?[age >= 18]}}'}</code>, 'A filtered list'],
            [<code key="o">{'.![expr]'}</code>, 'Projection (map)', <code key="o">{'{{users.![name]}}'}</code>, 'A mapped list'],
            [<code key="o">{'.^[predicate]'}</code>, 'First match', <code key="o">{"{{headers.^[name == 'From'].value}}"}</code>, 'A single element'],
            [<code key="o">{'.$[predicate]'}</code>, 'Last match', <code key="o">{"{{items.$[type == 'a'].id}}"}</code>, 'A single element'],
          ]}
        />
        <CodeBlock title="Collection examples">{`{{nums.?[#this > 10]}}
{{users.?[age >= 18 and active == true]}}
{{users.?[age >= 18].![name]}}
{{users.?[age >= 18].size()}}`}</CodeBlock>
        <p>
          Filters and projections chain, and a filter with no match returns an empty list, never{' '}
          <code>null</code>. Only a safe set of methods can be called on values: on text{' '}
          <code>length</code>, <code>isEmpty</code>, <code>isBlank</code>, <code>trim</code>,{' '}
          <code>strip</code>, <code>toLowerCase</code>, <code>toUpperCase</code>, <code>contains</code>,{' '}
          <code>startsWith</code>, <code>endsWith</code>, <code>substring</code>, <code>replace</code>,{' '}
          <code>replaceAll</code>, <code>replaceFirst</code>, <code>matches</code>, <code>split</code>,{' '}
          <code>indexOf</code>, <code>lastIndexOf</code>, <code>charAt</code>, <code>toString</code>; on lists{' '}
          <code>size</code>, <code>isEmpty</code>, <code>contains</code>, <code>get</code>; on objects{' '}
          <code>get</code>, <code>getOrDefault</code>, <code>containsKey</code>, <code>containsValue</code>,{' '}
          <code>size</code>, <code>isEmpty</code>. Type references, constructors, and other method calls are
          blocked.
        </p>

        <h2>Two different defaults: interface pipe vs default()</h2>
        <p>
          Interfaces and workflow fields use the same <code>{'{{ }}'}</code> braces but two different fallback
          mechanisms:
        </p>
        <DocsTable
          caption="Fallback syntax by context"
          rowHeaders
          head={['Context', 'Fallback syntax', 'Notes']}
          rows={[
            ['Interface template', <code key="d">{'{{name|fallback}}'}</code>, 'A simple name plus one optional pipe default. No functions, not Handlebars.'],
            ['Workflow node field or condition', <code key="d">{"default(var, 'x')"}</code>, 'There is no pipe operator: name|fallback here resolves to empty.'],
          ]}
        />
        <p>
          A file resolved into an interface template is rendered as its storage path, so{' '}
          <code>{'<img src="{{photo}}">'}</code> gets a usable address directly. See{' '}
          <a href="/interfaces">Interfaces &amp; apps</a> for the full templating model.
        </p>

        <h2>Common pitfalls</h2>
        <h3>The Code node&apos;s extra result level</h3>
        <p>
          A Code node&apos;s returned value is exposed downstream as{' '}
          <code>{'{{core:<label>.output.result.<field>}}'}</code>: the engine wraps what you return under an
          extra <code>result</code> key. A mapping written as <code>{'{"result":"{{core:normalize.output}}"}'}</code>{' '}
          produces a double <code>result.result</code> and reads as empty. Map past the wrapper:{' '}
          <code>{'{"result":"{{core:normalize.output.result}}"}'}</code>.
        </p>
        <h3>Sub-workflow outputs use the bare child key</h3>
        <p>
          A Sub-Workflow node returns the child&apos;s node outputs keyed by the child node&apos;s key without
          its prefix: <code>{'{{core:call_child.output.result.step_result.output.transformed.url}}'}</code>, not{' '}
          <code>result.core:step_result</code>. The prefixed form resolves to empty without an error.
        </p>
        <h3>Legacy forms</h3>
        <p>
          For backward compatibility the resolver still recognizes older dotted forms (<code>steps.</code>,{' '}
          <code>triggers.</code>, <code>data.</code>, <code>current_item.</code>, <code>mcps.</code>). Write the
          prefixed form.
        </p>

        <h2>The expression editor</h2>
        <p>
          Fields that accept expressions open an editor with categorized autocomplete for node outputs and
          functions, and a syntax guide with examples. The interface mapping inspector has its own
          function-help popover for the functions most useful when wiring data into a page.
        </p>

        <h2>Troubleshooting</h2>
        <DocsTable
          caption="Common expression problems"
          rowHeaders
          head={['Symptom', 'Cause', 'Fix']}
          rows={[
            [
              'A reference resolves to empty, with no error',
              'The path does not exist at that point: a misspelled field, an index past the end of a list, or a node that has not run before this step on the same path (a parallel branch before the merge).',
              'Pick the field from the expression editor autocomplete, or copy the exact path from the source node output of a past run. Read parallel branches only after they merge.',
            ],
            [
              'A function call comes out empty',
              <>A function error (wrong argument type, bad date, incompatible comparison) resolves to <code key="n">null</code> silently. Only <code key="j">json()</code> reports malformed input.</>,
              'Test the function on a literal value first, then swap the reference back in. Convert types explicitly with int(), double(), or string().',
            ],
            [
              <>A number comparison is wrong (<code key="c">{"'100' > '9'"}</code> is false)</>,
              'Values that arrive as text (form fields, CSV columns, many API fields) are compared as text, character by character.',
              <>Convert both sides first: <code key="f">{'{{int(trigger:form.output.form_data.amount) > 9}}'}</code>.</>,
            ],
            [
              <><code key="m">matches(value, pattern)</code> resolves to empty</>,
              <><code key="m">matches</code> is an operator, not a function, so the function form does not parse.</>,
              <>Write <code key="f">{"value matches 'pattern'"}</code>. The pattern must match the whole text.</>,
            ],
            [
              'A Code node output reads as empty downstream',
              <>The value the Code node returns is wrapped under an extra <code key="r">result</code> key.</>,
              <>Read <code key="f">{'{{core:<label>.output.result.<field>}}'}</code>, and map <code key="f2">output.result</code>, not <code key="f3">output</code>.</>,
            ],
            [
              'A reference to a node with spaces or accents in its label resolves to empty',
              <>Node keys are stored normalized, so <code key="k">core:Check Status</code> does not match the stored key.</>,
              <>Write the normalized label, for example <code key="f">core:check_status</code>. See Label normalization above.</>,
            ],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={3}>
          <Card icon={Workflow} title="Workflows" href="/workflows">Where node keys and outputs come from.</Card>
          <Card icon={Database} title="Tables & data" href="/tables">Table nodes, and how their filters compare values.</Card>
          <Card icon={LayoutPanelLeft} title="Interfaces & apps" href="/interfaces">The pipe-default template model used inside a page.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
