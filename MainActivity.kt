package uz.meningmoliyam.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createChannel()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent { FinanceApp(FinanceDb.get(this).dao()) }
    }
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("finance_reminders", "Moliyaviy eslatmalar", NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

private fun money(value: Long): String =
    NumberFormat.getNumberInstance(Locale("uz", "UZ")).format(value) + " so'm"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceApp(dao: FinanceDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(emptyList<FinanceEntry>()) }
    var tab by remember { mutableIntStateOf(0) }
    var dark by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var monthOffset by remember { mutableIntStateOf(0) }

    fun reload() { scope.launch { entries = dao.all() } }
    LaunchedEffect(Unit) { reload() }

    val now = Calendar.getInstance()
    now.add(Calendar.MONTH, monthOffset)
    val month = now.get(Calendar.MONTH)
    val year = now.get(Calendar.YEAR)
    val monthEntries = entries.filter {
        val c = Calendar.getInstance().apply { timeInMillis = it.date }
        c.get(Calendar.MONTH) == month && c.get(Calendar.YEAR) == year
    }
    val income = monthEntries.filter { it.kind == "INCOME" }.sumOf { it.amount }
    val expenses = monthEntries.filter { it.kind == "EXPENSE" || it.kind == "DEBT_PAYMENT" }.sumOf { it.amount }
    val owed = entries.filter { it.kind == "DEBT_OWED" }.sumOf { it.amount } -
        entries.filter { it.kind == "DEBT_PAYMENT" && it.note == "qarzimdan" }.sumOf { it.amount }
    val given = entries.filter { it.kind == "DEBT_GIVEN" }.sumOf { it.amount }

    MaterialTheme(colorScheme = if (dark) darkColorScheme(
        primary = Color(0xFF70D6A4), secondary = Color(0xFF8CB7FF),
        background = Color(0xFF101713), surface = Color(0xFF19231D)
    ) else lightColorScheme(
        primary = Color(0xFF087F5B), secondary = Color(0xFF3867A8),
        background = Color(0xFFF5F7F6), surface = Color.White
    )) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Column {
                        Text("Mening Moliyam", fontWeight = FontWeight.Bold)
                        Text("Shaxsiy moliyaviy nazorat", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } },
                    actions = {
                        IconButton(onClick = { dark = !dark }) {
                            Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, "Rejimni almashtirish")
                        }
                        IconButton(onClick = { sendReminder(context) }) { Icon(Icons.Default.Notifications, "Eslatma") }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    listOf("Bosh sahifa", "Qarzlar", "Hisobot", "Yozuvlar").forEachIndexed { i, label ->
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i },
                            icon = { Icon(listOf(Icons.Default.Dashboard, Icons.Default.AccountBalanceWallet, Icons.Default.BarChart, Icons.Default.ReceiptLong)[i], null) },
                            label = { Text(label, fontSize = 10.sp) }
                        )
                    }
                }
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { dialog = "TANLASH" }) { Icon(Icons.Default.Add, "Yangi yozuv") }
            }
        ) { padding ->
            when (tab) {
                0 -> Dashboard(entries, income, expenses, owed, given, Modifier.padding(padding))
                1 -> Debts(entries, owed, given, Modifier.padding(padding))
                2 -> Report(monthOffset, { monthOffset += it }, monthEntries, income, expenses, Modifier.padding(padding))
                else -> Entries(entries, Modifier.padding(padding), onDelete = { scope.launch { dao.delete(it); reload() } })
            }
        }

        if (dialog != null) {
            var selectedKind by remember(dialog) { mutableStateOf("INCOME") }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(if (dialog == "TANLASH") "Yangi yozuv turi" else "Yangi yozuv") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (dialog == "TANLASH") {
                            listOf("INCOME" to "Daromad", "EXPENSE" to "Xarajat", "DEBT_OWED" to "Qarz oldim", "DEBT_GIVEN" to "Qarz berdim", "DEBT_PAYMENT" to "Qarz to‘ladim").forEach { (k, v) ->
                                OutlinedButton(onClick = { selectedKind = k; dialog = k }) { Text(v) }
                            }
                        } else {
                            Text(when(dialog) {
                                "INCOME" -> "Daromad qo‘shish"
                                "EXPENSE" -> "Xarajat qo‘shish"
                                "DEBT_OWED" -> "Olingan qarz"
                                "DEBT_GIVEN" -> "Berilgan qarz"
                                else -> "Qarz to‘lovi"
                            }, fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(title, { title = it }, label = { Text("Nomi / kimga yoki kimdan") }, singleLine = true)
                            OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("Summa (so‘m)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                            OutlinedTextField(note, { note = it }, label = { Text("Izoh (ixtiyoriy)") }, singleLine = true)
                        }
                    }
                },
                confirmButton = {
                    if (dialog != "TANLASH") TextButton(onClick = {
                        val value = amount.toLongOrNull()
                        if (title.isNotBlank() && value != null && value > 0) {
                            scope.launch {
                                dao.insert(FinanceEntry(kind = dialog!!, title = title.trim(), amount = value, note = note))
                                entries = dao.all()
                                title = ""; amount = ""; note = ""; dialog = null
                            }
                        }
                    }) { Text("Saqlash") }
                },
                dismissButton = { TextButton(onClick = { dialog = null; title = ""; amount = ""; note = "" }) { Text("Bekor qilish") } }
            )
        }
    }
}

@Composable
private fun Dashboard(entries: List<FinanceEntry>, income: Long, expenses: Long, owed: Long, given: Long, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("Joriy oyda qolgan pul", color = Color.White.copy(alpha = .8f))
                    Spacer(Modifier.height(8.dp))
                    Text(money(income - expenses), color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("Daromad minus xarajatlar", color = Color.White.copy(alpha = .8f), fontSize = 12.sp)
                }
            }
        }
        item { Text("Moliyaviy ko‘rsatkichlar", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Daromad", income, Color(0xFF087F5B), Modifier.weight(1f))
                MetricCard("Xarajat", expenses, Color(0xFFD45B54), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Qarz oldim", owed.coerceAtLeast(0), Color(0xFFB7791F), Modifier.weight(1f))
                MetricCard("Qarz berdim", given, Color(0xFF3867A8), Modifier.weight(1f))
            }
        }
        item { Text("So‘nggi yozuvlar", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        items(entries.take(5)) { EntryRow(it) }
        if (entries.isEmpty()) item { Text("Hozircha yozuv yo‘q. + tugmasi orqali birinchi yozuvni kiriting.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun MetricCard(label: String, value: Long, tint: Color, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.height(7.dp))
            Text(money(value), color = tint, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}


private data class DebtPlan(val name: String, val balance: Long, val color: Color)
private data class PaymentMonth(val label: String, val anorMicro: Long, val anorNasiya: Long, val agro: Long, val avo: Long) {
    val total: Long get() = anorMicro + anorNasiya + agro + avo
}
private val importedDebts = listOf(
    DebtPlan("Anor mikroqarz", 2_134_218L, Color(0xFFE1306C)),
    DebtPlan("Anor nasiya", 4_814_000L, Color(0xFF7048E8)),
    DebtPlan("Agrobank", 7_070_000L, Color(0xFF2F9E44)),
    DebtPlan("Avo Bank", 4_089_081L, Color(0xFFF08C00))
)
private val importedSchedule = listOf(
    PaymentMonth("Noy 2026", 525227, 112025, 0, 0),
    PaymentMonth("Dek 2026", 525227, 733179, 204106, 113586),
    PaymentMonth("Yan 2027", 525227, 733179, 204106, 113586),
    PaymentMonth("Fev 2027", 558537, 729179, 204106, 113586),
    PaymentMonth("Mar 2027", 0, 719179, 204106, 113586),
    PaymentMonth("Apr 2027", 0, 682187, 204106, 113586),
    PaymentMonth("May 2027", 0, 265515, 204106, 113586),
    PaymentMonth("Iyun 2027", 0, 230799, 204106, 113586),
    PaymentMonth("Iyul 2027", 0, 193191, 204106, 113586),
    PaymentMonth("Avg 2027", 0, 156433, 204106, 113586),
    PaymentMonth("Sen 2027", 0, 147101, 204106, 113586),
    PaymentMonth("Okt 2027", 0, 112033, 204106, 113586),
    PaymentMonth("Noy 2027", 0, 0, 4106, 113586),
    PaymentMonth("Dek 2027", 0, 0, 4106, 113586),
    PaymentMonth("Yan 2028", 0, 0, 4106, 113586),
    PaymentMonth("Fev 2028", 0, 0, 4106, 113586),
    PaymentMonth("Mar 2028", 0, 0, 4106, 113586),
    PaymentMonth("Apr 2028", 0, 0, 4804300, 113586),
    PaymentMonth("May 2028", 0, 0, 0, 113586),
    PaymentMonth("Iyun 2028", 0, 0, 0, 113586),
    PaymentMonth("Iyul 2028", 0, 0, 0, 113586),
    PaymentMonth("Avg 2028", 0, 0, 0, 113586),
    PaymentMonth("Sen 2028", 0, 0, 0, 113586),
    PaymentMonth("Okt 2028", 0, 0, 0, 113586),
    PaymentMonth("Noy 2028", 0, 0, 0, 113586),
    PaymentMonth("Dek 2028", 0, 0, 0, 113586),
    PaymentMonth("Yan 2029", 0, 0, 0, 113586),
    PaymentMonth("Fev 2029", 0, 0, 0, 113586),
    PaymentMonth("Mar 2029", 0, 0, 0, 113586),
    PaymentMonth("Apr 2029", 0, 0, 0, 113586),
    PaymentMonth("May 2029", 0, 0, 0, 113586),
    PaymentMonth("Iyun 2029", 0, 0, 0, 113586),
    PaymentMonth("Iyul 2029", 0, 0, 0, 113586),
    PaymentMonth("Avg 2029", 0, 0, 0, 113586),
    PaymentMonth("Sen 2029", 0, 0, 0, 113586),
    PaymentMonth("Okt 2029", 0, 0, 0, 113586),
    PaymentMonth("Noy 2029", 0, 0, 0, 113586)
)

@Composable
private fun Debts(entries: List<FinanceEntry>, owed: Long, given: Long, modifier: Modifier = Modifier) {
    var showSchedule by remember { mutableStateOf(true) }
    val totalDebt = importedDebts.sumOf { it.balance }
    LazyColumn(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Qarzlar nazorati", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Siz yuborgan ro‘yxat asosida • 11.11.2026 holatiga", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Jami qolgan qarz", color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(money(totalDebt), fontSize = 25.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        items(importedDebts) { debt ->
            Card {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(debt.color, RoundedCornerShape(50)))
                    Spacer(Modifier.width(10.dp))
                    Text(debt.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(money(debt.balance), fontWeight = FontWeight.Bold, color = debt.color, fontSize = 13.sp)
                }
            }
        }
        item {
            OutlinedButton(onClick = { showSchedule = !showSchedule }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showSchedule) "Oylik to‘lov jadvalini yashirish" else "Oylik to‘lov jadvalini ko‘rsatish")
                Icon(if (showSchedule) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
        }
        if (showSchedule) {
            item {
                Text("Oylik umumiy to‘lov jadvali", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("Jadvaldagi summalar so‘mda. “—” qiymati 0 so‘mni anglatadi.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            item {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) {
                    Text("Oy", Modifier.weight(1.0f), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    Text("Mikro", Modifier.weight(0.9f), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    Text("Nasiya", Modifier.weight(0.9f), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    Text("Agro", Modifier.weight(0.8f), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    Text("Avo", Modifier.weight(0.8f), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    Text("Jami", Modifier.weight(0.9f), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                }
            }
            items(importedSchedule) { row ->
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 7.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.label, Modifier.weight(1.0f), fontSize = 9.sp)
                    Text(if (row.anorMicro == 0L) "—" else NumberFormat.getNumberInstance(Locale.US).format(row.anorMicro), Modifier.weight(0.9f), fontSize = 9.sp)
                    Text(if (row.anorNasiya == 0L) "—" else NumberFormat.getNumberInstance(Locale.US).format(row.anorNasiya), Modifier.weight(0.9f), fontSize = 9.sp)
                    Text(if (row.agro == 0L) "—" else NumberFormat.getNumberInstance(Locale.US).format(row.agro), Modifier.weight(0.8f), fontSize = 9.sp)
                    Text(if (row.avo == 0L) "—" else NumberFormat.getNumberInstance(Locale.US).format(row.avo), Modifier.weight(0.8f), fontSize = 9.sp)
                    Text(NumberFormat.getNumberInstance(Locale.US).format(row.total), Modifier.weight(0.9f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                HorizontalDivider()
            }
        }
        item {
            Text("Eslatma: bu jadval rasmda berilgan reja sifatida saqlandi. Haqiqiy to‘lovlar va qarz qoldig‘i o‘zgarsa, ularni alohida yangilash kerak.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Report(offset: Int, change: (Int) -> Unit, entries: List<FinanceEntry>, income: Long, expenses: Long, modifier: Modifier = Modifier) {
    val cal = Calendar.getInstance().apply { add(Calendar.MONTH, offset) }
    val label = SimpleDateFormat("MMMM yyyy", Locale("uz")).format(cal.time)
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Oylik hisobot", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Card {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { change(-1) }) { Icon(Icons.Default.ChevronLeft, "Oldingi oy") }
                Text(label.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold)
                IconButton(onClick = { change(1) }) { Icon(Icons.Default.ChevronRight, "Keyingi oy") }
            }
        }
        MetricCard("Jami daromad", income, Color(0xFF087F5B), Modifier.fillMaxWidth())
        MetricCard("Jami xarajat va to‘lovlar", expenses, Color(0xFFD45B54), Modifier.fillMaxWidth())
        MetricCard("Oy yakunidagi sof natija", income - expenses, if (income >= expenses) Color(0xFF087F5B) else Color(0xFFD45B54), Modifier.fillMaxWidth())
        Text("Tanlangan oydagi yozuvlar", fontWeight = FontWeight.SemiBold)
        LazyColumn { items(entries) { EntryRow(it) } }
    }
}

@Composable
private fun Entries(entries: List<FinanceEntry>, modifier: Modifier = Modifier, onDelete: (FinanceEntry) -> Unit) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("Barcha yozuvlar", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.id }) { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { EntryRow(e) }
                    IconButton(onClick = { onDelete(e) }) { Icon(Icons.Default.Delete, "O‘chirish", tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(e: FinanceEntry) {
    val color = when(e.kind) {
        "INCOME" -> Color(0xFF087F5B)
        "EXPENSE", "DEBT_PAYMENT" -> Color(0xFFD45B54)
        else -> Color(0xFFB7791F)
    }
    val label = when(e.kind) {
        "INCOME" -> "Daromad"
        "EXPENSE" -> "Xarajat"
        "DEBT_OWED" -> "Qarz oldim"
        "DEBT_GIVEN" -> "Qarz berdim"
        else -> "Qarz to‘lovi"
    }
    Card(shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.title, fontWeight = FontWeight.SemiBold)
                Text("$label • ${SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(e.date))}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(money(e.amount), color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

private fun sendReminder(context: Context) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val notification = NotificationCompat.Builder(context, "finance_reminders")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Mening Moliyam")
        .setContentText("Bugungi daromad va xarajatlaringizni kiritishni unutmang.")
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .build()
    NotificationManagerCompat.from(context).notify(1001, notification)
}
