package dev.arnv.bluke.bluetooth

// HIDP report types and handshake codes, as BluetoothHidDevice numbers them.
internal const val HID_REPORT_TYPE_INPUT: Byte = 1
internal const val HID_REPORT_TYPE_OUTPUT: Byte = 2
internal const val HID_ERROR_INVALID_REPORT_ID: Byte = 2

internal const val KEYBOARD_REPORT_ID = 1
internal const val MOUSE_REPORT_ID = 2
internal const val GAMEPAD_REPORT_ID = 3
internal const val CONSUMER_REPORT_ID = 4

/** What Bluke answers a host's GET_REPORT with: the report's bytes (without its ID), or a handshake error. */
internal sealed interface GetReportReply {
    class Report(val data: ByteArray) : GetReportReply
    class Error(val code: Byte) : GetReportReply
}

/**
 * The HID spec requires an answer to every GET_REPORT. An input report answers with the last
 * report sent under that ID, or the idle report before any was sent; the mouse keeps only its
 * buttons, since its movement and scroll are relative and a host may apply the answer as input.
 * The keyboard's output report answers with the LED byte the host last set. Bluke declares no
 * feature reports.
 */
internal fun replyToGetReport(
    type: Byte,
    id: Int,
    lastInputReport: (Int) -> ByteArray?,
    ledOutput: Byte,
    gamepadMode: GamepadDpadOutputMode,
): GetReportReply = when {
    type == HID_REPORT_TYPE_INPUT && id == MOUSE_REPORT_ID ->
        GetReportReply.Report(buildMouseReport(lastInputReport(id)?.firstOrNull() ?: 0, 0, 0, 0, 0))
    type == HID_REPORT_TYPE_INPUT && id in KEYBOARD_REPORT_ID..CONSUMER_REPORT_ID ->
        GetReportReply.Report(lastInputReport(id) ?: idleInputReport(id, gamepadMode))
    type == HID_REPORT_TYPE_OUTPUT && id == KEYBOARD_REPORT_ID ->
        GetReportReply.Report(byteArrayOf(ledOutput))
    else -> GetReportReply.Error(HID_ERROR_INVALID_REPORT_ID)
}

private fun idleInputReport(id: Int, gamepadMode: GamepadDpadOutputMode): ByteArray = when (id) {
    KEYBOARD_REPORT_ID -> ByteArray(KEYBOARD_REPORT_SIZE)
    GAMEPAD_REPORT_ID -> buildGamepadReport(0, 0, 0f, 0f, 0f, 0f, gamepadMode)
    else -> buildConsumerControlReport(null)
}

internal const val KEYBOARD_REPORT_SIZE = 8

/** Report 2: buttons, X, Y, vertical wheel, horizontal pan (AC Pan). */
internal fun buildMouseReport(buttons: Byte, x: Byte, y: Byte, wheel: Byte, pan: Byte): ByteArray =
    byteArrayOf(buttons, x, y, wheel, pan)
