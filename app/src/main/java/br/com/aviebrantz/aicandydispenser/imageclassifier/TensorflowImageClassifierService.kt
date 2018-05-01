package br.com.aviebrantz.aicandydispenser.imageclassifier

import android.content.ContentValues
import android.content.Context
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import android.media.ThumbnailUtils
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.Log
import br.com.aviebrantz.aicandydispenser.CandyCamera
import org.tensorflow.lite.Interpreter
import java.util.*
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.experimental.and


/**
 * Created by alvaroviebrantz on 15/06/17.
 */
class TensorflowImageClassifierService(ctx: Context): ImageClassifierService {

    val IMAGE_WIDTH = 224
    val IMAGE_HEIGHT = 224
    val PIXEL_SIZE = 3
    private val LABELS_FILE = "labels.txt"
    private val INVERTED_INDEX_FILE = "inverted_index.txt"
    val MODEL_FILE = "mobilenet_quant_v1_224.tflite"

    private val MAX_BEST_RESULTS = 10
    private val RESULT_CONFIDENCE_THRESHOLD = 0.1f

    private val mInferenceInterface: Interpreter
    private val mLabels: List<String>
    private val mInvertedIndex: Map<String, List<String>>

    private val  mCroppedBitmap: Bitmap
    private var mRgbFrameBitmap: Bitmap

    private val mImgData: ByteBuffer

    init {
        mInferenceInterface = Interpreter(loadModelFile(ctx, MODEL_FILE))
        mLabels = readLabels(ctx)
        mInvertedIndex = readInvertedIndex(ctx)

        mCroppedBitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT,
                Bitmap.Config.ARGB_8888)
        mRgbFrameBitmap = Bitmap.createBitmap(CandyCamera.InstanceHolder.IMAGE_WIDTH,
                CandyCamera.InstanceHolder.IMAGE_HEIGHT, Bitmap.Config.ARGB_8888)

        mImgData = ByteBuffer.allocateDirect(1 * IMAGE_WIDTH * IMAGE_HEIGHT * PIXEL_SIZE)
        mImgData.order(ByteOrder.nativeOrder())
    }

    private fun loadModelFile(context: Context, modelFile: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelFile)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun readInvertedIndex(ctx: Context): Map<String, List<String>> {
        val assetManager = ctx.assets
        val result = hashMapOf<String, List<String>>()
        try {
            val inputStream = assetManager.open(INVERTED_INDEX_FILE)
            val bufferedReader = BufferedReader(InputStreamReader(inputStream))
            var line = bufferedReader.readLine()
            while (line != null) {

                val parts = line.split('\t')
                val key = parts[0]
                val values = parts[1]

                result[key] = values.split(", ")

                line = bufferedReader.readLine()
            }
            return result
        } catch (ex: IOException) {
            throw IllegalStateException("Cannot read labels from $LABELS_FILE")
        }
    }

    private fun readLabels(ctx: Context): List<String> {
        val assetManager = ctx.assets
        val result = arrayListOf<String>()
        try {
            val inputStream = assetManager.open(LABELS_FILE)
            val bufferedReader = BufferedReader(InputStreamReader(inputStream))
            var line = bufferedReader.readLine()
            while (line != null) {
                result.add(line)
                line = bufferedReader.readLine()
            }
            return result
        } catch (ex: IOException) {
            throw IllegalStateException("Cannot read labels from $LABELS_FILE")
        }
    }

    private fun getPixels(bitmap: Bitmap): ByteBuffer {
        var mutBitmap = bitmap
        if (mutBitmap.width != IMAGE_WIDTH || mutBitmap.height != IMAGE_HEIGHT) {
            // rescale the bitmap if needed
            mutBitmap = ThumbnailUtils.extractThumbnail(bitmap, IMAGE_WIDTH, IMAGE_HEIGHT)
        }
        val intValues = IntArray(IMAGE_WIDTH * IMAGE_HEIGHT)

        bitmap.getPixels(intValues, 0, mutBitmap.width, 0, 0, mutBitmap.width, mutBitmap.height)

        mImgData.rewind()

        // Encode the image pixels into a byte buffer representation matching the expected
        // input of the Tensorflow model
        var pixel = 0
        for (i in 0 until bitmap.width) {
            for (j in 0 until bitmap.height) {
                val pixelValue = intValues[pixel++]
                mImgData.put((pixelValue shr 16 and 0xFF).toByte())
                mImgData.put((pixelValue shr 8 and 0xFF).toByte())
                mImgData.put((pixelValue and 0xFF).toByte())
            }
        }

        return mImgData
    }

    override fun annotateImage(imageBytes: ByteArray): Map<String, Float> {
        val image = BitmapFactory.decodeByteArray(imageBytes,0,imageBytes.size)
        this.cropAndRescaleBitmap(image, mCroppedBitmap)
        val pixels = this.getPixels(mCroppedBitmap)

        val confidencePerLabel = arrayOf(ByteArray(this.mLabels.size))

        mInferenceInterface.run(pixels, confidencePerLabel)

        // Get the results with the highest confidence and map them to their labels
        return this.getBestResults(confidencePerLabel[0], this.mLabels)
    }

    private fun getBestResults(outputs: ByteArray, labels: List<String>): Map<String, Float> {
        val annotations = hashMapOf<String, Float>()
        // Find the best classifications.
        val pq = PriorityQueue(MAX_BEST_RESULTS,
                Comparator<Pair<String, Float>> { lhs, rhs -> java.lang.Float.compare(rhs.second, lhs.second) })

        (0..(outputs.size - 1))
                .filter { outputs[it] > RESULT_CONFIDENCE_THRESHOLD }
                .mapTo(pq) {
                    val confidence: Float = (outputs[it] and 0xFF.toByte()) / 255.0f
                    Pair(labels[it], confidence)
                }

        val recognitionsSize = Math.min(pq.size, MAX_BEST_RESULTS)
        for (i in 0 until recognitionsSize) {
            val recognition = pq.poll()
            val key = recognition.first
            val confidence = recognition.second

            Log.d(ContentValues.TAG, "$key - $confidence")

            val parents = mInvertedIndex[key]
            if (parents != null) {
                for (p in parents){
                    if(annotations.containsKey(p)){
                        val partialConfidence = annotations[p]!!
                        annotations[p] = partialConfidence + confidence
                    }else{
                        annotations[p] = confidence
                    }
                }
            }
            annotations[key] = confidence
        }

        return annotations
    }

    override fun destroy() {
        mInferenceInterface.close()
    }

    private fun cropAndRescaleBitmap(src: Bitmap, dst: Bitmap) {
        //Assert.assertEquals(dst.width, dst.height)
        val minDim = Math.min(src.width, src.height).toFloat()

        val matrix = Matrix()

        // We only want the center square out of the original rectangle.
        val translateX = -Math.max(0f, (src.width - minDim) / 2)
        val translateY = -Math.max(0f, (src.height - minDim) / 2)
        matrix.preTranslate(translateX, translateY)

        val scaleFactor = dst.height / minDim
        matrix.postScale(scaleFactor, scaleFactor)

        val canvas = Canvas(dst)
        canvas.drawBitmap(src, matrix, null)
    }
}