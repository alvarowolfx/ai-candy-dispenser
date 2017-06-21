package br.com.aviebrantz.aicandydispenser.imageclassifier

import android.content.ContentValues
import android.content.Context
import org.tensorflow.contrib.android.TensorFlowInferenceInterface
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import android.media.ThumbnailUtils
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.Log
import junit.framework.Assert
import br.com.aviebrantz.aicandydispenser.CandyCamera
import java.util.*


/**
 * Created by alvaroviebrantz on 15/06/17.
 */
class TensorflowImageClassifierService(ctx: Context): ImageClassifierService{

    val IMAGE_SIZE = 224
    private val IMAGE_MEAN = 117
    private val IMAGE_STD = 1f
    private val LABELS_FILE = "imagenet_comp_graph_label_strings.txt"
    private val INVERTED_INDEX_FILE = "inverted_index.txt"
    val MODEL_FILE = "file:///android_asset/tensorflow_inception_graph.pb"
    val INPUT_NAME = "input:0"
    val OUTPUT_OPERATION = "output"
    val OUTPUT_NAME = OUTPUT_OPERATION + ":0"
    val OUTPUT_NAMES = arrayOf(OUTPUT_NAME)
    val NETWORK_STRUCTURE = longArrayOf(1, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong(), 3)
    val NUM_CLASSES = 1008

    private val MAX_BEST_RESULTS = 10
    private val RESULT_CONFIDENCE_THRESHOLD = 0.1f

    private val mInferenceInterface: TensorFlowInferenceInterface
    private val mLabels: List<String>
    private val mInvertedIndex: Map<String, List<String>>

    private val  mCroppedBitmap: Bitmap
    private var mRgbFrameBitmap: Bitmap

    init {
        mInferenceInterface = TensorFlowInferenceInterface(ctx.assets, MODEL_FILE)
        mLabels = readLabels(ctx)
        mInvertedIndex = readInvertedIndex(ctx)

        mCroppedBitmap = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE,
                Bitmap.Config.ARGB_8888)
        mRgbFrameBitmap = Bitmap.createBitmap(CandyCamera.IMAGE_WIDTH,
                CandyCamera.IMAGE_HEIGHT, Bitmap.Config.ARGB_8888)
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

                result.put(key, values.split(", "))

                line = bufferedReader.readLine()
            }
            return result
        } catch (ex: IOException) {
            throw IllegalStateException("Cannot read labels from " + LABELS_FILE)
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
            throw IllegalStateException("Cannot read labels from " + LABELS_FILE)
        }
    }

    fun getPixels(bitmap: Bitmap): FloatArray {
        var mutBitmap = bitmap
        if (mutBitmap.width != IMAGE_SIZE || mutBitmap.height != IMAGE_SIZE) {
            // rescale the bitmap if needed
            mutBitmap = ThumbnailUtils.extractThumbnail(bitmap, IMAGE_SIZE, IMAGE_SIZE)
        }
        val intValues = IntArray(IMAGE_SIZE * IMAGE_SIZE)

        bitmap.getPixels(intValues, 0, mutBitmap.width, 0, 0, mutBitmap.width, mutBitmap.height)

        val floatValues = FloatArray(IMAGE_SIZE * IMAGE_SIZE * 3)
        // Preprocess the image data from 0-255 int to normalized float based
        // on the provided parameters.
        for (i in intValues.indices) {
            val value = intValues[i]
            floatValues[i * 3] = ((value shr 16 and 0xFF) - IMAGE_MEAN) / IMAGE_STD
            floatValues[i * 3 + 1] = ((value shr 8 and 0xFF) - IMAGE_MEAN) / IMAGE_STD
            floatValues[i * 3 + 2] = ((value and 0xFF) - IMAGE_MEAN) / IMAGE_STD
        }
        return floatValues
    }

    override fun annotateImage(imageBytes: ByteArray): Map<String, Float> {
        val image = BitmapFactory.decodeByteArray(imageBytes,0,imageBytes.size)
        this.cropAndRescaleBitmap(image, mCroppedBitmap)
        val pixels = this.getPixels(mCroppedBitmap)

        // Feed the pixels of the image into the TensorFlow Neural Network
        mInferenceInterface.feed(INPUT_NAME, pixels, *NETWORK_STRUCTURE)

        // Run the TensorFlow Neural Network with the provided input
        mInferenceInterface.run(OUTPUT_NAMES)

        // Extract the output from the neural network back into an array of confidence per category
        val outputs = FloatArray(NUM_CLASSES)
        mInferenceInterface.fetch(OUTPUT_NAME, outputs)

        // Get the results with the highest confidence and map them to their labels
        return this.getBestResults(outputs, this.mLabels)
    }

    private fun getBestResults(outputs: FloatArray, labels: List<String>): Map<String, Float> {
        val annotations = hashMapOf<String, Float>()
        // Find the best classifications.
        val pq = PriorityQueue(MAX_BEST_RESULTS,
                Comparator<Pair<String, Float>> { lhs, rhs -> java.lang.Float.compare(rhs.second, lhs.second) })

        (0..(outputs.size - 1))
                .filter { outputs[it] > RESULT_CONFIDENCE_THRESHOLD }
                .mapTo(pq) { Pair(labels[it], outputs[it]) }

        val recognitionsSize = Math.min(pq.size, MAX_BEST_RESULTS)
        for (i in 0..recognitionsSize - 1) {
            val recognition = pq.poll()
            val key = recognition.first
            val confidence = recognition.second

            Log.d(ContentValues.TAG, "$key - $confidence")

            val parents = mInvertedIndex[key]
            if (parents != null) {
                for (p in parents){
                    if(annotations.containsKey(p)){
                        val partialConfidence = annotations[p]!!
                        annotations.put(p, partialConfidence + confidence)
                    }else{
                        annotations.put(p, confidence)
                    }
                }
            }
            annotations.put(key, confidence)
        }

        return annotations
    }

    override fun destroy() {
        mInferenceInterface.close()
    }

    fun cropAndRescaleBitmap(src: Bitmap, dst: Bitmap) {
        Assert.assertEquals(dst.width, dst.height)
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