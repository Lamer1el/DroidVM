package tech.astrix.droidvm.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import tech.astrix.droidvm.DroidVmApp
import tech.astrix.droidvm.MainActivity
import tech.astrix.droidvm.R
import tech.astrix.droidvm.i18n.tr

/**
 * Transparent foreground service (same model as Termux): it exists only while at least one VM runs,
 * always shows an ongoing notification with the running VMs and a "Stop" button, and never asks the
 * system for battery-optimization exemptions.
 *
 * A partial wake lock is NOT taken automatically. Like Termux's "Acquire wakelock", the user can enable
 * it from the notification (or the Settings default) when a VM must keep computing with the screen off.
 */
class VmService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        wantWakeLock = DroidVmApp.repo.settings.value.wakeLock
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground must be called promptly after startForegroundService(), whatever the action is
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        when (intent?.action) {
            ACTION_STOP_ALL -> VmManager.stopAllGracefully()
            ACTION_FORCE_STOP_ALL -> VmManager.stopAllForcibly()
            ACTION_TOGGLE_WAKELOCK -> wantWakeLock = !wantWakeLock
        }
        applyWakeLock()
        if (VmManager.runningCount() == 0 && intent?.action != null) { stopSelfNow(); return START_NOT_STICKY }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        return START_NOT_STICKY
    }

    private fun applyWakeLock() {
        if (wantWakeLock && VmManager.runningCount() > 0) {
            if (wakeLock?.isHeld != true) {
                wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidVM:vm-user").apply { setReferenceCounted(false); acquire() }
            }
        } else releaseWakeLock()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun stopSelfNow() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // swiping the app away does not kill the VMs (the notification stays, like Termux sessions)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun pending(action: String, req: Int) = PendingIntent.getService(this, req,
        Intent(this, VmService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun buildNotification(): Notification {
        val names = VmManager.runningIds().mapNotNull { VmManager.runningName(it) }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val held = wakeLock?.isHeld == true
        val text = names.joinToString().ifEmpty { tr("Starting…") } + if (held) " • " + tr("wake lock held") else ""
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_vm)
            .setContentTitle(if (names.isEmpty()) "DroidVM" else tr("${names.size} virtual machine(s) running"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, tr("Stop"), pending(ACTION_STOP_ALL, 1))
            .addAction(0, if (held) tr("Release wake lock") else tr("Acquire wake lock"), pending(ACTION_TOGGLE_WAKELOCK, 2))
            .addAction(0, tr("Force stop"), pending(ACTION_FORCE_STOP_ALL, 3))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val CHANNEL = "vms"
        const val NOTIF_ID = 42
        const val ACTION_STOP_ALL = "tech.astrix.droidvm.STOP_ALL"
        const val ACTION_FORCE_STOP_ALL = "tech.astrix.droidvm.FORCE_STOP_ALL"
        const val ACTION_TOGGLE_WAKELOCK = "tech.astrix.droidvm.TOGGLE_WAKELOCK"
        const val ACTION_REFRESH = "tech.astrix.droidvm.REFRESH"

        @Volatile private var wantWakeLock = false

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.vm_service_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) })
            }
        }

        fun refresh(ctx: Context) {
            if (VmManager.runningCount() == 0) {
                ctx.stopService(Intent(ctx, VmService::class.java))
            } else {
                runCatching { ctx.startService(Intent(ctx, VmService::class.java).setAction(ACTION_REFRESH)) }
            }
        }
    }
}
