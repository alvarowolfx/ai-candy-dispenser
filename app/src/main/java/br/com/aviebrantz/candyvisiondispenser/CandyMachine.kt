package br.com.aviebrantz.candyvisiondispenser

import android.os.CountDownTimer
import com.google.android.things.pio.Gpio
import com.google.android.things.pio.PeripheralManagerService

/**
 * Created by alvaroviebrantz on 07/05/17.
 */
class CandyMachine(gpio: String): AutoCloseable{

    private val CANDY_TIMEOUT = 3*1000L
    private var mCandyPin: Gpio? = null
    private var mCandiesTimer: CountDownTimer? = null

    init{
        val service = PeripheralManagerService()
        mCandyPin = service.openGpio(gpio)
        mCandyPin?.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW)
        mCandyPin?.setActiveType(Gpio.ACTIVE_HIGH)
    }

    fun giveCandies() {
        mCandyPin?.value = true
        mCandiesTimer?.cancel()
        mCandiesTimer = object: CountDownTimer(CANDY_TIMEOUT, CANDY_TIMEOUT) {
            override fun onTick(remainingMillis: Long) {
            }

            override fun onFinish() {
                mCandyPin?.value = false
            }
        }
        mCandiesTimer?.start()
    }

    override fun close() {
        mCandyPin?.value = false
        mCandiesTimer?.cancel()
        mCandyPin?.close()
    }
}
