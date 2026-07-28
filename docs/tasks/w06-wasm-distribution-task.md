# w06 — Tâche Gradle de distribution du `.wasm`

## Goal

Le binaire sort aujourd'hui sous un chemin de détail d'implémentation
(`engine/build/compileSync/wasmWasi/main/productionExecutable/optimized/*.wasm`), sous un nom
imposé par KGP. Un artefact publiable a besoin d'un **nom stable, d'un emplacement stable et
d'une taille surveillée**.

## Depends on

- `w04` (façade complète — inutile de packager un binaire incomplet).

## Inputs

- `engine/build.gradle.kts` — `binaries.executable()` posé en w01.
- `cli/build.gradle.kts` — la tâche `executableJar`, modèle de tâche de distribution du dépôt.
- `docs/kotlin-wasm-wasi.md` §5 — forme du module, taille de référence.

## Steps

1. **Tâche `:engine:wasmModule`** — une `Copy`/`Sync` qui dépend de la compilation optimisée et
   dépose le binaire en `engine/build/wasm/vcyclist-engine.wasm`. Nom du fichier **sans
   version** : la version vit dans les coordonnées Maven / le tag de release, pas dans le nom
   qu'un hôte code en dur.
2. **Vérifier que c'est bien le binaire optimisé** (passé par binaryen), pas l'intermédiaire.
   Si les deux existent, préférer l'optimisé et l'affirmer par une assertion de taille.
3. **Checksum** — écrire un `vcyclist-engine.wasm.sha256` à côté. Un hôte tiers qui télécharge
   un binaire depuis une release veut pouvoir le vérifier.
4. **Garde-fou de taille** — une petite tâche de vérification qui échoue si le binaire dépasse
   un seuil (par ex. 2 × la taille constatée à la rédaction), avec un message disant quoi faire
   (relever le seuil sciemment, ou chercher la régression). Référence POC : 145 Ko pour `:gpx`
   seul ; noter la valeur réelle de `:engine` au moment de la fiche.
5. **Reproductibilité** — vérifier que deux builds propres successifs donnent le même sha256.
   Si non (timestamps, ordre de DCE), le documenter plutôt que de le combattre : c'est un
   avertissement utile pour w07.
6. **Ne pas brancher `wasmModule` sur `check`** — c'est une tâche de packaging ; `assemble` et
   la CI de release suffisent.

## Outputs

- `engine/build.gradle.kts` : tâches `wasmModule`, `wasmModuleChecksum`, garde-fou de taille.
- Éventuellement une ligne dans `.github/workflows/check.yml` pour construire le binaire à
  chaque PR (détection précoce des régressions de taille).

## Validation

- [ ] `./gradlew :engine:wasmModule` produit `engine/build/wasm/vcyclist-engine.wasm` + `.sha256`.
- [ ] Le binaire s'instancie sous wasmtime-py et répond à `vcAbiVersion`.
- [ ] Le garde-fou de taille échoue si on abaisse artificiellement le seuil.
- [ ] `./gradlew clean :engine:wasmModule` deux fois : sha256 identique, ou écart documenté.

## Done when

Une seule commande Gradle produit le binaire publiable, à un chemin et sous un nom stables.

## Notes

Ne pas versionner le `.wasm` dans git. Il se reconstruit ; un binaire de 150 Ko à chaque commit
alourdit le dépôt pour rien.
