package cl.negociospyme.cobros

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
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

        if (generateRecurringDebts(debts)) {
            prefs.edit().putString("debts", debts.toString()).apply()
        }

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

            val debtId = debt.optLong("id")
            val statusKey = when {
                days < 0 -> "overdue"
                days == 0L -> "today"
                else -> "before"
            }
            if (alreadyNotifiedToday(debtId, statusKey)) continue

            val clientId = debt.optLong("clientId")
            val clientName = findClientName(clients, clientId)
            val amount = (debt.optLong("amount") - debt.optLong("paidAmount")).coerceAtLeast(0L)
            val concept = debt.optString("concept").ifBlank { debt.optString("category", "Cobro") }
            val status = when {
                days < 0 -> "Cobro vencido"
                days == 0L -> "Vence hoy"
                else -> "Próximo vencimiento"
            }

            showNotification(
                id = (debtId % Int.MAX_VALUE).toInt(),
                debtId = debtId,
                title = "$status · $clientName",
                text = "$concept · ${formatCurrency(amount)} · Vence $dueDate"
            )
            markNotifiedToday(debtId, statusKey)
        }
        return Result.success()
    }

    private fun generateRecurringDebts(debts: JSONArray): Boolean {
        var changed = false
        var nextId = System.currentTimeMillis()
        for (i in 0 until debts.length()) {
            nextId = maxOf(nextId, debts.optJSONObject(i)?.optLong("id", 0L) ?: 0L)
        }
        nextId++

        var guard = 0
        while (guard < 240) {
            guard++
            var source: JSONObject? = null

            for (i in 0 until debts.length()) {
                val candidate = debts.optJSONObject(i) ?: continue
                val recurrence = candidate.optString("recurrence", "Ninguno")
                val dueDate = candidate.optString("dueDate")
                if (recurrence == "Ninguno" || dueDate.isBlank()) continue

                val due = daysUntil(dueDate) ?: continue
                if (due > 0L) continue

                val candidateId = candidate.optLong("id")
                var hasChild = false
                for (j in 0 until debts.length()) {
                    val child = debts.optJSONObject(j) ?: continue
                    if (child.optLong("recurrenceParentId", 0L) == candidateId) {
                        hasChild = true
                        break
                    }
                }
                if (!hasChild) {
                    source = candidate
                    break
                }
            }

            val current = source ?: break
            val recurrence = current.optString("recurrence", "Ninguno")
            val nextDate = nextRecurrenceDate(current.optString("dueDate"), recurrence) ?: break

            val next = JSONObject(current.toString())
                .put("id", nextId++)
                .put("paidAmount", 0L)
                .put("paid", false)
                .put("dueDate", nextDate)
                .put("recurrenceParentId", current.optLong("id"))

            debts.put(next)
            changed = true
        }
        return changed
    }

    private fun nextRecurrenceDate(dateText: String, recurrence: String): String? {
        return try {
            val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).apply { isLenient = false }
            val date = formatter.parse(dateText) ?: return null
            val calendar = Calendar.getInstance().apply { time = date }
            when (recurrence) {
                "Semanal" -> calendar.add(Calendar.DAY_OF_MONTH, 7)
                "Mensual" -> {
                    val desiredDay = calendar.get(Calendar.DAY_OF_MONTH)
                    calendar.set(Calendar.DAY_OF_MONTH, 1)
                    calendar.add(Calendar.MONTH, 1)
                    calendar.set(
                        Calendar.DAY_OF_MONTH,
                        desiredDay.coerceAtMost(calendar.getActualMaximum(Calendar.DAY_OF_MONTH))
                    )
                }
                else -> return null
            }
            formatter.format(calendar.time)
        } catch (_: Exception) {
            null
        }
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

    private fun todayKey(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun alreadyNotifiedToday(debtId: Long, statusKey: String): Boolean {
        val prefs = applicationContext.getSharedPreferences("cobrospyme_notifications", Context.MODE_PRIVATE)
        return prefs.getBoolean("${todayKey()}_${debtId}_$statusKey", false)
    }

    private fun markNotifiedToday(debtId: Long, statusKey: String) {
        applicationContext.getSharedPreferences("cobrospyme_notifications", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("${todayKey()}_${debtId}_$statusKey", true)
            .apply()
    }

    private fun formatCurrency(value: Long): String {
        val formatter = NumberFormat.getCurrencyInstance(Locale("es", "CL"))
        formatter.maximumFractionDigits = 0
        formatter.minimumFractionDigits = 0
        return formatter.format(value)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    "cobros_vencimientos",
                    "Vencimientos de cobros",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Avisos de vencimientos y cobros pendientes"
                }
            )
        }
    }

    private fun showNotification(id: Int, debtId: Long, title: String, text: String) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            putExtra("openDebtId", debtId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            id,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, "cobros_vencimientos")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(id, notification)
    }
}
