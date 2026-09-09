package cl.negociospyme.cobros

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.layout.weight
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

private const val PREFS = "cobrospyme_data"
private const val KEY_CLIENTS = "clients"
private const val KEY_DEBTS = "debts"
private const val KEY_PAYMENTS = "payments"
private const val KEY_BUSINESS = "business"
private const val APP_VERSION_LABEL = "v1.2"

data class Client(
    val id: Long,
    val name: String,
    val phone: String,
    val email: String = "",
    val address: String = "",
    val notes: String = ""
)

data class Debt(
    val id: Long,
    val clientId: Long,
    val concept: String,
    val amount: Long,
    val paidAmount: Long,
    val dueDate: String,
    val paid: Boolean
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
    val paymentNotes: String = ""
)

data class BackupData(
    val clients: List<Client>,
    val debts: List<Debt>,
    val payments: List<PaymentRecord>,
    val business: BusinessSettings
)

enum class Screen { HOME, CLIENTS, DEBTS, SETTINGS }

enum class DebtFilter(val label: String) {
    ALL("Todos"),
    PENDING("Pendientes"),
    OVERDUE("Vencidos"),
    PAID("Pagados")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                CobrosPymeApp()
            }
        }
    }
}

@Composable
fun CobrosPymeApp() {
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
    var lastPayment by remember { mutableStateOf<Pair<Debt, Long>?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }

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

            Screen.SETTINGS -> BusinessSettingsScreen(
                modifier = Modifier.padding(innerPadding),
                settings = business,
                onSave = {
                    business = it
                    saveBusinessSettings(context, it)
                },
                onBackup = { shareBackup(context, clients, debts, payments, business) },
                onImport = { showImportDialog = true }
            )
        }
    }

    if (showClientDialog) {
        ClientDialog(
            title = "Nuevo cliente",
            initial = null,
            onDismiss = { showClientDialog = false },
            onSave = { name, phone, email, address, notes ->
                clients.add(
                    Client(
                        id = System.currentTimeMillis(),
                        name = name.trim(),
                        phone = phone.trim(),
                        email = email.trim(),
                        address = address.trim(),
                        notes = notes.trim()
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
            onSave = { name, phone, email, address, notes ->
                val index = clients.indexOfFirst { it.id == client.id }
                if (index >= 0) {
                    clients[index] = client.copy(
                        name = name.trim(),
                        phone = phone.trim(),
                        email = email.trim(),
                        address = address.trim(),
                        notes = notes.trim()
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
            onSave = { clientId, concept, amount, dueDate ->
                debts.add(
                    Debt(
                        id = System.currentTimeMillis(),
                        clientId = clientId,
                        concept = concept.trim(),
                        amount = amount,
                        paidAmount = 0L,
                        dueDate = dueDate.trim(),
                        paid = false
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
                        sharePaymentReceipt(context, business, client, debt, amount)
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
                .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MenuButton("Inicio", current == Screen.HOME) { onChange(Screen.HOME) }
            MenuButton("Clientes", current == Screen.CLIENTS) { onChange(Screen.CLIENTS) }
            MenuButton("Cobros", current == Screen.DEBTS) { onChange(Screen.DEBTS) }
            MenuButton("Ajustes", current == Screen.SETTINGS) { onChange(Screen.SETTINGS) }
        }
    }
}

@Composable
private fun MenuButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    if (selected) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(text)
        }
    } else {
        TextButton(onClick = onClick) { Text(text) }
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
    val collectedThisMonth = payments
        .filter { isCurrentMonth(it.date) }
        .sumOf { it.amount }
    val overdue = debts.count { debtStatus(it) == "Vencido" }
    val dueToday = debts.count {
        debtStatus(it) != "Pagado" && daysUntilDue(it.dueDate) == 0L
    }
    val dueWeek = debts.count {
        val days = daysUntilDue(it.dueDate)
        debtStatus(it) != "Pagado" && days != null && days in 0L..7L
    }

    val priorityDebts = debts.sortedWith(
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text("CobrosPyme", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Controla clientes, deudas y abonos",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    APP_VERSION_LABEL,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Total por cobrar")
                    Text(
                        formatCurrency(totalPending),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Cobrado este mes: ${formatCurrency(collectedThisMonth)}")
                    Text("Cobrado acumulado: ${formatCurrency(totalCollected)}")
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MiniInfoCard(
                    modifier = Modifier.weight(1f),
                    title = "Vence hoy",
                    value = dueToday.toString(),
                    color = Color(0xFFFFE5E5)
                )
                MiniInfoCard(
                    modifier = Modifier.weight(1f),
                    title = "Esta semana",
                    value = dueWeek.toString(),
                    color = Color(0xFFFFF0D9)
                )
            }
        }

        item { SummaryCard("Cobros vencidos", overdue.toString(), StatusType.OVERDUE) }
        item { SummaryCard("Clientes registrados", clients.size.toString(), StatusType.NEUTRAL) }

        item {
            Button(
                onClick = onCollectNow,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Cobrar ahora")
            }
        }

        item {
            OutlinedButton(
                onClick = onNewDebt,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("+ Nueva deuda")
            }
        }

        item {
            OutlinedButton(
                onClick = onNewClient,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("+ Nuevo cliente")
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Cobros prioritarios", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = onOpenDebts) { Text("Ver todos") }
            }
        }

        if (priorityDebts.isEmpty()) {
            item {
                EmptyCard(
                    title = "Aún no tienes cobros",
                    subtitle = "Agrega un cliente y registra su primera deuda."
                )
            }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onOpenClients) { Text("Clientes") }
                TextButton(onClick = onBackup) { Text("Respaldar") }
                TextButton(onClick = onOpenSettings) { Text("Ajustes") }
            }
        }
    }
}

@Composable
private fun MiniInfoCard(
    modifier: Modifier,
    title: String,
    value: String,
    color: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontSize = 12.sp)
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold)
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
            it.name.contains(query, ignoreCase = true) ||
            it.phone.contains(query, ignoreCase = true)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
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
                placeholder = { Text("Nombre o teléfono") },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onNewClient,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("+ Nuevo cliente")
            }
        }

        if (filtered.isEmpty()) {
            item {
                EmptyCard(
                    if (query.isBlank()) "Sin clientes" else "Sin resultados",
                    if (query.isBlank()) "Registra tu primer cliente para comenzar." else "No encontramos clientes con esa búsqueda."
                )
            }
        } else {
            items(filtered.sortedBy { it.name.lowercase() }, key = { it.id }) { client ->
                val clientDebts = debts.filter { it.clientId == client.id }
                val pending = clientDebts.sumOf { remainingBalance(it) }
                val collected = clientDebts.sumOf { it.paidAmount.coerceAtMost(it.amount) }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenClient(client) },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(client.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("Ver ficha", color = MaterialTheme.colorScheme.primary)
                        }
                        Text(client.phone.ifBlank { "Sin teléfono" })
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Pendiente: ${formatCurrency(pending)}", fontWeight = FontWeight.Bold)
                        Text("Pagado: ${formatCurrency(collected)}")
                        Text("Cobros: ${clientDebts.size}")
                    }
                }
            }
        }
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
                placeholder = { Text("Cliente, concepto o monto") },
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
            Text(debt.concept.ifBlank { "Cobro" })
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
                    Text("Total: ${formatCurrency(debt.amount)}")
                    Text("Abonado: ${formatCurrency(debt.paidAmount)}")
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Progreso: ${(progress * 100).toInt()}%")
                    Text("Saldo: ${formatCurrency(remainingBalance(debt))}", fontWeight = FontWeight.Bold)
                    Text("Vence: ${debt.dueDate.ifBlank { "Sin fecha" }}")
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
            if (debtStatus(debt) != "Pagado") {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = onPayment) { Text("Registrar abono") }
                    TextButton(onClick = onWhatsApp) { Text("WhatsApp") }
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
    onSave: (String, String, String, String, String) -> Unit
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var phone by remember(initial?.id) { mutableStateOf(initial?.phone ?: "") }
    var email by remember(initial?.id) { mutableStateOf(initial?.email ?: "") }
    var address by remember(initial?.id) { mutableStateOf(initial?.address ?: "") }
    var notes by remember(initial?.id) { mutableStateOf(initial?.notes ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nombre") },
                        singleLine = true
                    )
                }
                item {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("WhatsApp / teléfono") },
                        placeholder = { Text("Ej: 56912345678") },
                        singleLine = true
                    )
                }
                item {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Correo (opcional)") },
                        singleLine = true
                    )
                }
                item {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Dirección (opcional)") },
                        singleLine = true
                    )
                }
                item {
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Observaciones (opcional)") }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, phone, email, address, notes) },
                enabled = name.isNotBlank()
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun NewDebtDialog(
    clients: List<Client>,
    onDismiss: () -> Unit,
    onSave: (Long, String, Long, String) -> Unit
) {
    var selectedClientId by remember { mutableStateOf(clients.firstOrNull()?.id ?: 0L) }
    var concept by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var dueDate by remember { mutableStateOf("") }
    var clientMenu by remember { mutableStateOf(false) }

    val selectedClient = clients.firstOrNull { it.id == selectedClientId }
    val amount = amountText.filter { it.isDigit() }.toLongOrNull() ?: 0L
    val validDate = dueDate.isBlank() || isValidDate(dueDate)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nueva deuda") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Box {
                        OutlinedButton(onClick = { clientMenu = true }) {
                            Text("Cliente: ${selectedClient?.name ?: "Seleccionar"}")
                        }
                        DropdownMenu(
                            expanded = clientMenu,
                            onDismissRequest = { clientMenu = false }
                        ) {
                            clients.forEach { client ->
                                DropdownMenuItem(
                                    text = { Text(client.name) },
                                    onClick = {
                                        selectedClientId = client.id
                                        clientMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = concept,
                        onValueChange = { concept = it },
                        label = { Text("Concepto") },
                        placeholder = { Text("Ej: Cuota septiembre") },
                        singleLine = true
                    )
                }

                item {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it.filter(Char::isDigit) },
                        label = { Text("Monto") },
                        placeholder = { Text("Ej: 15000") },
                        singleLine = true
                    )
                }

                item {
                    OutlinedTextField(
                        value = dueDate,
                        onValueChange = { dueDate = it },
                        label = { Text("Vencimiento") },
                        placeholder = { Text("dd/mm/aaaa") },
                        singleLine = true,
                        isError = !validDate,
                        supportingText = {
                            if (!validDate) Text("Usa el formato dd/mm/aaaa")
                        }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(selectedClientId, concept, amount, dueDate) },
                enabled = selectedClientId != 0L && amount > 0 && validDate
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
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

@Composable
private fun BusinessSettingsScreen(
    modifier: Modifier,
    settings: BusinessSettings,
    onSave: (BusinessSettings) -> Unit,
    onBackup: () -> Unit,
    onImport: () -> Unit
) {
    var name by remember(settings) { mutableStateOf(settings.name) }
    var whatsapp by remember(settings) { mutableStateOf(settings.whatsapp) }
    var bank by remember(settings) { mutableStateOf(settings.bank) }
    var accountType by remember(settings) { mutableStateOf(settings.accountType) }
    var accountNumber by remember(settings) { mutableStateOf(settings.accountNumber) }
    var holder by remember(settings) { mutableStateOf(settings.holder) }
    var rut by remember(settings) { mutableStateOf(settings.rut) }
    var paymentNotes by remember(settings) { mutableStateOf(settings.paymentNotes) }
    var saved by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Ajustes", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                "Estos datos pueden incluirse en recordatorios y comprobantes.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; saved = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Nombre del negocio") },
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = whatsapp,
                onValueChange = { whatsapp = it; saved = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("WhatsApp del negocio") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true
            )
        }
        item {
            Text("Datos para recibir pagos", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        item {
            OutlinedTextField(
                value = bank,
                onValueChange = { bank = it; saved = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Banco") },
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = accountType,
                onValueChange = { accountType = it; saved = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Tipo de cuenta") },
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = accountNumber,
                onValueChange = { accountNumber = it; saved = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("N° de cuenta") },
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = holder,
                onValueChange = { holder = it; saved = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Titular") },
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = rut,
                onValueChange = { rut = it; saved = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("RUT") },
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = paymentNotes,
                onValueChange = { paymentNotes = it; saved = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Observaciones de pago") },
                placeholder = { Text("Ej: Enviar comprobante por WhatsApp") }
            )
        }

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
                            paymentNotes = paymentNotes.trim()
                        )
                    )
                    saved = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Guardar ajustes")
            }
            if (saved) {
                Text("Guardado correctamente ✓", color = MaterialTheme.colorScheme.primary)
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Respaldo", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            OutlinedButton(onClick = onBackup, modifier = Modifier.fillMaxWidth()) {
                Text("Exportar respaldo")
            }
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Text("Importar respaldo")
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Alertas de vencimiento", fontWeight = FontWeight.Bold)
                    Text(
                        "La app ya identifica vencidos, pagos que vencen hoy y esta semana. " +
                            "Las notificaciones del sistema se agregarán en un bloque aparte porque " +
                            "requieren permiso y cambios en AndroidManifest."
                    )
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

private fun sendWhatsAppReminder(
    context: Context,
    client: Client,
    debt: Debt,
    business: BusinessSettings
) {
    val phone = normalizePhone(client.phone)
    val balance = remainingBalance(debt)
    val status = debtStatus(debt)
    val days = daysUntilDue(debt.dueDate)

    val intro = when {
        status == "Vencido" -> "Tienes un cobro vencido pendiente."
        debt.paidAmount > 0 -> "Gracias por tu abono. Te recordamos el saldo pendiente."
        days == 0L -> "Te recordamos que tu pago vence hoy."
        days != null && days in 1L..3L -> "Te recordamos que tu pago vence pronto."
        else -> "Te enviamos un recordatorio de pago."
    }

    val paymentData = buildPaymentData(business)

    val message = buildString {
        append("Hola ${client.name} 👋

")
        append("${business.name}
")
        append("$intro

")
        if (debt.concept.isNotBlank()) append("Concepto: ${debt.concept}
")
        append("Monto original: ${formatCurrency(debt.amount)}
")
        if (debt.paidAmount > 0) {
            append("Abonado: ${formatCurrency(debt.paidAmount.coerceAtMost(debt.amount))}
")
        }
        append("Saldo pendiente: ${formatCurrency(balance)}
")
        if (debt.dueDate.isNotBlank()) append("Vencimiento: ${debt.dueDate}
")
        append("Estado: $status
")
        if (paymentData.isNotBlank()) {
            append("
Datos de pago:
")
            append(paymentData)
            append("
")
        }
        append("
Si ya realizaste el pago, puedes ignorar este mensaje. Gracias.")
    }

    openWhatsApp(context, phone, message)
}

private fun sendWhatsAppClient(
    context: Context,
    client: Client,
    business: BusinessSettings
) {
    val phone = normalizePhone(client.phone)
    val message = "Hola ${client.name} 👋
Te escribimos de ${business.name}."
    openWhatsApp(context, phone, message)
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
        if (business.bank.isNotBlank()) append("Banco: ${business.bank}
")
        if (business.accountType.isNotBlank()) append("Cuenta: ${business.accountType}
")
        if (business.accountNumber.isNotBlank()) append("N°: ${business.accountNumber}
")
        if (business.holder.isNotBlank()) append("Titular: ${business.holder}
")
        if (business.rut.isNotBlank()) append("RUT: ${business.rut}
")
        if (business.paymentNotes.isNotBlank()) append("${business.paymentNotes}
")
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
        append("${business.name}
")
        append("Resumen de cuenta - ${client.name}

")
        append("Pendiente: ${formatCurrency(pending)}
")
        append("Pagado: ${formatCurrency(collected)}
")
        append("Cobros vencidos: $overdue
")
        append("Total de cobros: ${debts.size}
")
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
        append("${business.name}
")
        append("COMPROBANTE DE ABONO
")
        append("--------------------------------
")
        append("Fecha: ${currentDate()}
")
        append("Cliente: ${client?.name ?: "Cliente"}
")
        if (debt.concept.isNotBlank()) append("Concepto: ${debt.concept}
")
        append("Abono recibido: ${formatCurrency(paymentAmount)}
")
        append("Total original: ${formatCurrency(debt.amount)}
")
        append("Saldo pendiente: ${formatCurrency(remainingBalance(debt))}
")
        append("Estado: ${debtStatus(debt)}
")
        append("--------------------------------
")
        if (business.whatsapp.isNotBlank()) {
            append("Contacto: ${business.whatsapp}
")
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
            paymentNotes = b.optString("paymentNotes")
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
                    notes = o.optString("notes")
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
                    paid = o.optBoolean("paid")
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
            paymentNotes = o.optString("paymentNotes")
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
                    notes = obj.optString("notes")
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
            val paidAmount = if (obj.has("paidAmount")) {
                obj.optLong("paidAmount", 0L)
            } else {
                if (oldPaid) amount else 0L
            }

            result.add(
                Debt(
                    id = obj.getLong("id"),
                    clientId = obj.getLong("clientId"),
                    concept = obj.optString("concept"),
                    amount = amount,
                    paidAmount = paidAmount.coerceAtMost(amount),
                    dueDate = obj.optString("dueDate"),
                    paid = oldPaid || paidAmount >= amount
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
                .put("paid", debt.paid || remainingBalance(debt) == 0L)
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
