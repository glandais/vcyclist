# Migration ledger — PrimeVue 4 → Nuxt UI v4

> **Statut global : `DONE`** — branche `migrate-nuxt-ui`. P1–P9 et T1–T18 faits : plus aucun composant PrimeVue, typecheck + lint + build verts, passe visuelle OK (y compris `ClimbsPanel`). Reporté depuis `virtual-cyclist` (PR #137, mergée le 2026-08-17), mêmes arbitrages acceptés.
> Décision : remplacer PrimeVue par **Nuxt UI v4** (MIT, bâti sur Tailwind v4 + Reka UI).
> Motif : PrimeVue v5 passe sous licence commerciale.
> Périmètre : `demo/` uniquement — le moteur Kotlin/JS (`engine/`) n'a aucune dépendance UI.
>
> Légende statut : `TODO` · `WIP` · `DONE` · `BLOCKED` · `SKIP`

> ### 📌 Dépôt jumeau
> Ce ledger est le pendant de `virtual-cyclist/demo/MIGRATION-NUXT-UI.md`. Les deux démos
> partagent **exactement la même surface PrimeVue** (mêmes composants, mêmes occurrences,
> mêmes fichiers, même `main.ts` à la mise en forme près). Vérifié par diff le 2026-08-17.
>
> **Toutes les tâches T1–T13 sont transposables verbatim** : les fichiers `SliderInput.vue`,
> `FieldsSidebar.vue`, `ConfigModal.vue`, `Toolbar.vue`, `WindTab.vue` et les 5 fichiers morts
> sont **identiques octet pour octet** entre les deux dépôts. Les fichiers qui diffèrent
> (`BikeTab`, `CyclistTab`, `EnhanceOptionsTab`, `FileSection`, `PowerTab`, `App.vue`) ne
> diffèrent **que** sur le typage moteur (`BikeDto`/`CyclistDto` via `engine-shim.ts`, noms de
> champs `massKg`/`wheelRadiusM`, `PowerSourceType.from_data`) — **jamais** sur le markup PrimeVue.
>
> **Stratégie recommandée** : migrer `virtual-cyclist/demo` en premier (tooling plus rapide),
> puis reporter ici. Les commits 4 à 9 (T1–T13) devraient être quasi copiables. Les écarts
> réels sont listés en §7.

---

## 0. État des lieux (relevé le 2026-08-17)

### Composants PrimeVue réellement montés

| Composant | Occ. vivantes | Fichiers |
|---|---|---|
| `Button` | 13 | `Toolbar.vue` (5), `BikeTab.vue` (4), `ConfigModal.vue` (4) |
| `SliderInput.vue` *(wrapper local `Slider` + `InputNumber`)* | **16** | `CyclistTab` (6), `BikeTab` (5), `PowerTab` (2), `EnhanceOptionsTab` (2), `WindTab` (1) |
| `Checkbox` | 7 | `EnhanceOptionsTab.vue` (5), `PowerTab.vue` (1), `FieldsSidebar.vue` (1, dans `v-for`) |
| `Panel` | 4 | `ConfigModal.vue`, `FileSection.vue`, `BikeTab.vue`, `CyclistTab.vue` |
| `Slider` (direct) | 1 | `WindTab.vue` (gradient custom) |
| `InputNumber` (direct) | 1 | `WindTab.vue` |
| `Select` | 1 | `FileSection.vue` |
| `RadioButton` | 3 | `PowerTab.vue` (même groupe `powerSource`) |
| `Drawer` | 1 | `FieldsSidebar.vue` |
| `Accordion` + 3 sous-composants | 1 | `FieldsSidebar.vue` |
| `Tabs` + 4 sous-composants | 1 | `ConfigModal.vue` |
| `Toast` + `useToast()` | 1 + 6 appels | `App.vue` |
| `ProgressSpinner` | 1 | `Toolbar.vue` |
| ~~`Dialog`~~ | **0** | uniquement dans `Modal.vue`, fichier mort |

### Dette identifiée à purger au passage

| Constat | Détail |
|---|---|
| **5 fichiers morts** | `Modal.vue`, `DataPanel.vue`, `FieldsTab.vue`, `VisualizationControls.vue`, `ControlPanel.vue` — zéro import dans tout `src/` |
| **`primeicons` inutilisé** | importé dans `main.css`, **zéro** classe `pi pi-*` dans le code ; toute l'iconographie est en emoji |
| **Dark mode mort** | `darkModeSelector: '.dark'` configuré, rien ne pose jamais la classe `dark` |
| **`cssLayer: false`** | le CSS PrimeVue n'est pas dans un `@layer` → conflit frontal avec Tailwind v4, aucun plugin `tailwindcss-primeui` installé |
| **Imports incohérents** | `ConfigModal.vue` et `FieldsSidebar.vue` importent depuis le barrel `primevue`, le reste par sous-chemin |
| **`vue-router` absent** | prérequis du plugin Vue standalone de Nuxt UI |

> `ClimbsPanel.vue` et `useClimbs.ts` (spécifiques à ce dépôt) sont **100 % Tailwind, zéro PrimeVue** — hors périmètre, rien à migrer.

---

## 1. Prérequis — à faire avant toute migration de composant

| # | Tâche | Statut | Notes |
|---|---|---|---|
| P1 | Supprimer les 5 fichiers morts (`Modal`, `DataPanel`, `FieldsTab`, `VisualizationControls`, `ControlPanel`) | `DONE` | Fait disparaître `Dialog` du périmètre. Commit séparé, avant tout le reste. |
| P2 | Retirer `primeicons` de `package.json` et de `main.css` | `DONE` | Dépendance morte, indépendante de la migration. Peut être commitée dès maintenant. |
| P3 | `npm i @nuxt/ui vue-router` · `npm rm primevue @primeuix/themes primeicons` | `DONE` | `vue-router` est requis par `@nuxt/ui/vue-plugin` même sans routes ; créer un router minimal (`createWebHashHistory`, une route `/`). |
| P4 | `vite.config.ts` : ajouter le plugin `ui()` de `@nuxt/ui/vite` avec `colorMode: false` | `DONE` | `colorMode: false` tant que le dark mode n'est pas un objectif — évite de réintroduire du mort. Voir T15 si on le veut. |
| P5 | `main.ts` : remplacer `app.use(PrimeVue, {...})` + `app.use(ToastService)` par `app.use(uiPlugin)` | `DONE` | Supprime le preset Aura et `cssLayer: false`. |
| P6 | `App.vue` : envelopper la racine dans `<UApp>` | `DONE` | Requis pour que `useToast()` / overlays fonctionnent. Remplace `<Toast />`. |
| P7 | `main.css` : ajouter `@import "@nuxt/ui";` après `@import "tailwindcss";` | `DONE` | Vérifier l'ordre par rapport à `custom.css` (styles Leaflet/compass). |
| P8 | `vite.config.ts` : remplacer les `manualChunks` `primevue1`/`primevue2`/`primeuix` | `DONE` | Nouveau split sur `node_modules/@nuxt/ui` + `node_modules/reka-ui`. Garder `leaflet` / `chartjs` / `engine` inchangés. |
| P9 | Vérifier que les nouvelles règles ESLint/`eslint-plugin-vue` ne cassent pas sur les composants Nuxt UI | `DONE` | **Spécifique à ce dépôt** (ESLint 10 + Prettier, pas oxlint/oxfmt). Les slots dynamiques de `UAccordion`/`UTabs` peuvent déclencher `vue/no-undef-*`. |

**Point de contrôle P** : `npm run typecheck && npm run lint && npm run build` passent avec l'app à moitié cassée visuellement mais Nuxt UI monté. Ne pas enchaîner tant que ce n'est pas vert.
**Attention** : `predev`/`prebuild` déclenchent `./gradlew :engine:jsBrowserProductionLibraryDistribution`. Le moteur Kotlin doit être buildé au moins une fois avant de pouvoir lancer un `vite build` — un échec Gradle n'a rien à voir avec la migration UI.

---

## 2. Migration des composants

Ordre imposé par le risque : `SliderInput` d'abord (16 usages en dépendent), les triviaux ensuite, les structurels en dernier.

| # | Cible | Effort | Statut | Détail |
|---|---|---|---|---|
| **T1** | **`SliderInput.vue`** → `USlider` + `UInputNumber` | **Élevé** | `DONE` | **Composant pivot — à faire et valider en premier.** Vérifier explicitement l'équivalence de : `minFractionDigits`/`maxFractionDigits` (calculés depuis `step` dans `fractionDigits`), `useGrouping: false`, `locale="en-US"`, `suffix` (` ${unit}`). Si `UInputNumber` ne couvre pas `suffix`, le rendre en `<span>` adjacent. Migrer `pt:input:class="text-right w-full"` vers la prop `:ui`. |
| T2 | `Button` → `UButton` | Faible | `DONE` | Mapping : `severity="secondary"` → `color="neutral"`, `warn` → `warning`, `primary` → `primary`, `success`/`danger` idem ; `outlined` → `variant="outline"` ; `size="small"` → `size="sm"` ; `:disabled` inchangé. Labels emoji restent en slot par défaut. |
| T3 | `Checkbox` → `UCheckbox` | Faible | `DONE` | 7 usages, tous en `:modelValue` + `@update:modelValue` (pilotage externe, pas de `v-model`) — ce pattern est conservé tel quel. `:binary="true"` disparaît (comportement par défaut). `:inputId` → vérifier l'attribut équivalent pour garder les `<label :for>` de `FieldsSidebar`. |
| T4 | `Select` → `USelect` | Faible | `DONE` | `FileSection.vue`. `:options` + `optionLabel`/`optionValue` → prop `items` avec `value-key`/`label-key`. Conserver `placeholder`, `:disabled`, `@update:modelValue` → `onGPXChange`. |
| T5 | `ProgressSpinner` → **manuel** | Faible | `DONE` | Aucun spinner circulaire dédié dans Nuxt UI. Remplacer par `<UIcon name="i-lucide-loader-circle" class="animate-spin size-5" />` — **implique d'ajouter une collection d'icônes** (`@iconify-json/lucide`), première icône non-emoji du projet. Alternative sans dépendance : un `<div>` CSS `border` + `animate-spin`. **Trancher avant T5.** |
| T6 | `RadioButton` ×3 → `URadioGroup` | Moyen | `DONE` | `PowerTab.vue` : pas de radio unitaire dans Nuxt UI. Les 3 radios sont déjà un seul groupe (`name="powerSource"`) → passer à un `URadioGroup` piloté par un tableau `items` `[{value, label, description}]`. **La mise en forme actuelle (carte cliquable `<label>` avec bordure, hover, titre gras + description) doit être reconstruite via les slots du composant** — c'est là que part l'effort, pas dans la logique. |
| T7 | `Panel` ×4 → `UCollapsible` (+ `UCard`) | **Moyen-élevé** | `DONE` | Pas d'équivalent 1:1. Deux profils distincts : (a) `BikeTab` / `CyclistTab` / `FileSection` = `toggleable :collapsed="true"` + `#header` → `UCollapsible` avec slot `#default` en trigger ; (b) `ConfigModal` = **non toggleable**, simple conteneur titré → un `UCard` (ou du markup Tailwind nu) suffit. Migrer les `pt:root:class` / `pt:header:class` / `pt:content:class` (bleus de `FileSection` et `ConfigModal`) vers `:ui` ou des classes directes. |
| T8 | `Tabs` (+`TabList`/`Tab`/`TabPanels`/`TabPanel`) → `UTabs` | Moyen | `DONE` | `ConfigModal.vue` : 5 onglets → API `items` array `[{label: '👤 Cyclist', slot: 'cyclist'}, ...]` avec un `<template #cyclist>` par onglet hébergeant le composant enfant. Refactor structurel, pas un renommage. Corrige au passage l'import barrel incohérent. |
| T9 | `Accordion` (+3 sous-composants) → `UAccordion` | Moyen | `DONE` | `FieldsSidebar.vue` : `v-for` sur `fieldConfig` → construire un `computed` `items` `[{label: category.name, slot: categoryKey}]`. `multiple` → prop `type="multiple"`. `:value="Object.keys(fieldConfig)"` (tout ouvert par défaut) → `default-value` avec le même tableau. Le contenu (liste de `UCheckbox` + labels) passe dans les slots dynamiques. |
| T10 | `Drawer` → `UDrawer` (ou `USlideover`) | Faible-moyen | `DONE` | `FieldsSidebar.vue` : `position="right"` → `direction="right"`. `header="📊 Chart Fields"` → slot `#header`. `class="!w-48/100"` (48 % de largeur, `!` pour battre la spécificité PrimeVue) → à réécrire proprement via `:ui`, le `!important` ne devrait plus être nécessaire. **Vérifier que `USlideover` n'est pas le meilleur choix** pour un panneau latéral persistant. |
| T11 | `Toast` + `useToast()` → `UToast` + `useToast()` | Faible | `DONE` | `App.vue`, 6 appels. Renommage de champs : `severity: 'success'` → `color: 'success'` (et `'error'` → `'error'`), `summary` → `title`, `detail` → `description`, `life` → `duration`. `<Toast />` supprimé au profit de `<UApp>` (voir P6). |
| T12 | `Slider` direct de `WindTab.vue` → `USlider` | Moyen | `DONE` | Le gradient `pt:root:class="bg-gradient-to-r from-blue-500 via-green-500 to-blue-500"` doit passer par `:ui` (slot `track`) ou du CSS ciblé dans `custom.css`. Attention au handler existant `Array.isArray($event) ? $event[0] : $event` — vérifier le type émis par `USlider` (range vs valeur simple). |
| T13 | `InputNumber` direct de `WindTab.vue` → `UInputNumber` | Faible | `DONE` | `suffix="°"`, `:min="0" :max="360" :step="15"`, `class="w-20"`. Dépend des constats de T1. |

---

## 3. Finalisation

| # | Tâche | Statut | Notes |
|---|---|---|---|
| T14 | Purge finale : plus aucune occurrence de `primevue`, `@primeuix`, `primeicons`, `pt:` dans `src/` et `package.json` | `DONE` | `grep -rn "primevue\|primeuix\|primeicons\|pt:" src/ package.json` doit rendre vide. |
| T15 | *(optionnel)* Activer réellement le dark mode | `SKIP` par défaut | Nuxt UI le fournit gratuitement via `colorMode: true` + `useColorMode()`. À décider explicitement — aujourd'hui c'est du mort chez PrimeVue. Ne pas l'ouvrir dans le même chantier. |
| T16 | Vérifier le poids du bundle avec un analyzer | `DONE` | **Mesuré et accepté** (même arbitrage que sur `virtual-cyclist`). `npm run build` :<br>• **Avant (PrimeVue)** : JS 543,5 kB (**120,6 kB gzip**) + CSS 33,4 kB (7,8 kB gzip) = **128,4 kB gzip**<br>• **Après (Nuxt UI)** : JS 568,2 kB (**168,3 kB gzip**) + CSS 197,8 kB (25,9 kB gzip) = **194,2 kB gzip**<br>**+65,8 kB gzip, soit +51 %.** Comme sur le dépôt jumeau, l'essentiel vient du CSS de Nuxt UI non purgé par Tailwind. Le chunk `engine` (Kotlin/JS, ~1,09 MB) reste de loin le poste dominant du bundle, ce qui relativise l'écart. |
| T17 | `npm run typecheck && npm run lint && npm run build` verts | `DONE` | Pas de script `check:demo` ici — enchaîner les trois à la main. |
| T18 | Passe visuelle manuelle sur les 5 onglets, le drawer, le toast, la carte, le graphe **et le panneau Climbs** | `DONE` | Vérifier surtout que Leaflet, Chart.js et `ClimbsPanel` (`custom.css`, `.leaflet-map`, `.compass`) ne sont pas régressés par le changement de couche CSS. `ClimbsPanel` est en Tailwind nu : c'est le meilleur canari d'une régression de couche. |

---

## 4. Risques

| Risque | Gravité | Mitigation |
|---|---|---|
| `SliderInput.vue` ne trouve pas d'équivalence exacte (`fractionDigits`, `suffix`, `locale`) | **Haute** — 16 usages | Le migrer **en premier** (T1) et valider avant tout autre composant. Si `UInputNumber` est insuffisant, envisager un `<input type="number">` natif stylé Tailwind plutôt que de tordre le composant. |
| Bundle plus lourd qu'avec PrimeVue | Moyenne | T16 avant de merger. Si rédhibitoire, repli sur Reka UI seul (2ᵉ choix technique, mais tout le style à écrire). |
| `Panel` et `RadioButton` demandent une vraie recomposition, pas un renommage | Moyenne | Budgéter T6 et T7 comme les deux plus gros postes après T1. |
| Régression CSS sur Leaflet / compass / graphe | Moyenne | Le passage de `cssLayer: false` à une intégration Tailwind native change l'ordre des couches — T18 est obligatoire, pas cosmétique. |
| Écosystème Nuxt UI centré Nuxt | Faible | Le chemin Vue standalone est officiellement documenté et supporté, mais les exemples de la doc resteront majoritairement Nuxt. |

---

## 5. Découpage en commits suggéré

1. `chore(demo): remove dead components` — P1
2. `chore(demo): drop unused primeicons dependency` — P2
3. `build(demo): install nuxt ui alongside primevue` — P3, P4, P5, P6, P7 *(point de contrôle P)*
4. `refactor(demo): migrate SliderInput to Nuxt UI` — T1 *(le commit à valider le plus soigneusement)*
5. `refactor(demo): migrate simple form controls` — T2, T3, T4, T5, T11
6. `refactor(demo): migrate PowerTab radio group` — T6
7. `refactor(demo): migrate Panel usages` — T7
8. `refactor(demo): migrate Tabs, Accordion and Drawer` — T8, T9, T10
9. `refactor(demo): migrate WindTab slider and input` — T12, T13
10. `build(demo): drop primevue dependencies and rechunk bundle` — P8, T14, T16

---

## 5 bis. Constats du report effectif (2026-08-17)

Le report s'est déroulé comme prévu. Ce qui a réellement différé de `virtual-cyclist` :

- **Pas de P9 TypeScript** : ce dépôt était déjà en `typescript@^6.0.3`, donc `npm install @nuxt/ui`
  passe **sans `--legacy-peer-deps`** et le typecheck n'était pas cassé au départ. Le P9 local
  (règles ESLint) s'est réduit à **175 erreurs `prettier/prettier`**, toutes d'indentation, toutes
  réglées par `npm run lint:fix`. Aucune règle `eslint-plugin-vue` n'a bronché sur les slots
  dynamiques de `UAccordion` / `UTabs`.
- **Le moteur Kotlin doit être disponible dans le worktree** : `engine/build/` est une sortie
  Gradle non versionnée, donc absente d'un worktree neuf — `vue-tsc` et `vite build` échouent sur
  `Cannot find module '@glandais/vcyclist-engine'`. Contourné en pointant sur le build du checkout
  principal ; sinon lancer `./gradlew :engine:jsBrowserProductionLibraryDistribution`.
- **5 fichiers copiés verbatim** depuis la version migrée du dépôt jumeau (`SliderInput`,
  `FieldsSidebar`, `ConfigModal`, `Toolbar`, `WindTab`) — identiques octet pour octet avant
  migration, donc identiques après.
- **`PowerTab`** n'a demandé qu'une substitution : `PowerSourceType.source` → `.from_data`.
- **`ClimbsPanel`** (Tailwind nu, propre à ce dépôt) sert de canari CSS : rendu intact après le
  changement de couche, bandes de couleur de pente comprises.

---

## 6. Écarts avec `virtual-cyclist/demo`

Seuls ces points diffèrent. Tout le reste du ledger est commun aux deux dépôts.

| Sujet | `virtual-cyclist/demo` | **Ici (`vcyclist/demo`)** | Impact migration |
|---|---|---|---|
| Surface PrimeVue | 13 composants | **strictement identique** | **Nul** — T1–T13 verbatim |
| Lint / format | oxlint + oxfmt | **ESLint 10 + Prettier** | P9 en plus ; commandes de T17 différentes |
| Script de check | `npm run check:demo` | **aucun** | Enchaîner les 3 commandes à la main |
| Build préalable | aucun | **`./gradlew :engine:...` via `predev`/`prebuild`** | Le moteur doit être buildé avant tout `vite build` |
| Source de données | lib TS `../src` (alias `@lib`) | **moteur Kotlin/JS via `engine-shim.ts`** | Aucun — le shim n'est jamais touché par la migration UI |
| Alias Vite | `@lib`, `@/`, `~/` | **`~/` seul** | Nul |
| Chunks | `primevue*`, `leaflet`, `chartjs` | **+ `engine`** | P8 : garder `engine` |
| `base` Vite | `'./'` | **conditionnel `DEPLOY_TARGET=gh-pages`** | Nul, mais tester le build gh-pages après T16 |
| Composant en plus | — | **`ClimbsPanel.vue` + `useClimbs.ts`** | Zéro PrimeVue ; sert de canari CSS en T18 |
| TypeScript | 7.x | **6.x** | Vérifier la compat des types `@nuxt/ui` en P3 |

**Fichiers identiques octet pour octet** (les migrations se copient telles quelles) :
`SliderInput.vue`, `FieldsSidebar.vue`, `ConfigModal.vue`, `Toolbar.vue`, `WindTab.vue`,
`Modal.vue`, `DataPanel.vue`, `FieldsTab.vue`, `VisualizationControls.vue`, `ControlPanel.vue`.

**Fichiers qui diffèrent mais dont le markup PrimeVue est identique** (rejouer le même diff UI,
en conservant les noms de champs locaux) : `BikeTab.vue` (`wheelRadiusM`), `CyclistTab.vue`
(`massKg`, `maxLeanAngleDeg`, `frontalAreaM2`), `EnhanceOptionsTab.vue` (`DemoEnhanceOptions`),
`FileSection.vue` (helpers `engine-shim`, chemins GPX `./gpx/`), `PowerTab.vue`
(`PowerSourceType.from_data`), `App.vue` (mêmes 6 `toast.add`, plus `ClimbsPanel`).

---

## 7. Références

- Installation Vue standalone : <https://ui.nuxt.com/docs/getting-started/installation/vue>
- Catalogue de composants : <https://ui.nuxt.com/components>
- `URadioGroup` : <https://ui.nuxt.com/docs/components/radio-group>
- `UToast` / `useToast()` : <https://ui.nuxt.com/docs/components/toast>
- Annonce v4 (fusion core + Pro, MIT) : <https://nuxt.com/blog/nuxt-ui-v4>
- Tree-shaking `reka-ui` : <https://github.com/nuxt/ui/issues/3376>
- Licence PrimeVue v5 : <https://primeui.dev/nextchapter> · <https://primevue.dev/migration/v5/>
