package com.arabiflow.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StageTest {
    @Test fun progressStagesRemainMonotonic() {
        val limits = listOf(0, 15, 30, 50, 70, 85, 95, 100)
        assertEquals(limits, Stage.entries.map { it.percent })
        assertEquals(Stage.PREPARING, Stage.fromProgress(14))
        assertEquals(Stage.TRANSLATION, Stage.fromProgress(69))
        assertEquals(Stage.COMPLETED, Stage.fromProgress(100))
    }
}
