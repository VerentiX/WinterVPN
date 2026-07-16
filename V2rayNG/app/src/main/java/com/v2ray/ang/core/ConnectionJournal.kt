package com.v2ray.ang.core

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/** In-memory, opt-in diagnostics. No connection history is written to disk. */
object ConnectionJournal {
    private const val MAX_ENTRIES = 250

    data class Entry(
        val app: String,
        val packageName: String,
        val network: String,
        val destination: String,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val entries = ConcurrentLinkedDeque<Entry>()

    fun record(app: String, packageName: String, network: String, destination: String) {
        entries.addFirst(Entry(app, packageName, network, destination))
        while (entries.size > MAX_ENTRIES) entries.pollLast()
    }

    fun clear() = entries.clear()

    fun summary(): String {
        val snapshot = entries.toList()
        if (snapshot.isEmpty()) return "Журнал пока пуст. Откройте приложение, которое создаёт трафик."
        val formatter = DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.getDefault())
        val grouped = snapshot.groupBy { "${it.app}\n${it.destination} (${it.network.uppercase()})" }
        return grouped.entries.sortedByDescending { it.value.size }.joinToString("\n\n") { (key, flows) ->
            "$key\nСоединений: ${flows.size} · последнее: ${formatter.format(Date(flows.maxOf { it.timestamp }))}"
        }
    }
}
