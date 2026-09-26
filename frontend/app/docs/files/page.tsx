import { Workflow, MessageSquare, Clapperboard, Table2 } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Files & storage',
  description:
    'How LiveContext stores and moves files: upload limits, the account storage pool, the FileRef object, file nodes, public links, files in interfaces and tools, and access control.',
  path: '/docs/files',
});

export default function FilesPage() {
  return (
    <>
      <DocsHero
        eyebrow="Data"
        title="Files & storage"
        lead="Every file your workflows, interfaces, and chats touch (images, PDFs, audio, video, exports, uploads) lands in the same storage and is represented the same way everywhere: a single FileRef object. Learn the limits, the shape, and where files show up across the product."
      />

      <DocsProse>
        <h2>Uploading files</h2>
        <p>
          Uploads through the Files page, the Data Input form node, and interface or form file inputs
          accept up to 50 MB per file. Go over it and the upload is rejected with{' '}
          <code>413 Payload Too Large</code>.
        </p>
        <Callout variant="warn" title="Chat attachments">
          On the managed cloud, a file attached in the chat composer is accepted up to{' '}
          10 MB. For a larger file, upload it on the Files page instead.
        </Callout>
        <p>
          You can drop a new upload straight into an existing manual folder. An invalid, blank, or
          cross-workspace folder doesn&apos;t fail the upload; the file simply lands at the root of your
          file browser instead.
        </p>

        <h2>Storage quota</h2>
        <p>
          A plan&apos;s included storage is one pool per account: usage is added up
          across every workspace the account owns. The quota is checked on every write, and once the
          pool is full every workspace of that account refuses new files, including one
          that stored nothing itself.
        </p>
        <DocsTable
          caption="Included storage per plan"
          rowHeaders
          head={['Plan', 'Included storage']}
          rows={[
            ['Free', '100 MB'],
            ['Starter', '1 GB'],
            ['Pro', '10 GB'],
            ['Team', '100 GB'],
            ['Enterprise Basic', '500 GB'],
            ['Enterprise Standard', '1 TB'],
            ['Enterprise Premium', '~2.5 TB'],
            ['Enterprise Ultimate', '5 TB'],
          ]}
        />
        <p>
          Past 80% of the pool the status reads <strong>Approaching limit</strong>. A write that would go
          over it fails with <code>413</code> and &ldquo;Storage quota exceeded&rdquo;. Self-hosted
          Community Edition on the Free tier has no quota: local storage is unlimited.
        </p>
        <h3>The Storage settings page</h3>
        <p>
          <strong>Settings</strong> &gt; <strong>Storage</strong> shows your usage against the plan, a{' '}
          <strong>Storage Breakdown</strong> by category (workflows, tables, files), and{' '}
          <strong>Recalculate usage</strong> to refresh the figures. Choose a single workspace or{' '}
          <strong>All workspaces</strong>; the account line reads &ldquo;shared across your
          workspaces&rdquo;. Members of a workspace see that workspace&apos;s own usage and whether the
          pool is full, not the account total.
        </p>

        <h2>The canonical FileRef</h2>
        <p>
          Every file your workflow touches, whatever produced it, is the same shape: a{' '}
          FileRef. It carries a display name, a MIME type, a size in bytes, an id used to
          build the file&apos;s URL, and an internal storage path.
        </p>
        <CodeBlock language="json" title="FileRef">{`{
  "_type": "file",
  "id": "b21f6c1e-5a9d-4c1b-9d0e-2f7a8c3e41d2",
  "path": "<internal storage key>",
  "name": "report.pdf",
  "mimeType": "application/pdf",
  "size": 48213
}`}</CodeBlock>
        <p>
          Everything that can hand you a file emits this shape: catalog tools that return binaries
          (image generation, screenshots, and the like), the file nodes below, interface and form file
          uploads, and chat attachments. Anything a catalog tool returns as a large base64 blob is also
          stored and rewritten into a FileRef for you.
        </p>
        <p>
          To pass a file from one step to the next, map the FileRef object itself, not
          one of its fields:
        </p>
        <CodeBlock language="text">{`{{core:my_download.output.file}}
{{mcp:generate_image.output.images[0]}}`}</CodeBlock>
        <Callout variant="warn">
          Drilling <code>.name</code>, <code>.mimeType</code>, or <code>.size</code> is safe (they are
          plain metadata). Never drill <code>.path</code> or <code>.id</code>: those are bare storage
          identifiers with no auth attached, and a step expecting a full FileRef treats a drilled string
          as a URL or a file id instead of uploading or displaying the actual file.
        </Callout>

        <h3>Where a FileRef stops being a file</h3>
        <ul>
          <li>
            <strong>Tables</strong>: store a file in a <code>file</code> or <code>image</code> column and
            it reads back as a file reference. Stored in a <code>text</code> column it becomes plain
            text. See <a href="/tables">Tables &amp; data</a>.
          </li>
          <li>
            <strong>Aggregate</strong>: collecting files with an Aggregate step turns each one into text.
            To gather several files into a list that later steps can still use as files, collect them in a
            Code step instead.
          </li>
        </ul>

        <h2>File nodes</h2>
        <p>
          These core nodes produce, transform, or read files. Most of them emit a FileRef under the same
          output key, <code>.output.file</code>, so once you know the pattern you can wire any of them the
          same way.
        </p>
        <DocsTable
          caption="Nodes that produce, transform, or read files"
          rowHeaders
          head={['Node', 'What it does', 'Notable outputs']}
          rows={[
            [
              'Download File',
              'Downloads a file from an external URL and stores it. Use it when the source is a public URL (a user pasted an image link into a form, for example).',
              <>
                <code>file</code>, <code>source_url</code>
              </>,
            ],
            [
              'Convert to File',
              'Converts data into CSV, Excel, or JSON and stores the result as a file.',
              <>
                <code>file</code>, <code>result</code> (inline contents), <code>format</code> (default csv), <code>row_count</code>
              </>,
            ],
            [
              'Extract from File',
              'Reads a file. Structured (rows) mode parses CSV, Excel, or JSON into rows. Text (raw content) mode extracts the text of a PDF, Word, HTML, or plain text file and can split it into chunks.',
              <>
                <code>items</code>, <code>format</code>, <code>rowCount</code>; in text mode each item has{' '}
                <code>content</code>, <code>chunk_index</code>, <code>total_chunks</code>
              </>,
            ],
            [
              'Media',
              'Audio and video processing: probe metadata, add an audio track to a video, mix tracks, extract audio, join videos, grab a still frame, overlay an image (watermark), burn in subtitles.',
              <>
                <code>file</code>, <code>duration_seconds</code>; probe returns metadata and no file
              </>,
            ],
            [
              'Public Link',
              'Creates a temporary public URL for a stored file (see below).',
              <>
                <code>url</code>, <code>expires_at</code>, <code>ttl_minutes</code>, <code>file</code>
              </>,
            ],
            [
              'SFTP',
              'Remote file operations: upload, download, list, delete, rename, mkdir. A download emits the FileRef; list returns each remote entry.',
              <>
                <code>files[]</code>, <code>file_count</code>, <code>duration_ms</code>
              </>,
            ],
            [
              'Compression',
              'Compresses or decompresses data (gzip, zip).',
              <>
                <code>result</code>, <code>format</code>
              </>,
            ],
          ]}
        />
        <p>
          After a catalog tool that already returns a file, you don&apos;t need a Download File node too:
          the file is already stored and already a FileRef. When joining videos with Media, list each
          input explicitly in the step (up to 8); a list built at run time can&apos;t be passed in as a
          whole.
        </p>
        <Callout variant="info">
          These nodes expose their file only under <code>.file</code>. Older flat fields like{' '}
          <code>.file_url</code>, <code>.file_name</code>, <code>.file_size</code>, and{' '}
          <code>.content_type</code> no longer exist on them and resolve to nothing at runtime; use{' '}
          <code>.file.name</code> for the filename, <code>.file.size</code> for the size, and so on.
        </Callout>

        <h3>Chunking documents with Extract from File</h3>
        <p>
          In <strong>Text (raw content)</strong> mode, turn on <strong>Enable chunking</strong> to split
          the extracted text into overlapping pieces, for example to embed them into a vector table. Pick
          a <strong>Chunking strategy</strong> (<strong>Fixed size</strong>,{' '}
          <strong>Recursive (paragraphs, then sentences)</strong>, or <strong>Custom separator</strong>),
          a <strong>Chunk size</strong> and an <strong>Overlap</strong>, counted in characters or tokens.
          Each chunk becomes one item, ready for a Split.
        </p>

        <h2>Giving a file to an external service</h2>
        <p>
          Some APIs don&apos;t accept an upload and instead fetch the file from a URL you give them. The{' '}
          <strong>Public Link</strong> node creates a signed URL that anyone can download from, with no
          sign-in, until it expires:
        </p>
        <ul>
          <li>Lifetime (<code>ttl_minutes</code>): 240 minutes (4 hours) by default, from 5 minutes up to 10,080 minutes (7 days).</li>
          <li>A link can&apos;t be revoked before it expires, so keep the lifetime as short as the receiving service allows.</li>
          <li>It only works on files that belong to your workspace.</li>
        </ul>
        <CodeBlock language="text">{`Public Link "Share video":
  file        = {{core:render.output.file}}
  ttl_minutes = 60

Later step:
  video_url = {{core:share_video.output.url}}`}</CodeBlock>

        <h2>Sending a file to an integration tool</h2>
        <p>
          To send a stored file into a catalog tool that accepts a file (Telegram&apos;s{' '}
          <code>send_photo</code> or <code>send_document</code>, or an image, audio, video, or file
          parameter on another API), map the FileRef object into that parameter. The
          platform downloads the bytes for you and sends them in the form the API expects.
        </p>
        <CodeBlock language="text">{`Telegram "Send Photo":
  photo = {{agent:generate.output.file}}`}</CodeBlock>
        <p>
          For a multipart form, each field is one of three modes: <code>fileRef</code> (always uploads
          bytes from a FileRef), <code>param</code> (always sends the raw value as text), or{' '}
          <code>auto</code> (picks automatically: a FileRef becomes a binary upload, a map or list
          becomes a JSON string, and any plain value, a public URL, a provider file id, a number, is sent
          as text).
        </p>
        <Callout variant="warn">
          Never drill <code>.path</code> or <code>.id</code> when mapping a file into a tool parameter. A
          drilled string is treated as a plain value (a URL or file id), not as a file to upload, so the
          upload silently doesn&apos;t happen.
        </Callout>

        <h2>Files in interfaces</h2>
        <p>
          Map a FileRef into <code>variable_mapping</code> under any friendly name, then use that name in
          an <code>&lt;img src&gt;</code>, <code>&lt;a href&gt;</code>, or <code>&lt;video src&gt;</code>.
          The renderer rewrites the FileRef into a usable URL, for single files and lists of files alike,
          so there&apos;s no URL to build by hand. The page never sees your session token: the app loads
          each file for it and hands the page the file&apos;s contents directly.
        </p>
        <CodeBlock language="html">{`<!-- variable_mapping: { "photo": "{{agent:generate.output.file}}" } -->
<img src="{{photo}}" alt="Generated" />`}</CodeBlock>
        <p>A download link needs both the file and its name mapped separately:</p>
        <CodeBlock language="text">{`variable_mapping:
  href:     {{core:dl.output.file}}
  filename: {{core:dl.output.file.name}}`}</CodeBlock>
        <CodeBlock language="html">{`<a href="{{href}}" download="{{filename}}">Download</a>`}</CodeBlock>
        <p>
          An interface can also collect a file from the user: an{' '}
          <code>&lt;input type=&quot;file&quot; name=&quot;photo&quot;&gt;</code> inside a form submits
          through the same contract as a standalone form trigger. The next step reads the uploaded file as
          a FileRef under <code>{'{{trigger:<label>.output.photo}}'}</code>. Form uploads also emit flat
          sidecar fields (<code>photo_file_url</code>, <code>photo_file_name</code>,{' '}
          <code>photo_file_size</code>, <code>photo_content_type</code>), but prefer the FileRef object in
          new designs. See <a href="/interfaces">Interfaces &amp; apps</a>.
        </p>

        <h2>Reading documents & seeing images in chat</h2>
        <p>
          When an agent looks at a file, documents are extracted to readable text (so the agent reads the
          actual content, not just a link) and images are shown to a vision-capable model instead.
        </p>
        <DocsTable
          caption="File formats an agent can read"
          rowHeaders
          head={['Family', 'Formats']}
          rows={[
            ['Documents', 'PDF, Word (.docx), Excel (.xlsx, one line per row), HTML'],
            [
              'Plain text, code, data',
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
          computed automatically from each file&apos;s run context: workflow, then epoch (one trigger
          firing), then run, then item (one iteration of a Split).
        </p>
        <p>
          Manual folders can be created, and files or folders moved into them or back to the root, but a
          manual folder can never nest inside a virtual workflow folder, only inside another manual folder
          or the root.
        </p>
        <p>
          Browsing is newest-first and paginated. Each entry shows its name, MIME type, size, kind, when
          it was created, and, when applicable, which run and step produced it. The browser lists real
          files only; step-output data that isn&apos;t a file is read instead through the run and
          node-output views.
        </p>
        <h3>Generating a file</h3>
        <p>
          The <strong>Generate</strong> button in the file browser generates a media asset
          with an AI model and saves the result as a file. <strong>Generated</strong> switches the view to
          the list of assets you generated, with the model and prompt that produced each one; open one to
          view it, or reuse its settings to generate again. See <a href="/studio">Studio</a> for the
          models and options. The buttons appear only where generation is available.
        </p>

        <h2>File serving & access control</h2>
        <p>
          Every user-facing file link is opaque: it addresses the file by its id, never
          by the raw storage key (which would otherwise leak the owning workspace&apos;s id).
        </p>
        <CodeBlock language="text">{`https://your-app/api/proxy/files/by-id/{id}/raw?disposition=inline|attachment`}</CodeBlock>
        <p>
          These links don&apos;t expire and are workspace-scoped: any member of the
          file&apos;s workspace can open it. A request from a different workspace gets a plain 404, never
          a 403, so a link&apos;s existence is never leaked to someone who shouldn&apos;t have it.
        </p>
        <p>
          A published marketplace or share preview needs to render for anonymous visitors, so it uses a
          second, expiring form instead: a signed URL, valid for 4 hours by default, where the signature
          itself is the authorization.
        </p>
        <DocsTable
          caption="File link forms"
          rowHeaders
          head={['Link form', "Who it's for", 'Lifetime']}
          rows={[
            [<code key="1">/api/proxy/files/by-id/{'{id}'}/raw</code>, "Signed-in members of the file's workspace", 'Does not expire'],
            [<code key="2">/api/files/proxy-signed?...&amp;sig=...</code>, 'Anonymous marketplace and share previews', '4 hours by default'],
            ['Public Link node URL', 'Any external service or person you give it to', '4 hours by default, up to 7 days'],
          ]}
        />
        <Callout variant="info">
          Deleting a file is restricted to the workspace that owns it, and moving or deleting a file also
          respects your role in that workspace.
        </Callout>

        <h2>Troubleshooting</h2>
        <DocsTable
          caption="Common file problems"
          rowHeaders
          head={['Symptom', 'Cause', 'Fix']}
          rows={[
            [
              <span key="s">
                An upload fails with <code>413 Payload Too Large</code>
              </span>,
              'The file is larger than 50 MB.',
              'Split or compress the file before uploading it.',
            ],
            [
              'A write fails with "Storage quota exceeded"',
              'The storage pool of the account that owns the workspace is full. The pool is shared by all of that account\'s workspaces.',
              'Delete files you no longer need, then use Recalculate usage in Settings > Storage, or move to a plan with more storage.',
            ],
            [
              'A file will not attach in the chat composer',
              'On LiveContext Cloud, a chat attachment is limited to 10 MB.',
              'Upload the file on the Files page instead.',
            ],
            [
              'A later step receives text or an id instead of a file',
              <span key="c">
                The step was given <code>.path</code> or <code>.id</code> instead of the whole FileRef, or the
                file went through a <code>text</code> column or an Aggregate step on the way.
              </span>,
              'Map the FileRef object itself, store files in file or image columns, and collect several files in a Code step.',
            ],
            [
              'An upload landed at the root instead of in a folder',
              'The target folder was invalid, blank, or in another workspace.',
              'Move the file into the folder from the file browser.',
            ],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={2}>
          <Card icon={Workflow} title="Node reference" href="/nodes">
            Every file node and its settings.
          </Card>
          <Card icon={Table2} title="Tables & data" href="/tables">
            Store files in file and image columns.
          </Card>
          <Card icon={Clapperboard} title="Studio" href="/studio">
            Generate media with AI models.
          </Card>
          <Card icon={MessageSquare} title="Chat" href="/chat">
            Attach files and let an agent read or see them.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
