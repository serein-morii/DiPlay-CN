package com.shilapi.xcertplay.transport

/**
 * The report line for each received iAP2 message. Now-playing (0x5001) and route-guidance
 * (0x5201) updates repeat every second or so: together they were over half of a 25,000-line
 * report (#473), and reports cut off by GitHub's size limits lost the lines that mattered (#536,
 * #606). Those two are logged the first [first] times, then once every [every] with a count.
 */
internal class Iap2RxLog(private val first: Int = 5, private val every: Int = 100) {
    private val counts = HashMap<Int, Int>()

    fun line(messageId: Int): String? {
        val id = "0x" + messageId.toString(16).padStart(4, '0')
        if (messageId !in PERIODIC) return "iap2 rx=$id"
        val count = (counts[messageId] ?: 0) + 1
        counts[messageId] = count
        return when {
            count <= first -> "iap2 rx=$id"
            count % every == 0 -> "iap2 rx=$id count=$count"
            else -> null
        }
    }

    private companion object {
        val PERIODIC = setOf(0x5001, 0x5201)
    }
}
