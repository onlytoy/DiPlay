package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VideoBackpressureTest {
    @Test(timeout=4000) fun transientCongestionWaitsForSpaceAndPreservesReferenceOrder() {
        val queue=VideoBackpressure(1,1000)
        assertTrue(queue.offer(byteArrayOf(1)))
        val offered=CountDownLatch(1);val started=CountDownLatch(1)
        val worker=Thread {started.countDown();if(queue.offer(byteArrayOf(2))) offered.countDown()}
        worker.start()
        try {
            assertTrue(started.await(1,TimeUnit.SECONDS))
            assertFalse(offered.await(30,TimeUnit.MILLISECONDS))
            assertArrayEquals(byteArrayOf(1),queue.poll(100))
            assertTrue(offered.await(1,TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(2),queue.poll(100))
            assertNull(queue.poll(1))
        } finally {worker.interrupt();worker.join(1000)}
    }
    @Test(timeout=3000) fun sustainedCongestionIsBoundedAndDoesNotReplaceAnEarlierFrame() {
        val queue=VideoBackpressure(1,20)
        assertTrue(queue.offer(byteArrayOf(1)))
        assertFalse(queue.offer(byteArrayOf(2)))
        assertArrayEquals(byteArrayOf(1),queue.poll(20))
        queue.clear();assertNull(queue.poll(1))
    }
}
