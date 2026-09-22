import React from 'react';

/**
 * Emphasis inside an ALREADY-TRANSLATED string, written as `**bold**`.
 *
 * <p><b>Why a marker and not next-intl's rich text.</b> `t.rich` is the right
 * tool wherever a component reads its own message, and it stays the tool there.
 * These messages are different: the pricing surfaces pass a feature through as
 * one `"label||tooltip"` STRING (see {@link FeatureLabel}), built by
 * `planFeatureLabels` and read by four separate components. A `t.rich` call
 * returns a ReactNode, which cannot survive that pipe, and turning the pipe into
 * ReactNode would touch every plan card, the comparison table and the
 * insufficient-credits modal to bold three figures. A marker the string can
 * carry keeps the change where it belongs: in the message and in the one place
 * that renders it.
 *
 * <p>The same marker is used by the model pickers' credit-estimate tooltips,
 * which DO read their own message and could have used `t.rich`. They share this
 * instead so there is ONE way to bold a figure in a credit tooltip: those
 * tooltips and the pricing ones are the same sentence in two places, and two
 * mechanisms for one sentence is how the next editor updates half of it.
 *
 * <p>`t.rich` remains the tool elsewhere, and `components/pricing/FoundingPriceNote`
 * is the standing example: it emphasises a date in a message it owns, nothing
 * pipes that string anywhere, and there is no reason for it to change.
 *
 * <p><b>ICU-safe.</b> `*` has no meaning in ICU MessageFormat (only `{`, `}`,
 * `#` and `'` do), so a marked message formats and interpolates exactly as it
 * did before, in every locale.
 */

/**
 * `**...**`, non-greedy and never spanning another `*`, so two marked spans in
 * one sentence stay two spans instead of swallowing the text between them.
 */
const BOLD_SEGMENT = /\*\*([^*]+)\*\*/g;

/**
 * The string with every `**marked**` span wrapped in a `<strong>`.
 *
 * <p>Returns the input unchanged when there is nothing to mark, so a caller can
 * use it unconditionally on any translated string.
 *
 * <p>An UNPAIRED `**` is left visible rather than stripped. It is an authoring
 * mistake in one locale's message, and a stray `**` on screen is how the next
 * person finds out; silently swallowing it would leave the figure un-bolded in
 * that locale alone, which is exactly the kind of drift nobody notices.
 */
export function renderBoldMarkup(text: string): React.ReactNode {
  if (!text.includes('**')) return text;

  const nodes: React.ReactNode[] = [];
  let cursor = 0;
  BOLD_SEGMENT.lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = BOLD_SEGMENT.exec(text)) !== null) {
    if (match.index > cursor) nodes.push(text.slice(cursor, match.index));
    nodes.push(
      <strong key={`b${match.index}`} className="font-semibold">
        {match[1]}
      </strong>
    );
    cursor = match.index + match[0].length;
  }
  if (nodes.length === 0) return text;
  if (cursor < text.length) nodes.push(text.slice(cursor));
  return nodes;
}

export default renderBoldMarkup;
