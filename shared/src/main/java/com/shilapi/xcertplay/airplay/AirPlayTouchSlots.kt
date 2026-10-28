package com.shilapi.xcertplay.airplay

/**
 * Keeps each finger in one touch report slot from press to release (#444). Contacts arrive with
 * Android pointer ids, which stay with a finger while the pointer index of the others changes. A
 * finger that is gone, and every finger when the list is empty, is released where it last was: a
 * release at (0,0) drags it to the corner first. A finger beyond the free slots is left out.
 */
internal class AirPlayTouchSlots(private val slots: Int) {
    private val pointers = arrayOfNulls<Int>(slots)
    private val held = arrayOfNulls<AirPlayContact>(slots)

    /** The contacts to report, each with its slot as id; empty slots are left out. */
    fun assign(contacts: List<AirPlayContact>): List<AirPlayContact> {
        val report = arrayOfNulls<AirPlayContact>(slots)
        for (contact in contacts) {
            val slot = pointers.indexOf(contact.id).takeIf { it >= 0 }
                ?: pointers.indexOf(null).takeIf { it >= 0 && contact.down }
                ?: continue
            pointers[slot] = contact.id
            report[slot] = contact.copy(id = slot)
        }
        for (slot in 0 until slots) {
            if (report[slot] == null) report[slot] = held[slot]?.copy(down = false)
            val sent = report[slot]
            if (sent?.down == true) {
                held[slot] = sent
            } else {
                held[slot] = null
                pointers[slot] = null
            }
        }
        return report.filterNotNull()
    }
}
