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
private const val KEY_PAYMENTS = "payments"
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
    val payments = remember {
        mutableStateListOf<PaymentRecord>().apply { addAll(loadPayments(context)) }
    }

    var screen by remember { mutableStateOf(Screen.HOME) }
    var showClientDialog by remember { mutableStateOf(false) }
    var editingClient by remember { mutableStateOf<Client?>(null) }
    var showDebtDialog by remember { mutableStateOf(false) }
    var paymentDebt by remember { mutableStateOf<Debt?>(null) }
    var selectedClient by remember { mutableStateOf<Client?>(null) }
    var selectedDebt by remember { mutableStateOf<Debt?>(null) }

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
                onOpenDebts = { screen = Screen.DEBTS },
                onOpenDebt = { selectedDebt = it }
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
                            payments.add(
                                PaymentRecord(
                                    id = System.currentTimeMillis(),
                                    debtId = debt.id,
                                    amount = balanceBefore,
                                    date = currentDate()
                                )
                            )
                            savePayments(context, payments)
                        }
                    }
                },
                onWhatsApp = { debt ->
                    val client = clients.firstOrNull { it.id == debt.clientId }
                    if (client != null) sendWhatsAppReminder(context, client, debt)
                },
                onOpenDebt = { selectedDebt = it }
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
                    val isPaid = newPaidAmount >= debt.amount
                    debts[index] = debt.copy(
                        paidAmount = newPaidAmount,
                        paid = isPaid
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
                    }
                }
                paymentDebt = null
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
            onWhatsApp = { sendWhatsAppClient(context, client) },
            onShareSummary = {
                shareClientSummary(
                    context = context,
                    client = client,
                    debts = debts.filter { it.clientId == client.id }
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
                if (client != null) sendWhatsAppReminder(context, client, debt)
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
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
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
    onNewClient: () -> Unit,
    onNewDebt: () -> Unit,
    onOpenClients: () -> Unit,
    onOpenDebts: () -> Unit,
    onOpenDebt: (Debt) -> Unit
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = "CobrosPyme",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Controla clientes, deudas y abonos",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = APP_VERSION_LABEL,
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
                        text = formatCurrency(totalPending),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Cobrado acumulado: ${formatCurrency(totalCollected)}",
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        item { SummaryCard("Cobros vencidos", overdue.toString(), StatusType.OVERDUE) }
        item { SummaryCard("Próximos / pendientes", upcoming.toString(), StatusType.PENDING) }
        item { SummaryCard("Clientes registrados", clients.size.toString(), StatusType.NEUTRAL) }

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
                    showActions = false,
                    onOpen = { onOpenDebt(debt) }
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
            client?.name?.contains(query, ignoreCase = true) == true
        statusMatches && textMatches
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
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Buscar cobro") },
                placeholder = { Text("Cliente o concepto") },
                singleLine = true
            )
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
                EmptyCard("Sin cobros", "No hay cobros para este filtro o búsqueda.")
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        shape = RoundedCornerShape(16.dp)
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
                Text("Abonado: ${formatCurrency(debt.paidAmount.coerceAtMost(debt.amount))}")
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
                Spacer(modifier = Modifier.height(2.dp))
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
    onWhatsApp: () -> Unit
) {
    val recorded = payments.sumOf { it.amount }
    val legacyPaid = (debt.paidAmount - recorded).coerceAtLeast(0L)

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
                    Text("Saldo: ${formatCurrency(remainingBalance(debt))}", fontWeight = FontWeight.Bold)
                    Text("Vence: ${debt.dueDate.ifBlank { "Sin fecha" }}")
                }

                if (legacyPaid > 0 || payments.isNotEmpty()) {
                    item { Text("Historial de abonos", fontWeight = FontWeight.Bold, fontSize = 17.sp) }
                    if (legacyPaid > 0) {
                        item {
                            PaymentRow(
                                label = "Abono anterior",
                                amount = legacyPaid
                            )
                        }
                    }
                    items(payments.sortedByDescending { it.id }, key = { it.id }) { payment ->
                        PaymentRow(
                            label = payment.date,
                            amount = payment.amount
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

    openWhatsApp(context, phone, message)
}

private fun sendWhatsAppClient(context: Context, client: Client) {
    val phone = normalizePhone(client.phone)
    val message = "Hola ${client.name} 👋"
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

private fun shareClientSummary(context: Context, client: Client, debts: List<Debt>) {
    val pending = debts.sumOf { remainingBalance(it) }
    val collected = debts.sumOf { it.paidAmount.coerceAtMost(it.amount) }
    val overdue = debts.count { debtStatus(it) == "Vencido" }

    val text = buildString {
        append("Resumen CobrosPyme - ${client.name}\n\n")
        append("Pendiente: ${formatCurrency(pending)}\n")
        append("Pagado: ${formatCurrency(collected)}\n")
        append("Cobros vencidos: $overdue\n")
        append("Total de cobros: ${debts.size}\n")
    }

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Compartir resumen"))
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
