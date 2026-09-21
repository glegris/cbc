# Décodage d'image avec stb_image.h via cbc (backend JVM) — 100 % Java, sans reflection

`stb_image.h` (la vraie bibliothèque C, quasi non modifiée) est compilée
par `cbc -arch=jvm`, puis **appelée directement, en Java pur, sans
sous-processus ni réflexion** : `ShowImage.java` appelle des méthodes
statiques publiques du programme C compilé, et une nouvelle API publique
de `cbc` pour lire/écrire sa mémoire simulée.

## Comment ça marche

`cbc` compile chaque fonction C de premier niveau (non `static` au sens
C) en une méthode Java `public static` — c'est déjà le cas de `main()`
et de toutes les fonctions publiques de `stb_image.h`
(`stbi_load_from_memory`, `stbi_failure_reason`, `stbi_image_free`, ...).
`decode.c` ajoute deux petites fonctions dans ce même esprit :

- `long allocBuffer(int size)` — enrobe `malloc()`.
- `unsigned char *decodeFromMemory(...)` — enrobe `stbi_load_from_memory()`.

Nouveauté côté compilateur (suite à cette démo) : `cbc` expose
désormais une vraie **API Java publique** pour lire/écrire la mémoire
simulée d'un programme compilé, sans réflexion :

- Chaque classe générée a une méthode `public static StandardRuntime
  $runtime()` renvoyant son instance runtime partagée.
- `StandardRuntime` a une section "Public Java API" :
  `readByte`/`readUnsignedByte`/`readShort`/`readUnsignedShort`/
  `readInt`/`readIntLE`/`readUnsignedInt`/`readLong`/`readLongLE`/
  `readFloat`/`readDouble`/`readPointer`, leurs équivalents `write*()`,
  `readBytes`/`writeBytes` pour un bloc entier, `readCString`/
  `newString` pour une chaîne C, et `memory()` pour le tableau brut.

`ShowImage.java` n'utilise donc plus AUCUNE réflexion :
`decode.$runtime()` + les méthodes `read*/write*` suffisent.

## Fichiers

- `stb_image.h` — copie locale de la bibliothèque attachée, avec
  quelques patchs minimes et documentés (`[cbc-patch]`) : support BMP
  uniquement, un `typedef` de validation statique simplifié, et les
  appels indirects `s->io.read/skip/eof(...)` remplacés par des appels
  directs (cbc ne peut pas encore appeler indirectement via un pointeur
  de fonction stocké dans un membre de structure).
- `decode.c` — `main()` (CLI, inchangé) + `allocBuffer`/
  `decodeFromMemory` pensés pour Java.
- `ShowImage.java` — programme Java ordinaire, zéro réflexion, zéro
  sous-processus.
- `mascot.bmp` — l'image attachée par l'utilisateur (redimensionnée),
  convertie en BMP.

## Pour rejouer

```sh
cbc -arch=jvm -I <cbc>/import -o decode decode.c
javac -cp .:<cbc>/build/classes ShowImage.java
java -cp .:<cbc>/build/classes ShowImage mascot.bmp out.png
```

## Ce que ça a nécessité côté compilateur

Deux vagues de changements dans le dépôt cbc lui-même
(`claude/elegant-bohr-63jhe2`) :

1. **`e914866`** — une dizaine de lacunes réelles du langage C trouvées
   en compilant stb_image.h : opérateur virgule, enum anonyme,
   "east const", déclarateurs multiples dans un membre de structure,
   virgule finale dans un littéral agrégé, nom de fonction comme
   constante d'initialisation statique, définition de fonction
   `extern`, expression constante dans un `case`, plus deux vrais bugs
   (pointeur-vers-const confondu avec pointeur-const ; un crash sur un
   type `typedef` entier).
2. **`508e7b5`** — la nouvelle API Java publique (`$runtime()` +
   `StandardRuntime`'s "Public Java API") décrite ci-dessus, en réponse
   directe à la demande d'éviter la réflexion.

`stb_image.h` n'a pas vocation à rejoindre la suite de tests permanente
de cbc (bibliothèque tierce de 8000 lignes, sans rapport avec le
compilateur) — ce dossier-ci n'est que la démo ; la nouvelle API Java,
elle, est testée en permanence via `test/pubapi-runtime.c` +
`test/PubapiRuntimeTest.java` dans le dépôt cbc.
