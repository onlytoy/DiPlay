package local.airuize.receiver

/** Small ARM EABI bridge, invoked only for wired USB interface activation. */
object LegacyUsbNative {
    private val loaded by lazy { System.loadLibrary("diplay_usb"); true }
    private external fun setInterface(fd:Int, interfaceId:Int, alternate:Int):Int
    private external fun setConfiguration(fd:Int, configurationId:Int):Int
    fun configure(fd:Int, configurationId:Int):Int {
        check(loaded)
        return setConfiguration(fd,configurationId)
    }
    fun select(fd:Int, interfaceId:Int, alternate:Int):Int {
        check(loaded)
        return setInterface(fd,interfaceId,alternate)
    }
}
