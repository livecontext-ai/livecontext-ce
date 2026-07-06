import { Workflow, Plug, Bot, Zap } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, Steps, Step, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Tables & data',
  description:
    'Built-in tables in LiveContext: the CRUD operations, how filters and comparisons actually work (textual, not numeric), pagination limits, the delete-all idiom, idempotent inserts, column types, and vector similarity search.',
  path: '/docs/tables',
});

export default function TablesPage() {
  return (
    <>
      <DocsHero
        eyebrow="Data"
        title="Tables & data"
        lead="Tables are built-in spreadsheets your workflows read and write - no external database needed. A workflow can find rows, insert and update them, delete them, start when a row changes, or use a table as shared memory for an agent."
      />

      <DocsProse>
        <h2>How tables are stored</h2>
        <p>
          A table's rows live in one shared store, with your columns packed into a single data field per
          row (there is no external database to connect or configure). Every row also carries three
          system fields you don't define yourself:
        </p>
        <DocsTable
          head={['System field', 'Meaning']}
          rows={[
            [<code key="id">id</code>, 'The row\'s primary key. Matches the reserved filter name id (see filtering below).'],
            [<code key="p">priority</code>, 'An integer used for the default sort order.'],
            [<code key="c">created_at</code>, 'Timestamp set when the row was inserted.'],
          ]}
        />
        <p>
          These three fields come back alongside your own columns on every read, and they take priority
          if one of your columns happens to share a name with them.
        </p>

        <h2>The CRUD operations</h2>
        <p>
          There are five underlying operations. The workflow builder and the agent-facing{' '}
          <code>table</code> tool label them slightly differently, but they map onto the same backend
          behavior:
        </p>
        <DocsTable
          head={['Builder node', 'Agent tool action', 'What it does']}
          rows={[
            ['Find Rows', 'query_rows', 'Query rows by a filter and return them as an items list, meant to feed a Split step for per-row parallel processing. Find Rows does not split by itself.'],
            ['Get Row', 'query_rows', 'Query rows by a filter and return them as a flat rows list. Find Rows and Get Row are separate builder nodes, but both resolve to the same underlying read.'],
            ['Create Row', 'insert_rows', 'Add a new row from a map of column values.'],
            ['Update Row', 'update_rows', 'Change columns on every row matching a filter.'],
            ['Delete Row', 'delete_rows', 'Remove every row matching a filter.'],
            ['Create Column', 'add_columns', 'Add a column to the table, optionally backfilling existing rows with a default value.'],
          ]}
        />
        <p>
          The agent <code>table</code> tool also exposes table-level actions (<code>create</code>,{' '}
          <code>get</code>, <code>list</code>, <code>update</code>, <code>delete</code>) and marketplace
          actions (<code>publish</code>, <code>unpublish</code>), plus <code>help</code>.
        </p>

        <h2>Column types</h2>
        <p>A column is one of fifteen types, which control how it's validated and shown:</p>
        <DocsTable
          head={['Type', 'Notes']}
          rows={[
            [<code key="t">text</code>, 'Free text.'],
            [<code key="n">number</code>, 'Numeric value (stored, but compared as text - see filtering below).'],
            [<code key="d">date</code>, 'Date value.'],
            [<code key="ck">checkbox</code>, 'Boolean.'],
            [<code key="s">select</code>, 'One choice from a fixed list. Requires a non-empty options list at creation.'],
            [<code key="ms">multi_select</code>, 'Several choices from a fixed list. Also requires a non-empty options list.'],
            [<code key="r">rating</code>, 'Numeric rating.'],
            [<code key="sn">sentiment</code>, 'Sentiment value.'],
            [<code key="pr">progress</code>, 'Progress value.'],
            [<code key="f">file</code>, 'File reference.'],
            [<code key="im">image</code>, 'Image reference.'],
            [<code key="em">email</code>, 'Email address.'],
            [<code key="ph">phone</code>, 'Phone number.'],
            [<code key="u">url</code>, 'URL.'],
            [<code key="v">vector</code>, 'Embedding vector for similarity search. Requires a dimension (1 to 2000) and is only available on self-hosted editions - see below.'],
          ]}
        />

        <h2>Filtering: the rule that trips people up</h2>
        <p>
          A filter (<code>where</code>) is a <strong>bare</strong> column name, an operator, and a
          value - write <code>status</code>, not <code>data.status</code>. The reserved name{' '}
          <code>id</code> matches a row's primary key, not a column you defined.
        </p>
        <DocsTable
          head={['Operator', 'Reliable?']}
          rows={[
            [<code key="o1">= · != · IN · IS NULL · IS NOT NULL · LIKE</code>, 'Yes - use these.'],
            [<code key="o2">&gt; · &lt; · &gt;= · &lt;=</code>, 'Textual order only - unreliable for numbers and dates.'],
          ]}
        />
        <Callout variant="warn">
          Every comparison is <strong>textual (lexicographic), not numeric</strong> - including on a{' '}
          <code>number</code> column. So <code>amount &gt; 9</code> <strong>excludes</strong>{' '}
          <code>&quot;100&quot;</code>, because as strings <code>&quot;1&quot; &lt; &quot;9&quot;</code>.
          For numeric or date ranges, filter with <code>=</code> / <code>IN</code> on known values, or
          compute the comparison in a Code step rather than relying on <code>&gt;</code> /{' '}
          <code>&lt;</code>.
        </Callout>
        <p>
          <code>LIKE</code> does not add wildcards automatically - include <code>%</code> or{' '}
          <code>_</code> in the value yourself (e.g. <code>%gmail.com</code>). <code>IN</code> requires a
          non-empty list of values.
        </p>

        <h2>Reading & pagination</h2>
        <p>
          A read defaults to <strong>20</strong> rows per page. The limit is floored at 1 and clamped up
          to a ceiling of <strong>10,000</strong> - but which ceiling you actually hit depends on where the
          read runs from:
        </p>
        <DocsTable
          head={['Caller', 'Effective row ceiling']}
          rows={[
            ['Workflow Get Row / Find Rows node', '~500 rows per read (Find Rows additionally caps its emitted items list at 100 by default).'],
            ['Agent table tool (query_rows)', 'Up to 10,000 rows.'],
            ['Direct table grid (REST)', 'Up to 100 rows.'],
          ]}
        />
        <p>
          A read returns <code>hasMore</code> so you know whether more rows exist beyond the current page,
          and echoes back the <code>offset</code> used. Every returned row carries the system fields{' '}
          <code>id</code>, <code>priority</code>, and <code>created_at</code> alongside your own columns.
          The default sort is <code>priority DESC, id DESC</code> (newest / highest-priority first) - there
          is no user-configurable sort on Find/Read, so sort downstream in a Code step if you need a
          specific order.
        </p>

        <h2>Writing rows</h2>
        <p>
          <strong>Insert</strong> takes a map of column to value. <strong>Update</strong> takes a filter
          plus a non-empty <code>set</code> map - both are required, or the write fails fast. A couple of
          storage details to know: an empty value is stored as an empty string (not a true null), and a
          list or object value is stored as its JSON text. Reserved system column names (<code>id</code>,{' '}
          <code>priority</code>, <code>created_at</code>, and other internal fields) are rejected in
          insert and update payloads, so you can't accidentally shadow them.
        </p>
        <CodeBlock language="text">{`Insert "Save contact":
  columns = { name: "{{trigger:form.output.name}}", email: "{{trigger:form.output.email}}" }

Update "Mark done":
  where = { column: "status", operator: "=", value: "in_progress" }
  set   = { status: "completed" }`}</CodeBlock>

        <h2>Deleting</h2>
        <p>
          Delete always needs a filter - there is no "clear table" button. To wipe every row, the idiom
          is to match on the always-present primary key:
        </p>
        <CodeBlock language="text">{`Delete "Clear table":
  where = { column: "id", operator: "IS NOT NULL" }`}</CodeBlock>

        <h2>Don&apos;t create duplicates</h2>
        <p>
          When a workflow can run more than once on the same item, guard your insert so re-runs
          don&apos;t pile up duplicate rows:
        </p>
        <Steps>
          <Step n={1} title="Find by a unique key">
            Look the item up first - e.g. <code>where: {`{ column: "message_id", operator: "=", value: "..." }`}</code>.
          </Step>
          <Step n={2} title="Decide on the count">
            A Decision on <code>{'{{table:check.output.item_count}} == 0'}</code> splits &ldquo;new&rdquo; from
            &ldquo;already there&rdquo;.
          </Step>
          <Step n={3} title="Insert only on the new branch">
            Insert on the <em>new</em> branch; exit on the other. The write is now idempotent.
          </Step>
        </Steps>

        <h2>Creating a column</h2>
        <p>
          A <code>select</code> or <code>multi_select</code> column restricts values to a set of choices
          you provide - creation is rejected if that options list is empty. A <code>vector</code> column
          needs a declared dimension (1 to 2000). When you add a column with a default value, existing
          rows that don't already have that key are back-filled with the default automatically.
        </p>

        <h2>Vector similarity search</h2>
        <Callout variant="info">
          Vector columns and similarity search are a <strong>self-hosted feature</strong> (Community
          Edition and self-hosted enterprise). On managed cloud, creating a vector column or running a
          similarity search is rejected with a message pointing you to text columns with keyword filters
          instead.
        </Callout>
        <p>
          On a self-hosted deployment, a similarity search is a read with a <code>similarity</code> block
          instead of (or alongside) <code>where</code>: <code>column</code>, <code>queryVector</code>, an
          optional <code>topK</code> (default 5), and an optional <code>threshold</code>. The query vector
          must match the column's declared dimension. <code>metric</code> defaults to{' '}
          <code>cosine</code> (also <code>l2</code> and <code>dot</code>). Combine <code>where</code> with{' '}
          <code>similarity</code> for hybrid search: the filter narrows the candidate rows before
          nearest-neighbor ranking runs.
        </p>

        <h2>When a row changes</h2>
        <p>
          Every insert, update, and delete fires a row-changed event once it commits. This is what
          powers a <a href="/triggers">datasource trigger</a>: a workflow that starts automatically
          whenever a row in a chosen table is created, updated, or deleted. An update event also carries
          the row's previous values, so a trigger can compare before/after state.
        </p>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={Workflow} title="Workflows" href="/workflows">Process rows in parallel with Find + Split.</Card>
          <Card icon={Zap} title="Triggers" href="/triggers">Start a workflow when a row changes.</Card>
          <Card icon={Plug} title="Integrations" href="/integrations">Pull data in from outside tools.</Card>
          <Card icon={Bot} title="Agents" href="/agents">Give an agent a table as scoped memory.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
