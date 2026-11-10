# Rapport de tests

Environnement : 1 cœur Intel Xeon 2,1 GHz, 4 Go, JDK 21, Kotlin 2.0.21, sherpa-onnx 2.0.1 Linux x64 (`docs/evidence/environnement.txt`). Les temps ne sont PAS ceux d'un téléphone. Audio : voix de synthèse (français, espagnol, anglais) + clips de test officiels (allemand, chinois, japonais, coréen).

## 1. Tests unitaires : 42 exécutés, 42 réussis
Lanceur de remplacement de JUnit (Maven bloqué) ; mêmes tests que `app/src/test` pour `gradle testDebugUnitTest` (non exécuté). Journal : `docs/evidence/unit-tests.log`.
Segmenter 8 · LanguageTracker 10 · TextFilter 4 · FloatBuf 4 · ModelCatalog 4 · HttpFetcher 6 (serveur HTTP local : coupure + reprise, Range ignoré, 416, 404, taille fausse) · SessionLog 6.
Un échec initial (ModelCatalog : unités Mo/Mio) a été corrigé. **Contrôle des tests** : 7 sabotages volontaires du code, 7 détectés.

## 2. Whisper réel (Kotlin → bibliothèque native)
Détection de langue, 14 clips : base 14/14, small 14/14, tiny 13/14 (« Thank you » → « bo » ; 1 s de français → « ru »).
Temps de calcul / durée audio : tiny 0,06–0,18 ; base 0,11–0,28 ; small 0,28–0,89. Journaux : `whisper-probe-*.log`.

## 3. Bout en bout (Segmenter + Silero + Whisper + langue + filtre), flux de 35,8 s, 8 phrases
| Modèle | Phrases découpées | Langue finale correcte | 1er provisoire après début de parole | Final après fin de parole | Décodage d'un final |
|---|---|---|---|---|---|
| tiny | 8/8 | 8/8 | 0,8–0,9 s | 0,46–0,70 s | 141–546 ms |
| base | 8/8 | 8/8 | 0,8–0,9 s | 0,46–0,70 s | 240–986 ms |
| small | 8/8 | 8/8 | 0,8–0,9 s | 0,46–0,70 s | 746–3042 ms |
Variante synchrone (pas le planificateur asynchrone réel). Observé : le 1er provisoire d'une nouvelle langue est parfois traduit dans l'ancien sens ; le tout premier provisoire reste sans traduction jusqu'à 2 s.

## 4. Non exécuté
Gradle (tests, lint), tests instrumentés, installation et démarrage de l'APK, tout test sur appareil, ML Kit, Hugging Face, source de secours GitHub (commons-compress absent).
