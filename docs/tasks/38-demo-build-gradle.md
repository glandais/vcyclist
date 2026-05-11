# 38 — Démo Vue/Vite : intégration Gradle + GPX samples + docs

## Goal

Faire de `:demo` un **module Gradle de premier ordre** : `./gradlew :demo:assemble` produit un site statique déployable, sans avoir à passer par `cd demo && npm run build` manuellement. La tâche couvre aussi :

- Copie de tous les GPX d'exemple depuis `virtual-cyclist/demo/public/gpx/` (7 fichiers).
- README du module + section "Try the demo" dans le README racine.
- Mise à jour de `docs/PLAN.md` : Phase 9 décrit le portage Vue (et non plus le Compose Multiplatform) + ligne `Avancement` pour les tâches 34-38.

## Depends on

- Task 34 (façade engine)
- Task 35 (bootstrap demo)
- Task 36 (intégration moteur)
- Task 37 (UI complète)

## Inputs

- `virtual-cyclist/demo/public/gpx/{amazfit,garmin,movescount,sample,sports-tracker,stelvio,strava}.gpx` — 7 GPX d'exemple.
- `elevation/build.gradle.kts` — référence pour un module KMP existant (ne pas copier ; juste s'inspirer du style Gradle DSL).
- `settings.gradle.kts` — y ajouter `include(":demo")`.
- `docs/PLAN.md` — section Phase 9 (lignes 481-491) à réécrire + table `Avancement` à étendre.
- Plugin candidat : [`com.github.node-gradle:plugin` (`node-gradle`)](https://github.com/node-gradle/gradle-node-plugin) v7+. Permet de gérer `npm install` / `npm run build` via Gradle de manière reproductible.

## Steps

### 1. Plugin Gradle Node

Ajouter à `gradle/libs.versions.toml` :

```toml
[versions]
node-gradle = "7.1.0"

[plugins]
node-gradle = { id = "com.github.node-gradle.node", version.ref = "node-gradle" }
```

Et dans `build.gradle.kts` racine (block `plugins` apply false, sur le modèle de kotlin.multiplatform) :

```kotlin
plugins {
    // …
    alias(libs.plugins.node.gradle) apply false
}
```

### 2. `settings.gradle.kts`

```kotlin
include(":elevation")
include(":engine")
include(":codegen")
include(":demo")  // nouveau
```

### 3. `demo/build.gradle.kts`

```kotlin
import com.github.gradle.node.npm.task.NpmTask

plugins {
    alias(libs.plugins.node.gradle)
}

node {
    download.set(true)        // download Node automatiquement, indépendant du système
    version.set("22.12.0")    // LTS aligné avec CI
    npmVersion.set("10.9.0")
}

// Dépendance explicite sur la sortie Kotlin/JS — l'alias file: dans package.json
// résout vers engine/build/dist/js/productionLibrary/, donc on s'assure que cette
// distribution est à jour avant tout npm task.
val engineDist = tasks.register("engineDist") {
    dependsOn(":engine:jsBrowserProductionLibraryDistribution")
}

tasks.named("npmInstall") {
    dependsOn(engineDist)
}

val npmBuild = tasks.register<NpmTask>("npmBuild") {
    dependsOn("npmInstall")
    args.set(listOf("run", "build"))
    inputs.dir("src")
    inputs.file("package.json")
    inputs.file("package-lock.json")
    inputs.file("vite.config.ts")
    inputs.file("tsconfig.json")
    inputs.file("index.html")
    inputs.dir("public")
    outputs.dir("dist")
}

val npmTypecheck = tasks.register<NpmTask>("npmTypecheck") {
    dependsOn("npmInstall")
    args.set(listOf("run", "typecheck"))
}

val npmLint = tasks.register<NpmTask>("npmLint") {
    dependsOn("npmInstall")
    args.set(listOf("run", "lint"))
}

tasks.register("assemble") {
    dependsOn(npmBuild)
}

tasks.register("check") {
    dependsOn(npmTypecheck, npmLint)
}

// `clean` task : Vite outDir + node_modules.
tasks.register<Delete>("clean") {
    delete("dist", "node_modules")
}
```

### 4. Copier les 7 GPX samples

```bash
cp virtual-cyclist/demo/public/gpx/*.gpx vcyclist/demo/public/gpx/
```

Mettre à jour `FileSection.vue` (ou son équivalent dans la liste de "Sample tracks") pour exposer les 7 GPX dans l'UI — si la TS demo le fait déjà, le port en task 37 doit avoir conservé la liste. Sinon, l'ajouter ici.

### 5. `demo/README.md`

```markdown
# vcyclist — Vue/Vite Demo

Interactive Vue 3 + Vite frontend that consumes the Kotlin/JS build of
`:engine` to demonstrate the physics-aware GPX virtualization pipeline.

## Quick start

```bash
# from vcyclist/ root
./gradlew :engine:jsBrowserProductionLibraryDistribution  # build the bundle
cd demo
npm install
npm run dev                                                 # http://localhost:3000
```

The `predev`/`prebuild` npm scripts automatically run the Gradle distribution
task, so subsequent runs only need `npm run dev`.

## Build a static site

```bash
# from vcyclist/ root
./gradlew :demo:assemble
# → demo/dist/ ready to serve with any static host
python -m http.server -d demo/dist 8000
```

## Architecture

The Vue 3 app imports `@glandais/vcyclist-engine` via a `file:` link to the
Gradle output (`../engine/build/dist/js/productionLibrary/`). The engine is the
Kotlin/JS compiled output of the `:engine` module — same physics, same GPX
parser, same DEM-fix pipeline as the JVM CLI (see [`README.md`](../README.md)).

- `src/engine-shim.ts` — thin re-export of the engine's `@JsExport` symbols.
- `src/composables/useGPXDemo.ts` — `parse → enhance → render` orchestration.
- `src/composables/useChart.ts` — Chart.js wrapper (zoom, crosshair, 36 fields).
- `src/composables/useMap.ts` — Leaflet wrapper + hover sync.
- `src/components/*.vue` — PrimeVue UI (tabs, sidebar, modals).

## License

Apache 2.0, same as the parent vcyclist project.
```

### 6. README racine — section "Try the demo"

Ajouter, après la section "Quick start" du `README.md` racine, un bloc :

```markdown
## Try the demo

A Vue 3 + Vite frontend that exercises the engine end-to-end in a browser is
shipped under [`demo/`](demo/). Build and serve with:

\`\`\`bash
./gradlew :demo:assemble
python -m http.server -d demo/build/dist 8000  # ou n'importe quel static server
\`\`\`

See [`demo/README.md`](demo/README.md) for details.
```

(Conserver le ton du README existant — éventuellement ajuster wording.)

### 7. Mise à jour `docs/PLAN.md`

#### 7a. Réécrire la section Phase 9 (lignes 481-491)

```markdown
## Phase 9 — Demo Vue/Vite sur sortie Kotlin/JS

**Décision** : la démo Compose Multiplatform initialement esquissée est
abandonnée. À la place, on **porte** la démo Vue 3 + Vite existante de
`virtual-cyclist/demo/` dans le module `:demo` du repo vcyclist, en branchant
le moteur Kotlin/JS à la place du moteur TypeScript. Avantages : réutilise un
UX déjà mature (PrimeVue, Leaflet, Chart.js), évite l'investissement en
chart/map natif Compose, livre la démo en quelques tâches.

- **Module `:demo`** : Vue 3 + Vite + PrimeVue + Leaflet + Chart.js,
  TypeScript, géré via Gradle (`com.github.node-gradle.node` plugin).
- **Consommation moteur** : `@glandais/vcyclist-engine` linké via
  `file:../engine/build/dist/js/productionLibrary` (Vite + npm). La cible
  Kotlin/JS est privilégiée pour la maturité d'interop avec Vue (vs Wasm).
- **API engine étendue** (task 34) : `enhanceWithCourse(path, cyclist?, bike?,
  wind?, power?, options?)`, `getField(path, i, fieldProp)`, `fieldDefinitions()`,
  plus les DTO `external interface`.
- **Optionnel** (task 39) : publication GitHub Pages sur push develop.
```

#### 7b. Ajouter 5 lignes à la table `Avancement`

À la fin du tableau (après la ligne 77, l'entrée 33) :

```markdown
| **— Phase 9 : demo Vue/Vite sur Kotlin/JS —** | | | | |
| 34 | Engine — `@JsExport` façade : enhanceWithCourse + getField + fieldDefinitions + DTO Cyclist/Bike/Wind/Power | ☐ | | |
| 35 | Demo — bootstrap Vue/Vite + alias `@glandais/vcyclist-engine` + shell vide | ☐ | | |
| 36 | Demo — intégration moteur : useGPXDemo + types + persistance config | ☐ | | |
| 37 | Demo — UI complète (16 composants Vue + Chart.js + Leaflet + 6 tabs + sidebar) | ☐ | | |
| 38 | Demo — intégration Gradle (`:demo:assemble`) + GPX samples + README | ☐ | | |
| 39 | Demo — déploiement GitHub Pages (optionnel) | ☐ | | |
```

### 8. CI : étendre `check`

Si le projet a un workflow CI global (`.github/workflows/ci.yml` ou similaire) qui exécute `./gradlew check`, vérifier qu'il inclut bien `:demo:check` (typecheck + lint). Sinon, ajouter une étape explicite ou un commentaire dans le workflow.

Pour ne pas alourdir le CI hot path, **ne pas** ajouter `:demo:assemble` à `check` (build complet npm coûteux). Il reste sous `assemble` que CI déclenche explicitement seulement pour les release / preview.

### 9. Smoke

```bash
cd vcyclist
./gradlew :demo:assemble
ls demo/dist/   # ou demo/build/dist/ selon où Vite outDir pointe
# Doit contenir index.html, assets/ avec chunks engine-*.js, primevue-*.js, etc.

# Serve et vérifier
python -m http.server -d demo/dist 8000
# Open http://localhost:8000, doit montrer la même UI que `npm run dev`
```

Vérifier aussi `./gradlew :demo:check` (typecheck + lint) passe.

## Outputs

Créés :

- `demo/build.gradle.kts`
- `demo/README.md`
- `demo/public/gpx/*.gpx` (6 fichiers, en plus de stelvio.gpx déjà copié en task 36 — soit 7 au total)

Modifiés :

- `settings.gradle.kts` (ajout `include(":demo")`)
- `gradle/libs.versions.toml` (entrée plugin node-gradle)
- `build.gradle.kts` racine (ajout `alias(libs.plugins.node.gradle) apply false` au `plugins` block)
- `README.md` racine (section "Try the demo")
- `docs/PLAN.md` (réécriture Phase 9 + ajout 6 lignes table Avancement)
- (optionnel) `.github/workflows/ci.yml` — si CI global existe

## Validation

```bash
cd vcyclist
./gradlew :demo:check
./gradlew :demo:assemble
./gradlew :elevation:allTests :engine:allTests     # régression
./gradlew ktlintCheck
```

Critères :

- `./gradlew :demo:check` vert (typecheck + lint clean).
- `./gradlew :demo:assemble` produit `demo/dist/` (ou `demo/build/dist/` selon où Vite outDir est configuré) avec un `index.html` + assets fonctionnels.
- `./gradlew clean :demo:assemble` (build à froid) passe en < 90 s sur une machine moyenne (le download Node + `npm install` initial dominent).
- Régression : tous les tests de `:elevation` et `:engine` restent verts.
- `docs/PLAN.md` montre Phase 9 mise à jour avec les 6 tasks (34-39) listées.

## Done when

- [x] `demo/build.gradle.kts` créé avec tasks `assemble`, `check`, `clean`, dépendance sur `:engine:jsBrowserProductionLibraryDistribution`
- [x] `settings.gradle.kts` inclut `:demo`
- [x] `libs.versions.toml` + root `build.gradle.kts` déclarent le plugin node-gradle
- [x] 7 GPX samples copiés dans `demo/public/gpx/`
- [x] `demo/README.md` créé
- [x] README racine étendu avec section "Try the demo"
- [x] `docs/PLAN.md` : Phase 9 réécrite + 6 lignes ajoutées au tableau Avancement
- [x] `./gradlew :demo:check` vert
- [x] `./gradlew :demo:assemble` produit un site statique servable
- [x] Régression : tous tests `:elevation` + `:engine` verts
- [x] Toutes les checkboxes cochées

## Notes

- **Plugin `node-gradle` vs `npm-publish-plugin`** : on choisit `node-gradle` car il télécharge Node automatiquement (CI reproductible, pas de prérequis système). Il fournit `NpmTask` qui s'intègre proprement avec les inputs/outputs Gradle pour le caching incrémental.
- **`outDir: 'dist'` vs `build/dist`** : Vite par défaut écrit dans `demo/dist/`. Si on veut respecter la convention Gradle (`build/`), modifier `vite.config.ts` : `build.outDir = 'build/dist'` et adapter README + task 39. Choix : laisser `dist/` pour rester proche du demo TS (moins de surprise pour quelqu'un qui connaît Vite), Gradle s'aligne via `outputs.dir("dist")`.
- **Caching `npm install`** : le plugin `node-gradle` cache `node_modules` correctement avec `package-lock.json` comme key. Premier run : ~30 s. Runs suivants : UP-TO-DATE immédiat. Si CI flaky, pré-warmer avec `actions/cache` sur `~/.npm` + `demo/node_modules`.
- **`@glandais/vcyclist-engine` file: link refresh** : npm v7+ ne suit pas les changements de la cible `file:`. Si le bundle est régénéré, `node_modules/@glandais/vcyclist-engine` reste obsolète. Mitigation : le `predev`/`prebuild` script invoque la distribution Gradle AVANT que Vite ne résolve les imports, donc Vite lit toujours la version fraîche (Vite suit le symlink à chaque resolve). Vérifier sur Windows — si non, ajouter un `npm rebuild @glandais/vcyclist-engine` à `predev`.
- **`ktlintCheck` n'inspecte pas TS** : pas de lint Kotlin pour la démo (qui n'a aucun fichier `.kt`). Le `lint` TypeScript est traité par `npm run lint` (ESLint + Prettier), invoqué dans `:demo:check`.
- **Pourquoi pas de tests automatisés UI** : sortir des tests E2E (Playwright/Cypress) pour une démo de portage est disproportionné. La validation reste manuelle. Si un futur besoin de régression UI apparaît, ajouter une suite Playwright minimale en hot-fix (load stelvio + assert chart datasets > 0).
