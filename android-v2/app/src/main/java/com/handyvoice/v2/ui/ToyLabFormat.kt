package com.handyvoice.v2.ui

import com.handyvoice.v2.api.ToyDiagnostics

/** One ended toy link, kept in prefs so the owner can read it hours later. */
data class ToyDisconnectRecord(
    val atMs: Long,
    val connectedMs: Long,
    val idleMs: Long,
    val unexpected: Boolean,
)

/** Android-free formatting and storage helpers for the Lovense test panel. */
object ToyLabFormat {

    const val HISTORY_MAX = 5

    /** 0 to 59 s gives "12 s", under an hour "45 min", else "1 h 12 min". Negative counts as 0. */
    fun duration(ms: Long): String {
        val totalS = (ms.coerceAtLeast(0L)) / 1000
        if (totalS < 60) return "$totalS s"
        val totalMin = totalS / 60
        if (totalMin < 60) return "$totalMin min"
        return "${totalMin / 60} h ${totalMin % 60} min"
    }

    fun ago(nowMs: Long, thenMs: Long): String = "${duration(nowMs - thenMs)} ago"

    /** The record for a link that just ended, or null if the toy never finished connecting. */
    fun recordFrom(d: ToyDiagnostics?): ToyDisconnectRecord? {
        val at = d?.disconnectedAtMs ?: return null
        val connectedAt = d.connectedAtMs ?: return null
        val lastActive = d.lastMotionAtMs ?: connectedAt
        return ToyDisconnectRecord(
            atMs = at,
            connectedMs = (at - connectedAt).coerceAtLeast(0),
            idleMs = (at - lastActive).coerceAtLeast(0),
            unexpected = d.disconnectUnexpected == true,
        )
    }

    // Stored as lines "at|connectedMs|idleMs|U" (U unexpected, u user), newest first.

    fun encodeHistory(records: List<ToyDisconnectRecord>): String =
        records.joinToString("\n") { "${it.atMs}|${it.connectedMs}|${it.idleMs}|${if (it.unexpected) "U" else "u"}" }

    fun parseHistory(raw: String): List<ToyDisconnectRecord> =
        raw.lineSequence().mapNotNull { line ->
            val p = line.split("|")
            if (p.size != 4) return@mapNotNull null
            val at = p[0].toLongOrNull() ?: return@mapNotNull null
            val c = p[1].toLongOrNull() ?: return@mapNotNull null
            val i = p[2].toLongOrNull() ?: return@mapNotNull null
            ToyDisconnectRecord(at, c, i, p[3] == "U")
        }.toList()

    /** Adds [rec] as the newest entry and keeps the last [HISTORY_MAX]. */
    fun appendHistory(raw: String, rec: ToyDisconnectRecord): String =
        encodeHistory((listOf(rec) + parseHistory(raw)).take(HISTORY_MAX))

    /** "Nov 22 14:03  dropped, connected 1 h 12 min, idle 45 min". [date] formats the timestamp. */
    fun describe(rec: ToyDisconnectRecord, date: (Long) -> String): String =
        "${date(rec.atMs)}  ${if (rec.unexpected) "dropped (unexpected)" else "disconnected (by you)"}, " +
            "connected ${duration(rec.connectedMs)}, idle ${duration(rec.idleMs)}"
}
