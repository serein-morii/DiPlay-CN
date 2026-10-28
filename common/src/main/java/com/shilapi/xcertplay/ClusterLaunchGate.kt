package com.shilapi.xcertplay

/**
 * DiLink 4 的 ADB 仪表窗口会抢走焦点，因此要等中控窗口先取得焦点（#536）。
 * Android 10 需要窗口有焦点才能隐藏系统栏；过早启动仪表会让中控先按较小尺寸连接，
 * 点击中控恢复焦点后尺寸变大，又触发重连。焦点等待仍保留超时和离开前台的出口。
 * 此门控仅在中控首帧后使用，仪表路由确认不再阻塞 CarPlay 启动。
 */
internal class ClusterLaunchGate(
    private val focusWaitMillis: Long = FOCUS_WAIT_MILLIS,
) {
    private var awaitingFocusSince = -1L
    val awaitingFocus: Boolean get() = awaitingFocusSince >= 0

    /** True when the launch may run now; otherwise it waits for [due]. */
    fun request(now: Long, hostFocused: Boolean): Boolean {
        if (hostFocused) return launch()
        if (awaitingFocusSince < 0) awaitingFocusSince = now
        return false
    }

    /** True once a waiting launch may run: the host has focus, is off screen, or waited long enough. */
    fun due(now: Long, hostFocused: Boolean, hostVisible: Boolean): Boolean {
        if (awaitingFocusSince < 0) return false
        if (!hostFocused && hostVisible && now - awaitingFocusSince < focusWaitMillis) return false
        return launch()
    }

    fun reset() {
        awaitingFocusSince = -1L
    }

    private fun launch(): Boolean {
        awaitingFocusSince = -1L
        return true
    }

    companion object {
        const val FOCUS_WAIT_MILLIS = 1_500L
        const val TICK_MILLIS = 100L
    }
}
