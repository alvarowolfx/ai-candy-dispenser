package br.com.aviebrantz.aicandydispenser.imageclassifier

/**
 * Created by alvaroviebrantz on 07/05/17.
 */

interface ImageClassifierService {
    fun annotateImage(imageBytes: ByteArray): Map<String, Float>
    fun destroy()
}