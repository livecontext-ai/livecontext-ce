import type { PartnerTermsBlock, PartnerTermsDocument } from './types';

/**
 * French typography: a non-breaking space before : ; ? ! and %, and inside « », and a narrow
 * non-breaking space in thousands. The text below is written with ordinary spaces and set here,
 * once, so it stays readable in the source. The replacement strings hold the invisible
 * characters themselves (U+00A0 and U+202F).
 */
function frenchSpaces(text: string): string {
  return text
    .replace(/ ([:;?!%])/g, ' $1')
    .replace(/« /g, '« ')
    .replace(/ »/g, ' »')
    // A time (00 h 00): never split across two lines.
    .replace(/(\d) h (\d)/g, '$1 h $2')
    // Thousands (1 000, 5 000 USD): never split across two lines.
    .replace(/(\d) (?=\d{3}\b)/g, '$1 ');
}

function typeset(doc: PartnerTermsDocument): PartnerTermsDocument {
  const block = (b: PartnerTermsBlock): PartnerTermsBlock =>
    typeof b === 'string' ? frenchSpaces(b) : { list: b.list.map(frenchSpaces) };
  return {
    ...doc,
    title: frenchSpaces(doc.title),
    metaDescription: frenchSpaces(doc.metaDescription),
    prevailingNote: frenchSpaces(doc.prevailingNote),
    intro: doc.intro.map(frenchSpaces),
    clauses: doc.clauses.map((c) => ({ ...c, title: frenchSpaces(c.title), blocks: c.blocks.map(block) })),
    schedule: {
      title: frenchSpaces(doc.schedule.title),
      intro: frenchSpaces(doc.schedule.intro),
      rows: doc.schedule.rows.map(([k, v]) => [frenchSpaces(k), frenchSpaces(v)]),
    },
  };
}

/**
 * Les conditions du programme partenaires, version française : c'est elle qui prévaut
 * (article 22.7). Chaque chiffre doit correspondre aux paramètres du programme côté backend
 * pour la même version (reward.partner.*), voir l'annexe 1.
 */
export const PARTNER_TERMS_FR: PartnerTermsDocument = typeset({
  lang: 'fr',
  title: 'Conditions du programme partenaires LiveContext',
  metaDescription:
    'Le contrat entre LiveContext et ses partenaires : commissions, paliers, partenaires fondateurs, paiement, règles de promotion, résiliation.',
  versionLabel: 'Version',
  effectiveLabel: 'En vigueur à compter du',
  effectiveDate: '1er octobre 2026',
  otherLanguageLabel: 'English version',
  prevailingNote:
    'Seule la version française des présentes Conditions fait foi en cas de divergence (article 22.7). La version anglaise est fournie à titre de commodité.',
  contentsLabel: 'Sommaire',
  intro: [
    'Les présentes conditions du programme partenaires (les « Conditions ») forment un contrat entre LIVECONTEXT, société par actions simplifiée au capital de 1 000 euros, immatriculée au registre du commerce et des sociétés de Paris sous le numéro 105 231 153, dont le siège social est situé 173 rue de Courcelles, 75017 Paris (« LiveContext », « nous »), et la personne physique ou morale qui demande à participer ou participe au programme partenaires LiveContext (le « Partenaire », « vous »).',
  ],
  clauses: [
    {
      number: '1',
      title: 'Définitions',
      blocks: [
        { list: [
          '« Programme » : le programme partenaires LiveContext décrit dans les présentes Conditions et sur la page du Programme (https://livecontext.ai/partners).',
          '« Service » : la plateforme LiveContext hébergée que nous exploitons selon nos conditions générales d’utilisation.',
          '« Code Partenaire » et « Lien Partenaire » : le code et le lien de suivi que nous vous attribuons.',
          '« Client Apporté » : un client qui vous est attribué selon l’article 5.',
          '« Chiffre d’Affaires Net » : les sommes que nous encaissons effectivement au titre des factures du compte d’un Client Apporté, hors TVA et autres taxes, déduction faite des remboursements, avoirs, sommes contestées et rétrofacturations (chargebacks), remises, crédits promotionnels et sommes impayées.',
          '« Commission » : votre part du Chiffre d’Affaires Net selon l’article 6.',
          '« Tableau de bord » : votre tableau de bord partenaire dans le Service.',
          '« Palier » : le niveau Silver, Gold ou Platinum selon l’article 7.',
          '« Annexe 1 » : les paramètres économiques du Programme figurant à la fin des présentes Conditions, dont elle fait partie intégrante.',
        ] },
      ],
    },
    {
      number: '2',
      title: 'Acceptation, preuve et documents contractuels',
      blocks: [
        '2.1 Vous acceptez les présentes Conditions en cochant la case d’acceptation lors de votre candidature, ou en cliquant sur le bouton d’acceptation de votre Tableau de bord. Vous devez les accepter pour candidater. Un partenaire dont le Code Partenaire a été créé autrement est invité à les accepter dans son Tableau de bord, et aucune Commission ne lui est payée avant qu’il ne l’ait fait (article 8.4).',
        '2.2 Lors de votre acceptation, nous enregistrons la version acceptée, l’empreinte de son texte, la date et l’heure, votre compte, ainsi que l’adresse IP et le navigateur utilisés. Vous convenez que cet enregistrement, et le cochage de la case d’acceptation qu’il constate, font preuve de votre acceptation de cette version, au titre d’une convention de preuve conclue selon l’article 1368 du Code civil. La preuve contraire peut être rapportée par tout moyen.',
        '2.3 Votre participation est régie par les présentes Conditions, l’Annexe 1, nos conditions générales d’utilisation (https://livecontext.ai/legal/terms) et notre politique de confidentialité (https://livecontext.ai/legal/privacy). Pour tout ce qui concerne le Programme, les présentes Conditions prévalent sur les conditions générales d’utilisation.',
        '2.4 Chaque version des présentes Conditions est identifiée par sa date, indiquée en haut de la page, et son texte ne change plus une fois publié : un nouveau texte est une nouvelle version. La version en vigueur est toujours publiée sur cette page. Nous conservons chaque version, et vous adressons sur demande une copie de toute version que vous avez acceptée. Nous vous invitons à en conserver ou en imprimer une copie.',
      ],
    },
    {
      number: '3',
      title: 'Conditions d’accès et admission',
      blocks: [
        '3.1 Le Programme est ouvert aux personnes morales et aux personnes physiques majeures qui y participent dans le cadre de leur activité commerciale, industrielle, artisanale, libérale ou agricole. En présentant votre candidature, vous confirmez agir en qualité de professionnel et non de consommateur.',
        '3.2 Un seul compte Partenaire par personne ou entité. Vous ne devez pas candidater au moyen de plusieurs comptes, ni pour le compte d’un tiers sans son autorisation.',
        '3.3 Nous examinons chaque candidature et pouvons l’accepter ou la refuser à notre seule discrétion, sans avoir à motiver notre décision. Une candidature n’est acceptée que par notre confirmation écrite : aucun silence ni délai ne vaut acceptation.',
        '3.4 Nous pouvons à tout moment vous demander, et vérifier, des informations sur votre identité, votre activité, votre site, votre audience, votre statut fiscal et votre compte bancaire, y compris au regard des listes de sanctions. Nous pouvons suspendre les paiements jusqu’à ce que ces informations soient fournies et vérifiées.',
        '3.5 Vous ne pouvez pas participer si vous-même, ou toute personne qui vous contrôle, faites l’objet de sanctions de l’Union européenne, de la France, des Nations unies, du Royaume-Uni ou des États-Unis, ou êtes établi dans un pays soumis à des sanctions générales.',
      ],
    },
    {
      number: '4',
      title: 'Votre rôle',
      blocks: [
        '4.1 Vous agissez en qualité d’apporteur d’affaires indépendant. Vous faites la promotion du Service et nous présentez des prospects, qui contractent ensuite directement avec nous selon nos conditions générales d’utilisation.',
        '4.2 Vous n’avez aucun pouvoir pour négocier, conclure ou modifier un contrat, accepter une commande, consentir un prix, une remise ou un engagement, ni encaisser un paiement en notre nom. Vous ne devez pas vous présenter comme notre mandataire, salarié, représentant, revendeur ou distributeur. Rien dans les présentes Conditions ne crée de mandat (notamment d’agent commercial), de contrat de travail, de société, de coentreprise, de franchise ou de distribution. Le mot « partenaire » désigne uniquement votre participation au Programme.',
        '4.3 Le Programme n’est exclusif pour aucune des parties. Nous pouvons mener d’autres programmes de partenariat, d’affiliation ou de parrainage, travailler avec vos concurrents et vendre directement à tout prospect.',
        '4.4 Vous supportez l’ensemble de vos frais et agissez à vos risques. La revente du Service, ou sa souscription pour le compte de vos clients, nécessite un accord écrit distinct avec nous.',
      ],
    },
    {
      number: '5',
      title: 'Apport de clients et attribution',
      blocks: [
        '5.1 Un client vous est attribué lorsque votre Lien Partenaire ou votre Code Partenaire est appliqué à un nouveau compte LiveContext pendant la période d’attribution fixée à l’Annexe 1 (actuellement 30 jours après la création du compte), et que ce compte respecte l’article 5.3. Une fois enregistrée, l’attribution est définitive.',
        '5.2 Un seul partenaire par client : le premier Lien Partenaire ou Code Partenaire valide appliqué au compte l’emporte, et une attribution n’est jamais transférée ni partagée. Si vous estimez qu’un client aurait dû vous être attribué, signalez-le-nous par écrit dans les 60 jours suivant la création du compte de ce client, avec les éléments dont vous disposez (nom ou adresse e-mail du client, date, lien ou code utilisé). Nous décidons sur la base de nos enregistrements et de vos éléments, et notre décision peut être contestée dans le délai de l’article 19.',
        '5.3 Ne donnent lieu à aucune attribution ni aucune Commission :',
        { list: [
          '(a) les comptes créés plus de la période d’attribution avant l’application de votre Lien ou Code Partenaire, les comptes qui disposaient déjà d’un abonnement payant à ce moment, et les titulaires qui avaient déjà un autre compte ;',
          '(b) vos propres comptes, ceux de votre société ou de votre groupe, de vos salariés, mandataires ou prestataires, ainsi que les comptes réglés avec vos moyens de paiement ;',
          '(c) les comptes créés avec des identités fausses, jetables ou automatisées ;',
          '(d) les clients obtenus en violation des présentes Conditions ou de la loi ;',
          '(e) les clients qui demandent à ne pas être attribués à un partenaire, lorsque nous acceptons leur demande.',
        ] },
        '5.4 Votre Lien Partenaire est conservé pendant la durée fixée à l’Annexe 1 (actuellement 30 jours) dans le navigateur de la personne qui l’a suivi, et appliqué lorsqu’elle crée son compte ou s’y connecte. L’attribution peut échouer si cette personne utilise un autre appareil ou navigateur, bloque ou efface les données enregistrées, ou applique d’abord le lien ou le code d’un autre partenaire. Aucune Commission n’est due pour un client qui ne vous a pas été attribué.',
        '5.5 Les crédits gratuits accordés aux nouveaux clients qui s’inscrivent avec votre Lien ou votre Code Partenaire sont un avantage que nous consentons au client, du montant indiqué sur la page du Programme. Nous pouvons le modifier ou le supprimer pour les inscriptions futures. Vous ne devez proposer aucun autre avantage, remise ou incitation en notre nom.',
      ],
    },
    {
      number: '6',
      title: 'Commissions',
      blocks: [
        '6.1 Taux. Pour chaque facture d’un Client Apporté payée pendant que votre participation est active, vous percevez une Commission égale à votre taux appliqué au Chiffre d’Affaires Net de cette facture. Votre taux est celui de votre Palier (Annexe 1) ou, si nous avons convenu par écrit d’un taux particulier pour votre Code Partenaire, ce taux s’il est plus élevé. Le taux applicable est celui en vigueur au paiement de la facture, tel qu’affiché dans votre Tableau de bord. Lorsque nous fixons des conditions particulières pour votre Code Partenaire (taux, durée de commissionnement, période de retenue, validité ou nombre d’utilisations), elles sont affichées dans votre Tableau de bord dès l’acceptation de votre candidature. Les conditions plus favorables pour vous que l’Annexe 1 s’appliquent en tout état de cause ; toute autre condition particulière ne s’applique que si vous poursuivez votre participation après qu’elle vous a été affichée, et vous pouvez la refuser en mettant fin à votre participation selon l’article 16.2. Les conditions particulières ainsi applicables constituent un accord particulier au sens de l’article 22.1.',
        '6.2 Durée. Vous percevez des Commissions sur chaque Client Apporté pendant la durée fixée à l’Annexe 1 (actuellement 12 mois), à compter de la première facture de ce client ayant donné lieu à Commission, même si cette Commission a ensuite été annulée. Au-delà, les factures de ce client ne donnent plus lieu à Commission.',
        '6.3 Devise. Une Commission est calculée dans la devise de la facture.',
        '6.4 Période de retenue. Chaque Commission est retenue pendant la période fixée à l’Annexe 1 (actuellement 14 jours) à compter du paiement de la facture, afin de couvrir les remboursements et contestations. Elle devient ensuite exigible.',
        '6.5 Annulation, reprise et compensation. Si une facture ayant donné lieu à une Commission est remboursée, fait l’objet d’un avoir, d’une contestation ou d’une rétrofacturation, est annulée, ou se révèle résulter d’une fraude ou d’un manquement aux présentes Conditions, la Commission est annulée en tout ou en proportion. Une Commission dont la facture est contestée est annulée dès l’ouverture de la contestation, quelle qu’en soit l’issue. Si elle a déjà été payée, nous pouvons la compenser avec toute somme que nous vous devons ; à défaut, vous devez la rembourser dans les 30 jours de notre demande écrite. Ce droit survit au paiement et à la fin de votre participation.',
        '6.6 Aucune Commission n’est due sur : les factures payées après la fin de votre participation ou pendant la suspension ou la désactivation de votre Code Partenaire ; les sommes que nous n’encaissons pas effectivement ; les taxes ; les factures des clients exclus par l’article 5.3 ; les crédits gratuits de l’article 5.5.',
        '6.7 Les Commissions constituent votre seule rémunération au titre du Programme. Aucune autre rémunération, aucun remboursement ni aucune indemnité ne vous est dû, y compris à la fin de votre participation, sauf disposition légale impérative contraire.',
      ],
    },
    {
      number: '7',
      title: 'Paliers partenaires et partenaires fondateurs',
      blocks: [
        '7.1 Paliers. Vous commencez au Palier Silver à l’acceptation de votre candidature. Vous passez automatiquement au Palier Gold, puis Platinum, lorsque le Chiffre d’Affaires Net consolidé des factures de vos Clients Apportés ayant donné lieu à Commission atteint les seuils fixés à l’Annexe 1. Le Chiffre d’Affaires Net d’une facture est consolidé lorsque la facture est plus ancienne que la période de consolidation fixée à l’Annexe 1 (actuellement 60 jours) et que sa Commission n’a pas été annulée. Les seuils sont calculés en dollars américains, devise de notre grille tarifaire, sur le Chiffre d’Affaires Net facturé dans cette devise.',
        '7.2 Un nouveau Palier s’applique aux factures payées après qu’il a été atteint, jamais rétroactivement.',
        '7.3 Tant que votre participation se poursuit, nous ne vous faisons pas descendre de Palier en raison d’une baisse de votre volume. Si votre participation reprend après avoir pris fin, vous conservez le Palier que vous aviez atteint.',
        '7.4 Partenaires fondateurs. Jusqu’à la date fixée à l’Annexe 1 (1er janvier 2027), nous pouvons, à notre seule discrétion et sans aucune obligation, désigner des partenaires fondateurs parmi les candidats. Nul n’a droit à être désigné, et candidater ne fait pas de vous un partenaire fondateur. Un partenaire fondateur est placé au Palier Platinum dès sa désignation, sans avoir à en atteindre le seuil, et perçoit le taux du Palier standard le plus élevé du Programme.',
        '7.5 Le statut de partenaire fondateur est personnel et dure autant que votre participation : c’est le sens de la mention « à vie » sur nos pages. Nous pouvons y mettre fin par notification écrite, et vous revenez alors au Palier qu’atteint votre Chiffre d’Affaires Net consolidé le jour où il prend fin, si (a) aucune facture d’un Client Apporté ne donne lieu à Commission pendant 12 mois consécutifs, (b) le contrôle du Partenaire change ou son activité est cédée, (c) nous mettons fin à votre participation selon l’article 16.3, ou (d) votre participation reprend après avoir pris fin. Il prend fin en tout état de cause à la fermeture du Programme selon l’article 17.4.',
        '7.6 Les modifications générales des Paliers, des taux ou des seuils suivent l’article 17 et ne s’appliquent qu’aux factures payées après leur entrée en vigueur.',
      ],
    },
    {
      number: '8',
      title: 'Paiement',
      blocks: [
        '8.1 Nous payons les Commissions exigibles par virement sur un compte à votre nom (ou au nom de votre société), en principe chaque mois et dans les 45 jours suivant la fin du mois au cours duquel elles sont devenues exigibles, dès que votre solde exigible atteint le minimum fixé à l’Annexe 1. Un solde inférieur est reporté.',
        '8.2 Avant tout paiement, vous devez nous communiquer, par le moyen que nous indiquons : votre identité juridique (nom ou dénomination sociale et numéro d’immatriculation), votre adresse, votre statut fiscal (entreprise ou particulier, et numéro de TVA le cas échéant), votre résidence fiscale et vos coordonnées bancaires. Nous pouvons demander des justificatifs et les vérifier.',
        '8.3 Les frais bancaires, frais de conversion de devise et frais de réception du virement sont à votre charge.',
        '8.4 Aucune Commission n’est payée tant que vous n’avez pas accepté une version des présentes Conditions. Les Commissions devenues exigibles avant votre acceptation sont payées une fois celle-ci intervenue, sous réserve de l’article 8.5.',
        '8.5 Si nous ne pouvons pas vous payer parce que les informations de l’article 8.2 sont manquantes, incomplètes ou erronées, ou parce que le virement échoue, nous vous relançons au moins deux fois par courriel. Les sommes restées impayées pour cette raison 12 mois après être devenues exigibles sont définitivement perdues.',
        '8.6 Les Commissions sont calculées à partir de nos données de facturation, tenues de bonne foi. Vous pouvez contester une ligne de Commission dans les 60 jours suivant son apparition dans votre Tableau de bord, par courriel accompagné de justificatifs. Nous examinons votre demande de bonne foi et corrigeons toute erreur.',
        '8.7 Nous pouvons suspendre les paiements pendant une vérification menée selon l’article 16.4 et compenser toute somme que vous nous devez.',
      ],
    },
    {
      number: '9',
      title: 'Facturation et fiscalité',
      blocks: [
        '9.1 Vous êtes seul responsable de la déclaration et du paiement des impôts, taxes et cotisations sociales dus sur vos Commissions.',
        '9.2 Si vous agissez en qualité d’entreprise, vous nous donnez mandat d’émettre en votre nom et pour votre compte les factures correspondant à vos Commissions (autofacturation). Chaque facture ainsi émise est réputée acceptée si vous ne la contestez pas dans les 15 jours de sa réception. Vous restez responsable de la TVA mentionnée sur ces factures, ou de son absence, et devez nous informer sans délai de tout changement de votre situation au regard de la TVA. Vous pouvez à la place émettre vos propres factures en nous en informant avant le premier paiement ; nous payons alors à réception d’une facture conforme.',
        '9.3 Si vous êtes établi dans un autre État membre de l’Union européenne et identifié à la TVA, les factures sont émises hors TVA selon le mécanisme d’autoliquidation. Lorsque la loi l’exige, les factures sont émises par le dispositif de facturation électronique en vigueur.',
        '9.4 Si vous êtes un particulier non assujetti à la TVA, aucune TVA n’est facturée. Nous pouvons être tenus de déclarer à l’administration fiscale les sommes qui vous sont versées (par exemple dans la déclaration annuelle des honoraires et commissions).',
        '9.5 Nous pouvons opérer toute retenue à la source que la loi nous impose sur vos Commissions (par exemple au titre de l’article 182 B du Code général des impôts pour les partenaires établis hors de France), sauf si vous nous transmettez en temps utile les documents permettant une réduction ou une exonération en application d’une convention fiscale.',
      ],
    },
    {
      number: '10',
      title: 'Règles de promotion',
      blocks: [
        '10.1 Vous faites la promotion du Service de manière loyale, licite et conforme à notre charte de marque. Vous ne pouvez décrire les fonctionnalités, prix et conditions que tels que nous les publions. Vous ne devez promettre aucun résultat, remise, prix, fonctionnalité ou support que nous ne proposons pas.',
        '10.2 Transparence. Dans chaque contenu, et avant ou à côté de chaque Lien Partenaire, vous devez indiquer clairement que vous percevez une commission lorsque des personnes s’inscrivent par votre lien, en des termes compréhensibles par votre audience (par exemple : « Je perçois une commission si vous vous abonnez par ce lien »). Une mention générale sur une autre page ne suffit pas. Dans une vidéo, dites-le au début et affichez-le à l’écran ; dans un direct, répétez-le. Lorsque vous vous adressez au public français, la mention « Publicité » ou « Collaboration commerciale » est obligatoire en application de la loi n° 2023-451 du 9 juin 2023. Vous respectez également les règles de chaque plateforme sur laquelle vous publiez.',
        '10.3 Il vous est interdit :',
        { list: [
          '(a) d’acheter comme mot-clé, dans la publicité en ligne sur les moteurs de recherche ou les réseaux sociaux, le terme « LiveContext » ou tout terme similaire, faute de frappe ou variante, ou de diffuser des annonces mentionnant notre marque, sans notre accord écrit préalable ;',
          '(b) d’enregistrer ou d’utiliser un nom de domaine, un nom de compte, un identifiant, un nom d’application ou de produit contenant nos marques ou un terme prêtant à confusion ;',
          '(c) d’envoyer des messages non sollicités (spam), ou de contacter des personnes sans le consentement exigé par la loi ;',
          '(d) de publier votre Lien Partenaire sur des sites de coupons, de cashback, de récompenses, d’incitation ou de bons plans, ou d’offrir quoi que ce soit en échange d’une inscription, en dehors de l’avantage prévu à l’article 5.5 ;',
          '(e) d’utiliser le « cookie stuffing », les clics forcés, les cadres cachés, les fenêtres surgissantes, les redirections automatiques, les liens masqués, des robots ou toute autre méthode produisant une inscription non voulue par la personne ;',
          '(f) de créer ou d’acheter des comptes, ou de vous apporter vous-même un client, directement ou par personne interposée ;',
          '(g) de vous faire passer pour LiveContext ou son personnel, ou de laisser entendre que nous cautionnons vos contenus ou services au-delà de votre participation au Programme ;',
          '(h) de promouvoir le Service dans ou à proximité de contenus illicites, trompeurs, diffamatoires, haineux, violents ou sexuellement explicites, ou visant des mineurs ;',
          '(i) de publier de faux avis ou de fausses notes, y compris sur la marketplace LiveContext ;',
          '(j) de recourir à des sous-affiliés ou à des tiers pour promouvoir votre Lien Partenaire sans notre accord écrit ; en cas d’accord, vous en répondez comme de vous-même.',
        ] },
        '10.4 Activité d’influence. Lorsque vous exercez une activité d’influence commerciale rémunérée à destination du public français, les présentes Conditions constituent le contrat écrit exigé par l’article 8 de la loi n° 2023-451. Elles précisent l’identité des parties (le préambule, et les informations de compte et de paiement que vous fournissez), la mission (la promotion du Service, article 4), la rémunération (articles 6 et 7), les conditions relatives à la propriété intellectuelle (article 11) et la soumission au droit français (article 22). Si vous êtes établi hors de l’Union européenne et de l’Espace économique européen, vous devez en outre désigner un représentant légal dans l’Union européenne et souscrire une assurance de responsabilité civile dans l’Union européenne, comme l’exige cette loi. Vous restez responsable des contenus que vous publiez.',
        '10.5 Contrôle. À notre demande, vous devez nous communiquer dans un délai de 5 jours ouvrés les informations raisonnablement nécessaires pour vérifier votre conformité, telles que les sources de votre trafic, les contenus et campagnes utilisés et les consentements recueillis. Les Commissions issues d’un trafic dont vous ne pouvez pas justifier l’origine sont annulées.',
      ],
    },
    {
      number: '11',
      title: 'Marques, badge et licences',
      blocks: [
        '11.1 Pour la durée de votre participation, nous vous concédons une licence limitée, non exclusive, non transférable, révocable et gratuite d’utilisation du nom « LiveContext », de nos logos et des supports que nous fournissons, dans le seul but de promouvoir le Service conformément aux présentes Conditions et à notre charte de marque. Vous ne devez pas les modifier, les associer à d’autres signes, ni déposer une marque, un nom de domaine ou un compte identique ou similaire. Tous les autres droits sont réservés.',
        '11.2 Tant que votre participation est active, votre profil et vos fiches sur la marketplace affichent le badge partenaire, sauf si votre profil est privé. Le badge signifie que vous participez au Programme. Il ne constitue ni une certification, ni une recommandation, ni une garantie de LiveContext quant à vos contenus, applications ou services, et vous ne devez pas le présenter comme tel. Nous le retirons lorsque votre participation prend fin ou est suspendue, et pouvons le retirer en cas de manquement.',
        '11.3 Pour la durée de votre participation, et pendant un délai raisonnable ensuite pour les retirer, vous nous concédez une licence non exclusive, gratuite et mondiale d’utilisation de votre nom, de votre nom commercial, de votre logo et des informations publiques de votre profil afin de vous présenter comme partenaire, notamment sur la page du Programme, sur la marketplace et dans nos communications. Vous garantissez disposer des droits nécessaires.',
        '11.4 Sur demande, vous devez cesser immédiatement toute utilisation de nos marques que nous jugeons contraire aux présentes Conditions ou préjudiciable à notre image.',
      ],
    },
    {
      number: '12',
      title: 'Vos prestations auprès de vos clients',
      blocks: [
        '12.1 Les prestations que vous fournissez à vos clients (conseil, mise en œuvre, intégration, formation, hébergement de l’édition auto-hébergée, support) sont réalisées par vous, en votre nom, dans le cadre de votre propre contrat avec chaque client. Vous en êtes seul responsable, notamment de leur qualité, de leur conformité, de leur sécurité et des données que vous traitez. Nous ne sommes pas partie à ces contrats et n’avons aucune obligation d’intervenir.',
        '12.2 Vos clients qui utilisent le Service hébergé contractent directement avec nous et acceptent nos conditions générales d’utilisation. Vous n’avez aucun accès à leur compte sauf s’ils vous l’accordent eux-mêmes, et vous devez respecter les limites de l’accès qu’ils vous donnent.',
        '12.3 L’installation et l’hébergement de l’édition auto-hébergée pour vos clients sont régis par la licence de cette édition. L’exploitation de LiveContext comme votre propre plateforme multi-clients nécessite une licence commerciale de notre part.',
      ],
    },
    {
      number: '13',
      title: 'Données personnelles',
      blocks: [
        '13.1 Chaque partie traite des données personnelles en qualité de responsable de traitement indépendant et respecte la réglementation applicable en matière de protection des données, notamment le RGPD.',
        '13.2 Nous ne vous communiquons ni l’identité ni les données personnelles des Clients Apportés. Votre Tableau de bord n’affiche que des montants et des statuts.',
        '13.3 Vous êtes seul responsable des données personnelles que vous collectez pour votre promotion (par exemple les coordonnées de prospects), y compris de l’information et du consentement exigés par la loi.',
        '13.4 Nos traitements de vos données (candidature, compte, enregistrements d’acceptation, informations de paiement et fiscales) sont décrits dans notre politique de confidentialité. Les enregistrements d’acceptation sont conservés pendant la durée de votre participation puis pendant le délai de prescription ; les données comptables et fiscales pendant la durée imposée par la loi.',
      ],
    },
    {
      number: '14',
      title: 'Confidentialité',
      blocks: [
        'Les informations non publiques que nous vous communiquons dans le cadre du Programme (notamment les taux particuliers, le contenu de votre Tableau de bord, les projets de produit et les communications signalées comme confidentielles) sont confidentielles. Vous ne pouvez les utiliser que pour le Programme et ne devez pas les divulguer, pendant votre participation et 2 ans après, sauf obligation légale.',
      ],
    },
    {
      number: '15',
      title: 'Conformité',
      blocks: [
        'Vous respectez toutes les lois applicables à votre activité, notamment en matière de protection des consommateurs, de publicité, de commerce électronique, de prospection, de lutte contre la corruption (telles que la loi n° 2016-1691 dite « Sapin II », le Foreign Corrupt Practices Act américain et le UK Bribery Act), de sanctions et de contrôle des exportations. Vous ne devez offrir ni accepter aucun avantage indu en lien avec le Programme.',
      ],
    },
    {
      number: '16',
      title: 'Durée, suspension et résiliation',
      blocks: [
        '16.1 Les présentes Conditions s’appliquent pour une durée indéterminée à compter de votre acceptation.',
        '16.2 Vous pouvez mettre fin à votre participation à tout moment par courriel à contact@livecontext.ai. Nous pouvons y mettre fin par un préavis écrit d’au moins 30 jours, allongé lorsque la loi impose un préavis plus long compte tenu de la durée de notre relation commerciale.',
        '16.3 Nous pouvons suspendre votre participation ou y mettre fin immédiatement, par notification écrite motivée, si :',
        { list: [
          '(a) vous manquez aux articles 10, 11 ou 15, ou commettez ou tentez de commettre une fraude ;',
          '(b) vous manquez à une autre obligation et n’y remédiez pas dans les 15 jours de notre notification ;',
          '(c) vous avez fourni des informations inexactes dans votre candidature ou au titre de l’article 8.2 ;',
          '(d) votre comportement porte gravement atteinte à notre réputation ou à nos clients ;',
          '(e) la loi, une juridiction ou une autorité l’exige ;',
          '(f) vous faites l’objet d’une procédure collective, dans la mesure permise par la loi.',
        ] },
        '16.4 Pendant la vérification d’un manquement ou d’une fraude présumés, nous pouvons suspendre votre Code Partenaire et vos paiements pendant 60 jours au plus, après vous en avoir informé.',
        '16.5 Si aucun Client Apporté ne s’inscrit par votre Lien Partenaire pendant 12 mois consécutifs, nous pouvons mettre fin à votre participation moyennant un préavis de 30 jours.',
        '16.6 À la fin de votre participation : votre Code et votre Lien Partenaire sont désactivés ; le badge et les licences de l’article 11 prennent fin ; vous cessez de vous présenter comme partenaire ; et les Commissions sur les factures payées avant la fin sont payées lorsqu’elles deviennent exigibles, selon les articles 6 et 8. Toutefois, si nous mettons fin à votre participation au titre de l’article 16.3 (a) ou (c), les Commissions issues de la fraude, du manquement ou des informations inexactes sont annulées. Aucune Commission n’est due sur les factures payées après la fin.',
        '16.7 Les articles 6.5, 8, 9, 10.4, 10.5, 11.4, 13, 14, 16.6, 16.8, 18, 19 et 22 survivent à la fin de votre participation.',
        '16.8 La suppression de votre compte LiveContext met fin à votre participation le jour de l’effacement du compte, à l’issue du délai de grâce qui suit votre demande de suppression. Les Commissions déjà exigibles ce jour-là sont payées selon l’article 8, à condition que nous disposions des informations de l’article 8.2 ; les Commissions encore en période de retenue ce jour-là sont annulées.',
      ],
    },
    {
      number: '17',
      title: 'Modification des Conditions et du Programme',
      blocks: [
        '17.1 Nous pouvons modifier les présentes Conditions, l’Annexe 1 ou le Programme. Nous vous en informons par courriel au moins 30 jours avant l’entrée en vigueur de la modification, et votre Tableau de bord vous demande de confirmer la nouvelle version dès qu’elle s’applique, sauf lorsqu’elle est imposée par la loi ou par un risque de sécurité ou de fraude, auquel cas elle peut s’appliquer plus tôt.',
        '17.2 Les modifications ne valent que pour l’avenir. Elles n’affectent jamais les Commissions sur les factures payées avant leur entrée en vigueur.',
        '17.3 Si vous êtes en désaccord avec une modification, vous pouvez mettre fin à votre participation sans frais avant son entrée en vigueur. Si vous continuez à participer après son entrée en vigueur, vous êtes réputé l’avoir acceptée, et nous pouvons aussi vous demander de confirmer votre acceptation dans votre Tableau de bord.',
        '17.4 Nous pouvons fermer le Programme moyennant un préavis de 90 jours. L’article 16.6 s’applique alors.',
      ],
    },
    {
      number: '18',
      title: 'Garanties et responsabilité',
      blocks: [
        '18.1 Le Programme, le suivi, le Tableau de bord et les supports sont fournis « en l’état ». Nous ne garantissons aucun volume d’inscriptions, de chiffre d’affaires ou de Commissions, ni que le suivi sera ininterrompu ou exempt d’erreurs. Sur justification d’une erreur de suivi, nous mettons en œuvre des efforts raisonnables pour la corriger.',
        '18.2 Aucune partie n’est responsable des dommages indirects, tels que perte de bénéfice, de chiffre d’affaires, de chance, de données ou d’image.',
        '18.3 Notre responsabilité totale au titre des présentes Conditions est limitée au montant des Commissions payées ou dues au cours des 12 mois précédant le fait générateur de la réclamation.',
        '18.4 Ces limitations ne s’appliquent pas à notre obligation de payer les Commissions dues au titre des présentes Conditions, à la fraude, à la faute lourde ou intentionnelle, aux dommages corporels, ni lorsque la loi les interdit.',
        '18.5 Vous nous garantissez et nous tenez indemnes de toute réclamation, sanction, amende ou dépense (y compris des honoraires d’avocat raisonnables) résultant de votre manquement aux présentes Conditions ou à la loi, des contenus que vous publiez, ou de vos prestations auprès de vos clients ou de vos relations avec eux, y compris de toute responsabilité solidaire que la loi fait peser sur nous du fait de votre activité d’influence.',
      ],
    },
    {
      number: '19',
      title: 'Délai de réclamation',
      blocks: [
        'Toute action d’une partie relative à une Commission doit être engagée dans un délai d’un an à compter du jour où cette partie a connu ou aurait dû connaître les faits lui permettant de l’exercer (article 2254 du Code civil).',
      ],
    },
    {
      number: '20',
      title: 'Cession',
      blocks: [
        'Vous ne pouvez céder ni transférer les présentes Conditions, votre Code Partenaire ou vos Commissions sans notre accord écrit préalable. Nous pouvons céder les présentes Conditions à une société de notre groupe ou à l’acquéreur de notre activité, après vous en avoir informé.',
      ],
    },
    {
      number: '21',
      title: 'Force majeure',
      blocks: [
        'Aucune partie n’est responsable d’une inexécution causée par un cas de force majeure au sens de l’article 1218 du Code civil.',
      ],
    },
    {
      number: '22',
      title: 'Dispositions générales',
      blocks: [
        '22.1 Intégralité. Les présentes Conditions, l’Annexe 1 et les documents de l’article 2.3 constituent l’intégralité de l’accord relatif au Programme et remplacent tout échange ou arrangement antérieur à ce sujet. Un accord particulier écrit entre les parties, y compris les conditions particulières de l’article 6.1, prévaut sur les points qu’il traite.',
        '22.2 Divisibilité. Si une clause est déclarée nulle, les autres clauses restent en vigueur, et la clause nulle est remplacée par une clause valable aussi proche que possible de son objet.',
        '22.3 Absence de renonciation. Le fait de ne pas se prévaloir d’une clause ne vaut pas renonciation à celle-ci.',
        '22.4 Notifications. Nous vous adressons les notifications à l’adresse électronique de votre compte et dans votre Tableau de bord. Vous nous adressez les vôtres à contact@livecontext.ai ou par courrier à notre siège social.',
        '22.5 Droit applicable. Les présentes Conditions sont régies par le droit français, à l’exclusion de la Convention des Nations unies sur les contrats de vente internationale de marchandises.',
        '22.6 Litiges. Les parties s’efforcent d’abord de régler tout litige à l’amiable pendant 30 jours à compter d’une notification écrite. À défaut, les juridictions compétentes de Paris sont seules compétentes, y compris en cas de pluralité de défendeurs ou d’appel en garantie, sous réserve des règles impératives.',
        '22.7 Langue. Les présentes Conditions sont rédigées en français et en anglais. La version française prévaut en cas de divergence ou de différence d’interprétation.',
      ],
    },
  ],
  schedule: {
    title: 'Annexe 1 : paramètres économiques du Programme',
    intro: 'Ces paramètres font partie des Conditions et ne sont modifiés que selon l’article 17.',
    rows: [
      ['Taux par Palier', 'Silver 30 %, Gold 40 %, Platinum 50 %'],
      ['Seuils des Paliers (Chiffre d’Affaires Net consolidé des factures ayant donné lieu à Commission)', 'Gold : 5 000 USD. Platinum : 25 000 USD'],
      ['Période d’attribution', '30 jours : un Code ou Lien Partenaire s’applique aux comptes créés depuis moins de 30 jours, et un Lien Partenaire est conservé 30 jours dans le navigateur'],
      ['Période de consolidation du chiffre d’affaires des Paliers', '60 jours'],
      ['Durée de commissionnement par Client Apporté', '12 mois à compter de la première facture donnant lieu à Commission'],
      ['Période de retenue par Commission', '14 jours'],
      ['Montant minimal de paiement', '50 USD, ou son équivalent dans la devise de paiement'],
      ['Désignation des partenaires fondateurs possible jusqu’au', '1er janvier 2027 (00 h 00 UTC)'],
    ],
  },
});
