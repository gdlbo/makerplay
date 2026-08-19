(() => {
  "use strict";
  if (globalThis.__makerplayCheatBridge) return;
  const sessionToken = __MAKERPLAY_SESSION_TOKEN__;
  const state = {
    godMode: false,
    infiniteHp: false,
    infiniteMp: false,
    playerSpeedMultiplier: 1,
    noClip: false,
    pending: [],
    forcedMutationDepth: 0,
    savedPositions: [null, null, null],
  };
  let distanceCalculationDepth = 0;
  const boundedId = value => Number.isInteger(value) && value >= 1 && value <= 9999;
  let flushTimer = null;
  let idleAttempts = 0;
  const scheduleFlush = () => {
    if (flushTimer !== null) return;
    idleAttempts = 0;
    flushTimer = setInterval(() => {
      idleAttempts += 1;
      const hooksReady = install();
      maintainPartyResources();
      maintainPlayerFlags();
      if (state.pending.length > 0) flush();
      const flagsActive = state.godMode || state.infiniteHp || state.infiniteMp ||
        state.playerSpeedMultiplier !== 1 || state.noClip;
      if (state.pending.length === 0 && !flagsActive && (hooksReady || idleAttempts >= 200)) {
        clearInterval(flushTimer);
        flushTimer = null;
      }
    }, 50);
  };
  const queue = payload => {
    if (state.pending.length < 32) state.pending.push(payload);
    scheduleFlush();
  };
  const partyMembers = () => {
    if (!globalThis.$gameParty) return null;
    if (typeof $gameParty.allMembers === "function") return $gameParty.allMembers();
    if (typeof $gameParty.members === "function") return $gameParty.members();
    return null;
  };
  const affectsPlayer = battler => {
    if (battler && typeof battler.isActor === "function") return battler.isActor();
    const members = partyMembers();
    return Array.isArray(members) && members.includes(battler);
  };
  const protectionEnabled = battler => state.forcedMutationDepth === 0 && affectsPlayer(battler);
  const forceMutation = action => {
    state.forcedMutationDepth += 1;
    try {
      return action();
    } finally {
      state.forcedMutationDepth -= 1;
    }
  };
  const installBattlerHooks = () => {
    let battlerReady = false;
    if (globalThis.Game_BattlerBase && Game_BattlerBase.prototype) {
      const proto = Game_BattlerBase.prototype;
      if (typeof proto.gainHp === "function") {
        const original = proto.gainHp;
        if (proto.__makerplayCheatGainHp !== original) {
          const wrapper = function(value) {
            if (protectionEnabled(this) && (state.godMode || state.infiniteHp)) {
              return typeof this.setHp === "function" && Number.isFinite(this.mhp)
                ? this.setHp(this.mhp) : undefined;
            }
            return original.call(this, value);
          };
          proto.gainHp = wrapper;
          Object.defineProperty(proto, "__makerplayCheatGainHp", { value: wrapper, writable: true });
        }
        battlerReady = true;
      }
      if (typeof proto.gainMp === "function") {
        const original = proto.gainMp;
        if (proto.__makerplayCheatGainMp !== original) {
          const wrapper = function(value) {
            if (protectionEnabled(this) && (state.godMode || state.infiniteMp)) {
              return typeof this.setMp === "function" && Number.isFinite(this.mmp)
                ? this.setMp(this.mmp) : undefined;
            }
            return original.call(this, value);
          };
          proto.gainMp = wrapper;
          Object.defineProperty(proto, "__makerplayCheatGainMp", { value: wrapper, writable: true });
        }
        battlerReady = battlerReady && true;
      }
      if (typeof proto.setHp === "function") {
        const original = proto.setHp;
        if (proto.__makerplayCheatSetHp !== original) {
          const wrapper = function(value) {
            const protectedValue = protectionEnabled(this) && (state.godMode || state.infiniteHp) &&
              Number.isFinite(this.mhp) ? this.mhp : value;
            return original.call(this, protectedValue);
          };
          proto.setHp = wrapper;
          Object.defineProperty(proto, "__makerplayCheatSetHp", { value: wrapper, writable: true });
        }
      }
      if (typeof proto.setMp === "function") {
        const original = proto.setMp;
        if (proto.__makerplayCheatSetMp !== original) {
          const wrapper = function(value) {
            const protectedValue = protectionEnabled(this) && (state.godMode || state.infiniteMp) &&
              Number.isFinite(this.mmp) ? this.mmp : value;
            return original.call(this, protectedValue);
          };
          proto.setMp = wrapper;
          Object.defineProperty(proto, "__makerplayCheatSetMp", { value: wrapper, writable: true });
        }
      }
      if (typeof proto.gainTp === "function") {
        const original = proto.gainTp;
        if (proto.__makerplayCheatGainTp !== original) {
          const wrapper = function(value) {
            if (protectionEnabled(this) && state.godMode) {
              return typeof this.setTp === "function" && typeof this.maxTp === "function"
                ? this.setTp(this.maxTp()) : undefined;
            }
            return original.call(this, value);
          };
          proto.gainTp = wrapper;
          Object.defineProperty(proto, "__makerplayCheatGainTp", { value: wrapper, writable: true });
        }
      }
      if (typeof proto.setTp === "function") {
        const original = proto.setTp;
        if (proto.__makerplayCheatSetTp !== original) {
          const wrapper = function(value) {
            const protectedValue = protectionEnabled(this) && state.godMode &&
              typeof this.maxTp === "function" ? this.maxTp() : value;
            return original.call(this, protectedValue);
          };
          proto.setTp = wrapper;
          Object.defineProperty(proto, "__makerplayCheatSetTp", { value: wrapper, writable: true });
        }
      }
      if (typeof proto.paySkillCost === "function") {
        const original = proto.paySkillCost;
        if (proto.__makerplayCheatPaySkillCost !== original) {
          const wrapper = function(skill) {
            if (protectionEnabled(this) && (state.godMode || state.infiniteMp)) return;
            return original.call(this, skill);
          };
          proto.paySkillCost = wrapper;
          Object.defineProperty(proto, "__makerplayCheatPaySkillCost", { value: wrapper, writable: true });
        }
      }
      if (typeof proto.addState === "function") {
        const original = proto.addState;
        if (proto.__makerplayCheatAddState !== original) {
          const wrapper = function(stateId) {
            if (protectionEnabled(this) && state.godMode && typeof this.deathStateId === "function" &&
                stateId === this.deathStateId()) return;
            return original.call(this, stateId);
          };
          proto.addState = wrapper;
          Object.defineProperty(proto, "__makerplayCheatAddState", { value: wrapper, writable: true });
        }
      }
      if (typeof proto.die === "function") {
        const original = proto.die;
        if (proto.__makerplayCheatDie !== original) {
          const wrapper = function() {
            if (protectionEnabled(this) && state.godMode) return;
            return original.call(this);
          };
          proto.die = wrapper;
          Object.defineProperty(proto, "__makerplayCheatDie", { value: wrapper, writable: true });
        }
      }
    }
    if (globalThis.Game_Action && Game_Action.prototype) {
      const proto = Game_Action.prototype;
      if (typeof proto.executeHpDamage === "function") {
        const original = proto.executeHpDamage;
        if (proto.__makerplayCheatExecuteHpDamage !== original) {
          const wrapper = function(target, value) {
            if (protectionEnabled(target) && (state.godMode || state.infiniteHp) && value > 0) return;
            return original.call(this, target, value);
          };
          proto.executeHpDamage = wrapper;
          Object.defineProperty(proto, "__makerplayCheatExecuteHpDamage", { value: wrapper, writable: true });
        }
        battlerReady = true;
      }
      if (typeof proto.executeMpDamage === "function") {
        const original = proto.executeMpDamage;
        if (proto.__makerplayCheatExecuteMpDamage !== original) {
          const wrapper = function(target, value) {
            if (protectionEnabled(target) && (state.godMode || state.infiniteMp) && value > 0) return;
            return original.call(this, target, value);
          };
          proto.executeMpDamage = wrapper;
          Object.defineProperty(proto, "__makerplayCheatExecuteMpDamage", { value: wrapper, writable: true });
        }
        battlerReady = true;
      }
    }
    return battlerReady;
  };
  const installPlayerHooks = () => {
    if (!globalThis.Game_Player || !Game_Player.prototype) return false;
    const proto = Game_Player.prototype;
    let ready = false;
    if (typeof proto.distancePerFrame === "function") {
      const original = proto.distancePerFrame;
      if (proto.__makerplayCheatDistancePerFrame !== original) {
        const wrapper = function() {
          distanceCalculationDepth += 1;
          try {
            return original.call(this) * state.playerSpeedMultiplier;
          } finally {
            distanceCalculationDepth -= 1;
          }
        };
        proto.distancePerFrame = wrapper;
        Object.defineProperty(proto, "__makerplayCheatDistancePerFrame", { value: wrapper, writable: true });
      }
      ready = true;
    }
    if (typeof proto.realMoveSpeed === "function") {
      const original = proto.realMoveSpeed;
      if (proto.__makerplayCheatRealMoveSpeed !== original) {
        const wrapper = function() {
          const speed = original.call(this);
          return distanceCalculationDepth > 0 || state.playerSpeedMultiplier === 1
            ? speed : speed + Math.log(state.playerSpeedMultiplier) / Math.LN2;
        };
        proto.realMoveSpeed = wrapper;
        Object.defineProperty(proto, "__makerplayCheatRealMoveSpeed", { value: wrapper, writable: true });
      }
      ready = true;
    }
    return ready;
  };
  const install = () => {
    const battlerReady = installBattlerHooks();
    const playerReady = installPlayerHooks();
    return battlerReady && playerReady;
  };
  const maintainPlayerFlags = () => {
    if (state.noClip && globalThis.$gamePlayer && $gamePlayer._through !== true) {
      $gamePlayer._through = true;
    }
  };
  const maintainPartyResources = () => {
    if (!state.godMode && !state.infiniteHp && !state.infiniteMp) return;
    const members = partyMembers();
    if (!Array.isArray(members)) return;
    members.forEach(actor => {
      if (!actor) return;
      if ((state.godMode || state.infiniteHp) && typeof actor.setHp === "function" &&
          Number.isFinite(actor.mhp)) actor.setHp(actor.mhp);
      if ((state.godMode || state.infiniteMp) && typeof actor.setMp === "function" &&
          Number.isFinite(actor.mmp)) actor.setMp(actor.mmp);
      if (state.godMode && typeof actor.setTp === "function" && typeof actor.maxTp === "function") {
        actor.setTp(actor.maxTp());
      }
    });
  };
  const battlersForTarget = target => {
    const needsParty = target === "leader" || target === "party" || target === "all";
    const needsEnemies = target === "enemies" || target === "all";
    if (!needsParty && !needsEnemies) return null;
    if (needsParty && (!globalThis.$gameParty ||
        (target === "leader" && typeof $gameParty.leader !== "function") ||
        (target !== "leader" && !Array.isArray(partyMembers())))) return null;
    if (needsEnemies && (!globalThis.$gameTroop || typeof $gameTroop.members !== "function")) return null;
    let battlers = [];
    if (target === "leader") {
      const leader = $gameParty.leader();
      if (!leader) return null;
      battlers = [leader];
    } else if (needsParty) {
      const party = partyMembers();
      if (!Array.isArray(party) || party.length === 0) return null;
      battlers = battlers.concat(party);
    }
    if (needsEnemies) {
      const enemies = $gameTroop.members();
      if (!Array.isArray(enemies) || enemies.length === 0) return null;
      battlers = battlers.concat(enemies);
    }
    return battlers;
  };
  const recover = target => {
    const battlers = battlersForTarget(target);
    if (!battlers || !battlers.every(battler => typeof battler.recoverAll === "function")) return false;
    forceMutation(() => battlers.forEach(battler => battler.recoverAll()));
    return true;
  };
  const refillResource = (target, resource) => {
    const battlers = battlersForTarget(target);
    if (!battlers || (resource !== "mp" && resource !== "tp")) return false;
    if (resource === "mp" && !battlers.every(battler => typeof battler.setMp === "function" &&
        Number.isFinite(battler.mmp))) return false;
    if (resource === "tp" && !battlers.every(battler => typeof battler.setTp === "function" &&
        typeof battler.maxTp === "function")) return false;
    forceMutation(() => battlers.forEach(battler => {
      if (resource === "mp") battler.setMp(battler.mmp);
      else battler.setTp(battler.maxTp());
    }));
    return true;
  };
  const clearStates = target => {
    const battlers = battlersForTarget(target);
    if (!battlers || !battlers.every(battler => typeof battler.clearStates === "function")) return false;
    forceMutation(() => battlers.forEach(battler => battler.clearStates()));
    return true;
  };
  const setHpToOne = target => {
    const battlers = battlersForTarget(target);
    if (!battlers || !battlers.every(battler => typeof battler.isAlive === "function" &&
        typeof battler.setHp === "function")) return false;
    forceMutation(() => battlers.forEach(battler => {
      if (battler.isAlive()) battler.setHp(1);
    }));
    return true;
  };
  const defeat = target => {
    const battlers = battlersForTarget(target);
    if (!battlers ||
        !battlers.every(battler => battler && typeof battler.isAlive === "function" &&
          (typeof battler.die === "function" ||
            (typeof battler.addState === "function" && typeof battler.deathStateId === "function")))) {
      return false;
    }
    forceMutation(() => battlers.forEach(battler => {
      if (!battler.isAlive()) return;
      if (typeof battler.die === "function") {
        battler.die();
      } else {
        battler.addState(battler.deathStateId());
      }
    }));
    return true;
  };
  const valueText = value => {
    if (typeof value === "string") return value.slice(0, 160);
    if (value === null || value === undefined) return "0";
    try { return JSON.stringify(value).slice(0, 160); } catch (_) { return String(value).slice(0, 160); }
  };
  const sendCatalog = () => {
    if (!globalThis.$dataSystem || !globalThis.$gameVariables || !globalThis.$gameSwitches ||
        !Array.isArray($dataSystem.variables) || !Array.isArray($dataSystem.switches) ||
        typeof $gameVariables.value !== "function" || typeof $gameSwitches.value !== "function") return false;
    const variables = [];
    const switches = [];
    for (let id = 1; id < $dataSystem.variables.length && variables.length < 2000; id += 1) {
      const name = typeof $dataSystem.variables[id] === "string" ? $dataSystem.variables[id].trim() : "";
      if (name) variables.push({ id, name: name.slice(0, 128), value: valueText($gameVariables.value(id)) });
    }
    for (let id = 1; id < $dataSystem.switches.length && switches.length < 2000; id += 1) {
      const name = typeof $dataSystem.switches[id] === "string" ? $dataSystem.switches[id].trim() : "";
      if (name) switches.push({ id, name: name.slice(0, 128), value: $gameSwitches.value(id) ? "ON" : "OFF" });
    }
    if (globalThis.makerplayCheatCatalog && typeof globalThis.makerplayCheatCatalog.postMessage === "function") {
      globalThis.makerplayCheatCatalog.postMessage(JSON.stringify({ v: 1, token: sessionToken, variables, switches }));
    }
    return true;
  };
  const apply = (candidateToken, payload) => {
    if (candidateToken !== sessionToken) return;
    if (!payload || payload.v !== 1 || typeof payload.op !== "string") return;
    if (payload.op === "flags") {
      state.godMode = payload.godMode === true;
      state.infiniteHp = payload.infiniteHp === true;
      state.infiniteMp = payload.infiniteMp === true;
      state.playerSpeedMultiplier = Number.isFinite(payload.playerSpeedMultiplier) &&
        payload.playerSpeedMultiplier >= 1 && payload.playerSpeedMultiplier <= 8
          ? payload.playerSpeedMultiplier : 1;
      state.noClip = payload.noClip === true;
      if (globalThis.$gamePlayer) $gamePlayer._through = state.noClip;
      install();
      scheduleFlush();
      return;
    }
    if (payload.op === "catalog") {
      if (!sendCatalog()) queue(payload);
      return;
    }
    install();
    if (payload.op === "gold") {
      if (!globalThis.$gameParty) { queue(payload); return; }
      if (Number.isInteger(payload.amount) && Math.abs(payload.amount) <= 1000000000) $gameParty.gainGold(payload.amount);
    }
    if (payload.op === "experience") {
      if (!globalThis.$gameActors || typeof $gameActors.actor !== "function") { queue(payload); return; }
      const actor = boundedId(payload.actorId) ? $gameActors.actor(payload.actorId) : null;
      if (!actor || typeof actor.gainExp !== "function") { queue(payload); return; }
      if (Number.isInteger(payload.amount) && Math.abs(payload.amount) <= 1000000000) {
        actor.gainExp(payload.amount);
      }
    }
    if (payload.op === "parameter") {
      if (!globalThis.$gameActors || typeof $gameActors.actor !== "function") { queue(payload); return; }
      const actor = boundedId(payload.actorId) ? $gameActors.actor(payload.actorId) : null;
      if (!actor || typeof actor.addParam !== "function") { queue(payload); return; }
      if (Number.isInteger(payload.parameterId) && payload.parameterId >= 0 && payload.parameterId <= 7 &&
          Number.isInteger(payload.amount) && Math.abs(payload.amount) <= 1000000000) {
        actor.addParam(payload.parameterId, payload.amount);
      }
    }
    if (payload.op === "inventory") {
      const tables = { item: globalThis.$dataItems, weapon: globalThis.$dataWeapons, armor: globalThis.$dataArmors };
      const table = tables[payload.kind];
      if (!globalThis.$gameParty || typeof $gameParty.gainItem !== "function" || !Array.isArray(table)) {
        queue(payload); return;
      }
      if (!boundedId(payload.id)) return;
      if (!table[payload.id]) { queue(payload); return; }
      if (Number.isInteger(payload.amount) && Math.abs(payload.amount) <= 9999) {
        $gameParty.gainItem(table[payload.id], payload.amount);
      }
    }
    if (payload.op === "teleport") {
      if (!globalThis.$gamePlayer || typeof $gamePlayer.reserveTransfer !== "function") { queue(payload); return; }
      if (boundedId(payload.mapId) && Number.isInteger(payload.x) && payload.x >= 0 && payload.x <= 9999 &&
          Number.isInteger(payload.y) && payload.y >= 0 && payload.y <= 9999) {
        const direction = typeof $gamePlayer.direction === "function" ? $gamePlayer.direction() : 2;
        $gamePlayer.reserveTransfer(payload.mapId, payload.x, payload.y, direction, 0);
      }
    }
    if (payload.op === "savePosition") {
      if (!globalThis.$gameMap || typeof $gameMap.mapId !== "function" || !globalThis.$gamePlayer) {
        queue(payload); return;
      }
      if (Number.isInteger(payload.slot) && payload.slot >= 0 && payload.slot < state.savedPositions.length) {
        state.savedPositions[payload.slot] = { mapId: $gameMap.mapId(), x: $gamePlayer.x, y: $gamePlayer.y };
      }
    }
    if (payload.op === "recallPosition") {
      if (!Number.isInteger(payload.slot) || payload.slot < 0 || payload.slot >= state.savedPositions.length) return;
      const position = state.savedPositions[payload.slot];
      if (!position) { queue(payload); return; }
      apply(sessionToken, { v: 1, op: "teleport", mapId: position.mapId, x: position.x, y: position.y });
    }
    if (payload.op === "variable") {
      if (!globalThis.$gameVariables) { queue(payload); return; }
      if (boundedId(payload.id) && Number.isFinite(payload.value) && Math.abs(payload.value) <= 1000000000) {
        $gameVariables.setValue(payload.id, payload.value);
        sendCatalog();
      }
    }
    if (payload.op === "switch") {
      if (!globalThis.$gameSwitches) { queue(payload); return; }
      if (boundedId(payload.id)) $gameSwitches.setValue(payload.id, payload.enabled === true);
      sendCatalog();
    }
    if (payload.op === "recover") {
      if (!recover(payload.target)) queue(payload);
    }
    if (payload.op === "refill" && !refillResource(payload.target, payload.resource)) queue(payload);
    if (payload.op === "clearStates" && !clearStates(payload.target)) queue(payload);
    if (payload.op === "hpOne" && !setHpToOne(payload.target)) queue(payload);
    if (payload.op === "defeat" && !defeat(payload.target)) queue(payload);
    if (payload.op === "defeatEnemies" && !defeat("enemies")) queue(payload);
  };
  const flush = () => {
    const pending = state.pending; state.pending = [];
    pending.forEach(payload => apply(sessionToken, payload));
    return state.pending.length === 0;
  };
  Object.defineProperty(globalThis, "__makerplayApplyCheat", { value: apply });
  Object.defineProperty(globalThis, "__makerplayCheatBridge", { value: true });
  scheduleFlush();
})();