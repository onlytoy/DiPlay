package local.airuize.receiver

data class VideoTrial(val id:String,val title:String,val width:Int=800,val height:Int=480,
    val fps:Int=30,val decoder:String="auto",val output:String="surface") {
    fun apply(settings:ReceiverSettings)=settings.copy(width=width,height=height,fps=fps,scale=10,
        hevc=false,decoderPolicy=decoder,videoOutput=output)
}

/** Session-only overrides: interruption/process death restores the driver's saved settings. */
object BlackScreenDiagnostics {
    val trials=listOf(
        VideoTrial("A","A 标准解码 / Surface / 800×480"),
        VideoTrial("B","B 软件解码 / Surface / 800×480",decoder="software"),
        VideoTrial("C","C 标准解码 / Texture / 800×480",output="texture"),
        VideoTrial("D","D 低分辨率 / Texture / 640×480",width=640,output="texture")
    )
    @Volatile var current:VideoTrial?=null
    fun software(name:String)=name.startsWith("OMX.google.",true) || name.startsWith("c2.android.",true) || name.contains("ffmpeg",true)
    fun decoders(names:List<String>,policy:String)=if(policy=="software") names.filter(::software) else names
    fun diagnosis(received:Long,started:Long,released:Long,visible:Boolean)=when {
        visible -> "用户确认看到画面"
        received==0L -> "未收到视频帧：检查会话、视频协商和NCM传输"
        started==0L -> "收到视频但解码器未启动：检查视频配置、Surface和解码器错误"
        released==0L -> "解码器已启动但没有输出缓冲：检查关键帧、码流和解码兼容"
        else -> "已释放显示缓冲，仍需用户确认画面是否可见"
    }
}

object DeviceVideoEvidence {
    fun snapshot(context:android.content.Context):String=buildString {
        append("设备检测（本机读取，不保证识破固件伪装）\n")
        append("appVersion=").append(runCatching {context.packageManager.getPackageInfo(context.packageName,0).versionName}.getOrDefault("unknown")).append('\n')
        append("deviceDate=").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",java.util.Locale.US).format(java.util.Date())).append('\n')
        append("Android=").append(android.os.Build.VERSION.RELEASE).append(" SDK=").append(android.os.Build.VERSION.SDK_INT)
        append(" ABI=").append(android.os.Build.CPU_ABI).append('/').append(android.os.Build.CPU_ABI2).append('\n')
        append("manufacturer=").append(android.os.Build.MANUFACTURER).append(" model=").append(android.os.Build.MODEL)
        append(" board=").append(android.os.Build.BOARD).append(" hardware=").append(android.os.Build.HARDWARE)
        append(" build=").append(android.os.Build.DISPLAY).append('\n')
        val metrics=context.resources.displayMetrics
        append("screen=").append(metrics.widthPixels).append('x').append(metrics.heightPixels).append(" density=").append(metrics.density).append('\n')
        append(HeadUnitDisplayInfo.capture(context).summary()).append('\n')
        val selected=ReceiverSettings.read(context);val display=selected.display()
        append("Selected projection (next connection): raw=").append(selected.width).append('x').append(selected.height)
            .append(" scale=").append(selected.scale).append(" effective=").append(display.widthPixels).append('x').append(display.heightPixels)
            .append(" fps=").append(display.fps).append(" physicalMm=").append(display.widthPhysicalMm).append('x').append(display.heightPhysicalMm).append('\n')
        val offsetUsb=runCatching { android.hardware.usb.UsbDeviceConnection::class.java.getMethod("bulkTransfer",
            android.hardware.usb.UsbEndpoint::class.java,ByteArray::class.java,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType) }.isSuccess
        append("API18 bulkTransfer offset method present=").append(offsetUsb).append("（本包使用API17无offset实现）\n")
        for(mime in listOf("video/avc","video/hevc")) {
            append("decoder ").append(mime).append('=').append(CodecCapabilities.names(mime).joinToString()).append('\n')
        }
        try {
            for(index in 0 until android.media.MediaCodecList.getCodecCount()) {
                val codec=android.media.MediaCodecList.getCodecInfoAt(index)
                if(!codec.isEncoder && codec.supportedTypes.any { it.equals("video/avc",true) }) {
                    append("AVC capabilities ").append(codec.name).append(": ")
                    val caps=codec.getCapabilitiesForType("video/avc")
                    append(caps.profileLevels.joinToString { "profile=${it.profile}/level=${it.level}" }).append('\n')
                }
            }
        } catch(error:Exception) { append("AVC capabilities unavailable=").append(error.javaClass.simpleName).append('\n') }
        val manager=context.getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
        for(device in runCatching { manager.deviceList.values.toList() }.getOrDefault(emptyList())) {
            append("USB vid=").append(device.vendorId).append(" pid=").append(device.productId)
            append(" interfaces=").append(device.interfaceCount).append(" permission=").append(manager.hasPermission(device)).append('\n')
        }
    }
}
