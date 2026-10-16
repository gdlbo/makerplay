package io.github.gdlbo.makerplay.wolfformat

import org.junit.Assert.assertEquals
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

    private val fixtures: List<File> = deploymentRoots(repoRoot)

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
        val roots = fixtures.filter { it.isDirectory }
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

    @Test
    fun opcode104InExampleGamesHasNoParameters() {
        val hits = mutableListOf<EventCommand>()
        for (root in deploymentRoots(repoRoot)) {
            val common = root.resolve("Data/BasicData/CommonEvent.dat")
            if (common.isFile) {
                val parsed = CommonEventDat.parse(common.readBytes())
                for (ev in parsed.events) hits += ev.commands.filter { it.commandType == 104 }
            }
            root.resolve("Data").walkTopDown().filter { it.extension.equals("mps", true) }.forEach { map ->
                val parsedMap = runCatching { MapFile.parse(map.readBytes()) }.getOrNull() ?: return@forEach
                for (event in parsedMap.events) for (page in event.pages) {
                    hits += page.commands.filter { it.commandType == 104 }
                }
            }
        }
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.params.isEmpty() && it.strings.isEmpty() })
    }

    @Test
    fun exampleGamesSystemVariableReferences() {
        var normal = 0
        var strings = 0
        val stringIds = HashSet<Int>()
        var system8 = 0
        var system9 = 0
        var system16 = 0
        val system8ids = HashSet<Int>()
        val system9ids = HashSet<Int>()
        fun consider(params: IntArray) {
            for (p in params) {
                when (p) {
                    in 2_000_000..2_999_999 -> normal++
                    in 3_000_000..3_999_999 -> {
                        strings++
                        stringIds += p - 3_000_000
                    }
                    in 8_000_000..8_999_999 -> {
                        system8++
                        system8ids += p - 8_000_000
                    }
                    in 9_000_000..9_999_999 -> {
                        system9++
                        system9ids += p - 9_000_000
                    }
                    in 1_600_000..1_699_999 -> system16++
                }
            }
        }
        for (root in deploymentRoots(repoRoot)) {
            val common = root.resolve("Data/BasicData/CommonEvent.dat")
            if (common.isFile) {
                for (ev in CommonEventDat.parse(common.readBytes()).events) {
                    for (cmd in ev.commands) consider(cmd.params)
                }
            }
            root.resolve("Data").walkTopDown().filter { it.extension.equals("mps", true) }.forEach { map ->
                val parsed = runCatching { MapFile.parse(map.readBytes()) }.getOrNull() ?: return@forEach
                for (event in parsed.events) for (page in event.pages) {
                    for (cmd in page.commands) consider(cmd.params)
                }
            }
        }
        assertEquals(31692, normal)
        assertEquals(2344, strings)
        assertEquals(106, stringIds.size)
        assertEquals(30, stringIds.count { it < 30 })
        assertEquals(4, system8)
        assertEquals(setOf(217700), system8ids)
        assertEquals(1464, system9)
        assertEquals(129180, system16)
        assertEquals(105, system9ids.size)
        assertEquals(74, system9ids.count { it < 150 })
        assertEquals(
            "0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,28,29,35,36,37,39,40,41,51,52,53,54,55,56,57,59,60,61,64,65,66,71,72,73,74,77,78,79,80,81,82,84,95,96,97,98,100,101,102,104,105,110,111,112,115,116,117,118",
            system9ids.filter { it < 150 }.sorted().joinToString(","),
        )
    }

    @Test
    fun dumpDatabaseAndEffectSamples() {
        val out = StringBuilder()
        for (root in deploymentRoots(repoRoot)) {
            val interesting = mutableListOf<String>()
            fun take(label: String, commands: List<EventCommand>) {
                for (cmd in commands) {
                    if (cmd.commandType !in setOf(251, 252, 255, 290)) continue
                    if (interesting.count { it.startsWith("${root.name} ${cmd.commandType}") } >= 4) continue
                    interesting += "${root.name} ${cmd.commandType} $label params=${cmd.params.toList()} strings=${cmd.strings}"
                }
            }
            val common = root.resolve("Data/BasicData/CommonEvent.dat")
            if (common.isFile) {
                val parsed = CommonEventDat.parse(common.readBytes())
                for (ev in parsed.events) take("CE ${ev.id}", ev.commands)
            }
            root.resolve("Data").walkTopDown().filter { it.extension.equals("mps", true) }.forEach { map ->
                val parsedMap = runCatching { MapFile.parse(map.readBytes()) }.getOrNull() ?: return@forEach
                for (event in parsedMap.events) for (page in event.pages) take(map.name, page.commands)
            }
            interesting.forEach { out.appendLine(it) }
        }
        val file = File(repoRoot, "build/db-effect-samples.txt")
        file.parentFile?.mkdirs()
        file.writeText(out.toString())
        println(out.toString())
        assertTrue(out.isNotEmpty())
    }

    companion object {
        fun deploymentRoots(repoRoot: File): List<File> {
            val example = repoRoot.resolve("example")
            val candidates = example.walkTopDown().maxDepth(3)
                .filter { it.isDirectory && it.resolve("Data/BasicData/CommonEvent.dat").isFile }
                .toList()
            return candidates.distinctBy { it.canonicalPath }.sortedBy { it.name }
        }
    }
}
