# Rapport de compilation

| Étape | Résultat | Détail |
|---|---|---|
| Accès Google Maven, Maven Central, services.gradle.org | Inaccessibles | Aucune compilation Gradle/AGP possible ici |
| Compilation Kotlin des 17 fichiers (kotlinc 2.0.21, JVM 17) | Réussie, 0 erreur, 0 avertissement | `docs/evidence/kotlin-compile.log` |
| Dépendances réelles utilisées | SDK Android 14 (android.jar officiel), sherpa-onnx 2.0.1 (classes.jar), kotlinx-coroutines, stdlib | |
| Dépendances remplacées par des stubs écrits par moi | androidx (core, appcompat, activity, lifecycle), Material, ML Kit Translate, play-services tasks, commons-compress, R | les signatures reflètent ma connaissance, non vérifiée |
| Compilation des tests (42) | Réussie | |
| Syntaxe des .kts et du workflow YAML | Valide | analyse syntaxique seulement |
| XML (manifeste, layout, drawables) | Bien formés, références R.id / R.drawable présentes | aapt2 non exécuté |
| APK debug / release | NON PRODUITS | à produire par GitHub Actions |
| Versions Maven (AGP 8.7.3, Gradle 8.10.2, ML Kit 17.0.3, etc.) | Non vérifiées | |
| Alignement 16 Ko des .so de sherpa-onnx (arm64) | Vérifié (16384) | |
