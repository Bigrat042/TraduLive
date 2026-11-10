package app.tradulive

import android.content.Context
import android.content.SharedPreferences

/** Réglages de l'utilisateur, enregistrés sur le téléphone. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("tradulive", Context.MODE_PRIVATE)

    /** Langue dans laquelle la personne veut lire (ex. "fr"). */
    var nativeLang: String
        get() = sp.getString("nativeLang", "fr") ?: "fr"
        set(v) = sp.edit().putString("nativeLang", v).apply()

    /** Autre langue : si la vidéo est dans ma langue, on traduit vers celle-ci. */
    var otherLang: String
        get() = sp.getString("otherLang", "en") ?: "en"
        set(v) = sp.edit().putString("otherLang", v).apply()

    /** true = Whisper détecte la langue parlée tout seul. */
    var autoDetect: Boolean
        get() = sp.getBoolean("autoDetect", true)
        set(v) = sp.edit().putBoolean("autoDetect", v).apply()

    /** Langue de la vidéo quand autoDetect = false. */
    var fixedSource: String
        get() = sp.getString("fixedSource", "es") ?: "es"
        set(v) = sp.edit().putString("fixedSource", v).apply()

    /** true = micro, false = son interne du téléphone. */
    var useMic: Boolean
        get() = sp.getBoolean("useMic", false)
        set(v) = sp.edit().putBoolean("useMic", v).apply()

    var modelId: String
        get() = sp.getString("modelId", "small") ?: "small"
        set(v) = sp.edit().putString("modelId", v).apply()

    /** Texte provisoire affiché pendant que la personne parle. */
    var partials: Boolean
        get() = sp.getBoolean("partials", true)
        set(v) = sp.edit().putBoolean("partials", v).apply()

    /** 0 = économe (3 cœurs), 1 = équilibré (4), 2 = maximum (6). */
    var power: Int
        get() = sp.getInt("power", 1)
        set(v) = sp.edit().putInt("power", v).apply()

    var showOriginal: Boolean
        get() = sp.getBoolean("showOriginal", true)
        set(v) = sp.edit().putBoolean("showOriginal", v).apply()

    var textSizeSp: Int
        get() = sp.getInt("textSizeSp", 18)
        set(v) = sp.edit().putInt("textSizeSp", v).apply()

    /** Opacité du fond des sous-titres, de 30 à 100. */
    var bgOpacity: Int
        get() = sp.getInt("bgOpacity", 80)
        set(v) = sp.edit().putInt("bgOpacity", v).apply()

    /** Position mémorisée de la bulle (-1 = position par défaut). */
    var overlayX: Int
        get() = sp.getInt("overlayX", -1)
        set(v) = sp.edit().putInt("overlayX", v).apply()

    var overlayY: Int
        get() = sp.getInt("overlayY", -1)
        set(v) = sp.edit().putInt("overlayY", v).apply()
}
