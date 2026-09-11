package cl.negociospyme.cobros

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import android.Manifest
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
private const val PREFS = "cobrospyme_data"
private const val KEY_CLIENTS = "clients"
private const val KEY_DEBTS = "debts"
private const val KEY_PAYMENTS = "payments"
private const val KEY_BUSINESS = "business"
private const val KEY_SECURITY_ENABLED = "security_enabled"
private const val KEY_SECURITY_PIN = "security_pin_hash"
private const val KEY_AUTO_BACKUP = "auto_backup"
private const val APP_VERSION_LABEL = "v1.5"

data class Client(
    val id: Long,
    val name: String,
    val phone: String,
    val email: String = "",
    val address: String = "",
    val notes: String = "",
    val rut: String = "",
    val label: String = "",
    val colorTag: String = "Verde"
)

data class Debt(
    val id: Long,
    val clientId: Long,
    val concept: String,
    val amount: Long,
    val paidAmount: Long,
    val dueDate: String,
    val paid: Boolean,
    val category: String = "Otro",
    val reminderEnabled: Boolean = true,
    val reminderDaysBefore: Int = 1,
    val recurrence: String = "Ninguno",
    val recurrenceParentId: Long = 0L
)

data class PaymentRecord(
    val id: Long,
    val debtId: Long,
    val amount: Long,
    val date: String
)

data class BusinessSettings(
    val name: String = "Mi negocio",
    val whatsapp: String = "",
    val bank: String = "",
    val accountType: String = "",
    val accountNumber: String = "",
    val holder: String = "",
    val rut: String = "",
    val paymentNotes: String = "",
    val reminderTemplate: String = "Hola {cliente} 👋\nTe escribimos de {negocio}.\nTienes un saldo pendiente de {saldo}.\nVencimiento: {vencimiento}.",
    val overdueTemplate: String = "Hola {cliente} 👋\nTe escribimos de {negocio}.\nTu cobro por {saldo} se encuentra vencido desde {vencimiento}.",
    val partialTemplate: String = "Hola {cliente} 👋\nGracias por tu abono. Tu saldo pendiente es {saldo}.\nVencimiento: {vencimiento}."
)

data class BackupData(
    val clients: List<Client>,
    val debts: List<Debt>,
    val payments: List<PaymentRecord>,
    val business: BusinessSettings
)

enum class Screen { HOME, CLIENTS, DEBTS, CALENDAR, SETTINGS }

enum class DebtFilter(val label: String) {
    ALL("Todos"),
    PENDING("Pendientes"),
    OVERDUE("Vencidos"),
    PAID("Pagados")
}

class MainActivity : FragmentActivity() {
    private val unlockedState = mutableStateOf(false)
    private val openDebtIdState = mutableStateOf<Long?>(null)
    private val splashVisibleState = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        scheduleCobrosWorkers(this)
        requestNotificationPermissionIfNeeded()
        openDebtIdState.value = intent.getLongExtra("openDebtId", -1L).takeIf { it > 0L }
        unlockedState.value = !loadSecurityEnabled(this)

        setContent {
            MaterialTheme {
                LaunchedEffect(Unit) {
                    delay(1400)
                    splashVisibleState.value = false
                }

                when {
                    splashVisibleState.value -> BrandSplashScreen()
                    unlockedState.value -> {
                        CobrosPymeApp(
                            openDebtId = openDebtIdState.value,
                            onOpenDebtHandled = { openDebtIdState.value = null }
                        )
                    }
                    else -> {
                        LockScreen(
                            onPin = { pin ->
                                if (verifySecurityPin(this, pin)) {
                                    unlockedState.value = true
                                    true
                                } else {
                                    false
                                }
                            },
                            onBiometric = { showBiometricPrompt() }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openDebtIdState.value = intent.getLongExtra("openDebtId", -1L).takeIf { it > 0L }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 901)
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    super.onAuthenticationSucceeded(result)
                    unlockedState.value = true
                }
            }
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Desbloquear CobrosPyme")
            .setSubtitle("Usa tu huella o bloqueo del dispositivo")
            .setAllowedAuthenticators(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        prompt.authenticate(info)
    }
}

@Composable
fun CobrosPymeApp(
    openDebtId: Long? = null,
    onOpenDebtHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val clients = remember {
        mutableStateListOf<Client>().apply { addAll(loadClients(context)) }
    }
    val debts = remember {
        mutableStateListOf<Debt>().apply { addAll(loadDebts(context)) }
    }
    val payments = remember {
        mutableStateListOf<PaymentRecord>().apply { addAll(loadPayments(context)) }
    }
    var business by remember { mutableStateOf(loadBusinessSettings(context)) }

    var screen by remember { mutableStateOf(Screen.HOME) }
    var showClientDialog by remember { mutableStateOf(false) }
    var editingClient by remember { mutableStateOf<Client?>(null) }
    var showDebtDialog by remember { mutableStateOf(false) }
    var paymentDebt by remember { mutableStateOf<Debt?>(null) }
    var selectedClient by remember { mutableStateOf<Client?>(null) }
    var selectedDebt by remember { mutableStateOf<Debt?>(null) }
    var editingPayment by remember { mutableStateOf<PaymentRecord?>(null) }
    var deletingPayment by remember { mutableStateOf<PaymentRecord?>(null) }
    var editingDebt by remember { mutableStateOf<Debt?>(null) }
    var deletingDebt by remember { mutableStateOf<Debt?>(null) }
    var deletingClient by remember { mutableStateOf<Client?>(null) }
    var lastPayment by remember { mutableStateOf<Pair<Debt, Long>?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val generated = generateRecurringDebts(debts.toList())
        if (generated.isNotEmpty()) {
            debts.addAll(generated)
            saveDebts(context, debts)
        }
    }

    LaunchedEffect(openDebtId) {
        val id = openDebtId ?: return@LaunchedEffect
        debts.firstOrNull { it.id == id }?.let { debt ->
            selectedDebt = debt
            screen = Screen.DEBTS
        }
        onOpenDebtHandled()
    }

    fun refreshDebtAfterPayments(debtId: Long, paymentsBeforeChange: List<PaymentRecord>) {
        val debtIndex = debts.indexOfFirst { it.id == debtId }
        if (debtIndex < 0) return
        val debt = debts[debtIndex]
        val oldRecorded = paymentsBeforeChange.filter { it.debtId == debtId }.sumOf { it.amount }
        val legacyPaid = (debt.paidAmount - oldRecorded).coerceAtLeast(0L)
        val newRecorded = payments.filter { it.debtId == debtId }.sumOf { it.amount }
        val newPaid = (legacyPaid + newRecorded).coerceIn(0L, debt.amount)
        debts[debtIndex] = debt.copy(
            paidAmount = newPaid,
            paid = newPaid >= debt.amount
        )
        saveDebts(context, debts)
        if (selectedDebt?.id == debtId) selectedDebt = debts[debtIndex]
    }

    Scaffold(
        bottomBar = {
            BottomMenu(
                current = screen,
                onChange = { screen = it }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (screen != Screen.SETTINGS) {
                Box(modifier = Modifier.padding(bottom = 56.dp)) {
                    FloatingActionButton(
                        onClick = {
                            if (clients.isEmpty()) showClientDialog = true else showDebtDialog = true
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Nuevo cobro")
                    }
                }
            }
        }
    ) { innerPadding ->
        when (screen) {
            Screen.HOME -> HomeScreen(
                modifier = Modifier.padding(innerPadding),
                clients = clients,
                debts = debts,
                payments = payments,
                onNewClient = { showClientDialog = true },
                onNewDebt = {
                    if (clients.isEmpty()) showClientDialog = true else showDebtDialog = true
                },
                onCollectNow = {
                    val next = debts
                        .filter { debtStatus(it) != "Pagado" }
                        .sortedWith(
                            compareBy<Debt> { statusPriority(it) }
                                .thenBy { parseDateOrMax(it.dueDate) }
                                .thenByDescending { it.id }
                        )
                        .firstOrNull()
                    if (next != null) selectedDebt = next else screen = Screen.DEBTS
                },
                onOpenClients = { screen = Screen.CLIENTS },
                onOpenDebts = { screen = Screen.DEBTS },
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenDebt = { selectedDebt = it },
                onBackup = { shareBackup(context, clients, debts, payments, business) }
            )

            Screen.CLIENTS -> ClientsScreen(
                modifier = Modifier.padding(innerPadding),
                clients = clients,
                debts = debts,
                onNewClient = { showClientDialog = true },
                onOpenClient = { selectedClient = it }
            )

            Screen.DEBTS -> DebtsScreen(
                modifier = Modifier.padding(innerPadding),
                clients = clients,
                debts = debts,
                onNewDebt = {
                    if (clients.isEmpty()) showClientDialog = true else showDebtDialog = true
                },
                onPayment = { paymentDebt = it },
                onPaid = { debt ->
                    val index = debts.indexOfFirst { it.id == debt.id }
                    if (index >= 0) {
                        val balanceBefore = remainingBalance(debt)
                        debts[index] = debt.copy(
                            paidAmount = debt.amount,
                            paid = true
                        )
                        saveDebts(context, debts)
                        if (balanceBefore > 0) {
                            val record = PaymentRecord(
                                id = System.currentTimeMillis(),
                                debtId = debt.id,
                                amount = balanceBefore,
                                date = currentDate()
                            )
                            payments.add(record)
                            savePayments(context, payments)
                            lastPayment = debts[index] to balanceBefore
                        }
                    }
                },
                onWhatsApp = { debt ->
                    val client = clients.firstOrNull { it.id == debt.clientId }
                    if (client != null) {
                        sendWhatsAppReminder(context, client, debt, business)
                    }
                },
                onOpenDebt = { selectedDebt = it }
            )

            Screen.CALENDAR -> CalendarScreen(
                modifier = Modifier.padding(innerPadding),
                clients = clients,
                debts = debts,
                onOpenDebt = { selectedDebt = it }
            )

            Screen.SETTINGS -> BusinessSettingsScreen(
                modifier = Modifier.padding(innerPadding),
                settings = business,
                onSave = {
                    business = it
                    saveBusinessSettings(context, it)
                },
                onBackup = { shareBackup(context, clients, debts, payments, business) },
                onImport = { showImportDialog = true },
                onImportClients = { imported ->
                    val existingPhones = clients.map { normalizePhone(it.phone) }.toSet()
                    imported.filter { normalizePhone(it.phone) !in existingPhones }.forEach {
                        clients.add(it)
                    }
                    saveClients(context, clients)
                },
                onRestoreAutoBackup = {
                    restoreAutomaticBackup(context)?.let { imported ->
                        clients.clear(); clients.addAll(imported.clients)
                        debts.clear(); debts.addAll(imported.debts)
                        payments.clear(); payments.addAll(imported.payments)
                        business = imported.business
                        saveClients(context, clients)
                        saveDebts(context, debts)
                        savePayments(context, payments)
                        saveBusinessSettings(context, business)
                        importMessage = "Respaldo automático restaurado ✓"
                    } ?: run {
                        importMessage = "Aún no existe un respaldo automático."
                    }
                }
            )
        }
    }

    if (showClientDialog) {
        ClientDialog(
            title = "Nuevo cliente",
            initial = null,
            onDismiss = { showClientDialog = false },
            onSave = { name, phone, email, address, notes, rut, label, colorTag ->
                clients.add(
                    Client(
                        id = System.currentTimeMillis(),
                        name = name.trim(),
                        phone = phone.trim(),
                        email = email.trim(),
                        address = address.trim(),
                        notes = notes.trim(),
                        rut = rut.trim(),
                        label = label.trim(),
                        colorTag = colorTag
                    )
                )
                saveClients(context, clients)
                showClientDialog = false
            }
        )
    }

    editingClient?.let { client ->
        ClientDialog(
            title = "Editar cliente",
            initial = client,
            onDismiss = { editingClient = null },
            onSave = { name, phone, email, address, notes, rut, label, colorTag ->
                val index = clients.indexOfFirst { it.id == client.id }
                if (index >= 0) {
                    clients[index] = client.copy(
                        name = name.trim(),
                        phone = phone.trim(),
                        email = email.trim(),
                        address = address.trim(),
                        notes = notes.trim(),
                        rut = rut.trim(),
                        label = label.trim(),
                        colorTag = colorTag
                    )
                    saveClients(context, clients)
                    selectedClient = clients[index]
                }
                editingClient = null
            }
        )
    }

    if (showDebtDialog) {
        NewDebtDialog(
            clients = clients,
            onDismiss = { showDebtDialog = false },
            onSave = { clientId, concept, amount, dueDate, category, reminderEnabled, reminderDaysBefore, recurrence ->
                debts.add(
                    Debt(
                        id = System.currentTimeMillis(),
                        clientId = clientId,
                        concept = concept.trim(),
                        amount = amount,
                        paidAmount = 0L,
                        dueDate = dueDate.trim(),
                        paid = false,
                        category = category,
                        reminderEnabled = reminderEnabled,
                        reminderDaysBefore = reminderDaysBefore,
                        recurrence = recurrence
                    )
                )
                saveDebts(context, debts)
                showDebtDialog = false
            }
        )
    }

    paymentDebt?.let { debt ->
        PaymentDialog(
            debt = debt,
            onDismiss = { paymentDebt = null },
            onSave = { payment ->
                val index = debts.indexOfFirst { it.id == debt.id }
                if (index >= 0) {
                    val adjustedPayment = payment.coerceAtMost(remainingBalance(debt))
                    val newPaidAmount = (debt.paidAmount + adjustedPayment).coerceAtMost(debt.amount)
                    debts[index] = debt.copy(
                        paidAmount = newPaidAmount,
                        paid = newPaidAmount >= debt.amount
                    )
                    saveDebts(context, debts)

                    if (adjustedPayment > 0) {
                        payments.add(
                            PaymentRecord(
                                id = System.currentTimeMillis(),
                                debtId = debt.id,
                                amount = adjustedPayment,
                                date = currentDate()
                            )
                        )
                        savePayments(context, payments)
                        lastPayment = debts[index] to adjustedPayment
                    }
                }
                paymentDebt = null
            }
        )
    }

    editingPayment?.let { payment ->
        EditPaymentDialog(
            payment = payment,
            onDismiss = { editingPayment = null },
            onSave = { newAmount, newDate ->
                val before = payments.toList()
                val index = payments.indexOfFirst { it.id == payment.id }
                if (index >= 0) {
                    payments[index] = payment.copy(
                        amount = newAmount,
                        date = newDate
                    )
                    savePayments(context, payments)
                    refreshDebtAfterPayments(payment.debtId, before)
                }
                editingPayment = null
            }
        )
    }

    deletingPayment?.let { payment ->
        ConfirmDialog(
            title = "Eliminar abono",
            message = "¿Seguro que quieres eliminar este abono de ${formatCurrency(payment.amount)}?",
            onDismiss = { deletingPayment = null },
            onConfirm = {
                val before = payments.toList()
                payments.removeAll { it.id == payment.id }
                savePayments(context, payments)
                refreshDebtAfterPayments(payment.debtId, before)
                deletingPayment = null

                coroutineScope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = "Abono eliminado",
                        actionLabel = "Deshacer"
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        val afterDelete = payments.toList()
                        payments.add(payment)
                        payments.sortByDescending { it.id }
                        savePayments(context, payments)
                        refreshDebtAfterPayments(payment.debtId, afterDelete)
                    }
                }
            }
        )
    }

    editingDebt?.let { debt ->
        EditDebtDialog(
            debt = debt,
            clients = clients,
            onDismiss = { editingDebt = null },
            onSave = { clientId, concept, amount, dueDate, category, reminderEnabled, reminderDaysBefore, recurrence ->
                val index = debts.indexOfFirst { it.id == debt.id }
                if (index >= 0) {
                    val paidAmount = debt.paidAmount.coerceAtMost(amount)
                    debts[index] = debt.copy(
                        clientId = clientId,
                        concept = concept.trim(),
                        amount = amount,
                        paidAmount = paidAmount,
                        dueDate = dueDate.trim(),
                        paid = paidAmount >= amount,
                        category = category,
                        reminderEnabled = reminderEnabled,
                        reminderDaysBefore = reminderDaysBefore,
                        recurrence = recurrence
                    )
                    saveDebts(context, debts)
                    selectedDebt = debts[index]
                }
                editingDebt = null
            }
        )
    }

    deletingDebt?.let { debt ->
        ConfirmDialog(
            title = "Eliminar cobro",
            message = "¿Seguro que quieres eliminar este cobro? También se eliminarán sus abonos.",
            onDismiss = { deletingDebt = null },
            onConfirm = {
                val removedPayments = payments.filter { it.debtId == debt.id }
                debts.removeAll { it.id == debt.id }
                payments.removeAll { it.debtId == debt.id }
                saveDebts(context, debts)
                savePayments(context, payments)
                selectedDebt = null
                deletingDebt = null

                coroutineScope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = "Cobro eliminado",
                        actionLabel = "Deshacer"
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        debts.add(debt)
                        debts.sortByDescending { it.id }
                        payments.addAll(removedPayments)
                        payments.sortByDescending { it.id }
                        saveDebts(context, debts)
                        savePayments(context, payments)
                    }
                }
            }
        )
    }

    deletingClient?.let { client ->
        val hasDebts = debts.any { it.clientId == client.id }
        AlertDialog(
            onDismissRequest = { deletingClient = null },
            title = { Text(if (hasDebts) "Cliente con cobros" else "Eliminar cliente") },
            text = {
                Text(
                    if (hasDebts) {
                        "Este cliente tiene cobros registrados. Elimina primero sus cobros."
                    } else {
                        "¿Seguro que quieres eliminar a ${client.name}?"
                    }
                )
            },
            confirmButton = {
                if (!hasDebts) {
                    Button(
                        onClick = {
                            clients.removeAll { it.id == client.id }
                            saveClients(context, clients)
                            selectedClient = null
                            deletingClient = null
                        }
                    ) { Text("Eliminar") }
                } else {
                    TextButton(onClick = { deletingClient = null }) { Text("Entendido") }
                }
            },
            dismissButton = {
                if (!hasDebts) {
                    TextButton(onClick = { deletingClient = null }) { Text("Cancelar") }
                }
            }
        )
    }

    selectedClient?.let { client ->
        ClientDetailDialog(
            client = client,
            debts = debts.filter { it.clientId == client.id },
            payments = payments,
            onDismiss = { selectedClient = null },
            onEdit = { editingClient = client },
            onDelete = { deletingClient = client },
            onWhatsApp = { sendWhatsAppClient(context, client, business) },
            onShareSummary = {
                shareClientSummary(
                    context = context,
                    client = client,
                    debts = debts.filter { it.clientId == client.id },
                    business = business
                )
            },
            onOpenDebt = {
                selectedDebt = it
                selectedClient = null
            }
        )
    }

    selectedDebt?.let { debt ->
        val client = clients.firstOrNull { it.id == debt.clientId }
        DebtDetailDialog(
            debt = debt,
            client = client,
            payments = payments.filter { it.debtId == debt.id },
            onDismiss = { selectedDebt = null },
            onPayment = {
                paymentDebt = debt
                selectedDebt = null
            },
            onWhatsApp = {
                if (client != null) sendWhatsAppReminder(context, client, debt, business)
            },
            onCopyMessage = {
                if (client != null) {
                    copyText(context, buildReminderMessage(client, debt, business))
                }
            },
            onCopyPaymentData = {
                val paymentData = buildPaymentData(business)
                if (paymentData.isNotBlank()) {
                    copyText(context, paymentData)
                    coroutineScope.launch { snackbarHostState.showSnackbar("Datos de transferencia copiados") }
                }
            },
            onEditDebt = { editingDebt = debt },
            onDeleteDebt = { deletingDebt = debt },
            onEditPayment = { editingPayment = it },
            onDeletePayment = { deletingPayment = it }
        )
    }

    lastPayment?.let { (debt, amount) ->
        val client = clients.firstOrNull { it.id == debt.clientId }
        AlertDialog(
            onDismissRequest = { lastPayment = null },
            title = { Text("Abono registrado ✓") },
            text = {
                Text(
                    "Se registró ${formatCurrency(amount)}. " +
                        "Saldo actual: ${formatCurrency(remainingBalance(debt))}."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        sharePaymentReceiptPdf(context, business, client, debt, amount)
                        lastPayment = null
                    }
                ) {
                    Text("Compartir comprobante")
                }
            },
            dismissButton = {
                TextButton(onClick = { lastPayment = null }) { Text("Cerrar") }
            }
        )
    }

    if (showImportDialog) {
        ImportBackupDialog(
            onDismiss = { showImportDialog = false },
            onImport = { raw ->
                val imported = parseBackup(raw)
                if (imported == null) {
                    importMessage = "No se pudo importar. Revisa que el respaldo esté completo."
                } else {
                    clients.clear()
                    clients.addAll(imported.clients)
                    debts.clear()
                    debts.addAll(imported.debts)
                    payments.clear()
                    payments.addAll(imported.payments)
                    business = imported.business
                    saveClients(context, clients)
                    saveDebts(context, debts)
                    savePayments(context, payments)
                    saveBusinessSettings(context, business)
                    importMessage = "Respaldo importado correctamente ✓"
                    showImportDialog = false
                }
            }
        )
    }

    importMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { importMessage = null },
            title = { Text("Importación") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { importMessage = null }) { Text("Aceptar") }
            }
        )
    }
}



@Composable
private fun BrandSplashScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFDF7FF))
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            shadowElevation = 8.dp,
            color = Color.White
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_cobrospyme_icon),
                contentDescription = "CobrosPyme",
                modifier = Modifier
                    .size(126.dp)
                    .padding(10.dp)
            )
        }

        Spacer(modifier = Modifier.height(22.dp))
        Text(
            text = "CobrosPyme",
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Gestión inteligente de cobros",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(30.dp))
        Text(
            text = "Hecho por NegociosPyme",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "by Juan Alarcon",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LockScreen(
    onPin: (String) -> Boolean,
    onBiometric: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("CobrosPyme", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("App protegida")
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit).take(8); error = false },
            label = { Text("PIN") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true
        )
        if (error) Text("PIN incorrecto", color = Color(0xFFC62828))
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                if (!onPin(pin)) error = true
            },
            enabled = pin.length >= 4,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Desbloquear") }
        OutlinedButton(onClick = onBiometric, modifier = Modifier.fillMaxWidth()) {
            Text("Usar huella / bloqueo del teléfono")
        }
    }
}

@Composable
private fun BottomMenu(
    current: Screen,
    onChange: (Screen) -> Unit
) {
    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MenuButton("Inicio", Icons.Default.Home, current == Screen.HOME, Modifier.weight(1f)) {
                onChange(Screen.HOME)
            }
            MenuButton("Clientes", Icons.Default.People, current == Screen.CLIENTS, Modifier.weight(1f)) {
                onChange(Screen.CLIENTS)
            }
            MenuButton("Cobros", Icons.Default.ReceiptLong, current == Screen.DEBTS, Modifier.weight(1f)) {
                onChange(Screen.DEBTS)
            }
            MenuButton("Agenda", Icons.Default.CalendarMonth, current == Screen.CALENDAR, Modifier.weight(1f)) {
                onChange(Screen.CALENDAR)
            }
            MenuButton("Ajustes", Icons.Default.Settings, current == Screen.SETTINGS, Modifier.weight(1f)) {
                onChange(Screen.SETTINGS)
            }
        }
    }
}

@Composable
private fun MenuButton(
    text: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val background = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val foreground = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .height(58.dp)
            .background(background, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = foreground,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = text,
                color = foreground,
                fontSize = 9.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}


@Composable
private fun HomeScreen(
    modifier: Modifier,
    clients: List<Client>,
    debts: List<Debt>,
    payments: List<PaymentRecord>,
    onNewClient: () -> Unit,
    onNewDebt: () -> Unit,
    onCollectNow: () -> Unit,
    onOpenClients: () -> Unit,
    onOpenDebts: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDebt: (Debt) -> Unit,
    onBackup: () -> Unit
) {
    val totalPending = debts.sumOf { remainingBalance(it) }
    val totalCollected = debts.sumOf { it.paidAmount.coerceAtMost(it.amount) }
    val collectedToday = payments.filter { it.date == currentDate() }.sumOf { it.amount }
    val collectedThisMonth = payments.filter { isCurrentMonth(it.date) }.sumOf { it.amount }
    val now = Calendar.getInstance()
    val collectedThisWeek = payments.filter { isCurrentWeek(it.date, now) }.sumOf { it.amount }
    val overdueAmount = debts.filter { debtStatus(it) == "Vencido" }.sumOf { remainingBalance(it) }
    val dueToday = debts.count { debtStatus(it) != "Pagado" && daysUntilDue(it.dueDate) == 0L }
    val overdueCount = debts.count { debtStatus(it) == "Vencido" }
    val recurringActive = debts.count { it.recurrence != "Ninguno" && debtStatus(it) != "Pagado" }
    val dueNext7 = debts.count {
        val days = daysUntilDue(it.dueDate)
        debtStatus(it) != "Pagado" && days != null && days in 1L..7L
    }
    val totalOriginal = debts.sumOf { it.amount }
    val recoveryPercent = if (totalOriginal > 0) ((totalCollected * 100) / totalOriginal).toInt() else 0

    val topDebtor = clients
        .map { client -> client to debts.filter { it.clientId == client.id }.sumOf { remainingBalance(it) } }
        .filter { it.second > 0 }
        .maxByOrNull { it.second }

    val topCategory = debts
        .filter { remainingBalance(it) > 0 }
        .groupBy { it.category }
        .mapValues { (_, list) -> list.sumOf { remainingBalance(it) } }
        .maxByOrNull { it.value }

    val priorityDebts = debts.sortedWith(
        compareBy<Debt> { statusPriority(it) }
            .thenBy { parseDateOrMax(it.dueDate) }
            .thenByDescending { it.id }
    )

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text("CobrosPyme", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("Gestión inteligente de cobros", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(APP_VERSION_LABEL, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Total por cobrar")
                    Text(formatCurrency(totalPending), fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    Text("Recuperación: $recoveryPercent%")
                    LinearProgressIndicator(
                        progress = (recoveryPercent / 100f).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DashboardMetric("Hoy", formatCurrency(collectedToday), Color(0xFFE4F5E7), Modifier.weight(1f))
                DashboardMetric("Semana", formatCurrency(collectedThisWeek), Color(0xFFEAF2FF), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DashboardMetric("Mes", formatCurrency(collectedThisMonth), Color(0xFFE4F5E7), Modifier.weight(1f))
                DashboardMetric("Vencido", formatCurrency(overdueAmount), Color(0xFFFFE5E5), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DashboardMetric("Vence hoy", dueToday.toString(), Color(0xFFFFF0D9), Modifier.weight(1f))
                DashboardMetric("Vencidos", overdueCount.toString(), Color(0xFFFFE5E5), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DashboardMetric("Recurrentes", recurringActive.toString(), Color(0xFFF0E8FF), Modifier.weight(1f))
                DashboardMetric("Próx. 7 días", dueNext7.toString(), Color(0xFFEAF2FF), Modifier.weight(1f))
            }
        }

        if (topDebtor != null || topCategory != null) {
            item {
                Text("Análisis rápido", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                topDebtor?.let {
                    InsightCard("Mayor deuda", "${it.first.name} · ${formatCurrency(it.second)}")
                }
                topCategory?.let {
                    InsightCard("Categoría con más saldo", "${it.key} · ${formatCurrency(it.value)}")
                }
                InsightCard("Recuperado", "$recoveryPercent% del total registrado")
            }
        }

        item {
            Button(
                onClick = onCollectNow,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Cobrar ahora") }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onNewDebt, modifier = Modifier.weight(1f)) { Text("+ Deuda") }
                OutlinedButton(onClick = onNewClient, modifier = Modifier.weight(1f)) { Text("+ Cliente") }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Prioridad de cobro", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = onOpenDebts) { Text("Ver todos") }
            }
        }

        if (priorityDebts.isEmpty()) {
            item { EmptyCard("Sin cobros", "Registra tu primer cobro para comenzar.") }
        } else {
            items(priorityDebts.take(5), key = { it.id }) { debt ->
                val client = clients.firstOrNull { it.id == debt.clientId }
                DebtCard(
                    clientName = client?.name ?: "Cliente",
                    debt = debt,
                    showActions = false,
                    onOpen = { onOpenDebt(debt) }
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TextButton(onClick = onBackup) { Text("Respaldar") }
                Text(
                    text = "Hecho por NegociosPyme",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "by Juan Alarcon",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DashboardMetric(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(94.dp),
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun InsightCard(title: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, fontSize = 13.sp)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}


enum class StatusType { OVERDUE, PENDING, PAID, NEUTRAL }

@Composable
private fun SummaryCard(title: String, value: String, type: StatusType) {
    val stripe = when (type) {
        StatusType.OVERDUE -> Color(0xFFC62828)
        StatusType.PENDING -> Color(0xFFEF6C00)
        StatusType.PAID -> Color(0xFF2E7D32)
        StatusType.NEUTRAL -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .background(stripe)
                    .padding(horizontal = 3.dp, vertical = 34.dp)
            )
            Column(modifier = Modifier.padding(16.dp)) {
                Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ClientsScreen(
    modifier: Modifier,
    clients: List<Client>,
    debts: List<Debt>,
    onNewClient: () -> Unit,
    onOpenClient: (Client) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = clients.filter {
        query.isBlank() ||
            it.name.contains(query, true) ||
            it.phone.contains(query, true) ||
            it.rut.contains(query, true) ||
            it.address.contains(query, true) ||
            it.label.contains(query, true)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Clientes", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Buscar cliente") },
                placeholder = { Text("Nombre, teléfono, RUT, dirección o etiqueta") },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onNewClient, modifier = Modifier.fillMaxWidth()) { Text("+ Nuevo cliente") }
        }

        if (filtered.isEmpty()) {
            item { EmptyCard("Sin resultados", "No encontramos clientes con esa búsqueda.") }
        } else {
            items(filtered.sortedBy { it.name.lowercase() }, key = { it.id }) { client ->
                val clientDebts = debts.filter { it.clientId == client.id }
                val pending = clientDebts.sumOf { remainingBalance(it) }
                val overdue = clientDebts.count { debtStatus(it) == "Vencido" }

                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenClient(client) },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(client.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Column(horizontalAlignment = Alignment.End) {
                                ClientStatusBadge(clientAccountStatus(clientDebts))
                                if (client.label.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    ClientTag(client)
                                }
                            }
                        }
                        if (client.rut.isNotBlank()) Text("RUT: ${client.rut}")
                        Text(client.phone.ifBlank { "Sin teléfono" })
                        if (client.address.isNotBlank()) Text(client.address)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Pendiente: ${formatCurrency(pending)}", fontWeight = FontWeight.Bold)
                        if (overdue > 0) Text("Vencidos: $overdue", color = Color(0xFFC62828))
                    }
                }
            }
        }
    }
}

@Composable
private fun ClientTag(client: Client) {
    if (client.label.isBlank()) return
    Surface(
        color = clientTagColor(client.colorTag),
        shape = RoundedCornerShape(50)
    ) {
        Text(
            client.label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ClientStatusBadge(status: String) {
    val background = when (status) {
        "Vencido" -> Color(0xFFFFE5E5)
        "Pendiente" -> Color(0xFFFFF0D9)
        else -> Color(0xFFE4F5E7)
    }
    val foreground = when (status) {
        "Vencido" -> Color(0xFFB71C1C)
        "Pendiente" -> Color(0xFFE65100)
        else -> Color(0xFF1B5E20)
    }
    Surface(color = background, shape = RoundedCornerShape(50)) {
        Text(
            status,
            color = foreground,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}



@Composable
private fun DebtsScreen(
    modifier: Modifier,
    clients: List<Client>,
    debts: List<Debt>,
    onNewDebt: () -> Unit,
    onPayment: (Debt) -> Unit,
    onPaid: (Debt) -> Unit,
    onWhatsApp: (Debt) -> Unit,
    onOpenDebt: (Debt) -> Unit
) {
    var filter by remember { mutableStateOf(DebtFilter.ALL) }
    var filterMenu by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var clientFilterId by remember { mutableStateOf<Long?>(null) }
    var clientMenu by remember { mutableStateOf(false) }

    val filteredDebts = debts.filter { debt ->
        val statusMatches = when (filter) {
            DebtFilter.ALL -> true
            DebtFilter.PENDING -> debtStatus(debt) == "Pendiente"
            DebtFilter.OVERDUE -> debtStatus(debt) == "Vencido"
            DebtFilter.PAID -> debtStatus(debt) == "Pagado"
        }
        val client = clients.firstOrNull { it.id == debt.clientId }
        val textMatches = query.isBlank() ||
            debt.concept.contains(query, ignoreCase = true) ||
            debt.category.contains(query, ignoreCase = true) ||
            debt.amount.toString().contains(query.filter(Char::isDigit)) ||
            remainingBalance(debt).toString().contains(query.filter(Char::isDigit)) ||
            client?.name?.contains(query, ignoreCase = true) == true
        val clientMatches = clientFilterId == null || debt.clientId == clientFilterId
        statusMatches && textMatches && clientMatches
    }.sortedWith(
        compareBy<Debt> { statusPriority(it) }
            .thenBy { parseDateOrMax(it.dueDate) }
            .thenByDescending { it.id }
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Cobros", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Buscar") },
                placeholder = { Text("Cliente, categoría, concepto o monto") },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onNewDebt, modifier = Modifier.fillMaxWidth()) {
                Text("+ Nueva deuda")
            }

            Spacer(modifier = Modifier.height(8.dp))
            Box {
                OutlinedButton(
                    onClick = { filterMenu = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Estado: ${filter.label}")
                }
                DropdownMenu(
                    expanded = filterMenu,
                    onDismissRequest = { filterMenu = false }
                ) {
                    DebtFilter.values().forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                filter = option
                                filterMenu = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Box {
                OutlinedButton(
                    onClick = { clientMenu = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val selectedName = clients.firstOrNull { it.id == clientFilterId }?.name ?: "Todos"
                    Text("Cliente: $selectedName")
                }
                DropdownMenu(
                    expanded = clientMenu,
                    onDismissRequest = { clientMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Todos") },
                        onClick = {
                            clientFilterId = null
                            clientMenu = false
                        }
                    )
                    clients.sortedBy { it.name.lowercase() }.forEach { client ->
                        DropdownMenuItem(
                            text = { Text(client.name) },
                            onClick = {
                                clientFilterId = client.id
                                clientMenu = false
                            }
                        )
                    }
                }
            }
        }

        if (filteredDebts.isEmpty()) {
            item {
                EmptyCard("Sin cobros", "No hay cobros para estos filtros.")
            }
        } else {
            items(filteredDebts, key = { it.id }) { debt ->
                val client = clients.firstOrNull { it.id == debt.clientId }
                DebtCard(
                    clientName = client?.name ?: "Cliente",
                    debt = debt,
                    showActions = true,
                    onPayment = { onPayment(debt) },
                    onPaid = { onPaid(debt) },
                    onWhatsApp = { onWhatsApp(debt) },
                    onOpen = { onOpenDebt(debt) }
                )
            }
        }
    }
}


@Composable
private fun DebtCard(
    clientName: String,
    debt: Debt,
    showActions: Boolean,
    onPayment: () -> Unit = {},
    onPaid: () -> Unit = {},
    onWhatsApp: () -> Unit = {},
    onOpen: () -> Unit = {}
) {
    val balance = remainingBalance(debt)
    val status = debtStatus(debt)
    val progress = if (debt.amount > 0) {
        debt.paidAmount.coerceIn(0L, debt.amount).toFloat() / debt.amount.toFloat()
    } else 0f
    val cardColor = when (status) {
        "Vencido" -> Color(0xFFFFF3F3)
        "Pagado" -> Color(0xFFF2FBF3)
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(clientName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                StatusBadge(status)
            }
            Text("${debt.category} · ${debt.concept.ifBlank { "Cobro" }}")
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Total: ${formatCurrency(debt.amount)}",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            if (debt.paidAmount > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Abonado: ${formatCurrency(debt.paidAmount.coerceAtMost(debt.amount))} " +
                        "(${(progress * 100).toInt()}%)"
                )
            }
            Text(
                "Saldo: ${formatCurrency(balance)}",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text("Vence: ${debt.dueDate.ifBlank { "Sin fecha" }}")
            if (debt.recurrence != "Ninguno") {
                Text("Recurrente: ${debt.recurrence}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
            if (debt.reminderEnabled) {
                Text("Recordatorio: ${debt.reminderDaysBefore} día(s) antes", fontSize = 12.sp)
            }

            if (showActions && status != "Pagado") {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onPayment,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Registrar abono")
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onWhatsApp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("WhatsApp")
                }
                TextButton(
                    onClick = onPaid,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Marcar saldo completo como pagado")
                }
            }
        }
    }
}


@Composable
private fun StatusBadge(status: String) {
    val background = when (status) {
        "Vencido" -> Color(0xFFFFE5E5)
        "Pagado" -> Color(0xFFE4F5E7)
        else -> Color(0xFFFFF0D9)
    }
    val foreground = when (status) {
        "Vencido" -> Color(0xFFB71C1C)
        "Pagado" -> Color(0xFF1B5E20)
        else -> Color(0xFFE65100)
    }

    Surface(
        color = background,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = status,
            color = foreground,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun ClientDetailDialog(
    client: Client,
    debts: List<Debt>,
    payments: List<PaymentRecord>,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onWhatsApp: () -> Unit,
    onShareSummary: () -> Unit,
    onOpenDebt: (Debt) -> Unit
) {
    val pending = debts.sumOf { remainingBalance(it) }
    val collected = debts.sumOf { it.paidAmount.coerceAtMost(it.amount) }
    val overdue = debts.count { debtStatus(it) == "Vencido" }
    val paymentCount = payments.count { payment -> debts.any { it.id == payment.debtId } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(client.name) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(client.phone.ifBlank { "Sin teléfono" })
                    if (client.rut.isNotBlank()) Text("RUT: ${client.rut}")
                    if (client.label.isNotBlank()) Text("Etiqueta: ${client.label}")
                    if (client.email.isNotBlank()) Text(client.email)
                    if (client.address.isNotBlank()) Text(client.address)
                    if (client.notes.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Notas: ${client.notes}")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Pendiente: ${formatCurrency(pending)}", fontWeight = FontWeight.Bold)
                    Text("Pagado: ${formatCurrency(collected)}")
                    Text("Vencidos: $overdue")
                    Text("Abonos registrados: $paymentCount")
                }

                if (debts.isNotEmpty()) {
                    item {
                        Text("Historial de cobros", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    items(debts.sortedByDescending { it.id }, key = { it.id }) { debt ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenDebt(debt) }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(debt.concept.ifBlank { "Cobro" }, fontWeight = FontWeight.Bold)
                                Text("Saldo: ${formatCurrency(remainingBalance(debt))}")
                                Text("Estado: ${debtStatus(debt)}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
        dismissButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onEdit) { Text("Editar") }
                TextButton(onClick = onWhatsApp) { Text("WhatsApp") }
                TextButton(onClick = onShareSummary) { Text("Compartir resumen") }
                TextButton(onClick = onDelete) { Text("Eliminar cliente", color = Color(0xFFC62828)) }
            }
        }
    )
}

@Composable
private fun DebtDetailDialog(
    debt: Debt,
    client: Client?,
    payments: List<PaymentRecord>,
    onDismiss: () -> Unit,
    onPayment: () -> Unit,
    onWhatsApp: () -> Unit,
    onCopyMessage: () -> Unit,
    onCopyPaymentData: () -> Unit,
    onEditDebt: () -> Unit,
    onDeleteDebt: () -> Unit,
    onEditPayment: (PaymentRecord) -> Unit,
    onDeletePayment: (PaymentRecord) -> Unit
) {
    val recorded = payments.sumOf { it.amount }
    val legacyPaid = (debt.paidAmount - recorded).coerceAtLeast(0L)
    val progress = if (debt.amount > 0) {
        debt.paidAmount.coerceIn(0L, debt.amount).toFloat() / debt.amount.toFloat()
    } else 0f

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(debt.concept.ifBlank { "Detalle del cobro" }) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(client?.name ?: "Cliente", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    StatusBadge(debtStatus(debt))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Categoría: ${debt.category}")
                    Text("Total: ${formatCurrency(debt.amount)}")
                    Text("Abonado: ${formatCurrency(debt.paidAmount)}")
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Progreso: ${(progress * 100).toInt()}%")
                    Text("Saldo: ${formatCurrency(remainingBalance(debt))}", fontWeight = FontWeight.Bold)
                    Text("Vence: ${debt.dueDate.ifBlank { "Sin fecha" }}")
                    if (debt.recurrence != "Ninguno") {
                        Text("Repetición: ${debt.recurrence}", color = MaterialTheme.colorScheme.primary)
                    }
                }

                if (legacyPaid > 0 || payments.isNotEmpty()) {
                    item {
                        Text("Historial de abonos", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                    if (legacyPaid > 0) {
                        item {
                            PaymentRow(
                                label = "Abono anterior",
                                amount = legacyPaid
                            )
                        }
                    }
                    items(payments.sortedByDescending { it.id }, key = { it.id }) { payment ->
                        EditablePaymentRow(
                            payment = payment,
                            onEdit = { onEditPayment(payment) },
                            onDelete = { onDeletePayment(payment) }
                        )
                    }
                } else {
                    item { Text("Aún no hay abonos registrados.") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
        dismissButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (debtStatus(debt) != "Pagado") {
                    TextButton(onClick = onPayment) { Text("Registrar abono") }
                    TextButton(onClick = onWhatsApp) { Text("WhatsApp") }
                    TextButton(onClick = onCopyMessage) { Text("Copiar mensaje") }
                    TextButton(onClick = onCopyPaymentData) { Text("Copiar transferencia") }
                }
                TextButton(onClick = onEditDebt) { Text("Editar cobro") }
                TextButton(onClick = onDeleteDebt) {
                    Text("Eliminar cobro", color = Color(0xFFC62828))
                }
            }
        }
    )
}

@Composable
private fun PaymentRow(label: String, amount: Long) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label)
            Text(formatCurrency(amount), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EditablePaymentRow(
    payment: PaymentRecord,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(payment.date)
                Text(formatCurrency(payment.amount), fontWeight = FontWeight.Bold)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onEdit) { Text("Editar") }
                TextButton(onClick = onDelete) {
                    Text("Eliminar", color = Color(0xFFC62828))
                }
            }
        }
    }
}

@Composable
private fun EmptyCard(title: String, subtitle: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ClientDialog(
    title: String,
    initial: Client?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, String, String, String) -> Unit
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var phone by remember(initial?.id) { mutableStateOf(initial?.phone ?: "") }
    var email by remember(initial?.id) { mutableStateOf(initial?.email ?: "") }
    var address by remember(initial?.id) { mutableStateOf(initial?.address ?: "") }
    var notes by remember(initial?.id) { mutableStateOf(initial?.notes ?: "") }
    var rut by remember(initial?.id) { mutableStateOf(initial?.rut ?: "") }
    var label by remember(initial?.id) { mutableStateOf(initial?.label ?: "") }
    var colorTag by remember(initial?.id) { mutableStateOf(initial?.colorTag ?: "Verde") }
    var colorMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true) }
                item {
                    OutlinedTextField(
                        phone, { phone = it },
                        label = { Text("WhatsApp / teléfono") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true
                    )
                }
                item { OutlinedTextField(rut, { rut = it }, label = { Text("RUT (opcional)") }, singleLine = true) }
                item { OutlinedTextField(email, { email = it }, label = { Text("Correo (opcional)") }, singleLine = true) }
                item { OutlinedTextField(address, { address = it }, label = { Text("Dirección (opcional)") }) }
                item { OutlinedTextField(label, { label = it }, label = { Text("Etiqueta (ej: VIP, Moroso)") }, singleLine = true) }
                item {
                    Box {
                        OutlinedButton(onClick = { colorMenu = true }) { Text("Color: $colorTag") }
                        DropdownMenu(expanded = colorMenu, onDismissRequest = { colorMenu = false }) {
                            listOf("Verde", "Azul", "Naranjo", "Rojo", "Gris").forEach { c ->
                                DropdownMenuItem(
                                    text = { Text(c) },
                                    onClick = { colorTag = c; colorMenu = false }
                                )
                            }
                        }
                    }
                }
                item { OutlinedTextField(notes, { notes = it }, label = { Text("Observaciones") }) }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, phone, email, address, notes, rut, label, colorTag) },
                enabled = name.isNotBlank()
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}


@Composable
private fun NewDebtDialog(
    clients: List<Client>,
    onDismiss: () -> Unit,
    onSave: (Long, String, Long, String, String, Boolean, Int, String) -> Unit
) {
    DebtEditorDialog(
        title = "Nueva deuda",
        clients = clients,
        initial = null,
        onDismiss = onDismiss,
        onSave = onSave
    )
}

@Composable
private fun EditDebtDialog(
    debt: Debt,
    clients: List<Client>,
    onDismiss: () -> Unit,
    onSave: (Long, String, Long, String, String, Boolean, Int, String) -> Unit
) {
    DebtEditorDialog(
        title = "Editar cobro",
        clients = clients,
        initial = debt,
        onDismiss = onDismiss,
        onSave = onSave
    )
}

@Composable
private fun DebtEditorDialog(
    title: String,
    clients: List<Client>,
    initial: Debt?,
    onDismiss: () -> Unit,
    onSave: (Long, String, Long, String, String, Boolean, Int, String) -> Unit
) {
    val context = LocalContext.current
    var selectedClientId by remember(initial?.id) {
        mutableStateOf(initial?.clientId ?: clients.firstOrNull()?.id ?: 0L)
    }
    var concept by remember(initial?.id) { mutableStateOf(initial?.concept ?: "") }
    var amountText by remember(initial?.id) { mutableStateOf(initial?.amount?.toString() ?: "") }
    var dueDate by remember(initial?.id) { mutableStateOf(initial?.dueDate ?: "") }
    var category by remember(initial?.id) { mutableStateOf(initial?.category ?: "Otro") }
    var reminderEnabled by remember(initial?.id) { mutableStateOf(initial?.reminderEnabled ?: true) }
    var reminderDays by remember(initial?.id) { mutableStateOf(initial?.reminderDaysBefore ?: 1) }
    var recurrence by remember(initial?.id) { mutableStateOf(initial?.recurrence ?: "Ninguno") }
    var clientMenu by remember { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf(false) }
    var reminderMenu by remember { mutableStateOf(false) }
    var recurrenceMenu by remember { mutableStateOf(false) }

    val selectedClient = clients.firstOrNull { it.id == selectedClientId }
    val amount = amountText.filter(Char::isDigit).toLongOrNull() ?: 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Box {
                        OutlinedButton(onClick = { clientMenu = true }) {
                            Text("Cliente: ${selectedClient?.name ?: "Seleccionar"}")
                        }
                        DropdownMenu(clientMenu, { clientMenu = false }) {
                            clients.forEach { client ->
                                DropdownMenuItem(
                                    text = { Text(client.name) },
                                    onClick = { selectedClientId = client.id; clientMenu = false }
                                )
                            }
                        }
                    }
                }
                item { OutlinedTextField(concept, { concept = it }, label = { Text("Concepto") }, singleLine = true) }
                item {
                    OutlinedTextField(
                        amountText,
                        { amountText = it.filter(Char::isDigit) },
                        label = { Text("Monto") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { showDatePicker(context, dueDate) { dueDate = it } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (dueDate.isBlank()) "Seleccionar vencimiento" else "Vencimiento: $dueDate")
                    }
                }
                item {
                    Box {
                        OutlinedButton(onClick = { categoryMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Categoría: $category")
                        }
                        DropdownMenu(categoryMenu, { categoryMenu = false }) {
                            listOf("Cuota", "Préstamo", "Servicio", "Arriendo", "Venta", "Otro").forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item) },
                                    onClick = { category = item; categoryMenu = false }
                                )
                            }
                        }
                    }
                }
                item {
                    Box {
                        OutlinedButton(onClick = { recurrenceMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (recurrence == "Ninguno") "Repetición: No se repite" else "Repetición: $recurrence")
                        }
                        DropdownMenu(recurrenceMenu, { recurrenceMenu = false }) {
                            listOf("Ninguno", "Semanal", "Mensual").forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(if (option == "Ninguno") "No se repite" else option) },
                                    onClick = { recurrence = option; recurrenceMenu = false }
                                )
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = { reminderEnabled = !reminderEnabled },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (reminderEnabled) "Recordatorio automático: Sí" else "Recordatorio automático: No")
                    }
                }
                if (reminderEnabled) {
                    item {
                        Box {
                            OutlinedButton(onClick = { reminderMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Avisar $reminderDays día(s) antes")
                            }
                            DropdownMenu(reminderMenu, { reminderMenu = false }) {
                                listOf(0, 1, 2, 3, 5, 7).forEach { d ->
                                    DropdownMenuItem(
                                        text = { Text(if (d == 0) "El mismo día" else "$d día(s) antes") },
                                        onClick = { reminderDays = d; reminderMenu = false }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = selectedClientId != 0L && amount > 0,
                onClick = {
                    onSave(
                        selectedClientId, concept, amount, dueDate,
                        category, reminderEnabled, reminderDays, recurrence
                    )
                }
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}


@Composable
private fun PaymentDialog(
    debt: Debt,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    val balance = remainingBalance(debt)
    val payment = amountText.filter { it.isDigit() }.toLongOrNull() ?: 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Registrar abono") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Saldo actual: ${formatCurrency(balance)}")
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter(Char::isDigit) },
                    label = { Text("Monto del abono") },
                    placeholder = { Text("Ej: 10000") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                if (payment > balance && balance > 0) {
                    Text("El abono se ajustará automáticamente al saldo pendiente.")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(payment.coerceAtMost(balance)) },
                enabled = payment > 0 && balance > 0
            ) {
                Text("Guardar abono")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun EditPaymentDialog(
    payment: PaymentRecord,
    onDismiss: () -> Unit,
    onSave: (Long, String) -> Unit
) {
    var amountText by remember(payment.id) { mutableStateOf(payment.amount.toString()) }
    var dateText by remember(payment.id) { mutableStateOf(payment.date) }
    val amount = amountText.filter(Char::isDigit).toLongOrNull() ?: 0L
    val validDate = isValidDate(dateText)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar abono") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter(Char::isDigit) },
                    label = { Text("Monto") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text("Fecha") },
                    placeholder = { Text("dd/MM/yyyy") },
                    singleLine = true
                )
                if (!validDate) {
                    Text("Fecha inválida. Usa dd/MM/yyyy.", color = Color(0xFFC62828))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(amount, dateText.trim()) },
                enabled = amount > 0 && validDate
            ) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Eliminar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

enum class AgendaRange(val label: String) {
    TODAY("Hoy"),
    WEEK("Semana"),
    MONTH("Mes")
}

@Composable
private fun CalendarScreen(
    modifier: Modifier,
    clients: List<Client>,
    debts: List<Debt>,
    onOpenDebt: (Debt) -> Unit
) {
    val context = LocalContext.current
    var selectedDate by remember { mutableStateOf(currentDate()) }
    var range by remember { mutableStateOf(AgendaRange.TODAY) }

    val visible = debts
        .filter { debt ->
            if (debtStatus(debt) == "Pagado") return@filter false
            when (range) {
                AgendaRange.TODAY -> debt.dueDate == selectedDate
                AgendaRange.WEEK -> {
                    val days = daysBetween(selectedDate, debt.dueDate)
                    days != null && days in 0L..6L
                }
                AgendaRange.MONTH -> sameMonthYear(selectedDate, debt.dueDate)
            }
        }
        .sortedWith(compareBy<Debt> { statusPriority(it) }.thenBy { parseDateOrMax(it.dueDate) })

    val total = visible.sumOf { remainingBalance(it) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Agenda de cobros", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Vencimientos por día, semana o mes", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AgendaRange.values().forEach { option ->
                    if (range == option) {
                        Button(
                            onClick = { range = option },
                            modifier = Modifier.weight(1f)
                        ) { Text(option.label) }
                    } else {
                        OutlinedButton(
                            onClick = { range = option },
                            modifier = Modifier.weight(1f)
                        ) { Text(option.label) }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showDatePicker(context, selectedDate) { selectedDate = it } },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Fecha base: $selectedDate")
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${range.label}: ${visible.size} cobro(s)")
                    Text(formatCurrency(total), fontWeight = FontWeight.Bold)
                }
            }
        }

        if (visible.isEmpty()) {
            item { EmptyCard("Sin vencimientos", "No hay cobros en el período seleccionado.") }
        } else {
            items(visible, key = { it.id }) { debt ->
                val client = clients.firstOrNull { it.id == debt.clientId }
                DebtCard(
                    clientName = client?.name ?: "Cliente",
                    debt = debt,
                    showActions = false,
                    onOpen = { onOpenDebt(debt) }
                )
            }
        }
    }
}

@Composable
private fun BusinessSettingsScreen(
    modifier: Modifier,
    settings: BusinessSettings,
    onSave: (BusinessSettings) -> Unit,
    onBackup: () -> Unit,
    onImport: () -> Unit,
    onImportClients: (List<Client>) -> Unit,
    onRestoreAutoBackup: () -> Unit
) {
    val context = LocalContext.current
    var name by remember(settings) { mutableStateOf(settings.name) }
    var whatsapp by remember(settings) { mutableStateOf(settings.whatsapp) }
    var bank by remember(settings) { mutableStateOf(settings.bank) }
    var accountType by remember(settings) { mutableStateOf(settings.accountType) }
    var accountNumber by remember(settings) { mutableStateOf(settings.accountNumber) }
    var holder by remember(settings) { mutableStateOf(settings.holder) }
    var rut by remember(settings) { mutableStateOf(settings.rut) }
    var paymentNotes by remember(settings) { mutableStateOf(settings.paymentNotes) }
    var reminderTemplate by remember(settings) { mutableStateOf(settings.reminderTemplate) }
    var overdueTemplate by remember(settings) { mutableStateOf(settings.overdueTemplate) }
    var partialTemplate by remember(settings) { mutableStateOf(settings.partialTemplate) }
    var pin by remember { mutableStateOf("") }
    var securityEnabled by remember { mutableStateOf(loadSecurityEnabled(context)) }
    var saved by remember { mutableStateOf(false) }
    var csvMessage by remember { mutableStateOf<String?>(null) }

    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                val imported = parseClientsCsv(content)
                onImportClients(imported)
                csvMessage = "Se importaron ${imported.size} cliente(s)."
            } catch (_: Exception) {
                csvMessage = "No se pudo leer el archivo CSV."
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Ajustes", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Negocio, mensajes, seguridad y respaldo", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item { OutlinedTextField(name, { name = it; saved = false }, modifier = Modifier.fillMaxWidth(), label = { Text("Nombre del negocio") }) }
        item {
            OutlinedTextField(
                whatsapp, { whatsapp = it; saved = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("WhatsApp del negocio") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
            )
        }

        item { Text("Datos de pago", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        item { OutlinedTextField(bank, { bank = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Banco") }) }
        item { OutlinedTextField(accountType, { accountType = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Tipo de cuenta") }) }
        item { OutlinedTextField(accountNumber, { accountNumber = it }, modifier = Modifier.fillMaxWidth(), label = { Text("N° de cuenta") }) }
        item { OutlinedTextField(holder, { holder = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Titular") }) }
        item { OutlinedTextField(rut, { rut = it }, modifier = Modifier.fillMaxWidth(), label = { Text("RUT") }) }
        item { OutlinedTextField(paymentNotes, { paymentNotes = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Observaciones de pago") }) }

        item { Text("Plantillas de WhatsApp", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        item {
            Text("Puedes usar {cliente}, {negocio}, {saldo}, {vencimiento}, {concepto}.", fontSize = 12.sp)
        }
        item { OutlinedTextField(reminderTemplate, { reminderTemplate = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Mensaje pendiente") }, minLines = 3) }
        item { OutlinedTextField(overdueTemplate, { overdueTemplate = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Mensaje vencido") }, minLines = 3) }
        item { OutlinedTextField(partialTemplate, { partialTemplate = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Mensaje con abono") }, minLines = 3) }

        item {
            Button(
                onClick = {
                    onSave(
                        BusinessSettings(
                            name = name.trim().ifBlank { "Mi negocio" },
                            whatsapp = whatsapp.trim(),
                            bank = bank.trim(),
                            accountType = accountType.trim(),
                            accountNumber = accountNumber.trim(),
                            holder = holder.trim(),
                            rut = rut.trim(),
                            paymentNotes = paymentNotes.trim(),
                            reminderTemplate = reminderTemplate,
                            overdueTemplate = overdueTemplate,
                            partialTemplate = partialTemplate
                        )
                    )
                    saved = true
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Guardar ajustes") }
            if (saved) Text("Guardado correctamente ✓", color = MaterialTheme.colorScheme.primary)
        }

        item { Text("Seguridad", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        item {
            OutlinedButton(
                onClick = {
                    if (securityEnabled) {
                        saveSecurity(context, false, "")
                        securityEnabled = false
                    } else if (pin.length >= 4) {
                        saveSecurity(context, true, pin)
                        securityEnabled = true
                        pin = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (securityEnabled) "Desactivar bloqueo" else "Activar PIN + huella")
            }
        }
        if (!securityEnabled) {
            item {
                OutlinedTextField(
                    pin,
                    { pin = it.filter(Char::isDigit).take(8) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("PIN (mínimo 4 dígitos)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
            }
        }

        item { Text("Respaldo e importación", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        item { OutlinedButton(onClick = onBackup, modifier = Modifier.fillMaxWidth()) { Text("Exportar respaldo") } }
        item { OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Importar respaldo JSON") } }
        item { OutlinedButton(onClick = onRestoreAutoBackup, modifier = Modifier.fillMaxWidth()) { Text("Restaurar último respaldo automático") } }
        item {
            OutlinedButton(
                onClick = { csvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Importar clientes desde CSV") }
        }

        csvMessage?.let { msg ->
            item { Text(msg, color = MaterialTheme.colorScheme.primary) }
        }


        item {
            Text("Acerca de", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("CobrosPyme $APP_VERSION_LABEL", fontWeight = FontWeight.Bold)
                    Text("Desarrollado por NegociosPyme")
                    Text(
                        "by Juan Alarcon",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://negociospyme.cl")
                                )
                            )
                        }
                    ) {
                        Text("Visitar NegociosPyme.cl")
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Automatización", fontWeight = FontWeight.Bold)
                    Text("La app revisa vencimientos en segundo plano y mantiene un respaldo local automático diario.")
                    Text("La nube y el plan mensual quedan para la etapa con servidor/backend.")
                }
            }
        }
    }
}


@Composable
private fun ImportBackupDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var raw by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Importar respaldo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pega aquí el texto JSON de un respaldo exportado desde CobrosPyme.")
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Respaldo JSON") },
                    minLines = 6
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onImport(raw) },
                enabled = raw.isNotBlank()
            ) { Text("Importar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

private fun generateRecurringDebts(existing: List<Debt>): List<Debt> {
    if (existing.isEmpty()) return emptyList()

    val all = existing.toMutableList()
    val generated = mutableListOf<Debt>()
    var nextId = (all.maxOfOrNull { it.id } ?: System.currentTimeMillis()).coerceAtLeast(System.currentTimeMillis()) + 1L
    var guard = 0

    while (guard < 240) {
        guard++
        val source = all
            .filter { it.recurrence != "Ninguno" && it.dueDate.isNotBlank() }
            .sortedBy { parseDateOrMax(it.dueDate) }
            .firstOrNull { candidate ->
                val due = daysUntilDue(candidate.dueDate)
                due != null && due <= 0L && all.none { it.recurrenceParentId == candidate.id }
            } ?: break

        val nextDate = nextRecurrenceDate(source.dueDate, source.recurrence) ?: break
        val nextDebt = source.copy(
            id = nextId++,
            paidAmount = 0L,
            dueDate = nextDate,
            paid = false,
            recurrenceParentId = source.id
        )
        all.add(nextDebt)
        generated.add(nextDebt)
    }

    return generated
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
                val maxDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
                calendar.set(Calendar.DAY_OF_MONTH, desiredDay.coerceAtMost(maxDay))
            }
            else -> return null
        }
        formatter.format(calendar.time)
    } catch (_: Exception) {
        null
    }
}

private fun remainingBalance(debt: Debt): Long {
    if (debt.paid) return 0L
    return (debt.amount - debt.paidAmount).coerceAtLeast(0L)
}

private fun debtStatus(debt: Debt): String {
    return when {
        debt.paid || remainingBalance(debt) == 0L -> "Pagado"
        isOverdue(debt.dueDate) -> "Vencido"
        else -> "Pendiente"
    }
}

private fun formatCurrency(value: Long): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale("es", "CL"))
    formatter.maximumFractionDigits = 0
    formatter.minimumFractionDigits = 0
    return formatter.format(value)
}

private fun isValidDate(dateText: String): Boolean {
    if (dateText.isBlank()) return true
    return try {
        val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        formatter.isLenient = false
        formatter.parse(dateText)
        true
    } catch (_: Exception) {
        false
    }
}

private fun isOverdue(dateText: String): Boolean {
    if (dateText.isBlank()) return false
    return try {
        val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        formatter.isLenient = false
        val due = formatter.parse(dateText) ?: return false
        val today = formatter.parse(formatter.format(Date())) ?: return false
        due.before(today)
    } catch (_: Exception) {
        false
    }
}

private fun daysUntilDue(dateText: String): Long? {
    if (dateText.isBlank()) return null
    return try {
        val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        formatter.isLenient = false
        val due = formatter.parse(dateText) ?: return null
        val today = formatter.parse(formatter.format(Date())) ?: return null
        ((due.time - today.time) / (24L * 60L * 60L * 1000L))
    } catch (_: Exception) {
        null
    }
}

private fun statusPriority(debt: Debt): Int {
    return when (debtStatus(debt)) {
        "Vencido" -> 0
        "Pendiente" -> 1
        else -> 2
    }
}

private fun parseDateOrMax(dateText: String): Long {
    if (dateText.isBlank()) return Long.MAX_VALUE
    return try {
        val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        formatter.isLenient = false
        formatter.parse(dateText)?.time ?: Long.MAX_VALUE
    } catch (_: Exception) {
        Long.MAX_VALUE
    }
}

private fun isCurrentMonth(dateText: String): Boolean {
    return try {
        val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        formatter.isLenient = false
        val date = formatter.parse(dateText) ?: return false
        val item = Calendar.getInstance().apply { time = date }
        val now = Calendar.getInstance()
        item.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            item.get(Calendar.MONTH) == now.get(Calendar.MONTH)
    } catch (_: Exception) {
        false
    }
}

private fun isCurrentWeek(dateText: String, now: Calendar = Calendar.getInstance()): Boolean {
    return try {
        val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).apply { isLenient = false }
        val date = formatter.parse(dateText) ?: return false
        val c = Calendar.getInstance().apply { time = date }
        c.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            c.get(Calendar.WEEK_OF_YEAR) == now.get(Calendar.WEEK_OF_YEAR)
    } catch (_: Exception) {
        false
    }
}

private fun clientTagColor(tag: String): Color = when (tag) {
    "Rojo" -> Color(0xFFFFCDD2)
    "Naranjo" -> Color(0xFFFFE0B2)
    "Azul" -> Color(0xFFBBDEFB)
    "Gris" -> Color(0xFFE0E0E0)
    else -> Color(0xFFC8E6C9)
}

private fun showDatePicker(
    context: Context,
    current: String,
    onSelected: (String) -> Unit
) {
    val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).apply { isLenient = false }
    val calendar = Calendar.getInstance()
    try {
        formatter.parse(current)?.let { calendar.time = it }
    } catch (_: Exception) {}

    DatePickerDialog(
        context,
        { _, year, month, day ->
            onSelected(String.format(Locale.getDefault(), "%02d/%02d/%04d", day, month + 1, year))
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    ).show()
}

private fun saveSecurity(context: Context, enabled: Boolean, pin: String) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs.edit()
        .putBoolean(KEY_SECURITY_ENABLED, enabled)
        .putString(KEY_SECURITY_PIN, if (enabled) hashPin(pin) else "")
        .apply()
}

private fun loadSecurityEnabled(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_SECURITY_ENABLED, false)

private fun verifySecurityPin(context: Context, pin: String): Boolean {
    val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_SECURITY_PIN, "") ?: ""
    return saved.isNotBlank() && saved == hashPin(pin)
}

private fun hashPin(pin: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

private fun scheduleCobrosWorkers(context: Context) {
    val notifications = PeriodicWorkRequestBuilder<CobrosNotificationWorker>(24, TimeUnit.HOURS).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "cobrospyme_notifications",
        ExistingPeriodicWorkPolicy.UPDATE,
        notifications
    )

    val backup = PeriodicWorkRequestBuilder<CobrosBackupWorker>(24, TimeUnit.HOURS).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "cobrospyme_backup",
        ExistingPeriodicWorkPolicy.UPDATE,
        backup
    )
}

private fun restoreAutomaticBackup(context: Context): BackupData? {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_AUTO_BACKUP, "") ?: ""
    return if (raw.isBlank()) null else parseBackup(raw)
}

private fun parseClientsCsv(raw: String): List<Client> {
    if (raw.isBlank()) return emptyList()
    val lines = raw.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return emptyList()
    val separator = if (lines.first().count { it == ';' } > lines.first().count { it == ',' }) ';' else ','
    val header = lines.first().split(separator).map { it.trim().lowercase() }
    val hasHeader = header.any { it in listOf("nombre", "name", "telefono", "teléfono", "phone", "rut") }
    val data = if (hasHeader) lines.drop(1) else lines

    fun value(cols: List<String>, names: List<String>, fallback: Int): String {
        val idx = names.map { header.indexOf(it) }.firstOrNull { it >= 0 } ?: fallback
        return cols.getOrNull(idx)?.trim()?.trim('"') ?: ""
    }

    val base = System.currentTimeMillis()
    return data.mapIndexedNotNull { index, line ->
        val cols = line.split(separator)
        val name = value(cols, listOf("nombre", "name"), 0)
        if (name.isBlank()) return@mapIndexedNotNull null
        Client(
            id = base + index,
            name = name,
            phone = value(cols, listOf("telefono", "teléfono", "phone", "whatsapp"), 1),
            email = value(cols, listOf("correo", "email"), 2),
            address = value(cols, listOf("direccion", "dirección", "address"), 3),
            rut = value(cols, listOf("rut"), 4)
        )
    }
}


private fun clientAccountStatus(debts: List<Debt>): String = when {
    debts.any { debtStatus(it) == "Vencido" } -> "Vencido"
    debts.any { debtStatus(it) == "Pendiente" } -> "Pendiente"
    else -> "Al día"
}

private fun daysBetween(fromText: String, toText: String): Long? {
    return try {
        val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).apply { isLenient = false }
        val from = formatter.parse(fromText) ?: return null
        val to = formatter.parse(toText) ?: return null
        (to.time - from.time) / 86_400_000L
    } catch (_: Exception) {
        null
    }
}

private fun sameMonthYear(baseText: String, otherText: String): Boolean {
    return try {
        val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).apply { isLenient = false }
        val base = Calendar.getInstance().apply { time = formatter.parse(baseText) ?: return false }
        val other = Calendar.getInstance().apply { time = formatter.parse(otherText) ?: return false }
        base.get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
            base.get(Calendar.MONTH) == other.get(Calendar.MONTH)
    } catch (_: Exception) {
        false
    }
}

private fun currentDate(): String {
    return SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
}

private fun normalizePhone(phone: String): String {
    val digits = phone.filter(Char::isDigit)
    return when {
        digits.startsWith("56") -> digits
        digits.length == 9 && digits.startsWith("9") -> "56$digits"
        else -> digits
    }
}

private fun buildReminderMessage(
    client: Client,
    debt: Debt,
    business: BusinessSettings
): String {
    val template = when {
        debtStatus(debt) == "Vencido" -> business.overdueTemplate
        debt.paidAmount > 0 -> business.partialTemplate
        else -> business.reminderTemplate
    }

    val base = template
        .replace("{cliente}", client.name)
        .replace("{negocio}", business.name)
        .replace("{saldo}", formatCurrency(remainingBalance(debt)))
        .replace("{vencimiento}", debt.dueDate.ifBlank { "Sin fecha" })
        .replace("{concepto}", debt.concept.ifBlank { debt.category })

    val paymentData = buildPaymentData(business)
    return if (paymentData.isBlank()) base else "$base\n\nDatos de pago:\n$paymentData"
}

private fun sendWhatsAppReminder(
    context: Context,
    client: Client,
    debt: Debt,
    business: BusinessSettings
) {
    openWhatsApp(context, normalizePhone(client.phone), buildReminderMessage(client, debt, business))
}

private fun sendWhatsAppClient(
    context: Context,
    client: Client,
    business: BusinessSettings
) {
    openWhatsApp(
        context,
        normalizePhone(client.phone),
        "Hola ${client.name} 👋\nTe escribimos de ${business.name}."
    )
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("CobrosPyme", text))
}

private fun sharePaymentReceiptPdf(
    context: Context,
    business: BusinessSettings,
    client: Client?,
    debt: Debt,
    paymentAmount: Long
) {
    try {
        val receiptNumber = "CP-${SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(Date())}"
        val pdf = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdf.startPage(pageInfo)
        val canvas = page.canvas
        val paint = Paint().apply { textSize = 17f; isAntiAlias = true }
        val small = Paint(paint).apply { textSize = 14f }
        val bold = Paint(paint).apply { isFakeBoldText = true; textSize = 24f }

        val brandPurple = android.graphics.Color.rgb(111, 82, 217)
        val whitePaint = Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 24f
            isFakeBoldText = true
            isAntiAlias = true
        }
        val brandPaint = Paint().apply {
            color = brandPurple
            isAntiAlias = true
        }

        canvas.drawRoundRect(50f, 42f, 112f, 104f, 14f, 14f, brandPaint)
        canvas.drawText("NP", 62f, 82f, whitePaint)

        var y = 64f
        canvas.drawText(business.name, 132f, y, bold); y += 24f
        canvas.drawText("CobrosPyme · NegociosPyme", 132f, y, small); y += 34f

        if (business.rut.isNotBlank()) {
            canvas.drawText("RUT: ${business.rut}", 50f, y, small); y += 22f
        }
        if (business.whatsapp.isNotBlank()) {
            canvas.drawText("WhatsApp: ${business.whatsapp}", 50f, y, small); y += 22f
        }

        y += 8f
        canvas.drawText("COMPROBANTE DE ABONO", 50f, y, bold); y += 32f
        canvas.drawText("N° $receiptNumber", 50f, y, small); y += 34f

        val lines = mutableListOf(
            "Fecha: ${currentDate()}",
            "Cliente: ${client?.name ?: "Cliente"}",
            "Concepto: ${debt.concept.ifBlank { debt.category }}",
            "Categoría: ${debt.category}",
            "Abono recibido: ${formatCurrency(paymentAmount)}",
            "Total original: ${formatCurrency(debt.amount)}",
            "Saldo pendiente: ${formatCurrency(remainingBalance(debt))}",
            "Estado: ${debtStatus(debt)}"
        )
        lines.forEach {
            canvas.drawText(it, 50f, y, paint)
            y += 30f
        }

        val paymentData = buildPaymentData(business)
        if (paymentData.isNotBlank()) {
            y += 12f
            canvas.drawText("DATOS DE PAGO", 50f, y, Paint(paint).apply { isFakeBoldText = true })
            y += 28f
            paymentData.lines().filter { it.isNotBlank() }.forEach {
                canvas.drawText(it, 50f, y, small)
                y += 24f
            }
        }

        y += 28f
        canvas.drawText("Hecho por NegociosPyme · by Juan Alarcon", 50f, y, small)
        y += 22f
        canvas.drawText("Generado por CobrosPyme · $receiptNumber", 50f, y, small)
        pdf.finishPage(page)

        val dir = File(context.cacheDir, "comprobantes").apply { mkdirs() }
        val file = File(dir, "comprobante_$receiptNumber.pdf")
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Comprobante $receiptNumber")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartir comprobante PDF"))
    } catch (_: Exception) {
        shareText(
            context,
            "Comprobante de abono",
            "${business.name}\nCliente: ${client?.name ?: "Cliente"}\nAbono: ${formatCurrency(paymentAmount)}"
        )
    }
}


private fun openWhatsApp(context: Context, phone: String, message: String) {
    val url = if (phone.isBlank()) {
        "https://wa.me/?text=${Uri.encode(message)}"
    } else {
        "https://wa.me/$phone?text=${Uri.encode(message)}"
    }
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private fun buildPaymentData(business: BusinessSettings): String {
    return buildString {
        if (business.bank.isNotBlank()) append("Banco: ${business.bank}\n")
        if (business.accountType.isNotBlank()) append("Cuenta: ${business.accountType}\n")
        if (business.accountNumber.isNotBlank()) append("N°: ${business.accountNumber}\n")
        if (business.holder.isNotBlank()) append("Titular: ${business.holder}\n")
        if (business.rut.isNotBlank()) append("RUT: ${business.rut}\n")
        if (business.paymentNotes.isNotBlank()) append("${business.paymentNotes}\n")
    }.trim()
}

private fun shareClientSummary(
    context: Context,
    client: Client,
    debts: List<Debt>,
    business: BusinessSettings
) {
    val pending = debts.sumOf { remainingBalance(it) }
    val collected = debts.sumOf { it.paidAmount.coerceAtMost(it.amount) }
    val overdue = debts.count { debtStatus(it) == "Vencido" }

    val text = buildString {
        append("${business.name}\n")
        append("Resumen de cuenta - ${client.name}\n\n")
        append("Pendiente: ${formatCurrency(pending)}\n")
        append("Pagado: ${formatCurrency(collected)}\n")
        append("Cobros vencidos: $overdue\n")
        append("Total de cobros: ${debts.size}\n")
    }

    shareText(context, "Resumen de ${client.name}", text)
}

private fun sharePaymentReceipt(
    context: Context,
    business: BusinessSettings,
    client: Client?,
    debt: Debt,
    paymentAmount: Long
) {
    val text = buildString {
        append("${business.name}\n")
        append("COMPROBANTE DE ABONO\\n")
        append("--------------------------------\\n")
        append("Fecha: ${currentDate()}\\n")
        append("Cliente: ${client?.name ?: "Cliente"}\\n")
        if (debt.concept.isNotBlank()) {
            append("Concepto: ${debt.concept}\\n")
        }
        append("Abono recibido: ${formatCurrency(paymentAmount)}\\n")
        append("Total original: ${formatCurrency(debt.amount)}\\n")
        append("Saldo pendiente: ${formatCurrency(remainingBalance(debt))}\\n")
        append("Estado: ${debtStatus(debt)}\\n")
        append("--------------------------------\\n")
        if (business.whatsapp.isNotBlank()) {
            append("Contacto: ${business.whatsapp}\\n")
        }
        append("Comprobante generado por CobrosPyme")
    }

    shareText(context, "Comprobante de abono", text)
}

private fun shareText(context: Context, title: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, title))
}

private fun shareBackup(
    context: Context,
    clients: List<Client>,
    debts: List<Debt>,
    payments: List<PaymentRecord>,
    business: BusinessSettings
) {
    val root = JSONObject()
    root.put("app", "CobrosPyme")
    root.put("version", APP_VERSION_LABEL)
    root.put("fecha", currentDate())

    val businessObj = JSONObject()
        .put("name", business.name)
        .put("whatsapp", business.whatsapp)
        .put("bank", business.bank)
        .put("accountType", business.accountType)
        .put("accountNumber", business.accountNumber)
        .put("holder", business.holder)
        .put("rut", business.rut)
        .put("paymentNotes", business.paymentNotes)
        .put("reminderTemplate", business.reminderTemplate)
        .put("overdueTemplate", business.overdueTemplate)
        .put("partialTemplate", business.partialTemplate)
    root.put("business", businessObj)

    val clientsArray = JSONArray()
    clients.forEach {
        clientsArray.put(
            JSONObject()
                .put("id", it.id)
                .put("name", it.name)
                .put("phone", it.phone)
                .put("email", it.email)
                .put("address", it.address)
                .put("notes", it.notes)
                .put("rut", it.rut)
                .put("label", it.label)
                .put("colorTag", it.colorTag)
        )
    }
    root.put("clients", clientsArray)

    val debtsArray = JSONArray()
    debts.forEach {
        debtsArray.put(
            JSONObject()
                .put("id", it.id)
                .put("clientId", it.clientId)
                .put("concept", it.concept)
                .put("amount", it.amount)
                .put("paidAmount", it.paidAmount)
                .put("dueDate", it.dueDate)
                .put("paid", it.paid)
                .put("category", it.category)
                .put("reminderEnabled", it.reminderEnabled)
                .put("reminderDaysBefore", it.reminderDaysBefore)
                .put("recurrence", it.recurrence)
                .put("recurrenceParentId", it.recurrenceParentId)
        )
    }
    root.put("debts", debtsArray)

    val paymentsArray = JSONArray()
    payments.forEach {
        paymentsArray.put(
            JSONObject()
                .put("id", it.id)
                .put("debtId", it.debtId)
                .put("amount", it.amount)
                .put("date", it.date)
        )
    }
    root.put("payments", paymentsArray)

    shareText(context, "Respaldo CobrosPyme", root.toString(2))
}

private fun parseBackup(raw: String): BackupData? {
    return try {
        val root = JSONObject(raw)

        val b = root.optJSONObject("business") ?: JSONObject()
        val business = BusinessSettings(
            name = b.optString("name", "Mi negocio"),
            whatsapp = b.optString("whatsapp"),
            bank = b.optString("bank"),
            accountType = b.optString("accountType"),
            accountNumber = b.optString("accountNumber"),
            holder = b.optString("holder"),
            rut = b.optString("rut"),
            paymentNotes = b.optString("paymentNotes"),
            reminderTemplate = b.optString("reminderTemplate", BusinessSettings().reminderTemplate),
            overdueTemplate = b.optString("overdueTemplate", BusinessSettings().overdueTemplate),
            partialTemplate = b.optString("partialTemplate", BusinessSettings().partialTemplate)
        )

        val clients = mutableListOf<Client>()
        val ca = root.optJSONArray("clients") ?: JSONArray()
        for (i in 0 until ca.length()) {
            val o = ca.getJSONObject(i)
            clients.add(
                Client(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    phone = o.optString("phone"),
                    email = o.optString("email"),
                    address = o.optString("address"),
                    notes = o.optString("notes"),
                    rut = o.optString("rut"),
                    label = o.optString("label"),
                    colorTag = o.optString("colorTag", "Verde")
                )
            )
        }

        val debts = mutableListOf<Debt>()
        val da = root.optJSONArray("debts") ?: JSONArray()
        for (i in 0 until da.length()) {
            val o = da.getJSONObject(i)
            debts.add(
                Debt(
                    id = o.getLong("id"),
                    clientId = o.getLong("clientId"),
                    concept = o.optString("concept"),
                    amount = o.getLong("amount"),
                    paidAmount = o.optLong("paidAmount"),
                    dueDate = o.optString("dueDate"),
                    paid = o.optBoolean("paid"),
                    category = o.optString("category", "Otro"),
                    reminderEnabled = o.optBoolean("reminderEnabled", true),
                    reminderDaysBefore = o.optInt("reminderDaysBefore", 1),
                    recurrence = o.optString("recurrence", "Ninguno"),
                    recurrenceParentId = o.optLong("recurrenceParentId", 0L)
                )
            )
        }

        val payments = mutableListOf<PaymentRecord>()
        val pa = root.optJSONArray("payments") ?: JSONArray()
        for (i in 0 until pa.length()) {
            val o = pa.getJSONObject(i)
            payments.add(
                PaymentRecord(
                    id = o.getLong("id"),
                    debtId = o.getLong("debtId"),
                    amount = o.getLong("amount"),
                    date = o.optString("date")
                )
            )
        }

        BackupData(clients, debts, payments, business)
    } catch (_: Exception) {
        null
    }
}


private fun loadBusinessSettings(context: Context): BusinessSettings {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_BUSINESS, "") ?: ""
    if (raw.isBlank()) return BusinessSettings()
    return try {
        val o = JSONObject(raw)
        BusinessSettings(
            name = o.optString("name", "Mi negocio"),
            whatsapp = o.optString("whatsapp"),
            bank = o.optString("bank"),
            accountType = o.optString("accountType"),
            accountNumber = o.optString("accountNumber"),
            holder = o.optString("holder"),
            rut = o.optString("rut"),
            paymentNotes = o.optString("paymentNotes"),
            reminderTemplate = o.optString("reminderTemplate", BusinessSettings().reminderTemplate),
            overdueTemplate = o.optString("overdueTemplate", BusinessSettings().overdueTemplate),
            partialTemplate = o.optString("partialTemplate", BusinessSettings().partialTemplate)
        )
    } catch (_: Exception) {
        BusinessSettings()
    }
}

private fun saveBusinessSettings(context: Context, settings: BusinessSettings) {
    val o = JSONObject()
        .put("name", settings.name)
        .put("whatsapp", settings.whatsapp)
        .put("bank", settings.bank)
        .put("accountType", settings.accountType)
        .put("accountNumber", settings.accountNumber)
        .put("holder", settings.holder)
        .put("rut", settings.rut)
        .put("paymentNotes", settings.paymentNotes)
        .put("reminderTemplate", settings.reminderTemplate)
        .put("overdueTemplate", settings.overdueTemplate)
        .put("partialTemplate", settings.partialTemplate)

    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_BUSINESS, o.toString())
        .apply()
}

private fun loadClients(context: Context): List<Client> {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_CLIENTS, "[]") ?: "[]"
    val result = mutableListOf<Client>()

    return try {
        val array = JSONArray(raw)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            result.add(
                Client(
                    id = obj.getLong("id"),
                    name = obj.getString("name"),
                    phone = obj.optString("phone"),
                    email = obj.optString("email"),
                    address = obj.optString("address"),
                    notes = obj.optString("notes"),
                    rut = obj.optString("rut"),
                    label = obj.optString("label"),
                    colorTag = obj.optString("colorTag", "Verde")
                )
            )
        }
        result
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveClients(context: Context, clients: List<Client>) {
    val array = JSONArray()
    clients.forEach { client ->
        array.put(
            JSONObject()
                .put("id", client.id)
                .put("name", client.name)
                .put("phone", client.phone)
                .put("email", client.email)
                .put("address", client.address)
                .put("notes", client.notes)
                .put("rut", client.rut)
                .put("label", client.label)
                .put("colorTag", client.colorTag)
        )
    }

    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_CLIENTS, array.toString())
        .apply()
}

private fun loadDebts(context: Context): List<Debt> {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_DEBTS, "[]") ?: "[]"
    val result = mutableListOf<Debt>()

    return try {
        val array = JSONArray(raw)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val amount = obj.getLong("amount")
            val oldPaid = obj.optBoolean("paid", false)
            val paidAmount = if (obj.has("paidAmount")) obj.optLong("paidAmount", 0L) else if (oldPaid) amount else 0L

            result.add(
                Debt(
                    id = obj.getLong("id"),
                    clientId = obj.getLong("clientId"),
                    concept = obj.optString("concept"),
                    amount = amount,
                    paidAmount = paidAmount.coerceAtMost(amount),
                    dueDate = obj.optString("dueDate"),
                    paid = oldPaid || paidAmount >= amount,
                    category = obj.optString("category", "Otro"),
                    reminderEnabled = obj.optBoolean("reminderEnabled", true),
                    reminderDaysBefore = obj.optInt("reminderDaysBefore", 1),
                    recurrence = obj.optString("recurrence", "Ninguno"),
                    recurrenceParentId = obj.optLong("recurrenceParentId", 0L)
                )
            )
        }
        result
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveDebts(context: Context, debts: List<Debt>) {
    val array = JSONArray()
    debts.forEach { debt ->
        array.put(
            JSONObject()
                .put("id", debt.id)
                .put("clientId", debt.clientId)
                .put("concept", debt.concept)
                .put("amount", debt.amount)
                .put("paidAmount", debt.paidAmount)
                .put("dueDate", debt.dueDate)
                .put("paid", debt.paid)
                .put("category", debt.category)
                .put("reminderEnabled", debt.reminderEnabled)
                .put("reminderDaysBefore", debt.reminderDaysBefore)
                .put("recurrence", debt.recurrence)
                .put("recurrenceParentId", debt.recurrenceParentId)
        )
    }

    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_DEBTS, array.toString())
        .apply()
}

private fun loadPayments(context: Context): List<PaymentRecord> {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_PAYMENTS, "[]") ?: "[]"
    val result = mutableListOf<PaymentRecord>()

    return try {
        val array = JSONArray(raw)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            result.add(
                PaymentRecord(
                    id = obj.getLong("id"),
                    debtId = obj.getLong("debtId"),
                    amount = obj.getLong("amount"),
                    date = obj.optString("date")
                )
            )
        }
        result
    } catch (_: Exception) {
        emptyList()
    }
}

private fun savePayments(context: Context, payments: List<PaymentRecord>) {
    val array = JSONArray()
    payments.forEach { payment ->
        array.put(
            JSONObject()
                .put("id", payment.id)
                .put("debtId", payment.debtId)
                .put("amount", payment.amount)
                .put("date", payment.date)
        )
    }

    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_PAYMENTS, array.toString())
        .apply()
}
