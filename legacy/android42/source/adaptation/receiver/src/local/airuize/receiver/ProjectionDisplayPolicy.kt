package local.airuize.receiver

import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings

data class ProjectionResolution(val width:Int,val height:Int) {
    val label:String get()="${width}×${height}"
    val valid:Boolean get()=width in 640..1920 && height in 360..1080 && width%2==0 && height%2==0
    fun applyTo(saved:ReceiverSettings):ReceiverSettings {
        require(valid) { "Unsupported projection dimensions" }
        return saved.copy(width=width,height=height,scale=10)
    }
}

/** Presets are explicit choices. Detecting the screen never silently changes a working session. */
object ProjectionDisplayPolicy {
    val fallback=ProjectionResolution(800,480)
    fun matchingScreen(width:Int,height:Int):ProjectionResolution? =
        ProjectionResolution(maxOf(width,height),minOf(width,height)).takeIf { it.valid }
    fun options(width:Int,height:Int,current:ProjectionResolution):List<ProjectionResolution> =
        listOfNotNull(matchingScreen(width,height),fallback,ProjectionResolution(960,540),ProjectionResolution(1024,600),ProjectionResolution(1280,720),current.takeIf {it.valid}).distinct()
    // Keep legacy custom 30..60 rates; 15fps is an explicit additional API17 option.
    fun fps(value:Int):Int=if(value==15) 15 else AirPlayDisplaySettings.sanitizeFps(value)
    fun restore(saved:ReceiverSettings)=fallback.applyTo(saved).copy(fps=30,physicalMm=200,physicalHeight=false,
        decoderPolicy="auto",videoOutput="surface",hevc=false)
}
