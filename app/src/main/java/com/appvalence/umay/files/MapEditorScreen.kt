package com.appvalence.umay.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appvalence.umay.R
import com.appvalence.umay.common.drawWallLayer
import com.appvalence.umay.common.rememberWallMapImage
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.MapEdits
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.isPlace
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Corrections to a saved map (see [MapEditorViewModel]), floor by floor: its
 * places (rename, change type, delete), the connections between them (remove,
 * add), and on a camera-surveyed floor its wall map (wipe a stray mark by
 * touching it, or clear it). Everything but the wall wiping is a plain list of
 * buttons, so the whole editor works with TalkBack.
 */
@Composable
fun MapEditorScreen(
    building: BuildingMap,
    onClose: () -> Unit,
    onRenamed: (String) -> Unit,
    onCheckBeacons: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MapEditorViewModel = hiltViewModel(),
) {
    LaunchedEffect(building.id) { viewModel.open(building) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val map = state.map ?: return

    var confirmLeave by remember { mutableStateOf(false) }
    var editingPlace by remember { mutableStateOf<MapNode?>(null) }
    var deletingPlace by remember { mutableStateOf<MapNode?>(null) }
    var connectingFloor by remember { mutableStateOf<FloorMap?>(null) }
    var clearingWallsOf by remember { mutableStateOf<Int?>(null) }

    fun leave() {
        viewModel.close()
        onClose()
    }
    val requestLeave = { if (state.hasChanges) confirmLeave = true else leave() }
    BackHandler(onBack = requestLeave)

    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                IconButton(onClick = requestLeave) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
                Text(
                    text = stringResource(R.string.editor_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                text = stringResource(R.string.editor_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onCheckBeacons, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(stringResource(R.string.beacons_open))
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.weight(1f), enabled = state.undoCount > 0, onClick = viewModel::undo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null)
                    Text(stringResource(R.string.editor_undo), modifier = Modifier.padding(start = 8.dp))
                }
                // A renamed building is saved under its new name: follow it there.
                Button(modifier = Modifier.weight(1f), enabled = state.hasChanges && !state.saving, onClick = { viewModel.save(onRenamed) }) {
                    Text(stringResource(R.string.common_save))
                }
            }
            state.message?.let { message ->
                Text(
                    text = stringResource(
                        when (message) {
                            MapEditorMessage.SAVED -> R.string.editor_saved
                            MapEditorMessage.SAVE_FAILED -> R.string.editor_save_failed
                            MapEditorMessage.NAME_TAKEN -> R.string.editor_name_taken
                            MapEditorMessage.NAME_BLANK -> R.string.editor_name_blank
                        },
                    ),
                    color = if (message == MapEditorMessage.SAVED) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        item(key = "name") {
            var name by rememberSaveable(map.id) { mutableStateOf(map.id) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.editor_building_name)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(enabled = name.trim() != map.id, onClick = { viewModel.renameBuilding(name) }) {
                    Text(stringResource(R.string.editor_building_name_apply))
                }
            }
        }

        if (state.unreachable.isNotEmpty()) {
            item(key = "unreachable") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.editor_unreachable, state.unreachable.joinToString { it.name }),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }

        map.floors.sortedBy { it.floorIndex }.forEach { floor ->
            val floorLabel = @Composable { stringResource(R.string.common_floor, floor.floorIndex) }
            val places = floor.nodes.filter { it.isPlace }.sortedBy { it.name }
            val names = floor.nodes.associate { it.id to it.name }

            item(key = "floor-${floor.floorIndex}") {
                Text(
                    text = floorLabel(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp).semantics { heading() },
                )
                Text(text = stringResource(R.string.editor_places), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                if (places.isEmpty()) Text(stringResource(R.string.editor_no_places), style = MaterialTheme.typography.bodySmall)
            }
            items(places, key = { "place-${it.id}" }) { place ->
                EditorRow(
                    title = place.name,
                    subtitle = typeLabel(place.type),
                ) {
                    IconButton(onClick = { editingPlace = place }) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.editor_place_edit_description, place.name))
                    }
                    IconButton(onClick = { deletingPlace = place }) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = stringResource(R.string.editor_place_delete_description, place.name))
                    }
                }
            }

            val links = MapEdits.placeLinks(floor)
            item(key = "links-${floor.floorIndex}") {
                Text(text = stringResource(R.string.editor_links), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                if (links.isEmpty()) Text(stringResource(R.string.editor_no_links), style = MaterialTheme.typography.bodySmall)
            }
            items(links, key = { link -> "link-${floor.floorIndex}-${link.edges.joinToString { it.fromId + it.toId }}" }) { link ->
                val from = names[link.fromId].orEmpty()
                val to = names[link.toId].orEmpty()
                EditorRow(
                    title = stringResource(
                        R.string.editor_link,
                        from,
                        to,
                        stringResource(R.string.common_meters_short, link.lengthMeters.roundToInt().coerceAtLeast(1)),
                    ),
                ) {
                    IconButton(onClick = { viewModel.disconnect(link) }) {
                        Icon(Icons.Filled.LinkOff, contentDescription = stringResource(R.string.editor_link_remove_description, from, to))
                    }
                }
            }
            item(key = "link-add-${floor.floorIndex}") {
                OutlinedButton(enabled = places.size >= 2, onClick = { connectingFloor = floor }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.editor_link_add))
                }
            }

            if (floor.occupancy != null) {
                item(key = "walls-${floor.floorIndex}") {
                    Column {
                        Text(text = stringResource(R.string.editor_walls), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        Text(text = stringResource(R.string.editor_walls_body), style = MaterialTheme.typography.bodySmall)
                        WallEraseCanvas(
                            floor = floor,
                            description = stringResource(R.string.editor_walls_description, floorLabel()),
                            onTap = { x, y -> viewModel.eraseWalls(floor.floorIndex, x, y) },
                        )
                        OutlinedButton(onClick = { clearingWallsOf = floor.floorIndex }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.editor_walls_clear))
                        }
                    }
                }
            }
        }
        item(key = "bottom") { Column(modifier = Modifier.height(24.dp)) {} }
    }

    editingPlace?.let { place ->
        EditPlaceDialog(
            place = place,
            onSave = { name, type -> viewModel.updatePlace(place.id, name, type) },
            onDismiss = { editingPlace = null },
        )
    }
    deletingPlace?.let { place ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.editor_place_delete_title, place.name),
            body = stringResource(R.string.editor_place_delete_body),
            onConfirm = { viewModel.deletePlace(place.id) },
            onDismiss = { deletingPlace = null },
        )
    }
    connectingFloor?.let { floor ->
        ConnectPlacesDialog(
            places = floor.nodes.filter { it.isPlace }.sortedBy { it.name },
            onConnect = { a, b -> viewModel.connect(a, b) },
            onDismiss = { connectingFloor = null },
        )
    }
    clearingWallsOf?.let { floorIndex ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.editor_walls_clear_title),
            body = stringResource(R.string.editor_walls_clear_body),
            onConfirm = { viewModel.clearWalls(floorIndex) },
            onDismiss = { clearingWallsOf = null },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.editor_leave_title)) },
            text = { Text(stringResource(R.string.editor_leave_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    leave()
                }) { Text(stringResource(R.string.editor_leave), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun EditorRow(title: String, subtitle: String? = null, actions: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            actions()
        }
    }
}

// The types a place can be given — the same ones the survey offers when naming it.
private val EDITABLE_TYPES = listOf(NodeType.WAYPOINT, NodeType.DOOR, NodeType.STAIRS, NodeType.ELEVATOR, NodeType.RAMP)

@Composable
private fun typeLabel(type: NodeType): String? = when (type) {
    NodeType.DOOR -> stringResource(R.string.survey_type_door)
    NodeType.STAIRS -> stringResource(R.string.survey_type_stairs)
    NodeType.ELEVATOR -> stringResource(R.string.survey_type_elevator)
    NodeType.RAMP -> stringResource(R.string.survey_type_ramp)
    else -> null
}

@Composable
private fun EditPlaceDialog(place: MapNode, onSave: (String, NodeType) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable(place.id) { mutableStateOf(place.name) }
    var type by rememberSaveable(place.id) { mutableStateOf(if (place.type in EDITABLE_TYPES) place.type else NodeType.WAYPOINT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_place_edit_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.editor_place_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.padding(top = 8.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    EDITABLE_TYPES.take(3).forEach { option -> TypeChip(option, type) { type = it } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    EDITABLE_TYPES.drop(3).forEach { option -> TypeChip(option, type) { type = it } }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(name, type)
                onDismiss()
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun TypeChip(option: NodeType, selected: NodeType, onSelect: (NodeType) -> Unit) {
    FilterChip(
        selected = option == selected,
        onClick = { onSelect(option) },
        label = { Text(typeLabel(option) ?: stringResource(R.string.editor_place_type_plain)) },
    )
}

@Composable
private fun ConnectPlacesDialog(places: List<MapNode>, onConnect: (String, String) -> Unit, onDismiss: () -> Unit) {
    var first by rememberSaveable { mutableStateOf<String?>(null) }
    var second by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_link_add_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.editor_link_add_body), style = MaterialTheme.typography.bodySmall)
                PlaceChoices(stringResource(R.string.editor_link_first), places, first) { first = it }
                PlaceChoices(stringResource(R.string.editor_link_second), places.filter { it.id != first }, second) { second = it }
            }
        },
        confirmButton = {
            TextButton(enabled = first != null && second != null && first != second, onClick = {
                onConnect(first!!, second!!)
                onDismiss()
            }) { Text(stringResource(R.string.editor_link_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun PlaceChoices(label: String, places: List<MapNode>, selected: String?, onSelect: (String) -> Unit) {
    Text(text = label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
    Column(modifier = Modifier.selectableGroup()) {
        places.forEach { place ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = place.id == selected, role = Role.RadioButton, onClick = { onSelect(place.id) }),
            ) {
                // The row is the control; the button only shows its state.
                RadioButton(selected = place.id == selected, onClick = null)
                Text(text = place.name, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** The floor's wall map, fitted to the box; a tap wipes the walls around that spot. */
@Composable
private fun WallEraseCanvas(floor: FloorMap, description: String, onTap: (Double, Double) -> Unit) {
    val raster = floor.occupancy ?: return
    val image by rememberWallMapImage(
        raster = raster,
        seenFloorColor = MaterialTheme.colorScheme.surfaceVariant,
        wallColor = MaterialTheme.colorScheme.onSurface,
    )
    val placeColor = MaterialTheme.colorScheme.primary
    val widthMeters = raster.width * raster.cellSizeMeters
    val heightMeters = raster.height * raster.cellSizeMeters
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .padding(vertical = 8.dp)
            .semantics { contentDescription = description }
            .pointerInput(raster) {
                detectTapGestures { tap ->
                    val scale = min(size.width / widthMeters, size.height / heightMeters)
                    val left = (size.width - widthMeters * scale) / 2
                    val top = (size.height - heightMeters * scale) / 2
                    val x = raster.originX + (tap.x - left) / scale
                    val y = raster.originY + heightMeters - (tap.y - top) / scale
                    onTap(x, y)
                }
            },
    ) {
        val scale = min(size.width / widthMeters, size.height / heightMeters)
        val left = (size.width - widthMeters * scale) / 2
        val top = (size.height - heightMeters * scale) / 2
        image?.let { walls ->
            drawWallLayer(walls.seenFloor, walls, Offset(left.toFloat(), top.toFloat()), scale.toFloat())
            drawWallLayer(walls.walls, walls, Offset(left.toFloat(), top.toFloat()), scale.toFloat())
        }
        floor.nodes.filter { it.isPlace }.forEach { node ->
            val center = Offset(
                (left + (node.x - raster.originX) * scale).toFloat(),
                (top + (raster.originY + heightMeters - node.y) * scale).toFloat(),
            )
            drawCircle(placeColor, radius = 6.dp.toPx(), center = center)
        }
    }
}
