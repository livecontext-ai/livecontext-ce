import { Workflow, Plug, Bot } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, Steps, Step, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Tables & data',
  description:
    'Built-in tables in LiveContext: the CRUD operations, how filters and comparisons actually work (textual, not numeric), the delete-all idiom, idempotent inserts, the column types, and limits.',
  path: '/docs/tables',
});

export default function TablesPage() {
  return (
    <>
      <DocsHero
        eyebrow="Data"
        title="Tables & data"
        lead="Tables are built-in spreadsheets your workflows read and write - no external database needed. A workflow can search a table, add and update rows, start when a row changes, or use a table as shared memory between steps."
      />

      <DocsProse>
        <h2>The operations</h2>
        <DocsTable
          head={['Operation', 'What it does']}
          rows={[
            ['Find rows', 'Query rows by a filter and process each match in parallel.'],
            ['Read rows', 'Return matching rows as a single list.'],
            ['Insert row', 'Add a new row from a map of column values.'],
            ['Update row', 'Change columns on every row matching a filter.'],
            ['Delete row', 'Remove every row matching a filter.'],
            ['Create column', 'Add a column to the table.'],
          ]}
        />

        <h2>Filtering: the rule that trips people up</h2>
        <p>
          A filter (<code>where</code>) is a <strong>bare</strong> column name, an operator, and a
          value - write <code>status</code>, not <code>data.status</code>. The operators are reliable
          for equality and membership; the catch is that{' '}
          <strong>every comparison is textual (lexicographic), not numeric</strong>.
        </p>
        <DocsTable
          head={['Operator', 'Reliable?']}
          rows={[
            [<code key="o">= · != · IN · IS NULL · IS NOT NULL · LIKE</code>, 'Yes - use these.'],
            [<code key="o">&gt; · &lt; · &gt;= · &lt;=</code>, 'Textual order only - unreliable for numbers and dates.'],
          ]}
        />
        <Callout variant="warn">
          Because comparisons are textual, <code>amount &gt; 9</code> <strong>excludes</strong>{' '}
          <code>&quot;100&quot;</code> - as strings, <code>&quot;1&quot; &lt; &quot;9&quot;</code>. For
          numeric or date ranges, filter with <code>=</code> / <code>IN</code> on known values, or
          compute the comparison in a Code step rather than relying on <code>&gt;</code> /{' '}
          <code>&lt;</code>.
        </Callout>
        <p>
          The reserved name <code>id</code> matches a row&apos;s primary key (not a column you defined).
          Reads return a default order - there is no custom sort on Find/Read, so sort downstream if you
          need a specific order. Read rows defaults to 20 (max 100); Find rows defaults to 100.
        </p>

        <h2>Writing rows</h2>
        <p>
          <strong>Insert</strong> takes a map of column → value. <strong>Update</strong> takes a filter
          plus a non-empty <code>set</code> map. A couple of storage details to know: an empty value is
          stored as an empty string (not a true null), and a list or object value is stored as its JSON
          text.
        </p>
        <CodeBlock language="text">{`Insert "Save contact":
  columns = { name: "{{trigger:form.output.name}}", email: "{{trigger:form.output.email}}" }

Update "Mark done":
  where = { column: "status", operator: "=", value: "in_progress" }
  set   = { status: "completed" }`}</CodeBlock>

        <h2>Deleting</h2>
        <p>
          Delete always needs a filter - there is no &ldquo;clear table&rdquo; button. To wipe every
          row, the idiom is to match on the always-present primary key:
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
            A Decision on <code>{'{{table:check.output.count}} == 0'}</code> splits &ldquo;new&rdquo; from
            &ldquo;already there&rdquo;.
          </Step>
          <Step n={3} title="Insert only on the new branch">
            Insert on the <em>new</em> branch; exit on the other. The write is now idempotent.
          </Step>
        </Steps>

        <h2>Column types</h2>
        <p>A column is one of fifteen types, which control how it&apos;s validated and shown:</p>
        <p>
          <code>text</code>, <code>number</code>, <code>date</code>, <code>checkbox</code>,{' '}
          <code>select</code>, <code>multi_select</code>, <code>rating</code>, <code>sentiment</code>,{' '}
          <code>progress</code>, <code>file</code>, <code>image</code>, <code>email</code>,{' '}
          <code>phone</code>, <code>url</code>, and <code>vector</code>.
        </p>
        <p>
          A <code>select</code> column restricts values to a set of choices (<code>multi_select</code>{' '}
          allows several). A <code>vector</code> column stores embeddings for similarity search.
        </p>
        <Callout variant="info">
          Vector columns are a cloud / enterprise feature. On the self-hosted Community Edition,
          creating one is rejected with an edition message.
        </Callout>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={Workflow} title="Workflows" href="/workflows">Process rows in parallel with Find + Split.</Card>
          <Card icon={Plug} title="Integrations" href="/integrations">Pull data in from outside tools.</Card>
          <Card icon={Bot} title="Agents" href="/agents">Give an agent a table as scoped memory.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
