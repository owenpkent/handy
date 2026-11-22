package com.handyvoice.v2.api

import java.util.UUID

/**
 * Android-free parts of the Lovense BLE protocol: level mapping, command
 * strings, reply parsing and GATT UUID derivation. The GATT plumbing lives in
 * [LovenseBleTransport].
 */
object LovenseProtocol {

    const val MAX_LEVEL = 20

    // Gen 1 (FFF0 service).
    const val GEN1_SERVICE = "0000fff0-0000-1000-8000-00805f9b34fb"
    const val GEN1_TX = "0000fff2-0000-1000-8000-00805f9b34fb"
    const val GEN1_RX = "0000fff1-0000-1000-8000-00805f9b34fb"

    // Gen 2 (Nordic UART).
    const val GEN2_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val GEN2_TX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    const val GEN2_RX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

    // Gen 3: xx300001-002y-4bd4-bbd5-a6920e4c5653, TX = ...300002..., RX = ...300003...
    private val GEN3_REGEX = Regex("^[0-9a-f]{2}300001-002[0-9a-f]-4bd4-bbd5-a6920e4c5653$")

    const val CMD_DEVICE_TYPE = "DeviceType;"
    const val CMD_BATTERY = "Battery;"
    /**
     * (sic) Lovense spells it "AutoSwith". The WRITE command takes words:
     * first = stop on accidental disconnect (On), second = restore the last
     * level on reconnect (Off). The numeric form (1:0) is only the [CMD_GET_AUTO_SWITCH] reply.
     */
    const val CMD_AUTO_SWITCH = "AutoSwith:On:Off;"
    const val CMD_GET_AUTO_SWITCH = "GetAS;"
    const val CMD_STOP = "Vibrate:0;"

    /** Candidate command names the test panel may probe. Vibrate is the baseline. */
    val PROBE_NAMES = listOf("Vibrate", "Oscillate", "Vibrate1", "Vibrate2", "Rotate", "Thrusting")

    /** `<Name>:<N>;` for an allow-listed name and N in 0..MAX_LEVEL, else null. */
    fun probeCommand(name: String, level: Int): String? =
        if (name in PROBE_NAMES && level in 0..MAX_LEVEL) "$name:$level;" else null

    /** Parses an allow-listed probe command to (name, level); null for anything else. */
    fun parseProbe(cmd: String): Pair<String, Int>? {
        if (!cmd.endsWith(";")) return null
        val parts = cmd.removeSuffix(";").split(":")
        if (parts.size != 2) return null
        val level = parts[1].toIntOrNull() ?: return null
        if (level.toString() != parts[1]) return null // canonical digits only
        return if (probeCommand(parts[0], level) == cmd) parts[0] to level else null
    }

    fun isAllowedProbe(cmd: String): Boolean = parseProbe(cmd) != null

    /** Velocity 0..100 to level 0..20, rounded, but any non-zero velocity is at least level 1. */
    fun levelForVelocity(velocity: Int): Int {
        val v = velocity.coerceIn(0, 100)
        if (v == 0) return 0
        val level = Math.round(v * MAX_LEVEL / 100.0).toInt()
        return level.coerceIn(1, MAX_LEVEL)
    }

    fun vibrate(level: Int): String = "Vibrate:${level.coerceIn(0, MAX_LEVEL)};"

    /** Advertised names for Lovense toys start with LVS- or LOVE-. */
    fun isLovenseName(name: String?): Boolean =
        name != null && (name.startsWith("LVS-") || name.startsWith("LOVE-"))

    fun isGen3Service(uuid: String): Boolean = GEN3_REGEX.matches(uuid.lowercase())

    /** TX characteristic UUID for a gen 3 service UUID. */
    fun gen3Tx(service: String): String = service.lowercase().replaceRange(2, 8, "300002")

    /** RX characteristic UUID for a gen 3 service UUID. */
    fun gen3Rx(service: String): String = service.lowercase().replaceRange(2, 8, "300003")

    /** The (service, tx, rx) UUIDs for a service UUID, or null if it is not a Lovense service. */
    fun channelFor(service: UUID): Triple<UUID, UUID, UUID>? {
        val s = service.toString().lowercase()
        return when {
            s == GEN1_SERVICE -> Triple(service, UUID.fromString(GEN1_TX), UUID.fromString(GEN1_RX))
            s == GEN2_SERVICE -> Triple(service, UUID.fromString(GEN2_TX), UUID.fromString(GEN2_RX))
            isGen3Service(s) -> Triple(service, UUID.fromString(gen3Tx(s)), UUID.fromString(gen3Rx(s)))
            else -> null
        }
    }

    /** "85;" gives 85. Anything else gives null. */
    fun parseBattery(reply: String): Int? {
        val body = reply.trim().removeSuffix(";")
        if (body.isEmpty() || !body.all { it.isDigit() }) return null
        return body.toIntOrNull()?.takeIf { it in 0..100 }
    }

    data class AutoSwitch(val stopOnDisconnect: Boolean, val restoreOnReconnect: Boolean) {
        /** The safe state we write: stop on link loss, never resume by itself. */
        val isLinkLossSafe: Boolean get() = stopOnDisconnect && !restoreOnReconnect
    }

    /** "1:0;" or "AutoSwith:1:0;" gives (true, false). Anything else gives null. */
    fun parseAutoSwitch(reply: String): AutoSwitch? {
        val body = reply.trim().removeSuffix(";").removePrefix("AutoSwith:")
        val parts = body.split(":")
        if (parts.size != 2 || parts.any { it != "0" && it != "1" }) return null
        return AutoSwitch(parts[0] == "1", parts[1] == "1")
    }

    /** "ERR;" style rejection. */
    fun isErrorReply(reply: String): Boolean = reply.trim().uppercase().startsWith("ERR")

    data class DeviceInfo(val model: String, val firmware: String, val address: String)

    /** "C:11:0082059AD3BD;" gives model C, firmware 11, address 0082059AD3BD. */
    fun parseDeviceType(reply: String): DeviceInfo? {
        val parts = reply.trim().removeSuffix(";").split(":")
        if (parts.size != 3 || parts.any { it.isEmpty() }) return null
        return DeviceInfo(parts[0], parts[1], parts[2])
    }

    /**
     * Reassembles `;`-terminated replies that may be split across, or batched
     * into, BLE notifications.
     */
    class ReplyBuffer {
        private val sb = StringBuilder()

        /** Feed a notification chunk; returns each complete reply (with its trailing ';'). */
        fun feed(chunk: String): List<String> {
            sb.append(chunk)
            val out = ArrayList<String>()
            while (true) {
                val i = sb.indexOf(";")
                if (i < 0) break
                out.add(sb.substring(0, i + 1))
                sb.delete(0, i + 1)
            }
            return out
        }

        fun clear() { sb.setLength(0) }
    }
}
