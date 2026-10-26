package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test
import com.shilapi.xcertplay.transport.IphoneUsbException
import java.io.ByteArrayOutputStream

class Api17UsbCompatibilityTest {
    @Test fun largeAndShortWritesPreserveEveryByteInOrder() {
        val source=ByteArray(40000) { (it*37).toByte() }
        val received=ByteArrayOutputStream()
        var calls=0
        UsbBulkWriter.writeAll(source, 1500) { bytes, size, timeout ->
            assertEquals(1500,timeout)
            assertEquals(size,bytes.size)
            assertTrue(size in 1..16384)
            val count=if(calls++%2==0) minOf(size,1023) else size
            received.write(bytes,0,count)
            count
        }
        assertTrue(calls>3)
        assertArrayEquals(source,received.toByteArray())
    }
    @Test fun failedOrImpossibleTransfersTerminateInsteadOfLoopingOrLosingBytes() {
        for (outcome in listOf(-1,0,9)) {
            var calls=0
            try {
                UsbBulkWriter.writeAll(ByteArray(8),1000) { _,_,_-> calls++;outcome }
                fail("Invalid USB result accepted")
            } catch (_: IphoneUsbException.DeviceUnavailable) { assertEquals(1,calls) }
        }
    }
    @Test fun emptyWritesDoNotTouchUsbAndTimeoutMustBePositive() {
        UsbBulkWriter.writeAll(byteArrayOf(),1000) { _,_,_-> fail("Empty write reached USB");0 }
        try { UsbBulkWriter.writeAll(byteArrayOf(1),0) { _,_,_->1 };fail("Zero timeout accepted") }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun unknownMockFlagDoesNotBecomeVerifiedVehicleGps() {
        assertFalse(GpsDiagnostics.freshGps("gps",30.0,110.0,null,100))
        assertTrue(GpsDiagnostics.freshGps("gps",30.0,110.0,false,100))
        assertTrue(GpsDiagnostics.describe("gps",100,null,null,false,false).contains("未知"))
    }
}
