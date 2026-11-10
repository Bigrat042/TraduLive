package app.tradulive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        /** Envoyé par la tuile des réglages rapides pour démarrer sans passer par l'écran. */
        const val ACTION_QUICK_START = "app.tradulive.QUICK_START"
    }

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var spNative: Spinner
    private lateinit var spOther: Spinner
    private lateinit var spFixed: Spinner
    private lateinit var rbAuto: RadioButton
    private lateinit var rbFixed: RadioButton
    private lateinit var rbInternal: RadioButton
    private lateinit var rbMic: RadioButton
    private lateinit var rgModel: RadioGroup
    private lateinit var tvModelStatus: TextView
    private lateinit var progressModel: ProgressBar
    private lateinit var btnDownload: MaterialButton
    private lateinit var btnStart: MaterialButton

    private val codes = Languages.codes
    private var quickStart = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                prepareLanguagesThenStart()
            } else {
                toast("Il faut autoriser le micro/l'audio pour capter le son.")
            }
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                launchService(res.resultCode, data)
            } else {
                quickStart = false
                toast("Capture du son refusée.")
            }
            refreshStartButton()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        bindLanguages()
        bindSource()
        bindModels()
        bindSpeed()
        bindDisplay()
        bindTranscript()

        findViewById<MaterialButton>(R.id.btnOverlayPerm).setOnClickListener { openOverlaySettings() }
        findViewById<MaterialButton>(R.id.btnBattery).setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            toast("Cherche TraduLive dans la liste et choisis « Ne pas optimiser ».")
        }
        btnStart = findViewById(R.id.btnStart)
        btnStart.setOnClickListener { onStartClicked() }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshStartButton()
        handler.post(pollDownload)
    }

    override fun onPause() {
        handler.removeCallbacks(pollDownload)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_QUICK_START) {
            // On consomme l'action pour ne pas la rejouer (rotation, retour à l'appli...).
            setIntent(Intent(this, MainActivity::class.java))
            if (!LiveService.running) {
                quickStart = true
                handler.post { onStartClicked() }
            }
        }
    }

    // ---------- Langues ----------

    private fun bindLanguages() {
        spNative = findViewById(R.id.spNative)
        spOther = findViewById(R.id.spOther)
        spFixed = findViewById(R.id.spFixed)
        rbAuto = findViewById(R.id.rbAuto)
        rbFixed = findViewById(R.id.rbFixed)

        val names = codes.map { Languages.displayName(it) }
        for (sp in listOf(spNative, spOther, spFixed)) {
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        }
        spNative.setSelection(indexOf(prefs.nativeLang))
        spOther.setSelection(indexOf(prefs.otherLang))
        spFixed.setSelection(indexOf(prefs.fixedSource))

        onSelected(spNative) { prefs.nativeLang = codes[it] }
        onSelected(spOther) { prefs.otherLang = codes[it] }
        onSelected(spFixed) { prefs.fixedSource = codes[it] }

        findViewById<MaterialButton>(R.id.btnSwap).setOnClickListener {
            val n = prefs.nativeLang
            val o = prefs.otherLang
            spNative.setSelection(indexOf(o))
            spOther.setSelection(indexOf(n))
        }

        rbAuto.isChecked = prefs.autoDetect
        rbFixed.isChecked = !prefs.autoDetect
        spFixed.visibility = if (prefs.autoDetect) View.GONE else View.VISIBLE
        findViewById<RadioGroup>(R.id.rgDetect).setOnCheckedChangeListener { _, id ->
            val auto = id == R.id.rbAuto
            prefs.autoDetect = auto
            spFixed.visibility = if (auto) View.GONE else View.VISIBLE
        }
    }

    private fun indexOf(code: String): Int = codes.indexOf(code).let { if (it < 0) 0 else it }

    private fun onSelected(sp: Spinner, block: (Int) -> Unit) {
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                block(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ---------- Source audio ----------

    private fun bindSource() {
        rbInternal = findViewById(R.id.rbInternal)
        rbMic = findViewById(R.id.rbMic)
        rbInternal.isChecked = !prefs.useMic
        rbMic.isChecked = prefs.useMic
        findViewById<RadioGroup>(R.id.rgSource).setOnCheckedChangeListener { _, id ->
            prefs.useMic = id == R.id.rbMic
        }
    }

    // ---------- Modèles Whisper ----------

    private fun bindModels() {
        rgModel = findViewById(R.id.rgModel)
        tvModelStatus = findViewById(R.id.tvModelStatus)
        progressModel = findViewById(R.id.progressModel)
        btnDownload = findViewById(R.id.btnDownload)

        val current = WhisperModels.byId(prefs.modelId).id
        for (m in WhisperModels.all) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = "${m.label} — environ ${m.approxMb} Mo"
                tag = m.id
                isChecked = current == m.id
            }
            rgModel.addView(rb)
        }
        rgModel.setOnCheckedChangeListener { group, checkedId ->
            val rb = group.findViewById<RadioButton>(checkedId)
            val id = rb?.tag as? String
            if (id != null) {
                prefs.modelId = id
                refreshModelStatus()
            }
        }
        btnDownload.setOnClickListener { downloadSelectedModel() }
    }

    private fun refreshModelStatus() {
        if (!::tvModelStatus.isInitialized) return
        val m = WhisperModels.byId(prefs.modelId)
        if (WhisperModels.isInstalled(this, m)) {
            tvModelStatus.text = "✓ Modèle installé, prêt à l'emploi (fonctionne ensuite sans internet)."
            btnDownload.visibility = View.GONE
        } else {
            tvModelStatus.text = "Modèle à télécharger une seule fois (Wi-Fi conseillé). Si la connexion coupe, le téléchargement reprend là où il s'est arrêté."
            btnDownload.visibility = View.VISIBLE
            btnDownload.isEnabled = true
        }
    }

    private fun downloadSelectedModel() {
        WhisperModels.startDownload(this, WhisperModels.byId(prefs.modelId))
        refreshDownloadUi()
    }

    /** Le téléchargement vit hors de l'écran : on lit simplement son état toutes les 0,4 s. */
    private val pollDownload = object : Runnable {
        override fun run() {
            refreshDownloadUi()
            handler.postDelayed(this, 400)
        }
    }

    private fun refreshDownloadUi() {
        if (!::progressModel.isInitialized) return
        val id = WhisperModels.downloadingId
        if (id != null) {
            progressModel.visibility = View.VISIBLE
            progressModel.progress = WhisperModels.downloadPercent
            btnDownload.isEnabled = false
            tvModelStatus.text = "Téléchargement du modèle « $id » : ${WhisperModels.downloadPercent} %. " +
                "Tu peux tourner l'écran ; reste dans l'appli pour que ça avance vite."
            return
        }
        progressModel.visibility = View.GONE
        val err = WhisperModels.downloadError
        if (err != null) {
            WhisperModels.clearError()
            toast("Échec du téléchargement : $err. Appuie à nouveau pour reprendre.")
        }
        refreshModelStatus()
    }

    // ---------- Vitesse ----------

    private fun bindSpeed() {
        val sw = findViewById<SwitchCompat>(R.id.swPartials)
        sw.isChecked = prefs.partials
        sw.setOnCheckedChangeListener { _, checked -> prefs.partials = checked }

        val rg = findViewById<RadioGroup>(R.id.rgPower)
        when (prefs.power) {
            0 -> findViewById<RadioButton>(R.id.rbEco).isChecked = true
            2 -> findViewById<RadioButton>(R.id.rbMax).isChecked = true
            else -> findViewById<RadioButton>(R.id.rbNormal).isChecked = true
        }
        rg.setOnCheckedChangeListener { _, id ->
            prefs.power = when (id) {
                R.id.rbEco -> 0
                R.id.rbMax -> 2
                else -> 1
            }
        }
    }

    // ---------- Affichage ----------

    private fun bindDisplay() {
        val sw = findViewById<SwitchCompat>(R.id.swOriginal)
        sw.isChecked = prefs.showOriginal
        sw.setOnCheckedChangeListener { _, checked -> prefs.showOriginal = checked }

        val sbSize = findViewById<SeekBar>(R.id.sbTextSize)
        sbSize.progress = (prefs.textSizeSp - 12).coerceIn(0, 22)
        sbSize.setOnSeekBarChangeListener(simpleSeekListener { prefs.textSizeSp = 12 + it })

        val sbOpacity = findViewById<SeekBar>(R.id.sbOpacity)
        sbOpacity.progress = (prefs.bgOpacity - 30).coerceIn(0, 70)
        sbOpacity.setOnSeekBarChangeListener(simpleSeekListener { prefs.bgOpacity = 30 + it })
    }

    private fun simpleSeekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    // ---------- Transcription ----------

    private fun bindTranscript() {
        findViewById<MaterialButton>(R.id.btnShare).setOnClickListener {
            val text = SessionLog.read(this)
            if (text.isBlank()) {
                toast("Aucune transcription pour l'instant.")
            } else {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text)
                startActivity(Intent.createChooser(send, "Partager la transcription"))
            }
        }
        findViewById<MaterialButton>(R.id.btnClearLog).setOnClickListener {
            SessionLog.clear(this)
            toast("Transcription effacée.")
        }
    }

    // ---------- Démarrage / arrêt ----------

    private fun refreshStartButton() {
        if (!::btnStart.isInitialized) return
        btnStart.text = if (LiveService.running) "Arrêter les sous-titres" else "Démarrer les sous-titres"
    }

    private fun onStartClicked() {
        if (LiveService.running) {
            stopService(Intent(this, LiveService::class.java))
            handler.postDelayed({ refreshStartButton() }, 400)
            return
        }
        if (prefs.nativeLang == prefs.otherLang) {
            quickStart = false
            toast("Choisis deux langues différentes.")
            return
        }
        val model = WhisperModels.byId(prefs.modelId)
        if (!WhisperModels.isInstalled(this, model)) {
            quickStart = false
            toast("Télécharge d'abord le modèle (étape 3).")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            quickStart = false
            toast("Autorise l'affichage par-dessus les autres applis.")
            openOverlaySettings()
            return
        }
        val needed = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
            return
        }
        prepareLanguagesThenStart()
    }

    /** Télécharge les langues de traduction (une seule fois) avant de lancer les sous-titres. */
    private fun prepareLanguagesThenStart() {
        btnStart.isEnabled = false
        btnStart.text = "Préparation des langues…"
        lifecycleScope.launch {
            val engine = TranslatorEngine()
            val native = prefs.nativeLang
            val other = prefs.otherLang
            var ok = engine.prepare(native, other) && engine.prepare(other, native)
            if (ok && !prefs.autoDetect && prefs.fixedSource != native && prefs.fixedSource != other) {
                ok = engine.prepare(prefs.fixedSource, native)
            }
            engine.close()
            btnStart.isEnabled = true
            refreshStartButton()
            if (!ok) {
                quickStart = false
                toast("Impossible de télécharger les langues. Vérifie internet puis réessaie.")
                return@launch
            }
            beginCapture()
        }
    }

    private fun beginCapture() {
        if (prefs.useMic) {
            launchService(null, null)
        } else {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = if (Build.VERSION.SDK_INT >= 34) {
                // Évite le choix « une seule appli » : on veut le son de tout le téléphone.
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                mpm.createScreenCaptureIntent()
            }
            projectionLauncher.launch(intent)
        }
    }

    private fun launchService(resultCode: Int?, data: Intent?) {
        val i = Intent(this, LiveService::class.java)
        if (prefs.useMic) {
            i.putExtra(LiveService.EXTRA_MIC, true)
        } else {
            i.putExtra(LiveService.EXTRA_CODE, resultCode ?: 0)
            i.putExtra(LiveService.EXTRA_DATA, data)
        }
        ContextCompat.startForegroundService(this, i)
        handler.postDelayed({ refreshStartButton() }, 900)
        if (quickStart) {
            // Démarrage depuis la tuile : on retourne tout de suite à l'appli qu'on regardait.
            quickStart = false
            moveTaskToBack(true)
        } else {
            toast("Sous-titres lancés : ouvre ta vidéo.")
        }
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
