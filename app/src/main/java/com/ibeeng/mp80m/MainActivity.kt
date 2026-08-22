package com.ibeeng.mp80m

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val vm: PrinterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDark()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppScreen(vm, ::consumeIncomingShare)
                }
            }
        }
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    private var pendingShareUri: Uri? = null
    private var pendingShareIsPdf = false

    private fun handleShare(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) {
            val type = intent.type ?: contentResolver.getType(uri) ?: ""
            pendingShareUri = uri
            pendingShareIsPdf = type.contains("pdf")
        }
    }

    /** Compose calls this after connect to consume any shared file. */
    private fun consumeIncomingShare(onImage: (Uri) -> Unit, onPdf: (Uri) -> Unit) {
        val uri = pendingShareUri ?: return
        if (pendingShareIsPdf) onPdf(uri) else onImage(uri)
        pendingShareUri = null
    }
}

@Composable
private fun isSystemInDark(): Boolean =
    androidx.compose.foundation.isSystemInDarkTheme()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    vm: PrinterViewModel,
    consumeShare: ((Uri) -> Unit, (Uri) -> Unit) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContextSafe()

    var hasPerm by remember { mutableStateOf(hasBtPermission(context)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPerm = result.values.all { it }
        if (hasPerm) vm.onReady()
    }

    LaunchedEffect(Unit) {
        if (hasPerm) vm.onReady() else permLauncher.launch(btPermissions())
    }

    // Once connected, consume any file shared into the app.
    LaunchedEffect(ui.connected) {
        if (ui.connected) consumeShare({ vm.stageImage(it) }, { vm.stagePdf(it) })
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { vm.stageImage(it) } }
    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { vm.stagePdf(it) } }

    // Preview dialog when a job is staged
    ui.pending?.let { job ->
        PreviewDialog(
            job = job,
            copies = ui.settings.copies,
            onConfirm = { vm.confirmPrint() },
            onCancel = { vm.cancelPending() },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MP-80M Printer") },
                actions = {
                    ConnectionChip(ui.connected, ui.connecting)
                    Spacer(Modifier.width(8.dp))
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!hasPerm) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Izin Bluetooth diperlukan", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = { permLauncher.launch(btPermissions()) }) { Text("Beri Izin") }
                    }
                }
            }

            PrinterSection(ui, vm, context)
            PrintSection(ui, vm, galleryLauncher, pdfLauncher)
            TemplatesSection(ui, vm)
            SettingsSection(ui, vm)
            LogSection(ui)

            if (ui.busy) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

// ---------------- Sections ----------------

@Composable
private fun PrinterSection(ui: UiState, vm: PrinterViewModel, context: Context) {
    SectionCard("Printer", Icons.Default.Bluetooth) {
        if (ui.connected) {
            Text("Terhubung: ${ui.connectedMac}", color = Color(0xFF4CAF50))
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.disconnect() }) { Text("Putuskan") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pilih printer (yang sudah di-pairing):", Modifier.weight(1f))
                IconButton(onClick = { vm.refreshPaired() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Muat ulang")
                }
            }
            if (ui.paired.isEmpty()) {
                Text("Belum ada printer ter-pairing.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }) { Text("Buka Setelan Bluetooth") }
            } else {
                ui.paired.forEach { d ->
                    ListItem(
                        headlineContent = { Text(d.name) },
                        supportingContent = { Text(d.mac, fontFamily = FontFamily.Monospace) },
                        trailingContent = {
                            Button(onClick = { vm.connect(d.mac) }, enabled = !ui.connecting) {
                                Text("Hubungkan")
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PrintSection(
    ui: UiState,
    vm: PrinterViewModel,
    galleryLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    pdfLauncher: androidx.activity.result.ActivityResultLauncher<String>,
) {
    SectionCard("Cetak", Icons.Default.Print) {
        var text by remember { mutableStateOf("") }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Teks (opsional)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { vm.stageText(text) },
            enabled = ui.connected && !ui.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Cetak Teks") }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { galleryLauncher.launch("image/*") },
            enabled = ui.connected && !ui.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Image, contentDescription = null)
            Spacer(Modifier.width(8.dp)); Text("Cetak Gambar (galeri)")
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { pdfLauncher.launch("application/pdf") },
            enabled = ui.connected && !ui.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.PictureAsPdf, contentDescription = null)
            Spacer(Modifier.width(8.dp)); Text("Cetak PDF")
        }
        Text(
            "Tip: bisa juga Share gambar/PDF dari app lain ke MP-80M Printer.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun TemplatesSection(ui: UiState, vm: PrinterViewModel) {
    SectionCard("Template Teks", Icons.Default.Bookmark) {
        var tplName by remember { mutableStateOf("") }
        var tplBody by remember { mutableStateOf("") }
        ui.templates.forEach { t ->
            ListItem(
                headlineContent = { Text(t.name) },
                supportingContent = {
                    Text(t.body.take(40).replace("\n", " "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = {
                    Row {
                        TextButton(
                            onClick = { vm.stageText(t.body) },
                            enabled = ui.connected
                        ) { Text("Cetak") }
                        IconButton(onClick = { vm.removeTemplate(t.name) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Hapus")
                        }
                    }
                }
            )
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = tplName, onValueChange = { tplName = it },
            label = { Text("Nama template") }, modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        OutlinedTextField(
            value = tplBody, onValueChange = { tplBody = it },
            label = { Text("Isi template") }, modifier = Modifier.fillMaxWidth(), minLines = 2
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            vm.addTemplate(tplName, tplBody); tplName = ""; tplBody = ""
        }, enabled = tplName.isNotBlank()) { Text("Simpan Template") }
    }
}

@Composable
private fun SettingsSection(ui: UiState, vm: PrinterViewModel) {
    SectionCard("Setelan", Icons.Default.Settings) {
        var trim by remember(ui.settings.trim) { mutableStateOf(ui.settings.trim) }
        var auto by remember(ui.settings.autoReconnect) { mutableStateOf(ui.settings.autoReconnect) }
        var margin by remember(ui.settings.trimMargin) { mutableStateOf(ui.settings.trimMargin.toString()) }
        var copies by remember(ui.settings.copies) { mutableStateOf(ui.settings.copies.toString()) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = trim, onCheckedChange = { trim = it })
            Text("Potong ruang kosong (hemat kertas)", Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = auto, onCheckedChange = { auto = it })
            Text("Auto-reconnect printer terakhir", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = margin,
                onValueChange = { margin = it.filter(Char::isDigit).take(3) },
                label = { Text("Margin bawah (dot)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = copies,
                onValueChange = { copies = it.filter(Char::isDigit).take(2) },
                label = { Text("Jumlah copy") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            vm.updateSettings(
                trim = trim,
                trimMargin = margin.toIntOrNull() ?: 16,
                autoReconnect = auto,
                copies = (copies.toIntOrNull() ?: 1).coerceIn(1, 20),
            )
        }) { Text("Simpan Setelan") }
    }
}

@Composable
private fun LogSection(ui: UiState) {
    if (ui.log.isEmpty()) return
    SectionCard("Log", Icons.Default.Description) {
        ui.log.takeLast(8).forEach {
            Text(
                it, style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PreviewDialog(
    job: PendingJob,
    copies: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Pratinjau — ${job.label}") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (job.previewBitmaps.isEmpty()) {
                    Text(
                        "Teks akan dicetak dengan font bawaan printer.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (job is PendingJob.TextJob) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                job.text, Modifier.padding(8.dp),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                } else {
                    job.previewBitmaps.forEach { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "pratinjau",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Text("Jumlah copy: $copies", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Cetak Sekarang") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Batal") } }
    )
}

// ---------------- Small components ----------------

@Composable
private fun ConnectionChip(connected: Boolean, connecting: Boolean) {
    val label = when {
        connecting -> "MENYAMBUNG…"
        connected -> "TERHUBUNG"
        else -> "OFFLINE"
    }
    AssistChip(
        onClick = {},
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = when {
                connecting -> Color(0xFFF9A825)
                connected -> Color(0xFF2E7D32)
                else -> Color(0xFF757575)
            },
            labelColor = Color.White
        )
    )
}

@Composable
private fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

// ---------------- helpers ----------------

@Composable
private fun LocalContextSafe() = androidx.compose.ui.platform.LocalContext.current

private fun btPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
    }

private fun hasBtPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    } else true
}
