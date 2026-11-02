package com.appvalence.umay.navigation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.appvalence.umay.R
import com.appvalence.umay.coremodel.BuildingMap

/** Saved reconstruction remains inspectable before there are any routable destinations. */
@Composable
internal fun BuildingGeometryPreview(building: BuildingMap) {
    val floors = remember(building) { building.floors.filter { it.reconstruction?.hasGeometry == true || it.occupancy != null }.sortedBy { it.floorIndex } }
    var selectedFloor by remember(building.id) { mutableStateOf(floors.firstOrNull()?.floorIndex) }
    val floor = floors.firstOrNull { it.floorIndex == selectedFloor } ?: floors.firstOrNull() ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.nav_geometry_only), style = MaterialTheme.typography.bodyMedium)
        if (floors.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            floors.forEach { candidate ->
                FilterChip(selected = candidate.floorIndex == floor.floorIndex,
                    onClick = { selectedFloor = candidate.floorIndex },
                    label = { Text(stringResource(R.string.common_floor, candidate.floorIndex)) })
            }
        }
        NavigationMapCanvas(floor, stringResource(R.string.common_floor, floor.floorIndex), emptyList(), 0,
            emptyMap(), null, stringResource(R.string.nav_map_description_floor, floor.floorIndex))
        if (floor.reconstruction != null) Text(stringResource(R.string.map_geometry_legend), style = MaterialTheme.typography.bodySmall)
    }
}
