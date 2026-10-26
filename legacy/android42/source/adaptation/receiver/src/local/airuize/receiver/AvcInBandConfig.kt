package local.airuize.receiver

/** v0.2.94 API17 fix: some decoders ignore csd-0/csd-1 and need in-band SPS/PPS.
 * Keep the prefix until MediaCodec actually accepts the first access unit.
 */
class AvcInBandConfig(sps:ByteArray,pps:ByteArray) {
    private var prefix:ByteArray?=byteArrayOf(0,0,0,1)+sps+byteArrayOf(0,0,0,1)+pps
    init {require(sps.isNotEmpty() && pps.isNotEmpty())}
    fun prepare(annexB:ByteArray):ByteArray=prefix?.let {it+annexB} ?: annexB
    fun queued() {prefix=null}
}
