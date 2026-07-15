package dev.chaichai.mobile.feature.libraries

import org.junit.Assert.assertEquals
import org.junit.Test

class MovieGridDensityTest {
    @Test
    fun `columns stay within each adaptive width class cap`() {
        assertEquals(3, movieGridColumnCount(LibraryWindowClass.Compact, 599f, 1f))
        assertEquals(5, movieGridColumnCount(LibraryWindowClass.Medium, 839f, 1f))
        assertEquals(8, movieGridColumnCount(LibraryWindowClass.Expanded, 2_000f, 1f))
    }

    @Test
    fun `large text reduces density without escaping class bounds`() {
        assertEquals(4, movieGridColumnCount(LibraryWindowClass.Medium, 839f, 1.35f))
        assertEquals(6, movieGridColumnCount(LibraryWindowClass.Expanded, 1_200f, 1.35f))
    }
}
