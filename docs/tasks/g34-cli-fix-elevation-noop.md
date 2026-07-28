# g34 — `--fix-elevation` du CLI ne corrige aucune élévation

## Goal

L'option existe, elle est documentée, elle s'active — et elle **ne fait rien**. `EnhanceCommand`
construit bien un `EnhanceOptions` avec `fixElevation = true`, puis appelle :

```kotlin
// cli/src/main/kotlin/…/command/EnhanceCommand.kt:361
return Enhancer.enhanceCourse(physics, options, elevationProvider = null)
```

et `Enhancer` saute l'étape quand le provider est nul :

```kotlin
// engine/src/commonMain/…/Enhancer.kt:90
if (options.fixElevation && elevationProvider != null) { … }
```

Aucun message, aucun code de sortie : le fichier écrit est plausible, ses altitudes sont celles
du GPX d'entrée lissées, et rien ne le dit. L'aide annonce pourtant « Correct elevations from DEM
tiles. **Requires network access.** » — un utilisateur qui lit ça et voit la commande réussir en
conclut que les tuiles ont été téléchargées.

À la fin de cette fiche, `--fix-elevation` corrige réellement, ou échoue en le disant.

## Depends on

- Rien. Le seam d'injection existe depuis `g21`, la fabrique JVM depuis `g32`.

## Contexte : comment c'est sorti

Trouvé en livrant **w05** (élévation host-injectée sous WASI), et de la pire façon : le CLI
servait de référence JVM pour valider le profil altimétrique WASI. L'écart mesuré était de 8,94 m
pour un budget de 1 m, et trois vérifications ont été nécessaires — les deux décodeurs WebP
rendent des octets identiques, une troisième implémentation Python des formules Terrarium donne
la valeur JVM, une trace d'un seul point rend bit pour bit la même valeur sous WASI — avant de
comprendre que **c'était la référence qui était fausse**. Comparer une sortie WASI *avec* DEM à
une sortie CLI *sans*.

C'est le coût réel d'un no-op silencieux : il ne casse pas, il fait mentir tout ce qui s'y
compare.

## Inputs

- `cli/src/main/kotlin/…/command/EnhanceCommand.kt` — `enhanceOne` (l. ~348-362), la déclaration
  de l'option (l. ~120-129), la construction des options (l. ~238-245).
- `cli/src/main/kotlin/…/command/ExportCommand.kt:224` — le seul endroit du CLI qui construit
  déjà un `ElevationProvider` (pour la carte SRTM). À imiter, et peut-être à mutualiser.
- `engine/src/commonMain/…/Enhancer.kt` — la condition qui saute l'étape.
- `elevation/src/commonMain/…/ElevationProvider.kt` + `ElevationProviderConfig` — zoom, taille de
  tuile, cache, template d'URL.
- `engine/src/jsMain/…/EngineJsApi.kt` — `enhance` fait déjà exactement ce qu'il faut :
  `val provider = if (opts.fixElevation) ElevationProvider() else null`. La façade JS a raison,
  le CLI a tort.
- `elevation/src/jvmMain/…/ElevationProviderFactory*.kt` (g32) — la fabrique qui accepte un
  fetcher, donc un cache disque.

## Steps

### 1. Câbler le provider

Dans `enhanceOne`, instancier un `ElevationProvider` quand `options.fixElevation` est vrai, et le
passer. Une ligne, la même que celle de `EngineJsApi.enhance`. Ne pas l'instancier sinon : c'est
un cache de tuiles et un client HTTP dont une exécution sans `--fix-elevation` n'a que faire.

### 2. Décider du sort de `--cache`

Le CLI a déjà une option `--cache=<dossier>` (« Folder for downloaded tiles and elevation data »).
Vérifier ce qu'elle pilote aujourd'hui, et trancher explicitement :

| Option | Effet |
|---|---|
| **A** | `--fix-elevation` seul → provider par défaut (tout en mémoire, retéléchargé à chaque run) ; avec `--cache` → fetcher sur disque via la fabrique de g32 |
| **B** | `--fix-elevation` exige `--cache` |

**A est recommandée** : une correction d'altitude sur un fichier ne doit pas exiger de choisir un
dossier, et g21/g32 ont justement rendu le cache disque optionnel et injectable. Mais si
`--cache` est aujourd'hui ignorée elle aussi, le dire dans la fiche plutôt que de l'ignorer une
fois de plus.

### 3. Échouer bruyamment, jamais en silence

Deux chemins à couvrir, tous deux constatés absents :

- **Réseau indisponible / tuile en erreur** : que fait `enhance` ? Propager (code de sortie non
  nul, message nommant la tuile) est préférable à écrire un fichier faux. Choisir, et le tester.
- **Invariant général** : `Enhancer` saute silencieusement `fixElevation` dès que le provider est
  nul. C'est ce qui a permis au bug de vivre. Envisager d'y lever plutôt que d'y sauter — un
  appelant qui demande la correction *et* ne fournit pas de provider s'est trompé. Attention :
  c'est un changement de comportement de `:engine`, donc à trancher ici et à documenter, pas à
  glisser en passant.

### 4. Tests

- `:cli` — un test qui prouve que `--fix-elevation` **atteint** le provider, sans réseau : injecter
  un fetcher de test (g32) et vérifier que les altitudes de sortie viennent de la tuile fournie,
  pas du GPX d'entrée. C'est le test qui manquait ; sans lui la régression revient.
- `:engine` — si l'étape 3 fait lever `Enhancer`, le test qui l'affirme.
- Ne **pas** se contenter d'un test `INTEGRATION=1` : le bug est un câblage, il doit être attrapé
  hors ligne.

## Outputs

- `cli/src/main/kotlin/…/command/EnhanceCommand.kt` corrigé.
- Le test `:cli` correspondant, hors ligne.
- Éventuellement `Enhancer` plus strict (+ CHANGELOG si le comportement change).
- `cli/README.md` si `--cache` change de sens.

## Validation

- [ ] `--fix-elevation` sur `demo/public/gpx/stelvio.gpx` change réellement les altitudes.
- [ ] Le profil obtenu vaut celui de `ElevationProvider()` appelé directement, à ±1 m.
- [ ] Un test hors ligne échoue si quelqu'un remet `elevationProvider = null`.
- [ ] `./gradlew check` et `ktlintCheck` verts.

## Done when

Le CLI corrige les altitudes quand on le lui demande, et un test hors ligne empêche le retour du
no-op.

## Notes

Vérifier au passage si `ExportCommand` a le même trou : il construit un provider pour la carte
SRTM, mais son `enhance` interne — s'il en a un — pourrait avoir hérité du même `null`.

Le fait que la façade JS ait raison depuis la tâche 33 et que le CLI ait tort est le motif exact
de la phase J : le cœur bouge, une surface adjacente ne suit pas. Ici la surface n'a jamais suivi.
