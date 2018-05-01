package br.com.aviebrantz.aicandydispenser

import android.content.ContentValues.TAG
import android.content.Context
import android.content.Context.CAMERA_SERVICE
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager


/**
 * Created by alvaroviebrantz on 07/05/17.
 */
class CandyCamera: AutoCloseable{

    // Image result processor
    private var mImageReader: ImageReader? = null
    // Active camera device connection
    private var mCameraDevice: CameraDevice? = null
    // Active camera capture session
    private var mCaptureSession: CameraCaptureSession? = null

    companion object InstanceHolder {
        // Camera image parameters (device-specific)
        val IMAGE_WIDTH  = 640
        val IMAGE_HEIGHT = 480
        val MAX_IMAGES   = 1
        val mCamera = CandyCamera()

        fun getInstance(): CandyCamera {
            return InstanceHolder.mCamera
        }
    }

    // Initialize a new camera device connection
    fun initializeCamera(context: Context, backgroundHandler: Handler, imageListener: ImageReader.OnImageAvailableListener){
        val manager = context.getSystemService(CAMERA_SERVICE) as CameraManager
        var camIds = emptyArray<String>()
        try {
            camIds = manager.cameraIdList
        } catch (e: CameraAccessException) {
            Log.d(TAG, "Cam access exception gettings ids", e)
        }
        if(camIds.isEmpty()){
            Log.d(TAG, "No cameras found")
            return
        }

        val id = camIds[0]
        // Initialize image processor
        mImageReader = ImageReader.newInstance(IMAGE_WIDTH, IMAGE_HEIGHT,
                ImageFormat.JPEG, MAX_IMAGES)
        mImageReader?.setOnImageAvailableListener(imageListener, backgroundHandler)
        // Open the camera resource
        try {
            manager.openCamera(id, mStateCallback, backgroundHandler)
        } catch (cae: Exception) {
            Log.d(TAG, "Camera access exception", cae)
        }
    }

    fun takePicture(){
        mCameraDevice?.let { mCameraDevice ->
            mCameraDevice.createCaptureSession(
                    arrayListOf(mImageReader?.surface),
                    mSessionCallback,
                    null)
        }
    }

    private fun triggerImageCapture() {
        val captureBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        captureBuilder.addTarget(mImageReader!!.surface)
        captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        Log.d(TAG, "Session initialized.")
        mCaptureSession!!.capture(captureBuilder.build(), mCaptureCallback, null)

    }

    val mCaptureCallback = object: CameraCaptureSession.CaptureCallback(){

        override fun onCaptureProgressed(session: CameraCaptureSession?, request: CaptureRequest?, partialResult: CaptureResult?) {
            Log.d(TAG, "Partial result")
        }

        override fun onCaptureFailed(session: CameraCaptureSession?, request: CaptureRequest?, failure: CaptureFailure?) {
            Log.d(TAG, "Capture session failed")
        }

        override fun onCaptureCompleted(session: CameraCaptureSession?, request: CaptureRequest?, result: TotalCaptureResult?) {
            session?.close()
            mCaptureSession = null
            Log.d(TAG, "Capture session closed")
        }
    }

    val mSessionCallback = object: CameraCaptureSession.StateCallback(){
        override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession?) {
            Log.w(TAG, "Failed to configure camera")
        }

        override fun onConfigured(cameraCaptureSession: CameraCaptureSession?) {
            if(mCameraDevice == null){
                return;
            }
            mCaptureSession = cameraCaptureSession
            triggerImageCapture()
        }

    }

    val mStateCallback = object: CameraDevice.StateCallback() {
        override fun onError(cameraDevice: CameraDevice, code: Int) {
            Log.d(TAG, "Camera device error, closing")
            cameraDevice.close()
        }

        override fun onOpened(cameraDevice: CameraDevice) {
            Log.d(TAG, "Opened camera.")
            mCameraDevice = cameraDevice
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            Log.d(TAG, "Camera disconnected, closing")
            cameraDevice.close()
        }

        override fun onClosed(camera: CameraDevice) {
            Log.d(TAG, "Closed camera, releasing")
            mCameraDevice = null
        }
    }

    // Close the camera resources
    override fun close() {
        mCameraDevice?.close()
    }

    fun dumpFormatInfo(context: Context) {
        val manager = context.getSystemService(CAMERA_SERVICE) as CameraManager
        var camIds = arrayOf<String>()
        try {
            camIds = manager.cameraIdList
        } catch (e: CameraAccessException) {
            Log.d(TAG, "Cam access exception getting IDs")
        }

        if (camIds.size < 1) {
            Log.d(TAG, "No cameras found")
        }
        val id = camIds[0]
        Log.d(TAG, "Using camera id " + id)
        try {
            val characteristics = manager.getCameraCharacteristics(id)
            val configs = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            for (format in configs!!.outputFormats) {
                Log.d(TAG, "Getting sizes for format: " + format)
                for (s in configs.getOutputSizes(format)) {
                    Log.d(TAG, "\t" + s.toString())
                }
            }
            val effects = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS)
            for (effect in effects!!) {
                Log.d(TAG, "Effect available: " + effect)
            }
        } catch (e: CameraAccessException) {
            Log.d(TAG, "Cam access exception getting characteristics.")
        }

    }
}
