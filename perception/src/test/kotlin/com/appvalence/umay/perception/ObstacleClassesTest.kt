package com.appvalence.umay.perception

import com.appvalence.umay.coremodel.ObstacleKind
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObstacleClassesTest {

    @Test
    fun `every class index names the same thing in the bundled model's own label map`() {
        // A model file with metadata carries its label map as a zip appended to the flatbuffer.
        val model = File("src/main/assets/${EfficientDetDetector.MODEL_ASSET_NAME}")
        val labels = ZipFile(model).use { zip -> zip.getInputStream(zip.getEntry("labelmap.txt")).bufferedReader().readLines() }

        ObstacleClasses.byIndex.forEach { (index, obstacleClass) -> assertEquals(obstacleClass.label, labels[index], "class $index") }
    }

    @Test
    fun `a box cut off by the bottom of the frame is close, whatever its height says`() {
        val chair = ObstacleClasses.byIndex.getValue(61)
        assertEquals(ObstacleKind.CHAIR, chair.kind)

        val whole = ObstacleClasses.distanceMeters(chair, top = 300f, bottom = 500f, imageHeight = 1000)
        val cutOff = ObstacleClasses.distanceMeters(chair, top = 800f, bottom = 1000f, imageHeight = 1000)

        assertTrue(whole > 3.0, "$whole")
        assertEquals(ObstacleClasses.CUT_OFF_DISTANCE_METERS, cutOff, 1e-9)
    }
}
