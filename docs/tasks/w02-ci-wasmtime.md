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

- [ ] Un run de CI vert, avec `:engine:wasmWasiWasmtimeTest` visible dans le log.
- [ ] Un second run montre un cache hit sur wasmtime (log `Cache restored`).
- [ ] Aucun autre workflow modifié sans raison.

## Done when

La CI exécute les tests WASI de façon stable, sans re-télécharger wasmtime à chaque job.

## Notes

Ne pas installer wasmtime via `apt`/`brew`/une action tierce : KGP veut *sa* version dans *son*
dossier, et une version hôte divergente donnerait des verts locaux et des rouges en CI (ou
l'inverse) sur les proposals GC/exnref.
