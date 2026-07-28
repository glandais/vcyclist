# w02 — CI : exécuter les tests wasmtime et mettre wasmtime en cache

## Goal

`wasmWasiWasmtimeTest` entre dans `check` dès que la cible est déclarée (w01), donc la CI
l'exécute déjà — mais elle re-télécharge wasmtime (~46.0.1, plusieurs dizaines de Mo) à chaque
job. Cette fiche rend ça explicite, rapide et diagnosticable.

## Depends on

- `w01` (la cible sur les quatre modules).

## Inputs

- `.github/workflows/check.yml` — le job existant (`./gradlew check` + smoke du jar CLI).
- `.github/workflows/release.yml`, `.github/workflows/gh-pages.yml` — les autres consommateurs
  de Gradle, à ne pas casser.
- `docs/kotlin-wasm-wasi.md` §1 — `kotlinWasmWasmtimeSetup` télécharge dans `~/.gradle/wasmtime/`.

## Steps

1. **Cache** — ajouter `~/.gradle/wasmtime/` au cache de `gradle/actions/setup-gradle` (option
   `cache-extra-paths`, ou une étape `actions/cache` dédiée si l'action ne le permet pas). Clé
   incluant la version de Kotlin : c'est KGP qui choisit la version de wasmtime.
2. **Mesurer** — noter dans le workflow, en commentaire, le delta de durée du job avec/sans
   cache. Si le gain est sous ~20 s, retirer le cache plutôt que d'entretenir de la complexité.
3. **Lisibilité de l'échec** — vérifier qu'un test wasmWasi rouge produit un rapport exploitable
   (le runner est un `KotlinJsTest`, le rapport HTML est sous
   `<module>/build/reports/tests/wasmWasiWasmtimeTest/`). Uploader ces rapports en artefact de
   job si ce n'est pas déjà fait pour les autres cibles.
4. **Release** — confirmer que `release.yml` ne publie pas encore le `.wasm` (c'est w07) et
   qu'aucune tâche `wasmWasi*` n'y ralentit inutilement la publication.

## Outputs

- `.github/workflows/check.yml` mis à jour.

## Validation

- [x] Un run de CI vert, avec `:engine:wasmWasiWasmtimeTest` visible dans le log.
- [x] Un second run montre un cache hit sur wasmtime (log `Cache restored`).
- [x] Aucun autre workflow modifié sans raison.

## Done when

La CI exécute les tests WASI de façon stable, sans re-télécharger wasmtime à chaque job.

## Notes

Ne pas installer wasmtime via `apt`/`brew`/une action tierce : KGP veut *sa* version dans *son*
dossier, et une version hôte divergente donnerait des verts locaux et des rouges en CI (ou
l'inverse) sur les proposals GC/exnref.

### Ce qui s'est passé

Rien à faire pour *exécuter* les tests : `wasmWasiWasmtimeTest` entre dans `check` dès que la
cible est déclarée (`./gradlew check --dry-run` liste les quatre modules), donc la CI les jouait
déjà. Les trois vraies décisions :

**1. Cache.** `gradle/actions/setup-gradle@v6` n'a **pas** de `cache-extra-paths` (vérifié dans
son `action.yml`). Deux options restaient : `gradle-home-cache-includes: caches / notifications /
wasmtime` — le dossier est bien sous `$GRADLE_USER_HOME` — ou une entrée `actions/cache` dédiée.
La seconde a été retenue, pour une raison de clé : c'est KGP qui choisit la version de wasmtime,
donc la clé doit suivre la version de Kotlin (`hashFiles('gradle/libs.versions.toml')`), ce que le
cache de Gradle Home ne fait pas. Bonus : l'étape est lisible dans le log, ce qui compte pour le
point 2.

**2. Mesure.** Le téléchargement à froid coûte **2,5 s en local** (`mv ~/.gradle/wasmtime` puis
`kotlinWasmWasmtimeSetup` chronométré) pour 64 Mo déballés — 16 Mo une fois compressés dans le
cache.

| Run | wasmtime | Étape cache | `./gradlew check` |
|---|---|---|---|
| PR #37, deux runs partis en parallèle | `Cache not found` | ~0 s | **239 s**, **241 s** |
| PR #38, premier run | `Cache not found` | ~1 s | **229 s** |
| PR #38, second run | **`Cache restored`** | **2 s** | **167 s** |

Le cache est donc conservé. Attention à ne pas lire ces 60 s de gain comme le coût du
téléchargement de wasmtime : entre le premier et le second run d'une même PR, les caches de
`setup-gradle` se réchauffent aussi. Ce qui est propre à cette étape-ci, c'est 2 s de
restauration contre un téléchargement que KGP mesure à 2,5 s en local — autant dire un
match nul, et c'est bien pour ça que la ligne suivante compte plus que celle-ci.

**2 bis. Le cloisonnement des caches, trouvé en route.** Le run suivant a réaffiché
`Cache not found` alors que le précédent venait d'écrire *la même clé*. Ce n'est pas la clé : les
caches GitHub sont **cloisonnés par ref**, et un run `pull_request` écrit dans le scope de sa PR.
Ce run-là appartenait à une autre PR (la première ayant été fermée par la suppression de sa base
`next`), donc il ne voyait rien. Conséquence structurelle, indépendante de l'incident : comme
`check.yml` ne se déclenche **que** sur `pull_request`, l'entrée n'est jamais écrite sur la
branche par défaut — seule une PR *déjà passée une fois* en profite. Toute PR neuve part à froid.
C'est le vrai argument contre ce cache, bien plus que les 2,5 s.

**3. Rapports.** Aucun workflow n'uploadait de rapport de test. Un `actions/upload-artifact@v7`
en `if: failure()` sur `*/build/reports/tests/**` couvre toutes les cibles d'un seul motif —
`wasmWasiWasmtimeTest` étant un `KotlinJsTest`, son rapport atterrit au même endroit que les
autres.

**4. Autres workflows.** `release.yml` lance `./gradlew check` : il exécute donc désormais la
suite WASI avant de publier, ce qui est voulu. Il ne publie aucun `.wasm` (c'est w07) et aucune
tâche `wasmWasi*` supplémentaire n'y traîne. `gh-pages.yml` ne fait que `:demo:assemble`.
Ni l'un ni l'autre n'est modifié : le cache est en sursis, on ne le duplique pas avant de savoir
s'il survit.
