package com.appvalence.umay.survey

import com.appvalence.umay.positioning.mapping.DepthFrame
import kotlin.math.min

/** Analytic floor and wall fixture, exclusively in unit tests. */
object SyntheticMappingDepth {
    fun frame(x: Float, z: Float, wallDistance: Double, timestamp: Long): DepthFrame {
        val w = 96; val h = 72; val f = 55f; val cy = 30f
        return DepthFrame(w,h,ShortArray(w*h) { i ->
            val down = (i/w-cy)/f
            (min(wallDistance, if (down > 0) 1.35/down else Double.POSITIVE_INFINITY)*1000).toInt().toShort()
        },ByteArray(w*h) { 255.toByte() },f,f,48f,cy,
            floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, x,1.35f,z,1f),timestamp)
    }
}
