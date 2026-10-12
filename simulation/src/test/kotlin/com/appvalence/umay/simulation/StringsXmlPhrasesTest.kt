package com.appvalence.umay.simulation

import com.appvalence.umay.coremodel.AlertPriority
import com.appvalence.umay.coremodel.ObstacleKind
import com.appvalence.umay.orchestrator.Turn
import com.appvalence.umay.orchestrator.TurnDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class StringsXmlPhrasesTest {
    private val phrases = StringsXmlPhrases.fromAppResources()

    @Test
    fun `speaks the app's own sentences`() {
        assertEquals("Mutfak yönünde yaklaşık 10 metre ilerle, sonra sağa dön.", phrases.walkToward("Mutfak", 9.6, Turn(90.0)))
        assertEquals("Sola dön.", phrases.turnNow(Turn(-80.0)))
        assertEquals("Hedefe yaklaşık 1 metre kaldı.", phrases.approachDestination(0.2))
        assertEquals("Dur! Hemen önünde bir sandalye var.", phrases.obstacle(AlertPriority.CRITICAL, ObstacleKind.CHAIR, 1.0))
        assertEquals("Duvar solunda; onu takip edebilirsin.", phrases.followWall(TurnDirection.LEFT))
    }

    @Test
    fun `clock-face wording when asked for`() {
        val clock = StringsXmlPhrases.fromAppResources(clockDirections = true)
        assertEquals("Yaklaşık 5 metre ilerle, sonra saat 2 yönüne dön.", clock.walkAhead(5.0, Turn(50.0)))
        assertEquals("Saat 9 yönüne dön.", clock.turnNow(Turn(-90.0)))
    }

    @Test
    fun `unescapes what the resource compiler unescapes`() {
        val parsed = StringsXmlPhrases.parse(
            """<resources><string name="say_arrived">Hedefe\'ulaştın &amp; dur</string><plurals name="say_meters"><item quantity="other">%d metre</item></plurals></resources>""",
        )
        assertEquals("Hedefe'ulaştın & dur", parsed.arrived())
    }
}
