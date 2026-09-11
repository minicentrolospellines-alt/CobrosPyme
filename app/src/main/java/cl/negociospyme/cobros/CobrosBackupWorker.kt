package cl.negociospyme.cobros

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CobrosBackupWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("cobrospyme_data", Context.MODE_PRIVATE)

        val root = JSONObject()
            .put("app", "CobrosPyme")
            .put("version", "v1.3")
            .put("fecha", SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date()))
            .put("business", JSONObject(prefs.getString("business", "{}") ?: "{}"))
            .put("clients", JSONArray(prefs.getString("clients", "[]") ?: "[]"))
            .put("debts", JSONArray(prefs.getString("debts", "[]") ?: "[]"))
            .put("payments", JSONArray(prefs.getString("payments", "[]") ?: "[]"))

        prefs.edit().putString("auto_backup", root.toString()).apply()
        return Result.success()
    }
}
