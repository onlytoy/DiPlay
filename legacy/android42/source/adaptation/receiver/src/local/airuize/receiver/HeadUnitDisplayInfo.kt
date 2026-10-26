package local.airuize.receiver

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager

data class HeadUnitDisplayInfo(val width:Int,val height:Int,val availableWidth:Int,val availableHeight:Int,
    val densityDpi:Int,val density:Float,val realMetrics:Boolean) {
    fun summary()="系统报告屏幕=${width}×${height}；应用可用=${availableWidth}×${availableHeight}；densityDpi=$densityDpi；density=$density；API17 realMetrics=$realMetrics"
    companion object {
        fun capture(context:Context):HeadUnitDisplayInfo {
            val fallback=context.resources.displayMetrics
            val app=DisplayMetrics().apply { setTo(fallback) }
            val panel=DisplayMetrics().apply { setTo(fallback) }
            var real=false
            try {
                val display=(context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
                display.getMetrics(app)
                try { display.getRealMetrics(panel);real=true } catch(_:Exception) {panel.setTo(app)}
            } catch(_:Exception) { /* OEM frameworks may omit a usable default display. */ }
            return HeadUnitDisplayInfo(panel.widthPixels,panel.heightPixels,app.widthPixels,app.heightPixels,
                panel.densityDpi,panel.density,real)
        }
    }
}
