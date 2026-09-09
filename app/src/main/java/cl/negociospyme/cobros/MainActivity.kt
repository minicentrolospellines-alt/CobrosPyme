package cl.negociospyme.cobros

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PREFS = "cobrospyme_data"
private const val KEY_CLIENTS = "clients"
private const val KEY_DEBTS = "debts"

data class Client(
    val id: Long,
    val name: String,
    val phone: String
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

enum class Screen {
    HOME, CLIENTS, DEBTS
}

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

    var screen by remember { mutableStateOf(Screen.HOME) }
    var showClientDialog by remember { mutableStateOf(false) }
    var showDebtDialog by remember { mutableStateOf(false) }
    var paymentDebt by remember { mutableStateOf<Debt?>(null) }

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
                onNewClient = { showClientDialog = true },
                onNewDebt = {
                    if (clients.isEmpty()) showClientDialog = true else showDebtDialog = true
                },
                onOpenClients = { screen = Screen.CLIENTS },
                onOpenDebts = { screen = Screen.DEBTS }
            )

            Screen.CLIENTS -> ClientsScreen(
                modifier = Modifier.padding(innerPadding),
                clients = clients,
                debts = debts,
                onNewClient = { showClientDialog = true }
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
                        debts[index] = debt.copy(
                            paidAmount = debt.amount,
                            paid = true
                        )
                        saveDebts(context, debts)
                    }
                },
                onWhatsApp = { debt ->
                    val client = clients.firstOrNull { it.id == debt.clientId }
                    if (client != null) sendWhatsAppReminder(context, client, debt)
                }
            )
        }
    }

    if (showClientDialog) {
        NewClientDialog(
            onDismiss = { showClientDialog = false },
            onSave = { name, phone ->
                clients.add(
                    Client(
                        id = System.currentTimeMillis(),
                        name = name.trim(),
                        phone = phone.trim()
                    )
                )
                saveClients(context, clients)
                showClientDialog = false
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
                    val newPaidAmount = (debt.paidAmount + payment).coerceAtMost(debt.amount)
                    val isPaid = newPaidAmount >= debt.amount
                    debts[index] = debt.copy(
                        paidAmount = newPaidAmount,
                        paid = isPaid
                    )
                    saveDebts(context, debts)
                }
                paymentDebt = null
            }
        )
    }
}

@Composable
private fun BottomMenu(
    current: Screen,
    onChange: (Screen) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            MenuButton("Inicio", current == Screen.HOME) { onChange(Screen.HOME) }
            MenuButton("Clientes", current == Screen.CLIENTS) { onChange(Screen.CLIENTS) }
            MenuButton("Cobros", current == Screen.DEBTS) { onChange(Screen.DEBTS) }
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
        Button(onClick = onClick) { Text(text) }
    } else {
        TextButton(onClick = onClick) { Text(text) }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    clients: List<Client>,
    debts: List<Debt>,
    onNewClient: () -> Unit,
    onNewDebt: () -> Unit,
    onOpenClients: () -> Unit,
    onOpenDebts: () -> Unit
) {
    val totalPending = debts.sumOf { remainingBalance(it) }
    val totalCollected = debts.sumOf { it.paidAmount.coerceAtMost(it.amount) }
    val overdue = debts.count { debtStatus(it) == "Vencido" }
    val upcoming = debts.count { debtStatus(it) == "Pendiente" }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "CobrosPyme",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Controla clientes, deudas y abonos",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Total por cobrar")
                    Text(
                        text = formatCurrency(totalPending),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        item { SummaryCard("Cobrado", formatCurrency(totalCollected)) }
        item { SummaryCard("Cobros vencidos", overdue.toString()) }
        item { SummaryCard("Próximos / pendientes", upcoming.toString()) }
        item { SummaryCard("Clientes registrados", clients.size.toString()) }

        item {
            Button(
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
                Text(
                    text = "Cobros recientes",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onOpenDebts) { Text("Ver todos") }
            }
        }

        if (debts.isEmpty()) {
            item {
                EmptyCard(
                    title = "Aún no tienes cobros",
                    subtitle = "Agrega un cliente y registra su primera deuda."
                )
            }
        } else {
            items(debts.sortedByDescending { it.id }.take(5), key = { it.id }) { debt ->
                val client = clients.firstOrNull { it.id == debt.clientId }
                DebtCard(
                    clientName = client?.name ?: "Cliente",
                    debt = debt,
                    showActions = false
                )
            }
        }

        item {
            TextButton(onClick = onOpenClients) {
                Text("Ver lista de clientes")
            }
        }
    }
}

@Composable
private fun SummaryCard(title: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ClientsScreen(
    modifier: Modifier,
    clients: List<Client>,
    debts: List<Debt>,
    onNewClient: () -> Unit
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Clientes", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onNewClient,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("+ Nuevo cliente")
            }
        }

        if (clients.isEmpty()) {
            item {
                EmptyCard("Sin clientes", "Registra tu primer cliente para comenzar.")
            }
        } else {
            items(clients.sortedBy { it.name.lowercase() }, key = { it.id }) { client ->
                val clientDebts = debts.filter { it.clientId == client.id }
                val pending = clientDebts.sumOf { remainingBalance(it) }
                val collected = clientDebts.sumOf { it.paidAmount.coerceAtMost(it.amount) }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(client.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(client.phone.ifBlank { "Sin teléfono" })
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Pendiente: ${formatCurrency(pending)}")
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
    onWhatsApp: (Debt) -> Unit
) {
    var filter by remember { mutableStateOf(DebtFilter.ALL) }
    var filterMenu by remember { mutableStateOf(false) }

    val filteredDebts = debts.filter { debt ->
        when (filter) {
            DebtFilter.ALL -> true
            DebtFilter.PENDING -> debtStatus(debt) == "Pendiente"
            DebtFilter.OVERDUE -> debtStatus(debt) == "Vencido"
            DebtFilter.PAID -> debtStatus(debt) == "Pagado"
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Cobros", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onNewDebt,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("+ Nueva deuda")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box {
                OutlinedButton(
                    onClick = { filterMenu = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Filtro: ${filter.label}")
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
        }

        if (filteredDebts.isEmpty()) {
            item {
                EmptyCard("Sin cobros", "No hay cobros para este filtro.")
            }
        } else {
            items(filteredDebts.sortedByDescending { it.id }, key = { it.id }) { debt ->
                val client = clients.firstOrNull { it.id == debt.clientId }
                DebtCard(
                    clientName = client?.name ?: "Cliente",
                    debt = debt,
                    showActions = true,
                    onPayment = { onPayment(debt) },
                    onPaid = { onPaid(debt) },
                    onWhatsApp = { onWhatsApp(debt) }
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
    onWhatsApp: () -> Unit = {}
) {
    val balance = remainingBalance(debt)
    val status = debtStatus(debt)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(clientName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(debt.concept.ifBlank { "Cobro" })
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Total: ${formatCurrency(debt.amount)}",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text("Abonado: ${formatCurrency(debt.paidAmount.coerceAtMost(debt.amount))}")
            Text(
                "Saldo: ${formatCurrency(balance)}",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text("Vence: ${debt.dueDate.ifBlank { "Sin fecha" }}")
            Text("Estado: $status", fontWeight = FontWeight.Bold)

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
                    Text("Enviar recordatorio por WhatsApp")
                }
                Spacer(modifier = Modifier.height(6.dp))
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
private fun NewClientDialog(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo cliente") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("WhatsApp / teléfono") },
                    placeholder = { Text("Ej: 56912345678") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onSave(name, phone) },
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nueva deuda") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

                OutlinedTextField(
                    value = concept,
                    onValueChange = { concept = it },
                    label = { Text("Concepto") },
                    placeholder = { Text("Ej: Cuota septiembre") },
                    singleLine = true
                )

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter(Char::isDigit) },
                    label = { Text("Monto") },
                    placeholder = { Text("Ej: 15000") },
                    singleLine = true
                )

                OutlinedTextField(
                    value = dueDate,
                    onValueChange = { dueDate = it },
                    label = { Text("Vencimiento") },
                    placeholder = { Text("dd/mm/aaaa") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(selectedClientId, concept, amount, dueDate) },
                enabled = selectedClientId != 0L && amount > 0
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

private fun normalizePhone(phone: String): String {
    val digits = phone.filter(Char::isDigit)
    return when {
        digits.startsWith("56") -> digits
        digits.length == 9 && digits.startsWith("9") -> "56$digits"
        else -> digits
    }
}

private fun sendWhatsAppReminder(context: Context, client: Client, debt: Debt) {
    val phone = normalizePhone(client.phone)
    val balance = remainingBalance(debt)
    val status = debtStatus(debt)

    val message = buildString {
        append("Hola ${client.name} 👋\n\n")
        append("Te enviamos un recordatorio de pago desde CobrosPyme.\n\n")
        if (debt.concept.isNotBlank()) append("Concepto: ${debt.concept}\n")
        append("Monto original: ${formatCurrency(debt.amount)}\n")
        if (debt.paidAmount > 0) append("Abonado: ${formatCurrency(debt.paidAmount)}\n")
        append("Saldo pendiente: ${formatCurrency(balance)}\n")
        if (debt.dueDate.isNotBlank()) append("Vencimiento: ${debt.dueDate}\n")
        append("Estado: $status\n\n")
        append("Si ya realizaste el pago, puedes ignorar este mensaje. Gracias.")
    }

    val url = if (phone.isBlank()) {
        "https://wa.me/?text=${Uri.encode(message)}"
    } else {
        "https://wa.me/$phone?text=${Uri.encode(message)}"
    }

    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    context.startActivity(intent)
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
                    phone = obj.optString("phone")
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
