
package com.opendrop.app

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor

private const val PORT = 45454
private const val PROTOCOL = "OPENDROP1"

private const val OPENDROP_SERVICE_TYPE = "_opendrop._tcp."

private data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int
)

private class OpenDropDiscovery(
    context: Context,
    private val onDevicesChanged: (List<DiscoveredDevice>) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val manager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val devices = linkedMapOf<String, DiscoveredDevice>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun registerReceiver() {
        if (registrationListener != null) return

        val model = Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "-")
        val service = NsdServiceInfo().apply {
            serviceName = "OpenDrop-$model"
            serviceType = OPENDROP_SERVICE_TYPE
            port = PORT
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                onStatus("Receiver advertised for nearby discovery.")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                registrationListener = null
                onStatus("Nearby advertising failed ($errorCode). Manual IP still works.")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                registrationListener = null
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                onStatus("Could not stop nearby advertising ($errorCode).")
            }
        }

        try {
            registrationListener = listener
            manager.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            registrationListener = null
            onStatus("Nearby advertising unavailable: ${e.message ?: "unknown error"}")
        }
    }

    fun unregisterReceiver() {
        val listener = registrationListener ?: return
        registrationListener = null
        try {
            manager.unregisterService(listener)
        } catch (_: Exception) {
        }
    }

    fun startDiscovery() {
        stopDiscovery()
        synchronized(devices) { devices.clear() }
        onDevicesChanged(emptyList())
        onStatus("Searching for nearby OpenDrop devices…")

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                onStatus("Searching for OpenDrop devices on this Wi-Fi…")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.trimEnd('.').equals(
                        OPENDROP_SERVICE_TYPE.trimEnd('.'), ignoreCase = true
                    )
                ) return

                try {
                    val resolveListener = object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            onStatus("Could not resolve ${info.serviceName} ($errorCode). Keep the receiver open and scan again.")
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            if (info.port != PORT) return

                            val device = DiscoveredDevice(
                                info.serviceName ?: "OpenDrop device",
                                host,
                                info.port
                            )
                            synchronized(devices) {
                                devices["${device.name}@${device.host}"] = device
                                onDevicesChanged(devices.values.toList())
                            }
                            onStatus("Found ${devices.size} device(s). Tap its IP to connect.")
                        }
                    }

                    // Keep compatibility with the compile SDK used by this project.
                    // resolveServiceInfo() is not available in all SDK stubs; use
                    // the supported ResolveListener API for service resolution.
                    @Suppress("DEPRECATION")
                    manager.resolveService(service, resolveListener)
                } catch (e: Exception) {
                    onStatus("Discovery lookup failed: ${e.message ?: "unknown error"}")
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                synchronized(devices) {
                    devices.entries.removeAll { it.value.name == service.serviceName }
                    onDevicesChanged(devices.values.toList())
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryListener = null
                onStatus("Discovery failed ($errorCode). Try manual IP.")
                try { manager.stopServiceDiscovery(this) } catch (_: Exception) {}
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                try { manager.stopServiceDiscovery(this) } catch (_: Exception) {}
            }
        }

        try {
            discoveryListener = listener
            manager.discoverServices(
                OPENDROP_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener
            )
        } catch (e: Exception) {
            discoveryListener = null
            onStatus("Discovery unavailable: ${e.message ?: "unknown error"}")
        }
    }

    fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        try {
            manager.stopServiceDiscovery(listener)
        } catch (_: Exception) {
        }
    }
}


private val Background = Color(0xFF0B1020)
private val CardColor = Color(0xFF171F33)
private val Accent = Color(0xFF7C8CFF)
private val SecondaryText = Color(0xFFABB5CC)

private data class SelectedFile(
    val uri: Uri,
    val name: String,
    val size: Long?,
    val mimeType: String
)

private data class IncomingRequest(
    val name: String,
    val size: Long,
    val decide: (Boolean) -> Unit
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    background = Background,
                    surface = CardColor,
                    onPrimary = Color.White,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                OpenDropApp(this)
            }
        }
    }
}

private fun readSelectedFile(context: Context, uri: Uri): SelectedFile {
    var name = uri.lastPathSegment ?: "selected-file"
    var size: Long? = null

    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null, null, null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameColumn =
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeColumn =
                cursor.getColumnIndex(OpenableColumns.SIZE)

            if (nameColumn >= 0) {
                name = cursor.getString(nameColumn) ?: name
            }
            if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                size = cursor.getLong(sizeColumn)
            }
        }
    }

    return SelectedFile(
        uri = uri,
        name = name,
        size = size,
        mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
    )
}

private fun formatSize(size: Long): String {
    return when {
        size < 1024L -> "$size B"
        size < 1024L * 1024L ->
            String.format(java.util.Locale.US, "%.1f KB", size / 1024.0)
        size < 1024L * 1024L * 1024L ->
            String.format(
                java.util.Locale.US, "%.2f MB",
                size / (1024.0 * 1024.0)
            )
        else ->
            String.format(
                java.util.Locale.US, "%.2f GB",
                size / (1024.0 * 1024.0 * 1024.0)
            )
    }
}

private fun safeFileName(input: String): String {
    val base = input.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
    return base.take(180).ifBlank { "received-file" }
}

private fun copyExactly(
    input: InputStream,
    output: OutputStream,
    byteCount: Long,
    onBytesCopied: ((Long) -> Unit)? = null
) {
    val buffer = ByteArray(64 * 1024)
    var remaining = byteCount
    var copied = 0L

    while (remaining > 0) {
        val read = input.read(
            buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()
        )
        if (read < 0) throw java.io.EOFException("File ended before transfer completed")
        output.write(buffer, 0, read)
        remaining -= read
        copied += read
        onBytesCopied?.invoke(copied)
    }
}

private fun saveIncomingFile(
    context: Context,
    requestedName: String,
    mimeType: String,
    input: InputStream,
    size: Long
): String {
    val name = safeFileName(requestedName)

    if (android.os.Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/OpenDrop"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: throw IllegalStateException("Could not create the destination file")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                copyExactly(input, output, size)
                output.flush()
            } ?: throw IllegalStateException("Could not open the destination file")

            val completed = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, completed, null, null)
            return "Downloads/OpenDrop/$name"
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    val directory = File(
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir,
        "OpenDrop"
    )
    if (!directory.exists() && !directory.mkdirs()) {
        throw IllegalStateException("Could not create the destination folder")
    }

    val destination = File(directory, name)
    destination.outputStream().buffered().use { output ->
        copyExactly(input, output, size)
    }
    return destination.absolutePath
}

private fun localIpv4Addresses(): String {
    val addresses = mutableListOf<String>()
    val interfaces = NetworkInterface.getNetworkInterfaces()
        ?: return "IP unavailable"

    while (interfaces.hasMoreElements()) {
        val network = interfaces.nextElement()
        try {
            if (!network.isUp || network.isLoopback) continue
        } catch (_: Exception) {
            continue
        }

        val entries = network.inetAddresses
        while (entries.hasMoreElements()) {
            val address = entries.nextElement()
            if (address is Inet4Address &&
                !address.isLoopbackAddress &&
                !address.isLinkLocalAddress
            ) {
                addresses.add(address.hostAddress ?: "")
            }
        }
    }

    return addresses.filter { it.isNotBlank() }
        .distinct().joinToString(", ").ifBlank { "IP unavailable" }
}

private fun handleIncoming(
    activity: Activity,
    socket: Socket,
    onPending: (IncomingRequest?) -> Unit,
    onStatus: (String) -> Unit,
    onHistory: (TransferHistoryEntry) -> Unit
) {
    var historyName = "Unknown file"
    var historySize: Long? = null
    var historyRecorded = false
    try {
        socket.use { connection ->
            connection.soTimeout = 120_000

            val input = DataInputStream(
                BufferedInputStream(connection.getInputStream())
            )
            val output = DataOutputStream(
                BufferedOutputStream(connection.getOutputStream())
            )

            if (input.readUTF() != PROTOCOL) {
                output.writeBoolean(false)
                output.writeUTF("Unsupported OpenDrop protocol")
                output.flush()
                return
            }

            val name = safeFileName(input.readUTF())
            val size = input.readLong()
            val mimeType = input.readUTF()
            historyName = name
            historySize = size

            if (size < 0L || size > 2L * 1024L * 1024L * 1024L) {
                output.writeBoolean(false)
                output.writeUTF("File size is invalid or exceeds 2 GB")
                output.flush()
                return
            }

            val latch = CountDownLatch(1)
            val decision = AtomicBoolean(false)
            val request = IncomingRequest(name, size) { accept ->
                decision.set(accept)
                latch.countDown()
            }

            activity.runOnUiThread {
                onPending(request)
                onStatus("Incoming file: $name (${formatSize(size)})")
            }

            val answered = latch.await(120, TimeUnit.SECONDS)
            if (!answered) {
                output.writeBoolean(false)
                output.writeUTF("Approval timed out")
                output.flush()
                return
            }

            val accepted = decision.get()
            output.writeBoolean(accepted)
            if (!accepted) {
                output.writeUTF("Transfer declined by receiver")
                output.flush()
                historyRecorded = true
                activity.runOnUiThread {
                    onHistory(
                        TransferHistoryEntry(
                            fileName = name,
                            direction = "Received",
                            sizeBytes = size,
                            outcome = TransferOutcome.REJECTED
                        )
                    )
                    onStatus("Declined: $name")
                }
                return
            }
            output.flush()

            try {
                val savedPath = saveIncomingFile(
                    activity, name, mimeType, input, size
                )
                output.writeBoolean(true)
                output.writeUTF(savedPath)
                output.flush()

                historyRecorded = true
                activity.runOnUiThread {
                    onHistory(
                        TransferHistoryEntry(
                            fileName = name,
                            direction = "Received",
                            sizeBytes = size,
                            outcome = TransferOutcome.SUCCESS
                        )
                    )
                    onStatus("Received $name • Saved to $savedPath")
                }
            } catch (e: Exception) {
                try {
                    output.writeBoolean(false)
                    output.writeUTF(
                        "Could not save file: ${e.message ?: "unknown error"}"
                    )
                    output.flush()
                } catch (_: Exception) {
                    // The connection may already be closed.
                }
                historyRecorded = true
                activity.runOnUiThread {
                    onHistory(
                        TransferHistoryEntry(
                            fileName = name,
                            direction = "Received",
                            sizeBytes = size,
                            outcome = TransferOutcome.FAILED
                        )
                    )
                    onStatus("Receive failed: ${e.message ?: "unknown error"}")
                }
            }
        }
    } catch (e: Exception) {
        if (!historyRecorded && historySize != null) {
            historyRecorded = true
            activity.runOnUiThread {
                onHistory(
                    TransferHistoryEntry(
                        fileName = historyName,
                        direction = "Received",
                        sizeBytes = historySize,
                        outcome = TransferOutcome.FAILED
                    )
                )
                onStatus("Connection failed: ${e.message ?: "connection closed"}")
            }
        } else {
            activity.runOnUiThread {
                onStatus("Connection failed: ${e.message ?: "connection closed"}")
            }
        }
    } finally {
        activity.runOnUiThread {
            onPending(null)
        }
    }
}

private fun startReceiver(
    activity: MainActivity,
    onStarted: (ServerSocket, String) -> Unit,
    onStopped: () -> Unit,
    onPending: (IncomingRequest?) -> Unit,
    onStatus: (String) -> Unit,
    onHistory: (TransferHistoryEntry) -> Unit
) {
    Thread {
        var server: ServerSocket? = null
        try {
            server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress("0.0.0.0", PORT))
            val activeServer = server

            activity.runOnUiThread {
                onStarted(activeServer, localIpv4Addresses())
                onStatus("Receiver ready on port $PORT. Keep OpenDrop open.")
            }

            while (!activeServer.isClosed) {
                val client = activeServer.accept()
                handleIncoming(activity, client, onPending, onStatus, onHistory)
            }
        } catch (e: Exception) {
            if (server?.isClosed != true) {
                activity.runOnUiThread {
                    onStatus("Receiver could not start: ${e.message ?: "unknown error"}")
                    onStopped()
                }
            }
        }
    }.apply {
        name = "OpenDropReceiver"
        isDaemon = true
        start()
    }
}

private fun sendFiles(
    activity: Activity,
    host: String,
    files: List<SelectedFile>,
    onStatus: (String) -> Unit,
    onProgress: (String, Int, Int, Long, Long, Long, Long, Double, Long) -> Unit,
    onHistory: (TransferHistoryEntry) -> Unit,
    onFinished: () -> Unit
) {
    Thread {
        var currentFile: SelectedFile? = null
        try {
            if (host.isBlank() || host.any { it.isWhitespace() }) {
                throw IllegalArgumentException("Enter the receiver's IPv4 address")
            }

            var completed = 0
            val totalBytes = files.sumOf { it.size ?: 0L }
            var completedBytes = 0L
            val transferStart = System.nanoTime()

            for ((fileIndex, file) in files.withIndex()) {
                currentFile = file
                val size = file.size
                    ?: throw IllegalStateException(
                        "Size unavailable for ${file.name}. Choose another copy of this file."
                    )

                if (size < 0L || size > 2L * 1024L * 1024L * 1024L) {
                    throw IllegalStateException(
                        "${file.name} is larger than the 2 GB MVP limit"
                    )
                }

                activity.runOnUiThread {
                    onStatus("Connecting to $host:$PORT • ${file.name}")
                }

                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, PORT), 8_000)
                    socket.soTimeout = 120_000

                    val output = DataOutputStream(
                        BufferedOutputStream(socket.getOutputStream())
                    )
                    val input = DataInputStream(
                        BufferedInputStream(socket.getInputStream())
                    )

                    output.writeUTF(PROTOCOL)
                    output.writeUTF(safeFileName(file.name))
                    output.writeLong(size)
                    output.writeUTF(file.mimeType)
                    output.flush()

                    val accepted = input.readBoolean()

                    if (!accepted) {
                        val reason = input.readUTF()
                        activity.runOnUiThread {
                            onHistory(
                                TransferHistoryEntry(
                                    fileName = file.name,
                                    direction = "Sent",
                                    sizeBytes = size,
                                    outcome = TransferOutcome.REJECTED
                                )
                            )
                            onStatus("Not sent: ${file.name} • $reason")
                        }
                        currentFile = null
                    } else {
                        val source = activity.contentResolver.openInputStream(file.uri)
                            ?: throw IllegalStateException("Cannot open ${file.name}")

                        source.use { stream ->
                            copyExactly(stream, output, size) { fileBytes ->
                                val elapsedSeconds =
                                    (System.nanoTime() - transferStart) / 1_000_000_000.0
                                val sentBytes = completedBytes + fileBytes
                                val speed = if (elapsedSeconds > 0.0) {
                                    sentBytes / elapsedSeconds
                                } else {
                                    0.0
                                }
                                val remainingBytes =
                                    (totalBytes - sentBytes).coerceAtLeast(0L)
                                val etaSeconds = if (speed > 0.0) {
                                    (remainingBytes / speed).toLong()
                                } else {
                                    -1L
                                }
                                val percent = if (totalBytes > 0L) {
                                    ((sentBytes.toDouble() / totalBytes) * 100)
                                        .toInt().coerceIn(0, 100)
                                } else {
                                    100
                                }

                                activity.runOnUiThread {
                                    onProgress(
                                        file.name,
                                        fileIndex + 1,
                                        files.size,
                                        fileBytes,
                                        size,
                                        sentBytes,
                                        totalBytes,
                                        speed,
                                        etaSeconds
                                    )
                                    onStatus(
                                        "Sending ${fileIndex + 1}/${files.size}: " +
                                            "${file.name} • $percent%"
                                    )
                                }
                            }
                        }
                        completedBytes += size
                        output.flush()

                        val success = input.readBoolean()
                        val result = input.readUTF()

                        if (!success) {
                            throw IllegalStateException(result)
                        }

                        completed++
                        activity.runOnUiThread {
                            onHistory(
                                TransferHistoryEntry(
                                    fileName = file.name,
                                    direction = "Sent",
                                    sizeBytes = size,
                                    outcome = TransferOutcome.SUCCESS
                                )
                            )
                            onStatus(
                                "Sent $completed/${files.size}: ${file.name}. " +
                                    "Receiver saved it to $result"
                            )
                        }
                        currentFile = null
                    }
                }
            }

            activity.runOnUiThread {
                onStatus("Transfer queue finished: $completed/${files.size} file(s) sent.")
            }
        } catch (e: Exception) {
            val failedFile = currentFile
            activity.runOnUiThread {
                if (failedFile != null) {
                    onHistory(
                        TransferHistoryEntry(
                            fileName = failedFile.name,
                            direction = "Sent",
                            sizeBytes = failedFile.size,
                            outcome = TransferOutcome.FAILED
                        )
                    )
                }
                onStatus("Send failed: ${e.message ?: "unknown error"}")
            }
        } finally {
            activity.runOnUiThread { onFinished() }
        }
    }.apply {
        name = "OpenDropSender"
        isDaemon = true
        start()
    }
}

private data class TransferProgress(
    val fileName: String,
    val fileIndex: Int,
    val fileCount: Int,
    val fileBytesSent: Long,
    val fileBytesTotal: Long,
    val totalBytesSent: Long,
    val totalBytesTotal: Long,
    val bytesPerSecond: Double,
    val etaSeconds: Long
)

@Composable
private fun OpenDropApp(activity: MainActivity) {
    var selectedFiles by remember { mutableStateOf<List<SelectedFile>>(emptyList()) }
    var message by remember { mutableStateOf("Ready to share files on your local network.") }
    var receiverHost by remember { mutableStateOf("") }
    var receiverIp by remember { mutableStateOf("") }
    var receiverServer by remember { mutableStateOf<ServerSocket?>(null) }
    var receiving by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var transferProgress by remember { mutableStateOf<TransferProgress?>(null) }
    var pending by remember { mutableStateOf<IncomingRequest?>(null) }
    var discoveredDevices by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }
    var discovering by remember { mutableStateOf(false) }
    var historyEntries by remember {
        mutableStateOf(TransferHistoryStore.load(activity))
    }
    var showClearHistoryConfirmation by remember { mutableStateOf(false) }
    var showReceiverQr by remember { mutableStateOf(false) }

    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        if (raw.isNullOrBlank()) {
            message = "QR scan cancelled."
        } else {
            val parts = raw.split("|")
            if (parts.size >= 3 && parts[0] == "OPENDROP1") {
                val host = parts[1].trim()
                val port = parts[2].toIntOrNull()
                if (host.isNotBlank() && port == PORT) {
                    receiverHost = host
                    message = "QR connected to $host:$port. Choose files and tap Send selected files."
                } else {
                    message = "Invalid OpenDrop QR code. Check the receiver is using the same app version."
                }
            } else {
                message = "That QR code is not an OpenDrop receiver QR."
            }
        }
    }

    val recordHistory: (TransferHistoryEntry) -> Unit = { entry ->
        TransferHistoryStore.add(activity, entry)
        historyEntries = TransferHistoryStore.load(activity)
    }

    val discovery = remember(activity) {
        OpenDropDiscovery(
            activity,
            onDevicesChanged = { devices ->
                activity.runOnUiThread { discoveredDevices = devices }
            },
            onStatus = { status ->
                activity.runOnUiThread { message = status }
            }
        )
    }

    DisposableEffect(discovery) {
        onDispose {
            discovery.stopDiscovery()
            discovery.unregisterReceiver()
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val files = mutableListOf<SelectedFile>()
        for (uri in uris) {
            try {
                activity.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Temporary picker access may still be sufficient.
            }

            try {
                files.add(readSelectedFile(activity, uri))
            } catch (_: Exception) {
                // Skip files that cannot be described.
            }
        }
        selectedFiles = files
        message = if (files.isEmpty()) "No readable files selected."
        else "${files.size} file(s) selected."
    }

    @Composable
    fun SectionLabel(label: String) {
        Text(
            label,
            color = SecondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.3.sp
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Brand header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "OpenDrop",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        letterSpacing = (-0.8).sp
                    )
                    Text(
                        "SHARE WITHOUT THE CLOUD",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        color = SecondaryText
                    )
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (receiving) Color(0xFF173B37) else CardColor
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(7.dp),
                            shape = RoundedCornerShape(50),
                            color = if (receiving) Color(0xFF55D6A5) else SecondaryText
                        ) {}
                        Text(
                            if (receiving) "RECEIVING" else "STANDBY",
                            color = if (receiving) Color(0xFF8DE7C4) else SecondaryText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Main send panel
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                color = CardColor
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SectionLabel("QUICK SHARE")

                    Text(
                        "Your files.\nYour devices.",
                        color = Color.White,
                        fontSize = 28.sp,
                        lineHeight = 33.sp,
                        fontWeight = FontWeight.ExtraBold
                    )

                    Text(
                        if (selectedFiles.isEmpty())
                            "Choose photos, videos or documents to get started."
                        else
                            "${selectedFiles.size} file(s) ready to send",
                        color = SecondaryText,
                        fontSize = 14.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { picker.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(15.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) {
                            Text(
                                if (selectedFiles.isEmpty()) "＋  Choose files"
                                else "＋  Add files",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                selectedFiles = emptyList()
                                message = "Selection cleared."
                            },
                            enabled = selectedFiles.isNotEmpty(),
                            shape = RoundedCornerShape(15.dp)
                        ) {
                            Text("Clear")
                        }
                    }

                    if (selectedFiles.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Background,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                selectedFiles.take(4).forEach { file ->
                                    Text(
                                        "•  ${file.name}  ·  ${
                                            file.size?.let { formatSize(it) } ?: "Unknown size"
                                        }",
                                        color = Color.White,
                                        fontSize = 12.sp
                                    )
                                }
                                if (selectedFiles.size > 4) {
                                    Text(
                                        "+ ${selectedFiles.size - 4} more file(s)",
                                        color = SecondaryText,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Nearby devices
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = CardColor
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                "Nearby devices",
                                color = Color.White,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                when {
                                    discoveredDevices.isNotEmpty() ->
                                        "${discoveredDevices.size} device(s) found"
                                    discovering -> "Searching your local network…"
                                    else -> "Find another OpenDrop device"
                                },
                                color = SecondaryText,
                                fontSize = 12.sp
                            )
                        }

                        Text(
                            "⌁",
                            color = Accent,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = {
                            discovering = true
                            discovery.startDiscovery()
                            message = "Searching for nearby OpenDrop devices…"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) {
                        Text(
                            if (discovering) "Search again" else "Scan for devices",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            val options = ScanOptions().apply {
                                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                setPrompt("Scan the QR code shown on the receiving phone")
                                setBeepEnabled(false)
                                setOrientationLocked(false)
                            }
                            qrScanner.launch(options)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Scan receiver QR code")
                    }

                    if (discovering) {
                        OutlinedButton(
                            onClick = {
                                discovery.stopDiscovery()
                                discovering = false
                                message = "Search stopped. You can still enter an IP address."
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Stop scanning")
                        }
                    }

                    if (discoveredDevices.isNotEmpty()) {
                        discoveredDevices.forEach { device ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = Background,
                                shape = RoundedCornerShape(15.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Surface(
                                        modifier = Modifier.size(42.dp),
                                        color = Color(0xFF293456),
                                        shape = RoundedCornerShape(13.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                "↔",
                                                color = Color.White,
                                                fontSize = 22.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            device.name,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            "${device.host}:${device.port}",
                                            color = SecondaryText,
                                            fontSize = 11.sp
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            receiverHost = device.host
                                            if (selectedFiles.isEmpty()) {
                                                message = "Selected ${device.name} (${device.host}). Choose files first, then tap Send."
                                            } else if (sending) {
                                                message = "A transfer is already in progress."
                                            } else {
                                                sending = true
                                                transferProgress = null
                                                message = "Connecting to ${device.host}…"
                                                sendFiles(
                                                    activity = activity,
                                                    host = device.host,
                                                    files = selectedFiles,
                                                    onStatus = { status ->
                                                        activity.runOnUiThread { message = status }
                                                    },
                                                    onProgress = { fileName, fileIndex, fileCount, fileBytesSent, fileBytesTotal, totalBytesSent, totalBytesTotal, bytesPerSecond, etaSeconds ->
                                                        activity.runOnUiThread {
                                                            transferProgress = TransferProgress(
                                                                fileName, fileIndex, fileCount,
                                                                fileBytesSent, fileBytesTotal,
                                                                totalBytesSent, totalBytesTotal,
                                                                bytesPerSecond, etaSeconds
                                                            )
                                                        }
                                                    },
                                                    onHistory = { entry ->
                                                        activity.runOnUiThread { recordHistory(entry) }
                                                    },
                                                    onFinished = {
                                                        activity.runOnUiThread { sending = false }
                                                    }
                                                )
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(
                                            horizontal = 12.dp,
                                            vertical = 6.dp
                                        ),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Accent
                                        )
                                    ) {
                                        Text(if (selectedFiles.isEmpty()) "Connect" else "Send", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    } else if (discovering) {
                        Text(
                            "Keep both phones on the same Wi-Fi. If no device appears, you can use its IP address below.",
                            color = SecondaryText,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Receive panel
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = CardColor
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SectionLabel("RECEIVE")

                    Text(
                        if (receiving) "This phone is ready" else "Receive files on this phone",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        if (receiving) "Listening on port $PORT"
                        else "Start receiving so another phone can send files here.",
                        color = SecondaryText,
                        fontSize = 13.sp
                    )

                    if (receiverIp.isNotBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Background,
                            shape = RoundedCornerShape(13.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    "THIS PHONE'S IP ADDRESS",
                                    color = SecondaryText,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    receiverIp,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    if (receiving) {
                        OutlinedButton(
                            onClick = { showReceiverQr = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Show QR code for sender")
                        }
                    }

                    Button(
                        onClick = {
                            if (!receiving) {
                                message = "Starting receiver…"
                                startReceiver(
                                    activity,
                                    onStarted = { server, ips ->
                                        receiverServer = server
                                        receiverIp = ips
                                        receiving = true
                                        discovery.registerReceiver()
                                    },
                                    onStopped = {
                                        receiverServer = null
                                        receiving = false
                                    },
                                    onPending = { pending = it },
                                    onStatus = { message = it },
                                    onHistory = { recordHistory(it) }
                                )
                            } else {
                                try {
                                    receiverServer?.close()
                                } catch (_: Exception) {
                                }
                                receiverServer = null
                                receiving = false
                                pending = null
                                discovery.unregisterReceiver()
                                message = "Receiver stopped."
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (receiving) Color(0xFFB94A58) else Accent
                        )
                    ) {
                        Text(
                            if (receiving) "Stop receiving" else "Start receiving",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (pending != null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Background,
                            shape = RoundedCornerShape(15.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "Incoming file request",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "${pending!!.name}\n${formatSize(pending!!.size)}",
                                    color = SecondaryText,
                                    fontSize = 13.sp
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            val request = pending
                                            pending = null
                                            request?.decide(true)
                                            message = "Accepted incoming file."
                                        }
                                    ) { Text("Accept") }

                                    OutlinedButton(
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            val request = pending
                                            pending = null
                                            request?.decide(false)
                                            message = "Incoming file declined."
                                        }
                                    ) { Text("Decline") }
                                }
                            }
                        }
                    }
                }
            }

            // Manual send panel
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = CardColor
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SectionLabel("SEND")

                    Text(
                        "Send to an IP address",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = receiverHost,
                        onValueChange = { receiverHost = it },
                        label = { Text("Receiver IPv4 address") },
                        placeholder = { Text("Example: 192.168.1.25") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )

                    Text(
                        "TCP PORT  •  $PORT",
                        color = SecondaryText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Button(
                        onClick = {
                            if (selectedFiles.isEmpty()) {
                                message = "Choose at least one file first."
                            } else if (receiverHost.isBlank()) {
                                message = "Select a nearby device or enter its IP address."
                            } else {
                                sending = true
                                sendFiles(
                                    activity,
                                    receiverHost.trim(),
                                    selectedFiles,
                                    onStatus = { message = it },
                                    onHistory = { recordHistory(it) },
                                    onProgress = { name, index, count, fileSent, fileTotal,
                                                   totalSent, totalTotal, speed, eta ->
                                        transferProgress = TransferProgress(
                                            fileName = name,
                                            fileIndex = index,
                                            fileCount = count,
                                            fileBytesSent = fileSent,
                                            fileBytesTotal = fileTotal,
                                            totalBytesSent = totalSent,
                                            totalBytesTotal = totalTotal,
                                            bytesPerSecond = speed,
                                            etaSeconds = eta
                                        )
                                    },
                                    onFinished = { sending = false }
                                )
                            }
                        },
                        enabled = !sending,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) {
                        Text(
                            if (sending) "Sending…" else "Send selected files  →",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Activity panel
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = CardColor
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SectionLabel("ACTIVITY")

                    val progress = transferProgress
                    if (sending && progress != null) {
                        val fileFraction = if (progress.fileBytesTotal > 0L) {
                            (progress.fileBytesSent.toFloat() /
                                progress.fileBytesTotal.toFloat()).coerceIn(0f, 1f)
                        } else {
                            1f
                        }
                        val queueFraction = if (progress.totalBytesTotal > 0L) {
                            (progress.totalBytesSent.toFloat() /
                                progress.totalBytesTotal.toFloat()).coerceIn(0f, 1f)
                        } else {
                            1f
                        }
                        val speedText = if (progress.bytesPerSecond > 0.0) {
                            String.format(
                                java.util.Locale.getDefault(),
                                "%.2f MiB/s",
                                progress.bytesPerSecond / (1024.0 * 1024.0)
                            )
                        } else {
                            "Calculating…"
                        }
                        val etaText = if (progress.etaSeconds >= 0L) {
                            val minutes = progress.etaSeconds / 60
                            val seconds = progress.etaSeconds % 60
                            if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
                        } else {
                            "Calculating…"
                        }

                        Text(
                            "FILE ${progress.fileIndex} OF ${progress.fileCount}",
                            color = Accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            progress.fileName,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        LinearProgressIndicator(
                            progress = { fileFraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            String.format(
                                java.util.Locale.getDefault(),
                                "Current file: %.1f%%  •  %,d / %,d bytes",
                                fileFraction * 100f,
                                progress.fileBytesSent,
                                progress.fileBytesTotal
                            ),
                            color = SecondaryText,
                            fontSize = 11.sp
                        )

                        Spacer(Modifier.height(4.dp))
                        Text(
                            "OVERALL QUEUE",
                            color = SecondaryText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        LinearProgressIndicator(
                            progress = { queueFraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            String.format(
                                java.util.Locale.getDefault(),
                                "%.1f%%  •  %,d / %,d bytes",
                                queueFraction * 100f,
                                progress.totalBytesSent,
                                progress.totalBytesTotal
                            ),
                            color = Color.White,
                            fontSize = 12.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(speedText, color = Accent, fontSize = 12.sp)
                            Text(
                                "ETA $etaText",
                                color = SecondaryText,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Text(
                        message,
                        color = Color.White,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            }

            TransferHistoryScreen(
                entries = historyEntries,
                onClearHistory = { showClearHistoryConfirmation = true },
                modifier = Modifier.heightIn(min = 180.dp, max = 520.dp)
            )

            if (showClearHistoryConfirmation) {
                AlertDialog(
                    onDismissRequest = { showClearHistoryConfirmation = false },
                    title = { Text("Clear transfer history?") },
                    text = { Text("This removes saved history entries from this device.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                TransferHistoryStore.clear(activity)
                                historyEntries = emptyList()
                                showClearHistoryConfirmation = false
                            }
                        ) { Text("Clear history") }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showClearHistoryConfirmation = false }
                        ) { Text("Cancel") }
                    }
                )
            }

            Text(
                "Same trusted Wi-Fi is recommended. Keep OpenDrop open while receiving. Transfers are not encrypted yet.",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                color = SecondaryText,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))
        }
    }

    if (showReceiverQr) {
        val firstIp = receiverIp.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.matches(Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")) }
        val qrBitmap = remember(firstIp, receiving) {
            if (receiving && !firstIp.isNullOrBlank()) {
                runCatching {
                    val matrix = MultiFormatWriter().encode(
                        "OPENDROP1|$firstIp|$PORT",
                        BarcodeFormat.QR_CODE,
                        640,
                        640
                    )
                    Bitmap.createBitmap(640, 640, Bitmap.Config.RGB_565).also { bitmap ->
                        for (x in 0 until 640) for (y in 0 until 640) {
                            bitmap.setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
                        }
                    }
                }.getOrNull()
            } else null
        }
        AlertDialog(
            onDismissRequest = { showReceiverQr = false },
            title = { Text("Scan to connect") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "OpenDrop receiver QR code",
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                        )
                        Text("$firstIp:$PORT", color = SecondaryText)
                    } else {
                        Text("No usable Wi-Fi IPv4 address is available. Connect to Wi-Fi and restart Receive.")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showReceiverQr = false }) { Text("Close") }
            }
        )
    }

}
