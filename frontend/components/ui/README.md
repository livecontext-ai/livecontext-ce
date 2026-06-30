# Composants UI Réutilisables

Cette bibliothèque de composants contient tous les composants UI réutilisables pour le projet.

## Installation des dépendances

Pour utiliser tous les composants, installez les dépendances Radix UI suivantes :

```bash
npm install @radix-ui/react-toggle @radix-ui/react-checkbox @radix-ui/react-radio-group @radix-ui/react-slider @radix-ui/react-progress @radix-ui/react-tooltip @radix-ui/react-dialog @radix-ui/react-popover
```

## Composants disponibles

### Boutons
- **Button** - Bouton avec différentes variantes (default, secondary, outline, ghost, contrast, destructive, link) et tailles (sm, default, lg, icon)

### Formulaires
- **Input** - Champ de saisie texte
- **Textarea** - Zone de texte multiligne
- **Label** - Étiquette pour les champs de formulaire
- **Select** - Menu déroulant de sélection
- **Checkbox** - Case à cocher
- **RadioGroup** / **RadioGroupItem** - Groupe de boutons radio
- **Slider** - Curseur pour sélectionner une valeur

### Contrôles
- **Switch** - Interrupteur on/off
- **Toggle** - Bouton toggle avec état pressé/non pressé

### Feedback
- **Alert** - Message d'alerte avec variantes (default, destructive)
- **Badge** - Étiquette avec variantes (default, secondary, destructive, outline)
- **Progress** - Barre de progression

### Overlays
- **Tooltip** - Infobulle contextuelle (nécessite TooltipProvider)
- **Dialog** - Modal/dialogue
- **Popover** - Popup contextuelle

### Données
- **Card** - Conteneur avec Header, Content, Footer, Description
- **Tabs** - Onglets (TabsList, TabsTrigger, TabsContent)
- **Table** - Tableau avec Header, Body, Footer, Row, Cell

## Exemple d'utilisation

```tsx
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card'

export default function MyComponent() {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Mon formulaire</CardTitle>
      </CardHeader>
      <CardContent>
        <Input placeholder="Entrez votre nom" />
        <Button>Envoyer</Button>
      </CardContent>
    </Card>
  )
}
```

## Page de démonstration

Tous les composants sont disponibles sur la page `/ui-components` avec des exemples de configuration et d'utilisation.

## Configuration

Tous les composants utilisent les variables CSS du projet (`--bg-primary`, `--text-primary`, `--accent-primary`, etc.) pour s'adapter automatiquement au thème (clair/sombre).

## Personnalisation

Chaque composant accepte une prop `className` pour la personnalisation supplémentaire. Les composants utilisent `class-variance-authority` (CVA) pour gérer les variantes et `tailwind-merge` via la fonction `cn()` pour fusionner les classes de manière optimale.

