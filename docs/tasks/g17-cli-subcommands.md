# g17 — `:cli` : sous-commandes `process`, `virtualize`, `export`

## Goal

Porter les trois sous-commandes de `gpxtools-cli` en réutilisant les briques des phases C, D
et F. À l'issue de cette tâche, `:cli` couvre l'ensemble des usages de `gpxtools-cli`.

## Depends on

- `g16` (charpente picocli et mixins)
- `g05` (`--start-time`), `g06` (CSV), `g07` (JSON)
- `g10` (export FIT)
- `g13`, `g14`, `g15` (cartes)

## Inputs

Dans `../gpx2web/gpxtools-cli/src/main/java/io/github/glandais/` :

- `process/ProcessCommand.java` (options : `--csv`, `--xlsx`, élévation valide, puissance
  valide, puissance cycliste, vent)
- `virtualize/VirtualizeCommand.java` (options : `--csv`, vent, `--start-date` ISO8601)
- `export/ExportCommand.java` (options : carte d'élévation, carte, URL de tuiles, largeur,
  hauteur, sortie FIT)

## Steps

### 1. `enhance` — la commande principale

gpx2web n'a pas d'équivalent direct : `ProcessCommand` et `VirtualizeCommand` se recouvrent
largement. Côté vcyclist, le pipeline est unifié dans `Enhancer`. Exposer donc une commande
`enhance` claire, et garder `process`/`virtualize` comme alias documentés si la compatibilité
des noms le justifie.

```
vcyclist enhance <input.gpx> [options]
  -o, --output <file>            GPX de sortie
      --csv <file>               export CSV
      --json <file>              export JSON
      --fit <file>               export FIT (implique --start-time)
      --start-time <ISO8601>     horodatage absolu du premier point
      --no-fix-elevation         désactive la correction d'altitude
      --no-virtualize            désactive la simulation
      --no-simplify              désactive Douglas-Peucker
      --simplify-tolerance <m>   défaut 10
      --cache <dir>              dossier de cache des tuiles DEM
  + mixins cycliste / vélo / vent
```

Chaque option de pipeline doit correspondre à un champ d'`EnhanceOptions` — ne pas inventer
d'options qui n'ont pas de traduction dans le modèle.

### 2. `export` — sorties dérivées

```
vcyclist export <input.gpx> [options]
      --map <file.png>           carte sur fond de tuiles
      --tile-url <pattern>       URL des tuiles (obligatoire avec --map)
      --elevation-map <file.png> rendu du relief
      --width / --height / --max-size / --zoom / --margin
      --fit <file.fit>
      --csv <file> / --json <file>
```

`--tile-url` sans défaut, conformément à la décision de g14.

### 3. Multi-fichiers

`FilesMixin` de gpx2web accepte plusieurs entrées. Conserver ce comportement : traiter chaque
fichier indépendamment, nommer les sorties d'après l'entrée quand un répertoire de sortie est
fourni plutôt qu'un fichier.

Un échec sur un fichier ne doit pas interrompre les suivants : les collecter, tout traiter, et
sortir avec un code non nul en récapitulant les échecs à la fin.

### 4. Codes de sortie

Reprendre la convention déjà présente dans `EngineCli` (`EXIT_USAGE`, `EXIT_NO_INPUT`,
`EXIT_RUNTIME`) et la documenter dans `--help`. Un CLI scriptable a besoin de codes stables.

### 5. Sortie console

Reprendre le style d'`EngineCli` (points d'entrée, distance, dénivelé, points de sortie,
durée simulée). Ajouter une option `--quiet` pour l'usage scripté.

Le mécanisme de log ne doit pas écrire sur stdout si stdout porte une sortie de données — ici
ce n'est pas le cas (tout va dans des fichiers), mais le noter pour d'éventuelles évolutions.

### 6. Tests

Tests d'intégration sur des fixtures réelles, en écrivant dans un répertoire temporaire, avec
`fixElevation` désactivé pour ne pas dépendre du réseau.

## Outputs

Créés :

- `cli/src/main/kotlin/io/github/glandais/cli/command/{EnhanceCommand,ExportCommand}.kt`
- `cli/src/test/kotlin/io/github/glandais/cli/command/{EnhanceCommandTest,ExportCommandTest}.kt`
- `cli/README.md` (usage complet, correspondance avec les commandes gpxtools-cli)

Modifiés :

- `cli/src/main/kotlin/io/github/glandais/cli/RootCommand.kt`

## Validation

```bash
./gradlew :cli:test
./gradlew :cli:run -Pargs="enhance ../virtual-cyclist/gpx/sample.gpx -o /tmp/out.gpx --csv /tmp/out.csv"
./gradlew :cli:run -Pargs="export ../virtual-cyclist/gpx/stelvio.gpx --elevation-map /tmp/relief.png"
./gradlew ktlintCheck
```

Cas de test (≥ 12) :

| # | Cas | Attendu |
|---|---|---|
| 1 | `enhance` GPX → GPX | fichier écrit, relisible par `GpxParser` |
| 2 | `enhance --csv` | CSV écrit, en-tête + N lignes |
| 3 | `enhance --json` | JSON écrit et parsable |
| 4 | `enhance --fit` sans `--start-time` | erreur explicite |
| 5 | `enhance --fit --start-time` | FIT écrit et décodable |
| 6 | `--no-virtualize` | pas de vitesse simulée en sortie |
| 7 | `--simplify-tolerance 50` | moins de points qu'avec le défaut |
| 8 | Options cycliste/vélo répercutées | durée simulée différente du défaut |
| 9 | Fichier d'entrée absent | `EXIT_NO_INPUT` |
| 10 | 3 entrées dont 1 cassée | 2 sorties écrites, code non nul, récapitulatif |
| 11 | `export --map` sans `--tile-url` | erreur explicite |
| 12 | `export --elevation-map` | PNG écrit |
| 13 | `--quiet` | stdout vide en cas de succès |

## Done when

- [ ] `enhance` et `export` implémentées, options alignées sur `EnhanceOptions`
- [ ] `--tile-url` obligatoire avec `--map`
- [ ] Multi-fichiers : un échec n'interrompt pas les suivants
- [ ] Codes de sortie stables et documentés
- [ ] `--quiet`
- [ ] `cli/README.md` avec la table de correspondance gpxtools-cli → vcyclist
- [ ] ≥ 12 tests verts
- [ ] `ktlintCheck` vert

## Notes

- **XLSX n'est pas porté** : `--xlsx` de `ProcessCommand` n'a pas d'équivalent (acté dans
  `PLAN-GPX2WEB.md`). Le signaler dans la table de correspondance et, si l'option est passée,
  émettre un message clair renvoyant vers `--csv` plutôt qu'un « option inconnue ».
- **`process` / `virtualize`** : décider de les garder en alias ou de ne documenter que
  `enhance`. Le recouvrement entre les deux commandes gpx2web ne justifie pas de reproduire la
  distinction ; consigner le choix dans la table de correspondance.
- **Ne pas dépendre du réseau dans les tests** : `fixElevation` désactivé partout sauf dans un
  éventuel test gaté `INTEGRATION=1`.
- **`--start-time` obligatoire pour FIT** : conséquence directe de g05 et g10, à signaler dans
  l'aide de l'option, pas seulement à l'exécution.
