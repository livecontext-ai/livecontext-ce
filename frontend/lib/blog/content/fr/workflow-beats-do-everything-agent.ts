// workflow-beats-do-everything-agent - fr
const content = `La démo d'un unique agent autonome est toujours impressionnante. Vous lui donnez un objectif, il réfléchit, il appelle des outils, il revient avec une réponse. Puis vous le mettez en production et la facture arrive, les résultats vacillent, et personne ne peut vous dire pourquoi il a fait ce qu'il a fait.

Le problème n'est pas le modèle. Le problème est la forme. Un seul agent qui fait tout est la mauvaise forme pour la plupart des travaux réels.

## Coût : le contexte est le compteur

Chaque fois qu'un agent appelle le modèle, il renvoie son contexte. Les instructions, l'historique, tous les résultats d'outils obtenus jusque-là. Un agent bon à tout faire accumule tout cela dans une seule longue conversation, et le contexte grossit à chaque étape.

Vous payez ce contexte à chaque appel. Une tâche en dix étapes ne coûte pas dix petits appels. Elle coûte dix appels qui portent chacun un tas grandissant de tout ce qui a précédé.

Un workflow découpe la tâche en étapes cadrées et n'alimente chacune qu'avec ce dont elle a besoin. L'étape de classification voit le message. L'étape de brouillon voit le message et la catégorie. L'étape d'envoi voit le brouillon approuvé. Aucune étape ne traîne tout l'historique derrière elle.

Alimentez chaque agent avec une tranche étroite plutôt qu'avec toute la transcription et le nombre de jetons chute fortement. En pratique, la même tâche tourne environ dix fois moins cher. Ce n'est pas un tour de passe-passe. C'est le résultat direct de ne pas payer pour renvoyer un contexte qu'une étape donnée n'utilise jamais.

## Contrôle : branchement déterministe contre improvisation

Un agent bon à tout faire décide de son propre chemin à l'exécution. Parfois il prend le bon. Parfois il en invente un nouveau. Vous faites confiance à un système probabiliste pour faire le même choix de routage à chaque fois, et il ne le fera pas.

Un workflow rend le routage explicite. Une question de facturation descend la branche facturation parce que le graphe le dit, pas parce que le modèle en avait envie cette fois-ci. Le jugement flou (est-ce de la facturation ou un bug ?) se produit toujours à l'intérieur d'une étape. La décision de structure (ce qui arrive à un élément de facturation) est fixée.

Cette séparation est tout l'enjeu. Laissez le modèle faire ce que seul un modèle peut faire, à savoir lire et juger. Ne le laissez pas improviser les parties que vous avez besoin de rendre fiables.

## Auditabilité : un chemin que vous pouvez pointer

Quand un seul agent fait tout dans une seule boucle, le compte rendu est un mur de raisonnements et d'appels d'outils. Reconstituer ce qui s'est réellement passé relève de l'archéologie.

Un workflow vous donne une exécution que vous pouvez lire. Voici l'entrée. Voici la branche qu'elle a prise. Voici ce que chaque étape a reçu et renvoyé. Voici le coût de chaque étape. Voici qui a approuvé avant l'envoi. Quand quelqu'un demande pourquoi un client a reçu une réponse précise, vous répondez à partir de la trace au lieu de deviner.

## Débogage : une surface bornée

Un gros agent qui échoue vous donne un unique échec géant à contempler. Était-ce le plan, un mauvais résultat d'outil, une instruction perdue vingt tours plus tôt ? Vous ne pouvez pas l'isoler, parce que tout partage un seul contexte.

Un workflow échoue à un nœud. L'étape de brouillon a produit le mauvais ton, alors vous ouvrez l'étape de brouillon. Ses entrées sont là, sous vos yeux. Vous modifiez cette étape, relancez, et laissez le reste intact. Petit, borné et répétable, comme fonctionne le débogage logiciel normal.

## Soyons justes : quand un seul agent est le bon choix

Les workflows cadrés ne sont pas toujours la réponse, et prétendre le contraire est une forme de battage à part entière.

Ayez recours à un unique agent autonome quand :

- **La tâche est réellement ouverte.** Recherche exploratoire, ou débogage où le prochain geste dépend entièrement du dernier résultat. Vous ne pouvez pas dessiner les branches à l'avance parce qu'elles n'existent pas encore.
- **Le chemin est court et bon marché.** Une consultation en un coup ou un brouillon rapide n'a pas besoin d'un graphe. Un graphe serait de la surcharge.
- **Vous découvrez encore la forme.** Au début, laissez un agent vagabonder et observez ce qu'il fait réellement. Les parties stables de ce comportement sont exactement ce que vous relèverez plus tard dans un workflow.

La règle honnête : si vous pouvez dessiner les étapes, construisez un workflow. Si vous ne pouvez réellement pas encore les dessiner, un agent est le bon outil, pour l'instant.

## L'hybride : le workflow orchestre, les agents font les parties floues

Les meilleurs systèmes de production ne sont ni l'un ni l'autre. Ce sont un workflow avec des agents à l'intérieur.

Le workflow possède la structure : les déclencheurs, les branches, les fusions, les approbations, les nouvelles tentatives, le budget de chaque étape. Il est déterministe là où le déterminisme compte.

À l'intérieur de nœuds individuels, des agents font le travail qui demande du jugement : classer ce message, rédiger cette réponse, extraire ces champs, résumer ce document. Chacun de ces agents est cadré. Il reçoit une entrée claire, un petit jeu d'outils, un budget qu'il ne peut pas dépasser, et il renvoie une sortie claire à l'étape suivante.

Vous obtenez le profil de coût des étapes cadrées, la fiabilité du branchement explicite, et le raisonnement d'un modèle exactement là où le raisonnement aide. L'agent gère la sous-tâche floue. Le workflow gère tout le reste, et il se livre comme une application que vous pouvez exécuter, surveiller et confier à quelqu'un d'autre.

Commencez par vous demander quelles parties de votre travail ont réellement besoin de jugement. Enveloppez-les dans des agents cadrés. Câblez le reste comme un graphe. Voilà la forme qui survit au contact de la production.
`;

export default content;
