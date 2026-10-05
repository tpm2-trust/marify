package app.kira.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.kira.KiraApp
import app.kira.MainActivity
import app.kira.R
import app.kira.session.SessionMode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while a session runs, with an ongoing
 * "Verifying <machine>…" notification. Stops itself when the session ends.
 */
class AttestationService : LifecycleService() {

    private var watching = false

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification(getString(R.string.notif_connecting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (watching) return START_NOT_STICKY
        watching = true
        val manager = (application as KiraApp).container.sessions
        lifecycleScope.launch {
            manager.active.collectLatest { runner ->
                if (runner == null) {
                    stopSelf()
                    return@collectLatest
                }
                runner.state.collect { s ->
                    if (s.finished) {
                        ServiceCompat.stopForeground(this@AttestationService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val name = s.machineName ?: getString(R.string.a_machine)
                        val text = if (s.mode is SessionMode.Enrol) getString(R.string.notif_enrolling, name)
                        else getString(R.string.notif_verifying, name)
                        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private companion object {
        const val CHANNEL = "session"
        const val NOTIF_ID = 1
    }
}
