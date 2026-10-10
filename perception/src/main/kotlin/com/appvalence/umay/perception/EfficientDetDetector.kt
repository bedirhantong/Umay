package com.appvalence.umay.perception

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * EfficientDet-Lite0 (COCO) on the LiteRT interpreter, without the TFLite task
 * library: that library's native code isn't aligned for Android's 16 KB memory
 * pages, LiteRT's is. The model already ends in its own post-processing
 * (TFLite_Detection_PostProcess), so the outputs are finished detections —
 * boxes, classes, scores and a count, in the order its metadata lists them.
 *
 * Not thread-safe: one detector per thread (the camera analyzer's).
 */
class EfficientDetDetector private constructor(private val interpreter: Interpreter) : Closeable {

    /** One detection in the analyzed bitmap's pixel coordinates; [classIndex] into the COCO label map. */
    data class Detection(val classIndex: Int, val score: Float, val left: Float, val top: Float, val right: Float, val bottom: Float)

    private val inputSize: Int
    private val input: ByteBuffer
    private val pixels: IntArray
    private val boxes: Array<Array<FloatArray>>
    private val classes: Array<FloatArray>
    private val scores: Array<FloatArray>
    private val count = FloatArray(1)

    init {
        val inputShape = interpreter.getInputTensor(0).shape() // [1, size, size, 3], uint8
        inputSize = inputShape[1]
        input = ByteBuffer.allocateDirect(inputSize * inputSize * 3).order(ByteOrder.nativeOrder())
        pixels = IntArray(inputSize * inputSize)
        interpreter.allocateTensors()
        val boxShape = interpreter.getOutputTensor(BOXES).shape()
        require(boxShape.size == 3 && boxShape[2] == 4) { "Unexpected detection output layout: ${boxShape.contentToString()}" }
        val maxDetections = boxShape[1]
        boxes = Array(1) { Array(maxDetections) { FloatArray(4) } }
        classes = Array(1) { FloatArray(maxDetections) }
        scores = Array(1) { FloatArray(maxDetections) }
    }

    fun detect(bitmap: Bitmap, minScore: Float): List<Detection> {
        // A plain resize, as the task library did: the model was trained on squashed images.
        val scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        if (scaled !== bitmap) scaled.recycle()
        input.rewind()
        for (pixel in pixels) {
            input.put((pixel shr 16 and 0xFF).toByte())
            input.put((pixel shr 8 and 0xFF).toByte())
            input.put((pixel and 0xFF).toByte())
        }
        input.rewind()
        interpreter.runForMultipleInputsOutputs(arrayOf(input), mapOf(BOXES to boxes, CLASSES to classes, SCORES to scores, COUNT to count))

        val found = count[0].toInt().coerceIn(0, scores[0].size)
        return (0 until found).mapNotNull { i ->
            val score = scores[0][i]
            if (score < minScore) return@mapNotNull null
            // Boxes are [top, left, bottom, right], normalized.
            val box = boxes[0][i]
            Detection(
                classIndex = classes[0][i].toInt(),
                score = score,
                left = box[1].coerceIn(0f, 1f) * bitmap.width,
                top = box[0].coerceIn(0f, 1f) * bitmap.height,
                right = box[3].coerceIn(0f, 1f) * bitmap.width,
                bottom = box[2].coerceIn(0f, 1f) * bitmap.height,
            )
        }
    }

    override fun close() = interpreter.close()

    companion object {
        const val MODEL_ASSET_NAME = "efficientdet_lite0.tflite"

        // Output order of the model (its metadata: location, category, score, number of detections).
        private const val BOXES = 0
        private const val CLASSES = 1
        private const val SCORES = 2
        private const val COUNT = 3

        fun create(context: Context, numThreads: Int): EfficientDetDetector {
            val model = context.assets.openFd(MODEL_ASSET_NAME).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                    channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
                }
            }
            return EfficientDetDetector(Interpreter(model, Interpreter.Options().setNumThreads(numThreads)))
        }
    }
}
