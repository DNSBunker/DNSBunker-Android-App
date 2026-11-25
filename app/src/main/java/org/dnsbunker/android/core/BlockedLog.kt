package org.dnsbunker.android.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class BlockedEntry(val domain: String, val category: DnsWire.BlockCategory, val count: Int, val lastSeen: Long)

object BlockedLog {
    const val LIMIT = 200
    val entries = MutableStateFlow<List<BlockedEntry>>(emptyList())
    @Volatile var enabled = false
        set(value) { field = value; if (!value) clear() }

    fun record(domain: String, category: DnsWire.BlockCategory, now: Long = System.currentTimeMillis()) {
        val name = domain.trimEnd('.').lowercase(java.util.Locale.ROOT)
        if (!enabled || name.isEmpty() || category == DnsWire.BlockCategory.NONE) return
        entries.update { list ->
            val old = list.firstOrNull { it.domain == name }
            val next = BlockedEntry(name, category, (old?.count ?: 0) + 1, now)
            (listOf(next) + list.filterNot { it.domain == name }).take(LIMIT)
        }
    }

    fun clear() { entries.value = emptyList() }
}
