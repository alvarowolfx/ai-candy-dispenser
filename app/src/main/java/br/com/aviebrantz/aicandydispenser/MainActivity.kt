package br.com.aviebrantz.aicandydispenser

import android.app.Activity
import android.content.ContentValues.TAG
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

class MainActivity : Activity() {

    enum class GameState {
        WAITING_TENSORFLOW, WAITING_PLAYER, WAITING_PHOTO, WAITING_RECLAIM_PRIZE, TIMEOUT
    }

    private val CANDY_PIN = "BCM27"
    private var mCandyMachine: CandyMachine? = null

    private val BUTTON_PIN = "BCM20"
    private var mButton: Button? = null

    private val LCD_I2C_BUS = "I2C1"
    private var mDisplay: Display? = null

    private lateinit var mCamera: CandyCamera
    private var mAnnotations = mapOf<String, Float>()

    private lateinit var mLedStrip: LedStrip
    private val LED_STRIP_BUS = "SPI0.0"

    private val LABELS = listOf(
            "DOG","CAT","FISH",
            "CAR", "FROG","BEAR",
            "ANT", "ZEBRA","MONKEY",
            "PINEAPPLE", "BANANA",
            "ORANGE", "STRAWBERRY",
            //"AIRPLANE", "LAPTOP",
            //"COW","SHEEP",
            "BEE","LION","PENGUIN",
            "BIRD", "RABBIT", "ELEPHANT",
            //"FOOD",
            "FLOWER")

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
        //setContentView(R.layout.activity_main)

        mDisplay = Display(LCD_I2C_BUS)
        mButton = Button(BUTTON_PIN, Button.LogicState.PRESSED_WHEN_LOW)
        mButton?.setOnButtonEventListener(mButtonEventListener)
        mCandyMachine = CandyMachine(CANDY_PIN)

        raffleLabel()

        mCameraThread = HandlerThread("CameraBackgroundThread")
        mCameraThread.start()
        mCameraHandler = Handler(mCameraThread.looper)

        mCamera = CandyCamera.getInstance()
        //mCamera.dumpFormatInfo(this)
        mCamera.initializeCamera(this, mCameraHandler, mOnImageAvailableListener)

        mClassifyThread= HandlerThread("ClassifyThread")
        mClassifyThread.start()
        mClassifyHandler = Handler(mClassifyThread.looper)


        mLedStrip = LedStrip(LED_STRIP_BUS)
        mLedStrip.start()

        updateGameState(WAITING_TENSORFLOW)

        mImageClassifierService = TensorflowImageClassifierService(this)

        updateGameState(WAITING_PLAYER)
    }

    fun updateGameState(nextGameState: GameState) {
        Log.d(TAG, nextGameState.name)
        when(nextGameState){
            WAITING_TENSORFLOW -> {
                mLedStrip.setMode(LedStrip.LedMode.INIT)
            }
            WAITING_PLAYER -> {
                mLedStrip.setMode(LedStrip.LedMode.RAINBOW)
            }
            WAITING_PHOTO -> {
                mLedStrip.setMode(LedStrip.LedMode.WAITING_PHOTO)
                mAnnotations = mapOf()
                raffleLabel()
                startCountdown()
                startAnalyzing()
            }
            WAITING_RECLAIM_PRIZE -> {
                mLedStrip.setMode(LedStrip.LedMode.WIN)
                startResetCountdown(10000)
            }
            TIMEOUT -> {
                mLedStrip.setMode(LedStrip.LedMode.TIMEOUT)
                startResetCountdown()
            }
        }
        mGameState = nextGameState
        mDisplay?.clear()
        runOnUiThread {
            renderGameState()
        }
    }

    private fun startAnalyzing() {
        mCamera.takePicture()
    }

    private fun renderGameState() {
        when (mGameState) {
            WAITING_TENSORFLOW -> {
                mDisplay?.print(1, "A.I. Candy Dispenser")
                mDisplay?.printCenter(2, "")
                mDisplay?.printCenter(3, "Creating time-loop")
                mDisplay?.printCenter(4, "inversion field")
            }
            WAITING_PLAYER -> {
                mDisplay?.print(1, "A.I. Candy Dispenser")
                mDisplay?.printCenter(2, "")
                mDisplay?.printCenter(3, "Press the Button")
                mDisplay?.printCenter(4, "To start")
            }
            WAITING_PHOTO -> {
                mDisplay?.printCenter(1, "Show a $mCurrentLabel")
                val secs = "${(mRemainingMillis / 1000)} seconds"
                mDisplay?.printCenter(2, secs)

                if (mAnnotations.isEmpty()) {
                    mDisplay?.printCenter(4, "Not found yet")
                } else {
                    val desc = mAnnotations.keys.first()
                    mDisplay?.printCenter(4, "$desc found")
                }
            }
            WAITING_RECLAIM_PRIZE -> {
                val secs = "${(mRemainingMillis / 1000)} seconds to claim"
                mDisplay?.printCenter(1, "AEHOOO, You Won")
                mDisplay?.printCenter(2, "$mCurrentLabel found")
                mDisplay?.printCenter(4, secs)
            }
            TIMEOUT -> {
                mDisplay?.printCenter(1, "Time's up :(")
                mDisplay?.printCenter(3, "$mCurrentLabel not found")
            }
        }

    }

    private val mButtonEventListener = { _: Button, pressed: Boolean ->
        Log.d(TAG, "Button is pressed: $pressed")
        if(pressed){
            when(mGameState){
                WAITING_PLAYER -> {
                    updateGameState(WAITING_PHOTO)
                }
                WAITING_RECLAIM_PRIZE -> {
                    mCandyMachine?.giveCandies()
                    mTimer?.cancel()
                    updateGameState(WAITING_PLAYER)
                }
                WAITING_TENSORFLOW, WAITING_PHOTO, TIMEOUT -> {
                    // Do nothing
                }
            }
        }
    }

    private val mOnImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        try {
            val image = reader.acquireLatestImage()
            val imageBuffer = image.planes[0].buffer
            val imageBytes = ByteArray(imageBuffer.remaining())
            imageBuffer.get(imageBytes)
            image.close()

            onPictureTaken(imageBytes)
        } catch(e: Exception){
            Log.d(TAG, e.message)
        }
    }

    private fun onPictureTaken(imageBytes: ByteArray){
        Log.d(TAG, "Picture Taken with " + imageBytes.size)

        mClassifyHandler.post({
            Log.d(TAG, "sending image to cloud vision")
            try {
                mAnnotations = mImageClassifierService.annotateImage(imageBytes)

                Log.d(TAG, "finished image analyses")

                for ((desc, value) in mAnnotations) {
                    Log.d(TAG, "$desc - $value")
                }

            }catch(e: Exception){
                Log.d(TAG, e.message)
                e.printStackTrace()
                mAnnotations = hashMapOf<String, Float>()
                        .plus(Pair(e.message!!, 0.0f))
            }finally {
                if(checkIfWon() && mRemainingMillis > 0){
                    updateGameState(WAITING_RECLAIM_PRIZE)
                }else if(mRemainingMillis > 0){
                    mClassifyHandler.post {
                        renderGameState()
                        mCamera.takePicture()
                    }
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

    private fun raffleLabel(){
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

        mLedStrip.close()
    }
}
