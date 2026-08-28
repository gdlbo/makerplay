package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.runtime.api.CheatActorStat
import io.github.gdlbo.makerplay.runtime.api.CheatCommand
import io.github.gdlbo.makerplay.runtime.api.CheatInventoryKind
import io.github.gdlbo.makerplay.runtime.api.CheatOperation
import io.github.gdlbo.makerplay.runtime.api.CheatResource
import io.github.gdlbo.makerplay.runtime.api.RecoveryTarget
import io.github.gdlbo.makerplay.runtime.webview.internal.bridge.RuntimeCheatBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RuntimeCheatBridgeTest {
    private val session = RuntimeCheatBridge.Session("11111111-1111-4111-8111-111111111111")

    @Test
    fun `serializes bounded operations without arbitrary script fragments`() {
        val variable = RuntimeCheatBridge.script(
            session,
            CheatCommand(1, CheatOperation.SetVariable(10000, "2_000_000_000")),
        )
        assertTrue(variable.contains("\"op\":\"variable\""))
        assertTrue(variable.contains("\"id\":9999"))
        assertTrue(variable.contains("\"value\":\"2_000_000_000\""))

        val textVariable = RuntimeCheatBridge.script(
            session,
            CheatCommand(1, CheatOperation.SetVariable(3, "hello world")),
        )
        assertTrue(textVariable.contains("\"value\":\"hello world\""))

        val flags = RuntimeCheatBridge.script(
            session,
            CheatCommand(
                2,
                CheatOperation.SetFlags(
                    godMode = true,
                    infiniteHp = false,
                    infiniteMp = true,
                    holdToSkipDialog = true,
                )
            ),
        )
        assertTrue(flags.contains("\"godMode\":true"))
        assertTrue(flags.contains("\"infiniteMp\":true"))
        assertTrue(flags.contains("\"holdToSkipDialog\":true"))

        val maximumSpeed = RuntimeCheatBridge.script(
            session,
            CheatCommand(
                3,
                CheatOperation.SetFlags(
                    godMode = false,
                    infiniteHp = false,
                    infiniteMp = false,
                    playerSpeedMultiplier = 99.0,
                    gameSpeedMultiplier = 99.0,
                ),
            ),
        )
        assertTrue(maximumSpeed.contains("\"playerSpeedMultiplier\":8.0"))
        assertTrue(maximumSpeed.contains("\"gameSpeedMultiplier\":8.0"))

        val invalidSpeed = RuntimeCheatBridge.script(
            session,
            CheatCommand(
                4,
                CheatOperation.SetFlags(
                    godMode = false,
                    infiniteHp = false,
                    infiniteMp = false,
                    playerSpeedMultiplier = Double.NaN,
                    gameSpeedMultiplier = Double.NaN,
                ),
            ),
        )
        assertTrue(invalidSpeed.contains("\"playerSpeedMultiplier\":1.0"))
        assertTrue(invalidSpeed.contains("\"gameSpeedMultiplier\":1.0"))
    }

    @Test
    fun `serializes one shot operations to fixed operation names`() {
        assertTrue(
            RuntimeCheatBridge.script(session, CheatCommand(1, CheatOperation.AddGold(50)))
                .contains("\"op\":\"gold\"")
        )
        assertTrue(
            RuntimeCheatBridge.script(session, CheatCommand(1, CheatOperation.SetGold(250)))
                .contains("\"op\":\"setGold\"")
        )
        assertTrue(
            RuntimeCheatBridge.script(
                session,
                CheatCommand(
                    1,
                    CheatOperation.SetActorStat(2, CheatActorStat.HP, 99),
                ),
            ).contains("\"op\":\"actorStat\"")
        )
        assertTrue(
            RuntimeCheatBridge.script(
                session,
                CheatCommand(2, CheatOperation.SetSwitch(3, true))
            ).contains("\"op\":\"switch\"")
        )
        RecoveryTarget.entries.forEachIndexed { index, target ->
            val recoveryPayload = RuntimeCheatBridge.script(
                session,
                CheatCommand(3 + index.toLong(), CheatOperation.Recover(target)),
            )
            assertTrue(recoveryPayload.contains("\"op\":\"recover\""))
            assertTrue(recoveryPayload.contains("\"target\":\"${target.name.lowercase()}\""))
            CheatResource.entries.forEach { resource ->
                val refillPayload = RuntimeCheatBridge.script(
                    session,
                    CheatCommand(
                        4 + index.toLong(),
                        CheatOperation.RefillResource(target, resource)
                    ),
                )
                assertTrue(refillPayload.contains("\"op\":\"refill\""))
                assertTrue(refillPayload.contains("\"resource\":\"${resource.name.lowercase()}\""))
            }
            assertTrue(
                RuntimeCheatBridge.script(
                    session,
                    CheatCommand(5 + index.toLong(), CheatOperation.ClearStates(target)),
                ).contains("\"op\":\"clearStates\"")
            )
            val hpOnePayload = RuntimeCheatBridge.script(
                session,
                CheatCommand(5 + index.toLong(), CheatOperation.SetHpToOne(target)),
            )
            assertTrue(hpOnePayload.contains("\"op\":\"hpOne\""))
            assertTrue(hpOnePayload.contains("\"target\":\"${target.name.lowercase()}\""))
            val defeatPayload = RuntimeCheatBridge.script(
                session,
                CheatCommand(7 + index.toLong(), CheatOperation.Defeat(target)),
            )
            assertTrue(defeatPayload.contains("\"op\":\"defeat\""))
            assertTrue(defeatPayload.contains("\"target\":\"${target.name.lowercase()}\""))
        }
        assertTrue(
            RuntimeCheatBridge.script(session, CheatCommand(8, CheatOperation.RefreshCatalog))
                .contains("\"op\":\"catalog\"")
        )
        assertTrue(
            RuntimeCheatBridge.script(
                session,
                CheatCommand(9, CheatOperation.AddExperience(2, 500))
            ).contains("\"op\":\"experience\"")
        )
        assertTrue(
            RuntimeCheatBridge.script(
                session,
                CheatCommand(10, CheatOperation.AddParameter(2, 3, 4))
            ).contains("\"op\":\"parameter\"")
        )
        assertTrue(
            RuntimeCheatBridge.script(
                session,
                CheatCommand(11, CheatOperation.AddInventory(CheatInventoryKind.WEAPON, 6, 2))
            ).contains("\"kind\":\"weapon\"")
        )
        assertTrue(
            RuntimeCheatBridge.script(
                session,
                CheatCommand(12, CheatOperation.Teleport(3, 8, 9))
            ).contains("\"op\":\"teleport\"")
        )
        assertTrue(
            RuntimeCheatBridge.script(
                session,
                CheatCommand(13, CheatOperation.SavePosition(1))
            ).contains("\"op\":\"savePosition\"")
        )
        assertTrue(
            RuntimeCheatBridge.script(
                session,
                CheatCommand(14, CheatOperation.RecallPosition(1))
            ).contains("\"op\":\"recallPosition\"")
        )
    }

    @Test
    fun `accepts only token bound named catalog entries`() {
        val message =
            """{"v":1,"token":"${session.token}","gold":123,"mapId":7,"mapX":3,"mapY":4,"actors":[{"id":1,"name":"Hero","level":5,"hp":10,"mhp":20,"mp":3,"mmp":8,"tp":0,"exp":100}],"items":[{"id":3,"name":"Potion","value":"2"}],"variables":[{"id":2,"name":" Level ","value":"42"},{"id":3,"name":" ","value":"0"}],"switches":[{"id":4,"name":"Door","value":"ON"}]}"""

        val catalog = RuntimeCheatBridge.parseCatalog(message, session.token)

        assertEquals(123, catalog?.gold)
        assertEquals(7, catalog?.mapId)
        assertEquals(listOf(1), catalog?.actors?.map { it.id })
        assertEquals("Hero", catalog?.actors?.single()?.name)
        assertEquals(listOf(3), catalog?.items?.map { it.id })
        assertEquals(listOf(2), catalog?.variables?.map { it.id })
        assertEquals("Level", catalog?.variables?.single()?.name)
        assertEquals("ON", catalog?.switches?.single()?.value)
        assertNull(RuntimeCheatBridge.parseCatalog(message, "wrong-token"))
    }

    @Test
    fun `javascript bridge applies operations and waits for complete engine initialization`() {
        val bridgeSource =
            RuntimeCheatBridge.source(runtimeAsset("bridges/cheat-bridge.js"), session)
        val commands = listOf(
            CheatOperation.SetFlags(
                godMode = true,
                infiniteHp = false,
                infiniteMp = true,
                playerSpeedMultiplier = 4.0,
                gameSpeedMultiplier = 2.0,
                noClip = true,
                holdToSkipDialog = true,
            ),
            CheatOperation.AddGold(50),
            CheatOperation.SetGold(200),
            CheatOperation.SetActorStat(1, CheatActorStat.HP, 55),
            CheatOperation.SetVariable(7, "12.5"),
            CheatOperation.SetVariable(8, "quest-flag"),
            CheatOperation.SetSwitch(9, true),
            CheatOperation.Recover(RecoveryTarget.LEADER),
            CheatOperation.Recover(RecoveryTarget.PARTY),
            CheatOperation.Recover(RecoveryTarget.ENEMIES),
            CheatOperation.Recover(RecoveryTarget.ALL),
            CheatOperation.RefillResource(RecoveryTarget.PARTY, CheatResource.MP),
            CheatOperation.RefillResource(RecoveryTarget.PARTY, CheatResource.TP),
            CheatOperation.ClearStates(RecoveryTarget.PARTY),
            CheatOperation.SetHpToOne(RecoveryTarget.ENEMIES),
            CheatOperation.Defeat(RecoveryTarget.ENEMIES),
            CheatOperation.AddExperience(1, 500),
            CheatOperation.AddParameter(1, 2, 15),
            CheatOperation.AddInventory(CheatInventoryKind.ITEM, 3, 4),
            CheatOperation.SavePosition(0),
            CheatOperation.Teleport(7, 11, 12),
            CheatOperation.RecallPosition(0),
            CheatOperation.RefreshCatalog,
        ).mapIndexed { index, operation ->
            RuntimeCheatBridge.script(
                session,
                CheatCommand(index.toLong(), operation)
            )
        }
        val harness = """
            const assert = require("node:assert/strict");
            let interval;
            let cleared = false;
            globalThis.setInterval = callback => { interval = callback; return 1; };
            globalThis.clearInterval = () => { cleared = true; };
            $bridgeSource

            assert.equal(globalThis.__makerplayCheatBridge, true);
            assert.equal(Object.getOwnPropertyDescriptor(globalThis, "__makerplayApplyCheat").writable, false);
            assert.equal(Object.getOwnPropertyDescriptor(globalThis, "__makerplayApplyCheat").configurable, false);
            globalThis.__makerplayApplyCheat("${session.token}", null);
            globalThis.__makerplayApplyCheat("${session.token}", { v: 2, op: "gold", amount: 999 });
            globalThis.__makerplayApplyCheat("${session.token}", { v: 1, op: "unknown" });
            ${commands.joinToString("\n")}

            class Game_BattlerBase {
              constructor() {
                this.hpChanges = [];
                this.mpChanges = [];
                this.tpChanges = [];
                this.hp = 100;
                this.mp = 40;
                this.tp = 0;
                this.mhp = 100;
                this.mmp = 40;
                this.dead = false;
                this.statesCleared = 0;
                this.skillCostsPaid = 0;
              }
              gainHp(value) { this.hpChanges.push(value); }
              gainMp(value) { this.mpChanges.push(value); }
              gainTp(value) { this.tpChanges.push(value); }
              setHp(value) { this.hp = value; }
              setMp(value) { this.mp = value; }
              setTp(value) { this.tp = value; }
              maxTp() { return 100; }
              isAlive() { return !this.dead; }
              die() { this.dead = true; this.hp = 0; }
              deathStateId() { return 1; }
              addState(stateId) { if (stateId === 1) this.die(); }
              clearStates() { this.statesCleared += 1; }
              paySkillCost() { this.skillCostsPaid += 1; }
            }
            globalThis.Game_BattlerBase = Game_BattlerBase;
            interval();
            assert.equal(cleared, false, "polling must continue while player hooks are missing");

            class Game_CharacterBase {
              distancePerFrame() { return 0.0625; }
              realMoveSpeed() { return 4; }
            }
            class Game_Player extends Game_CharacterBase {}
            globalThis.Game_Player = Game_Player;

            const actor = new Game_BattlerBase();
            const companion = new Game_BattlerBase();
            const npc = new Game_CharacterBase();
            const player = new Game_Player();
            player.x = 4;
            player.y = 5;
            player.direction = () => 6;
            const transfers = [];
            player.reserveTransfer = (...args) => transfers.push(args);
            actor.recovered = 0;
            actor.recoverAll = () => { actor.recovered += 1; };
            actor.actorId = () => 1;
            actor.name = () => "Hero";
            actor.level = 3;
            actor.currentExp = () => actor.experience;
            companion.recovered = 0;
            companion.recoverAll = () => { companion.recovered += 1; };
            companion.actorId = () => 2;
            companion.name = () => "Friend";
            companion.level = 2;
            companion.currentExp = () => 0;
            let gold = 0;
            const variables = new Map();
            const switches = new Map();
            const catalogMessages = [];
            actor.experience = 0;
            actor.parameters = [];
            actor.gainExp = value => { actor.experience += value; };
            actor.addParam = (id, value) => { actor.parameters.push([id, value]); };
            const inventoryChanges = [];
            const itemCounts = new Map([[3, 0]]);
            globalThis.makerplayCheatCatalog = { postMessage: message => catalogMessages.push(JSON.parse(message)) };
            globalThis.${'$'}gameParty = {
              gold: () => gold,
              gainGold: value => { gold += value; },
              gainItem: (item, amount) => {
                inventoryChanges.push([item.id, amount]);
                itemCounts.set(item.id, (itemCounts.get(item.id) || 0) + amount);
              },
              numItems: item => itemCounts.get(item.id) || 0,
              leader: () => actor,
              members: () => [actor, companion],
              allMembers: () => [actor, companion],
            };
            globalThis.SceneManager = {
              determineRepeatNumber() { return 1; },
            };
            globalThis.${'$'}dataSystem = {
              variables: [], switches: [],
            };
            globalThis.${'$'}dataSystem.variables[2] = "Player level";
            globalThis.${'$'}dataSystem.variables[3] = "   ";
            globalThis.${'$'}dataSystem.switches[4] = "Door open";
            globalThis.${'$'}dataSystem.switches[5] = "";
            globalThis.${'$'}dataItems = [];
            globalThis.${'$'}dataWeapons = [];
            globalThis.${'$'}dataArmors = [];
            globalThis.${'$'}dataItems[3] = { id: 3, name: "Potion" };
            globalThis.${'$'}gameActors = { actor: id => id === 1 ? actor : null };
            globalThis.${'$'}gameMap = { mapId: () => 2 };
            globalThis.${'$'}gameVariables = {
              value: id => id === 2 ? 42 : 0,
              setValue: (id, value) => variables.set(id, value),
            };
            globalThis.${'$'}gameSwitches = {
              value: id => id === 4,
              setValue: (id, value) => switches.set(id, value),
            };
            const enemy = {
              dead: false,
              hp: 100,
              recovered: 0,
              recoverAll: () => { enemy.recovered += 1; },
              deathStateId: () => 1,
              isAlive: () => true,
              die: () => { enemy.dead = "die"; },
              setHp: value => { enemy.hp = value; },
              addState: state => { enemy.dead = `state:${'$'}{state}`; },
            };
            globalThis.${'$'}gameTroop = { members: () => [enemy] };
            globalThis.${'$'}gamePlayer = player;
            interval();
            globalThis.__makerplayApplyCheat("wrong-token", { v: 1, op: "gold", amount: 999 });
            globalThis.__makerplayApplyCheat("wrong-token", { v: 1, op: "flags", godMode: false, infiniteHp: false, infiniteMp: false, playerSpeedMultiplier: 8 });
            assert.equal(cleared, false, "active modifiers must keep resource maintenance running");
            assert.equal(gold, 200);
            assert.equal(variables.get(7), 12.5);
            assert.equal(variables.get(8), "quest-flag");
            assert.equal(switches.get(9), true);
            assert.equal(actor.recovered, 3);
            assert.equal(companion.recovered, 2);
            assert.equal(actor.hp, 55, "forced setActorStat must bypass god-mode protection");
            assert.equal(actor.mp, 40);
            assert.equal(companion.mp, 40);
            assert.equal(actor.tp, 100);
            assert.equal(companion.tp, 100);
            assert.equal(actor.statesCleared, 1);
            assert.equal(companion.statesCleared, 1);
            assert.equal(enemy.recovered, 2);
            assert.equal(enemy.hp, 1);
            assert.equal(enemy.dead, "die");
            assert.equal(actor.experience, 500);
            assert.deepEqual(actor.parameters, [[2, 15]]);
            assert.deepEqual(inventoryChanges, [[3, 4]]);
            assert.deepEqual(transfers, [[7, 11, 12, 6, 0], [2, 4, 5, 6, 0]]);
            assert.equal(player._through, true);
            assert.equal(player.distancePerFrame(), 0.25);
            assert.equal(npc.distancePerFrame(), 0.0625);
            assert.equal(SceneManager.determineRepeatNumber(1), 2);
            assert.deepEqual(catalogMessages[0].variables, [{ id: 2, name: "Player level", value: "42" }]);
            assert.deepEqual(catalogMessages[0].switches, [{ id: 4, name: "Door open", value: "ON" }]);
            assert.equal(catalogMessages[0].actors[0].name, "Hero");
            assert.equal(catalogMessages[0].items[0].id, 3);

            actor.gainHp(-5);
            actor.gainMp(-6);
            actor.setHp(25);
            actor.setMp(10);
            actor.gainTp(-20);
            actor.setTp(10);
            actor.paySkillCost({});
            actor.addState(actor.deathStateId());
            actor.die();
            actor.gainHp(4);
            actor.gainMp(3);
            assert.equal(actor.hp, 100);
            assert.equal(actor.mp, 40);
            assert.equal(actor.tp, 100);
            assert.equal(actor.dead, false);
            assert.equal(actor.skillCostsPaid, 0);
            assert.deepEqual(actor.hpChanges, []);
            assert.deepEqual(actor.mpChanges, []);
            assert.deepEqual(actor.tpChanges, []);
            const enemyBattler = new Game_BattlerBase();
            enemyBattler.isActor = () => false;
            enemyBattler.gainHp(-7);
            enemyBattler.gainMp(-8);
            assert.deepEqual(enemyBattler.hpChanges, [-7]);
            assert.deepEqual(enemyBattler.mpChanges, [-8]);

            ${
            RuntimeCheatBridge.script(
                session,
                CheatCommand(
                    20,
                    CheatOperation.SetFlags(
                        godMode = false,
                        infiniteHp = true,
                        infiniteMp = false,
                        playerSpeedMultiplier = 1.0,
                    ),
                )
            )
        }
            assert.equal(player.distancePerFrame(), 0.0625);
            actor.gainHp(-2);
            actor.gainMp(-3);
            assert.deepEqual(actor.hpChanges, []);
            assert.deepEqual(actor.mpChanges, [-3]);
            Game_BattlerBase.prototype.gainHp = function(value) { this.hpChanges.push(`late:${'$'}{value}`); };
            interval();
            actor.gainHp(-9);
            assert.deepEqual(actor.hpChanges, [], "late plugin overrides must be wrapped again");
            globalThis.__makerplayApplyCheat("${session.token}", {
              v: 1, op: "flags", godMode: false, infiniteHp: false, infiniteMp: false,
              playerSpeedMultiplier: 4,
            });
            Game_Player.prototype.distancePerFrame = function() { return 0.1; };
            interval();
            assert.equal(player.distancePerFrame(), 0.4, "late speed overrides must be wrapped again");
            assert.equal(player.realMoveSpeed(), 6, "real move speed must include the selected multiplier");
            globalThis.__makerplayApplyCheat("${session.token}", { v: 1, op: "defeat", target: "party" });
            assert.equal(actor.dead, true);
            assert.equal(companion.dead, true);

            globalThis.__makerplayApplyCheat("${session.token}", { v: 1, op: "gold", amount: 1000000001 });
            globalThis.__makerplayApplyCheat("${session.token}", { v: 1, op: "variable", id: 0, value: 1 });
            globalThis.__makerplayApplyCheat("${session.token}", { v: 1, op: "switch", id: 10000, enabled: true });
            assert.equal(gold, 200);
            assert.equal(variables.has(0), false);
            assert.equal(switches.has(10000), false);
            globalThis.__makerplayApplyCheat("${session.token}", { v: 1, op: "recover", target: "arbitrary();" });
            assert.equal(actor.recovered, 3);
            assert.equal(companion.recovered, 2);
            assert.equal(enemy.recovered, 2);

            delete globalThis.Game_Player;
            globalThis.__makerplayApplyCheat("${session.token}", { v: 1, op: "gold", amount: 5 });
            assert.equal(gold, 205, "one-shot cheats must not depend on the speed hook");

            const delayedEnemy = {
              dead: false,
              isAlive: () => true,
              die: () => { delayedEnemy.dead = true; },
            };
            globalThis.${'$'}gameTroop = { members: () => [] };
            globalThis.__makerplayApplyCheat("${session.token}", { v: 1, op: "defeat", target: "enemies" });
            globalThis.${'$'}gameTroop = { members: () => [delayedEnemy] };
            interval();
            assert.equal(delayedEnemy.dead, true, "queued defeat must wait for an active troop");
        """.trimIndent()
        val scriptFile = Files.createTempFile("runtime-cheat-bridge", ".js")
        try {
            Files.write(scriptFile, harness.toByteArray(Charsets.UTF_8))
            val process = ProcessBuilder("node", scriptFile.toString())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
        } finally {
            Files.deleteIfExists(scriptFile)
        }
    }
}
