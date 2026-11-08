package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.wolfformat.CommonEventDat
import io.github.gdlbo.makerplay.wolfformat.EventCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Covers opcodes 255/256/257, which `GamePro.exe` dispatches to dedicated
 * XY-array routines (sub_A2ACA0 / sub_A2BD90 / sub_A2C500) rather than to the
 * database path. See docs/wolf-ida-vm-reference.md sections 5 and 7.
 *
 * Flag word layout used by these three opcodes, from the executor
 * (`(v128 >> 12) & 0xF` selects the action):
 * - bits 12..15 action (0 = read a cell, 1..3 = write forms)
 * - bits 4..7   operator (0 assign, 1 add, 2 subtract, 3 multiply, 4 divide)
 * - bits 8..11  bank nibble (3 in every occurrence this deployment ships)
 */
class WolfDatabaseXyArrayTest {

private fun command(
        type: Int,
        params: IntArray,
        strings: List<String> = emptyList(),
    ) = EventCommand(
        paramCount = params.size + 1,
        commandType = type,
        params = params,
        branchDepth = 0,
        strings = strings,
        route = null,
    )

    /** Action 0 (read), bank 3. */
    private fun readFlags() = 0x0300

    /** Action 1 (write), bank 3, optional operator in bits 4..7. */
    private fun writeFlags(operator: Int = 0) = 0x1300 or (operator shl 4)

    private fun run(
        database: WolfDatabase,
        command: EventCommand,
        variables: MutableMap<Int, Int> = mutableMapOf(),
    ) {
        database.execute(
            command,
            readNumber = { ref -> variables[ref] ?: 0 },
            writeNumber = { ref, value -> variables[ref] = value },
            readString = { "" },
            writeString = { _, _ -> },
        )
    }

    private fun readCell(database: WolfDatabase, array: Int, x: Int, y: Int, dest: Int): Int {
        val variables = mutableMapOf<Int, Int>()
        run(database, command(255, intArrayOf(array, x, y, readFlags(), dest)), variables)
        return variables[dest] ?: -1
    }

    @Test
    fun `write then read round-trips a cell for each of 255 256 257`() {
        for (opcode in listOf(255, 256, 257)) {
            val database = WolfDatabase.empty()
            run(database, command(opcode, intArrayOf(27, 4, 7, writeFlags(), 99)))
            assertEquals(
                "opcode $opcode must store into the XY array",
                99,
                readCell(database, 27, 4, 7, 2_000_005),
            )
        }
    }

    @Test
    fun `operator subtypes follow sub_AED340`() {
        val database = WolfDatabase.empty()
        run(database, command(257, intArrayOf(0, 1, 1, writeFlags(), 10)))

        run(database, command(257, intArrayOf(0, 1, 1, writeFlags(1), 5)))
        assertEquals(15, readCell(database, 0, 1, 1, 2_000_001))
        run(database, command(257, intArrayOf(0, 1, 1, writeFlags(2), 3)))
        assertEquals(12, readCell(database, 0, 1, 1, 2_000_001))
        run(database, command(257, intArrayOf(0, 1, 1, writeFlags(3), 2)))
        assertEquals(24, readCell(database, 0, 1, 1, 2_000_001))
        run(database, command(257, intArrayOf(0, 1, 1, writeFlags(4), 4)))
        assertEquals(6, readCell(database, 0, 1, 1, 2_000_001))
    }

    @Test
    fun `division by zero is guarded, matching the engine`() {
        val database = WolfDatabase.empty()
        run(database, command(257, intArrayOf(0, 2, 2, writeFlags(), 100)))
        // sub_AED340 substitutes 1 for a zero divisor instead of faulting.
        run(database, command(257, intArrayOf(0, 2, 2, writeFlags(4), 0)))
        assertEquals(100, readCell(database, 0, 2, 2, 2_000_002))
    }

    @Test
    fun `the real battle event write lands in the array, not a database type`() {
        // Opcode 257 in CE 144 '■バトル' ships params[3] = 1127168 = 0x113300.
        // The user bank is empty in this test, so a database interpretation
        // would drop the write entirely; the XY path must keep it.
        val database = WolfDatabase.empty()
        run(database, command(257, intArrayOf(13, 0, 1, 0x113300, 7), listOf("", "BTL_order")))
        assertEquals(7, readCell(database, 13, 0, 1, 2_000_003))
    }

    @Test
    fun `opcode 250 rejects bank 3 instead of treating it as a user type`() {
        // sub_9E20C0's case 250 only accepts banks 0/1/2 and bails otherwise.
        val variables = mutableMapOf<Int, Int>()
        val database = WolfDatabase.empty()
        run(database, command(250, intArrayOf(0, 0, 0, 0x113300, 2_000_004)), variables)
        assertTrue(
            "bank 3 must not resolve through the database path",
            !variables.containsKey(2_000_004),
        )
    }


}
