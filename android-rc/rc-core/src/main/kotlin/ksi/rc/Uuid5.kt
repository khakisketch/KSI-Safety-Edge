package ksi.rc

import java.security.MessageDigest

/**
 * RFC 4122 version-5 (SHA-1 name-based) UUIDs.
 *
 * Ported for MOP identifier parity: the Python side derives message ids as
 * `uuid5(NAMESPACE, "|".join(parts))` with
 * `NAMESPACE = uuid5(NAMESPACE_DNS, "ksi-safety-edge-harness")`
 * (`harness/src/ksi_harness/mop.py`). Cross-language golden tests pin the
 * exact bytes.
 */
object Uuid5 {
    /** RFC 4122 DNS namespace (6ba7b810-9dad-11d1-80b4-00c04fd430c8). */
    val DNS_NAMESPACE: java.util.UUID =
        java.util.UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

    fun of(namespace: java.util.UUID, name: String): java.util.UUID {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(uuidBytes(namespace))
        md.update(name.toByteArray(Charsets.UTF_8))
        val hash = md.digest()
        hash[6] = ((hash[6].toInt() and 0x0F) or 0x50).toByte() // version 5
        hash[8] = ((hash[8].toInt() and 0x3F) or 0x80).toByte() // RFC 4122 variant
        var msb = 0L
        var lsb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (hash[i].toLong() and 0xFF)
        for (i in 8 until 16) lsb = (lsb shl 8) or (hash[i].toLong() and 0xFF)
        return java.util.UUID(msb, lsb)
    }

    private fun uuidBytes(uuid: java.util.UUID): ByteArray {
        val out = ByteArray(16)
        var msb = uuid.mostSignificantBits
        var lsb = uuid.leastSignificantBits
        for (i in 7 downTo 0) {
            out[i] = (msb and 0xFF).toByte()
            msb = msb ushr 8
        }
        for (i in 15 downTo 8) {
            out[i] = (lsb and 0xFF).toByte()
            lsb = lsb ushr 8
        }
        return out
    }
}
