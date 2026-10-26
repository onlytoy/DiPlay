package com.shilapi.xcertplay.transport
import android.hardware.usb.*
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** SDK17 has no timed requestWait; bounded bulk reads avoid an uncancellable wait. */
class Iap2UsbSession internal constructor(private val connection:UsbDeviceConnection,
    private val outEndpoint:UsbEndpoint,private val inEndpoint:UsbEndpoint,
    private val releaseConnection:()->Unit={connection.close()}) : Closeable {
    private val closed=AtomicBoolean(false)
    private val readLock=Any(); private val writeLock=Any()
    fun write(data:ByteArray,timeoutMillis:Int) = synchronized(writeLock) {
        check(!closed.get()); require(timeoutMillis>0)
        // API17 has only the bulkTransfer overload without an offset argument.
        local.airuize.receiver.UsbBulkWriter.writeAll(data, timeoutMillis) { chunk, size, timeout ->
            check(!closed.get())
            connection.bulkTransfer(outEndpoint,chunk,size,timeout)
        }
    }
    fun read(timeoutMillis:Long):ByteArray? = synchronized(readLock) {
        check(!closed.get()); require(timeoutMillis>0)
        val bytes=ByteArray(16384)
        val count=connection.bulkTransfer(inEndpoint,bytes,bytes.size,timeoutMillis.coerceIn(1,1000).toInt())
        if(closed.get()) throw IphoneUsbException.DeviceUnavailable("USBMUX closed")
        if(count>0) bytes.copyOf(count) else null
    }
    override fun close() { if(closed.compareAndSet(false,true)) releaseConnection() }
}
