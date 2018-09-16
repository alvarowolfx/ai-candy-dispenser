package br.com.aviebrantz.aicandydispenser

import nz.geek.android.things.driver.display.CharacterDisplay
import nz.geek.android.things.driver.display.I2cLcdCharacterDisplay

/**
 * Created by alvaroviebrantz on 07/05/17.
 */
class Display(i2cBus: String): AutoCloseable{

    private val LCD_WIDTH = 20
    private val LCD_HEIGHT = 4
    private val mLcd: CharacterDisplay

    init{
        val builder = I2cLcdCharacterDisplay.builder(LCD_WIDTH, LCD_HEIGHT)
                .rs(0)
                .rw(1)
                .e(2)
                .bl(3)
                .data(4, 5, 6, 7)
                .address(7)
                .isPcf8574(true)
                .withBus(i2cBus)
        mLcd = builder.build()
        mLcd.connect()
        mLcd.clearDisplay()
        mLcd.enableBackLight(true)
    }

    fun clear(){
        mLcd.clearDisplay()
    }

    fun print(line: Int, message: String){
        mLcd.print(line, message)
    }

    fun printCenter(line: Int, message: String) {
        var msg = message
        if(message.length > LCD_WIDTH){
            msg = message.substring(0, LCD_WIDTH)
        }
        val messageSize = msg.length
        val remaining = LCD_WIDTH - messageSize
        val padding = remaining/2 + messageSize
        val paddedMessage = msg
                .padStart(padding,' ')
                .padEnd(remaining, ' ')
        mLcd.print(line, paddedMessage)
    }

    override fun close() {
        mLcd.disconnect()
    }

}
