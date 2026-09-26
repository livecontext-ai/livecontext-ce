import { FolderOpen, SlidersHorizontal, Workflow } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card, Steps, Step, CodeBlock } from '../_components';

export const metadata = docsMetadata({
  title: 'Studio',
  description:
    'Generate images, video, sound, speech, and music from a prompt in the Studio, from Files or chat, or with the Generate node in a workflow.',
  path: '/docs/studio',
});

export default function StudioPage() {
  return (
    <>
      <DocsHero
        eyebrow="AI"
        title="Studio"
        lead="The Studio turns a prompt, and optionally a few files, into an image, a video clip, a sound, speech, or music. This page covers generating in the Studio, who pays for a generation, where results are saved, and how to generate from Files, chat, agents, and workflows."
      />

      <DocsProse>
        <h2>What the Studio is</h2>
        <p>
          The Studio is the generation side of the home page. Use the <strong>Chat</strong> /{' '}
          <strong>Studio</strong> switch on the home page to move between the two. Each Studio thread
          is a conversation of its own: it appears in the conversation sidebar (filter it with the{' '}
          <strong>Studio</strong> chip) and keeps every generation you made in it. A thread is only created
          when you send your first request, so opening the Studio and leaving creates nothing.
        </p>
        <p>
          The model you choose decides the format, the settings you can change, and the price. There are
          five formats:
        </p>
        <DocsTable
          caption="Studio output formats"
          head={['Format', 'Produces']}
          rowHeaders
          rows={[
            ['Image', 'Still images.'],
            ['Video', 'Video clips.'],
            ['Sound', 'Sound effects and other audio.'],
            ['Speech', 'A voice reading your text.'],
            ['Music', 'Music tracks.'],
          ]}
        />

        <Callout title="Who can generate">
          <p>
            A generation writes a file into the workspace and can spend credits, so read-only members of
            an organization are not offered it. If the install has no generation model yet, the Studio says{' '}
            <em>No generation model is available yet. An administrator can add one.</em>
          </p>
        </Callout>

        <h2>Generate an asset in the Studio</h2>
        <Steps>
          <Step n={1} title="Choose a model">
            Open the model picker (<strong>Choose a model</strong>, or <strong>Change model</strong> once one
            is selected). Pick <strong>What to make</strong> (the format), then the{' '}
            <strong>Provider</strong>, then the model.
          </Step>
          <Step n={2} title="Choose who pays">
            Under the model list, set <strong>Credential source</strong> to <strong>Platform</strong> or <strong>My credential</strong>. See{' '}
            <a href="#who-pays">Who pays for a generation</a>.
          </Step>
          <Step n={3} title="Describe it">
            Type your prompt in the composer (<em>Describe what to create with</em> the model you chose).
            Some models also run with no prompt, on their files and settings only.
          </Step>
          <Step n={4} title="Set the parameters and add files">
            Open <strong>Parameters</strong> to change the settings the model accepts. To give the model a
            picture or a clip, press <strong>Add a file</strong> and answer{' '}
            <strong>What is this file for?</strong>. Settings the model requires are highlighted until you
            fill them in.
          </Step>
          <Step n={5} title="Create">
            Check the price shown next to the button, then press <strong>Create</strong>. A video can take a
            few minutes. The result appears as a card in the thread.
          </Step>
        </Steps>

        <h3>Parameters</h3>
        <p>
          Each model lists its own parameters, so not every model shows every one. The common ones are:
        </p>
        <DocsTable
          caption="Common generation parameters"
          head={['Parameter', 'What it sets']}
          rowHeaders
          rows={[
            ['What to avoid', 'A negative prompt: what should not appear.'],
            ['Aspect ratio', 'The shape of an image or video frame.'],
            ['Resolution', 'The output size.'],
            ['Quality', 'The quality level the model offers.'],
            ['Duration (seconds)', 'The length of a video or audio clip.'],
            ['How many', 'The number of images to produce in one request.'],
            ['Seed', 'A number that makes a result reproducible on models that support it.'],
            ['Voice, Language, Style', 'Voice and style options, on models that have them.'],
          ]}
        />
        <p>
          When a parameter has a fixed list of values, the Studio shows them. Some lists are read from the
          provider when you open them (<em>Asking the provider</em>). If the list cannot be read, or is
          long and shown as a sample, you can still type a value.
        </p>

        <h3>Reference files</h3>
        <p>
          A model that accepts files asks what each file is for. The roles it offers depend on the model,
          and some roles cannot be combined (the Studio says which go together):
        </p>
        <DocsTable
          caption="Roles of the files you attach"
          head={['Role', 'What the model does with it']}
          rowHeaders
          rows={[
            ['Source image', 'Transforms it: the file comes back changed.'],
            ['First frame', 'The clip opens on this image.'],
            ['Last frame', 'The clip ends on this image.'],
            ['Reference image', 'Borrows its subject or style. It never appears as a frame.'],
            ['Mask', 'Marks where the other image may be changed.'],
          ]}
        />

        <h3>Reuse and modify a result</h3>
        <p>
          On a result card, <strong>Modify</strong> loads the prompt, model, settings, and files that made
          it back into the composer, so you can change one thing and create again.{' '}
          <strong>See it in Files</strong> opens the saved file. On an empty Studio,{' '}
          <strong>Your generations</strong> lists what your workspace has already generated, and the same
          actions are available there.
        </p>

        <h2 id="who-pays">Who pays for a generation</h2>
        <p>Every generation runs on one of two keys:</p>
        <DocsTable
          caption="Who pays for a generation"
          head={['Choice', 'Who is billed', 'Price shown']}
          rowHeaders
          rows={[
            [
              'Platform',
              'You, in credits, at the rate published for that model.',
              <>
                Before you create: <em>N credits for this request</em>, computed from your settings (a
                longer video or a longer text costs more). After: <em>Billed on</em> the quantity measured.
              </>,
            ],
            [
              'My credential',
              'The provider bills you directly, on a provider key you connected. The platform charges nothing for the generation itself.',
              'No credit price is shown.',
            ],
          ]}
        />
        <p>
          <strong>Platform</strong> is offered only for a model the platform actually sells: a platform key exists
          for that provider and a price is published for that model. Otherwise the model shows{' '}
          <em>Not sold on the platform key</em> and the Studio switches you to <strong>My credential</strong>,
          where you can connect a key for that provider without leaving the picker. Generations are
          measured per call, per second, per image, or per character, depending on the model.
        </p>
        <Callout title="Self-hosted (CE)">
          <p>
            A self-hosted install usually holds no platform keys, so generations run on provider keys you
            connected. If the install is linked to LiveContext Cloud and its <strong>Integration
            credentials</strong> source is set to <strong>Cloud</strong>, generation runs on the cloud
            account&apos;s provider keys instead and is billed there in credits, at the price published for
            each model. That requires an active paid subscription on the linked cloud account. See{' '}
            <a href="/self-host">Self-hosting</a>.
          </p>
        </Callout>

        <h2>Available models</h2>
        <p>
          The generation catalog is shared by the Studio, the Generate dialog, agents, and the Generate
          node. The catalog shipped with LiveContext includes models from AudioCraft, Clipdrop, Deepgram,
          ElevenLabs, Flux, Google Gemini, HeyGen, Higgsfield, Ideogram, OpenAI, Runway, Seedance, Stability
          AI, and xAI. What you can pick, and on which key, depends on what your install has enabled and
          priced. Generation models are not part of the chat model list in{' '}
          <strong>Settings &rsaquo; AI Providers</strong> (see <a href="/models">Models &amp; providers</a>).
        </p>

        <h2>Where results are saved</h2>
        <p>
          Every generated asset is stored as a file in your workspace, so it outlives the provider&apos;s own
          temporary link. Open it with{' '}
          <strong>See it in Files</strong>. See <a href="/files">Files</a>.
        </p>

        <h2>Other ways to generate</h2>
        <h3>From Files and chat</h3>
        <p>
          The <strong>Generate</strong> button in the Files toolbar, and the{' '}
          <strong>Generate an image, video or sound</strong> button in the chat tab of the side panel, open the{' '}
          <strong>Generate an asset</strong> dialog: <strong>Format</strong>, then <strong>Prompt</strong>{' '}
          (pick a model and describe it), then <strong>Result</strong>. The dialog uses the same models and
          prices as the Studio, and <strong>Past generations</strong> lets you start again from an earlier
          result. Files also lists your past generations beside the button.
        </p>

        <h3>From an agent or a chat</h3>
        <p>
          An agent can generate assets only when its <strong>Generation</strong> setting is on. It is off
          by default, for agents and for the chat assistant (in the chat settings), because every asset
          spends credits at the rate of the model used. See <a href="/agents">Agents</a> and{' '}
          <a href="/chat">Chat</a>.
        </p>

        <h3>In a workflow: the Generate node</h3>
        <p>
          The <strong>Generate</strong> node runs the same generation as a workflow step. Set the model,
          the prompt, the model&apos;s parameters, and who pays (<code>platform</code>, the default, or{' '}
          <code>user</code> for your own key). Whatever the format, the node always has the same outputs:
        </p>
        <DocsTable
          caption="Generate node outputs"
          head={['Output', 'Content']}
          rows={[
            [<code key="f">file</code>, 'The generated asset as a file reference. Map the whole object into a downstream file parameter.'],
            [<code key="m">model</code>, 'The model that ran.'],
            [<code key="k">kind</code>, 'The format produced: image, video, audio, voice, or music.'],
            [<code key="p">provider</code>, 'The provider whose model ran.'],
            [<code key="q">billed_quantity</code>, 'The size the run was billed on (for example 10 for a 10-second video; 1 for a model sold per call).'],
            [<code key="u">billed_unit</code>, 'What billed_quantity counts: call, second, image, or character.'],
            [<code key="c">billed_credits</code>, 'Credits charged for the run. Absent when you paid the provider directly with your own key.'],
            [<code key="r">provider_response</code>, 'The raw payload of the provider. Its shape varies by provider, so do not build on it.'],
          ]}
        />
        <CodeBlock language="text" title="Using the generated file downstream">{`{{agent:generate_cover.output.file}}`}</CodeBlock>
        <p>
          See <a href="/nodes">Nodes</a> for the other node types and <a href="/files">Files</a> for passing
          files between steps.
        </p>

        <h2>Troubleshooting</h2>
        <DocsTable
          caption="Studio messages and what they mean"
          head={['What you see', 'What it means']}
          rows={[
            [
              <em key="a">No answer came back for this one.</em>,
              'The request may still have finished and been charged. Check your files before running it again.',
            ],
            [
              <em key="b">This was refused before it ran, so nothing was charged.</em>,
              'Nothing was charged, and your prompt and files are kept. Check the settings, the files, or who pays, then create again.',
            ],
            [
              <em key="c">It finished, but nothing came back that this page can show.</em>,
              'If an asset was produced, it is in your files.',
            ],
            [
              <em key="d">This generation ran and was charged for, but the file could not be saved.</em>,
              <>The generation ran and was charged, but saving failed. Use <strong>Save the asset from the provider</strong> right away: the link expires in a few minutes.</>,
            ],
            [
              <em key="e">Not sold on the platform key</em>,
              <>No platform price is published for this model. Use <strong>My credential</strong> with your own provider key.</>,
            ],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={3}>
          <Card icon={FolderOpen} title="Files" href="/files">Where generated assets are stored and how to pass them on.</Card>
          <Card icon={Workflow} title="Nodes" href="/nodes">The Generate node and the other workflow steps.</Card>
          <Card icon={SlidersHorizontal} title="Models & providers" href="/models">Chat and agent models, keys, and bridges.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
