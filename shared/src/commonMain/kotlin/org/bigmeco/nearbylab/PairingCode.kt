package org.bigmeco.nearbylab

import kotlin.io.encoding.Base64

/**
 * Matches the pinned Nearby C++ EncryptionRunner::ToHumanReadableString. Android
 * authenticationDigits uses a different numeric representation of this same token. Never replace
 * verification with a value received over the connection being verified.
 */
object PairingCode {
    fun fromRawToken(token: ByteArray): String {
        require(token.size == 32) { "Expected 32-byte UKEY2 verification token" }
        return Base64.UrlSafe.encode(token).take(5).uppercase()
    }
}
