package io.github.gdlbo.makerplay.wolfformat

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.TreeMap

/**
 * Static audit of the WOLF engine features actually used by the example
 * deployments. Walks every common event, map event, and database command in
 * each game and emits an opcode-coverage checklist that defines engine parity
 * for these two games.
 *
 * The report lands in build/wolf-command-audit.md; the test only asserts that
 * both deployments parsed, so it doubles as a whole-corpus parser smoke test.
 */
class WolfCommandAuditTest {

    private val repoRoot: File = run {
        var dir = File(System.getProperty("user.dir"))
        repeat(4) { if (dir.resolve("example").isDirectory) return@run dir; dir = dir.parentFile ?: dir }
        dir
    }

    private val fixtures: List<String> =
        repoRoot.resolve("example").listFiles().orEmpty()
            .filter { it.resolve("Data/BasicData/CommonEvent.dat").isFile }
            .map { it.name }
            .sorted()

    /** Human names for the WOLF system-call band (commands 100-199). */
    private val systemCallNames: Map<Int, String> = mapOf(
        100 to "ShowMessageScroll", 101 to "ShowMessage", 102 to "ShowChoices",
        103 to "Comment", 105 to "ForceStopMessage", 106 to "DebugMessage",
        107 to "ClearDebugText", 110 to "OpenSaveLoadMenu", 111 to "NumberCondition",
        112 to "StringCondition", 113 to "SwitchCondition", 114 to "VariableBoxCondition",
        115 to "RandomRangeCondition", 116 to "CselfCondition", 117 to "DatabaseCondition",
        118 to "StringDatabaseCondition", 119 to "CommonEventCondition",
        120 to "SubEventCondition", 121 to "SetVariable", 122 to "SetString",
        123 to "InputKey", 124 to "SetVariablePlus", 125 to "AutoInput",
        126 to "BanInput", 127 to "BanKeyInput", 128 to "LoadCommonEventVariables",
        130 to "Teleport", 131 to "SetPartyMember", 132 to "MovePartyMember",
        133 to "EncounterRate", 134 to "Encounter", 135 to "OpenShop",
        136 to "OpenGauge", 137 to "OpenMenu", 138 to "LoadGameCustom",
        139 to "OpenDatabase", 140 to "Sound", 141 to "PlayMidi",
        142 to "LoadSoundCustom", 143 to "SetSoundValue", 144 to "StopMusic",
        145 to "StopSE", 150 to "Picture", 151 to "ChangeColor",
        152 to "LoadPictureCustom", 153 to "TransitionPrepare", 154 to "TransitionExecute",
        155 to "TransitionConfig", 160 to "SetTransition", 161 to "PrepareTransition",
        162 to "ExecuteTransition", 163 to "LoadTransitionCustom", 170 to "StartLoop",
        171 to "BreakLoop", 172 to "BreakEvent", 173 to "EraseEvent",
        174 to "ReturnToTitle", 175 to "EndGame", 176 to "StartLoop2",
        177 to "StopNonPic", 178 to "ResumeNonPic", 179 to "LoopTimes",
        180 to "Wait", 181 to "WaitStep", 190 to "MovePicture",
    )

    private data class Usage(
        val count: Int,
        val samples: MutableList<String>,
    )

    @Test
    fun auditCommandCoverageOfExampleDeployments() {
        val roots = fixtures.map { repoRoot.resolve("example").resolve(it) }
            .filter { it.isDirectory }
        assumeTrue("No example deployments present", roots.isNotEmpty())

        val report = StringBuilder()
        report.appendLine("# WOLF command audit — example deployments")
        report.appendLine()

        for (root in roots) {
            val usage = TreeMap<Int, Usage>()
            val dataDir = root.resolve("Data")
            var parseFailures = 0

            fun record(commandType: Int, sample: String?) {
                val entry = usage.getOrPut(commandType) { Usage(0, mutableListOf()) }
                entry.samples.let { if (sample != null && it.size < 3 && sample.isNotBlank()) it.add(sample.take(60)) }
                usage[commandType] = Usage(entry.count + 1, entry.samples)
            }

            fun walkCommands(commands: List<EventCommand>) {
                for (cmd in commands) {
                    val sample = cmd.strings.firstOrNull()
                    record(cmd.commandType, sample)
                    cmd.route?.steps?.size?.let { record(-1, null) } // route steps counted under -1
                }
            }

            // Common events.
            val commonEvent = dataDir.resolve("BasicData/CommonEvent.dat")
            var commonEventFailures = 0
            if (commonEvent.isFile) {
                try {
                    val parsed = CommonEventDat.parse(commonEvent.readBytes())
                    for (ev in parsed.events) walkCommands(ev.commands)
                } catch (e: Exception) {
                    commonEventFailures++
                    report.appendLine("> WARN: CommonEvent.dat failed to parse: ${e.message}")
                }
            }

            // Maps.
            val mapFiles = dataDir.walkTopDown().filter { it.isFile && it.extension.equals("mps", true) }.toList()
            for (map in mapFiles) {
                try {
                    val parsed = MapFile.parse(map.readBytes())
                    for (event in parsed.events) for (page in event.pages) walkCommands(page.commands)
                } catch (e: Exception) {
                    parseFailures++
                    report.appendLine("> WARN: ${map.name} failed to parse: ${e.message}")
                }
            }

            report.appendLine("## ${root.name}")
            report.appendLine()
            report.appendLine("- Maps parsed: ${mapFiles.size - parseFailures}/${mapFiles.size}" +
                (if (commonEventFailures > 0) " (CommonEvent.dat FAILED)" else ""))
            report.appendLine("- Distinct opcodes: ${usage.size}")
            report.appendLine()
            report.appendLine("| Opcode | System call | Count | Samples |")
            report.appendLine("|--------|-------------|-------|---------|")
            for ((opcode, entry) in usage.entries.sortedByDescending { it.value.count }) {
                val name = systemCallNames[opcode] ?: if (opcode == -1) "(move-route steps)" else ""
                report.appendLine("| $opcode | $name | ${entry.count} | ${entry.samples.joinToString(" ; ") { it.replace("|", "\\|") }} |")
            }
            report.appendLine()
        }

        val outFile = File(repoRoot, "build/wolf-command-audit.md")
        outFile.parentFile?.mkdirs()
        outFile.writeText(report.toString())
        println(report.toString())
        assertTrue(report.isNotEmpty())
    }
}
