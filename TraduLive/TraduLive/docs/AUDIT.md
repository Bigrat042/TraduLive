# Audit technique TraduLive 1.2

Périmètre : 17 fichiers Kotlin, layout, manifeste, Gradle, workflow, intégration Whisper, téléchargement, traduction, capture, service, README. Méthode : relecture complète, compilation Kotlin, tests, mesures sur de vrais modèles.

## Intégration Whisper (vérifiée sur le code source de sherpa-onnx v2.0.1 et par exécution)
- Signatures réelles lues dans `kotlin-api/*.kt` ; config identique à l'appli acceptée par la bibliothèque native Linux.
- Format : 16 kHz, mono, flottants [-1;1] ; `language=""` → détection automatique ; `lang` renvoie « fr », « es »… (testé).
- Détecteur Silero : `isSpeechDetected()` = parole en cours (lu dans le C++ et testé par le banc de bout en bout).
- Modèles : noms et tailles exacts relevés dans les archives officielles GitHub (tiny, base, small, turbo). Adresses Hugging Face : non vérifiables ici.

## Problèmes trouvés et corrigés
| # | Problème | Gravité | Correction | Preuve |
|---|---|---|---|---|
| 1 | README : « coût indépendant de la longueur » (faux) | info fausse | mesure + README corrigé | sondes Whisper |
| 2 | Exception non rattrapée au démarrage (micro occupé…) | plantage | CoroutineExceptionHandler + try/catch | compilé, non testé sur appareil |
| 3 | Libération native pendant utilisation (arrêt) | plantage | verrous + drapeau d'arrêt | compilé, non testé (concurrence) |
| 4 | Langue non gérée devenue « stable » ; 1er extrait court décisif | erreur d'affichage | LanguageTracker | 10 tests, mesures « bo »/« ru » |
| 5 | Téléchargement tué par rotation ; fichier tronqué non détecté | fiabilité | portée applicative, configChanges, tailles exactes | 6 tests HTTP |
| 6 | Phrases jusqu'à 18 s (coût croissant) | performance | 10 s avec recouvrement | test + mesures |
| 7 | Transcription coupée au milieu d'une entrée | données | coupe sur entrée entière | test |
| 8 | Bulle : plantage si autorisation retirée | plantage | try/catch + arrêt propre | compilé |
| 9 | Rappels écran principal après fermeture | fuite | removeCallbacks onDestroy | compilé |
| 10 | Lint entièrement désactivé | qualité | lint exécuté et publié en CI | non exécuté |
| 11 | Tailles de modèles imprécises | UX | calculées depuis les octets exacts | test |
| 12 | Aucun test | qualité | 42 tests | exécutés |

## Non corrigé / non vérifiable ici
Voir « C » dans `RAPPORT_FINAL.md`.
