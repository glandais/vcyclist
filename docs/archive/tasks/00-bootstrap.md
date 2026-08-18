# 00 — Bootstrap projet vcyclist

## Goal

Créer le squelette Gradle Kotlin Multiplatform du projet `vcyclist/` avec les deux modules `:elevation` et `:engine` (vides mais compilables), les cibles JVM / JS Node / Wasm browser configurées, la chaîne de tests `kotlin-test` opérationnelle sur les trois cibles, et le dossier `docs/tasks/` prêt à recevoir les fichiers de suivi des tâches suivantes.

À la fin de cette tâche, `./gradlew check` doit passer (sans test puisque les modules sont vides) et un test bidon par module/target doit s'exécuter avec succès pour valider que la chaîne est fonctionnelle.

## Depends on

- (aucune) — première tâche

## Inputs

- `PLAN.md` à la racine du repo (`/home/glandais/code/perso/vcyclist-all/PLAN.md`) — référence d'ensemble
- `kotlin-wasm-jvm-webp.md` à la racine du repo — guide interop Wasm/JVM/WebP
- Versions cibles (à figer ici, propagées à toutes tâches) :
  - Gradle **8.10+**
  - Kotlin **2.1.0** (ou plus récent stable)
  - JDK **21** (toolchain)
  - `kotlinx-coroutines-core` **1.10.2**
  - `kotlinx-browser` **0.3**
  - TwelveMonkeys `imageio-webp` **3.12.0** (sera utilisé en Phase 1, déclaré dès maintenant pour `:elevation`)

## Steps

### 1. Créer la structure de dossiers

```
vcyclist/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
├── gradle/wrapper/
├── gradlew, gradlew.bat
├── docs/
│   ├── PLAN.md                       # copie de la racine
│   ├── kotlin-wasm-jvm-webp.md       # copie de la racine
│   └── tasks/
│       └── 00-bootstrap.md           # ce fichier, déplacé ici
├── elevation/
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/kotlin/io/github/glandais/elevation/.gitkeep
│       ├── commonTest/kotlin/io/github/glandais/elevation/SmokeTest.kt
│       ├── jvmMain/kotlin/.gitkeep
│       ├── jsMain/kotlin/.gitkeep
│       └── wasmJsMain/kotlin/.gitkeep
└── engine/
    ├── build.gradle.kts
    └── src/<idem structure que elevation>
```

### 2. `settings.gradle.kts` (racine `vcyclist/`)

```kotlin
rootProject.name = "vcyclist"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":elevation", ":engine")
```

### 3. `gradle/libs.versions.toml` (catalogue versions centralisé)

```toml
[versions]
kotlin = "2.1.0"
coroutines = "1.10.2"
kotlinx-browser = "0.3"
imageio-webp = "3.12.0"
ktlint = "12.1.1"

[libraries]
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-browser = { module = "org.jetbrains.kotlinx:kotlinx-browser", version.ref = "kotlinx-browser" }
imageio-webp = { module = "com.twelvemonkeys.imageio:imageio-webp", version.ref = "imageio-webp" }

[plugins]
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
ktlint = { id = "org.jlleitschuh.gradle.ktlint", version.ref = "ktlint" }
```

### 4. `build.gradle.kts` racine

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.ktlint)
}

allprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}
```

### 5. `elevation/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)

    jvm()

    js(IR) {
        nodejs()
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        binaries.executable()
        generateTypeScriptDefinitions()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation(libs.imageio.webp)
        }
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
    }
}
```

### 6. `engine/build.gradle.kts`

Identique à `elevation/build.gradle.kts` **sans** `imageio-webp` (réservé à `:elevation`), **plus** une dépendance `api(project(":elevation"))` dans `commonMain`. Décommenter cette dépendance dès la Phase 2 (laissée commentée ici pour permettre la compilation à vide).

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)
    jvm()
    js(IR) { nodejs() }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser { testTask { useKarma { useChromeHeadless() } } }
        binaries.executable()
        generateTypeScriptDefinitions()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            // api(project(":elevation")) // activé en Phase 2
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
    }
}
```

### 7. `gradle.properties`

```
kotlin.code.style=official
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
kotlin.mpp.stability.nowarn=true
```

### 8. Smoke test par module (à supprimer dès la première tâche de Phase 1 / 2)

`elevation/src/commonTest/kotlin/io/github/glandais/elevation/SmokeTest.kt` :

```kotlin
package io.github.glandais.elevation

import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun `compile and run`() {
        assertEquals(2, 1 + 1)
    }
}
```

Idem pour `:engine` dans `engine/src/commonTest/kotlin/io/github/glandais/engine/SmokeTest.kt`.

### 9. Wrapper Gradle

Depuis la racine `vcyclist/` :

```bash
gradle wrapper --gradle-version 8.10
```

(Ou copier un wrapper depuis un autre projet récent et ajuster la version.)

### 10. Lint

Ktlint appliqué globalement via `allprojects`. Lancer `./gradlew ktlintCheck` doit passer (rien à formater puisque code minimal).

### 11. Copie des références dans `docs/`

```bash
cp /home/glandais/code/perso/vcyclist-all/PLAN.md vcyclist/docs/PLAN.md
cp /home/glandais/code/perso/vcyclist-all/kotlin-wasm-jvm-webp.md vcyclist/docs/kotlin-wasm-jvm-webp.md
mv /home/glandais/code/perso/vcyclist-all/00-bootstrap.md vcyclist/docs/tasks/00-bootstrap.md
```

### 12. CI minimal (optionnel mais recommandé)

`.github/workflows/check.yml` :

```yaml
name: check
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: 21, distribution: temurin }
      - uses: gradle/actions/setup-gradle@v4
      - run: cd vcyclist && ./gradlew check
```

## Outputs

Fichiers créés à la fin de la tâche :

- `vcyclist/settings.gradle.kts`
- `vcyclist/build.gradle.kts`
- `vcyclist/gradle.properties`
- `vcyclist/gradle/libs.versions.toml`
- `vcyclist/gradle/wrapper/gradle-wrapper.{jar,properties}`
- `vcyclist/gradlew`, `vcyclist/gradlew.bat`
- `vcyclist/elevation/build.gradle.kts`
- `vcyclist/elevation/src/commonTest/kotlin/io/github/glandais/elevation/SmokeTest.kt`
- `vcyclist/elevation/src/{commonMain,jvmMain,jsMain,wasmJsMain}/kotlin/.gitkeep`
- `vcyclist/engine/build.gradle.kts`
- `vcyclist/engine/src/commonTest/kotlin/io/github/glandais/engine/SmokeTest.kt`
- `vcyclist/engine/src/{commonMain,jvmMain,jsMain,wasmJsMain}/kotlin/.gitkeep`
- `vcyclist/docs/PLAN.md`
- `vcyclist/docs/kotlin-wasm-jvm-webp.md`
- `vcyclist/docs/tasks/00-bootstrap.md` (ce fichier, déplacé)
- `vcyclist/.gitignore` (build/, .gradle/, .idea/, *.iml)
- `.github/workflows/check.yml` (optionnel)

## Validation

Commandes à lancer depuis `vcyclist/` :

```bash
./gradlew build                                  # compile tous modules + cibles
./gradlew :elevation:jvmTest                     # smoke JVM
./gradlew :elevation:jsNodeTest                  # smoke Node
./gradlew :elevation:wasmJsBrowserTest           # smoke Wasm (nécessite Chrome installé)
./gradlew :engine:jvmTest :engine:jsNodeTest :engine:wasmJsBrowserTest
./gradlew ktlintCheck                            # lint OK
```

Critères :

- `./gradlew build` → BUILD SUCCESSFUL
- Chaque `*Test` → 1 test, 0 échec
- `ktlintCheck` → 0 violation
- `.d.ts` produit dans `elevation/build/.../kotlin/elevation.d.ts` (vide ou quasi, mais existant)

## Done when

- [x] Structure de dossiers conforme aux Outputs
- [x] `./gradlew build` vert
- [x] Smoke test passe sur JVM, JS Node, Wasm browser pour les deux modules
- [x] `ktlintCheck` vert
- [x] Fichier `.d.ts` généré pour `:elevation` (validation que `generateTypeScriptDefinitions()` est actif)
- [x] `docs/PLAN.md` et `docs/kotlin-wasm-jvm-webp.md` présents
- [x] Ce fichier déplacé en `vcyclist/docs/tasks/00-bootstrap.md`
- [x] Checkboxes ci-dessus cochées dans le fichier déplacé

## Notes

- **Karma + Chrome headless** : `wasmJsBrowserTest` exige Chrome/Chromium installé. Sur CI, utiliser `setup-chrome` action ou Docker `mcr.microsoft.com/playwright`. En local, accepter de skipper cette cible et indiquer dans le README comment l'activer.
- **`generateTypeScriptDefinitions()`** : statut expérimental côté JetBrains. Si la génération échoue ou produit un fichier vide pour cause de modules sans `@JsExport`, ne pas paniquer — c'est la tâche **28** qui exercera réellement cette feature. Ici on valide juste que le bloc compile.
- **Pas de `:demo`** : volontairement absent à ce stade. Sera ajouté en Phase 9 dans `settings.gradle.kts`.
- **`api(project(":elevation"))`** : laissé commenté dans `engine/build.gradle.kts` pour permettre une compilation indépendante. À décommenter au début de la Phase 2 (tâche 10).
- **JDK toolchain 21** : choix arbitraire mais raisonnable (LTS récent). Adapter si besoin.
- **Kotlin 2.1.0 minimum** : nécessaire pour le support stable de `wasmJs` + `generateTypeScriptDefinitions`.
- **Versions à figer ici** : toute version listée dans `gradle/libs.versions.toml` devient la référence pour toutes les tâches suivantes. Mise à jour future via une tâche dédiée.
