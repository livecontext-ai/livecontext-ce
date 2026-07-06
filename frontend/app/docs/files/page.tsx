import { Workflow, MessageSquare, Store } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Files & storage',
  description:
    'How LiveContext stores and moves files: the 50 MB upload limit and storage quota, the canonical FileRef object, the file-producing nodes, files inside interfaces, sending files to integration tools, document extraction and vision in chat, the workspace file browser, and access control.',
  path: '/docs/files',
});

export default function FilesPage() {
  return (
    <>
      <DocsHero
        eyebrow="Data"
        title="Files & storage"
        lead="Every file your workflows, interfaces, and chats touch (images, PDFs, audio, exports, uploads) lands in the same object storage and is represented the same way everywhere: a single FileRef object. Learn the limit, the shape, and where files show up across the product."
      />

      <DocsProse>
        <h2>Uploading files</h2>
        <p>
          Every upload path (chat attachments, the Data Input form node, interface file inputs, generic
          uploads) shares one hard cap: <strong>50 MB per file</strong>. Go over it and the upload is
          rejected with <code>413 Payload Too Large</code>.
        </p>
        <p>
          Uploads also count against your workspace&apos;s <strong>storage quota</strong>. The quota is
          checked on every write, so a small allowed upload can never mask a later one that would push
          you over.
        </p>
        <DocsTable
          head={['Plan', 'Included storage']}
          rows={[
            ['Free', '100 MB'],
            ['Starter', '1 GB'],
            ['Pay-as-you-go', '5 GB'],
            ['Pro', '10 GB'],
            ['Team', '100 GB'],
            ['Enterprise Basic', '500 GB'],
            ['Enterprise Standard', '1 TB'],
            ['Enterprise Premium', '~2.5 TB'],
            ['Enterprise Ultimate', '5 TB'],
          ]}
        />
        <p>
          A soft warning appears once you cross 80% of your cap. Exceeding the cap fails the upload with{' '}
          <code>413</code> and &ldquo;Storage quota exceeded&rdquo;. Self-hosted Community Edition on the
          Free tier bypasses the quota entirely: local storage is unlimited.
        </p>
        <p>
          You can drop a new upload straight into an existing manual folder by pointing it at that
          folder. An invalid, blank, or cross-workspace folder id doesn&apos;t fail the upload; the file
          simply lands at the root of your file browser instead.
        </p>

        <h2>The canonical FileRef</h2>
        <p>
          Every file your workflow touches, whatever produced it, is the same shape: a{' '}
          <strong>FileRef</strong>. It carries a display name, a MIME type, a size in bytes, an id used to
          build the file&apos;s URL, and an internal storage path.
        </p>
        <CodeBlock language="json">{`{
  "_type": "file",
  "id": "b21f...-uuid",
  "name": "report.pdf",
  "mimeType": "application/pdf",
  "size": 48213
}`}</CodeBlock>
        <p>
          Everything that can hand you a file emits this exact shape: MCP catalog tools that return
          binaries (image generation, screenshots, and the like), the four core file-producing nodes
          below, interface and standalone form file uploads, and chat attachments. Anything a catalog
          tool returns as a large base64 blob is also auto-persisted to storage and rewritten into a
          FileRef for you, so you rarely need to think about the raw bytes at all.
        </p>
        <p>
          To pass a file from one step to the next, map the <strong>FileRef object itself</strong>, not
          one of its fields:
        </p>
        <CodeBlock language="text">{`{{core:my_download.output.file}}
{{mcp:generate_image.output.images[0]}}`}</CodeBlock>
        <Callout variant="warn">
          Drilling <code>.name</code>, <code>.mimeType</code>, or <code>.size</code> is safe (they are
          plain metadata). Never drill <code>.path</code> or <code>.id</code>: those are bare storage
          identifiers with no auth attached, and a component expecting a full FileRef will treat a drilled
          string as a URL or a file id instead of uploading or displaying the actual file.
        </Callout>

        <h2>File-producing nodes</h2>
        <p>
          Five node types (all with the <code>core:</code> prefix) produce, move, or read files. Four of
          them (Download File, Convert to File, SFTP, and Compression) emit a FileRef under the same
          output key, <code>.output.file</code>, so once you know the pattern you can wire any of them the
          same way. Extract from File is the odd one out: it reads a file and emits rows, so its useful
          output is <code>items</code>, not a <code>.file</code>.
        </p>
        <DocsTable
          head={['Node', 'What it does', 'Notable extra outputs']}
          rows={[
            [
              'Download File',
              'Downloads a file from an external URL and stores it. Use it when the source is a public URL (a user pasted an image link into a form, for example).',
              <code key="d">source_url</code>,
            ],
            [
              'Convert to File',
              'Converts data into CSV, Excel, or JSON and stores the result as a file.',
              <>
                <code>result</code> (inline contents), <code>format</code> (default csv), <code>row_count</code>
              </>,
            ],
            [
              'Extract from File',
              'Extracts structured rows out of a CSV, Excel, or JSON file. This is the node to wire in for chunked processing of a large document.',
              <>
                <code>items</code>, <code>format</code> (default csv), <code>rowCount</code>, <code>columns</code>
              </>,
            ],
            [
              'SFTP',
              'Remote file operations: upload, download, list, delete, rename, mkdir. A download emits the FileRef; list returns each remote entry as name/size/is_dir/modified.',
              <>
                <code>files[]</code>, <code>file_count</code>, <code>duration_ms</code>
              </>,
            ],
            [
              'Compression',
              'Compresses or decompresses data (gzip, zip). A compress operation emits the FileRef.',
              <>
                <code>result</code>, <code>format</code>
              </>,
            ],
          ]}
        />
        <p>
          After an MCP tool that already returns a file, you don&apos;t need a Download File node too:
          the file is already stored and already a FileRef.
        </p>
        <Callout variant="info">
          The four FileRef-emitting nodes only expose their file under <code>.file</code>. Older flat
          fields like{' '}
          <code>.file_url</code>, <code>.file_name</code>, <code>.file_size</code>, and{' '}
          <code>.content_type</code> no longer exist and resolve to nothing at runtime; use{' '}
          <code>.file.name</code> for the filename, <code>.file.size</code> for the size, and so on.
        </Callout>

        <h2>Files in interfaces</h2>
        <p>
          Map a FileRef into <code>variable_mapping</code> under any friendly name, then use that name in
          an <code>&lt;img src&gt;</code>, <code>&lt;a href&gt;</code>, or <code>&lt;video src&gt;</code>.
          The renderer auto-rewrites the FileRef into a usable URL, recursively for both single files and
          arrays of files, so there&apos;s no proxy URL to build by hand.
        </p>
        <p>
          Because the interface iframe is sandboxed and has no session token of its own, the renderer
          fetches each file with your auth header behind the scenes and hands the iframe an in-memory URL.
          Your session token never appears in the interface&apos;s HTML.
        </p>
        <CodeBlock language="html">{`<!-- variable_mapping: { "photo": "{{core:generate.output.file}}" } -->
<img src="{{photo}}" alt="Generated" />`}</CodeBlock>
        <p>A download link needs both the file and its name mapped separately:</p>
        <CodeBlock language="text">{`variable_mapping:
  href:     {{core:dl.output.file}}
  filename: {{core:dl.output.file.name}}`}</CodeBlock>
        <CodeBlock language="html">{`<a href="{{href}}" download="{{filename}}">Download</a>`}</CodeBlock>
        <Callout variant="info">
          The HTML <code>download</code> attribute forces a save prompt even when the server would
          otherwise display the file inline.
        </Callout>
        <p>
          An interface can also collect a file from the user: an{' '}
          <code>&lt;input type=&quot;file&quot; name=&quot;photo&quot;&gt;</code> inside a form submits
          through the same contract as a standalone form trigger. The next step reads the uploaded file as
          a FileRef under <code>{'{{trigger:<label>.output.photo}}'}</code>. For back-compat, form uploads
          also emit flat sidecar fields (<code>photo_file_url</code>, <code>photo_file_name</code>,{' '}
          <code>photo_file_size</code>, <code>photo_content_type</code>), but prefer the FileRef object in
          new designs.
        </p>

        <h2>Sending a file to an integration tool</h2>
        <p>
          To send a stored file into a catalog tool that accepts a file (Telegram&apos;s{' '}
          <code>send_photo</code> or <code>send_document</code>, or an image/audio/video/file parameter on
          another API), map the FileRef <strong>object</strong> into that parameter. The platform downloads
          the bytes for you and uploads them as a multipart file part.
        </p>
        <CodeBlock language="text">{`Telegram "Send Photo":
  photo = {{core:generate.output.file}}`}</CodeBlock>
        <p>
          Under the hood, each multipart field is one of three modes: <code>fileRef</code> (always
          uploads bytes from a FileRef), <code>param</code> (always sends the raw value as text), or{' '}
          <code>auto</code> (picks automatically: a FileRef becomes a binary upload, a map or list becomes
          a JSON string, and any plain value, a public URL, a provider file id, a number, is sent verbatim
          as text).
        </p>
        <Callout variant="warn">
          Never drill <code>.path</code> or <code>.id</code> when mapping a file into a tool parameter. A
          drilled string is treated as a plain value (a URL or file id), not as a file to upload, so the
          upload silently doesn&apos;t happen.
        </Callout>

        <h2>Reading documents & seeing images in chat</h2>
        <p>
          When an agent looks at a file, documents are extracted to readable text (so the agent reads the
          actual content, not just a link) and images are shown to a vision-capable model instead.
        </p>
        <DocsTable
          head={['Family', 'Formats']}
          rows={[
            ['Documents', 'PDF, Word (.docx), Excel (.xlsx, one line per row), HTML'],
            [
              'Plain text / code / data',
              '.txt .md .markdown .csv .tsv .json .xml .yaml .yml .log .js .mjs .ts .tsx .jsx .py .java .kt .c .h .cpp .cs .go .rb .rs .php .sh .sql .css .scss .ini .toml .properties .conf .env .srt .vtt (MIME wins, extension is the fallback)',
            ],
            ['Images', 'Never extracted as text; a vision-capable model sees the raw image directly.'],
          ]}
        />
        <ul>
          <li>Document text extraction is skipped over 10 MB (the file is offered as a link instead, with a note to wire it into an Extract from File node for chunked processing).</li>
          <li>Image vision inlining is skipped over roughly 3.6 MB raw.</li>
          <li>Extracted or read text is paged in 128 KB windows, with an offset to expand further for long files.</li>
        </ul>

        <h2>The workspace file browser</h2>
        <p>
          The file browser shows one tree that mixes folders you create by hand with a virtual folder tree
          computed automatically from each file&apos;s run context: Workflow → Epoch → Run → Item.
        </p>
        <DocsTable
          head={['Level', 'What it groups']}
          rows={[
            ['Workflow', 'Every file produced by that workflow, across all its runs.'],
            ['Epoch', 'One trigger firing (labeled "Epoch 1", "Epoch 2", ...).'],
            ['Run', 'A re-execution within that epoch (labeled "Run 1", "Run 2", ...).'],
            ['Item', 'One iteration of a Split, when the step ran once per item (labeled "Item 1", "Item 2", ...).'],
          ]}
        />
        <p>
          Manual folders can be created, and files or folders moved into them or back to the root, but a
          manual folder can never nest inside a virtual workflow folder, only inside another manual folder
          or the root.
        </p>
        <p>
          Browsing is newest-first and paginated. Each entry shows its name, MIME type, size, kind, an
          opaque URL, when it was created, and, when applicable, which run and step produced it. The
          browser lists real files only; step-output data that isn&apos;t a file is read instead through
          the run and node-output views.
        </p>

        <h2>File serving & access control</h2>
        <p>
          Every user-facing file link is absolute and <strong>opaque</strong>: it addresses the file by
          its storage-row id, never by the raw storage key (which would otherwise leak the owning
          workspace&apos;s id).
        </p>
        <CodeBlock language="text">{`https://your-app/api/proxy/files/by-id/{id}/raw?disposition=inline|attachment`}</CodeBlock>
        <p>
          These links don&apos;t expire and are <strong>workspace-scoped</strong>: any member of the
          file&apos;s workspace can open it. A request from a different workspace gets a plain 404, never
          a 403, so a link&apos;s existence is never leaked to someone who shouldn&apos;t have it. Your own
          files remain reachable even while you have a different workspace active.
        </p>
        <p>
          A published marketplace or share preview needs to render for anonymous, logged-out visitors, so
          it uses a second, short-lived form instead: an HMAC-signed URL, valid for 15 minutes, where the
          signature itself is the authorization (no session or workspace header involved).
        </p>
        <DocsTable
          head={['Link form', "Who it's for", 'Lifetime']}
          rows={[
            [<code key="1">/api/proxy/files/by-id/{'{id}'}/raw</code>, "Signed-in members of the file's workspace", 'Does not expire'],
            [<code key="2">/api/files/proxy-signed?...&amp;sig=...</code>, 'Anonymous marketplace/share previews', '15 minutes'],
          ]}
        />
        <Callout variant="info">
          Deleting a file is restricted to the workspace that owns it. Moving or deleting a file also
          respects your role in that workspace: a read-only or restricted member can&apos;t re-parent or
          delete a file they don&apos;t have write access to.
        </Callout>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={Workflow} title="Workflows" href="/workflows">
            Wire file-producing nodes into a run.
          </Card>
          <Card icon={MessageSquare} title="Chat" href="/chat">
            Upload attachments and let an agent read or see them.
          </Card>
          <Card icon={Store} title="Marketplace" href="/marketplace">
            Publish an app whose previews use signed file links.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
