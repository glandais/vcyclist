# 15 — Engine : GPX writer (`Path`/`GpxDocument` → XML)

## Goal

Symétrique de la tâche 14 : produire une chaîne XML GPX bien formée à partir d'un `Path` ou d'un `GpxDocument`, avec gestion correcte des **namespaces** (Garmin TPX) et des **extensions** (power, hr, cad, atemp). Pas de dépendance JVM-only — utilise `xmlutil` (déjà dans le projet via la tâche 14).

Trois livrables :

1. **`GpxWriter`** : émet la racine `<gpx version="1.1" creator="...">` avec namespaces Garmin TPX + xsi déclarés, puis `<metadata>`, puis `<trk>` → `<trkseg>` → `<trkpt>` avec extensions correctement préfixées.
2. **Pont retour `Path → GpxDocument`** : extension `Path.toGpxTrack(name: String?, type: String?): GpxTrack` (et `Path.toGpxDocument(...)`) qui matérialise un `GpxDocument` complet depuis un `Path` (conversion radians → degrés, masquage des champs absents via `NaN`).
3. **Tests round-trip** : `parse(write(parse(x))) ≈ parse(x)` — vérifie la stabilité bidirectionnelle.

## Depends on

- `14-engine-gpx-parser` (modèles `GpxDocument`/`GpxTrack`/`GpxTrackPoint`, `xmlutil` configuré)
- `12-engine-path` (`Path`, accesseurs)

## Inputs

- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/gpx/GPXWriter.ts` — référence canonique
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/gpx/types.ts` — `KNOWN_NAMESPACES`, `NAMESPACE_PREFIXES`
- Tâche 14 rapport (notes API xmlutil writer) — `IXmlStreaming.newGenericWriter(out)` ou `xmlStreaming.newWriter(...)` selon ce qui marche le mieux multi-target.

## Steps

### 1. `Path → GpxTrack` bridge (`GpxFromPath.kt`)

`engine/src/commonMain/kotlin/io/github/glandais/engine/gpx/GpxFromPath.kt` :

```kotlin
package io.github.glandais.engine.gpx

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import kotlin.math.roundToInt

/** Convert this [Path] back to a [GpxTrack]. Converts lat/lon back to degrees, exposes
 *  `time` as epoch ms (only if `time(i) > 0` — `0` is treated as "absent" since the parser
 *  uses 0 as sentinel for missing `<time>`). */
fun Path.toGpxTrack(name: String? = null, type: String? = "cycling"): GpxTrack {
    val points = List(size) { i ->
        GpxTrackPoint(
            latitudeDeg = latitude(i) * MathConstants.RAD_TO_DEG,
            longitudeDeg = longitude(i) * MathConstants.RAD_TO_DEG,
            elevationM = elevation(i).takeUnless { it.isNaN() },
            timeEpochMs = time(i).toLong().takeIf { it > 0L },
            heartRate = heartRate(i).takeUnless { it.isNaN() || it == 0.0 }?.roundToInt(),
            cadence = cadence(i).takeUnless { it.isNaN() || it == 0.0 }?.roundToInt(),
            temperatureC = temperature(i).takeUnless { it.isNaN() || it == 0.0 },
            powerW = pInputPower(i).takeUnless { it.isNaN() || it == 0.0 },
        )
    }
    return GpxTrack(name = name, type = type, points = points)
}

fun Path.toGpxDocument(name: String = "noname", trackName: String? = null): GpxDocument =
    GpxDocument(name = name, tracks = listOf(toGpxTrack(name = trackName)))
```

**Sémantique `0.0` = absent** : `GeneratedPath` initialise tout à `0.0`. Pour `heartRate/cadence/temperature/power`, on traite `0.0` comme « non renseigné » plutôt que comme « 0 réel » — sinon on émettrait des extensions parasites. Pour `elevation` et `time`, on garde la valeur littérale (`0.0` peut être valide). Documenté dans une note.

### 2. `GpxWriter.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/gpx/GpxWriter.kt` :

```kotlin
package io.github.glandais.engine.gpx

import kotlin.time.Instant
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.xmlStreaming

object GpxWriter {

    /** Namespaces utilisés. */
    private const val NS_GPX = "http://www.topografix.com/GPX/1/1"
    private const val NS_XSI = "http://www.w3.org/2001/XMLSchema-instance"
    private const val NS_GARMIN_TPX = "http://www.garmin.com/xmlschemas/TrackPointExtension/v1"
    private const val PREFIX_GARMIN_TPX = "gpxtpx"
    private const val CREATOR = "@glandais/vcyclist"

    /** Write a single track as a GPX 1.1 document. */
    fun write(document: GpxDocument): String {
        val out = StringBuilder()
        out.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        val writer = xmlStreaming.newGenericWriter(out, repairNamespaces = false, xmlDeclMode = XmlDeclMode.None)

        writer.use { w ->
            w.startTag(NS_GPX, "gpx", null)
            w.attribute(null, "version", null, "1.1")
            w.attribute(null, "creator", null, CREATOR)
            w.namespaceAttr("", NS_GPX)
            w.namespaceAttr("xsi", NS_XSI)
            w.namespaceAttr(PREFIX_GARMIN_TPX, NS_GARMIN_TPX)
            w.attribute(NS_XSI, "schemaLocation", "xsi",
                "$NS_GPX http://www.topografix.com/GPX/1/1/gpx.xsd")

            // <metadata><name>…</name></metadata>
            w.startTag(NS_GPX, "metadata", null)
            w.startTag(NS_GPX, "name", null); w.text(document.name); w.endTag(NS_GPX, "name", null)
            w.endTag(NS_GPX, "metadata", null)

            for (track in document.tracks) writeTrack(w, track)

            w.endTag(NS_GPX, "gpx", null)
        }
        return out.toString()
    }

    /** Convenience : write a single-track document from a [io.github.glandais.engine.path.Path]. */
    fun write(path: io.github.glandais.engine.path.Path, name: String = "noname", trackName: String? = null): String =
        write(path.toGpxDocument(name = name, trackName = trackName))

    private fun writeTrack(w: nl.adaptivity.xmlutil.XmlWriter, track: GpxTrack) {
        w.startTag(NS_GPX, "trk", null)
        track.name?.let { w.startTag(NS_GPX, "name", null); w.text(it); w.endTag(NS_GPX, "name", null) }
        track.type?.let { w.startTag(NS_GPX, "type", null); w.text(it); w.endTag(NS_GPX, "type", null) }
        w.startTag(NS_GPX, "trkseg", null)
        for (p in track.points) writeTrackPoint(w, p)
        w.endTag(NS_GPX, "trkseg", null)
        w.endTag(NS_GPX, "trk", null)
    }

    private fun writeTrackPoint(w: nl.adaptivity.xmlutil.XmlWriter, p: GpxTrackPoint) {
        w.startTag(NS_GPX, "trkpt", null)
        w.attribute(null, "lat", null, p.latitudeDeg.toString())
        w.attribute(null, "lon", null, p.longitudeDeg.toString())

        p.timeEpochMs?.let {
            w.startTag(NS_GPX, "time", null)
            w.text(Instant.fromEpochMilliseconds(it).toString())
            w.endTag(NS_GPX, "time", null)
        }
        p.elevationM?.let {
            w.startTag(NS_GPX, "ele", null); w.text(it.toString()); w.endTag(NS_GPX, "ele", null)
        }

        val hasGarminExt = p.heartRate != null || p.cadence != null || p.temperatureC != null
        val hasPower = p.powerW != null
        if (hasGarminExt || hasPower) {
            w.startTag(NS_GPX, "extensions", null)
            if (hasGarminExt) {
                w.startTag(NS_GARMIN_TPX, "TrackPointExtension", PREFIX_GARMIN_TPX)
                p.heartRate?.let {
                    w.startTag(NS_GARMIN_TPX, "hr", PREFIX_GARMIN_TPX); w.text(it.toString())
                    w.endTag(NS_GARMIN_TPX, "hr", PREFIX_GARMIN_TPX)
                }
                p.cadence?.let {
                    w.startTag(NS_GARMIN_TPX, "cad", PREFIX_GARMIN_TPX); w.text(it.toString())
                    w.endTag(NS_GARMIN_TPX, "cad", PREFIX_GARMIN_TPX)
                }
                p.temperatureC?.let {
                    w.startTag(NS_GARMIN_TPX, "atemp", PREFIX_GARMIN_TPX); w.text(it.toString())
                    w.endTag(NS_GARMIN_TPX, "atemp", PREFIX_GARMIN_TPX)
                }
                w.endTag(NS_GARMIN_TPX, "TrackPointExtension", PREFIX_GARMIN_TPX)
            }
            if (hasPower) {
                w.startTag(NS_GPX, "power", null)
                w.text(p.powerW!!.toString())
                w.endTag(NS_GPX, "power", null)
            }
            w.endTag(NS_GPX, "extensions", null)
        }
        w.endTag(NS_GPX, "trkpt", null)
    }
}
```

L'API exacte de xmlutil 0.91 peut différer — l'agent ajuste les noms de méthodes (`writer.startTag(...)` vs `writer.smartStartTag(...)`, `namespaceAttr` vs `addNamespace`, etc.) en consultant la version installée. La sémantique attendue est : XML 1.0 UTF-8, namespaces déclarés une seule fois à la racine `<gpx>`, prefixes `gpxtpx`/`xsi`.

### 3. Tests `GpxWriterTest.kt`

`engine/src/commonTest/kotlin/io/github/glandais/engine/gpx/GpxWriterTest.kt`. Cas à couvrir :

| # | Cas | Attendu |
|---|---|---|
| 1 | Écrire un GpxDocument vide (0 track) | XML valide avec `<gpx>` root + metadata, pas de `<trk>` |
| 2 | Écrire 1 track avec 0 point | `<trk><trkseg/></trk>` ou équivalent valide |
| 3 | Écrire 1 trackpoint minimal (lat/lon seuls) | `<trkpt lat="..." lon="..."/>`, pas de `<ele>`, `<time>`, `<extensions>` |
| 4 | trkpt avec elevation | `<ele>` présent |
| 5 | trkpt avec time epochMs | `<time>` au format ISO-8601 UTC (`...Z`) |
| 6 | trkpt avec power | `<extensions><power>NN</power></extensions>` |
| 7 | trkpt avec heartRate seul | `<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>NN</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>` |
| 8 | trkpt avec hr+cad+atemp+power | extensions complètes avec TPX nested + power au niveau extensions |
| 9 | Namespace `xmlns:gpxtpx` déclaré sur la racine | sentinel string |
| 10 | Round-trip : parse → write → parse → comparer les points | propriété de stabilité |
| 11 | Round-trip sample.gpx : trkpt count + premier point.powerW conservés | regression |
| 12 | Round-trip garmin.gpx : hr/cad préservés | regression |
| 13 | `Path.toGpxTrack()` : taille préservée, lat/lon convertis degrés | bridge |
| 14 | `Path.toGpxTrack()` : heartRate==0 → null (absence) | sémantique zéro |
| 15 | `Path.toGpxTrack()` : elevation==0 → null ou 0 selon décision (préférence : préserver le `0`) | sémantique zéro |
| 16 | `GpxWriter.write(path)` produit un XML parseable par `GpxParser.parse` | sanity |
| 17 | XML output start avec `<?xml version="1.0" encoding="UTF-8"?>` | sentinel |
| 18 | Test multi-track : 2 tracks → 2 `<trk>` dans la sortie | propriété |

Le **round-trip** (test 10-12) est la propriété la plus forte. Tolérances :
- `latitudeDeg`/`longitudeDeg` : 1e-9 (parsing/formatting de doubles peut introduire un noise < 1 ulp)
- `elevationM` : 1e-9
- `timeEpochMs` : exact (entier)
- extensions int (hr/cad) : exact
- extensions double (power, temp) : 1e-9

### 4. Vérification ktlint

`./gradlew :engine:ktlintFormat` au besoin.

## Outputs

Créés (commonMain) :

- `engine/src/commonMain/kotlin/io/github/glandais/engine/gpx/GpxFromPath.kt`
- `engine/src/commonMain/kotlin/io/github/glandais/engine/gpx/GpxWriter.kt`

Tests :

- `engine/src/commonTest/kotlin/io/github/glandais/engine/gpx/GpxWriterTest.kt`

Aucune nouvelle dépendance — `xmlutil` et `kotlinx-datetime` (devenue `kotlin.time.Instant` en 0.7) sont déjà là.

## Validation

```bash
./gradlew :engine:allTests
./gradlew :elevation:allTests
./gradlew ktlintCheck
./gradlew :engine:build
```

Critères :

- ≥ 18 tests `GpxWriterTest` verts sur JVM/JS/Wasm.
- Round-trip strict sur les 4 fixtures (sample, garmin, amazfit, movescount).
- XML output conforme :
  - `<?xml version="1.0" encoding="UTF-8"?>` en première ligne
  - racine `<gpx>` avec `version="1.1"`, `creator`, namespace par défaut GPX, `xmlns:xsi`, `xmlns:gpxtpx`
  - extensions sous `<extensions>` avec préfixe `gpxtpx:` pour TPX, `power` au niveau extensions (non-namespacé, comme sample.gpx)
- `:elevation:allTests` toujours vert ; `ktlintCheck` vert.

## Done when

- [x] `GpxFromPath.kt` créé (`Path.toGpxTrack`, `Path.toGpxDocument`)
- [x] `GpxWriter.kt` créé
- [x] `GpxWriterTest.kt` ≥ 18 tests verts
- [x] Round-trip parse→write→parse stable sur 4 fixtures
- [x] `:engine:allTests` + `:elevation:allTests` verts
- [x] `ktlintCheck` vert
- [x] Toutes les checkboxes cochées

## Notes

- **API exacte xmlutil writer** : la version 0.91 expose `xmlStreaming.newGenericWriter(out, repairNamespaces, xmlDeclMode)` selon le rapport tâche 14. Si la signature diffère, ajuster — l'API évolue rapidement entre versions. Critères : KMP-safe, supporte namespace prefixes explicites, supporte `Appendable`/`StringBuilder` en sortie.
- **`kotlin.time.Instant` vs `kotlinx.datetime.Instant`** : depuis `kotlinx-datetime 0.7`, le type est devenu un typealias deprecated vers `kotlin.time.Instant`. La tâche 14 utilise directement `kotlin.time.Instant`. Cohérent — on garde la même import.
- **Format de `time`** : `Instant.toString()` produit l'ISO-8601 `YYYY-MM-DDTHH:MM:SS.SSSZ`. Compatible avec le parser TS (qui utilise `new Date(string).getTime()`).
- **Format de `lat/lon`** : `Double.toString()` côté Kotlin émet la représentation la plus courte qui round-trip exactement. Pour `45.680697` (valeur du sample.gpx), on récupère `"45.680697"` — bon. Pour des valeurs avec ~15 chiffres significatifs, Kotlin peut basculer en notation scientifique (`1.0E-15`). Le parser xmlutil + `toDouble()` la reparse correctement.
- **Sémantique `0.0 = absent`** dans `Path.toGpxTrack` : compromis pragmatique. Plus tard, si un champ doit explicitement distinguer "0 réel" vs "absent", on adoptera un sentinel `NaN` à l'écriture (la tâche 14 parse `<power>0</power>` → `0.0`, donc le round-trip `0 power → absent → 0 power` est cassé pour ce cas extrême). Décision documentée — peut être révisée en tâche 26 (parité fixtures).
- **`type` du track** : `"cycling"` par défaut. Le sample.gpx l'utilise. Pour d'autres activités, l'appelant peut passer `null`.
- **`creator="@glandais/vcyclist"`** : marque clairement les fichiers générés. Différent du TS (`@glandais/virtual-cyclist`) — renommage cohérent avec le module `:engine`.
- **Pretty-print** : le TS faisait un pretty-print manuel. xmlutil sait indenter (option `indentString` sur le writer si dispo). Pas critique pour la correction — la spec parser ignore les whitespace inter-tags. Si l'output est moche, ajouter `xmlStreaming.newGenericWriter(out, … , indentString = "  ")`. Sinon laisser tel quel.
- **Round-trip et NaN** : `time(i) == 0.0` se mappe à `timeEpochMs = null` (cf. `takeIf { it > 0L }`). Si l'appelant met explicitement `path.setTime(0, 0.0)` pour signifier « 1970-01-01 », on perd l'info — cas extrême négligeable. Le sample.gpx commence en 2024.
- **`type` chez `GpxTrack`** : la tâche 14 n'a peut-être pas exposé `type: String?` dans `GpxTrack`. Si manquant, ajouter à `Gpx.kt` (modif mineure, retro-compatible).
- **Si `xmlutil` ne fournit pas de writer KMP-safe** : alternative — émettre du XML à la main via `StringBuilder` avec échappement minimal (`<`, `>`, `&`, `"`). Approche fragile mais déterministe ; à utiliser en dernier recours.
