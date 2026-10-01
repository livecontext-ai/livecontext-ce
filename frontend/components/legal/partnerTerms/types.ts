/** A paragraph, or a list of items. Plain text: the documents are rendered, never interpolated. */
export type PartnerTermsBlock = string | { list: string[] };

export interface PartnerTermsClause {
  number: string;
  title: string;
  blocks: PartnerTermsBlock[];
}

/** One language version of the Partner Program Terms, with the labels of its page. */
export interface PartnerTermsDocument {
  lang: 'en' | 'fr';
  title: string;
  metaDescription: string;
  versionLabel: string;
  effectiveLabel: string;
  effectiveDate: string;
  /** The link to the other language version. */
  otherLanguageLabel: string;
  /** Which version prevails: shown under the title. */
  prevailingNote: string;
  contentsLabel: string;
  intro: string[];
  clauses: PartnerTermsClause[];
  /** Schedule 1: the Program economics, as label / value rows. */
  schedule: { title: string; intro: string; rows: [string, string][] };
}
