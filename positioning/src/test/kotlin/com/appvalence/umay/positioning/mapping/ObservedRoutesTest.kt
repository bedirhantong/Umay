package com.appvalence.umay.positioning.mapping

import com.appvalence.umay.coremodel.*
import kotlin.test.*

class ObservedRoutesTest {
    @Test fun `routes require clearance and unknown blocks a previously walked edge`() {
        val cells = ByteArray(100) { OccupancyRaster.FREE }
        val map = FloorMap(0,listOf(MapNode("a",0.55,0.55,NodeType.WAYPOINT),MapNode("b",1.35,0.55,NodeType.WAYPOINT)),
            listOf(MapEdge("a","b",true,0.8)),OccupancyRaster.of(0.2,0.0,0.0,10,10,cells),reconstruction=SpatialMap(coordinateFrameId="a"))
        assertEquals(1,ObservedRoutes.restrict(map).edges.size)
        cells[2*10+4] = OccupancyRaster.UNKNOWN
        assertTrue(ObservedRoutes.restrict(map.copy(occupancy=OccupancyRaster.of(0.2,0.0,0.0,10,10,cells))).edges.isEmpty())
        assertTrue(ObservedRoutes.restrict(map.copy(occupancy=null)).edges.isEmpty())
    }
}
