package com.cleanforge.core

import com.cleanforge.data.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThemeModeTest {
    @Test fun `the default follows the system and the switch cycles through all three`() {
        assertThat(ThemeMode.entries.first()).isEqualTo(ThemeMode.System)
        assertThat(ThemeMode.System.next()).isEqualTo(ThemeMode.Light)
        assertThat(ThemeMode.Light.next()).isEqualTo(ThemeMode.Dark)
        assertThat(ThemeMode.Dark.next()).isEqualTo(ThemeMode.System)
    }
}
