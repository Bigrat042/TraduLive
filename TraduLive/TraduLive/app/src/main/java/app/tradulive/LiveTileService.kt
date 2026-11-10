package app.tradulive

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Tuile des réglages rapides : démarre ou arrête TraduLive depuis n'importe quelle appli,
 * sans ouvrir TraduLive à la main (il suffit de baisser le volet de notifications).
 */
class LiveTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (LiveService.running) {
            stopService(Intent(this, LiveService::class.java))
            qsTile?.let {
                it.state = Tile.STATE_INACTIVE
                it.updateTile()
            }
            return
        }
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_QUICK_START)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            launchLegacy(intent)
        }
    }

    @Suppress("DEPRECATION")
    private fun launchLegacy(intent: Intent) {
        startActivityAndCollapse(intent)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = if (LiveService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "TraduLive"
        tile.updateTile()
    }
}
