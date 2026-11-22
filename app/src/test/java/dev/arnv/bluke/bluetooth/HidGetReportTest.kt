package dev.arnv.bluke.bluetooth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HidGetReportTest {
    private fun reply(
        type: Byte,
        id: Int,
        sent: Map<Int, ByteArray> = emptyMap(),
        led: Byte = 0,
        mode: GamepadDpadOutputMode = GamepadDpadOutputMode.NATIVE_HAT,
    ) = replyToGetReport(type, id, sent::get, led, mode)

    private fun bytes(reply: GetReportReply): ByteArray =
        (reply as GetReportReply.Report).data

    @Test fun inputReportAnswersWithTheLastReportSentUnderThatId() {
        val keyA = byteArrayOf(0, 0, 0x04, 0, 0, 0, 0, 0)
        assertArrayEquals(keyA, bytes(reply(HID_REPORT_TYPE_INPUT, KEYBOARD_REPORT_ID, mapOf(1 to keyA))))
    }

    @Test fun inputReportBeforeAnySendAnswersIdle() {
        assertArrayEquals(ByteArray(8), bytes(reply(HID_REPORT_TYPE_INPUT, KEYBOARD_REPORT_ID)))
        assertArrayEquals(ByteArray(5), bytes(reply(HID_REPORT_TYPE_INPUT, MOUSE_REPORT_ID)))
        assertArrayEquals(ByteArray(2), bytes(reply(HID_REPORT_TYPE_INPUT, CONSUMER_REPORT_ID)))
        val gamepad = bytes(reply(HID_REPORT_TYPE_INPUT, GAMEPAD_REPORT_ID))
        assertEquals(GAMEPAD_REPORT_SIZE_BYTES, gamepad.size)
        assertEquals(GAMEPAD_HAT_NEUTRAL.toByte(), gamepad[3]) // hat centred, not "up"
    }

    @Test fun mouseAnswersItsButtonsButNeverReplaysMovementOrScroll() {
        val dragging = buildMouseReport(1, 5, -3, 1, -2)
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 0), bytes(reply(HID_REPORT_TYPE_INPUT, MOUSE_REPORT_ID, mapOf(2 to dragging))))
    }

    @Test fun keyboardOutputReportAnswersWithTheHostsLedByte() {
        assertArrayEquals(byteArrayOf(0x02), bytes(reply(HID_REPORT_TYPE_OUTPUT, KEYBOARD_REPORT_ID, led = 0x02)))
    }

    @Test fun reportsBlukeDoesNotDeclareAreRejected() {
        val feature: Byte = 3
        for ((type, id) in listOf(feature to 1, HID_REPORT_TYPE_INPUT to 0, HID_REPORT_TYPE_INPUT to 5, HID_REPORT_TYPE_OUTPUT to 2)) {
            val answer = reply(type, id)
            assertTrue("type $type id $id", answer is GetReportReply.Error)
            assertEquals(HID_ERROR_INVALID_REPORT_ID, (answer as GetReportReply.Error).code)
        }
    }

    @Test fun mouseReportCarriesHorizontalPanLast() {
        assertArrayEquals(byteArrayOf(1, 5, -3, 1, -2), buildMouseReport(1, 5, -3, 1, -2))
    }
}
