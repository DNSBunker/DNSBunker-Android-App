package org.dnsbunker.android.core

import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException

object ClockCheck {
    private val hints = listOf("certificate time invalid", "timestamp check failed", "certificate expired", "not valid until", "not yet valid")

    fun isTimeError(error: Throwable?): Boolean {
        var current = error
        var depth = 0
        while (current != null && depth++ < 12) {
            if (current is CertificateExpiredException || current is CertificateNotYetValidException) return true
            val message = current.message?.lowercase() ?: ""
            if (hints.any { it in message }) return true
            current = current.cause
        }
        return false
    }
}
