// from-dataset-to-live-workflow - fr
const content = `Un jeu de données devient utile au moment où quelque chose se produit à cause de lui. Jusque-là, ce n'est qu'un fichier. Voici la structure que nous utilisons pour transformer une source de niche statique en un workflow qui tourne tout seul et se termine par une action réelle.

Pour rester concret, un exemple traverse les cinq étapes : une liste de prix fournisseurs hebdomadaire qui devrait déclencher un workflow de revue et d'alerte. Chaque lundi, vos fournisseurs envoient une grille tarifaire mise à jour. Aujourd'hui, quelqu'un ouvre chacune d'elles, la parcourt du regard, et prévient l'acheteur si un prix a bondi. C'est exactement le genre de corvée qui devrait s'exécuter toute seule.

## Étape 1 : choisir une source qui a un battement

Choisissez une donnée qui se met à jour selon un rythme que vous pouvez prévoir. Un export hebdomadaire, une page publique qui se rafraîchit chaque matin, une boîte de réception qui reçoit un rapport chaque lundi. Le battement est ce qui vous permet d'automatiser le rafraîchissement au lieu de recopier des lignes à la main. Si la source ne change jamais, vous n'avez pas besoin d'un workflow, vous avez besoin d'une consultation. Épargnez-vous l'effort.

Soyez précis sur le battement. Pas juste "hebdomadaire" mais "arrive par e-mail chaque lundi avant 9h, un CSV par fournisseur". Cette précision décide de votre déclencheur. Un fichier qui atterrit dans une boîte de réception suggère un déclencheur e-mail. Une page qui se rafraîchit suggère une récupération planifiée. Un système capable d'appeler l'extérieur suggère un webhook.

**Exemple travaillé.** Les listes de prix fournisseurs arrivent en pièces jointes d'e-mail chaque lundi matin. C'est un battement propre et prévisible. Le déclencheur est "nouvel e-mail d'un fournisseur connu avec une pièce jointe de liste de prix". Personne n'a à se souvenir de lancer quoi que ce soit.

## Étape 2 : normaliser une fois, à l'entrée

Les sources brutes sont désordonnées. Les noms de colonnes dérivent, les dates arrivent en trois formats, la même entité apparaît sous deux orthographes, un fournisseur l'appelle "prix unitaire" et un autre "prix/pièce". Faites le nettoyage à un seul endroit, juste là où la donnée entre, pour que chaque étape en aval voie une forme unique et cohérente. Une petite étape de normalisation en tête se rembourse plusieurs fois. Tout ce qui vient après devient plus simple parce qu'il peut se fier à l'entrée.

Décidez d'abord de la forme canonique, puis rattachez chaque source à elle. Pour les listes de prix, la ligne canonique pourrait être : fournisseur, référence, description, prix_unitaire, devise, date_effet. Quelle que soit l'allure de la feuille d'un fournisseur, l'étape de normalisation émet cette forme et rien d'autre. Les nœuds en aval ne voient jamais le désordre brut.

**Exemple travaillé.** Le fournisseur A envoie un fichier Excel avec une colonne "Coût" en euros. Le fournisseur B envoie un CSV avec un "Prix catalogue" en dollars. L'étape de normalisation lit chacun, convertit vers une devise commune, analyse les dates, et sort les six mêmes champs propres pour chaque fournisseur. À partir de là, le workflow ne sait ni ne se soucie de savoir de quel fournisseur vient une ligne.

## Étape 3 : bifurquer sur la décision, pas sur la donnée

Le but du workflow est une décision. Modélisez cette décision explicitement. Si une valeur franchit un seuil, routez d'un côté. Sinon, routez de l'autre. Divisez une liste et traitez chaque élément en parallèle quand les éléments sont indépendants. Créez des chemins séparés quand deux choses doivent se produire en même temps. Gardez le branchement lisible. Un graphe que toute votre équipe peut suivre d'un coup d'œil vaut mieux qu'un graphe astucieux que seul son auteur comprend.

Le piège ici est de bifurquer sur la donnée brute au lieu de bifurquer sur la décision. Peu vous importe que le prix soit 12,40. Ce qui vous importe, c'est de savoir s'il a monté de plus que votre tolérance depuis la semaine dernière. Alors calculez la décision, puis bifurquez dessus.

**Exemple travaillé.** Pour chaque ligne normalisée, le workflow recherche le prix de la semaine dernière pour la même référence, calcule le pourcentage de variation, et bifurque : si la hausse dépasse cinq pour cent, il route vers le chemin "signaler" ; sinon il marque la ligne comme revue et passe à la suite. Comme chaque référence est indépendante, la liste est divisée et les lignes sont vérifiées en parallèle, si bien qu'une feuille de mille lignes se traite encore en une seule passe.

## Étape 4 : terminer par une action, avec un humain là où ça compte

Le dernier nœud devrait faire quelque chose : envoyer la notification, mettre à jour la ligne, ouvrir le ticket, préparer le bon de commande. Quand l'action est risquée ou irréversible, marquez d'abord une pause pour approbation. L'exécution attend qu'une personne valide, puis reprend exactement là où elle s'était arrêtée. Les actions bon marché et réversibles peuvent tourner sans surveillance. Les actions coûteuses ou à sens unique obtiennent un point de contrôle humain.

**Exemple travaillé.** Les hausses de prix signalées sont réunies dans un seul résumé et envoyées à l'acheteur : fournisseur, référence, ancien prix, nouveau prix, pourcentage de variation. Si une hausse est assez grande pour mettre automatiquement en pause une commande déjà en cours, le workflow s'arrête à une étape d'approbation et attend que l'acheteur confirme avant de toucher à quoi que ce soit. Les cas de routine se contentent d'envoyer l'alerte.

## Étape 5 : consigner le résultat pour que la prochaine exécution soit plus fine

Réécrivez le résultat. Une table que le workflow lit et met à jour devient une mémoire partagée : elle se souvient de ce qu'elle a déjà traité, si bien que la prochaine exécution saute les doublons et ne touche que ce qui est nouveau. C'est aussi la source de la comparaison de la semaine prochaine.

**Exemple travaillé.** Chaque ligne traitée est écrite dans une table de prix indexée par fournisseur et référence, avec la date d'effet. Cette table est exactement ce que l'étape 3 lit pour calculer la "variation depuis la semaine dernière". Le workflow s'auto-alimente. Il vous donne aussi une piste d'audit propre : quels prix ont changé et quand, et qui a approuvé la réponse.

## Pièges courants

- **Pas de vrai battement.** Automatiser une source qui change rarement ajoute des pièces mobiles sans bénéfice. Confirmez la cadence avant de construire.
- **Normaliser à trois endroits.** Si deux nœuds nettoient chacun la donnée à leur façon, ils vont dériver et se contredire. Normalisez une fois, à l'entrée.
- **Bifurquer sur des valeurs brutes.** Calculez la décision, puis bifurquez sur la décision. Des seuils enfouis dans cinq nœuds différents sont impossibles à changer plus tard.
- **Pas de point de contrôle humain sur les actions irréversibles.** Envoyer automatiquement un bon de commande sur une mauvaise analyse, voilà comment l'automatisation gagne une mauvaise réputation. Mettez un contrôle sur les étapes à sens unique.
- **Oublier de réécrire.** Sans mémoire, chaque exécution retraite tout et ne peut pas détecter le changement. Le journal n'est pas optionnel, c'est ce qui fait fonctionner la boucle.
- **Comparer du texte comme si c'étaient des nombres.** Stockez les prix dans une forme numérique cohérente et comparez-les comme des nombres, pour qu'un bond de 9 à 100 se lise comme une hausse, pas comme une baisse.

Voilà tout le schéma. Une source qui a un battement, une entrée propre, une décision explicite, une action réelle, et une mémoire. Câblez ces cinq éléments ensemble et le jeu de données cesse d'être un fichier que vous consultez. Il devient un système qui travaille pour vous.
`;

export default content;
