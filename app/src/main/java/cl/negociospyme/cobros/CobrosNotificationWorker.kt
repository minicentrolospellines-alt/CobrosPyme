package cl.negociospyme.cobros

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CobrosNotificationWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("cobrospyme_data", Context.MODE_PRIVATE)
        val debts = JSONArray(prefs.getString("debts", "[]") ?: "[]")
        val clients = JSONArray(prefs.getString("clients", "[]") ?: "[]")

        createChannel()

        for (i in 0 until debts.length()) {
            val debt = debts.getJSONObject(i)
            if (debt.optBoolean("paid", false)) continue
            if (!debt.optBoolean("reminderEnabled", true)) continue

            val dueDate = debt.optString("dueDate")
            if (dueDate.isBlank()) continue

            val days = daysUntil(dueDate) ?: continue
            val before = debt.optInt("reminderDaysBefore", 1)
            val shouldNotify = days < 0 || days == 0L || days == before.toLong()
            if (!shouldNotify) continue

            val clientId = debt.optLong("clientId")
            val clientName = findClientName(clients, clientId)
            val amount = (debt.optLong("amount") - debt.optLong("paidAmount")).coerceAtLeast(0L)
            val status = when {
                days < 0 -> "Cobro vencido"
                days == 0L -> "Vence hoy"
                else -> "Próximo vencimiento"
            }

            showNotification(
                id = (debt.optLong("id") % Int.MAX_VALUE).toInt(),
                title = "$status · $clientName",
                text = "Saldo pendiente: $amount · Vence $dueDate"
            )
        }
        return Result.success()
    }

    private fun findClientName(clients: JSONArray, clientId: Long): String {
        for (i in 0 until clients.length()) {
            val c = clients.getJSONObject(i)
            if (c.optLong("id") == clientId) return c.optString("name", "Cliente")
        }
        return "Cliente"
    }

    private fun daysUntil(dateText: String): Long? {
        return try {
            val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).apply { isLenient = false }
            val due = formatter.parse(dateText) ?: return null
            val today = formatter.parse(formatter.format(Date())) ?: return null
            (due.time - today.time) / 86_400_000L
        } catch (_: Exception) {
            null
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    "cobros_vencimientos",
                    "Vencimientos de cobros",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }

    private fun showNotification(id: Int, title: String, text: String) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val notification = NotificationCompat.Builder(applicationContext, "cobros_vencimientos")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(id, notification)
    }
}
