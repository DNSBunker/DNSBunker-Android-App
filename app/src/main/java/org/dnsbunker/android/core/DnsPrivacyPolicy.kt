package org.dnsbunker.android.core

object DnsPrivacyPolicy {
    private val suffixes = setOf("localhost", "local", "lan", "home", "internal", "home.arpa", "onion", "invalid", "test")
    fun isLocal(name: String, configuredSuffixes: String = ""): Boolean {
        val qname = name.trimEnd('.').lowercase(java.util.Locale.ROOT)
        if (!qname.contains('.')) return true
        val configured = configuredSuffixes.split(',').map { it.trim().trim('.').lowercase(java.util.Locale.ROOT) }.filter { it.isNotEmpty() }
        if ((suffixes + configured).any { qname == it || qname.endsWith(".$it") }) return true
        if (qname.endsWith(".in-addr.arpa")) {
            val octets = qname.removeSuffix(".in-addr.arpa").split('.').reversed().map { it.toIntOrNull() }
            val a = octets.getOrNull(0); val b = octets.getOrNull(1)
            return a == 0 || a == 10 || a == 127 || (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        if (qname.endsWith(".ip6.arpa")) {
            val prefix = qname.removeSuffix(".ip6.arpa").split('.').reversed().joinToString("")
            return prefix.startsWith("fc") || prefix.startsWith("fd") ||
                listOf("fe8", "fe9", "fea", "feb").any(prefix::startsWith) ||
                prefix == "00000000000000000000000000000001" || prefix.all { it == '0' }
        }
        return false
    }
}

object WifiPolicy {
    fun normalizeSsid(raw: String?): String? = raw?.trim('"')?.takeUnless {
        it.isBlank() || it.equals("<unknown ssid>", ignoreCase = true) || it == "0x"
    }
    fun exempt(ssid: String?, configured: Set<String>, lockdown: Boolean): Boolean =
        !lockdown && normalizeSsid(ssid)?.let { it in configured } == true
    fun allowLocal(ssid: String?, configured: Set<String>, consent: Boolean): Boolean =
        consent && normalizeSsid(ssid)?.let { it in configured } == true
}
