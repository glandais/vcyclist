# g23 — Option d'écriture des `<extensions>` GPX

## Goal

gpx2web expose `writeGPX(gpx, writer, boolean extensions)`
(`GPXFileWriter.java:83`, drapeau propagé jusqu'à `writePath` puis testé ligne 153). vcyclist
écrit **toujours** les extensions (`GpxWriter.kt:173-191` : `<power>` non namespacé, puis
`<gpxtpx:TrackPointExtension>` avec fréquence cardiaque, cadence et température).

Certains consommateurs veulent un GPX nu : import sur une plateforme au schéma strict, diff de
traces lisible, fichier destiné à un GPS ancien, ou simplement réduction de taille — les
extensions représentent l'essentiel du volume d'un fichier échantillonné à 1 Hz.

Porter le drapeau, avec un défaut qui ne change rien.

## Depends on

- `g05` (paramètre `startTime` — le second paramètre optionnel de la même signature)
- **Conflit de fichier avec `g24`** : les deux touchent `GpxWriter.kt`. Faire `g23` puis `g24`,
  pas en parallèle.

## Inputs

- `gpx/src/commonMain/…/gpx/GpxWriter.kt` — les 3 surcharges `write`, `writeDocument`,
  `writeTrackPoint` (lignes 155-196), les constantes de namespace (lignes 12-28)
- `../gpx2web/gpx/src/main/java/io/github/glandais/gpx/io/write/GPXFileWriter.java` — référence
- `cli/src/main/kotlin/…/command/{EnhanceCommand,ExportCommand}.kt`

## Steps

### 1. API

Ajouter `writeExtensions: Boolean = true` aux trois surcharges de `GpxWriter.write` :

```kotlin
fun write(document: GpxDocument, writeExtensions: Boolean = true): String

fun write(
    path: Path,
    name: String = "noname",
    trackName: String? = null,
    startTime: Instant? = null,
    writeExtensions: Boolean = true,
): String

fun write(
    paths: List<Path>,
    name: String = "noname",
    trackNames: List<String>? = null,
    waypoints: List<GpxWaypoint> = emptyList(),
    startTime: Instant? = null,
    writeExtensions: Boolean = true,
): String
```

Le paramètre va **en dernier** sur chaque surcharge : les appels positionnels existants
continuent de compiler.

Le défaut `true` préserve le comportement actuel **à l'octet près** — c'est l'invariant que
teste le cas 1 ci-dessous.

### 2. Comportement quand `writeExtensions == false`

- Aucun élément `<extensions>` n'est écrit, ni sur `<trkpt>` ni sur `<wpt>`.
- **Les déclarations de namespace `gpxtpx` et `xsi` disparaissent aussi de l'élément racine**, y
  compris l'attribut `xsi:schemaLocation` qui ne référence plus qu'un schéma inutilisé. Un GPX
  qui déclare des namespaces qu'il n'utilise pas est valide mais bruyant, et casse la comparaison
  octet à octet avec la sortie gpx2web.
- `<ele>`, `<time>`, `<name>`, `<sym>`, `<type>` **restent écrits** : ce ne sont pas des
  extensions, ce sont des éléments GPX 1.1 standard. La confusion est facile côté `<wpt>`, où
  `<sym>` a une odeur d'extension Garmin sans en être une.

Concrètement, `writeDocument` doit recevoir le drapeau pour décider des namespaces, et
`writeTrackPoint` pour décider du bloc. Ne pas se contenter d'un `if` dans `writeTrackPoint`.

### 3. CLI

Drapeau **négatif** `--no-extensions` sur `enhance` et `export`, pour que l'absence d'option
conserve le comportement actuel. Sous picocli : `@Option(names = ["--no-extensions"], negatable
= false)` sur un `Boolean` initialisé à `false`, propagé en `writeExtensions = !noExtensions`.

Documenter la nouvelle option dans `cli/README.md`, y compris dans la table de migration
`gpxtools-cli` si une option équivalente y figurait.

## Outputs

Modifiés :

- `gpx/src/commonMain/…/gpx/GpxWriter.kt`
- `cli/src/main/kotlin/…/command/{EnhanceCommand,ExportCommand}.kt`
- `cli/README.md`

Créés :

- Tests dans `gpx/src/commonTest/…/gpx/GpxWriterExtensionsTest.kt`

## Validation

```bash
./gradlew :gpx:allTests :cli:test
./gradlew ktlintCheck
./gradlew :cli:run -Pargs="enhance sample.gpx --gpx out.gpx --no-extensions"
```

| # | Cas | Attendu |
|---|---|---|
| 1 | `writeExtensions = true` sur une fixture riche | sortie **identique octet à octet** à pré-g23 |
| 2 | `writeExtensions = false` | aucun `<extensions>`, aucun `gpxtpx`, aucun `xsi` dans la racine |
| 3 | `false` sur un path portant puissance, FC, cadence, température | ces quatre valeurs absentes, tout le reste présent |
| 4 | `false` — `<ele>` et `<time>` | toujours écrits |
| 5 | `false` — waypoints avec `<sym>` / `<type>` | toujours écrits |
| 6 | Round-trip `write(false) → parse` | positions, élévations, temps préservés ; champs capteurs à `NaN` |
| 7 | XML produit avec `false` | bien formé, parsable par `GpxParser` sans avertissement |
| 8 | CLI sans `--no-extensions` | sortie identique à pré-g23 |
| 9 | CLI avec `--no-extensions` | fichier plus petit, sans extensions |

## Done when

- [x] `writeExtensions` sur les 3 surcharges, en dernière position, défaut `true`
- [x] Namespace `gpxtpx` retiré quand le drapeau est `false` (**`xsi` conservé**, voir Résultat)
- [x] `--no-extensions` sur `enhance` et `export`, documenté dans `cli/README.md`
- [x] Cas 1 vérifié par une comparaison de chaîne exacte, pas par une assertion approximative
- [x] `./gradlew check` + `ktlintCheck` verts

## Résultat

### API

`writeExtensions: Boolean = true` en **dernière position** des trois surcharges de
`GpxWriter.write`, relayé jusqu'à `writeDocument` (déclaration de namespace) et `writeTrackPoint`
(bloc `<extensions>`). Défaut neutre à l'octet près — c'est le cas de test 1, une comparaison de
chaînes exacte entre `write(doc)` et `write(doc, writeExtensions = true)`.

### Écart assumé : `xsi` est conservé

La fiche demandait de retirer **`gpxtpx` et `xsi`**. Seul `gpxtpx` l'est.

`xsi` n'est pas un namespace d'extension : il porte `xsi:schemaLocation`, qui référence le schéma
**GPX lui-même** (`gpx.xsd`) et rien d'autre. Le retirer aurait supprimé une information utile aux
validateurs stricts — c'est-à-dire précisément le public visé par un GPX nu — et aurait changé le
contenu au-delà de ce que le drapeau annonce. Le raisonnement de la fiche (« un namespace déclaré
mais non utilisé est du bruit ») ne s'applique qu'à `gpxtpx`, qui n'apparaît effectivement plus
nulle part quand le drapeau est à `false`.

### Découverte : `@JvmOverloads` est inutilisable en `commonMain`

Première tentative d'écriture : `@JvmOverloads` sur les trois surcharges. Résultat :

```
e: GpxWriter.kt:46:6 Unresolved reference 'JvmOverloads'.
```

`kotlin.jvm.JvmOverloads` **n'est pas résoluble depuis un source set commun** en Kotlin 2.3.21 —
contrairement à `@JvmStatic` ou `@JvmName`, elle n'a pas de déclaration commune. C'est la réponse
à l'étape 1 de **g27**, obtenue avant d'y arriver : le repli documenté dans cette fiche (façades
`jvmMain` avec surcharges explicites) devient le plan principal, cohérent d'ailleurs avec les
ponts `…Jvm` de g22. `docs/tasks/g27-jvm-overloads.md` a été mis à jour en conséquence.

Corollaire immédiat, constaté par la CI locale : **ajouter un paramètre à défaut est une rupture
de source pour les appelants Java**. Le test `ReadmeJavaSnippetTest` (g22) a cessé de compiler dès
l'ajout de `writeExtensions`, et il a fallu épeler `write(path, name, null, null, true)`. Le
`README.md` a été corrigé dans le même mouvement. C'est un argument de plus pour la façade JVM de
g27 : elle isolerait Java de ce genre d'évolution.

### CLI

`--no-extensions` sur `enhance` et `export` (drapeau négatif : son absence conserve le
comportement actuel), propagé en `writeExtensions = !noExtensions`. Documenté dans
`cli/README.md`, tableau des options + une section dédiée.

Smoke réel sur une trace à extensions : 721 octets → **560 octets**, sans `<extensions>` ni
`gpxtpx`, toujours parsable.

### Vérification

- 10 cas dans `GpxWriterExtensionsTest` (commonTest) × 3 cibles, plus 3 cas CLI
  (`EnhanceCommandTest` 18-19, `ExportCommandTest` 19).
- Le cas 10 compare les deux sorties d'un document **sans** capteur : elles ne diffèrent que par
  la déclaration de namespace, vérifié par égalité de chaînes après retrait de celle-ci.
- Deux assertions ont dû passer par le parser plutôt que par `contains` : `Double.toString` rend
  `45.0` en `"45"` sur Kotlin/JS et `"45.0"` sur la JVM. Les tests concernés le documentent.
- `./gradlew check` + `ktlintCheck` verts.

## Notes

- **Pas d'objet `GpxWriteOptions` à cette occasion.** `startTime` (g05) et `writeExtensions` font
  deux paramètres optionnels, ce qui reste sous le seuil où un objet d'options se justifie. Si un
  troisième arrive, refactorer alors — et à ce moment-là seulement.
- **`toGpxTrack` exporte `pInputPower`, pas `pComputedPower`.** Constaté en écrivant le test CLI :
  un GPX enhancé depuis une trace sans extensions n'a **aucun** `<extensions>` en sortie, alors
  que le FIT du même parcours porte la puissance simulée. Ce n'est pas une régression de g23 et
  ce n'était pas dans son périmètre, mais c'est une asymétrie GPX/FIT à trancher — au minimum à
  documenter, sinon à corriger dans une fiche à part.
- **Un seul drapeau, pas une granularité par extension.** gpx2web n'en propose qu'un ; découper
  en « écrire la puissance / la FC / la cadence » multiplierait les combinaisons sans besoin
  identifié. Si le besoin apparaît, ce sera un `Set<PointField>` et non quatre booléens.
- `@JvmOverloads` sur ces trois surcharges est traité en `g27`, après cette tâche pour ne pas
  annoter deux fois.
