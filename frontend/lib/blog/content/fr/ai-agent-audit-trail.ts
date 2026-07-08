// ai-agent-audit-trail - fr
const content = `Un agent IA qui marche dans la démo a prouvé exactement une chose : il peut marcher une fois. La production pose une question plus dure. Quand il fait quelque chose de mal, et il le fera, pouvez-vous découvrir ce qui s'est passé et pourquoi ? Si la réponse est non, vous n'avez pas un système que vous pouvez exploiter. Vous avez un système sur lequel vous fondez des espoirs.

Ce qui transforme l'espoir en exploitation, c'est une piste d'audit. Un compte rendu complet de ce que l'agent a fait, à chaque exécution, que vous pouvez lire après coup.

## Pourquoi "ça marchait dans la démo" ne suffit pas

Une démo est un unique chemin heureux sous une entrée bienveillante. La production, ce sont des milliers d'exécutions sous des entrées que vous n'avez jamais anticipées. Une fraction tourne mal : une mauvaise classification, un outil qui a renvoyé n'importe quoi, une action prise sur le mauvais enregistrement.

Quand l'une d'elles fait surface, en général sous forme de réclamation, vous devez répondre vite à trois questions. Qu'a vu l'agent ? Qu'a-t-il fait ? Pourquoi a-t-il choisi cela ? Sans piste, vous reconstituez une décision d'un système probabiliste après coup, autrement dit vous devinez.

Une piste remplace la devinette par un compte rendu. C'est toute la différence entre un agent que vous exploitez et un que vous vous contentez de déployer.

## Quoi consigner

Une piste d'audit ne vaut que ce qu'elle capture. Consignez assez pour qu'une exécution puisse être entièrement rejouée sur papier, sans la relancer.

- **Entrées.** Ce qui est réellement entré dans l'agent ou l'étape. Pas un résumé, l'entrée réelle. La plupart des rapports "l'IA est cassée" se révèlent être une entrée mauvaise ou surprenante, et vous ne pouvez pas la voir si vous ne l'avez pas consignée.
- **Chaque appel d'outil et son résultat.** Chaque outil que l'agent a invoqué, avec ce qu'il a passé et ce qui est revenu. Les résultats d'outils sont là où la réalité entre dans l'exécution, et là où beaucoup d'échecs commencent.
- **Sorties.** Ce que l'agent a produit à chaque étape et à la fin. La réponse finale, et les réponses intermédiaires qui y ont mené.
- **Coût.** Jetons et dépense par étape. C'est votre facture et votre alerte précoce pour une étape qui en fait plus qu'elle ne devrait.
- **La branche ou la décision prise.** Quel chemin l'exécution a suivi. Un élément de facturation est descendu la branche facturation : consignez qu'il l'a fait, pour que vous puissiez confirmer que le routage était juste.
- **Qui a approuvé.** Pour toute étape sous contrôle humain, consignez qui a approuvé, quand, et ce qu'il a vu au moment de le faire. Les approbations sont l'épine dorsale de la responsabilité.

Capturez cela et n'importe quelle exécution devient une histoire que vous pouvez lire du début à la fin.

## Comment la piste vous aide à déboguer

Déboguer sans piste, c'est contempler une mauvaise sortie et théoriser. Déboguer avec une piste, c'est suivre un chemin.

Vous ouvrez l'exécution en échec. Vous lisez l'entrée et elle a l'air normale. Vous passez à l'étape de classification et voyez qu'elle a renvoyé la mauvaise étiquette. Vous vérifiez ce qu'elle a reçu, et le message était ambigu d'une façon que vous n'aviez pas envisagée. La correction est maintenant évidente : affiner les instructions de classification ou ajouter une branche pour ce cas. Vous l'avez trouvée en lisant, pas en relançant tout vingt fois en espérant le reproduire.

Une piste par étape localise aussi le problème. Vous savez quel nœud a échoué, alors vous changez ce nœud et laissez le reste tranquille. La piste transforme un vague "l'agent se trompe" en une étape précise et corrigeable.

## Comment la piste aide la conformité et la confiance

Certains travaux doivent être explicables à quelqu'un hors de l'équipe : un client, un auditeur, un régulateur, votre propre direction. "L'IA a décidé" n'est une réponse acceptable pour aucun d'eux.

Une piste vous permet de répondre correctement. Voici l'entrée que l'agent a reçue. Voici la règle que la branche a appliquée. Voici l'humain qui a approuvé avant tout envoi. C'est un compte rendu défendable d'une décision, et c'est la même preuve que la question vienne d'un client curieux ou d'un audit formel.

La confiance à l'intérieur de l'équipe fonctionne de la même façon. Les gens confient plus de responsabilités à une automatisation une fois qu'ils peuvent voir exactement ce qu'elle a fait la semaine dernière. La piste, c'est ce qui gagne cette confiance.

## Rétention et revue des exécutions

Une piste que vous ne pouvez pas retrouver ou pas conserver n'est pas vraiment une piste. Quelques notes pratiques.

**Rétention.** Gardez les exécutions assez longtemps pour couvrir les questions que vous recevrez réellement. Les réclamations et les audits arrivent des semaines ou des mois après l'exécution, donc une fenêtre qui ne garde que les derniers jours est trop courte. Adaptez la rétention à la durée pendant laquelle une décision reste vivante et à toute règle qui gouverne vos données.

**Revue.** N'attendez pas une réclamation pour regarder. Passez en revue un échantillon d'exécutions normales selon un calendrier. Vous vérifiez que les branches routent comme prévu, que les coûts se situent là où vous les attendez, et que les approbations se produisent là où elles le devraient. C'est ainsi que vous attrapez la dérive tant qu'elle est petite.

**Grain fin.** Gardez le compte rendu par étape, pas seulement par exécution. Un unique statut final vous dit qu'elle a échoué. Un compte rendu par étape vous dit où et pourquoi. Le détail supplémentaire est exactement ce dont vous avez besoin le jour où quelque chose tourne mal.

## En résumé

Un agent IA de production ne se définit pas par sa performance un bon jour. Il se définit par votre capacité à expliquer ce qu'il a fait un mauvais jour. Consignez les entrées, chaque appel d'outil et son résultat, les sorties, le coût, la branche prise, et qui a approuvé. Gardez cela assez longtemps pour que ça compte, et revoyez-le avant d'y être forcé.

Faites cela et vos agents cessent d'être une boîte noire que vous défendez avec une démo. Ils deviennent des systèmes que vous pouvez déboguer, dont vous pouvez rendre compte, et auxquels vous pouvez vous fier, ce qui est le seul genre qui vaille la peine d'être exploité.
`;

export default content;
