import { Workflow, Plug, Bot, Zap } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, Steps, Step, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Tables & data',
  description:
    'Built-in tables in LiveContext: CRUD operations, textual comparisons, row limits, the delete-all idiom, idempotent inserts, column types, file columns, export, and vector search.',
  path: '/docs/tables',
});

export default function TablesPage() {
  return (
    <>
      <DocsHero
        eyebrow="Data"
        title="Tables & data"
        lead="Tables are built-in spreadsheets your workflows and agents read and write, with no external database to set up. A workflow can find rows, insert and update them, delete them, or start when a row changes."
      />

      <DocsProse>
        <h2>How tables are stored</h2>
        <p>
          A table&apos;s rows live in one shared store, with your columns packed into a single data field
          per row (there is no external database to connect or configure). Every row also carries three
          system fields you don&apos;t define yourself:
        </p>
        <DocsTable
          caption="System fields on every row"
          rowHeaders
          head={['System field', 'Meaning']}
          rows={[
            [<code key="id">id</code>, 'The row’s primary key. Matches the reserved filter name id (see filtering below).'],
            [<code key="p">priority</code>, 'An integer used for the default sort order.'],
            [<code key="c">created_at</code>, 'Timestamp set when the row was inserted.'],
          ]}
        />
        <p>
          These fields come back alongside your own columns on every read. A few names are reserved
          and can&apos;t be used for your own columns: <code>id</code>, <code>data_source_id</code>,{' '}
          <code>tenant_id</code>, <code>data</code>, <code>priority</code>, <code>row_index</code>,{' '}
          <code>created_at</code>, and <code>updated_at</code>. A write to one of them is refused.
        </p>

        <h2>The CRUD operations</h2>
        <p>
          There are five underlying operations. The workflow builder and the agent-facing{' '}
          <code>table</code> tool label them slightly differently, but they map onto the same
          behavior:
        </p>
        <DocsTable
          caption="Table operations in the builder and the agent tool"
          rowHeaders
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
        <p>A column is one of fifteen types, which control how it&apos;s validated and shown:</p>
        <DocsTable
          caption="Column types"
          rowHeaders
          head={['Type', 'Notes']}
          rows={[
            [<code key="t">text</code>, 'Free text.'],
            [<code key="n">number</code>, 'Numeric value (stored, but compared as text: see filtering below).'],
            [<code key="d">date</code>, 'Date value.'],
            [<code key="ck">checkbox</code>, 'Boolean.'],
            [<code key="s">select</code>, 'One choice from a fixed list. Requires a non-empty options list at creation.'],
            [<code key="ms">multi_select</code>, 'Several choices from a fixed list. Also requires a non-empty options list.'],
            [<code key="r">rating</code>, 'Numeric rating.'],
            [<code key="sn">sentiment</code>, 'Sentiment value.'],
            [<code key="pr">progress</code>, 'Progress value.'],
            [<code key="f">file</code>, 'A stored file, shown as a card. See file and image columns below.'],
            [<code key="im">image</code>, 'The same value as file, shown as a thumbnail.'],
            [<code key="em">email</code>, 'Email address.'],
            [<code key="ph">phone</code>, 'Phone number.'],
            [<code key="u">url</code>, 'URL.'],
            [<code key="v">vector</code>, 'Embedding vector for similarity search. Requires a dimension (1 to 2000). Plan-gated on the managed cloud: see below.'],
          ]}
        />

        <h3>Values are converted to the column type</h3>
        <p>
          Every value you write is converted to its column&apos;s type: a <code>number</code> column
          turns <code>&quot;3,14&quot;</code> or <code>&quot;42%&quot;</code> into a number, a{' '}
          <code>checkbox</code> accepts <code>yes</code> or <code>1</code>, and so on. A value that
          doesn&apos;t fit its column does not fail the write: instead the write reports{' '}
          <code>warnings</code>. Most are harmless
          normalizations, but a value that can&apos;t be parsed leaves the cell empty, and a file
          reference with nothing to fetch it by is stored but unusable.
        </p>
        <p>
          In a workflow, read them from the step output as{' '}
          <code>{'{{table:<label>.output.warnings}}'}</code>; the agent <code>table</code> tool adds them
          to its reply. A vector value is the exception: a malformed embedding fails the whole write and
          no row is stored.
        </p>

        <h3>File and image columns</h3>
        <p>
          A <code>file</code> column and an <code>image</code> column hold the same value, a file
          reference, and differ only in how the grid shows it (a card or a thumbnail). In the grid, a
          cell can take an upload, an existing file picked from Files, or an external URL. Reads return
          the file reference as an object (<code>id</code>, <code>path</code>, <code>mimeType</code>, and{' '}
          <code>size</code> appear only when known):
        </p>
        <CodeBlock language="json" title="File or image cell value">{`{
  "_type": "file",
  "id": "b21f6c1e-5a9d-4c1b-9d0e-2f7a8c3e41d2",
  "url": "/api/proxy/files/by-id/b21f6c1e-5a9d-4c1b-9d0e-2f7a8c3e41d2/raw",
  "name": "report.pdf",
  "mimeType": "application/pdf",
  "size": 48213
}`}</CodeBlock>
        <p>
          Write a whole file reference (for example <code>{'{{core:dl.output.file}}'}</code>) into the
          cell, not one of its fields. Store files in a <code>file</code> or <code>image</code> column,
          not a <code>text</code> column: a text column keeps whatever text it was given and gives it
          back as text. A file cell can&apos;t be used as a filter value (it matches nothing).
        </p>

        <h2>Filtering: the rule that trips people up</h2>
        <p>
          A filter (<code>where</code>) is a bare column name, an operator, and a
          value: write <code>status</code>, not <code>data.status</code> (a <code>data.</code> prefix is
          stripped for you). The reserved name <code>id</code> matches a row&apos;s primary key, not a
          column you defined.
        </p>
        <DocsTable
          caption="Filter operators and how reliable they are"
          rowHeaders
          head={['Operator', 'Reliable?']}
          rows={[
            [<code key="o1">=, !=, IN, IS NULL, IS NOT NULL, LIKE</code>, 'Yes, use these.'],
            [<code key="o2">&gt;, &lt;, &gt;=, &lt;=</code>, 'Textual order only: unreliable for numbers and dates.'],
          ]}
        />
        <Callout variant="warn" title="Comparisons are textual">
          Every comparison is textual (lexicographic), not numeric, including on a{' '}
          <code>number</code> column and on <code>id</code>. So <code>amount &gt; 9</code>{' '}
          excludes <code>&quot;100&quot;</code>, because as strings{' '}
          <code>&quot;1&quot; &lt; &quot;9&quot;</code>, and <code>id &gt; 5</code> skips ids 10 to 99.
          For numeric or date ranges, filter with <code>=</code> or <code>IN</code> on known values, or
          compute the comparison in a Code step rather than relying on <code>&gt;</code> or{' '}
          <code>&lt;</code>.
        </Callout>
        <p>
          <code>LIKE</code> does not add wildcards automatically: include <code>%</code> or{' '}
          <code>_</code> in the value yourself (for example <code>%gmail.com</code>). <code>IN</code>{' '}
          requires a non-empty list of values. Aliases are accepted too: <code>==</code>,{' '}
          <code>EQ</code>, <code>NE</code>, <code>GT</code>, <code>LT</code>, <code>GTE</code>,{' '}
          <code>LTE</code>, <code>CONTAINS</code> (same as <code>LIKE</code>, still without automatic
          wildcards), <code>ISNULL</code>, and <code>NOTNULL</code>.
        </p>

        <h2>Reading & pagination</h2>
        <p>
          How many rows a read returns, and how many it can return at most, depends on where it runs
          from:
        </p>
        <DocsTable
          caption="Row limits by caller"
          rowHeaders
          head={['Caller', 'Rows per read']}
          rows={[
            ['Workflow Get Row node', 'Up to 500 rows per read. With no limit set it reads up to 500; a higher limit is lowered to 500.'],
            ['Workflow Find Rows node', 'Keeps 100 rows by default. You can set a higher limit, but the read behind it still returns at most 500 rows.'],
            ['Agent table tool (query_rows)', '20 rows by default, up to 10,000. There is no offset: to page through a large table, narrow the where filter instead of raising the limit.'],
            ['The table grid in the app', '20 rows per page by default; choose 10, 20, 50, or 100 per page.'],
          ]}
        />
        <p>
          Every returned row carries the system fields <code>id</code>, <code>priority</code>, and{' '}
          <code>created_at</code> alongside your own columns. The default sort is{' '}
          <code>priority DESC, id DESC</code> (newest or highest priority first). There is no
          user-configurable sort on Find Rows or Get Row, so sort downstream in a Code step if you need a
          specific order. A limit of <code>0</code> returns no rows, which makes a cheap existence check.
        </p>

        <h2>Writing rows</h2>
        <p>
          <strong>Insert</strong> takes a map of column to value. <strong>Update</strong> takes a filter
          plus a non-empty <code>set</code> map: both are required, or the write fails fast. An empty
          value is stored as an empty string (not a true null).
        </p>
        <CodeBlock language="text">{`Insert "Save contact":
  columns = { name: "{{trigger:form.output.name}}", email: "{{trigger:form.output.email}}" }

Update "Mark done":
  where = { column: "status", operator: "=", value: "in_progress" }
  set   = { status: "completed" }`}</CodeBlock>

        <h2>Deleting</h2>
        <p>
          Delete always needs a <code>where</code> filter: there is no &ldquo;clear table&rdquo;
          operation. To wipe every row, match on the always-present primary key:
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
            Look the item up first with Find Rows, for example{' '}
            <code>{'where: { column: "message_id", operator: "=", value: "{{trigger:gmail.output.id}}" }'}</code>.
          </Step>
          <Step n={2} title="Decide on the count">
            A Decision on <code>{'{{table:check.output.item_count}} == 0'}</code> splits &ldquo;new&rdquo;
            from &ldquo;already there&rdquo;.
          </Step>
          <Step n={3} title="Insert only on the new branch">
            Insert on the <em>new</em> branch; end on the other. The write is now idempotent.
          </Step>
        </Steps>

        <h2>Creating a column</h2>
        <p>
          A <code>select</code> or <code>multi_select</code> column restricts values to a set of choices
          you provide: creation is rejected if that options list is empty. A <code>vector</code> column
          needs a declared dimension (1 to 2000) and a distance metric. When you add a column with a
          default value, existing rows that don&apos;t already have that key are back-filled with the
          default automatically.
        </p>

        <h2>Exporting a table</h2>
        <p>
          In the table grid, <strong>Export</strong> downloads the rows as CSV, JSON, or Excel, either
          the current view or all data. There is no CSV import button in the grid: to fill a table from a
          file, read the file with an Extract from File step and insert the rows, or ask the assistant to
          create the table with its data.
        </p>

        <h2>Vector similarity search</h2>
        <Callout title="Plan requirement">
          On the managed cloud, vector columns and similarity search require the Pro{' '}
          plan or higher. The plan that counts is the plan of the workspace that owns the
          table, not the plan of whoever runs the query, so every member and every workflow run
          of an eligible workspace can use it. Self-hosted deployments are never restricted.
        </Callout>
        <p>
          Tables store and search embeddings; they don&apos;t create them. Generate the embedding with an
          embeddings API (through an integration or an HTTP Request step) and insert it into the vector
          column. To prepare documents, an Extract from File step in text mode splits a PDF, Word, HTML,
          or text file into chunks you can embed one by one (see{' '}
          <a href="/files">Files &amp; storage</a>).
        </p>
        <p>
          A similarity search is a read with a <code>similarity</code> block instead of (or alongside){' '}
          <code>where</code>: <code>column</code>, <code>queryVector</code>, an optional{' '}
          <code>topK</code> (default 5), and an optional <code>threshold</code>. The query vector must
          match the column&apos;s declared dimension. The distance metric is not part of the query: it
          is set on the vector column when you create it (<code>cosine</code> by default, or{' '}
          <code>l2</code> or <code>dot</code>). Combine <code>where</code> with <code>similarity</code>{' '}
          for hybrid search: the filter narrows the candidate rows before nearest-neighbor ranking runs.
        </p>

        <h2>When a row changes</h2>
        <p>
          Every insert, update, and delete fires a row-changed event once it commits. This is what
          powers a <a href="/triggers">datasource trigger</a>: a workflow that starts automatically
          whenever a row in a chosen table is created, updated, or deleted. An update event also carries
          the row&apos;s previous values, so a trigger can compare before and after.
        </p>

        <h2>Troubleshooting</h2>
        <DocsTable
          caption="Common table problems"
          rowHeaders
          head={['Symptom', 'Cause', 'Fix']}
          rows={[
            [
              <span key="s">
                A <code>&gt;</code> or <code>&lt;</code> filter skips rows it should match
              </span>,
              'Comparisons are textual, so "100" sorts before "9".',
              'Filter with = or IN on known values, or compare in a Code step.',
            ],
            [
              'A delete fails because it has no filter',
              'Delete always needs a where filter: there is no clear-table operation.',
              <span key="f">
                To remove every row, filter on <code>id</code> with <code>IS NOT NULL</code>.
              </span>,
            ],
            [
              'Re-running a workflow creates duplicate rows',
              'The insert runs every time, even for an item already stored.',
              'Look the item up with Find Rows first and insert only when item_count is 0.',
            ],
            [
              'A read returns fewer rows than the table holds',
              'A workflow read returns at most 500 rows, and Find Rows keeps 100 by default.',
              'Narrow the filter, or page with offset on Get Row.',
            ],
            [
              'A stored file comes back as text',
              <span key="c">
                It was saved in a <code>text</code> column.
              </span>,
              <span key="f">
                Use a <code>file</code> or <code>image</code> column and map the whole file reference into
                it.
              </span>,
            ],
            [
              'A write succeeds but a cell is empty',
              'The value could not be converted to the column type. The write reports it in warnings instead of failing.',
              'Read the warnings output and fix the value or the column type.',
            ],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={2}>
          <Card icon={Workflow} title="Workflows" href="/workflows">Process rows in parallel with Find Rows and Split.</Card>
          <Card icon={Zap} title="Triggers" href="/triggers">Start a workflow when a row changes.</Card>
          <Card icon={Plug} title="Integrations" href="/integrations">Pull data in from outside tools.</Card>
          <Card icon={Bot} title="Agents" href="/agents">Let an agent read and write tables with its table tool.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
