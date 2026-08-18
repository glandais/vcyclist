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

- [x] `./gradlew :engine:wasmModule` produit `engine/build/wasm/vcyclist-engine.wasm` + `.sha256`.
- [x] Le binaire s'instancie sous wasmtime-py et répond à `vcAbiVersion`.
- [x] Le garde-fou de taille échoue si on abaisse artificiellement le seuil.
- [x] `./gradlew clean :engine:wasmModule` deux fois : sha256 identique, ou écart documenté.

## Done when

Une seule commande Gradle produit le binaire publiable, à un chemin et sous un nom stables.

## Notes

Ne pas versionner le `.wasm` dans git. Il se reconstruit ; un binaire de 150 Ko à chaque commit
alourdit le dépôt pour rien.

### Ce qui s'est passé

Trois tâches dans `engine/build.gradle.kts`, aucune surprise :

- **`wasmModule`** (`Copy`, groupe `distribution`) dépose `build/wasm/vcyclist-engine.wasm` et
  écrit le `.sha256` à côté, au format de `sha256sum` — `<sha>  <fichier>`, deux espaces — pour
  que `sha256sum -c` l'avale tel quel. Vérifié.
- **`checkWasmModuleSize`** échoue au-delà de **600 000 octets**, soit environ deux fois la taille
  du jour, avec un message qui dit quoi faire : chercher ce qui a élargi le graphe joignable, ou
  relever le seuil sciemment. Surchargeable par `-PwasmSizeLimit=…` pour une expérience ponctuelle
  — c'est d'ailleurs comme ça que la validation « le garde-fou échoue » a été faite.
- `assemble` dépend de `wasmModule` ; `check` non, comme demandé.

La source est bien le répertoire `optimized/` et non le `kotlin/` voisin, qui contient le module
**pré-binaryen** — 994 010 o contre 300 968 o, soit **3,3 fois** le poids pour un binaire qui
marche exactement pareil. C'est le genre d'erreur qui ne se voit qu'en pesant l'asset d'une release, d'où
le commentaire explicite à l'endroit du `from(...)`.

| | Taille |
|---|---|
| `productionExecutable/kotlin/` (pré-binaryen) | 994 010 o |
| **`optimized/` (publié)** | **300 968 o** |
| Plafond | 600 000 o |

**Reproductibilité : oui.** Deux `clean` + `wasmModule` successifs donnent le même sha256
(`c64f935b…`). Rien à documenter comme écart, et c'est une bonne nouvelle pour w07 : le checksum
publié dans une release est vérifiable par quiconque reconstruit le tag.

Le smoke wasmtime-py passe sur le binaire *distribué* (et non sur celui du répertoire de build) —
avec une remarque qui vaut pour w10 : le script de w03 **n'arrive plus à instancier le module**,
parce que depuis w05 les imports sont trois et qu'il n'en fournit que deux. Les trois sont
obligatoires, y compris pour un hôte qui ne corrigera jamais d'altitude ; `fetch_tile` peut se
contenter de retourner `0`.

La CI construit et pèse le binaire à chaque PR (`checkWasmModuleSize` dans `check.yml`), en dehors
de `check`. Une régression de taille vient de ce que les exports atteignent, donc elle apparaît au
moment où quelqu'un élargit ce graphe — bien moins cher à voir là qu'au moment de publier.
