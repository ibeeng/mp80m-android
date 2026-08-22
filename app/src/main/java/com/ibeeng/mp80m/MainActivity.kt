package com.ibeeng.mp80m

import android.Manifest
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val vm: PrinterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppScreen(vm)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(vm: PrinterViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContextSafe()

    // Runtime permission for Android 12+ (BLUETOOTH_CONNECT/SCAN).
    var hasPerm by remember { mutableStateOf(hasBtPermission(context)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPerm = result.values.all { it }
        if (hasPerm) vm.refreshPaired()
    }

    LaunchedEffect(Unit) {
        if (hasPerm) vm.refreshPaired() else permLauncher.launch(btPermissions())
    }

    // Image picker (gallery)
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { vm.printImage(it) } }

    // PDF picker
    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { vm.printPdf(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MP-80M Printer") },
                actions = {
                    ConnectionChip(ui.connected)
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
                        Button(onClick = { permLauncher.launch(btPermissions()) }) {
                            Text("Beri Izin")
                        }
                    }
                }
            }

            // --- Printer ---
            SectionCard("Printer", Icons.Default.Bluetooth) {
                if (ui.connected) {
                    Text("Terhubung: ${ui.connectedMac}", color = Color(0xFF9CCC65))
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
                        Text(
                            "Belum ada printer ter-pairing.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        }) { Text("Buka Setelan Bluetooth") }
                    } else {
                        ui.paired.forEach { d ->
                            ListItem(
                                headlineContent = { Text(d.name) },
                                supportingContent = {
                                    Text(d.mac, fontFamily = FontFamily.Monospace)
                                },
                                trailingContent = {
                                    Button(onClick = { vm.connect(d.mac) }, enabled = !ui.busy) {
                                        Text("Hubungkan")
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // --- Print actions ---
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
                    onClick = { vm.printText(text) },
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
                    Spacer(Modifier.width(8.dp))
                    Text("Cetak Gambar (galeri)")
                }

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { pdfLauncher.launch("application/pdf") },
                    enabled = ui.connected && !ui.busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Cetak PDF")
                }
            }

            // --- Settings ---
            SectionCard("Setelan", Icons.Default.Settings) {
                var trim by remember(ui.settings.trim) { mutableStateOf(ui.settings.trim) }
                var margin by remember(ui.settings.trimMargin) {
                    mutableStateOf(ui.settings.trimMargin.toString())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = trim, onCheckedChange = { trim = it })
                    Text("Potong ruang kosong (hemat kertas)", Modifier.weight(1f))
                }
                OutlinedTextField(
                    value = margin,
                    onValueChange = { margin = it.filter(Char::isDigit).take(3) },
                    label = { Text("Sisa margin bawah (dot)") },
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    vm.updateSettings(trim, margin.toIntOrNull() ?: 16)
                }) { Text("Simpan Setelan") }
            }

            // --- Log ---
            if (ui.log.isNotEmpty()) {
                SectionCard("Log", Icons.Default.Article) {
                    ui.log.takeLast(8).forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (ui.busy) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
private fun ConnectionChip(connected: Boolean) {
    AssistChip(
        onClick = {},
        label = { Text(if (connected) "TERHUBUNG" else "OFFLINE") },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (connected) Color(0xFF2E7D32) else Color(0xFF757575),
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

// --- helpers ---

@Composable
private fun LocalContextSafe() = androidx.compose.ui.platform.LocalContext.current

private fun btPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
    }

private fun hasBtPermission(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    } else true
}
