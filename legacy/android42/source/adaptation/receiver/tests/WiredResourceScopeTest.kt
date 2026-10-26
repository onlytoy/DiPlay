package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WiredResourceScopeTest {
    private fun await(latch:CountDownLatch) { assertTrue(latch.await(2,TimeUnit.SECONDS)) }
    @Test fun abortsTransportBeforeClosingProtocolAndUsesReverseOwnershipOrder() {
        val scope=WiredResourceScope()
        val calls=java.util.Collections.synchronizedList(ArrayList<String>())
        scope.own(Closeable { calls.add("USB") },"descriptor",true)
        scope.own(Closeable { assertTrue(calls.contains("USB"));calls.add("TLS") },"TLS")
        scope.own(Closeable { calls.add("media") },"media")
        scope.close()
        assertEquals(listOf("USB","media","TLS"),calls)
        assertTrue(scope.released)
    }
    @Test fun concurrentCloseWaitsForActualReleaseAndClosesOnlyOnce() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val firstDone=CountDownLatch(1);val secondDone=CountDownLatch(1);val calls=AtomicInteger()
        val scope=WiredResourceScope()
        scope.own(Closeable { calls.incrementAndGet();entered.countDown();release.await() },"blocked")
        val first=Thread { scope.close();firstDone.countDown() }
        val second=Thread { scope.close();secondDone.countDown() }
        first.start();await(entered);second.start()
        try {
            assertFalse(scope.released)
            assertTrue(scope.summary().contains("blocked"))
            assertFalse(secondDone.await(100,TimeUnit.MILLISECONDS))
        } finally { release.countDown();first.join(2000);second.join(2000) }
        await(firstDone);await(secondDone)
        assertEquals(1,calls.get());assertTrue(scope.released)
    }
    @Test fun stuckUsbAbortCannotPreventIndependentTunAbort() {
        val usb=CountDownLatch(1);val tun=CountDownLatch(1);val release=CountDownLatch(1)
        val scope=WiredResourceScope()
        scope.own(Closeable { usb.countDown();release.await() },"USB",true)
        scope.own(Closeable { tun.countDown() },"TUN",true)
        val closer=Thread { scope.close() };closer.start()
        try { await(usb);await(tun);assertFalse(scope.released) }
        finally { release.countDown();closer.join(2000) }
        assertTrue(scope.released)
    }
    @Test fun lateConstructorIsClosedInsteadOfPublishedToStoppedSession() {
        val scope=WiredResourceScope();scope.close()
        val closed=AtomicInteger()
        try { scope.own(Closeable { closed.incrementAndGet() },"late");fail("Late resource accepted") }
        catch(_:IllegalStateException) {}
        assertEquals(1,closed.get())
    }
    @Test fun closeFailureIsRetainedAndDoesNotDeclareRelease() {
        val scope=WiredResourceScope()
        scope.own(Closeable { throw IOException("synthetic") },"USB")
        scope.close();scope.close()
        assertFalse(scope.released);assertTrue(scope.closeFailed)
        assertTrue(scope.summary().contains("failed=USB"))
    }
    @Test fun failedCloseOfLateConstructorAlsoBlocksReplacement() {
        val scope=WiredResourceScope();scope.close()
        try { scope.own(Closeable { throw IOException("synthetic late close") },"late");fail("Accepted") }
        catch(_:IllegalStateException) {}
        assertTrue(scope.closeFailed);assertFalse(scope.released)
    }
    @Test fun oldScopeCannotCloseResourcesBelongingToNextSession() {
        val old=WiredResourceScope();val next=WiredResourceScope();val closed=AtomicInteger()
        old.close();next.own(Closeable { closed.incrementAndGet() },"next")
        old.close();assertEquals(0,closed.get())
        next.close();assertEquals(1,closed.get())
    }
}
