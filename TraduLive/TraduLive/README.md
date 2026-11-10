# TraduLive 1.2

Sous-titres **traduits en direct** par-dessus n'importe quelle appli Android. Tout est calculé sur le téléphone : reconnaissance (Whisper), détection de langue, traduction (ML Kit). Pas de compte.

**État de validation : voir `docs/RAPPORT_FINAL.md`.** Le code est écrit, compilé (Kotlin) et en partie testé sur ordinateur ; il n'a PAS encore été compilé en APK ni testé sur un téléphone.

## Fonctions
- Texte provisoire pendant que la personne parle, puis texte définitif à la fin de la phrase.
- Langue détectée automatiquement (stabilisée) ou fixée à la main. Traduction dans les deux sens : autre langue → ta langue, ta langue → l'autre langue choisie.
- Son interne du téléphone (marche avec casque sans fil, filaire ou sans casque) ou micro.
- Bulle déplaçable (A−, A+, Orig., Fixer, ✕), tuile dans les réglages rapides, notification (Verrouiller / Arrêter).
- Transcription enregistrée, partageable. Téléchargement des modèles avec reprise, vérification de taille et source de secours.

## 1. Fabriquer l'APK (sans Android Studio)
1. Crée un dépôt GitHub et envoie-y **tout** le contenu de ce dossier, dossier caché `.github` compris.
2. Onglet **Actions** : « Build, tests et APK » se lance seul (≈ 10 min). Il exécute les tests, compile debug et release, lance le lint et vérifie le contenu des APK.
3. Si c'est vert : **Releases** → `TraduLive.apk` (release) et `TraduLive-debug.apk`. Les rapports de tests et de lint sont dans l'artefact « rapports-tests-lint ».
4. Si c'est rouge : copie les dernières lignes d'erreur de l'étape en échec ; c'est la première vraie compilation, des corrections sont possibles.

## 2. Première utilisation (OnePlus 15)
1. Choisis ta langue et l'autre langue. 2. Télécharge un modèle. 3. Autorise l'affichage par-dessus les autres applis, puis Batterie → « Ne pas optimiser ». 4. Démarre : Android demande l'autorisation de capture du son à chaque démarrage (règle d'Android). 5. Lance ta vidéo dans l'appli YouTube.

## 3. Quel modèle ? (mesures, pas promesses)
Le temps de calcul est **proportionnel à la durée de l'audio**. Mesuré sur un processeur de test (1 cœur Xeon 2,1 GHz, **pas un téléphone**) : *tiny* 0,06–0,18 × le temps réel, *base* 0,11–0,28 ×, *small* 0,28–0,89 ×. Les rapports de vitesse sur le OnePlus 15 ne sont pas mesurés. *tiny* se trompe sur la langue de phrases très courtes (« Thank you » → « bo ») ; *base* et *small* ont détecté 14/14 clips. Par défaut : *small*. Si les sous-titres retardent, essaie *base*.

## 4. Limites connues
- Chrome, Netflix, Prime Video, Disney+, Spotify bloquent la capture du son interne : utilise le mode Micro.
- Une phrase courte dans une nouvelle langue peut être traduite dans le mauvais sens le temps de la confirmation.
- Whisper peut inventer du texte sur de la musique ; seuls quelques cas sont filtrés.
- Traduction ML Kit jamais exécutée par moi : qualité non évaluée. Batterie, chauffe et écran éteint : non testés.

## 5. Développement
`bash scripts/fetch-native.sh` puis Android Studio (JDK 17). Tests : `gradle testDebugUnitTest`. Banc sur ordinateur : `tools/jvm-harness/README.md`.
