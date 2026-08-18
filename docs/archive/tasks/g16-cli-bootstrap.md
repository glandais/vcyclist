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

- [x] Module `:cli` créé, JVM-only
- [x] Annotations picocli fonctionnelles en Kotlin
- [x] 4 mixins avec conversion vers les modèles du moteur
- [x] Défauts tirés de `EngineConstants`, testés par assertion croisée
- [x] Noms d'options alignés sur gpxtools-cli
- [x] Jar exécutable produit et exécuté seul, mode de distribution tranché
- [x] 15 tests verts
- [x] `./gradlew check` et `ktlintCheck` verts

## Résultat

**Annotations picocli en Kotlin : le piège annoncé, réglé d'emblée.** Il faut la cible de site
`@field:CommandLine.Option` — sans elle l'annotation atterrit sur la propriété et picocli ne voit
rien. Les valeurs par défaut passent par l'initialiseur du champ, et `\${DEFAULT-VALUE}` dans la
description les affiche dans l'aide, donc elles ne sont écrites qu'une fois.

**`picocli-codegen` volontairement non branché.** Il sert à produire les métadonnées de réflexion
pour une image native GraalVM, que ce projet ne construit pas ; picocli fonctionne par réflexion
sans lui. Ajouter kapt ou KSP au build coûterait du temps de compilation à tout le monde, pour
toujours, au bénéfice de personne. (La fiche autorisait explicitement ce choix.)

**Défauts : source unique, et un test qui le prouve.** Toutes les valeurs viennent
d'`EngineConstants`. Le cas 11 les compare champ par champ, et les cas 3 et 6 vérifient
qu'un CLI sans option construit **exactement** `Cyclist()` et `Bike()` de la bibliothèque. Sans
ça, une même commande donnerait des résultats différents selon qu'elle passe par le CLI ou par
l'API.

**Deux défauts divergent volontairement de gpxtools-cli** — noms d'options identiques, valeurs
différentes, la bibliothèque l'emporte. **À reporter dans la matrice g20 :**

| Option | gpxtools-cli | vcyclist | Source |
|---|---|---|---|
| `--cyclist-max-angle` | 45° | **35°** | `DEFAULT_MAX_LEAN_ANGLE_DEG` |
| `--cyclist-max-speed` | 90 km/h | **100 km/h** | `DEFAULT_MAX_SPEED_KMH` |

Figés par le cas 12 pour que ça reste une décision et non une dérive.

**`--cyclist-power` ne va pas dans `Cyclist`.** gpx2web embarque la puissance dans son `Cyclist` ;
côté vcyclist c'est une stratégie (`CyclistPowerProvider`). Le mixin expose donc `toCyclist()` et
`toPowerProvider()` séparément.

**`FilesMixin` : deux écarts assumés.** La référence journalise et appelle `System.exit` depuis le
mixin — un porteur de paramètres capable de tuer le processus est intestable et surprenant. Ici
`collectGpxFiles` **retourne** la liste et signale les fichiers ignorés via un callback, au
choix de l'appelant. Et le parcours de dossier est **trié par nom** : `listFiles` ne garantit
aucun ordre, donc la référence traite les fichiers dans un ordre dépendant du système.

**Distribution tranchée : `:cli` ne va pas sur Maven Central.** C'est une application, pas une
bibliothèque — personne ne devrait compiler contre un outil en ligne de commande. La tâche
`:cli:executableJar` produit un jar autonome de 7,5 Mo, **vérifié exécuté seul**
(`java -jar … --version` → `vcyclist 1.2.1`, `--help` correct), destiné à être attaché à une
release GitHub. Aucune publication n'a été ajoutée au `publishCmd` de `.releaserc.json`. **À
confirmer en g19.**

La version affichée est lue depuis un fichier de propriétés généré par le build plutôt que depuis
le manifeste du jar, pour qu'elle soit correcte aussi en exécution depuis un classpath —
`./gradlew :cli:run` et les tests compris. Le cas 2 échoue si le câblage casse, plutôt que de
laisser passer un `unknown` jusqu'à la release.

**Validation :** `./gradlew check` + `ktlintCheck` verts. `:cli` = 15 tests, aucune I/O réseau.
`--help` et `--version` vérifiés via Gradle **et** via le jar autonome.

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
