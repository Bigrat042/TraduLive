# Banc de test sur ordinateur (JVM / Linux)

Ces outils font tourner **les vraies classes Kotlin de TraduLive** (découpage, détecteur de voix Silero, langue, filtre)
et **le vrai Whisper** (sherpa-onnx 2.0.1, build Linux x64) sur de l'audio, sans téléphone. Ils ne testent PAS :
la capture audio Android, le service, la bulle flottante, ML Kit, ni le comportement sur un vrai appareil.

Ils servent à vérifier la reconnaissance, la détection de langue, le découpage en phrases et les temps de calcul
(mesurés sur CE processeur de test, pas sur un téléphone).

## Fichiers
- `Probe.kt` : décode des clips avec chaque modèle (`lang` = langue détectée, `scale` = temps de calcul selon la durée).
- `E2E.kt` : fait passer un flux multilingue (phrases + silences) dans Segmenter + Silero + Whisper + LanguageTracker + TextFilter.
- `gen_tts.py` : fabrique les clips de parole français/espagnol/anglais (voix Piper, sherpa-onnx < 2.0 pour la phonétique).
- `shim/` : remplaçant minimal de JUnit 4 + lanceur de tests, utilisé quand Maven est inaccessible.
  Sur GitHub Actions, ce sont les vrais JUnit/Gradle qui exécutent `app/src/test` (`gradle testDebugUnitTest`).

## Commandes utilisées (à adapter aux chemins)
1. Compilateur : `kotlin-compiler-2.0.21.zip` (GitHub JetBrains/kotlin). SDK Android : `android.jar` API 34 (Sable/android-platforms).
2. Bibliothèque : `sherpa-onnx-2.0.1.aar` (on utilise son `classes.jar`) + `sherpa-onnx-v2.0.1-linux-x64-jni.tar.bz2` (libs natives Linux).
3. Modèles : `sherpa-onnx-whisper-{tiny,base,small}.tar.bz2` (release `asr-models`), fichiers `*int8.onnx` et `*tokens.txt`.
4. Compiler l'appli : `kotlinc -jvm-target 17 -no-stdlib -cp android.jar:classes.jar:kotlinx-coroutines-core-jvm.jar:kotlin-stdlib.jar app/src/main/java/app/tradulive/*.kt <stubs>`
   (androidx, Material, ML Kit et commons-compress n'ont pas de jar téléchargeable ici : de fins stubs de leurs signatures ont été utilisés).
5. Tests : compiler `app/src/test/java/app/tradulive/*.kt` + `shim/`, puis `java -cp ... RunnerKt app.tradulive.SegmenterTest ...`.
6. Banc : `java -Djava.library.path=<lib linux> -cp ... E2EKt base 1 1` (modèle, partiels 0/1, nombre de threads).
