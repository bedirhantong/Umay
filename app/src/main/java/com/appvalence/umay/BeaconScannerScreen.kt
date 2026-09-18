package com.appvalence.umay

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.positioning.BeaconMapBuilder
import com.appvalence.umay.positioning.BeaconSurveyResult
import com.appvalence.umay.positioning.BeaconSurveySolver
import com.appvalence.umay.positioning.BleBeaconScanner
import com.appvalence.umay.positioning.BleScanState
import com.appvalence.umay.positioning.PdrIntegrator
import com.appvalence.umay.positioning.StepDetector
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Process-lifetime log of every iBeacon ever seen this run, and of every
 * reference-point capture taken for the on-site survey — both independent of
 * any composable's lifecycle. A [androidx.compose.runtime.remember]-scoped
 * state would be wiped every time the "Beacon Tarayıcı" tab is left
 * (MainActivity's tab switch disposes that branch of the composition
 * entirely) — while walking a building that's exactly the data loss we
 * can't afford. This is throwaway survey tooling; delete this object and its
 * call sites once the on-site beacon survey is done.
 */

private object BeaconSurveyLog {
    val discovered = mutableStateMapOf<String, BleBeaconReading>()
    val trajectory = mutableStateListOf<Position>()
    val rawReadings = mutableStateListOf<BleBeaconReading>()
    var isRecording by mutableStateOf(false)
    var results by mutableStateOf<List<BeaconSurveyResult>>(emptyList())
    var hasSolved by mutableStateOf(false)

    // Manually marked by the operator when taking stairs/an elevator (no barometer-based
    // floor detection yet). PDR has no vertical axis, so a floor change starts a fresh
    // (x, y) origin instead of letting stairwell walking distort the current floor's shape.
    var currentFloor by mutableStateOf(0)
}

private val REQUIRED_BLE_PERMISSIONS = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
    }
}.toTypedArray()

/**
 * On-site beacon discovery screen (see [BleBeaconScanner]): lists every BLE
 * advertiser currently in range, closest (strongest RSSI) first, and lets the
 * operator record a PDR walking survey that's solved into a beacon map (see
 * [BeaconSurveySolver]).
 */
@Composable
fun BeaconScannerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var hasPermissions by remember {
        mutableStateOf(REQUIRED_BLE_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        hasPermissions = grants.values.all { it }
    }
    LaunchedEffect(Unit) {
        if (!hasPermissions) permissionLauncher.launch(REQUIRED_BLE_PERMISSIONS)
    }

    if (!hasPermissions) {
        Text(
            text = "Beacon taraması için konum ve Bluetooth izni gerekiyor.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = modifier.padding(16.dp),
        )
        return
    }

    val scanner = remember { BleBeaconScanner(context) }
    DisposableEffect(Unit) {
        scanner.start()
        onDispose { scanner.stop() }
    }

    val beacons by scanner.beaconUpdates().collectAsState(initial = emptyList())
    val scanState by scanner.scanState().collectAsState(initial = BleScanState.Idle)

    val discoveredBeacons = BeaconSurveyLog.discovered
    LaunchedEffect(scanner) {
        scanner.beaconUpdates().collect { list ->
            list.filter { it.uuid != null }.forEach { discoveredBeacons[it.beaconId] = it }
        }
    }
    val clipboardManager = LocalClipboardManager.current

    val stepDetector = remember { StepDetector(context) }
    val trajectory = BeaconSurveyLog.trajectory
    val rawReadings = BeaconSurveyLog.rawReadings
    LaunchedEffect(BeaconSurveyLog.isRecording) {
        if (!BeaconSurveyLog.isRecording) return@LaunchedEffect
        coroutineScope {
            launch {
                var trackedFloor = BeaconSurveyLog.currentFloor
                var pdr = PdrIntegrator(floor = trackedFloor)
                stepDetector.stepEvents().collect { step ->
                    if (BeaconSurveyLog.currentFloor != trackedFloor) {
                        trackedFloor = BeaconSurveyLog.currentFloor
                        pdr = PdrIntegrator(floor = trackedFloor) // fresh (x, y) origin for the new floor
                    }
                    trajectory.add(pdr.integrate(step))
                }
            }
            launch { scanner.rawReadingUpdates().collect { reading -> rawReadings.add(reading) } }
        }
    }

    var showOnlyIBeacon by remember { mutableStateOf(true) }
    val visibleBeacons = if (showOnlyIBeacon) beacons.filter { it.uuid != null } else beacons

    val uuidGroups = remember(beacons) {
        beacons.filter { it.uuid != null }
            .groupBy { it.uuid!! }
            .map { (uuid, readings) ->
                UuidGroup(uuid = uuid, distinctMinors = readings.mapNotNull { it.minor }.toSet().size, sightingCount = readings.size)
            }
            .sortedByDescending { it.distinctMinors }
    }

    LazyColumn(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (scanState is BleScanState.Failed) {
            item {
                Text(
                    text = "Tarama başlatılamadı: ${(scanState as BleScanState.Failed).reason}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        item {
            val solve = {
                BeaconSurveyLog.results = BeaconSurveySolver.solve(trajectory, rawReadings)
                BeaconSurveyLog.hasSolved = true
            }
            AutoSurveySection(
                trajectoryPointCount = trajectory.size,
                rawReadingCount = rawReadings.size,
                currentFloor = BeaconSurveyLog.currentFloor,
                results = BeaconSurveyLog.results,
                hasSolved = BeaconSurveyLog.hasSolved,
                onToggleRecording = {
                    val wasRecording = BeaconSurveyLog.isRecording
                    BeaconSurveyLog.isRecording = !wasRecording
                    // Stopping a recording is exactly when there's something new to solve;
                    // requiring a separate manual "Hesapla" tap right after is a step nobody
                    // needs to remember.
                    if (wasRecording) solve()
                },
                onFloorChange = { delta -> BeaconSurveyLog.currentFloor += delta },
                onSolve = solve,
                onCopyResults = {
                    val text = BeaconSurveyLog.results.joinToString("\n") { it.toSurveyLine() }
                    clipboardManager.setText(AnnotatedString(text))
                },
                onClear = {
                    trajectory.clear()
                    rawReadings.clear()
                    BeaconSurveyLog.results = emptyList()
                    BeaconSurveyLog.hasSolved = false
                    BeaconSurveyLog.currentFloor = 0
                },
            )
        }

        item {
            SurveyMapCanvas(
                trajectory = trajectory.filter { it.floor == BeaconSurveyLog.currentFloor },
                results = BeaconSurveyLog.results.filter { it.floor == BeaconSurveyLog.currentFloor },
            )
        }

        item {
            var saveMessage by remember { mutableStateOf<String?>(null) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = BeaconSurveyLog.results.isNotEmpty(),
                    onClick = {
                        val map = BeaconMapBuilder.build(buildingId = "umay-building", results = BeaconSurveyLog.results)
                        BuildingMapStorage(context).save(map)
                        val anchorCount = map.floors.sumOf { it.nodes.size }
                        val edgeCount = map.floors.sumOf { it.edges.size }
                        saveMessage = "Kaydedildi: $anchorCount beacon, $edgeCount bağlantı, ${map.floors.size} kat."
                    },
                ) {
                    Text("Haritayı kaydet")
                }
                saveMessage?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Text(
                text = "Bu, çözülen beacon konumlarını (yakındaki beacon'ları birbirine bağlayan basit bir ağ olarak) \"Navigasyon\" ekranının kullanacağı harita dosyasına kaydeder.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        item {
            DiscoveredHeader(
                discoveredCount = discoveredBeacons.size,
                onCopyAll = {
                    val text = discoveredBeacons.values
                        .sortedWith(compareBy({ it.uuid }, { it.minor }))
                        .joinToString("\n") { it.toSurveyLine() }
                    clipboardManager.setText(AnnotatedString(text))
                },
                onClear = discoveredBeacons::clear,
            )
        }

        if (uuidGroups.isNotEmpty()) {
            item { UuidGroupsSection(uuidGroups) }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "${visibleBeacons.size} / ${beacons.size} cihaz", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Sadece iBeacon", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = showOnlyIBeacon, onCheckedChange = { showOnlyIBeacon = it })
                }
            }
        }

        items(visibleBeacons, key = { it.beaconId }) { beacon -> BeaconRow(beacon) }
    }
}

@Composable
private fun AutoSurveySection(
    trajectoryPointCount: Int,
    rawReadingCount: Int,
    currentFloor: Int,
    results: List<BeaconSurveyResult>,
    hasSolved: Boolean,
    onToggleRecording: () -> Unit,
    onFloorChange: (Int) -> Unit,
    onSolve: () -> Unit,
    onCopyResults: () -> Unit,
    onClear: () -> Unit,
) {
    Column {
        Text(text = "Otomatik saha kaydı (önerilen)", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Elle x/y girmene gerek yok: kayda başla, binada normal şekilde yürü, bitince durdur. Telefon adım sayarak (PDR) nerede olduğunu kendi hesaplar, aynı anda gördüğü beacon'ları zaman damgasıyla eşler. Sonunda \"Hesapla\" ile 3+ noktadan görülen her beacon'ın konumu otomatik çıkar. MERDİVEN/ASANSÖRLE KAT DEĞİŞTİRMEDEN ÖNCE aşağıdan katı güncelle — yoksa dikey hareket yatay konuma karışıp beacon konumlarını bozar.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onToggleRecording) {
                Text(if (BeaconSurveyLog.isRecording) "Kaydı durdur" else "Kaydı başlat")
            }
            Text(
                text = "$trajectoryPointCount adım, $rawReadingCount okuma",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Kat: $currentFloor", style = MaterialTheme.typography.bodyMedium)
            Button(modifier = Modifier.padding(start = 8.dp), onClick = { onFloorChange(-1) }) { Text("-1") }
            Button(modifier = Modifier.padding(start = 8.dp), onClick = { onFloorChange(1) }) { Text("+1") }
        }
        Row(modifier = Modifier.padding(top = 8.dp)) {
            Button(enabled = trajectoryPointCount > 0 && !BeaconSurveyLog.isRecording, onClick = onSolve) {
                Text("Hesapla")
            }
            Button(modifier = Modifier.padding(start = 8.dp), enabled = results.isNotEmpty(), onClick = onCopyResults) {
                Text("Sonuçları kopyala")
            }
            Button(modifier = Modifier.padding(start = 8.dp), enabled = trajectoryPointCount > 0, onClick = onClear) {
                Text("Temizle")
            }
        }
        if (hasSolved && results.isEmpty()) {
            Text(
                text = "Hiçbir beacon konumlandırılamadı: her beacon en az ${BeaconSurveySolver.MIN_OBSERVATIONS_FOR_FIX} farklı, aynı doğru üzerinde olmayan adımdan görülmüş olmalı. " +
                    "$trajectoryPointCount adım kaydedildi — eğer bu az ise (örn. tek haneli), adım sayımı çalışmamış olabilir; daha uzun ve yavaş yürüyerek tekrar dene.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (results.isNotEmpty()) {
            Text(text = "${results.size} beacon konumlandı:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            results.forEach { result ->
                Text(
                    text = "${result.beaconId} (kat ${result.floor}) -> x=${result.x.round1()} y=${result.y.round1()} (${result.observationCount} okuma)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DiscoveredHeader(discoveredCount: Int, onCopyAll: () -> Unit, onClear: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "$discoveredCount keşfedilen iBeacon (kaybolmaz)", style = MaterialTheme.typography.titleMedium)
            Row {
                Button(enabled = discoveredCount > 0, onClick = onCopyAll) { Text("Tümünü kopyala") }
                Button(modifier = Modifier.padding(start = 8.dp), enabled = discoveredCount > 0, onClick = onClear) { Text("Temizle") }
            }
        }
        Text(
            text = "\"Tümünü kopyala\" o ana kadar görülen HER beacon'ı verir (bazıları eski/bayat okumayla) — hangi beacon'ın kurumun ağı olduğunu bulmak için kullan.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun UuidGroupsSection(uuidGroups: List<UuidGroup>) {
    Column {
        Text(
            text = "UUID grupları (aynı UUID + farklı minor = muhtemelen kurumun beacon ağı):",
            style = MaterialTheme.typography.titleSmall,
        )
        uuidGroups.forEach { group ->
            val hint = if (group.distinctMinors > 1) "muhtemel kurum ağı" else "tek cihaz, muhtemelen kişisel"
            Text(
                text = "${group.uuid} — ${group.distinctMinors} farklı minor, ${group.sightingCount} okuma ($hint)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private data class UuidGroup(val uuid: String, val distinctMinors: Int, val sightingCount: Int)

private fun BleBeaconReading.toSurveyLine(): String {
    val distance = if (estimatedDistanceMeters >= 0) "${(estimatedDistanceMeters * 10).roundToInt() / 10.0}m" else "bilinmiyor"
    return "uuid=$uuid major=$major minor=$minor mac=$macAddress rssi=${rssi}dBm mesafe=$distance"
}

private fun BeaconSurveyResult.toSurveyLine(): String =
    "beaconId=$beaconId kat=$floor x=${x.round1()} y=${y.round1()} okuma=$observationCount"

internal fun Double.round1(): Double = (this * 10).roundToInt() / 10.0

@Composable
private fun BeaconRow(beacon: BleBeaconReading) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            val deviceName = beacon.deviceName
            val title = when {
                beacon.uuid != null -> "iBeacon ${beacon.uuid}"
                deviceName != null -> deviceName
                else -> "Bilinmeyen cihaz"
            }
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (beacon.uuid != null) {
                Text(text = "major=${beacon.major} minor=${beacon.minor}", style = MaterialTheme.typography.bodySmall)
            }
            Text(text = "MAC: ${beacon.macAddress}", style = MaterialTheme.typography.bodySmall)
            val distanceText = if (beacon.estimatedDistanceMeters >= 0) {
                "${(beacon.estimatedDistanceMeters * 10).roundToInt() / 10.0}m (kalibre edilmemiş)"
            } else {
                "bilinmiyor"
            }
            Text(text = "RSSI: ${beacon.rssi} dBm, tahmini mesafe: $distanceText", style = MaterialTheme.typography.bodySmall)
        }
    }
}
