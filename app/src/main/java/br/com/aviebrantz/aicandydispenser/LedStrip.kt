package br.com.aviebrantz.aicandydispenser

import android.animation.ObjectAnimator
import android.content.ContentValues.TAG
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.google.android.things.contrib.driver.apa102.Apa102
import java.io.IOException


/**
 * Created by alvaroviebrantz on 27/09/17.
 */

class LedStrip(spiBus: String): AutoCloseable{

    enum class LedMode {
        INIT, RAINBOW, TIMEOUT, WAITING_PHOTO, WIN
    }

    // LED configuration.
    private val NUM_LEDS = 30
    private val LED_BRIGHTNESS = 10 // 0 ... 31
    private val LED_MODE = Apa102.Mode.BGR

    private var mLedstrip: Apa102? = null
    private var mLedMode = LedMode.RAINBOW
    private var mLedColors = intArrayOf()
    private var mFrame = 0
    private val MAX_FRAMES = 60
    private var mHandler: Handler
    private var mPioThread: HandlerThread = HandlerThread("ledStripThread")

    init {
        mPioThread.start()
        mHandler = Handler(mPioThread.looper)

        mLedColors = IntArray(NUM_LEDS)
        try {
            Log.d(TAG, "Initializing LED strip")
            mLedstrip = Apa102(spiBus, LED_MODE)
            mLedstrip?.brightness = LED_BRIGHTNESS

        } catch (e: IOException) {
            Log.e(TAG, "Error initializing LED strip", e)
        }
    }

    fun start(){
        mHandler.post(mAnimateRunnable)
    }

    private var mAnimateRunnable: Runnable = object : Runnable {
        override fun run() {
            // Animation configuration.
            var frameDelayMs = 16L // 60fps
            when(mLedMode){
                LedMode.INIT -> {
                    frameDelayMs = 32L
                    initAnimation()
                }
                LedMode.RAINBOW -> {
                    frameDelayMs = 64L
                    rainbowAnimation()
                }
                LedMode.WIN -> {
                    rainbowAnimation()
                }
                LedMode.TIMEOUT -> {
                    frameDelayMs = 32L
                    timeoutAnimation()
                }
                LedMode.WAITING_PHOTO -> {
                    frameDelayMs = 32L
                    waitingPhotoAnimation()
                }
            }
            try{
                mLedstrip?.write(mLedColors);
            } catch (e: IOException) {
                Log.e(TAG, "Error while writing to LED strip", e)
            }
            mFrame = (mFrame + 1) % MAX_FRAMES
            mHandler.postDelayed(this, frameDelayMs)
        }
    }

    fun setMode(ledMode: LedMode){
        mLedMode = ledMode
    }

    private fun rainbowAnimation(){
        val hsv = floatArrayOf(1f, 1f, 1f)
        for (i in mLedColors.indices) { // Assigns gradient colors.
            val n = (i + mFrame) % MAX_FRAMES
            hsv[0] = n * 360.0f / MAX_FRAMES
            mLedColors[i] = Color.HSVToColor(0, hsv)
        }
    }

    private fun blinkHue(hue: Float){
        val hsv = floatArrayOf(hue, 1f, 1f)
        for (i in mLedColors.indices) {
            if(mFrame > MAX_FRAMES/2) {
                hsv[2] = ((MAX_FRAMES - mFrame)/(MAX_FRAMES/2.0)).toFloat()
            }else{
                hsv[2] = (mFrame/(MAX_FRAMES/2.0)).toFloat()
            }
            mLedColors[i] = Color.HSVToColor(0, hsv)
        }
    }

    private fun timeoutAnimation(){
        blinkHue(0.0f)
    }

    private fun waitingPhotoAnimation() {
        blinkHue(180.0f)
    }

    private fun initAnimation(){
        blinkHue(130.0f)
    }

    override fun close() {
        mHandler.removeCallbacks(mAnimateRunnable)
        mPioThread.quitSafely()
        Log.d(TAG, "Closing LED strip")
        try {
            mLedstrip?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Exception closing LED strip", e)
        } finally {
            mLedstrip = null
        }
    }
}