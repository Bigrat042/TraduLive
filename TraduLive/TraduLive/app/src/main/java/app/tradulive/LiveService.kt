package app.tradulive

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Service au premier plan : il garde la capture du son, Whisper et la bulle de sous-titres
 * en vie même quand TraduLive n'est plus à l'écran (pendant que YouTube ou une autre appli est ouverte).
 */
class LiveService : Service() {

    companion object {
        const val ACTION_STOP = "app.tradulive.STOP"
        const val ACTION_TOGGLE_LOCK = "app.tradulive.TOGGLE_LOCK"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_MIC = "mic"
        private const val CHANNEL_ID = "tradulive"
        private const val NOTIF_ID = 42

        @Volatile
        var running = false
    }

    private var overlay: OverlayController? = null
    private var pipeline: SpeechPipeline? = null
    private var projection: MediaProjection? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_TOGGLE_LOCK) {
            overlay?.toggleLocked()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        val useMic = intent.getBooleanExtra(EXTRA_MIC, false)

        createChannel()
        val type = if (useMic) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!useMic) {
            val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
            val data = readProjectionData(intent)
            if (code != Activity.RESULT_OK || data == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(code, data)
            if (proj == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            // Obligatoire depuis Android 14 : on enregistre un callback avant de capter.
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))
            projection = proj
        }

        // Garde le processeur éveillé même si l'écran s'éteint (ex. podcast, écoute écran éteint).
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tradulive:live").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {
        }

        val prefs = Prefs(this)
        val ov = OverlayController(this, prefs) { stopSelf() }
        overlay = ov
        ov.show()

        val pl = SpeechPipeline(
            context = this,
            prefs = prefs,
            onSubtitle = { sub ->
                ov.showSubtitle(sub)
                if (sub.isFinal) SessionLog.append(applicationContext, sub)
            },
            onStatus = { text -> ov.setStatus(text) },
        )
        pipeline = pl
        pl.start(projection)

        running = true
        refreshTile()
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onScreenChanged()
    }

    override fun onDestroy() {
        running = false
        overlay?.hide()
        overlay = null
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
        val pl = pipeline
        val proj = projection
        pipeline = null
        projection = null
        // L'arrêt de Whisper peut prendre un instant : on le fait hors du thread principal.
        Thread {
            try {
                pl?.stop()
            } catch (_: Throwable) {
            }
            try {
                proj?.stop()
            } catch (_: Throwable) {
            }
        }.start()
        refreshTile()
        super.onDestroy()
    }

    private fun refreshTile() {
        try {
            TileService.requestListeningState(this, ComponentName(this, LiveTileService::class.java))
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun readProjectionData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_DATA)
        }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sous-titres en direct", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, LiveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val lock = PendingIntent.getService(
            this, 2,
            Intent(this, LiveService::class.java).setAction(ACTION_TOGGLE_LOCK),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("TraduLive")
            .setContentText("Sous-titres traduits en direct")
            .setContentIntent(open)
            .addAction(0, "Verrouiller / déverrouiller", lock)
            .addAction(0, "Arrêter", stop)
            .setOngoing(true)
            .build()
    }
}
