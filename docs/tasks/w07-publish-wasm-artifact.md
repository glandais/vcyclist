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

- [ ] `./gradlew publishToMavenLocal` dépose `vcyclist-engine-<v>-wasi.wasm` dans `~/.m2`.
- [ ] Un projet consommateur résout cette dépendance et instancie le binaire.
- [ ] Une release de test (ou un `workflow_dispatch` à blanc) attache les deux assets.
- [ ] Le sha256 publié correspond au binaire publié.

## Done when

`curl -L https://github.com/glandais/vcyclist/releases/latest/download/vcyclist-engine.wasm` puis
`wasmtime` suffit à exécuter le moteur — aucune compilation.

## Notes

Ne pas publier depuis une Beta du compilateur (`kotlin-wasm-wasi.md` §4) : w08 est un
prérequis dur de la première publication réelle, même si toute la plomberie de cette fiche peut
être écrite et testée en local avant.
