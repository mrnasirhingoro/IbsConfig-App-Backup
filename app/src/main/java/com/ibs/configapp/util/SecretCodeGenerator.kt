package com.ibs.configapp.util

import java.security.MessageDigest

object SecretCodeGenerator {

    fun fromDeviceId(deviceId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(deviceId.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
            .take(6)
            .uppercase()
    }
}
