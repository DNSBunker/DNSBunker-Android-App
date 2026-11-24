package org.dnsbunker.android.core

class TransportPolicy(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class Health(val ready: Boolean = false, val failures: Int = 0, val retryAt: Long = 0, val latency: Long = Long.MAX_VALUE)
    private val states = mutableMapOf<String, Health>()
    @Synchronized fun success(name: String, latency: Long) { states[name] = Health(true, latency = latency) }
    @Synchronized fun failure(name: String) {
        val old = states[name] ?: Health()
        val failures = (old.failures + 1).coerceAtMost(6)
        states[name] = Health(false, failures, nowMillis() + (2_000L shl failures).coerceAtMost(60_000))
    }
    @Synchronized fun state(name: String) = states[name] ?: Health()
    @Synchronized fun canTry(name: String) = nowMillis() >= (states[name]?.retryAt ?: 0)
    private fun quic(name: String) = name.startsWith("DoQ") || name.startsWith("DoH/3")
    @Synchronized fun order(names: List<String>): List<String> {
        val usable = names.filter { canTry(it) && (!quic(it) || state(it).ready) }
        if (usable.isNotEmpty()) return rank(usable)
        return names.filterNot(::quic).sortedBy { state(it).retryAt }.take(2)
    }
    private fun rank(names: List<String>): List<String> = names.sortedBy { name ->
        val h = state(name)
        when {
            h.ready && name.startsWith("DoH/3") -> 0
            h.ready && name.startsWith("DoQ") -> 1
            name == "DoH/2 · IP" -> 2
            name.startsWith("DoH/2") -> 3
            else -> 4
        }
    }
}

data class UnderlayCandidate(val id: Long, val wifi: Boolean, val validated: Boolean,
    val captive: Boolean, val internet: Boolean, val unrestricted: Boolean, val vpn: Boolean = false)
object UnderlayPolicy {
    fun choose(candidates: List<UnderlayCandidate>, current: Long? = null): Long? {
        val usable = candidates.filter { it.internet && it.unrestricted && !it.vpn }
        val open = usable.filterNot { it.captive }
        val pool = open.filter { it.validated }.ifEmpty { open }
        return pool.firstOrNull { it.wifi && it.validated }?.id
            ?: pool.firstOrNull { it.id == current }?.id
            ?: pool.firstOrNull { it.wifi }?.id
            ?: pool.firstOrNull()?.id
            ?: usable.firstOrNull { it.captive }?.id
    }
}
