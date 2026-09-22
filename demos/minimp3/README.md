# Décodage MP3 avec minimp3 via cbc (backend JVM) — 100 % Java, sans reflection

`minimp3.h` (la vraie bibliothèque C, quasi non modifiée) est compilée par
`cbc -arch=jvm`, puis appelée directement en Java pur, sans sous-processus
ni réflexion — exactement la même approche que `demos/stb_image`.

## Comment ça marche

`decode.c` expose trois petites fonctions au-dessus de l'API cœur de
minimp3 (`mp3dec_init`/`mp3dec_decode_frame`) :

- `long allocBuffer(int size)` — enrobe `malloc()`.
- `void initDecoder(unsigned char *buf, int len)` — initialise le
  décodeur sur un buffer déjà rempli par Java.
- `int decodeNextFrame(void)` — avance d'une trame MP3 et renvoie le
  nombre d'échantillons décodés (toutes voies confondues), ou 0 en fin
  de fichier.
- `frameBufferAddr()`/`frameChannels()`/`frameSampleRate()` — accès au
  petit buffer PCM (réutilisé à chaque trame) et aux métadonnées.

`PlayMp3.java` boucle sur `decode.decodeNextFrame()`, lit chaque trame
via `decode.$runtime().readBytes(...)`, accumule le PCM dans un
`ByteArrayOutputStream` Java, puis écrit un `.wav` (`AudioSystem.write`)
et tente une lecture live (`Clip`) — repliée proprement sur "playback
skipped" si l'environnement n'a pas de périphérique audio (ce qui est le
cas de ce bac à sable).

**Pourquoi trame par trame plutôt que `mp3dec_load_buf` (l'API "tout en
un" de minimp3_ex.h) ?** Cette API passe par un `mp3dec_io_t` de
pointeurs de fonction lecture/seek (utile pour du vrai streaming
fichier/réseau, inutile ici puisque le fichier est déjà en mémoire), et
le backend JVM ne sait appeler indirectement qu'à travers une variable
pointeur-de-fonction *simple*, pas un membre de structure (même
limitation déjà documentée pour `demos/stb_image`). Décoder trame par
trame évite aussi d'accumuler tout le PCM d'une chanson entière (des
dizaines de Mo) dans le tas simulé de cbc, dimensionné pour des
programmes C ordinaires — Java, avec son propre tas, s'en charge à sa
place.

## Fichiers

- `minimp3.h` — copie locale de la bibliothèque attachée, non modifiée
  (seul le cœur `mp3dec_init`/`mp3dec_decode_frame` est utilisé ; pas de
  patch nécessaire ici, contrairement à `stb_image.h`).
- `decode.c` — les 5 fonctions ci-dessus + un `main()` vide.
- `PlayMp3.java` — programme Java ordinaire, zéro réflexion, zéro
  sous-processus.
- `Padords.mp3` — le fichier attaché par l'utilisateur.

## Pour rejouer

```sh
cbc -arch=jvm -I <cbc>/import -o decode decode.c
javac -cp .:<cbc>/build/classes PlayMp3.java
java -cp .:<cbc>/build/classes PlayMp3 Padords.mp3 out.wav
```

## Ce que ça a nécessité côté compilateur

Cette démo a mis au jour et corrigé quatre lacunes/bugs réels de cbc en
compilant minimp3.h et en décodant un vrai fichier :

1. **Taille de tableau non littérale** (`float mdct_overlap[2][9*32]`) —
   `arraySuffix()`/`typePostfix()` n'acceptaient qu'un `<INTEGER>` brut
   entre crochets ; ils acceptent maintenant une vraie expression
   constante entière (repliée à l'analyse via l'évaluateur déjà utilisé
   par les désignateurs de tableau, `evalConstIndex`).
2. **Suffixe `U` ignoré sur un littéral décimal** — `UINT64_MAX`
   (`18446744073709551615ULL`) provoquait un crash du compilateur
   (`NumberFormatException`) : `integerValue()` n'appliquait le repli
   "non signé" (au-delà de `Long.MAX_VALUE`) qu'aux constantes
   hexadécimales/octales, jamais aux décimales, même en présence d'un
   `U` explicite — corrigé pour respecter la table C99 6.4.4.1p5 (un
   suffixe `U` autorise toujours la pleine plage 64 bits non signée,
   quelle que soit la base).
3. **`float *= expr;` (et `+=`/`-=`/`/=`) rejeté** — `L3_ldexp_q2`'s
   `y *= g_expfrac[...]…` échouait avec *"wrong operand type for \*:
   float"* : `visit(OpAssignNode)` dans `TypeChecker` n'acceptait que
   des opérandes entiers pour ces quatre opérateurs, alors qu'ils sont
   valides sur les types flottants en C99 (seuls `%`, `&`, `|`, `^`,
   `<<`, `>>` restent réservés aux entiers) — corrigé pour suivre le
   même chemin "arithmétique" que l'opérateur binaire `+`/`-`/`*`/`/`
   ordinaire.
4. **Vrai bug : mauvaise décroissance pointeur d'un tableau
   multi-dimensionnel** — `unsigned char *p = s.ist_pos[ch];` (où
   `ist_pos` est `uint8_t[2][39]`) compilait sans erreur mais lisait de
   la mémoire n'importe où (silencieusement, ou avec une exception
   `IndexOutOfBoundsException` selon l'adresse obtenue) : `visit(MemberNode)`/
   `visit(PtrMemberNode)`/`visit(DereferenceNode)` dans `IRGenerator`
   décroissent correctement un résultat de type tableau vers sa seule
   adresse (au lieu de le "charger" comme un scalaire), mais
   `visit(ArefNode)` ne faisait jamais cette vérification — corrigé pour
   suivre le même garde `isLoadable()` que les trois autres.

Aucun patch n'a donc été nécessaire sur `minimp3.h` lui-même (contrairement
à `stb_image.h`, dont le backend JVM ne peut pas appeler certains
pointeurs de fonction membres de structure) : les quatre corrections
ci-dessus étaient toutes de vraies lacunes/bugs du compilateur, pas des
limitations d'architecture à contourner.

Une dernière subtilité, côté démo cette fois (pas un bug cbc) :
`PlayMp3.java` alloue le buffer d'entrée avec 32 octets de marge après
la fin réelle du fichier (jamais inclus dans la longueur passée à
`initDecoder()`). Le lecteur de bitstream de minimp3 (`L3_huffman`)
préfetche jusqu'à 4 octets au-delà de sa position logique par
construction — inoffensif sur une vraie machine, où la dernière trame
MP3 est suivie par n'importe quoi d'autre partageant l'espace d'adressage
du processus, mais une exception dure contre le tas simulé et borné de
cbc en toute fin de fichier sans cette marge.

## Vitesse de décodage : 56,8s → 1,36s

Décoder `Padords.mp3` (112s de musique) en entier a d'abord pris **56,8s**
via le backend JVM — comparé à ~0,11-0,13s pour la même bibliothèque
compilée en C natif (gcc -O2), soit un facteur ~425×. En creusant
(profilage, lecture du bytecode réel via `javap -c`), la cause dominante
n'était ni `ByteBuffer` (en fait plus rapide qu'un tableau `byte[]` géré
à la main — HotSpot l'intrinséifie), ni le modèle mémoire simulée en
lui-même, mais deux choses combinées :

1. **`mp3d_synth`** (le filtre de synthèse polyphasé de minimp3) générait
   **8955 octets** de bytecode — juste au-dessus de la limite par défaut
   de HotSpot pour compiler une méthode (`-XX:HugeMethodLimit=8000`) :
   cette fonction tournait donc en permanence dans l'interpréteur.
2. Chaque variable locale (même un simple compteur de boucle) vivait
   dans le tas simulé de cbc, avec un recalcul d'adresse complet
   (`frameBase` + offset + appel `ByteBuffer`) à chaque accès, sans
   aucune réutilisation — exactement ce qui gonflait `mp3d_synth` au
   point de dépasser cette limite.

Le compilateur promeut maintenant en vrais slots de variables locales
JVM (`ILOAD`/`ISTORE`) toute variable locale/paramètre/temporaire dont
l'adresse n'est jamais prise dans sa fonction (voir la nouvelle section
correspondante du README principal et `EscapeAnalysis`). Résultat sur ce
même fichier, sans rien changer d'autre : **1,36s** (meilleur sur 3
passes, sans aucun flag JVM spécial) — un facteur **~42×**, avec un
audio décodé strictement identique bit à bit (même somme SHA-256 du
PCM) avant/après. `mp3d_synth` lui-même est redescendu à 4103 octets,
repassant sous la limite de compilation JIT. Il reste un facteur ~10-12×
par rapport au C natif, principalement inhérent à l'absence de SIMD sur
ce backend et à l'écart normal entre bytecode JIT-compilé et code
machine natif.
