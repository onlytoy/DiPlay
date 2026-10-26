package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class AvcInBandConfigTest {
    @Test fun api17FirstFrameCarriesSpsPpsBeforeIdrEvenWhenInputWasTemporarilyUnavailable() {
        val sps=byteArrayOf(0x67,0x42,0,0x1e);val pps=byteArrayOf(0x68,1,2)
        val frame=byteArrayOf(0,0,0,1,0x65,7,8)
        val expected=byteArrayOf(0,0,0,1)+sps+byteArrayOf(0,0,0,1)+pps+frame
        val config=AvcInBandConfig(sps,pps)
        sps[0]=0 // Configuration owns an immutable copy.
        assertArrayEquals(expected,config.prepare(frame))
        assertArrayEquals(expected,config.prepare(frame)) // A stalled input did not consume SPS/PPS.
        config.queued()
        assertSame(frame,config.prepare(frame))
    }
    @Test fun decoderRecreationRestoresItsOwnParameterSets() {
        val frame=byteArrayOf(0,0,0,1,0x65)
        val first=AvcInBandConfig(byteArrayOf(0x67,1),byteArrayOf(0x68,1));first.queued()
        val recreated=AvcInBandConfig(byteArrayOf(0x67,2),byteArrayOf(0x68,2))
        assertEquals(17,recreated.prepare(frame).size)
        assertEquals(2,recreated.prepare(frame)[5].toInt())
        assertSame(frame,first.prepare(frame))
    }
}
