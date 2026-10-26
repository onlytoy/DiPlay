package local.airuize.receiver

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout

/** Two API17 rendering targets; never send callbacks from an inactive target to the decoder. */
class LegacyVideoTarget(context:Context,private val changed:(String)->Unit):FrameLayout(context) {
    private val plane=SurfaceView(context)
    private val texture=TextureView(context)
    private var textureSurface:Surface?=null
    private var planeReady=false
    var textureUpdates=0L; private set
    var mode="surface"; private set
    val targetSurface:Surface? get()=(if(mode=="texture") textureSurface else if(planeReady) plane.holder.surface else null)?.takeIf { it.isValid }
    init {
        addView(plane,LayoutParams(-1,-1));addView(texture,LayoutParams(-1,-1));texture.visibility=View.GONE
        plane.holder.addCallback(object:SurfaceHolder.Callback {
            override fun surfaceCreated(holder:SurfaceHolder) { planeReady=true;if(mode=="surface") changed("Surface创建") }
            override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int) { if(mode=="surface") changed("Surface尺寸 ${width}x$height") }
            override fun surfaceDestroyed(holder:SurfaceHolder) { planeReady=false;if(mode=="surface") changed("Surface销毁") }
        })
        texture.surfaceTextureListener=object:TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(value:SurfaceTexture,width:Int,height:Int) {
                textureSurface?.release();textureSurface=Surface(value)
                if(mode=="texture") changed("Texture创建 ${width}x$height hardware=$isHardwareAccelerated")
            }
            override fun onSurfaceTextureSizeChanged(value:SurfaceTexture,width:Int,height:Int) { if(mode=="texture") changed("Texture尺寸 ${width}x$height") }
            override fun onSurfaceTextureDestroyed(value:SurfaceTexture):Boolean {
                val old=textureSurface;textureSurface=null
                if(mode=="texture") changed("Texture销毁")
                old?.release();return true
            }
            override fun onSurfaceTextureUpdated(value:SurfaceTexture) { textureUpdates++ /* Not proof of a visible picture. */ }
        }
    }
    fun select(requested:String) {
        val next=if(requested=="texture") "texture" else "surface"
        if(next==mode) return
        mode=next
        plane.visibility=if(mode=="surface") View.VISIBLE else View.GONE
        texture.visibility=if(mode=="texture") View.VISIBLE else View.GONE
        changed("显示目标切换=$mode hardware=$isHardwareAccelerated")
    }
}
