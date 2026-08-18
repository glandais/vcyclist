# w03 — `EngineWasiApi` : figer l'ABI v1 et absorber le POC `GpxWasiApi`

## Goal

Le POC `gpx/src/wasmWasiMain/…/wasi/GpxWasiApi.kt` a prouvé le protocole à callbacks sur quatre
exports. Cette fiche en fait une **ABI de première classe** : un seul point d'entrée, dans
`:engine`, versionné, testé, et suffisamment spécifié pour qu'un hôte tiers l'implémente sans
lire le code Kotlin.

Le contenu fonctionnel (enhance, cols, exports…) est la fiche w04 ; ici on fige *la forme*.

## Depends on

- `w01` (cible sur `:engine`).

## Inputs

- `docs/kotlin-wasm-wasi.md` §5, §6, §7 — protocole, formes de module, hôte Python validé.
- `gpx/src/wasmWasiMain/…/wasi/GpxWasiApi.kt` — le POC à absorber.
- `engine/src/jsMain/…/EngineJsApi.kt` — la façade sœur : conventions de nommage, DTO, KDoc.
- `gpx/src/commonMain/…/gpx/` — `GpxParser`, `GpxWriter`, `firstTrackAsPath`.

## Steps

### 1. Emplacement et suppression du POC

Créer `engine/src/wasmWasiMain/kotlin/io/github/glandais/engine/wasi/EngineWasiApi.kt` et
**supprimer** `GpxWasiApi.kt` de `:gpx` (et le `binaries.executable()` de `gpx/build.gradle.kts`,
devenu inutile). Deux façades = deux binaires = deux ABI à maintenir.

Conserver `wasmWasi { wasmtime() }` sur `:gpx` : la cible reste nécessaire comme dépendance.

### 2. Le protocole, figé

Reprendre tel quel ce que le POC a validé, et l'écrire noir sur blanc dans le KDoc du fichier :

- Imports hôte, module `"vcyclist"` : `read_input(ptr, cap) -> i32`, `write_output(ptr, len)`.
- Objets → handles `Int` positifs, table interne, libération explicite (`vcRelease`).
- Erreurs → sentinelle négative + `vcLastError()` qui pousse le message via `write_output`.
- Interdits : réentrer un export pendant un callback ; conserver un pointeur au-delà de l'appel.

Ajouter ce que le POC n'avait pas :

- **`vcAbiVersion(): Int`** — entier monotone, `1` ici. Premier export que tout hôte doit
  appeler ; c'est la seule protection d'un hôte compilé contre une version antérieure.
- **Un espace de nommage des codes d'erreur** : `-1` erreur générique (message dans
  `vcLastError`), `-2` handle inconnu, `-3` argument invalide. Une constante Kotlin par code,
  documentée, et reprise à l'identique dans la doc de w10.
- **`vcReleaseAll(): Int`** — remet la table à zéro, retourne le nombre de handles libérés.
  Indispensable pour un hôte qui réutilise une instance sur plusieurs traces.

### 3. JSON pour tout ce qui n'est pas un scalaire

Décision du plan : les structures traversent la frontière en **JSON UTF-8**, via le même couple
`read_input` / `write_output`. Conséquences à acter ici :

- Les options d'entrée (`EnhanceOptionsDto` & co. côté JS) deviennent un objet JSON dont le
  schéma est **le même que celui du DTO JS**, nom de champ pour nom de champ. Un consommateur
  qui migre de JS à WASI ne réapprend pas les noms.
- Choix du parseur JSON côté guest : `kotlinx-serialization` n'est pas encore une dépendance du
  projet. Trancher entre (a) l'ajouter (variante wasm-wasi disponible, ~quelques dizaines de Ko
  sur le binaire) et (b) un mini-parseur maison. **Recommandation : (a)**, mesurer l'impact sur
  la taille du `.wasm` et le noter dans les Notes de la fiche ; (b) seulement si le surcoût
  dépasse ~50 %.
- Côté sortie, réutiliser les writers JSON existants de `:gpx` (g07) là où ils s'appliquent.

### 4. Tests

Deux niveaux, tous les deux nécessaires :

- **`wasmWasiTest`** — teste ce qui ne traverse pas la frontière (table de handles, mapping des
  erreurs, sérialisation des options). Attention (cf. `kotlin-wasm-wasi.md` §5) : un test qui
  appelle un export utilisant `read_input` échouera, le runner KGP ne fournit pas les imports
  custom. Structurer le code pour que la logique testable ne dépende pas des imports.
- **Hôte de référence** — le vrai test de bout en bout, fiche w09. Ici, se contenter d'un
  smoke manuel avec le script Python de `kotlin-wasm-wasi.md` §7 et le consigner.

## Outputs

- `engine/src/wasmWasiMain/…/wasi/EngineWasiApi.kt` (exports POC portés + `vcAbiVersion`,
  `vcReleaseAll`, codes d'erreur).
- Suppression de `gpx/src/wasmWasiMain/…/wasi/GpxWasiApi.kt` et du `binaries.executable()` de `:gpx`.
- `engine/src/wasmWasiTest/…/EngineWasiApiTest.kt`.

## Validation

- [x] `./gradlew check` vert.
- [x] Le `.wasm` de `:engine` s'instancie sous wasmtime-py et répond `1` à `vcAbiVersion`.
- [x] Round-trip GPX (parse → distance → write) identique au POC, sur `demo/public/gpx/stelvio.gpx`.
- [x] Taille du binaire relevée et notée (référence POC : 145 Ko pour `:gpx` seul).

## Done when

Il existe un unique `.wasm`, celui de `:engine`, dont l'ABI est figée, versionnée et décrite
dans le KDoc du fichier.

## Notes

`vcAbiVersion` doit rester le premier export *au sens sémantique* : il ne doit jamais dépendre
d'un import hôte, pour qu'un hôte puisse l'appeler avant même d'avoir câblé `read_input`.

### Ce qui s'est passé

**La façade est en deux fichiers, et ce n'est pas de la coquetterie.** `EngineWasiApi.kt` porte
les `@WasmExport` et les deux imports ; `WasiAbi.kt` porte la version, les codes d'erreur, la
table de handles et le dernier message. La raison est la contrainte de l'étape 4, vérifiée à la
dure : le premier jet testait `vcParseGpx(0)` pour affirmer que l'argument est validé *avant* le
callback, et le runner a répondu

```
unknown import: `vcyclist::read_input` has not been defined
```

Un test qui *atteint* un export touchant un import garde cet import vivant dans le binaire de
test, et le runner KGP ne sait pas le fournir : le module ne s'instancie plus, toute la suite
tombe. La validation est donc devenue `WasiAbi.invalidLengthOrNull(byteLen)`, testable sans rien
traverser, et l'export l'appelle. Règle générale pour la suite : **toute logique digne d'un test
doit vivre hors du chemin des imports**.

**Codes d'erreur, y compris pour les `Double`.** Le POC retournait `NaN` pour un handle inconnu ;
v1 retourne le code, converti en `Double` (`-2.0`). Sans ambiguïté — les grandeurs transportées
sont toutes positives — et un hôte n'a qu'un seul vocabulaire à connaître. `NaN` reste disponible
pour ce qui est réellement « pas un nombre ».

**Handles : jamais réémis.** `nextHandle` ne décroît pas, donc un handle libéré ne peut pas
ressusciter chez un hôte qui l'aurait gardé. `vcReleaseAll` existe pour l'hôte qui enchaîne les
traces sur une même instance ; sans lui la table ne fait que croître.

### JSON : parseur maison, pas kotlinx-serialization

La fiche recommandait kotlinx-serialization sauf si le surcoût dépassait ~50 %. **Mesuré, il le
dépasse largement** : plugin + `kotlinx-serialization-json:1.9.0` en `wasmWasiMain`, une seule
`@Serializable` de quatre champs décodée puis ré-encodée dans un export jetable :

| Binaire optimisé `:engine` | Taille |
|---|---|
| ABI v1 telle que livrée | **148 904 o** (145 Ko) |
| + kotlinx-serialization-json | **281 030 o** (274 Ko) |

**+132 Ko, soit +89 %** pour un objet d'options. C'est le coût fixe de la machinerie de
sérialisation, pas celui du schéma : il ne se dilue qu'en ajoutant des classes. La décision
retenue est donc **(b)** — un mini-parseur / writer JSON maison, écrit en w04 avec les DTO
qu'il sert, et dont la surface reste minuscule parce qu'il n'a à couvrir que des objets plats de
scalaires. Le nommage des champs reste celui des DTO JS, comme prévu.

Taille du binaire, à comparer aux 145 Ko du POC `:gpx` seul : **148 904 o**, soit +4 Ko pour
l'ABI complète. `:engine` traîne pourtant `:elevation` et `:fit` — le DCE ne garde que ce que les
exports atteignent, et v1 n'atteint encore que le GPX. La courbe intéressante commencera en w04.

### Smoke wasmtime-py (étape 4)

`pip install wasmtime`, script du §7 de `kotlin-wasm-wasi.md` étendu aux exports v1, sur
`demo/public/gpx/stelvio.gpx` :

```
exports: memory, vcAbiVersion, vcLastError, vcParseGpx, vcPathSize,
         vcPathTotalDistance, vcRelease, vcReleaseAll, vcWriteGpx
vcAbiVersion    = 1
vcParseGpx      = 1          vcPathSize = 259     totalDistance = 3573.805 m
vcWriteGpx      = 17424 bytes   → <?xml version="1.0" encoding="UTF-8"?>…
vcPathSize(404) = -2         distance(404) = -2.0      vcParseGpx(0) = -3
vcLastError     = "byteLen must be positive, was 0"
vcRelease(h) = 1 puis 0      vcReleaseAll = 2 puis 0
```

259 points et le même round-trip que le POC : l'absorption n'a rien perdu. Le script est resté
dans le scratchpad, sa version pérenne est la fiche w09.
