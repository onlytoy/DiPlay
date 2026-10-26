package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class BlackScreenDiagnosticsTest {
    @Test fun softwareTrialNeverSilentlyUsesHardwareDecoder() {
        val names=listOf("OMX.vendor.avc","OMX.google.h264.decoder")
        assertEquals(listOf("OMX.google.h264.decoder"),BlackScreenDiagnostics.decoders(names,"software"))
        assertTrue(BlackScreenDiagnostics.decoders(listOf("OMX.vendor.avc"),"software").isEmpty())
        assertEquals(names,BlackScreenDiagnostics.decoders(names,"auto"))
    }
    @Test fun trialOverridesLeaveSavedSettingsUntouched() {
        val saved=ReceiverSettings(width=1024,height=600,fps=60,scale=8,hevc=true)
        for(trial in BlackScreenDiagnostics.trials) {
            val active=trial.apply(saved)
            assertFalse(active.hevc);assertEquals(10,active.scale)
            assertEquals(trial.width,active.display().widthPixels)
            assertEquals(trial.fps,active.display().fps)
        }
        assertEquals(1024,saved.width);assertTrue(saved.hevc);assertEquals(60,saved.fps)
    }
    @Test fun renderedBuffersAreNotMistakenForHumanVisibleSuccess() {
        assertTrue(BlackScreenDiagnostics.diagnosis(0,0,0,false).contains("未收到视频"))
        assertTrue(BlackScreenDiagnostics.diagnosis(10,0,0,false).contains("解码器未启动"))
        assertTrue(BlackScreenDiagnostics.diagnosis(10,1,0,false).contains("没有输出缓冲"))
        assertTrue(BlackScreenDiagnostics.diagnosis(10,1,10,false).contains("仍需用户确认"))
        assertTrue(BlackScreenDiagnostics.diagnosis(10,1,10,true).contains("用户确认"))
    }
}
