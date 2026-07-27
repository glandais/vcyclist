# g16 — Module `:cli` : bootstrap picocli et mixins

## Goal

Créer le module JVM-only `:cli` sous picocli et y porter la structure de `gpxtools-cli` :
commande racine, mixins de paramètres partagés (cycliste, vélo, vent, fichiers).

Cette tâche pose la charpente ; les sous-commandes arrivent en g17.

## Depends on

- `g01` (module `:gpx`)

Les sous-commandes de g17 dépendront en plus de g05, g06, g07, g10 et g13-g15.

## Inputs

Dans `../gpx2web/gpxtools-cli/src/main/java/io/github/glandais/` :

- `RootCommand.java`
- `BikeMixin.java`, `CyclistMixin.java`, `FilesMixin.java`
- `CacheFolderProviderImpl.java`

Côté vcyclist :

- `engine/src/jvmMain/…/EngineCli.kt` (à remplacer en g18)
- `engine/src/commonMain/…/{Cyclist,Bike,EngineConstants}.kt` (valeurs par défaut)

## Steps

### 1. Créer le module

`settings.gradle.kts` : ajouter `":cli"`.

`gradle/libs.versions.toml` :

```toml
[versions]
picocli = "4.7.7"

[libraries]
picocli = { module = "info.picocli:picocli", version.ref = "picocli" }
picocli-codegen = { module = "info.picocli:picocli-codegen", version.ref = "picocli" }
```

`cli/build.gradle.kts` : plugin `kotlin-jvm`, `jvmToolchain(21)`.

```kotlin
dependencies {
    implementation(project(":engine"))   // tire :gpx et :elevation en transitif
    implementation(project(":fit"))
    implementation(project(":map"))
    implementation(libs.picocli)
    kapt(libs.picocli.codegen)   // ou KSP — voir Notes
}
```

**Ne pas** publier `:cli` sur Maven Central comme bibliothèque : c'est un exécutable. Produire
un jar exécutable (`Jar` avec `Main-Class` et dépendances, ou plugin shadow) et le publier en
artefact de release GitHub. À trancher dans cette tâche, à documenter pour g19.

### 2. Annotations picocli en Kotlin

picocli fonctionne par annotations sur des champs. En Kotlin, les champs annotés doivent être
`lateinit var` ou avoir une valeur par défaut, et l'annotation doit viser le bon site
(`@field:CommandLine.Option`). C'est le piège de démarrage : le régler d'emblée sur un cas
simple avant d'écrire tous les mixins.

### 3. Mixins

`cli/src/main/kotlin/io/github/glandais/cli/mixin/` :

- **`CyclistMixin`** — masse, puissance, harmoniques, freinage max (g), Cd, surface frontale,
  angle d'inclinaison max, vitesse max.
- **`BikeMixin`** — Crr, inerties avant/arrière, rayon de roue, rendement.
- **`WindMixin`** — vitesse, direction en degrés (0 = nord, sens horaire).
- **`FilesMixin`** — fichiers d'entrée, répertoire de sortie, dossier de cache.

Chaque mixin expose une méthode de conversion vers le modèle du moteur :

```kotlin
fun toCyclist(): Cyclist
fun toBike(): Bike
fun toWindProvider(): WindProvider
```

**Les valeurs par défaut viennent de `EngineConstants`**, pas de constantes recopiées dans le
CLI. Une divergence entre le défaut du CLI et celui de la bibliothèque est un bug garanti.

Reprendre les noms d'options de gpxtools-cli quand ils existent : c'est ce qui permet à un
utilisateur de gpx2web de basculer sans réapprendre.

### 4. Commande racine

`RootCommand` avec `mixinStandardHelpOptions`, version lue depuis les propriétés du build,
et les sous-commandes déclarées (vides à ce stade, remplies en g17).

### 5. Point d'entrée et tâche Gradle

```kotlin
tasks.register<JavaExec>("run") { … }   // même schéma que engine/build.gradle.kts
```

Plus une tâche produisant le jar exécutable.

### 6. Tests

picocli permet de tester le parsing sans exécuter : instancier la commande, appeler
`CommandLine.parseArgs`, vérifier les champs. Aucune I/O nécessaire.

## Outputs

Créés :

- `cli/build.gradle.kts`
- `cli/src/main/kotlin/io/github/glandais/cli/RootCommand.kt`
- `cli/src/main/kotlin/io/github/glandais/cli/mixin/{CyclistMixin,BikeMixin,WindMixin,FilesMixin}.kt`
- `cli/src/test/kotlin/io/github/glandais/cli/MixinParsingTest.kt`

Modifiés :

- `settings.gradle.kts`, `gradle/libs.versions.toml`, `README.md`

## Validation

```bash
./gradlew :cli:test
./gradlew :cli:run -Pargs="--help"
./gradlew check
./gradlew ktlintCheck
```

Cas de test (≥ 10) :

| # | Cas | Attendu |
|---|---|---|
| 1 | `--help` | sortie non vide, code 0 |
| 2 | `--version` | version du build |
| 3 | Mixin cycliste sans option | défauts égaux à `EngineConstants` |
| 4 | `--weight 75` | `toCyclist().massKg == 75.0` |
| 5 | Toutes les options cycliste | toutes reportées |
| 6 | Toutes les options vélo | idem |
| 7 | Vent : vitesse + direction | `WindProvider` construit correctement |
| 8 | Vent absent | `WindProviderNone` |
| 9 | Option inconnue | erreur picocli, code de sortie non nul |
| 10 | Valeur invalide (`--weight abc`) | erreur explicite |
| 11 | Défauts CLI = défauts bibliothèque | assertion croisée sur `EngineConstants` |

Le cas 11 est celui qui protège contre la dérive.

## Done when

- [ ] Module `:cli` créé, JVM-only
- [ ] Annotations picocli fonctionnelles en Kotlin
- [ ] 4 mixins avec conversion vers les modèles du moteur
- [ ] Défauts tirés de `EngineConstants`, testés par assertion croisée
- [ ] Noms d'options alignés sur gpxtools-cli
- [ ] Jar exécutable produit, mode de distribution tranché
- [ ] ≥ 10 tests verts
- [ ] `./gradlew check` et `ktlintCheck` verts

## Notes

- **kapt ou KSP** : `picocli-codegen` sert à générer les métadonnées de réflexion (utile pour
  GraalVM natif). Si ni kapt ni KSP n'est déjà configuré dans le dépôt, s'en passer : picocli
  fonctionne sans, par réflexion. Ne pas introduire un processeur d'annotations pour une
  optimisation dont on n'a pas besoin.
- **Ne pas recopier les défauts** : `EngineConstants` est la source unique.
- **Compatibilité des noms d'options** avec gpxtools-cli : c'est ce qui rend la migration
  indolore. Là où un nom diverge, le consigner pour la matrice g20.
- `:cli` ne va pas sur Maven Central en tant que bibliothèque — décision à confirmer et à
  reporter dans g19.
