package local.airuize.receiver

import com.shilapi.xcertplay.transport.IphoneUsbException

/** Copy bounded slices for API17, preserving byte order across short USB writes. */
object UsbBulkWriter {
    fun writeAll(data: ByteArray, timeoutMillis: Int, transfer: (ByteArray, Int, Int) -> Int) {
        require(timeoutMillis > 0)
        var offset = 0
        while (offset < data.size) {
            val size = minOf(16384, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + size)
            val count = transfer(chunk, size, timeoutMillis)
            if (count <= 0 || count > size)
                throw IphoneUsbException.DeviceUnavailable("USBMUX bulk write failed: count=$count requested=$size")
            offset += count
        }
    }
}
