# w08 — Passage à Kotlin 2.4.20 final et re-vérifications

## Goal

Le projet est sur `2.4.20-RC` (la fiche disait `2.4.20-Beta2` ; la montée en RC a eu lieu depuis)
uniquement parce que le support wasmtime de KGP est apparu dans cette ligne.
Publier des artefacts (JVM, JS *et* WASI — la Beta recompile tout) depuis une Beta n'est pas
acceptable : cette fiche fait le passage à la version finale et re-vérifie les points que le POC
avait notés comme « rugosités de la Beta ».

## Depends on

- `w01` (la cible partout — pour que la montée soit vérifiée sur l'ensemble du cœur).
- Bloque `w07` (publication réelle).

## Inputs

- `gradle/libs.versions.toml` — `kotlin = "2.4.20-Beta2"`.
- `docs/kotlin-wasm-wasi.md` §1, §4, §5, §8 — la liste précise des points à revérifier.
- `kotlin-js-store/yarn.lock` — a dû être régénéré lors de la montée en Beta2.

## Steps

1. **Attendre / vérifier la disponibilité** de `2.4.20` final sur Maven Central. Si elle n'est
   pas sortie, la fiche reste ouverte : ne pas publier entre-temps (w07).
2. **Bump** `kotlin` dans `libs.versions.toml`, puis `./gradlew kotlinUpgradeYarnLock` (la montée
   précédente n'avait cassé que ce lock).
3. **Re-vérifier les trois observations de la Beta**, une par une, et corriger la doc :
   - l'avertissement `⚠️ JS Environment Not Selected` malgré `wasmtime()` — a-t-il disparu ?
   - l'absence de `_initialize` dans le module sans `main()` (forme réacteur à section `start`) —
     toujours vraie ? C'est le contrat que les hôtes de w09 et la doc de w10 supposent ;
   - la version de wasmtime provisionnée par KGP (46.0.1 constatée) — a-t-elle bougé ? Si oui,
     réaligner la version de wasmtime-py recommandée.
4. **Re-mesurer la taille du `.wasm`** et le sha256 (w06). Un changement de compilateur les
   déplace ; c'est attendu, il faut juste que le garde-fou de taille reste pertinent.
5. **Mettre à jour** `docs/kotlin-wasm-wasi.md` (§1, §4, §5, §8) et la ligne « Tools » de
   `CLAUDE.md`.
6. **Re-tester `kotlin.js.ir.output.granularity=per-file`** — reporté ici depuis E1 du
   [ledger des avertissements](../../ledgers/build-warnings-ledger.md#e1-investigation). Mesuré sur
   `2.4.20-RC` : la propriété exige `useEsModules()`, compile proprement avec, et n'a **aucun
   effet observable** (sortie toujours en 13 modules, chunk de la démo identique au byte). C'est
   la seule voie propre pour que le bundler élimine `pathToFit` et, avec lui, les 47 % du chunk
   `engine` de la démo qui sont l'encodeur FIT — inutilisé par la démo. Si la propriété fonctionne
   en 2.4.20 final : basculer `engine-shim.ts` sur des imports nommés et re-mesurer
   `demo/dist/assets/engine-*.js` (référence à battre : 1 011 KiB brut / 254 KiB gzip). Attention,
   `useEsModules()` change le format du paquet npm publié — c'est cassant pour un consommateur
   CommonJS, donc à décider séparément, pas à activer en passant.

## Outputs

- `gradle/libs.versions.toml`, `kotlin-js-store/yarn.lock`.
- `docs/kotlin-wasm-wasi.md`, `CLAUDE.md` mis à jour.

## Validation

- [ ] `./gradlew check` vert sur toutes les cibles.
- [ ] Le `.wasm` produit s'instancie et passe le harnais d'hôtes de w09.
- [ ] Les exports du module inspectés (`wasmtime objdump` ou wasmtime-py) : forme réacteur
      confirmée, pas de régression sur `_initialize`/`_start`.
- [ ] Les trois points de §8 « Ouvert » du doc sont soit fermés, soit ré-écrits avec l'état 2.4.20.
- [ ] `granularity=per-file` re-testé : soit il fonctionne et E1 devient actionnable, soit le
      ledger est mis à jour avec l'état 2.4.20 final.

## Done when

Le projet compile sur une version stable de Kotlin, et les hypothèses de la doc WASI ont été
re-validées dessus.

## Notes

Si `2.4.20` final régressait sur wasmtime (peu probable, mais c'est le genre de chose qu'une
fiche doit prévoir) : ne pas revenir en arrière silencieusement — documenter, ouvrir un ticket
en amont, et garder la Beta en publiant **sans** les artefacts WASI plutôt que l'inverse.
