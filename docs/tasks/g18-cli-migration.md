# g18 — Retrait d'`EngineCli` et documentation du CLI

## Goal

Faire de `:cli` le point d'entrée unique en ligne de commande : retirer `EngineCli` de
`:engine`, mettre à jour toute la documentation, et vérifier qu'un utilisateur de
`gpxtools-cli` peut basculer sans perte.

## Depends on

- `g16`, `g17` (`:cli` complet)

## Inputs

- `engine/src/jvmMain/…/EngineCli.kt` et `engine/src/jvmTest/…/EngineCliSmokeTest.kt`
- `engine/build.gradle.kts` (tâche `run`)
- `README.md`, `CLAUDE.md`, `docs/publishing.md`
- `../gpx2web/README.md` et `../gpx2web/gpxtools-cli/**` (surface à couvrir)

## Steps

### 1. Vérifier la couverture avant de supprimer

Établir, option par option, que `:cli` couvre `EngineCli` **et** `gpxtools-cli`. Toute
divergence est soit comblée, soit inscrite dans la table de correspondance avec sa raison.

Ne rien supprimer avant que cette table soit complète : c'est elle qui justifie la suppression.

### 2. Retirer `EngineCli`

- Supprimer `EngineCli.kt` et `EngineCliSmokeTest.kt`.
- Retirer la tâche `run` de `engine/build.gradle.kts` (ou la rediriger vers `:cli`).
- `FullPipelineSmokeTest` (jvmTest de `:engine`) ne dépend pas d'`EngineCli` — vérifier et le
  conserver, c'est lui qui garde la couverture du pipeline complet côté `:engine`.

Si un test de `:engine` dépendait d'`EngineCli`, le déplacer vers `:cli` plutôt que le
supprimer.

### 3. Documentation

- **`README.md`** — section « Quick start » : remplacer `./gradlew :engine:run -Pargs="…"` par
  l'invocation `:cli`. Mettre à jour le tableau des modules avec `:gpx`, `:fit`, `:map`, `:cli`.
- **`CLAUDE.md`** — sections « Project overview », « Build commands », « Where to find things ».
  La ligne « How to run the CLI ? » doit pointer vers `cli/README.md`.
- **`docs/publishing.md`** — mode de distribution du jar exécutable.
- **`cli/README.md`** — table de correspondance complète :

| Commande gpxtools-cli | Équivalent vcyclist | Note |
|---|---|---|
| `process --csv` | `enhance --csv` | |
| `process --xlsx` | — | non porté, utiliser `--csv` |
| `virtualize --start-date` | `enhance --start-time` | nom d'option changé |
| `export --fit` | `export --fit` | |
| … | | |

### 4. Distribution

Confirmer le mode retenu en g16 (jar exécutable en artefact de release GitHub) et vérifier
qu'il fonctionne réellement :

```bash
./gradlew :cli:<tâche de packaging>
java -jar cli/build/libs/<jar> enhance sample.gpx -o /tmp/out.gpx
```

Un jar qui se construit mais ne s'exécute pas (classe principale absente, dépendances non
incluses) est le mode d'échec classique.

### 5. Intégration CI

Ajouter au workflow existant une étape qui construit le jar et exécute au moins
`--help` et un `enhance` sur une fixture. Sans ça, le CLI casse silencieusement à la première
évolution du moteur.

## Outputs

Supprimés :

- `engine/src/jvmMain/…/EngineCli.kt`
- `engine/src/jvmTest/…/EngineCliSmokeTest.kt`

Modifiés :

- `engine/build.gradle.kts`
- `README.md`, `CLAUDE.md`, `docs/publishing.md`
- `cli/README.md` (table de correspondance)
- `.github/workflows/**`

## Validation

```bash
./gradlew check
./gradlew ktlintCheck
./gradlew :cli:<packaging> && java -jar cli/build/libs/<jar> --help
java -jar cli/build/libs/<jar> enhance ../virtual-cyclist/gpx/sample.gpx -o /tmp/out.gpx
```

Critères :

- Plus aucune référence à `EngineCli` dans le dépôt (`grep -r EngineCli` vide, hors
  `CHANGELOG.md` et `docs/PLAN.md` qui sont historiques).
- Le jar s'exécute depuis un répertoire quelconque.
- Table de correspondance complète : chaque commande et option de `gpxtools-cli` a une ligne.

## Done when

- [ ] Couverture de `gpxtools-cli` et d'`EngineCli` établie option par option
- [ ] `EngineCli` et son test supprimés, tâche `run` de `:engine` retirée ou redirigée
- [ ] `FullPipelineSmokeTest` préservé
- [ ] `README.md`, `CLAUDE.md`, `docs/publishing.md` à jour
- [ ] `cli/README.md` avec la table de correspondance complète
- [ ] Jar exécutable vérifié à la main
- [ ] Étape CI ajoutée
- [ ] `./gradlew check` et `ktlintCheck` verts

## Notes

- **Commit `feat!:`** : la suppression d'`EngineCli` retire un point d'entrée public. Même si
  son usage réel est probablement nul, c'est formellement une rupture — semantic-release doit
  passer en majeure, ou bien il faut le documenter comme non-rupture assumée. Trancher au
  moment du commit, avec les autres ruptures éventuelles de la phase H.
- **Ne pas supprimer avant d'avoir la table** : c'est elle qui prouve que rien n'est perdu, et
  elle alimente directement g20.
- **Le CI est ce qui empêche la régression silencieuse** : le CLI est le seul consommateur qui
  traverse tous les modules d'un coup, donc c'est un excellent test d'intégration.
