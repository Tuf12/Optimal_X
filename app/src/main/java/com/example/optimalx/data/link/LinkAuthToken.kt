package com.example.optimalx.data.link

// Spec: app/docs/architecture/OPTIMALX_LINK.md §security-model.
//
// Per-session bearer credential for the phone-side HTTP server. Generated with
// [SecureRandom] and surfaced URL-safe base64 (no padding). The token never
// leaves the device unless the user shows it (text or QR) on the Link screen.
//
// The encoder is hand-rolled rather than using `java.util.Base64` (API 26+) or
// `android.util.Base64` (Android-only) so this class remains pure JVM-testable
// while keeping our minSdk 24 floor.

import java.security.SecureRandom

object LinkAuthToken {

    /** Default raw entropy size. 24 random bytes ≈ 192 bits → 32 base64url chars. */
    const val DEFAULT_BYTES: Int = 24

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /**
     * Returns a freshly generated bearer token suitable for the `Authorization`
     * header. Output is URL-safe base64 with no padding so it round-trips
     * through query params, QR payloads, and shell-quoted curl commands.
     *
     * @param byteLength raw entropy in bytes. Must be > 0. Defaults to 24.
     */
    fun generate(byteLength: Int = DEFAULT_BYTES): String {
        require(byteLength > 0) { "byteLength must be positive (was $byteLength)" }
        val buf = ByteArray(byteLength)
        SecureRandom().nextBytes(buf)
        return encodeBase64Url(buf)
    }

    private fun encodeBase64Url(input: ByteArray): String {
        val sb = StringBuilder((input.size * 4 + 2) / 3)
        var i = 0
        while (i < input.size) {
            val b0 = input[i].toInt() and 0xFF
            val b1 = if (i + 1 < input.size) input[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < input.size) input[i + 2].toInt() and 0xFF else -1

            sb.append(ALPHABET[b0 ushr 2])
            if (b1 < 0) {
                sb.append(ALPHABET[(b0 and 0x03) shl 4])
            } else {
                sb.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                if (b2 < 0) {
                    sb.append(ALPHABET[(b1 and 0x0F) shl 2])
                } else {
                    sb.append(ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
                    sb.append(ALPHABET[b2 and 0x3F])
                }
            }
            i += 3
        }
        return sb.toString()
    }
}
