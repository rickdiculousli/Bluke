package dev.arnv.bluke.ui

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorHotkeysTest {
    private val E = KeyboardLayouts.KEY_E

    @Test
    fun roundTripKeepsLabelsModifiersAndNullLabels() {
        val list = listOf(
            MirrorHotkey(E, 0, "End turn"),
            MirrorHotkey(KeyboardLayouts.KEY_TAB, HotkeyModifiers.SHIFT),
            MirrorHotkey(KeyboardLayouts.KEY_F5, HotkeyModifiers.CTRL or HotkeyModifiers.SHIFT, null),
        )
        val json = encodeMirrorHotkeys(list)
        assertTrue(json.contains("\"version\":1"))
        assertEquals(list, decodeMirrorHotkeys(json))
        assertTrue(!json.substringAfter("\"key\":${KeyboardLayouts.KEY_TAB}").substringBefore("}").contains("label"))
    }

    @Test
    fun decodeRejectsBadInput() {
        assertEquals(emptyList<MirrorHotkey>(), decodeMirrorHotkeys(null))
        assertEquals(emptyList<MirrorHotkey>(), decodeMirrorHotkeys("not json"))
        assertEquals(emptyList<MirrorHotkey>(), decodeMirrorHotkeys("{\"version\":2,\"hotkeys\":[{\"key\":8}]}"))
        assertEquals(emptyList<MirrorHotkey>(), decodeMirrorHotkeys("{\"hotkeys\":[{\"key\":8}]}"))
    }

    @Test
    fun decodeSkipsBadEntriesAndCleansFields() {
        val json = """{"version":1,"hotkeys":[
            {"key":3},{"key":232},{"modifiers":1},"x",
            {"key":8,"modifiers":255,"label":"  a very long name  "},
            {"key":9,"label":"   "}]}"""
        assertEquals(
            listOf(MirrorHotkey(8, HotkeyModifiers.ALL, "a very l"), MirrorHotkey(9)),
            decodeMirrorHotkeys(json),
        )
    }

    @Test
    fun decodeKeepsAtMostEight() {
        val list = (0 until 12).map { MirrorHotkey(KeyboardLayouts.KEY_A + it) }
        val json = encodeMirrorHotkeys(list)
        assertEquals(list.take(MAX_MIRROR_HOTKEYS), decodeMirrorHotkeys(json))
        val raw = "{\"version\":1,\"hotkeys\":[" + list.joinToString(",") { "{\"key\":${it.keyCode}}" } + "]}"
        assertEquals(MAX_MIRROR_HOTKEYS, decodeMirrorHotkeys(raw).size)
    }

    @Test
    fun preferencesPairRoundTripsAndCaps() {
        val prefs = FakePrefs()
        assertEquals(emptyList<MirrorHotkey>(), prefs.mirrorHotkeys())
        prefs.saveMirrorHotkeys((0 until 10).map { MirrorHotkey(KeyboardLayouts.KEY_A + it, 1, "k$it") })
        assertEquals(MAX_MIRROR_HOTKEYS, prefs.mirrorHotkeys().size)
        assertEquals(MirrorHotkey(KeyboardLayouts.KEY_A, 1, "k0"), prefs.mirrorHotkeys().first())
    }

    private class Recorder : MirrorKeySender {
        val events = mutableListOf<String>()
        override fun sendKey(keyCode: Int, isPress: Boolean) {
            events += (if (isPress) "+" else "-") + "%02X".format(keyCode)
        }
    }

    @Test
    fun presserOrdersModifiersAroundKey() {
        val r = Recorder()
        val p = MirrorHotkeyPresser(r)
        val hk = MirrorHotkey(E, HotkeyModifiers.CTRL or HotkeyModifiers.SHIFT)
        p.press(hk)
        assertEquals(listOf("+E0", "+E1", "+08"), r.events)
        assertEquals(setOf(hk), p.held)
        p.press(hk)
        assertEquals(3, r.events.size)
        p.lift(hk)
        assertEquals(listOf("+E0", "+E1", "+08", "-08", "-E0", "-E1"), r.events)
        p.lift(hk)
        assertEquals(6, r.events.size)
        assertTrue(p.held.isEmpty())
    }

    @Test
    fun sharedModifierDropsForTheOtherHotkey() {
        val r = Recorder()
        val p = MirrorHotkeyPresser(r)
        val a = MirrorHotkey(E, HotkeyModifiers.CTRL)
        val b = MirrorHotkey(KeyboardLayouts.KEY_F, HotkeyModifiers.CTRL)
        p.press(a)
        p.press(b) // Ctrl already held: not sent again
        assertEquals(listOf("+E0", "+08", "+09"), r.events)
        p.lift(a)
        assertEquals(listOf("+E0", "+08", "+09", "-08", "-E0"), r.events)
        p.lift(b)
        assertEquals("-09", r.events.last())
        assertEquals(6, r.events.size)
    }

    @Test
    fun modifierKeyHotkeySendsJustThatKey() {
        val r = Recorder()
        val p = MirrorHotkeyPresser(r)
        val hk = MirrorHotkey(KeyboardLayouts.MOD_LSHIFT, HotkeyModifiers.CTRL)
        p.press(hk)
        p.lift(hk)
        assertEquals(listOf("+E1", "-E1"), r.events)
    }

    @Test
    fun releaseAllLiftsEverythingHeld() {
        val r = Recorder()
        val p = MirrorHotkeyPresser(r)
        p.press(MirrorHotkey(E, HotkeyModifiers.ALT))
        p.press(MirrorHotkey(KeyboardLayouts.KEY_TAB))
        r.events.clear()
        p.releaseAll()
        assertEquals(listOf("-08", "-E2", "-2B"), r.events)
        assertTrue(p.held.isEmpty())
        p.releaseAll()
        assertEquals(3, r.events.size)
    }

    @Test
    fun displayLabels() {
        assertEquals("E", MirrorHotkey(E).displayLabel)
        assertEquals("1", MirrorHotkey(KeyboardLayouts.KEY_1).displayLabel)
        assertEquals("Esc", MirrorHotkey(KeyboardLayouts.KEY_ESC).displayLabel)
        assertEquals("⇧Tab", MirrorHotkey(KeyboardLayouts.KEY_TAB, HotkeyModifiers.SHIFT).displayLabel)
        assertEquals("⌃⇧F5", MirrorHotkey(KeyboardLayouts.KEY_F5, HotkeyModifiers.CTRL or HotkeyModifiers.SHIFT).displayLabel)
        assertEquals("⌃⌥⇧⌘A", MirrorHotkey(KeyboardLayouts.KEY_A, HotkeyModifiers.ALL).displayLabel)
        assertEquals("End turn", MirrorHotkey(E, 0, " End turn ").displayLabel)
        assertEquals("E", MirrorHotkey(E, 0, "  ").displayLabel)
        assertEquals(";", MirrorHotkey(KeyboardLayouts.KEY_SEMICOLON).displayLabel)
    }

    @Test
    fun spokenLabels() {
        assertEquals("Shift Tab", MirrorHotkey(KeyboardLayouts.KEY_TAB, HotkeyModifiers.SHIFT).spokenLabel)
        assertEquals("Control E", MirrorHotkey(E, HotkeyModifiers.CTRL).spokenLabel)
        assertEquals("End turn, E", MirrorHotkey(E, 0, "End turn").spokenLabel)
        assertEquals("Escape", MirrorHotkey(KeyboardLayouts.KEY_ESC).spokenLabel)
    }

    @Test
    fun keyListNamesAndNoDuplicates() {
        assertEquals(26 + 10 + 12 + 9 + 4, MIRROR_HOTKEY_KEYS.size)
        assertEquals(MIRROR_HOTKEY_KEYS.size, MIRROR_HOTKEY_KEYS.map { it.keyCode }.toSet().size)
        assertEquals("Cmd/Win", hotkeyKeyName(KeyboardLayouts.MOD_LWIN))
        assertEquals("⌫", hotkeyKeyName(KeyboardLayouts.KEY_BACKSPACE))
        assertEquals("0", hotkeyKeyName(KeyboardLayouts.KEY_0))
    }

    @Test
    fun characterMappingUsQwerty() {
        val us = KeyboardCharacterLayout.US_QWERTY
        assertEquals(MirrorHotkey(E), hotkeyForCharacter("e", us))
        assertEquals(MirrorHotkey(E), hotkeyForCharacter("E", us))
        assertEquals(MirrorHotkey(KeyboardLayouts.KEY_1), hotkeyForCharacter("1", us))
        assertEquals(MirrorHotkey(KeyboardLayouts.KEY_1, HotkeyModifiers.SHIFT), hotkeyForCharacter("!", us))
        assertEquals(MirrorHotkey(KeyboardLayouts.KEY_SEMICOLON), hotkeyForCharacter(";", us))
        assertEquals(MirrorHotkey(KeyboardLayouts.KEY_SEMICOLON, HotkeyModifiers.SHIFT), hotkeyForCharacter(":", us))
        assertEquals(MirrorHotkey(KeyboardLayouts.KEY_SPACE), hotkeyForCharacter(" ", us))
        assertNull(hotkeyForCharacter("é", us))
        assertNull(hotkeyForCharacter("", us))
        assertNull(hotkeyForCharacter("ab", us))
    }

    @Test
    fun characterMappingFollowsTheLayout() {
        assertEquals(
            MirrorHotkey(KeyboardLayouts.KEY_Z),
            hotkeyForCharacter("z", KeyboardCharacterLayout.GERMAN_QWERTZ),
        )
        val dvorak = KeyboardLayouts.getLayout(KeyboardLayoutType.OLIVIA_75, KeyboardCharacterLayout.DVORAK)
            .flatten().first { it.legend == "S" }
        assertEquals(MirrorHotkey(dvorak.keyCode), hotkeyForCharacter("s", KeyboardCharacterLayout.DVORAK))
        assertEquals(
            MirrorHotkey(KeyboardLayouts.KEY_Q),
            hotkeyForCharacter("й", KeyboardCharacterLayout.RUSSIAN_JCUKEN),
        )
        assertEquals(
            MirrorHotkey(KeyboardLayouts.KEY_Q),
            hotkeyForCharacter("Й", KeyboardCharacterLayout.RUSSIAN_JCUKEN),
        )
    }
}

private class FakePrefs : SharedPreferences {
    private val map = HashMap<String, Any?>()
    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String?, defValue: String?): String? = map[key] as String? ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int) = defValue
    override fun getLong(key: String?, defValue: Long) = defValue
    override fun getFloat(key: String?, defValue: Float) = defValue
    override fun getBoolean(key: String?, defValue: Boolean) = defValue
    override fun contains(key: String?) = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = apply { map[key!!] = value }
        override fun apply() {}
        override fun commit() = true
        override fun putStringSet(key: String?, values: MutableSet<String>?) = this
        override fun putInt(key: String?, value: Int) = this
        override fun putLong(key: String?, value: Long) = this
        override fun putFloat(key: String?, value: Float) = this
        override fun putBoolean(key: String?, value: Boolean) = this
        override fun remove(key: String?) = this
        override fun clear() = this
    }
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}
