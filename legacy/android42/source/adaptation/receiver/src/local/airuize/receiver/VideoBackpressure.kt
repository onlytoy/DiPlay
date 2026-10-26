package local.airuize.receiver

/** v0.2.86–0.2.94: bounded waiting preserves reference frames through short CPU spikes.
 * Sustained overload remains bounded and requests an IDR after discarding queued frames.
 */
class VideoBackpressure(private val capacity:Int=16,private val waitMillis:Long=120) {
    private val frames=java.util.concurrent.ArrayBlockingQueue<ByteArray>(capacity)
    fun offer(frame:ByteArray)=frames.offer(frame,waitMillis,java.util.concurrent.TimeUnit.MILLISECONDS)
    fun poll(timeout:Long)=frames.poll(timeout,java.util.concurrent.TimeUnit.MILLISECONDS)
    fun clear()=frames.clear()
}
