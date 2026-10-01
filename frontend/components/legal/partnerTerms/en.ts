import type { PartnerTermsDocument } from './types';

/**
 * The Partner Program Terms, English version (a translation for convenience: the French version
 * prevails, clause 22.7). Every figure must match the backend program defaults of the same
 * version (reward.partner.*), see Schedule 1.
 */
export const PARTNER_TERMS_EN: PartnerTermsDocument = {
  lang: 'en',
  title: 'LiveContext Partner Program Terms',
  metaDescription:
    'The contract between LiveContext and its partners: commissions, tiers, founding partners, payment, promotion rules, termination.',
  versionLabel: 'Version',
  effectiveLabel: 'In force from',
  effectiveDate: 'October 1, 2026',
  otherLanguageLabel: 'Version française (prévaut)',
  prevailingNote:
    'This English text is provided for convenience. The French version of these Terms prevails in case of discrepancy (clause 22.7).',
  contentsLabel: 'Contents',
  intro: [
    'These Partner Program Terms (the "Terms") form a contract between LIVECONTEXT, a French société par actions simplifiée with a share capital of 1,000 euros, registered with the Paris Trade and Companies Register under number 105 231 153, whose registered office is at 173 rue de Courcelles, 75017 Paris, France ("LiveContext", "we", "us"), and the individual or legal entity that applies to or takes part in the LiveContext Partner Program (the "Partner", "you").',
  ],
  clauses: [
    {
      number: '1',
      title: 'Definitions',
      blocks: [
        { list: [
          '"Program": the LiveContext partner program described in these Terms and on the Program page (https://livecontext.ai/partners).',
          '"Service": the cloud-hosted LiveContext platform operated by us under our Terms of Service.',
          '"Partner Code" and "Partner Link": the code and the tracked link we issue to you.',
          '"Referred Customer": a customer attributed to you under clause 5.',
          '"Net Revenue": the amounts we actually receive for the invoices of a Referred Customer\'s account, excluding VAT and other taxes, after deducting refunds, credit notes, disputed amounts and chargebacks, discounts, promotional credits and unpaid amounts.',
          '"Commission": your share of Net Revenue under clause 6.',
          '"Dashboard": your partner dashboard in the Service.',
          '"Tier": the Silver, Gold or Platinum level under clause 7.',
          '"Schedule 1": the Program economics at the end of these Terms, which form part of them.',
        ] },
      ],
    },
    {
      number: '2',
      title: 'Acceptance, evidence and documents',
      blocks: [
        '2.1 You accept these Terms by ticking the acceptance box when you apply, or by clicking the acceptance button in your Dashboard. You must accept them to apply. A partner whose Partner Code was created otherwise is asked to accept them in their Dashboard, and no Commission is paid to them before they do (clause 8.4).',
        '2.2 When you accept, we record the version accepted, the fingerprint of its text, the date and time, your account, and the IP address and browser used. You agree that this record, and the ticking of the acceptance box that it records, prove your acceptance of that version, as an evidence agreement under article 1368 of the French Civil Code. You may prove otherwise by any means.',
        '2.3 Your participation is governed by these Terms, Schedule 1, our Terms of Service (https://livecontext.ai/legal/terms) and our Privacy Policy (https://livecontext.ai/legal/privacy). For anything concerning the Program, these Terms prevail over the Terms of Service.',
        '2.4 Each version of these Terms is identified by its date, shown at the top of the page, and its text never changes once published: a new text is a new version. The current version is always published on this page. We keep every version, and send you on request a copy of any version you accepted. You should save or print a copy.',
      ],
    },
    {
      number: '3',
      title: 'Eligibility and approval',
      blocks: [
        '3.1 The Program is open to legal entities and to adult individuals who take part for the purposes of their trade, business or profession. By applying, you confirm that you act as a professional and not as a consumer.',
        '3.2 One Partner account per person or entity. You must not apply through several accounts, or on behalf of someone else without their authority.',
        '3.3 We review every application and may accept or refuse it at our sole discretion, without having to give reasons. An application is accepted only by our written confirmation: no silence or delay is an acceptance.',
        '3.4 At any time we may ask for, and verify, information about your identity, business, website, audience, tax status and bank account, including against sanctions lists. We may suspend payments until this information is provided and verified.',
        '3.5 You may not take part if you, or anyone who controls you, is subject to sanctions of the European Union, France, the United Nations, the United Kingdom or the United States, or is established in a country subject to comprehensive sanctions.',
      ],
    },
    {
      number: '4',
      title: 'Your role',
      blocks: [
        '4.1 You act as an independent business introducer. You promote the Service and refer prospects, who then contract directly with us under our Terms of Service.',
        '4.2 You have no authority to negotiate, conclude or amend any contract, to accept any order, to grant any price, discount or commitment, or to receive any payment on our behalf. You must not present yourself as our agent, employee, representative, reseller or distributor. Nothing in these Terms creates an agency (including a commercial agency), employment, partnership, joint venture, franchise or distribution relationship. The word "partner" only describes your membership of the Program.',
        '4.3 The Program is non-exclusive for both parties. We may run other partner, affiliate or referral programs, work with your competitors and sell directly to any prospect.',
        '4.4 You bear all your costs and act at your own risk. Reselling the Service, or subscribing to it on behalf of your clients, requires a separate written agreement with us.',
      ],
    },
    {
      number: '5',
      title: 'Referrals and attribution',
      blocks: [
        '5.1 A customer is attributed to you when your Partner Link or Partner Code is applied to a new LiveContext account within the attribution period of Schedule 1 (currently 30 days after the account is created), and the account meets clause 5.3. Once recorded, an attribution is final.',
        '5.2 One partner per customer: the first valid Partner Link or Partner Code applied to the account prevails, and an attribution is never transferred or shared. If you believe a customer should have been attributed to you, tell us in writing within 60 days after that customer\'s account was created, with the evidence you have (the customer\'s name or email address, the date, the link or code used). We decide on the basis of our records and your evidence, and our decision may be challenged within the time limit of clause 19.',
        '5.3 There is no attribution and no Commission for:',
        { list: [
          '(a) accounts created more than the attribution period before your Partner Link or Code is applied, accounts that already had a paid subscription at that time, and holders who already had another account;',
          '(b) your own accounts, those of your company or group, of your employees, agents or contractors, and accounts paid with your means of payment;',
          '(c) accounts created with false, disposable or automated identities;',
          '(d) customers obtained in breach of these Terms or of the law;',
          '(e) customers who ask not to be attributed to a partner, where we accept their request.',
        ] },
        '5.4 Your Partner Link is kept for the period of Schedule 1 (currently 30 days) in the browser of the person who clicked it, and is applied when they create or sign in to their account. Attribution may fail if that person uses another device or browser, blocks or clears the stored data, or applies another partner\'s link or code first. No Commission is due for a customer who was not attributed to you.',
        '5.5 The free credits given to new customers who sign up with your Partner Link or Code are a benefit we grant to the customer, of the amount shown on the Program page. We may change or stop it for future sign-ups. You must not offer any other benefit, discount or incentive in our name.',
      ],
    },
    {
      number: '6',
      title: 'Commissions',
      blocks: [
        '6.1 Rate. For each invoice of a Referred Customer paid while your participation is active, you earn a Commission equal to your rate applied to the Net Revenue of that invoice. Your rate is the rate of your Tier (Schedule 1) or, where we have agreed a specific rate for your Partner Code in writing, that rate if it is higher. The rate is the one in force when the invoice is paid, as shown in your Dashboard. Where we set specific conditions for your Partner Code (rate, commission period, holding period, validity or number of uses), they are shown in your Dashboard from the approval of your application. Conditions more favourable to you than Schedule 1 apply in any case; any other specific condition applies only if you keep taking part after it is shown to you, and you may refuse it by ending your participation under clause 16.2. Specific conditions applied this way are a specific agreement under clause 22.1.',
        '6.2 Duration. You earn Commissions on each Referred Customer for the period of Schedule 1 (currently 12 months), counted from that customer\'s first invoice that gave rise to a Commission, even if that Commission was later cancelled. Afterwards, that customer\'s invoices give rise to no Commission.',
        '6.3 Currency. A Commission is calculated in the currency of the invoice.',
        '6.4 Holding period. Each Commission is held for the period of Schedule 1 (currently 14 days) from the payment of the invoice, to cover refunds and disputes. It then becomes payable.',
        '6.5 Reversal, clawback and set-off. If an invoice that gave rise to a Commission is refunded, credited, disputed, charged back or cancelled, or turns out to result from fraud or from a breach of these Terms, the Commission is cancelled in whole or in proportion. A Commission whose invoice is disputed is cancelled when the dispute is opened, whatever its outcome. If it has already been paid, we may deduct it from any amount we owe you; otherwise you must repay it within 30 days of our written request. This right survives payment and the end of your participation.',
        '6.6 No Commission is due on: invoices paid after your participation ends or while your Partner Code is suspended or deactivated; amounts we do not actually receive; taxes; invoices of customers excluded by clause 5.3; and the free credits of clause 5.5.',
        '6.7 Commissions are your only remuneration under the Program. No other fee, reimbursement or indemnity is due to you, including when your participation ends, except where mandatory law provides otherwise.',
      ],
    },
    {
      number: '7',
      title: 'Partner tiers and founding partners',
      blocks: [
        '7.1 Tiers. You start at Silver when your application is approved. You move up to Gold, then Platinum, automatically once the settled Net Revenue of the invoices of your Referred Customers that gave rise to a Commission reaches the thresholds of Schedule 1. The Net Revenue of an invoice is settled once the invoice is older than the settlement period of Schedule 1 (currently 60 days) and its Commission has not been cancelled. Thresholds are counted in US dollars, the currency of our price list, on Net Revenue invoiced in that currency.',
        '7.2 A new Tier applies to invoices paid after it is reached, never retroactively.',
        '7.3 While your participation continues, we will not move you to a lower Tier because your volume falls. If your participation resumes after it ended, you keep the Tier you had reached.',
        '7.4 Founding partners. Until the date of Schedule 1 (1 January 2027), we may, at our sole discretion and without any obligation, designate founding partners among applicants. No one has a right to be designated, and applying does not make you a founding partner. A founding partner is placed on Platinum from the designation, without having to reach its threshold, and earns the rate of the highest standard Tier of the Program.',
        '7.5 Founding partner status is personal and lasts as long as your participation: this is what "for life" means on our pages. We may end it by written notice, and you then return to the Tier your settled Net Revenue reaches on the day it ends, if (a) no invoice of a Referred Customer gives rise to a Commission for 12 consecutive months, (b) control of the Partner changes or its business is transferred, (c) we end your participation under clause 16.3, or (d) your participation resumes after it ended. It ends in any case when the Program is closed under clause 17.4.',
        '7.6 Program-wide changes to the Tiers, rates or thresholds follow clause 17 and apply only to invoices paid after they take effect.',
      ],
    },
    {
      number: '8',
      title: 'Payment',
      blocks: [
        '8.1 We pay payable Commissions by bank transfer to an account in your name (or in your company\'s name), generally monthly and within 45 days after the end of the month in which they became payable, once your payable balance reaches the minimum of Schedule 1. A lower balance is carried forward.',
        '8.2 Before any payment, you must give us, through the channel we indicate: your legal identity (name or company name and registration number), address, tax status (business or individual, and VAT number where applicable), tax residence and bank details. We may ask for supporting documents and verify them.',
        '8.3 Bank charges, currency conversion charges and the costs of receiving the transfer are at your expense.',
        '8.4 No Commission is paid until you have accepted a version of these Terms. Commissions that became payable before your acceptance are paid once you have accepted a version of these Terms, subject to clause 8.5.',
        '8.5 If we cannot pay you because the information of clause 8.2 is missing, incomplete or wrong, or because the transfer fails, we will remind you by email at least twice. Amounts still unpaid for that reason 12 months after they became payable are forfeited.',
        '8.6 Commissions are calculated from our billing records, which we keep in good faith. You may contest a Commission line within 60 days after it appears in your Dashboard, by email with supporting evidence. We will review your request in good faith and correct any error.',
        '8.7 We may suspend payments during an investigation under clause 16.4, and set off any amount you owe us.',
      ],
    },
    {
      number: '9',
      title: 'Invoicing and taxes',
      blocks: [
        '9.1 You alone are responsible for declaring and paying the taxes and social contributions due on your Commissions.',
        '9.2 If you act as a business, you mandate us to issue the invoices for your Commissions in your name and on your behalf (self-billing). Each self-billed invoice is deemed accepted unless you contest it within 15 days of receiving it. You remain responsible for the VAT shown on these invoices, or for its absence, and must inform us at once of any change in your VAT status. You may instead issue your own invoices by telling us before the first payment; we then pay upon receipt of a compliant invoice.',
        '9.3 If you are established in another member state of the European Union and registered for VAT, invoices are issued without VAT under the reverse-charge mechanism. Where the law requires it, invoices go through the electronic invoicing system in force.',
        '9.4 If you are an individual not registered for VAT, no VAT is invoiced. We may be required to report the amounts paid to you to the tax authorities (for example in the French annual return of fees and commissions).',
        '9.5 We may withhold any tax the law requires us to withhold on your Commissions (for example under article 182 B of the French General Tax Code for partners established outside France), unless you provide in time the documents that allow a reduction or exemption under a tax treaty.',
      ],
    },
    {
      number: '10',
      title: 'Promotion rules',
      blocks: [
        '10.1 You promote the Service honestly, lawfully and in line with our brand guidelines. You may only describe features, prices and conditions as we publish them. You must not promise results, discounts, prices, features or support that we do not offer.',
        '10.2 Disclosure. In each piece of content, and before or next to each Partner Link, you must clearly disclose that you receive a commission when people sign up through your link, in words your audience understands (for example: "I earn a commission if you subscribe through this link"). A general mention on another page is not enough. In a video, say it at the start and show it on screen; in a live stream, repeat it. When you address the French public, the mention "Publicité" or "Collaboration commerciale" is mandatory under French law no. 2023-451 of 9 June 2023. You also follow the rules of each platform you publish on.',
        '10.3 You must not:',
        { list: [
          '(a) bid on "LiveContext" or any similar term, misspelling or variant as a keyword in paid search or social ads, or run ads that mention our brand, without our prior written consent;',
          '(b) register or use a domain name, account name, handle, app or product name that contains our trademarks or a confusingly similar term;',
          '(c) send unsolicited messages (spam), or contact people without the consent the law requires;',
          '(d) publish your Partner Link on coupon, cashback, reward, incentive or deal sites, or offer anything to people in exchange for signing up, other than the benefit of clause 5.5;',
          '(e) use cookie stuffing, forced clicks, hidden frames, pop-ups, automatic redirects, masked links, bots or any other method that creates a sign-up the person did not intend;',
          '(f) create or buy accounts, or refer yourself, directly or through someone else;',
          '(g) impersonate LiveContext or its staff, or suggest that we endorse your content or services beyond your membership of the Program;',
          '(h) promote the Service in or next to content that is unlawful, misleading, defamatory, hateful, violent or sexually explicit, or that targets minors;',
          '(i) post fake reviews or ratings, including on the LiveContext marketplace;',
          '(j) use sub-affiliates or third parties to promote your Partner Link without our written consent; if we consent, you are responsible for them as for yourself.',
        ] },
        '10.4 Influence activity. When you carry out a paid influence activity addressed to the French public, these Terms are the written contract required by article 8 of French law no. 2023-451. They set out the identity of the parties (the preamble, and the account and payment information you provide), the mission (promoting the Service, clause 4), the remuneration (clauses 6 and 7), the intellectual property terms (clause 11) and the application of French law (clause 22). If you are established outside the European Union and the European Economic Area, you must also designate a legal representative in the European Union and hold civil liability insurance in the European Union, as that law requires. You remain responsible for the content you publish.',
        '10.5 Audit. At our request, you must give us within 5 business days the information we reasonably need to check your compliance, such as the sources of your traffic, the content and campaigns you used and the consents you collected. Commissions resulting from traffic you cannot substantiate are cancelled.',
      ],
    },
    {
      number: '11',
      title: 'Trademarks, badge and licences',
      blocks: [
        '11.1 For the duration of your participation, we grant you a limited, non-exclusive, non-transferable, revocable and royalty-free licence to use the name "LiveContext", our logos and the materials we provide, solely to promote the Service under these Terms and our brand guidelines. You must not modify them, combine them with other signs, or register any trademark, domain name or account identical or similar to them. All other rights are reserved.',
        '11.2 While your participation is active, your profile and your marketplace listings show the partner badge, unless your profile is private. The badge means that you take part in the Program. It is not a certification, endorsement or guarantee by LiveContext of your content, apps or services, and you must not present it as one. We remove it when your participation ends or is suspended, and may remove it in case of breach.',
        '11.3 For the duration of your participation, and for a reasonable time afterwards to remove them, you grant us a non-exclusive, royalty-free, worldwide licence to use your name, trade name, logo and the public information of your profile to present you as a partner, including on the Program page, in the marketplace and in our communications. You warrant that you hold the rights needed to do so.',
        '11.4 On request, you must immediately stop any use of our trademarks that we consider contrary to these Terms or harmful to our image.',
      ],
    },
    {
      number: '12',
      title: 'Your services to your clients',
      blocks: [
        '12.1 The services you provide to your clients (advice, implementation, integration, training, hosting of the self-hosted edition, support) are provided by you, in your own name, under your own contract with each client. You are solely responsible for them, including their quality, compliance and security and the data you process. We are not a party to those contracts and have no obligation to intervene.',
        '12.2 Your clients who use the cloud Service contract directly with us and accept our Terms of Service. You have no access to their account unless they grant it to you themselves, and you must respect the limits of the access they give you.',
        '12.3 Installing and hosting the self-hosted edition for your clients is governed by the licence of that edition. Operating LiveContext as your own multi-client platform requires a commercial licence from us.',
      ],
    },
    {
      number: '13',
      title: 'Personal data',
      blocks: [
        '13.1 Each party processes personal data as an independent controller and complies with the applicable data protection law, including the GDPR.',
        '13.2 We do not share the identity or personal data of Referred Customers with you. Your Dashboard shows amounts and statuses only.',
        '13.3 You are solely responsible for the personal data you collect for your promotion (for example the contact details of prospects), including the information and consent the law requires.',
        '13.4 Our processing of your data (application, account, acceptance records, payment and tax information) is described in our Privacy Policy. Acceptance records are kept for the duration of your participation and then for the limitation period; accounting and tax data are kept for the period the law requires.',
      ],
    },
    {
      number: '14',
      title: 'Confidentiality',
      blocks: [
        'The non-public information we share with you in the Program (in particular specific rates, the content of your Dashboard, product plans and communications marked as confidential) is confidential. You may use it only for the Program and must not disclose it, during your participation and for 2 years afterwards, unless the law requires it.',
      ],
    },
    {
      number: '15',
      title: 'Compliance',
      blocks: [
        'You comply with all the laws that apply to your activity, including consumer protection, advertising, e-commerce, anti-spam, anti-corruption (such as French law no. 2016-1691 "Sapin II", the US Foreign Corrupt Practices Act and the UK Bribery Act), sanctions and export control laws. You must not offer or accept any undue advantage in connection with the Program.',
      ],
    },
    {
      number: '16',
      title: 'Duration, suspension and termination',
      blocks: [
        '16.1 These Terms apply for an indefinite period from your acceptance.',
        '16.2 You may end your participation at any time by email to contact@livecontext.ai. We may end it by written notice of at least 30 days, extended where the law requires a longer notice given the length of our business relationship.',
        '16.3 We may suspend or end your participation immediately, by written notice stating the reasons, if:',
        { list: [
          '(a) you breach clause 10, 11 or 15, or commit or attempt fraud;',
          '(b) you breach another obligation and do not remedy it within 15 days of our notice;',
          '(c) you gave false information in your application or under clause 8.2;',
          '(d) your conduct seriously harms our reputation or our customers;',
          '(e) the law, a court or an authority requires it;',
          '(f) you are subject to insolvency proceedings, where the law allows it.',
        ] },
        '16.4 During an investigation of a suspected breach or fraud, we may suspend your Partner Code and your payments for up to 60 days, after informing you.',
        '16.5 If no Referred Customer signs up through your Partner Link for 12 consecutive months, we may end your participation with 30 days\' notice.',
        '16.6 When your participation ends: your Partner Code and Link are deactivated; the badge and the licences of clause 11 end; you stop presenting yourself as a partner; and the Commissions on invoices paid before the end are paid when they become payable, under clauses 6 and 8. However, if we end your participation under clause 16.3(a) or (c), the Commissions resulting from the fraud, the breach or the false information are cancelled. No Commission is due on invoices paid after the end.',
        '16.7 Clauses 6.5, 8, 9, 10.4, 10.5, 11.4, 13, 14, 16.6, 16.8, 18, 19 and 22 survive the end of your participation.',
        '16.8 Deleting your LiveContext account ends your participation on the day the account is erased, at the end of the grace period that follows your deletion request. Commissions already payable on that day are paid under clause 8, provided we have the information of clause 8.2; Commissions still within their holding period on that day are cancelled.',
      ],
    },
    {
      number: '17',
      title: 'Changes to the Terms and to the Program',
      blocks: [
        '17.1 We may change these Terms, Schedule 1 or the Program. We will inform you by email at least 30 days before a change takes effect, and your Dashboard asks you to confirm the new version once it applies, except when it is required by law or by a security or fraud risk, in which case it may take effect sooner.',
        '17.2 Changes apply only to the future. They never affect Commissions on invoices paid before they take effect.',
        '17.3 If you disagree with a change, you may end your participation at no cost before it takes effect. If you keep taking part after it takes effect, you are deemed to accept it, and we may also ask you to confirm your acceptance in your Dashboard.',
        '17.4 We may close the Program with 90 days\' notice. Clause 16.6 then applies.',
      ],
    },
    {
      number: '18',
      title: 'Warranties and liability',
      blocks: [
        '18.1 The Program, the tracking, the Dashboard and the materials are provided "as is". We do not guarantee any volume of sign-ups, revenue or Commissions, or that tracking will be uninterrupted or free of errors. On evidence of a tracking error, we will make reasonable efforts to correct it.',
        '18.2 Neither party is liable for indirect damage, such as loss of profit, turnover, opportunity, data or image.',
        '18.3 Our total liability under these Terms is limited to the Commissions paid or payable to you in the 12 months before the event giving rise to the claim.',
        '18.4 These limits do not apply to our obligation to pay the Commissions due under these Terms, to fraud, to gross or wilful misconduct, to personal injury, or where the law prohibits them.',
        '18.5 You will indemnify us and hold us harmless against any claim, sanction, fine or cost (including reasonable legal fees) resulting from your breach of these Terms or of the law, from the content you publish, or from your services to or relations with your clients, including any joint liability the law places on us for your influence activity.',
      ],
    },
    {
      number: '19',
      title: 'Time limit for claims',
      blocks: [
        'Any claim by either party relating to a Commission must be brought within 1 year from the day that party knew or should have known the facts on which it is based (article 2254 of the French Civil Code).',
      ],
    },
    {
      number: '20',
      title: 'Assignment',
      blocks: [
        'You may not assign or transfer these Terms, your Partner Code or your Commissions without our prior written consent. We may assign these Terms to a company of our group or to the acquirer of our business, after informing you.',
      ],
    },
    {
      number: '21',
      title: 'Force majeure',
      blocks: [
        'Neither party is liable for a failure caused by force majeure within the meaning of article 1218 of the French Civil Code.',
      ],
    },
    {
      number: '22',
      title: 'General provisions',
      blocks: [
        '22.1 Entire agreement. These Terms, Schedule 1 and the documents of clause 2.3 are the entire agreement on the Program and replace any prior discussion or arrangement about it. A specific written agreement between the parties, including the specific conditions of clause 6.1, prevails on the points it covers.',
        '22.2 Severability. If a clause is held invalid, the other clauses remain in force, and the invalid clause is replaced by a valid one as close as possible to its purpose.',
        '22.3 No waiver. Not enforcing a clause does not waive it.',
        '22.4 Notices. We send notices to the email address of your account and in your Dashboard. You send yours to contact@livecontext.ai or by post to our registered office.',
        '22.5 Governing law. These Terms are governed by French law, excluding the United Nations Convention on Contracts for the International Sale of Goods.',
        '22.6 Disputes. The parties will first try to settle any dispute amicably for 30 days from a written notice. Failing that, the competent courts of Paris, France, have exclusive jurisdiction, including in case of multiple defendants or third-party claims, subject to mandatory rules.',
        '22.7 Language. These Terms are written in French and in English. The French version prevails in case of discrepancy or difference of interpretation.',
      ],
    },
  ],
  schedule: {
    title: 'Schedule 1: Program economics',
    intro: 'These figures form part of the Terms and change only under clause 17.',
    rows: [
      ['Tier rates', 'Silver 30%, Gold 40%, Platinum 50%'],
      ['Tier thresholds (settled Net Revenue of the invoices giving rise to a Commission)', 'Gold: USD 5,000. Platinum: USD 25,000'],
      ['Attribution period', '30 days: a Partner Code or Link applies to accounts created less than 30 days earlier, and a Partner Link is kept 30 days in the browser'],
      ['Settlement period for Tier revenue', '60 days'],
      ['Commission period per Referred Customer', '12 months from the first invoice giving rise to a Commission'],
      ['Holding period per Commission', '14 days'],
      ['Minimum payout', 'USD 50, or its equivalent in the currency of payment'],
      ['Founding partners may be designated until', '1 January 2027 (00:00 UTC)'],
    ],
  },
};
