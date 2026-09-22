/**
 * The registry half of the run-mode Params contract.
 *
 * A backend node reports a key; this registry gives it a human label. The two
 * halves are one contract, and the backend half is the only one the e2e can see:
 * a node the alignment spec cannot reach (it ends its run failing, it needs a
 * live LLM, it parks on a signal) has NOTHING checking that its reported keys
 * still have labels here. Renaming a key on one side and not the other is then
 * silent, and the Params column falls back to `humanizeKey` - which is exactly
 * the raw-key rendering this work removed.
 *
 * These are the pairs the e2e cannot cover. Each entry is the key a node
 * actually reports, verified against the node source.
 */
import { describe, expect, it } from 'vitest';

import { getInputLabel, humanizeKey, inputLabelRegistry } from '../input-label-registry';
import type { InspectorNodeType } from '../../core/types';

/** `<inspector type>` -> keys its node reports that the e2e can never see. */
const UNREACHABLE_BY_E2E: Record<string, string[]> = {
  // StopOnErrorNode ends the run, so its row is always FAILED and the spec reads
  // only completed rows. Renamed from error_message / error_code to match
  // StopOnErrorConfig.
  stop_on_error: ['errorMessage', 'errorCode'],
  // PublicLinkNode: `file` is the plan's own param name (stepProcessor writes
  // params.file / ttl_minutes / disposition).
  public_link: ['file', 'ttl_minutes', 'disposition'],
  // DownloadFileNode reports these on BOTH exit paths since the rename.
  download_file: ['url', 'filename', 'mimeType'],
  // RespondToWebhookNode had a label waiting on a key it never sent.
  respond_to_webhook: ['statusCode', 'contentType', 'body', 'headers'],
  // SftpNode summarises the payload rather than dumping a whole file.
  sftp: ['localContentSize'],
  // GenerateNode reports whatever unified parameters the chosen model accepts, and the
  // file slots are the ones a catalogue change can add without touching this file: a
  // model that takes a first frame and a last frame reported two keys nothing labelled.
  generate: [
    'model', 'prompt', 'input_image', 'input_audio', 'input_video',
    'first_frame_image', 'last_frame_image', 'reference_image',
  ],
  // A split reports `error` beside its configuration on the failure path, and the
  // spec reads completed rows only.
  split: ['error'],
  // An interface is unreachable for a different reason on each of its two shapes:
  // with a `__continue` mapping it parks on INTERFACE_SIGNAL and its row never
  // settles for the spec's quiescence poll, and either way it is built from an
  // InterfaceDef rather than from `plan.cores`, which is the collection the spec
  // walks. `variableMapping` is the key a reader opens the panel for: the wiring
  // between the workflow's data and an empty-looking page.
  interface: [
    'interfaceId', 'actions', 'variableMapping', 'variableMappingError',
    'isEntryInterface', 'generateScreenshot', 'exposeRenderedSource',
    'generatePdf', 'pdfFormat', 'pdfLandscape',
    'generateVideo', 'videoPreset', 'videoMaxDurationSeconds', 'videoMode', 'videoFps',
  ],
  // A find node is built from plan.tables, which `collectAlignment` never walks,
  // so no fixture can put these under the e2e whatever it contains. `list` is the
  // expression the author wrote and `listResolved` what it evaluated to.
  'find-row': ['list', 'listResolved', 'maxItems'],
  // A user approval parks on USER_APPROVAL, so like the interface its row never settles
  // for the spec's quiescence poll. These are the keys SignalResumeService writes onto the
  // row the approval finally gets, and the third parking node was the one that had no
  // frontend half at all: `detectNodeType` returned `unknown` for it, so every key fell
  // through to humanizeKey whatever this registry said.
  user_approval: [
    'approverRoles', 'requiredApprovals', 'timeoutMs', 'contextTemplate',
    'delegation', 'continuationMode',
  ],
};

describe('input label registry covers the keys the e2e cannot reach', () => {
  for (const [nodeType, keys] of Object.entries(UNREACHABLE_BY_E2E)) {
    it(`labels every key ${nodeType} reports`, () => {
      const labels = inputLabelRegistry[nodeType as keyof typeof inputLabelRegistry] ?? {};
      const unlabelled = keys.filter((key) => !labels[key]);
      expect(
        unlabelled,
        `${nodeType} reports these keys with no label, so the Params column shows a humanised raw key: ${unlabelled.join(', ')}`,
      ).toEqual([]);
    });
  }

  it('does not count a label that says exactly what the fallback would have said', () => {
    // The check above passes on a label byte-identical to `humanizeKey`, which is the
    // rendering this registry exists to replace: `interfaceId` -> "Interface Id" is an
    // entry that costs a line and changes nothing, and it hides the fact that the key has
    // no real label. Listed rather than asserted empty, because some of these ARE the right
    // words and a registry entry is still how they stay pinned when the key is renamed;
    // what must not grow silently is the list.
    const redundant: string[] = [];
    for (const [nodeType, keys] of Object.entries(UNREACHABLE_BY_E2E)) {
      const labels = inputLabelRegistry[nodeType as keyof typeof inputLabelRegistry] ?? {};
      for (const key of keys) {
        if (labels[key] && labels[key] === humanizeKey(key)) {
          redundant.push(`${nodeType}.${key}`);
        }
      }
    }
    expect(redundant.sort()).toMatchInlineSnapshot(`
      [
        "download_file.filename",
        "find-row.maxItems",
        "generate.input_audio",
        "generate.input_image",
        "generate.input_video",
        "generate.model",
        "generate.prompt",
        "interface.actions",
        "interface.exposeRenderedSource",
        "interface.videoMode",
        "interface.videoPreset",
        "public_link.disposition",
        "public_link.file",
        "respond_to_webhook.body",
        "respond_to_webhook.contentType",
        "respond_to_webhook.headers",
        "respond_to_webhook.statusCode",
        "split.error",
        "stop_on_error.errorCode",
        "stop_on_error.errorMessage",
        "user_approval.approverRoles",
        "user_approval.delegation",
        "user_approval.requiredApprovals",
      ]
    `);
  });

  it('no longer labels the names those nodes stopped reporting', () => {
    // The other direction: a label left behind after a rename is a promise the
    // product cannot keep, and it is how `public_link.file` came to be declared
    // for years while the node sent `file_expression`.
    const stale: Array<[string, string]> = [
      ['stop_on_error', 'error_message'],
      ['stop_on_error', 'error_code'],
      ['public_link', 'file_expression'],
      ['download_file', 'url_expression'],
      ['download_file', 'filename_expression'],
      ['download_file', 'mime_type_expression'],
      // The generate node never reported these: they are how the same parameter was
      // named on one surface while three others had moved on.
      ['generate', 'input_image_url'],
      ['generate', 'reference_images'],
    ];
    const leftovers = stale.filter(([nodeType, key]) => {
      const labels = inputLabelRegistry[nodeType as keyof typeof inputLabelRegistry] ?? {};
      return Boolean(labels[key]);
    });
    expect(leftovers, `stale labels: ${JSON.stringify(leftovers)}`).toEqual([]);
  });
});

describe('keys the reporting gate adds to any node', () => {
  it('labels `paramsTruncated`, which belongs to no node type', () => {
    // `ReportedParams.forReport` bounds the WHOLE map and states under this key how
    // many entries it dropped. It can appear on a split, an agent or an interface
    // alike, so no per-type entry can cover it; left to `humanizeKey` it reads
    // "Params Truncated" beside the real parameters, as if the node had one by that
    // name. Asserted on two unrelated node types, because the point is that the
    // label does not depend on which node is being read.
    expect(getInputLabel('split' as InspectorNodeType, 'paramsTruncated')).toBe('Truncated');
    // The signal bookkeeping a parked node's resumed row carries: interface, approval and
    // wait all get these, so no per-type block can own them.
    for (const key of ['signal_type', 'signal_config', 'item_id', 'trigger_id', 'epoch']) {
      expect(
        getInputLabel('interface' as InspectorNodeType, key),
        `${key} must read the same on every parked node: no per-type block can own it`,
      ).toBe(getInputLabel('user_approval' as InspectorNodeType, key));
    }
    expect(getInputLabel('agent' as InspectorNodeType, 'paramsTruncated')).toBe('Truncated');
  });

  it('is read BEFORE the per-type registry, and no node type claims its key', () => {
    // Two halves of one rule, and the first was untested: asserting that an ordinary key
    // still resolves normally passes whichever way round the lookup goes, and passes with
    // the cross-node map deleted. What actually matters is that the cross-node entry WINS
    // for its key, and therefore that no node type may label that key for itself - a
    // per-type `paramsTruncated` would be silently dead code.
    const claimedByANodeType = Object.entries(inputLabelRegistry).filter(
      ([, labels]) => labels && 'paramsTruncated' in labels,
    );
    expect(
      claimedByANodeType.map(([nodeType]) => nodeType),
      'these node types label `paramsTruncated`, which the cross-node map already wins',
    ).toEqual([]);
    // 'split' has its own label block, and the cross-node key still beats it.
    expect(getInputLabel('split' as InspectorNodeType, 'paramsTruncated')).toBe('Truncated');
    // The signal bookkeeping a parked node's resumed row carries: interface, approval and
    // wait all get these, so no per-type block can own them.
    for (const key of ['signal_type', 'signal_config', 'item_id', 'trigger_id', 'epoch']) {
      expect(
        getInputLabel('interface' as InspectorNodeType, key),
        `${key} must read the same on every parked node: no per-type block can own it`,
      ).toBe(getInputLabel('user_approval' as InspectorNodeType, key));
    }
    // While an ordinary key still takes the per-type path.
    expect(getInputLabel('split' as InspectorNodeType, 'listResolved')).toBe('Items (resolved)');
  });
});
