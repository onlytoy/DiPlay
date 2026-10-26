package local.airuize.receiver

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import org.junit.Assert.*
import org.junit.Test

class ProjectionDisplayPolicyTest {
    @Test fun nativePresetMatchesReportedLandscapeAndIsNotDuplicated() {
        val native=ProjectionResolution(1024,600)
        val options=ProjectionDisplayPolicy.options(1024,600,native)
        assertEquals(native,options.first());assertEquals(1,options.count {it==native})
        assertTrue(options.contains(ProjectionResolution(800,480)))
    }
    @Test fun portraitMetricsAreNormalizedForLandscapeProjection() {
        assertEquals(ProjectionResolution(1024,600),ProjectionDisplayPolicy.matchingScreen(600,1024))
    }
    @Test fun invalidOrOversizedReportedScreenDoesNotAdvertiseAnInvalidPreset() {
        for((width,height) in listOf(0 to 0,4096 to 2160,1023 to 599)) {
            assertNull(ProjectionDisplayPolicy.matchingScreen(width,height))
            assertTrue(ProjectionDisplayPolicy.options(width,height,ProjectionResolution(0,0)).all {it.valid})
        }
    }
    @Test fun existingCustomResolutionRemainsSelectable() {
        val custom=ProjectionResolution(1152,664)
        assertTrue(ProjectionDisplayPolicy.options(1024,600,custom).contains(custom))
    }
    @Test fun selectingResolutionRestoresFullPixelScaleAndPreservesOtherChoices() {
        val saved=ReceiverSettings(width=800,height=480,scale=6,fps=15,physicalMm=250,
            volume=65,decoderPolicy="software",videoOutput="texture",microphone=false)
        val changed=ProjectionResolution(1024,600).applyTo(saved)
        assertEquals(1024,changed.display().widthPixels);assertEquals(600,changed.display().heightPixels)
        assertEquals(15,changed.fps);assertEquals(250,changed.physicalMm)
        assertEquals(65,changed.volume);assertFalse(changed.microphone)
        assertEquals("software",changed.decoderPolicy);assertEquals("texture",changed.videoOutput)
        assertEquals(6,saved.scale);assertEquals(800,saved.width)
    }
    @Test fun invalidChoiceIsRejectedBeforeItCanReachPreferences() {
        try {ProjectionResolution(4096,2160).applyTo(ReceiverSettings());fail("Invalid size accepted")}
        catch(_:IllegalArgumentException) {}
    }
    @Test fun restoreReturnsTheHumanConfirmedBaselineWithoutResettingAudio() {
        val restored=ProjectionDisplayPolicy.restore(ReceiverSettings(width=1280,height=720,fps=60,scale=8,
            physicalMm=300,physicalHeight=true,decoderPolicy="software",videoOutput="texture",hevc=true,volume=65))
        assertEquals(800,restored.display().widthPixels);assertEquals(480,restored.display().heightPixels)
        assertEquals(30,restored.fps);assertEquals(200,restored.physicalMm);assertFalse(restored.physicalHeight)
        assertFalse(restored.hevc);assertEquals("auto",restored.decoderPolicy);assertEquals("surface",restored.videoOutput)
        assertEquals(65,restored.volume)
    }
    @Test fun fifteenFpsReachesBothDisplayAndIphoneAdvertisement() {
        val display=ReceiverSettings(width=1024,height=600,fps=15).display()
        assertEquals(15,display.fps)
        val config=AirPlayConfig("Test","02:00:00:00:00:01","02:00:00:00:00:02","320.20",display)
        val entries=AirPlayInfoPlist.build(config)["displays"] as List<*>
        assertEquals(15,(entries.first() as Map<*,*>)["maxFPS"])
    }
    @Test fun existingFrameRateRulesAndDefaultsRemainCompatible() {
        assertEquals(15,ProjectionDisplayPolicy.fps(15))
        assertEquals(30,ProjectionDisplayPolicy.fps(-1));assertEquals(30,ProjectionDisplayPolicy.fps(20))
        assertEquals(45,ProjectionDisplayPolicy.fps(45));assertEquals(60,ProjectionDisplayPolicy.fps(100))
        assertEquals(30,ReceiverSettings().fps)
    }
}
