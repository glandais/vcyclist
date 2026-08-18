# w07 — Publier le `.wasm` : Maven Central et release GitHub

## Goal

Rendre le binaire **récupérable sans compiler le projet**. C'est l'aboutissement du plan :
« publier des wasm utilisables avec wasmtime a minima ».

Les klib `-wasm-wasi` sont déjà publiées gratuitement par vanniktech (validé dans le POC) et ne
sont *pas* le sujet : elles servent aux consommateurs Gradle, pas aux hôtes WASI.

## Depends on

- `w06` (l'artefact et son nom stable).
- `w08` (Kotlin 2.4.20 final) — **avant toute publication réelle**, cf. `kotlin-wasm-wasi.md` §4.

## Inputs

- `build.gradle.kts` racine — bloc `subprojects` où vanniktech est configuré une seule fois.
- `docs/publishing.md` — le flux de release actuel (semantic-release sur `develop`).
- `.github/workflows/release.yml`.
- `docs/kotlin-wasm-wasi.md` §3 — layout Maven KMP, et pourquoi un `.wasm` demande un
  `artifact()` explicite.

## Steps

### 1. Choisir les canaux

| Canal | Coordonnées | Pour qui |
|---|---|---|
| **Release GitHub** | asset `vcyclist-engine.wasm` + `.sha256` sur le tag | tout hôte non-JVM (Python, Go, Rust, CLI) — **canal principal** |
| **Maven Central** | `io.github.glandais:vcyclist-engine:<v>:wasi@wasm` (classifier) | hôtes JVM (Chicory, wasmtime-java) et builds Gradle/Maven |

Faire les deux. Le classifier Maven est peu de travail une fois `wasmModule` en place, et c'est
le seul moyen pour un hôte JVM de récupérer le binaire par une dépendance déclarée.

### 2. Maven Central

Attacher l'artefact à la publication `jvm` (ou à la publication racine `kotlinMultiplatform` —
trancher et documenter) avec un bloc `artifact(tasks.named("wasmModule")) { classifier = "wasi"
; extension = "wasm" }`. Vérifier que :

- la signature GPG s'applique bien à l'artefact ajouté (Central le refuse sinon) ;
- l'ajout ne casse pas la validation des métadonnées Gradle du module ;
- un `publishToMavenLocal` suivi d'une résolution depuis un projet consommateur ramène bien le
  `.wasm`.

### 3. Release GitHub

Étendre `release.yml` : après le succès de semantic-release, construire `:engine:wasmModule` et
téléverser les deux fichiers en assets du tag créé. Ne pas créer de release à part — un tag, une
release, tous les artefacts dedans.

### 4. Documentation de release

Mettre à jour `docs/publishing.md` : ce qui est publié (npm / Maven / assets), depuis quel
workflow, comment vérifier un checksum, et **la politique de version de l'ABI** — `vcAbiVersion`
(w03) évolue indépendamment de la version sémantique du projet ; dire laquelle fait foi pour un
hôte.

## Outputs

- `build.gradle.kts` (ou `engine/build.gradle.kts`) : artefact Maven à classifier.
- `.github/workflows/release.yml` : upload des assets.
- `docs/publishing.md` mis à jour.

## Validation

- [x] `./gradlew publishToMavenLocal` dépose `vcyclist-engine-<v>-wasi.wasm` dans `~/.m2`.
- [x] Un projet consommateur résout cette dépendance et instancie le binaire.
- [ ] Une release de test (ou un `workflow_dispatch` à blanc) attache les deux assets.
- [x] Le sha256 publié correspond au binaire publié.

## Done when

`curl -L https://github.com/glandais/vcyclist/releases/latest/download/vcyclist-engine.wasm` puis
`wasmtime` suffit à exécuter le moteur — aucune compilation.

## Notes

Ne pas publier depuis une Beta du compilateur (`kotlin-wasm-wasi.md` §4) : w08 est un
prérequis dur de la première publication réelle, même si toute la plomberie de cette fiche peut
être écrite et testée en local avant.

## État : plomberie livrée, publication en attente de w08

**Kotlin 2.4.20 n'est pas sortie** — Maven Central s'arrête à `2.4.20-Beta2`, pas même une RC. La
règle de `kotlin-wasm-wasi.md` §4 tient : rien ne se publie depuis une Beta. Cette fiche livre
donc tout sauf le geste final, comme ses propres Notes l'y autorisent. La case restante — une
vraie release attachant les assets — se cochera au premier passage sur `develop` après w08 ; c'est
le seul point que je ne peux pas vérifier sans publier pour de bon.

### Ce qui a été fait

**Maven Central**, sur la publication `kotlinMultiplatform` et non `jvm` : c'est la coordonnée
sans suffixe de cible (`vcyclist-engine`, pas `vcyclist-engine-jvm`), et un hôte qui réclame un
binaire Wasm ne réclame pas une bibliothèque JVM.

```kotlin
implementation("io.github.glandais:vcyclist-engine:<version>:wasi@wasm")
```

**Release GitHub** : les deux fichiers sont déclarés dans les `assets` de
`@semantic-release/github`, à côté du jar CLI. Mon premier jet ajoutait une étape
`gh release upload` après `npx semantic-release` — inutile et moins robuste : `.releaserc.json`
est l'endroit prévu, et il attache les assets au moment où la release est créée. `release.yml`
construit et pèse quand même le binaire **avant** semantic-release, pour qu'un binaire qui ne se
lie pas ou qui dépasse le plafond de w06 arrête le run avant toute publication.

### Les trois vérifications faites en local

1. **`publishToMavenLocal`** dépose bien `vcyclist-engine-3.0.0-wasi.wasm` (300 968 o), et son
   sha256 est identique à celui que w06 publie.
2. **Un consommateur Gradle réel** — projet séparé, `repositories { mavenLocal() }`, dépendance en
   notation artifact-only — récupère le fichier et **l'instancie sous wasmtime-py** (`vcEnhance`
   répond). Ce n'est donc pas seulement un fichier au bon endroit.
3. **La signature GPG s'applique** : avec une clé jetable générée pour l'occasion,
   `vcyclist-engine-3.0.0-wasi.wasm.asc` est produit et `gpg --verify` le valide. Central refuse
   un artefact non signé, et rien ne garantissait *a priori* que `signAllPublications` couvre un
   artefact ajouté à la main.

Vérifié aussi, parce que la fiche le demandait : les **métadonnées Gradle du module ne sont pas
cassées**. Le `.module` reste du JSON valide, ses onze variantes sont intactes, et le `.wasm`
n'apparaît dans aucune d'elles — il n'est atteignable que par classifier explicite, donc un
consommateur ordinaire de `vcyclist-engine` ne le télécharge jamais sans le demander.

### Politique de version, écrite dans `publishing.md`

Deux numéros indépendants, et c'est voulu : `vcAbiVersion` (petit entier, bougé seulement quand
un export casse un hôte) est ce qu'un hôte vérifie ; la version du projet bouge à chaque release,
y compris celles qui ne touchent pas la surface WASI. Un hôte épingle la première dans son code et
la seconde dans son URL de téléchargement.
