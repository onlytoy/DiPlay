package local.airuize.receiver

import com.shilapi.xcertplay.airplay.AirPlayCrypto
import org.bouncycastle.crypto.InvalidCipherTextException
import java.util.concurrent.atomic.AtomicLong

/** Adapted from DiPlay-Legacy-Android v0.2.94 NativeChaCha (#296).
 * Uses our existing API17/freestanding Monocypher library; control/pairing crypto stays pinned.
 */
object LegacyScreenCrypto {
    val opens=AtomicLong()
    val fallbackOpens=AtomicLong()
    private val totalNanos=AtomicLong()
    private val bytes=AtomicLong()
    private val failures=AtomicLong()
    private val loaded=try { System.loadLibrary("diplay_usb");true } catch(_:LinkageError) { false }
    private fun hex(text:String)=ByteArray(text.length/2) { text.substring(it*2,it*2+2).toInt(16).toByte() }
    // RFC8439 2.8.2. The expected tag is independent of either implementation.
    val available:Boolean by lazy {
        if(!loaded) false else try {
            val key=hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
            val nonce=hex("070000004041424344454647")
            val aad=hex("50515253c0c1c2c3c4c5c6c7")
            val plain=("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, " + "sunscreen would be it.").toByteArray(Charsets.US_ASCII)
            val sealed=AirPlayCrypto.chachaSeal(key,nonce,plain,aad)
            val tag=hex("1ae10b594f09e26a7e902ecbd0600691")
            val matches=sealed.copyOfRange(sealed.size-16,sealed.size).contentEquals(tag)
            val buffer=sealed.copyOf()
            val length=nativeOpen(key,nonce,aad,buffer,buffer.size-16)
            matches && length==plain.size && buffer.copyOf(length).contentEquals(plain)
        } catch(_:Exception) { false } catch(_:LinkageError) { false }
    }
    private external fun nativeOpen(key:ByteArray,nonce:ByteArray,aad:ByteArray,data:ByteArray,ciphertextLength:Int):Int
    /** A native authentication rejection is never retried through a different implementation. */
    fun open(key:ByteArray,nonce:ByteArray,data:ByteArray,aad:ByteArray):ByteArray {
        require(key.size==32 && nonce.size==12 && data.size>=16 && data.size<=8*1024*1024 && aad.size<=4096)
        val start=System.nanoTime()
        try {
            if(available) {
                val length=nativeOpen(key,nonce,aad,data,data.size-16)
                if(length == -1) throw InvalidCipherTextException("Video authentication rejected")
                if(length>=0) { check(length==data.size-16);opens.incrementAndGet();bytes.addAndGet(length.toLong());return data.copyOf(length) }
            }
            val result=AirPlayCrypto.chachaOpen(key,nonce,data,aad)
            fallbackOpens.incrementAndGet();bytes.addAndGet(result.size.toLong());return result
        } catch(error:Exception) { failures.incrementAndGet();throw error }
        finally { totalNanos.addAndGet(System.nanoTime()-start) }
    }
    fun summary():String="Video crypto native=$available nativeOpens=${opens.get()} fallbackOpens=${fallbackOpens.get()} bytes=${bytes.get()} decryptUs=${totalNanos.get()/1000} failures=${failures.get()}"
}
