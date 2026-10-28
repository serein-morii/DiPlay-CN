package com.shilapi.xcertplay.media

import android.view.MotionEvent
import com.shilapi.xcertplay.airplay.AirPlayContact

/**
 * Converts Android MotionEvents into normalized CarPlay touch contacts, one per pointer. A contact's
 * id is the pointer id, which stays with its finger; the session picks the report slots (#444).
 */
object CarPlayTouchMapper {
    fun contacts(event: MotionEvent, viewWidth: Int, viewHeight: Int): List<AirPlayContact> =
        contacts(event, CarPlayVideoLayout(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat()))

    fun contacts(event: MotionEvent, content: CarPlayVideoLayout): List<AirPlayContact> {
        val width = content.width.coerceAtLeast(1f)
        val height = content.height.coerceAtLeast(1f)
        val action = event.actionMasked
        val liftedIndex = if (action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        val allUp = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        val count = event.pointerCount
        val contacts = ArrayList<AirPlayContact>(count)
        for (index in 0 until count) {
            contacts.add(
                AirPlayContact(
                    id = event.getPointerId(index),
                    x = ((event.getX(index) - content.left).toDouble() / width).coerceIn(0.0, 1.0),
                    y = ((event.getY(index) - content.top).toDouble() / height).coerceIn(0.0, 1.0),
                    down = !allUp && index != liftedIndex,
                ),
            )
        }
        return contacts
    }
}
