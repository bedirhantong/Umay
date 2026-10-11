package com.appvalence.umay.simulation

import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType

/** The buildings the simulations walk through. */
internal object VirtualBuildings {

    private fun place(id: String, x: Double, y: Double, name: String = id, type: NodeType = NodeType.WAYPOINT) =
        MapNode(id = id, x = x, y = y, type = type, name = name)

    /**
     * A typical institution floor: a 40 m main corridor, two side corridors
     * up to a parallel one 15 m away (so there is a loop, and two corridors
     * that look alike to the steps), and a dead end to the south. Beacons
     * about every 10 m, as KURULUM.md recommends. No walls are modelled: the
     * corridors are all there is.
     *
     * ```
     *  N10 ─── N20 ─── N30
     *   │               │
     *  M0 ─ M10 ─ M20 ─ M30 ─ M40
     *              │
     *             S20
     * ```
     */
    fun institutionFloor(radio: RadioModel = RadioModel()) = VirtualBuilding(
        name = "Sanal kurum katı",
        nodes = listOf(
            place("M0", 0.0, 0.0),
            place("M10", 10.0, 0.0),
            place("M20", 20.0, 0.0),
            place("M30", 30.0, 0.0),
            place("M40", 40.0, 0.0),
            place("N10", 10.0, 15.0),
            place("N20", 20.0, 15.0),
            place("N30", 30.0, 15.0),
            place("S20", 20.0, -8.0),
        ),
        edges = listOf(
            "M0" to "M10", "M10" to "M20", "M20" to "M30", "M30" to "M40",
            "M10" to "N10", "N10" to "N20", "N20" to "N30", "N30" to "M30",
            "M20" to "S20",
        ),
        beacons = listOf(
            VirtualBeacon("b1", 4.0, 1.5),
            VirtualBeacon("b2", 16.0, -1.5),
            VirtualBeacon("b3", 27.0, 1.5),
            VirtualBeacon("b4", 38.0, -1.5),
            VirtualBeacon("b5", 11.5, 8.0),
            VirtualBeacon("b6", 28.5, 8.0),
            VirtualBeacon("b7", 15.0, 16.5),
            VirtualBeacon("b8", 25.0, 13.5),
            VirtualBeacon("b9", 21.5, -6.0),
        ),
        radio = radio,
    )

    /**
     * An open-plan office, 32 × 22 m: the entrance in the middle of the south
     * wall, a reception desk, rows of desks between three north–south aisles,
     * two meeting rooms and a WC behind walls, an open kitchen in the north-east
     * corner, the lift by the west wall. Ten beacons (two inside the meeting
     * rooms, heard through their walls), printed markers at the main places.
     *
     * ```
     *  ┌──────────┬────────┬──┬─────────────┐
     *  │ Toplantı │Toplantı│  │   Mutfak    │
     *  │    1     │   2    │  │      ●      │
     *  ├───●──────┴───●────┘  │      │      │
     *  │   w3───────n14─m3────┴─────e3      │
     *  ├──┐ │  [masa]   │   [masa]   │ [dolap]
     *  │WC●─w2──Masa 7──m2──Masa 21──e2     │
     *  ├──┘ │  [masa]   │   [masa]   │      │
     *  │Asansör─a1────Lobi──Danışma──a3─Yazıcı
     *  │                │  [danışma]        │
     *  └────────────── Giriş ───────────────┘
     * ```
     */
    fun openOffice(radio: RadioModel = RadioModel()) = VirtualBuilding(
        name = "Sanal açık ofis",
        nodes = listOf(
            place("giris", 16.0, 0.8, "Giriş"),
            place("lobi", 16.0, 5.0, "Lobi"),
            place("asansor", 1.0, 5.0, "Asansör", NodeType.ELEVATOR),
            place("a1", 6.5, 5.0),
            place("danisma", 21.25, 5.0, "Danışma"),
            place("a3", 27.0, 5.0),
            place("yazici", 30.5, 5.0, "Yazıcı"),
            place("w2", 6.5, 10.25),
            place("tuvalet", 5.0, 10.25, "Tuvalet", NodeType.DOOR),
            place("masa7", 11.25, 10.25, "Masa 7"),
            place("m2", 16.0, 10.25),
            place("masa21", 21.5, 10.25, "Masa 21"),
            place("e2", 27.0, 10.25),
            place("w3", 6.5, 15.0),
            place("n14", 14.0, 15.0),
            place("m3", 16.0, 15.0),
            place("e3", 27.0, 15.0),
            place("toplanti1", 6.5, 16.5, "Toplantı Odası 1", NodeType.DOOR),
            place("toplanti2", 14.0, 16.5, "Toplantı Odası 2", NodeType.DOOR),
            place("mutfak", 27.0, 18.5, "Mutfak", NodeType.DESTINATION),
        ),
        edges = listOf(
            "giris" to "lobi",
            "asansor" to "a1", "a1" to "lobi", "lobi" to "danisma", "danisma" to "a3", "a3" to "yazici",
            "a1" to "w2", "w2" to "w3", "w2" to "tuvalet",
            "w2" to "masa7", "masa7" to "m2", "m2" to "masa21", "masa21" to "e2",
            "lobi" to "m2", "m2" to "m3",
            "a3" to "e2", "e2" to "e3",
            "w3" to "toplanti1", "w3" to "n14", "n14" to "toplanti2", "n14" to "m3", "m3" to "e3",
            "e3" to "mutfak",
        ),
        beacons = listOf(
            VirtualBeacon("b1", 0.3, 3.0),
            VirtualBeacon("b2", 11.0, 0.3),
            VirtualBeacon("b3", 24.0, 0.3),
            VirtualBeacon("b4", 31.7, 9.0),
            VirtualBeacon("b5", 16.6, 12.0),
            VirtualBeacon("b6", 5.2, 8.0),
            VirtualBeacon("b7", 9.8, 19.0),
            VirtualBeacon("b8", 18.8, 19.0),
            VirtualBeacon("b9", 27.0, 21.7),
            VirtualBeacon("b10", 24.0, 16.0),
        ),
        radio = radio,
        walls = listOf(
            // Outer walls, the entrance a gap in the south one.
            Wall(0.0, 0.0, 15.2, 0.0), Wall(16.8, 0.0, 32.0, 0.0),
            Wall(32.0, 0.0, 32.0, 22.0), Wall(32.0, 22.0, 0.0, 22.0), Wall(0.0, 22.0, 0.0, 0.0),
            // WC, its door facing the west aisle.
            Wall(0.0, 7.0, 5.0, 7.0), Wall(0.0, 13.5, 5.0, 13.5), Wall(5.0, 7.0, 5.0, 9.75), Wall(5.0, 10.75, 5.0, 13.5),
            // Meeting rooms, doors onto the north aisle.
            Wall(0.0, 16.5, 6.0, 16.5), Wall(7.0, 16.5, 13.5, 16.5), Wall(14.5, 16.5, 19.0, 16.5),
            Wall(10.0, 16.5, 10.0, 22.0), Wall(19.0, 16.5, 19.0, 22.0),
        ),
        blocks = listOf(
            Block(19.5, 2.2, 23.0, 3.2), // reception desk
            Block(8.5, 7.0, 14.0, 9.25), Block(8.5, 11.25, 14.0, 13.25), // desks
            Block(18.0, 7.0, 25.0, 9.25), Block(18.0, 11.25, 25.0, 13.25),
            Block(29.5, 7.0, 31.6, 13.0), // cabinets
            Block(23.5, 20.5, 30.5, 21.6), // kitchen counter
            Block(2.5, 18.5, 7.5, 20.5), Block(12.0, 18.5, 17.0, 20.5), // meeting tables
        ),
        markerNodeIds = setOf("giris", "asansor", "danisma", "tuvalet", "toplanti1", "toplanti2", "mutfak"),
    )

    /**
     * The open office ([openOffice]) as the ground floor of a two-storey building:
     * a staircase by the printer and the lift by the west wall lead up to a first
     * floor with a corridor along its south side and three rooms off it. Each
     * staircase and lift landing has one way out, as most do. Floors are 3.5 m
     * apart; a slab between beacon and phone costs 15 dB.
     *
     * ```
     *  Floor 1:   ┌── Arşiv ──── Müdür odası ─── Toplantı salonu ──┐
     *             Asansör ─ k1 ── Bekleme salonu ── k3 ── k4       │
     *             └────────────────────────────────────── Merdiven ┘
     * ```
     */
    fun twoFloorOffice(radio: RadioModel = RadioModel()): VirtualBuilding {
        val ground = openOffice(radio)
        val upper = listOf(
            place("asansor1", 1.0, 5.0, "Asansör (1. kat)", NodeType.ELEVATOR),
            place("k1", 6.5, 5.0),
            place("bekleme", 16.0, 5.0, "Bekleme salonu"),
            place("k3", 27.0, 5.0),
            place("k4", 30.5, 5.0),
            place("merdiven1", 30.5, 2.0, "Merdiven (1. kat)", NodeType.STAIRS),
            place("arsiv", 6.5, 10.0, "Arşiv", NodeType.DOOR),
            place("mudur", 16.0, 10.0, "Müdür odası", NodeType.DOOR),
            place("salon", 27.0, 10.0, "Toplantı salonu", NodeType.DOOR),
        )
        val stairsDown = place("merdiven0", 30.5, 2.0, "Merdiven (zemin)", NodeType.STAIRS)
        return VirtualBuilding(
            name = "Sanal iki katlı ofis",
            nodes = ground.nodes + stairsDown + upper,
            edges = ground.edges + ("yazici" to "merdiven0") + listOf(
                "asansor1" to "k1", "k1" to "bekleme", "bekleme" to "k3", "k3" to "k4", "k4" to "merdiven1",
                "k1" to "arsiv", "bekleme" to "mudur", "k3" to "salon",
            ),
            beacons = ground.beacons + listOf(
                VirtualBeacon("u1", 3.0, 7.4, floor = 1),
                VirtualBeacon("u2", 12.0, 0.3, floor = 1),
                VirtualBeacon("u3", 21.0, 7.4, floor = 1),
                VirtualBeacon("u4", 31.7, 4.0, floor = 1),
                VirtualBeacon("u5", 16.0, 11.7, floor = 1),
            ),
            radio = radio,
            walls = ground.walls + listOf(
                Wall(0.0, 0.0, 32.0, 0.0, floor = 1), Wall(32.0, 0.0, 32.0, 12.0, floor = 1),
                Wall(32.0, 12.0, 0.0, 12.0, floor = 1), Wall(0.0, 12.0, 0.0, 0.0, floor = 1),
                // The rooms' wall along the corridor, a door to each.
                Wall(0.0, 7.5, 5.9, 7.5, floor = 1), Wall(7.1, 7.5, 15.4, 7.5, floor = 1),
                Wall(16.6, 7.5, 26.4, 7.5, floor = 1), Wall(27.6, 7.5, 32.0, 7.5, floor = 1),
                Wall(11.0, 7.5, 11.0, 12.0, floor = 1), Wall(21.5, 7.5, 21.5, 12.0, floor = 1),
            ),
            blocks = ground.blocks,
            markerNodeIds = ground.markerNodeIds,
            floorOf = upper.associate { it.id to 1 },
            transitions = listOf("asansor" to "asansor1", "merdiven0" to "merdiven1"),
        )
    }
}
