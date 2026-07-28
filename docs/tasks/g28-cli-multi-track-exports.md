# g28 — CSV et JSON : arrêter de perdre les pistes 2..n

## Goal

`enhance` et `export` écrivent **toutes** les pistes en GPX (depuis g02) et en FIT (depuis g25).
Pour CSV et JSON, ils écrivent `first()` :

```kotlin
// EnhanceCommand.kt:278-279
csvOut?.let { target -> writeText(naming.resolve(target, "csv"), CsvWriter.write(enhanced.first()), out) }
jsonOut?.let { target -> writeText(naming.resolve(target, "json"), JsonWriter.write(enhanced.first()), out) }
// ExportCommand.kt:229-230 : identique
```

Sur un fichier multi-piste, les pistes 2..n disparaissent **sans message**. L'utilisateur voit
« wrote out.csv », un fichier plausible, et n'a aucun moyen de savoir qu'il est incomplet — le
compte de points affiché juste avant, lui, couvre bien toutes les pistes.

Ce n'était pas visible tant que tous les formats se comportaient pareil. Depuis g25 c'est une
incohérence interne à la même commande : `--fit` prend tout, `--csv` prend un tiers.

## Depends on

- `g25` (qui a créé l'incohérence en corrigeant `--fit`)
- Aucune dépendance sur `g27`.

## Inputs

- `cli/src/main/kotlin/…/command/EnhanceCommand.kt:278-279`
- `cli/src/main/kotlin/…/command/ExportCommand.kt:229-230`
- `cli/src/main/kotlin/…/OutputNaming.kt` — sait déjà dériver un nom par entrée en mode dossier
- `gpx/src/commonMain/…/io/{CsvWriter,JsonWriter}.kt` — `write(path, options)`, mono-`Path`
- `../gpx2web/…/io/write/tabular/CSVFileWriter.java` — référence : un fichier par `GPXPath`

## Steps

### 1. Trancher la forme de sortie — **avant de coder**

Trois options, à choisir explicitement :

| Option | Sortie pour 3 pistes | Pour | Contre |
|---|---|---|---|
| **A. Un fichier par piste** | `out-1.csv`, `out-2.csv`, `out-3.csv` | C'est ce que fait gpx2web ; chaque fichier reste analysable tel quel dans un tableur | Change le nom du fichier produit même quand il n'y a qu'une piste, si on n'y prend pas garde |
| **B. Une colonne `track`** | `out.csv` avec une colonne d'index | Un seul fichier, comparaisons inter-pistes triviales | Ajoute une colonne à un format que des scripts existants lisent peut-être |
| **C. Concaténation simple** | `out.csv`, pistes bout à bout | Aucun changement de format | Indistinguable d'une piste unique : le problème reste, déguisé |

**C est à écarter** : elle reproduit le défaut qu'on corrige. La recommandation est **A pour le
cas multi-piste, nom inchangé pour le cas mono-piste** — un fichier à une piste, de loin le cas
dominant, doit continuer à produire exactement `out.csv`. `OutputNaming` fait déjà ce genre
d'arbitrage pour les entrées multiples ; s'en inspirer plutôt que d'inventer une seconde
convention.

Si B est retenue, la colonne doit être **la première** et son nom fixé une fois pour toutes.

### 2. Implémentation

- Le point de décision est dans le CLI, pas dans les writers : `CsvWriter.write(path)` reste
  mono-`Path`. Un `write(paths)` en commonMain n'aurait de sens qu'avec l'option B, et alors
  seulement.
- Aligner `enhance` et `export` : même logique, même nommage, même message de sortie.
- Le message affiché doit dire ce qui a été écrit — `wrote out-1.csv, out-2.csv, out-3.csv` ou
  `wrote out.csv (3 tracks)`. C'est la moitié du correctif : le silence était le vrai défaut.

### 3. Documentation

`cli/README.md` : la ligne `--csv` / `--json` du tableau des options dit ce qui se passe avec
plusieurs pistes. Ajouter aussi une phrase dans la section de migration `gpxtools-cli` si le
comportement diffère de gpx2web.

## Outputs

Modifiés :

- `cli/src/main/kotlin/…/command/{EnhanceCommand,ExportCommand}.kt`
- `cli/src/main/kotlin/…/OutputNaming.kt` (si l'option A demande une variante)
- `cli/README.md`
- Tests dans `cli/src/test/kotlin/…/command/{EnhanceCommandTest,ExportCommandTest}.kt`

## Validation

```bash
./gradlew :cli:test
./gradlew check ktlintCheck
```

| # | Cas | Attendu |
|---|---|---|
| 1 | GPX mono-piste, `--csv out.csv` | `out.csv` exactement, contenu identique à pré-g28 |
| 2 | GPX 3 pistes, `--csv out.csv` | les 3 pistes présentes dans la sortie, selon l'option retenue |
| 3 | Idem, `--json` | même comportement que CSV, pas un autre |
| 4 | GPX 3 pistes | le message de sortie nomme tout ce qui a été écrit |
| 5 | `export --csv` sur 3 pistes | même comportement que `enhance --csv` |
| 6 | Somme des lignes CSV (option A) ou des lignes hors en-tête (option B) | égale au nombre total de points de toutes les pistes |
| 7 | Mode dossier (plusieurs fichiers d'entrée) × multi-piste | pas de collision de noms |
| 8 | GPX 3 pistes, `--gpx` + `--csv` + `--fit` | les trois formats décrivent le **même** ensemble de pistes |

Le cas 8 est celui qui verrouille l'invariant : plus aucun format ne doit voir un sous-ensemble
différent des autres.

## Done when

- [x] Forme de sortie tranchée et écrite dans la fiche avant l'implémentation
- [x] `enhance` et `export` alignés, CSV et JSON alignés entre eux
- [x] Cas mono-piste inchangé, nom de fichier compris
- [x] Message de sortie explicite sur ce qui a été écrit
- [x] `cli/README.md` à jour
- [x] `./gradlew check` + `ktlintCheck` verts

## Résultat

### Option A retenue : un fichier par piste

- **Une piste** (le cas dominant) : le fichier porte **exactement** le nom demandé, `out.csv`.
  Aucun suffixe, aucun changement pour les scripts existants.
- **Plusieurs pistes** : `out-1.csv`, `out-2.csv`, … chacune nommée sur la sortie standard.

C'est la forme de gpx2web (`CSVFileWriter` écrit un fichier par `GPXPath`) et chaque fichier reste
ouvrable tel quel dans un tableur. L'option C (concaténation) était écartée d'avance par la fiche
— elle aurait conservé le défaut en le déguisant ; l'option B (colonne `track`) aurait modifié un
format que des scripts lisent peut-être déjà.

La logique tient dans une fonction, `trackOutputFiles(target, trackCount)`, partagée par `enhance`
et `export` — les deux commandes avaient exactement le même défaut, elles ont maintenant exactement
le même comportement.

### Vérification

- 4 cas dans `EnhanceCommandTest` (25-28) et 1 dans `ExportCommandTest` (20).
- Le cas 28 est celui qui verrouille l'invariant que la fiche visait : sur un GPX à deux pistes,
  `--gpx`, `--csv` et `--fit` décrivent **le même ensemble** — 2 tracks, 2 laps, 2 CSV dont chacun
  a autant de lignes que sa propre piste a de points. Plus aucun format ne voit un sous-ensemble
  différent des autres.
- Le cas 25 vérifie l'absence de suffixe sur une piste unique, y compris qu'aucun `out-1.csv`
  n'apparaît à côté.
- `./gradlew check` + `ktlintCheck` verts.

### Note de vérification annexe

La fiche demandait de contrôler que `--map` et `--elevation-map` traitent bien toutes les pistes :
c'est le cas, les deux reçoivent `paths` en entier (`TileMapProducer.createTileMap(paths = …)`,
`SrtmMapProducer.createSrtmMap(file, paths, …)`) et dessinent une couleur par piste. Rien à
corriger de ce côté.

## Notes

- **Pourquoi ce n'est pas juste un `map`.** Le sujet est le nommage et la découvrabilité, pas la
  boucle. Écrire trois fichiers sans le dire est à peine mieux que d'en écrire un seul.
- **Le compte de points affiché est déjà global** (`enhanced.sumOf { it.size }`), ce qui rend
  l'écart d'autant plus trompeur : le CLI annonce 3 000 points puis en exporte 900.
- Vérifier au passage si `--map` et `--elevation-map` (`:map`) traitent bien toutes les pistes ;
  ils prennent `paths` en entier a priori, mais la même vérification vaut d'être faite une fois.
