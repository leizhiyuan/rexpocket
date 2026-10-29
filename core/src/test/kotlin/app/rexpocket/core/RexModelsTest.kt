package app.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RexModelsTest {
    @Test
    fun parsesView() {
        val json = org.json.JSONObject(
            """
            {"revision":3,"session_id":"session:abc","label":"work","active_window_id":"window:w",
             "windows":[{"window_id":"window:w","label":"shell","focused_block_id":"block:b","layers":[
               {"blocks":[{"block_id":"block:b","label":"shell","creator_name":"com.superlogical.terminal"}]}
             ]}]}
            """.trimIndent(),
        )
        val view = parseView(json)
        assertEquals("work", view.label)
        assertEquals("block:b", view.focusedBlock()?.id)
        assertEquals(1, view.windows.size)
    }
}
