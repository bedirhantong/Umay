package com.appvalence.umay.simulation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Writes the walk viewer (docs/YOL_HARITASI.md 0.11) for a set of simulated
 * walks to simulation/build/reports/walks/sanal-ofis.html — open it in a
 * browser to watch them:
 *
 *     ./gradlew :simulation:testDebugUnitTest --tests '*WalkReportsTest*'
 */
class WalkReportsTest {

    @Test
    fun `writes the virtual office walks page`() {
        val office = VirtualBuildings.openOffice()
        val institution = VirtualBuildings.institutionFloor()
        val everything = Aids(beacons = true, markerNodeIds = office.markerNodeIds)
        val phrases = StringsXmlPhrases.fromAppResources()

        val walks = listOf(
            simulateGuidedWalk(office, "giris", "tuvalet", seed = 1, phrases = phrases, aids = everything, record = true,
                title = "Girişten tuvalete"),
            simulateGuidedWalk(office, "asansor", "toplanti2", seed = 2, phrases = phrases, aids = everything, record = true,
                title = "Asansörden Toplantı Odası 2'ye"),
            simulateGuidedWalk(office, "yazici", "toplanti1", seed = 3, aids = everything, record = true,
                phrases = StringsXmlPhrases.fromAppResources(clockDirections = true), title = "Yazıcıdan Toplantı Odası 1'e, saat yönüyle"),
            simulateGuidedWalk(office, "giris", "mutfak", seed = 4, phrases = phrases, aids = Aids(), record = true,
                title = "Girişten mutfağa, yalnız adımla"),
            simulateGuidedWalk(office, "giris", "mutfak", seed = 5, phrases = phrases, aids = everything, appStartNodeId = "lobi", record = true,
                title = "Yanlış başlangıç: lobide sanıyor"),
        ).map { it.recording!! } + listOf(
            institutionWalk(institution, "Kurum katı, beacon'larla", seed = 1, aids = Aids(beacons = true)),
            institutionWalk(institution, "Kurum katı, yanlış başlangıç ve yer işareti", seed = 1, aids = Aids(markerNodeIds = setOf("M10")), filterStart = "M40"),
        )

        val page = WalkViewerPage.write(File("build/reports/walks/sanal-ofis.html"), walks)

        val html = page.readText()
        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue("__UMAY_WALK_DATA__" !in html, "data was not filled in")
        assertTrue("Girişten tuvalete" in html)
        println("Walk viewer: ${page.absolutePath}")
    }

    private fun institutionWalk(building: VirtualBuilding, title: String, seed: Int, aids: Aids, filterStart: String? = null): WalkRecording {
        val route = listOf("M0", "M10", "N10", "N20", "N30", "M30", "M40")
        val result = simulateWalk(building, route, seed, aids = aids, filterStartNodeId = filterStart ?: route.first(), record = true)
        return WalkRecording(
            title = title,
            subtitle = "${building.name}: önceden belirli bir yol, uygulama yalnızca konumu buluyor (yönlendirme yok).",
            map = result.map!!,
            frames = result.frames,
            summary = listOf(
                SummaryItem("Ortalama konum hatası", "%.2f m".format(result.errorsMeters.average())),
                SummaryItem("En kötü", "%.1f m".format(result.errorsMeters.max()), bad = result.errorsMeters.max() > 3),
                SummaryItem("Son", "%.2f m".format(result.final)),
                SummaryItem("Adım", result.errorsMeters.size.toString()),
            ),
            beacons = if (aids.beacons) building.beaconMarks() else emptyList(),
            markerNodeIds = aids.markerNodeIds,
        )
    }
}
