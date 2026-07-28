# w11 — Décodeur WebP / VP8L pur Kotlin (optionnel)

## Goal

Supprimer le dernier service que le `.wasm` demande à son hôte pour l'élévation : le décodage
des tuiles. Avec un décodeur en `commonMain`, `fetch_tile` (w05) peut rendre les **octets WebP
bruts** — ce que n'importe quel hôte sait fournir avec un simple client HTTP — au lieu d'une
image décodée.

Fiche **optionnelle** : rien dans le chemin critique n'en dépend. Elle améliore l'ergonomie des
hôtes et rétablit les 7 tests déplacés en w01.

## Depends on

- `w05` (le câblage de l'élévation, qu'elle simplifie).

## Inputs

- `elevation/src/commonMain/…/TileFetcher.kt` — le split fetch/decode (g21) : le décodeur
  s'y branche.
- Les implémentations existantes : TwelveMonkeys (JVM), `@jsquash/webp` (JS) — références de
  comportement, pas de code à porter.
- `elevation/src/decodingTest/…/TileDecodeSplitTest.kt` — les 7 tests, prêts à revenir en
  `commonTest`.
- `docs/elevation-integration.md` — format Terrarium.

## Steps

### 1. Périmètre : VP8L seulement

Les tuiles Mapterhorn sont du **WebP lossless (VP8L)** — vérifié lors de l'exploration
(`RIFF`/`WEBP`/`VP8L` sur une tuile live). Le lossy (VP8, 3000-5000 lignes) n'est pas
nécessaire : le rejeter avec un message qui nomme le fourcc rencontré.

Re-vérifier cette hypothèse sur une tuile actuelle **avant** d'écrire une ligne : si la source
change de codec, la fiche entière change de coût.

### 2. Implémentation, en `commonMain`

Dans `elevation/src/commonMain/…/webp/` — donc testée sur **toutes** les cibles, même si seule
WASI s'en sert en production :

- `RiffParser.kt` — conteneur RIFF, localisation du chunk `VP8L`, rejet explicite de `VP8 ` /
  `VP8X` / `ALPH`.
- `Vp8lBitReader.kt` — lecteur LSB-first, en-tête 5 octets (signature `0x2F`, largeur-1 et
  hauteur-1 sur 14 bits, alpha hint, version).
- `HuffmanTree.kt` — tables canoniques, y compris le cas dégénéré du symbole unique.
- `Vp8lDecoder.kt` — groupes Huffman, backward references, cache de couleurs, puis les quatre
  transformations (predictor, color transform, subtract green, color indexing).

Chacun est testable isolément : préférer quatre fichiers avec leurs tests à un décodeur
monolithique.

### 3. Branchement

- `decodeTileBytes` en `commonMain` devient une implémentation réelle ; les `actual` JVM / JS
  peuvent **rester** sur leurs décodeurs natifs (plus rapides) — trancher : uniformiser sur le
  décodeur Kotlin est plus simple à maintenir, garder les natifs est plus rapide. Mesurer avant
  de décider, et écrire la raison.
- `fetch_tile` (w05) accepte désormais des octets WebP ; garder la compatibilité avec les tuiles
  déjà décodées si le coût est nul, sinon casser franchement et bumper `vcAbiVersion`.
- Ramener les 7 tests de `decodingTest` vers `commonTest` et supprimer le source-set intermédiaire.

## Outputs

- `elevation/src/commonMain/…/webp/{RiffParser,Vp8lBitReader,HuffmanTree,Vp8lDecoder}.kt` + tests.
- `TileFetcher` mis à jour, source-set `decodingTest` supprimé.
- Taille du `.wasm` re-mesurée (le décodeur pèse).

## Validation

- [x] Décodage d'une tuile Terrarium réelle identique, octet pour octet, à TwelveMonkeys.
- [x] Les 7 tests `TileDecodeSplitTest` verts sur les quatre cibles depuis `commonTest`.
- [x] `INTEGRATION=1` : profil altimétrique inchangé, ±1 m.
- [x] Le garde-fou de taille de w06 réajusté sciemment si dépassé.

## Done when

Un hôte WASI n'a besoin que d'un client HTTP pour l'élévation.

## Notes

C'est la fiche la plus lourde du plan en volume de code (~1000-1500 lignes avec les tests). Ne
pas la démarrer avant que le chemin critique (w01→w07) soit livré : elle n'apporte rien à
« publier un `.wasm` utilisable ».

### Ce qui s'est passé

Démarrée sur décision explicite, le chemin critique étant livré **sauf** son dernier geste (w07
attend Kotlin 2.4.20, qui n'est pas sortie). Portée par **quatre sous-agents** sur des contrats
d'API figés à l'avance : RIFF + lecteur de bits, Huffman, transformations inverses en parallèle,
puis le décodeur qui les assemble.

`elevation/src/commonMain/…/webp/` — 1 623 lignes, 100 tests :

| Fichier | Lignes | Tests |
|---|---|---|
| `RiffParser.kt` | 139 | 19 |
| `Vp8lBitReader.kt` (+ `Vp8lHeader`) | 122 | 22 |
| `HuffmanTree.kt` | 346 | 16 |
| `Vp8lTransforms.kt` | 388 | 31 |
| `Vp8lDecoder.kt` | 628 | 8 |

**Les fixtures ont été écrites avant le décodeur**, et c'est ce qui a rendu la parallélisation
sûre : cinq WebP réels produits par Pillow, choisis pour que libwebp emploie une transformation
différente à chaque fois (prédicteur, palette 2 bits sur largeur impaire, palette 1 bit,
subtract-green + cross-colour, contenu Terrarium), avec les pixels attendus pris **du décodeur de
Pillow** — donc d'une implémentation qui ne sait rien de la nôtre. Les cinq passent octet pour
octet, ainsi que les deux fixtures historiques.

**Trois erreurs de mes propres briefs, trouvées par les agents.** Elles valent d'être notées,
parce qu'elles montrent où porter l'attention :

1. Le mapping des canaux de la transformation cross-colour, que j'avais inversé : l'agent a suivi
   la spec et `ColorCodeToMultipliers` de libwebp plutôt que moi, et l'a documenté.
2. L'ordre du cache de couleurs et de la méta-Huffman — la spec dit `color-cache-info meta-prefix
   data`, mon brief disait l'inverse ; toute image utilisant l'un des deux se désynchronise.
3. `hasAlpha` du header VP8L est **faux** sur nos fixtures alors que tous leurs pixels sont
   opaques : c'est un indice que l'encodeur peut effacer, et rien ne doit en dépendre.

Aucune n'aurait été trouvée par relecture ; les fixtures les auraient toutes attrapées.

### La validation qui compte

Une vraie tuile Mapterhorn 512×512, décodée par le Kotlin et par **TwelveMonkeys**, comparées
octet pour octet — plus l'empreinte de référence déjà figée du projet
(`Vp8lAgainstImageIoTest`, `INTEGRATION=1`). Vérifié aussi que le test tourne réellement plutôt
que d'être sauté par sa garde : le démon Gradle capture l'environnement, et un test vert qui n'a
rien exécuté est le piège classique de cette validation-là.

Performance : **16,4 ms** par tuile de 512×512 sur la JVM (mesuré par l'agent sur 20 tours à
chaud), deux ordres de grandeur sous le téléchargement qui l'a précédée. Le décodeur Huffman est
un marcheur façon `puff.c` et non une table de lookup, faute de `peek`/`skip` sur le lecteur de
bits ; à 16 ms, la table ne se justifie pas.

### Décisions

**Les décodeurs natifs restent sur JVM et JS.** Uniformiser sur le Kotlin serait moins de code à
maintenir, mais remplacerait deux décodeurs éprouvés et déjà livrés par un écrit la semaine
dernière, sur les deux cibles réellement publiées. Le gain serait de la maintenance, le risque
serait les altitudes de tout le monde. Écrit dans le KDoc de `TileFetcher.kt` ; à revisiter dans
quelques versions.

**`fetch_tile` accepte les octets bruts sans casser l'ABI.** Un champ `tileFormat` de
`vcSetElevationConfig` — `"rgba"` (défaut, contrat de w05) ou `"webp"` — plutôt qu'un reniflage
de la charge utile : un WebP est plus petit qu'une tuile décodée *aujourd'hui*, et décider à la
taille se tromperait le jour où ce n'est plus vrai, en rendant de fausses altitudes au lieu d'une
erreur. Compatibilité totale, donc pas de `vcAbiVersion` à bumper — ce que la fiche autorisait
explicitement.

Le harnais de w09 le prouve de bout en bout : la même trace, les mêmes tuiles, les deux modes,
**profils identiques au bit** — l'un avec Pillow côté hôte, l'autre avec un hôte qui ne fait
qu'un `GET`.

### Les 7 tests, et celui qui n'est pas revenu

`TileDecodeSplitTest` est de retour en `commonTest`, vert sur les quatre cibles. Un seul cas est
resté dans `src/decodingTest` : la composition `fetchTileBytes` + `decodeTileBytes` contre une
tuile en ligne, qui atteint l'import hôte. La garde `INTEGRATION` ne suffit pas — la joignabilité
est statique, la garde est à l'exécution — donc l'y ramener tuerait toute la suite wasmWasi par
`unknown import`. Le source-set survit avec un test et la raison écrite dedans.

### Taille

**300 968 → 318 495 octets**, soit **+17,5 Ko (+5,8 %)** pour le décodeur complet. Le plafond de
w06 (600 000) n'a pas eu à bouger.
