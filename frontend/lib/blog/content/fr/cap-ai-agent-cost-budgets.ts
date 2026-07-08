// cap-ai-agent-cost-budgets - fr
const content = `La plupart des récits d'horreur sur le coût de l'IA partagent une cause racine : un agent sans plafond. Il a bouclé, il a réessayé, il a traîné un énorme contexte, et personne ne l'a découvert avant la facture. La solution n'est pas un modèle plus intelligent. C'est un budget strict sur chaque agent, appliqué avant que la dépense ne se produise, pas après.

## Pourquoi les agents sans plafond sont un risque financier

Un agent autonome décide de son propre prochain geste. C'est la fonctionnalité, et c'est aussi l'exposition. Trois modes de défaillance transforment une tâche normale en robinet ouvert.

**Boucles.** L'agent essaie quelque chose, ça ne marche pas, il essaie une variante, et il continue. Sans limite, il brûlera des appels à poursuivre un but qu'il ne peut pas atteindre.

**Nouvelles tentatives.** Un outil instable ou une limite de débit déclenche une nouvelle tentative. Les tentatives s'empilent. Ce qui ressemblait à un appel devient vingt, chacun payant le plein coût de contexte.

**Contextes longs.** Chaque appel au modèle renvoie toute la conversation jusque-là. Une tâche qui accumule de l'historique paie plus à chaque étape que la précédente. Le dernier appel d'une longue exécution peut coûter plusieurs fois le premier.

Aucun de ces cas n'est rare. Ce sont les comportements normaux d'un système à qui l'on donne un but et aucun plafond. Un budget transforme ce risque ouvert en un nombre connu et plafonné.

## Ce qu'un budget par agent devrait plafonner

Un budget n'est utile que s'il arrête le travail quand il est atteint. Il devrait plafonner les choses qui pilotent réellement le coût et la durée :

- **Dépense totale.** Un plafond strict en crédits ou en jetons. Quand l'agent l'atteint, il s'arrête. Pas de dépassement, pas de "juste encore un peu".
- **Nombre d'appels au modèle.** Plafonne directement la boucle. Un agent qui ne peut pas faire un vingt-et-unième appel ne peut pas boucler indéfiniment.
- **Appels d'outils.** Certains outils coûtent de l'argent ou touchent des quotas externes. Plafonnez le nombre de fois qu'un agent peut y recourir.
- **Temps d'horloge.** Un agent bloqué ne devrait pas tourner pendant une heure. Fixez-lui un délai d'expiration.

La règle qui rend un budget réel : quand le plafond est atteint, l'agent s'arrête et le workflow s'en occupe. Il ne continue pas en silence, et il n'échoue pas discrètement. Il s'arrête, et l'exécution enregistre qu'il s'est arrêté parce qu'il a atteint son budget.

## Cadrez les outils et les données qu'un agent peut toucher

Le budget est la moitié de la réponse. Le cadrage est l'autre moitié, et il abaisse le coût avant qu'aucun plafond ne soit nécessaire.

Un agent qui peut tout voir tentera de tout utiliser. Donnez-lui toute la base de données et il raisonne sur toute la base de données, et vous payez les jetons. Donnez-lui seulement les outils et les données dont l'étape a besoin, et il reste petit par construction.

Pour une étape de classification, cela signifie le texte du message et un outil pour renvoyer une étiquette. Rien d'autre. Pour une étape de brouillon, le message et la catégorie. Un agent étroitement cadré est moins cher à chaque appel parce que son contexte est petit, et il est plus sûr parce qu'il ne peut pas s'égarer vers des données ou des actions qui ne sont pas son travail.

Le cadrage réduit le rayon de dégâts. Le budget plafonne ce qui reste. Vous voulez les deux.

## Fixez des budgets par agent et par exécution

Un seul nombre ne suffit pas. Il vous faut des budgets à deux niveaux.

**Par agent.** Chaque étape reçoit son propre plafond dimensionné à son travail. Une classification rapide devrait avoir un tout petit budget. Une étape de recherche qui lit plusieurs documents en obtient plus. Dimensionner chaque agent à son travail réel garantit qu'une étape gourmande ne peut pas dépenser toute l'allocation.

**Par exécution.** Le workflow entier reçoit aussi un plafond. Même si chaque agent individuel reste dans son propre budget, une exécution qui se déploie en des centaines de branches parallèles peut s'additionner. Un plafond au niveau de l'exécution protège contre la somme, pas seulement contre les parties.

Ensemble, ils vous donnent une enveloppe prévisible : un pire cas connu par étape et un pire cas connu pour l'exécution. C'est ce qui transforme le "coût de l'IA" d'une surprise en une ligne budgétaire que vous pouvez planifier.

## Surveillez la dépense par agent et par outil

Les budgets stoppent le coût qui s'emballe. La surveillance vous dit où le coût vit réellement pour que vous puissiez l'ajuster.

Suivez la dépense à un grain fin :

- **Par agent.** Quelle étape coûte le plus ? Souvent c'est un nœud qui en fait plus qu'il n'a besoin, qui porte trop de contexte, ou qui utilise un modèle plus gros que la tâche ne l'exige.
- **Par outil.** Quels appels d'outils dominent ? Un unique appel d'API externe coûteux fait sur chaque élément peut discrètement devenir l'essentiel de la facture.
- **Par exécution.** Combien coûte une exécution typique, et combien coûte une mauvaise ? L'écart entre les deux est là où se cachent vos boucles et vos nouvelles tentatives.

Avec cette vue, vous ajustez délibérément. Rognez le contexte d'une étape. Faites-la passer à un modèle moins cher là où la qualité le permet. Ajoutez un garde de déduplication pour qu'un outil ne soit pas appelé deux fois pour le même élément. Chaque changement est mesurable parce que vous voyez le nombre bouger.

## Mettez le tout ensemble

Le coût de l'IA qui s'emballe est un problème de conception, pas un problème de modélisation. Vous le résolvez structurellement.

Cadrez chaque agent aux outils et aux données dont son travail a besoin. Donnez à chaque agent un budget strict qu'il ne peut pas dépasser. Mettez un plafond sur l'exécution entière. Surveillez la dépense par agent et par outil, et ajustez là où l'argent va réellement.

Faites cela et le coût cesse d'être ce qui vous empêche de livrer. Il devient un nombre que vous fixez exprès, appliquez automatiquement, et pouvez défendre ligne par ligne.
`;

export default content;
