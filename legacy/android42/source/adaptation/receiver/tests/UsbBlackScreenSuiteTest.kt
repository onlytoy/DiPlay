package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class UsbBlackScreenSuiteTest {
    private class Fixture {
        var time=0L
        var stopped=true
        var invokeStopCompletion=true
        val starts=mutableListOf<String>()
        val saved=mutableListOf<String>()
        val done=mutableListOf<String>()
        val scheduled=mutableListOf<Pair<Long,()->Unit>>()
        val suite=UsbBlackScreenSuite({ delay,action -> scheduled.add(time+delay to action);Unit },{time},{stopped},
            { after -> if(invokeStopCompletion) after() },{ starts.add(it.id) },{ trial,visible,_ -> "${trial.id} visible=$visible" },
            { text,_ -> saved.add(text);Unit },{ _,_-> },{ done.add(it);Unit })
        fun advance(milliseconds:Long) {
            val target=time+milliseconds
            while(true) {
                val next=scheduled.filter { it.first<=target }.minByOrNull { it.first } ?: break
                scheduled.remove(next);time=next.first;next.second()
            }
            time=target
        }
    }
    @Test fun allTrialsSaveBeforeCompletionAndRestoreTransientOverride() {
        val f=Fixture();f.suite.begin()
        f.advance(90000+60000*3L)
        assertEquals(listOf("A","B","C","D"),f.starts)
        assertEquals(5,f.saved.size);assertEquals(1,f.done.size)
        assertFalse(f.suite.running);assertNull(BlackScreenDiagnostics.current)
    }
    @Test fun cancellationKeepsPartialReportAndStaleTimeoutCannotAdvanceAnotherRun() {
        val f=Fixture();f.suite.begin();f.advance(1000);f.suite.cancel()
        assertTrue(f.saved.first().contains("INTERRUPTED"));assertNull(BlackScreenDiagnostics.current)
        f.suite.begin();f.advance(89000)
        assertEquals(listOf("A","A"),f.starts) // Old A timeout cannot finish the new A.
        f.suite.seen();assertEquals("B",f.starts.last())
        assertTrue(f.saved.any { it.contains("visible=true") });f.suite.cancel()
    }
    @Test fun missingTeardownStopsSuiteWithoutStartingOverlappingUsbService() {
        val f=Fixture();f.stopped=false;f.suite.begin();f.advance(15000)
        assertTrue(f.starts.isEmpty());assertFalse(f.suite.running)
        assertTrue(f.done.single().contains("未释放"));assertNull(BlackScreenDiagnostics.current)
    }
    @Test fun missingStopCallbackStillTimesOutAndPreservesDiagnosticCapture() {
        val f=Fixture();f.stopped=false;f.invokeStopCompletion=false
        f.suite.begin();f.advance(15000)
        assertFalse(f.suite.running);assertTrue(f.starts.isEmpty())
        assertEquals(1,f.done.size)
        assertTrue(f.saved.single().contains("A visible=false"))
    }
    @Test fun nextTrialWaitsUntilTransportReallyReportsStopped() {
        val f=Fixture();f.stopped=false;f.suite.begin();f.advance(1000)
        assertTrue(f.starts.isEmpty())
        f.stopped=true;f.advance(250)
        assertEquals(listOf("A"),f.starts)
        f.suite.cancel()
    }
}
