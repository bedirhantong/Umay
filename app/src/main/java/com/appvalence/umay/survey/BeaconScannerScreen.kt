package com.appvalence.umay.survey

import android.Manifest
import android.content.Context
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.RadioFingerprint
import com.appvalence.umay.coremodel.TaggedWaypoint
import com.appvalence.umay.positioning.ble.BleBeaconScanner
import com.appvalence.umay.positioning.ble.BleScanState
import com.appvalence.umay.positioning.pdr.PdrIntegrator
import com.appvalence.umay.positioning.pdr.StepDetector
import com.appvalence.umay.positioning.trilateration.BeaconMapBuilder
import com.appvalence.umay.positioning.trilateration.BeaconSurveyResult
import com.appvalence.umay.positioning.trilateration.BeaconSurveySolver
import com.appvalence.umay.storage.BuildingMapStorage
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
    val taggedWaypoints = mutableStateListOf<TaggedWaypoint>()
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
 * On-site mapping walk (see [BleBeaconScanner], [BeaconSurveySolver]): the
 * operator starts a recording, walks the building naming places as they go,
 * then stops and saves the resulting map. BLE scanning itself only runs
 * while a recording is active (see the [BeaconSurveyLog.isRecording]-keyed
 * [DisposableEffect] below) — there's nothing to discover before that, and
 * running the radio on the whole time this screen happens to be open serves
 * no one. Everything below the main record/save flow (raw beacon list, UUID
 * grouping, copyable survey text) is advanced/debug detail, folded away by
 * default.
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

    if (!remember { StepDetector.isAvailable(context) }) {
        Text(
            text = "Bu cihazda konum takibi için gereken ivmeölçer veya jiroskop sensörü yok, saha kaydı kullanılamıyor.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier.padding(16.dp),
        )
        return
    }

    val scanner = remember { BleBeaconScanner(context) }
    DisposableEffect(BeaconSurveyLog.isRecording) {
        val isRecording = BeaconSurveyLog.isRecording
        if (isRecording) scanner.start()
        onDispose { if (isRecording) scanner.stop() }
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

    var showAdvanced by remember { mutableStateOf(false) }

    LazyColumn(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
            RecordingCard(
                isRecording = BeaconSurveyLog.isRecording,
                trajectoryPointCount = trajectory.size,
                rawReadingCount = rawReadings.size,
                taggedWaypointCount = BeaconSurveyLog.taggedWaypoints.size,
                currentFloor = BeaconSurveyLog.currentFloor,
                onStart = { BeaconSurveyLog.isRecording = true },
                onStop = {
                    BeaconSurveyLog.isRecording = false
                    // Stopping is exactly when there's something new to solve; requiring a
                    // separate manual step right after is friction nobody needs.
                    solve()
                },
                onFloorChange = { delta -> BeaconSurveyLog.currentFloor += delta },
                onClear = {
                    trajectory.clear()
                    rawReadings.clear()
                    BeaconSurveyLog.taggedWaypoints.clear()
                    BeaconSurveyLog.results = emptyList()
                    BeaconSurveyLog.hasSolved = false
                    BeaconSurveyLog.currentFloor = 0
                },
            )
        }

        if (BeaconSurveyLog.isRecording) {
            item {
                WaypointTaggingSection(
                    taggedWaypoints = BeaconSurveyLog.taggedWaypoints,
                    onTag = { name, type ->
                        val position = trajectory.lastOrNull { it.floor == BeaconSurveyLog.currentFloor }
                        if (position != null) {
                            val fingerprint = beacons.filter { it.uuid != null }.map { RadioFingerprint(it.beaconId, it.rssi.toDouble()) }
                            BeaconSurveyLog.taggedWaypoints.add(TaggedWaypoint(name, position.x, position.y, position.floor, fingerprint, type))
                        }
                    },
                    onDelete = { index -> BeaconSurveyLog.taggedWaypoints.removeAt(index) },
                )
            }
        }

        item {
            SurveyMapCanvas(
                trajectory = trajectory.filter { it.floor == BeaconSurveyLog.currentFloor },
                results = BeaconSurveyLog.results.filter { it.floor == BeaconSurveyLog.currentFloor },
                waypoints = BeaconSurveyLog.taggedWaypoints.filter { it.floor == BeaconSurveyLog.currentFloor },
            )
        }

        if (BeaconSurveyLog.hasSolved && !BeaconSurveyLog.isRecording) {
            item {
                SaveMapCard(
                    context = context,
                    results = BeaconSurveyLog.results,
                    taggedWaypoints = BeaconSurveyLog.taggedWaypoints,
                    trajectoryPointCount = trajectory.size,
                )
            }
        }

        item {
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Icon(if (showAdvanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                Text(
                    text = if (showAdvanced) "Gelişmiş bilgileri gizle" else "Gelişmiş bilgileri göster (ham beacon listesi, UUID grupları)",
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }

        if (showAdvanced) {
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.toggleable(
                            value = showOnlyIBeacon,
                            role = Role.Switch,
                            onValueChange = { showOnlyIBeacon = it },
                        ),
                    ) {
                        Text(text = "Sadece iBeacon", style = MaterialTheme.typography.bodySmall)
                        // The Row's toggleable modifier owns the click handling and merged
                        // accessible name ("Sadece iBeacon, açık/kapalı") — the Switch itself
                        // must not also be independently focusable, or TalkBack sees two
                        // controls (an unlabeled switch and a plain text) instead of one.
                        Switch(checked = showOnlyIBeacon, onCheckedChange = null)
                    }
                }
            }

            items(visibleBeacons, key = { it.beaconId }) { beacon -> BeaconRow(beacon) }
        }
    }
}

@Composable
private fun RecordingCard(
    isRecording: Boolean,
    trajectoryPointCount: Int,
    rawReadingCount: Int,
    taggedWaypointCount: Int,
    currentFloor: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onFloorChange: (Int) -> Unit,
    onClear: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isRecording) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (!isRecording) {
                Text(text = "Yeni bir alan haritala", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Kaydı başlat, binada normal şekilde yürü, önemli yerlere isim ver, bitince durdur. " +
                        "Elle konum girmene gerek yok, telefon adım sayarak kendi hesaplar.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (trajectoryPointCount > 0) {
                    Text(
                        text = "Bitmemiş bir kayıt var: $trajectoryPointCount adım, $taggedWaypointCount isimlendirilmiş yer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Button(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    onClick = onStart,
                ) {
                    Icon(Icons.Filled.FiberManualRecord, contentDescription = null)
                    Text(
                        text = if (trajectoryPointCount > 0) "Kayda devam et" else "Haritalamayı başlat",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (trajectoryPointCount > 0) {
                    TextButton(modifier = Modifier.padding(top = 4.dp), onClick = onClear) {
                        Text("Kaydı sil ve baştan başla")
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(
                        text = "Kaydediliyor...",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    text = "$trajectoryPointCount adım · $rawReadingCount beacon okuması · $taggedWaypointCount isimlendirilmiş yer",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Kat $currentFloor", style = MaterialTheme.typography.bodyMedium)
                    // No explicit size here: IconButton's Material3 default already
                    // reserves a full 48dp touch target even though the icon itself is
                    // smaller — pinning this to 36dp would shrink the tappable area below
                    // the accessible minimum.
                    IconButton(onClick = { onFloorChange(-1) }) {
                        Icon(Icons.Filled.Remove, contentDescription = "Bir alt kat")
                    }
                    IconButton(onClick = { onFloorChange(1) }) {
                        Icon(Icons.Filled.Add, contentDescription = "Bir üst kat")
                    }
                    Text(
                        text = "Merdiven/asansörden önce güncelle",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Button(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = onStop,
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Text(text = "Kaydı durdur", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

// The types worth offering at tagging time — plain WAYPOINT is the default and not
// listed as its own chip (deselecting a hazard chip already means "just a point").
private val TAGGABLE_HAZARD_TYPES = listOf(
    NodeType.DOOR to "Kapı",
    NodeType.STAIRS to "Merdiven",
    NodeType.ELEVATOR to "Asansör",
    NodeType.RAMP to "Rampa",
)

@Composable
private fun WaypointTaggingSection(
    taggedWaypoints: List<TaggedWaypoint>,
    onTag: (String, NodeType) -> Unit,
    onDelete: (Int) -> Unit,
) {
    var nameInput by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(NodeType.WAYPOINT) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocationOn, contentDescription = null)
                Text(text = "Bulunduğun yeri isimlendir", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                text = "\"Ana Giriş\", \"203 Sınıfı\" gibi önemli bir noktaya geldiğinde ismini yaz ve işaretle. " +
                    "Kapı/merdiven/asansör/rampa gibi bir tehlike ya da kat geçişiyse türünü de seç — kamera bunları göremez, " +
                    "navigasyon sırasında sesli uyarı ancak burada işaretlersen verilebilir.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TAGGABLE_HAZARD_TYPES.forEach { (type, label) ->
                    FilterChip(
                        selected = selectedType == type,
                        onClick = { selectedType = if (selectedType == type) NodeType.WAYPOINT else type },
                        label = { Text(label) },
                    )
                }
            }
            Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Yer adı") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    modifier = Modifier.padding(start = 8.dp),
                    enabled = nameInput.isNotBlank(),
                    onClick = {
                        onTag(nameInput.trim(), selectedType)
                        nameInput = ""
                        selectedType = NodeType.WAYPOINT
                    },
                ) {
                    Text("İşaretle")
                }
            }
            taggedWaypoints.forEachIndexed { index, waypoint ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val typeLabel = TAGGABLE_HAZARD_TYPES.firstOrNull { it.first == waypoint.type }?.second
                    val label = if (typeLabel != null) "${waypoint.name} ($typeLabel, kat ${waypoint.floor})" else "${waypoint.name} (kat ${waypoint.floor})"
                    Text(text = label, style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { onDelete(index) }) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = "Sil")
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveMapCard(
    context: Context,
    results: List<BeaconSurveyResult>,
    taggedWaypoints: List<TaggedWaypoint>,
    trajectoryPointCount: Int,
) {
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var saveFailed by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (results.isEmpty() && taggedWaypoints.isEmpty()) {
                Text(
                    text = "Hiçbir yer haritalanamadı: en az bir yere isim vermen veya her beacon'ın " +
                        "en az ${BeaconSurveySolver.MIN_OBSERVATIONS_FOR_FIX} farklı noktadan görülmesi gerekiyor. " +
                        "$trajectoryPointCount adım kaydedildi — eğer bu az ise (örn. tek haneli), adım sayımı çalışmamış olabilir; " +
                        "daha uzun ve yavaş yürüyerek tekrar dene.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }
            Text(text = "Harita hazır", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${taggedWaypoints.size} isimlendirilmiş yer, ${results.size} konumlandırılan beacon.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Button(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                onClick = {
                    val map = BeaconMapBuilder.buildFromWaypoints(
                        buildingId = "umay-building",
                        taggedWaypoints = taggedWaypoints,
                        beaconResults = results,
                    )
                    val saved = BuildingMapStorage(context).save(map)
                    saveFailed = !saved
                    saveMessage = if (saved) {
                        val nodeCount = map.floors.sumOf { it.nodes.size }
                        val edgeCount = map.floors.sumOf { it.edges.size }
                        "Kaydedildi: $nodeCount nokta, $edgeCount bağlantı, ${map.floors.size} kat."
                    } else {
                        "Kaydedilemedi — cihazda yer kalmamış olabilir. Tekrar dene."
                    }
                },
            ) {
                Icon(Icons.Filled.Save, contentDescription = null)
                Text(text = "Haritayı kaydet", modifier = Modifier.padding(start = 8.dp))
            }
            saveMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (saveFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp),
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
            Text(text = "$discoveredCount keşfedilen iBeacon", style = MaterialTheme.typography.titleMedium)
            Row {
                OutlinedButton(enabled = discoveredCount > 0, onClick = onCopyAll) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(text = "Kopyala", modifier = Modifier.padding(start = 4.dp))
                }
                TextButton(modifier = Modifier.padding(start = 4.dp), enabled = discoveredCount > 0, onClick = onClear) {
                    Text("Temizle")
                }
            }
        }
        Text(
            text = "Bir kayıt sırasında görülen HER beacon burada birikir (bazıları eski/bayat okumayla) — hangi beacon'ın kurumun ağı olduğunu bulmak için kullan.",
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
