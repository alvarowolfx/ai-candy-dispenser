package br.com.aviebrantz.aicandydispenser.imageclassifier

import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.vision.v1.Vision
import com.google.api.services.vision.v1.VisionRequestInitializer
import com.google.api.services.vision.v1.model.AnnotateImageRequest
import com.google.api.services.vision.v1.model.BatchAnnotateImagesRequest
import com.google.api.services.vision.v1.model.Feature
import com.google.api.services.vision.v1.model.Image

/**
 * Created by alvaroviebrantz on 15/06/17.
 */
class CloudVisionImageClassifierService: ImageClassifierService{
    
    val CLOUD_VISION_API_KEY = "YOUR_API_KEY_HERE"

    override fun annotateImage(imageBytes: ByteArray): Map<String, Float> {
        // Construct the Vision API instance
        val httpTransport = AndroidHttp.newCompatibleTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()
        val initializer = VisionRequestInitializer(CLOUD_VISION_API_KEY)
        val vision = Vision.Builder(httpTransport, jsonFactory, null)
                .setApplicationName("AI Candy Dispenser")
                .setVisionRequestInitializer(initializer)
                .build()

        // Create the image request
        val imageRequest = AnnotateImageRequest()
        val image = Image()
        image.encodeContent(imageBytes)
        imageRequest.image = image

        // Add the features we want
        val labelDetection = Feature()
        labelDetection.type = "LABEL_DETECTION"
        labelDetection.maxResults = 10

        imageRequest.features = listOf(labelDetection)

        // Batch and execute the request
        val requestBatch = BatchAnnotateImagesRequest()
        requestBatch.requests = listOf(imageRequest)
        val response = vision.images()
                .annotate(requestBatch)
                .setDisableGZipContent(true)
                .execute()


        val annotations = hashMapOf<String, Float>()
        // Convert response into a readable collection of annotations
        val labels = response.responses[0].labelAnnotations
        if (labels != null) {
            for (label in labels) {
                annotations.put(label.description, label.score)
            }
        }

        return annotations
    }

    override fun destroy() {

    }
}