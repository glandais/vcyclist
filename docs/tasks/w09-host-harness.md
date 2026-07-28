# w09 — Harnais d'hôtes de référence (`tools/wasi/`)

## Goal

Le seul test qui prouve que le binaire est *utilisable* est un vrai hôte WASI qui l'exécute. Les
tests `wasmWasiWasmtimeTest` de KGP n'y suffisent pas : ils ne peuvent pas fournir les imports
custom (`kotlin-wasm-wasi.md` §5), donc ils n'exercent jamais la façade.

Cette fiche crée le harnais qui manque, et le branche sur la CI.

## Depends on

- `w04` (façade), `w06` (binaire à un chemin stable). Utile dès `w03` en version minimale.

## Inputs

- `docs/kotlin-wasm-wasi.md` §7 — l'hôte Python déjà validé (wasmtime-py 47.0.1), à reprendre.
- `tools/parity/` — le modèle de harnais du dépôt (`run-all.sh`, README).
- `demo/public/gpx/stelvio.gpx` — la trace de référence, déjà utilisée par le smoke CLI.
- `engine/src/commonTest/…/parity/ParityFixtures.kt` — les valeurs attendues du pipeline.

## Steps

### 1. `tools/wasi/` — hôte Python

Structurer le script du §7 en quelque chose de maintenable :

- `host.py` — la classe d'hôte réutilisable : config des trois proposals, `Linker`, les imports
  `read_input` / `write_output` / `fetch_tile`, et des méthodes Python confortables autour des
  exports (`parse_gpx(bytes) -> handle`, `enhance(handle, options: dict) -> handle`, …).
- `test_engine.py` — les assertions : round-trip GPX, taille, distance, `enhance` comparé aux
  métriques de `ParityFixtures` à 0,5 %, cycle de vie des handles, chemin d'erreur
  (`vcLastError`), `vcAbiVersion`.
- `requirements.txt` — `wasmtime>=47`, et `Pillow` pour le décodage de tuiles de w05.
- `run-all.sh` — construit le binaire (`./gradlew :engine:wasmModule`) puis lance les tests, à
  la manière de `tools/parity/run-all.sh`.
- `README.md` — comment lancer, ce qui est couvert.

### 2. Smoke wasmtime CLI

Un test supplémentaire, très court, qui ne dépend d'aucun binding : `wasmtime` en ligne de
commande doit au minimum **valider et instancier** le module. Sans hôte pour les imports custom,
l'instanciation échouera sur les imports manquants — c'est attendu ; ce qu'on vérifie ici c'est
que le message d'erreur est bien « import manquant » et non « proposal non supporté » ou
« module invalide ». Cela attrape les régressions de compatibilité runtime, qui sont le vrai
risque.

Documenter le fait que le wasmtime utilisé est celui de `~/.gradle/wasmtime/`, pour ne pas
tester une version différente de celle de la CI.

### 3. CI

Ajouter une étape à `check.yml` : `:engine:wasmModule` + `tools/wasi/run-all.sh`. Python est
présent sur `ubuntu-latest` ; épingler la version de `wasmtime-py` dans `requirements.txt`.

Les tests réseau (élévation, w05) restent derrière `INTEGRATION=1`, comme partout dans le
projet.

## Outputs

- `tools/wasi/{host.py,test_engine.py,requirements.txt,run-all.sh,README.md}`.
- `.github/workflows/check.yml` : étape harnais WASI.

## Validation

- [ ] `./tools/wasi/run-all.sh` vert depuis un arbre propre.
- [ ] Les métriques `enhance` tiennent dans 0,5 % des références JVM.
- [ ] Le smoke wasmtime CLI échoue avec « missing import », pas autre chose.
- [ ] La CI exécute le harnais et échoue si un export est renommé.

## Done when

Une régression de l'ABI ou du binaire fait rougir la CI, sans intervention manuelle.

## Notes

Le harnais est aussi la **documentation exécutable** de l'ABI : w10 pointe dessus plutôt que de
recopier du code Python dans un markdown qui divergera.
