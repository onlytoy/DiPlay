package local.airuize.receiver

import android.location.Location

/** API17 cannot attest the mock flag; unknown fixes are never sent as verified GPS. */
object LegacyLocationFlags {
    fun mockStatus(location: Location): Boolean? = try {
        location.javaClass.getMethod("isFromMockProvider").invoke(location) as? Boolean
    } catch (_: Exception) { null }
}
