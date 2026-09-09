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
    val dueDate: String,
    val paid: Boolean
)

enum class Screen {
    HOME, CLIENTS, DEBTS
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
                onPaid = { debt ->
                    val index = debts.indexOfFirst { it.id == debt.id }
                    if (index >= 0) {
                        debts[index] = debt.copy(paid = true)
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
                        dueDate = dueDate.trim(),
                        paid = false
                    )
                )
                saveDebts(context, debts)
                showDebtDialog = false
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
    val pending = debts.filter { !it.paid }
    val paid = debts.filter { it.paid }
    val totalPending = pending.sumOf { it.amount }
    val totalPaid = paid.sumOf { it.amount }
    val overdue = pending.count { isOverdue(it.dueDate) }

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
                text = "Control simple de clientes y cobros",
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

        item { SummaryCard("Cobrado", formatCurrency(totalPaid)) }
        item { SummaryCard("Cobros vencidos", overdue.toString()) }
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
                val pending = debts.filter { it.clientId == client.id && !it.paid }.sumOf { it.amount }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(client.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(client.phone.ifBlank { "Sin teléfono" })
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Pendiente: ${formatCurrency(pending)}")
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
    onPaid: (Debt) -> Unit,
    onWhatsApp: (Debt) -> Unit
) {
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
        }

        if (debts.isEmpty()) {
            item {
                EmptyCard("Sin cobros", "Todavía no has registrado deudas.")
            }
        } else {
            items(debts.sortedByDescending { it.id }, key = { it.id }) { debt ->
                val client = clients.firstOrNull { it.id == debt.clientId }
                DebtCard(
                    clientName = client?.name ?: "Cliente",
                    debt = debt,
                    showActions = true,
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
    onPaid: () -> Unit = {},
    onWhatsApp: () -> Unit = {}
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(clientName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(debt.concept.ifBlank { "Cobro" })
            Text(
                formatCurrency(debt.amount),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text("Vence: ${debt.dueDate.ifBlank { "Sin fecha" }}")
            Text(if (debt.paid) "Pagado" else if (isOverdue(debt.dueDate)) "Vencido" else "Pendiente")

            if (showActions && !debt.paid) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onWhatsApp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Enviar recordatorio por WhatsApp")
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onPaid,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Marcar como pagado")
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
    val message = buildString {
        append("Hola ${client.name} 👋\n\n")
        append("Te recordamos que tienes un pago pendiente de ${formatCurrency(debt.amount)}")
        if (debt.concept.isNotBlank()) append(" por ${debt.concept}")
        if (debt.dueDate.isNotBlank()) append(", con vencimiento ${debt.dueDate}")
        append(".\n\nGracias.\nCobrosPyme")
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
            result.add(
                Debt(
                    id = obj.getLong("id"),
                    clientId = obj.getLong("clientId"),
                    concept = obj.optString("concept"),
                    amount = obj.getLong("amount"),
                    dueDate = obj.optString("dueDate"),
                    paid = obj.optBoolean("paid", false)
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
                .put("dueDate", debt.dueDate)
                .put("paid", debt.paid)
        )
    }

    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_DEBTS, array.toString())
        .apply()
}
