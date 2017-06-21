package br.com.aviebrantz.aicandydispenser

import android.app.Activity
import android.content.ContentValues.TAG
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ImageReader
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import br.com.aviebrantz.aicandydispenser.MainActivity.GameState.*
import br.com.aviebrantz.aicandydispenser.imageclassifier.ImageClassifierService
import br.com.aviebrantz.aicandydispenser.imageclassifier.TensorflowImageClassifierService
import com.google.android.things.contrib.driver.button.Button
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {

    enum class GameState {
        WAITING_PLAYER, WAITING_PHOTO, ANALYZING_PHOTO, WAITING_RECLAIM_PRIZE, SHOW_RESULTS, TIMEOUT
    }

    private val CANDY_PIN = "BCM20"
    private var mCandyMachine: CandyMachine? = null

    private val BUTTON_PIN = "BCM26"
    private var mButton: Button? = null

    private val LCD_I2C_BUS = "I2C1"
    private var mDisplay: Display? = null

    private lateinit var mCamera: CandyCamera
    private var mAnnotations = mapOf<String, Float>()

    private val LABELS = listOf(
            "DOG","CAT","FISH","SHEEP",
            "CAR","AIRPLANE", "LAPTOP",
            "COW","BEE","LION","PENGUIN",
            "BIRD", "RABBIT", "ELEPHANT",
            "FOOD", "FLOWER")

    private val DEFAULT_TIMEOUT = 30*1000L
    private var mCurrentLabel = ""
    private var mWonCurrentGame = false
    private var mRemainingMillis = 0L
    private var mTimer: CountDownTimer? = null

    private var mGameState = WAITING_PLAYER

    private lateinit var mCameraHandler: Handler
    private lateinit var mCameraThread: HandlerThread

    private lateinit var mClassifyHandler: Handler
    private lateinit var mClassifyThread: HandlerThread

    private lateinit var mImageClassifierService: ImageClassifierService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mDisplay = Display(LCD_I2C_BUS)
        mButton = Button(BUTTON_PIN, Button.LogicState.PRESSED_WHEN_LOW)
        mButton?.setOnButtonEventListener(mButtonEventListener)
        mCandyMachine = CandyMachine(CANDY_PIN)

        sortLabel()

        mCameraThread = HandlerThread("CameraBackgroundThread")
        mCameraThread.start()
        mCameraHandler = Handler(mCameraThread.looper)

        mCamera = CandyCamera.getInstance()
        //mCamera.dumpFormatInfo(this)
        mCamera.initializeCamera(this, mCameraHandler, mOnImageAvailableListener)

        mClassifyThread= HandlerThread("ClassifyThread")
        mClassifyThread.start()
        mClassifyHandler = Handler(mClassifyThread.looper)

        mImageClassifierService = TensorflowImageClassifierService(this)

        updateGameState(WAITING_PLAYER)
    }

    fun updateGameState(nextGameState: GameState) {
        Log.d(TAG, nextGameState.name)
        when(nextGameState){
            WAITING_PLAYER -> {

            }
            WAITING_PHOTO -> {
                sortLabel()
                startCountdown()
            }
            ANALYZING_PHOTO -> {
                stopCountdown()
                mCamera.takePicture()
            }
            WAITING_RECLAIM_PRIZE -> {
                startResetCountdown(10000)
            }
            SHOW_RESULTS, TIMEOUT -> {
                startResetCountdown()
            }
        }
        mGameState = nextGameState
        mDisplay?.clear()
        mDisplay?.print(1, "A.I. Candy Dispenser")
        renderGameState()
    }

    private fun renderGameState() {
        when(mGameState){
            WAITING_PLAYER -> {
                mDisplay?.printCenter(2, "")
                mDisplay?.printCenter(3, "Press the Button")
                mDisplay?.printCenter(4, "To start")
            }
            WAITING_PHOTO -> {
                val secs = "${(mRemainingMillis/1000)} seconds"
                mDisplay?.printCenter(2, secs)
                mDisplay?.printCenter(3, "Please, show me a")
                mDisplay?.printCenter(4, mCurrentLabel)
            }
            ANALYZING_PHOTO -> {
                mDisplay?.printCenter(2, "")
                mDisplay?.printCenter(3, "Hummm...")
                mDisplay?.printCenter(4, "Let me see...")
            }
            SHOW_RESULTS -> {
                var idx = 2
                for( (desc, _) in mAnnotations) {
                    mDisplay?.printCenter(idx, "$desc found")
                    idx++
                    if(idx > 4) break
                }
            }
            WAITING_RECLAIM_PRIZE -> {
                val secs = "${(mRemainingMillis/1000)} seconds to claim"
                mDisplay?.printCenter(2, "AEHOOO, You Won")
                mDisplay?.printCenter(3, mCurrentLabel + " found")
                mDisplay?.printCenter(4, secs)
            }
            TIMEOUT -> {
                mDisplay?.printCenter(2, "")
                mDisplay?.printCenter(3, "Time's up :(")
                mDisplay?.printCenter(4, mCurrentLabel + " not found")
            }
        }
    }

    val mButtonEventListener = { _: Button, pressed: Boolean ->
        Log.d(TAG, "Button is pressed: " + pressed)
        if(pressed){
            when(mGameState){
                WAITING_PLAYER -> {
                    updateGameState(WAITING_PHOTO)
                }
                WAITING_PHOTO -> {
                    updateGameState(ANALYZING_PHOTO)
                }
                WAITING_RECLAIM_PRIZE -> {
                    mCandyMachine?.giveCandies()
                    updateGameState(WAITING_PLAYER)
                }
            }
        }
    }

    val mOnImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage()
        val imageBuffer = image.planes[0].buffer
        val imageBytes = ByteArray(imageBuffer.remaining())
        imageBuffer.get(imageBytes)
        image.close()

        onPictureTaken(imageBytes)
    }

    private fun onPictureTaken(imageBytes: ByteArray){
        Log.d(TAG, "Picture Taken with " + imageBytes.size)
        val bitmap = BitmapFactory.decodeByteArray(imageBytes,0,imageBytes.size)
        val matrix = Matrix()
        matrix.postRotate(180f)

        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0,0, bitmap.width, bitmap.height, matrix,true)
        /*
        DEBUG - Save to file
        val out = this.openFileOutput("test.jpeg", Context.MODE_PRIVATE)
        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
        out.close()
        */

        val stream = ByteArrayOutputStream()
        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
        val rotatedImageBytes = stream.toByteArray()


        mClassifyHandler.post({
            Log.d(TAG, "sending image to cloud vision")
            try {
                mAnnotations = mImageClassifierService.annotateImage(rotatedImageBytes)

                Log.d(TAG, "finished image analyses")

                for ((desc, value) in mAnnotations) {
                    Log.d(TAG, "$desc - $value")
                }

            }catch(e: Exception){
                Log.d(TAG, e.message)
                mAnnotations = hashMapOf<String, Float>()
                        .plus(Pair(e.message!!, 0.0f))
            }finally {
                if(checkIfWon()){
                    updateGameState(WAITING_RECLAIM_PRIZE)
                }else{
                    updateGameState(SHOW_RESULTS)
                }
            }
        })
    }

    private fun checkIfWon(): Boolean {
        for ((desc, _) in mAnnotations) {
            if(desc.contains(mCurrentLabel,true)){
                mWonCurrentGame = true
                return true
            }
        }
        return false
    }

    private fun startCountdown() {
        mTimer?.cancel()
        mTimer = object: CountDownTimer(DEFAULT_TIMEOUT, 1000) {
            override fun onTick(remainingMillis: Long) {
                mRemainingMillis = remainingMillis
                renderGameState()
            }

            override fun onFinish() {
                Log.d(TAG, "Finished countdown")
                mRemainingMillis = 0
                updateGameState(TIMEOUT)
            }
        }
        mTimer?.start()
    }

    private fun stopCountdown() {
        mTimer?.cancel()
    }

    private fun startResetCountdown(timeout: Long = 5000L) {
        mTimer?.cancel()
        mRemainingMillis = timeout
        mTimer = object: CountDownTimer(timeout, 1000) {
            override fun onTick(remainingMillis: Long) {
                mRemainingMillis = remainingMillis
                renderGameState()
            }

            override fun onFinish() {
                mRemainingMillis = 0
                updateGameState(WAITING_PLAYER)
            }
        }
        mTimer?.start()
    }

    fun sortLabel(){
        var newLabel:String
        do {
            val idx = (Math.random() * LABELS.size).toInt()
            newLabel = LABELS[idx]
        }while (newLabel == mCurrentLabel)
        mCurrentLabel = newLabel
    }


    override fun onStop() {
        super.onStop()
        mTimer?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()

        mDisplay?.close()
        mButton?.close()
        mCandyMachine?.close()

        mCameraThread.quitSafely()
        mClassifyThread.quitSafely()

        mCamera.close()
    }
}
