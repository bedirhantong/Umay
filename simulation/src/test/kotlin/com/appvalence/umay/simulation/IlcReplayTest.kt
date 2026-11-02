package com.appvalence.umay.simulation

import java.io.File
import kotlin.test.Test

/**
 * Not a check: replays real walks from Microsoft's Indoor Location Competition
 * 2.0 sample data (MIT; github.com/location-competition/indoor-location-competition-20)
 * through the app's step detection and positioning, and prints how far off it
 * was, beside plain dead reckoning that is even told the true starting
 * direction. Each walk is replayed on a map surveyed from the floor's other
 * walks ("eksik": the survey missed some of where this walk goes) and from
 * all of them ("tam"). Point UMAY_ILC_DATA at the data folder (or one
 * floor of it) and run this test on its own; a few walks also land in
 * simulation/build/reports/walks/gercek-yuruyusler.html.
 */
class IlcReplayTest {
    @Test
    fun `report on the competition's real walks`() {
        val root = System.getenv("UMAY_ILC_DATA")?.let(::File) ?: return
        val shown = ArrayList<WalkRecording>()
        println("kat        harita yürüyüş kapsama | adım sayma* | uygulama | + pusula | + wifi | + wifi ve pusula   (ortalama hata)")
        for (floor in IlcFloor.loadAll(root)) {
            for (full in listOf(false, true)) {
                val plain = floor.paths.mapNotNull { replayIlcWalk(floor, it, surveyIncludesWalk = full, record = full) }
                if (plain.isEmpty()) continue
                if (full) shown += plain.mapNotNull { it.recording }.sortedBy { it.frames.size }.takeLast(3)
                val compass = floor.paths.mapNotNull { replayIlcWalk(floor, it, surveyIncludesWalk = full, compassHintSigmaDeg = COMPASS_SIGMA) }
                val wifi = floor.paths.mapNotNull { replayIlcWalk(floor, it, radio = IlcRadio.WIFI, surveyIncludesWalk = full) }
                val both = floor.paths.mapNotNull { replayIlcWalk(floor, it, radio = IlcRadio.WIFI, surveyIncludesWalk = full, compassHintSigmaDeg = COMPASS_SIGMA) }
                fun mean(results: List<IlcResult>) = results.flatMap { it.particleErrors }.average()
                println(
                    "%-10s %-6s %3d/%-3d %5.0f%%  |  %5.2f m   | %5.2f m  | %5.2f m  | %5.2f m | %5.2f m".format(
                        floor.name, if (full) "tam" else "eksik", plain.size, floor.paths.size, 100 * plain.map { it.coverage }.average(),
                        plain.flatMap { it.pdrErrors }.average(), mean(plain), mean(compass), mean(wifi), mean(both),
                    ),
                )
            }
        }
        println("* Yalnız adım sayma, gerçek başlangıç yönü verilerek (uygulamanın bilmediği bir şey).")
        if (shown.isNotEmpty()) println("Viewer: " + WalkViewerPage.write(File("build/reports/walks/gercek-yuruyusler.html"), shown).absolutePath)
    }

    private companion object {
        const val COMPASS_SIGMA = 30.0
    }
}
