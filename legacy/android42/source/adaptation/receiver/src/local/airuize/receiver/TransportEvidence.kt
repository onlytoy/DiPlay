package local.airuize.receiver

/** Keep startup plus recent structural evidence independently of noisy live logs. */
class TransportEvidence {
    private val startup=StringBuilder()
    private val recent=java.util.ArrayDeque<String>()
    private var recentSize=0
    @Synchronized fun append(message:String) {
        val safe=DiagnosticRedactor.clean(message).take(12000)+"\n"
        if(startup.length+safe.length<=12000 && recent.isEmpty()) startup.append(safe)
        else {
            recent.addLast(safe);recentSize+=safe.length
            while(recentSize>72000 && recent.size>1) recentSize-=recent.removeFirst().length
        }
    }
    @Synchronized fun snapshot():String= startup.toString()+
        (if(recent.isEmpty()) "" else "\nRecent transport evidence (bounded):\n"+recent.joinToString(""))
    companion object {
        fun readPrevious(file:java.io.File):String = try {
            if(file.isFile && file.length()<=300000) DiagnosticRedactor.clean(file.readText(Charsets.UTF_8)) else ""
        } catch(_:Exception) { "" }
        fun relevant(message:String)=!message.startsWith("iAP2 rx") &&
            (message.startsWith("Kernel audio") || message.startsWith("NCM") || message.startsWith("USB") ||
                message.startsWith("Firmware path:") || message.contains("音频") ||
                message.startsWith("Video") || message.contains("视频") || message.startsWith("Receive: video") || message.startsWith("Audio") || message.startsWith("Receive: audio") || message.startsWith("AirPlay") ||
                message.contains("会话") || message.contains("PCM") || message.contains("RTP"))
    }
}
