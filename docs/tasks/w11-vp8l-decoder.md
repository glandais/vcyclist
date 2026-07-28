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

- [ ] Décodage d'une tuile Terrarium réelle identique, octet pour octet, à TwelveMonkeys.
- [ ] Les 7 tests `TileDecodeSplitTest` verts sur les quatre cibles depuis `commonTest`.
- [ ] `INTEGRATION=1` : profil altimétrique inchangé, ±1 m.
- [ ] Le garde-fou de taille de w06 réajusté sciemment si dépassé.

## Done when

Un hôte WASI n'a besoin que d'un client HTTP pour l'élévation.

## Notes

C'est la fiche la plus lourde du plan en volume de code (~1000-1500 lignes avec les tests). Ne
pas la démarrer avant que le chemin critique (w01→w07) soit livré : elle n'apporte rien à
« publier un `.wasm` utilisable ».
