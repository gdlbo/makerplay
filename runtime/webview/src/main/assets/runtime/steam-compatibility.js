(() => {
  "use strict";
  if (globalThis.__makerplaySteamCompatibility) return;
  globalThis.__makerplaySteamCompatibility = true;

  const storageKey = "makerplay.steam.compat.v1";
  const loadState = () => {
    try {
      const value = JSON.parse(localStorage.getItem(storageKey) || "{}");
      return {
        achievements: value.achievements && typeof value.achievements === "object" ? value.achievements : {},
        stats: value.stats && typeof value.stats === "object" ? value.stats : {},
        cloud: value.cloud && typeof value.cloud === "object" ? value.cloud : {},
        profile: value.profile && typeof value.profile === "object" ? value.profile : null
      };
    } catch (_) {
      return { achievements: {}, stats: {}, cloud: {}, profile: null };
    }
  };
  const state = loadState();
  const saveState = () => {
    try {
      localStorage.setItem(storageKey, JSON.stringify(state));
      return true;
    } catch (_) {
      return false;
    }
  };
  const createProfile = () => {
    const random = new Uint32Array(1);
    if (globalThis.crypto?.getRandomValues) {
      globalThis.crypto.getRandomValues(random);
    } else {
      random[0] = (Date.now() ^ Math.floor(Math.random() * 0xffffffff)) >>> 0;
    }
    const accountId = random[0] >>> 0;
    return {
      screenName: "Player",
      accountId,
      staticAccountId: String(accountId),
      steamId: String(76561197960265728n + BigInt(accountId))
    };
  };
  if (!state.profile?.steamId) {
    state.profile = createProfile();
    saveState();
  }
  const callback = (fn, value) => {
    if (typeof fn === "function") queueMicrotask(() => fn(value));
  };
  const key = value => typeof value === "string" && value.length <= 256 ? value : "";
  const language = () => {
    const value = (navigator.language || "en").toLowerCase();
    if (value.startsWith("ru")) return "russian";
    if (value.startsWith("ja")) return "japanese";
    if (value.startsWith("zh-cn") || value.startsWith("zh-sg")) return "schinese";
    if (value.startsWith("zh")) return "tchinese";
    return "english";
  };
  const greenworks = {
    FriendFlags: { Immediate: 4 },
    init: () => true,
    initAPI: () => true,
    on: (event, listener) => {
      if (event === "game-overlay-activated") callback(listener, false);
      return true;
    },
    getSteamId: () => ({ ...state.profile }),
    getCurrentUILanguage: language,
    getCurrentGameLanguage: language,
    isSteamRunning: () => true,
    activateAchievement: (name, success, failure) => {
      name = key(name);
      if (!name) { callback(failure, false); return false; }
      state.achievements[name] = true;
      const saved = saveState();
      callback(saved ? success : failure, saved);
      return saved;
    },
    getAchievement: (name, success, failure) => {
      name = key(name);
      if (!name) { callback(failure, false); return false; }
      const value = !!state.achievements[name];
      callback(success, value);
      return value;
    },
    clearAchievement: (name, success, failure) => {
      name = key(name);
      if (!name) { callback(failure, false); return false; }
      delete state.achievements[name];
      const saved = saveState();
      callback(saved ? success : failure, saved);
      return saved;
    },
    indicateAchievementProgress: (name, current, maximum) => {
      name = key(name);
      current = Number(current);
      maximum = Number(maximum);
      if (!name || !Number.isFinite(current) || !Number.isFinite(maximum) || maximum <= 0) return false;
      state.stats[`achievement-progress:${name}`] = Math.max(0, Math.min(current, maximum));
      if (current >= maximum) state.achievements[name] = true;
      return saveState();
    },
    getNumberOfAchievements: () => Object.keys(state.achievements).length,
    getStatInt: name => Math.trunc(Number(state.stats[key(name)]) || 0),
    getStatFloat: name => Number(state.stats[key(name)]) || 0,
    setStat: (name, value) => {
      name = key(name);
      value = Number(value);
      if (!name || !Number.isFinite(value)) return false;
      state.stats[name] = value;
      return saveState();
    },
    storeStats: (success, failure) => {
      const saved = saveState();
      callback(saved ? success : failure, saved);
      return saved;
    },
    getNumberOfPlayers: (success) => {
      callback(success, 1);
      return true;
    },
    getFriendCount: () => 0,
    isCloudEnabled: () => false,
    isCloudEnabledForUser: () => false,
    activateGameOverlay: () => false,
    isGameOverlayEnabled: () => false,
    isSteamInBigPictureMode: () => false,
    activateGameOverlayToWebPage: () => false,
    getDLCCount: () => 0,
    isDLCInstalled: () => false,
    installDLC: () => false,
    uninstallDLC: () => false,
    // Locally imported games are entitled by definition; several plugins
    // (e.g. MadeWithMv) hard-block on these checks.
    isSubscribedApp: () => true,
    isAppInstalled: () => true,
    saveTextToFile: (name, contents, success, failure) => {
      name = key(name);
      if (!name || typeof contents !== "string") { callback(failure, "Invalid file"); return false; }
      state.cloud[name] = contents;
      const saved = saveState();
      callback(saved ? success : failure, saved ? undefined : "Unable to save file");
      return saved;
    },
    readTextFromFile: (name, success, failure) => {
      name = key(name);
      if (!name || !Object.prototype.hasOwnProperty.call(state.cloud, name)) {
        callback(failure, "File not found");
        return false;
      }
      callback(success, state.cloud[name]);
      return true;
    },
    deleteFile: (name, success, failure) => {
      name = key(name);
      if (!name || !Object.prototype.hasOwnProperty.call(state.cloud, name)) {
        callback(failure, false);
        return false;
      }
      delete state.cloud[name];
      const saved = saveState();
      callback(saved ? success : failure, saved);
      return saved;
    }
  };
  const nativeRequire = globalThis.require;
  if (typeof nativeRequire === "function" && !nativeRequire.__makerplayGreenworks) {
    const compatibleRequire = function(id) {
      if (/^(?:\.\/)?greenworks(?:\.js)?$/i.test(String(id))) return greenworks;
      return nativeRequire.apply(this, arguments);
    };
    Object.keys(nativeRequire).forEach(name => { compatibleRequire[name] = nativeRequire[name]; });
    compatibleRequire.__makerplayCommonJs = nativeRequire.__makerplayCommonJs;
    compatibleRequire.__makerplayGreenworks = true;
    globalThis.require = compatibleRequire;
  }
  const install = () => {
    const api = globalThis.OrangeGreenworks || globalThis.Hudell?.OrangeGreenworks;
    if (!api || api.__makerplayInstalled) return false;
    api.__makerplayInstalled = true;
    api.initialized = true;
    api.steamId = { ...state.profile };
    api.getScreenName = () => state.profile.screenName;
    api.getUILanguage = language;
    api.getGameLanguage = language;
    api.isSteamRunning = () => true;
    api.activateAchievement = name => {
      name = key(name);
      if (!name) return false;
      state.achievements[name] = true;
      return saveState();
    };
    api.getAchievement = name => !!state.achievements[key(name)];
    api.clearAchievement = name => {
      name = key(name);
      if (!name) return false;
      delete state.achievements[name];
      return saveState();
    };
    api.getNumberOfAchievements = () => Object.keys(state.achievements).length;
    api.getStatInt = name => Math.trunc(Number(state.stats[key(name)]) || 0);
    api.getStatFloat = name => Number(state.stats[key(name)]) || 0;
    api.setStat = (name, value) => {
      name = key(name);
      value = Number(value);
      if (!name || !Number.isFinite(value)) return false;
      state.stats[name] = value;
      return saveState();
    };
    api.storeStats = saveState;
    api.getFriendCount = () => 0;
    api.isCloudEnabled = () => false;
    api.isCloudEnabledForUser = () => false;
    api.activateGameOverlay = () => false;
    api.isGameOverlayEnabled = () => false;
    api.activateGameOverlayToWebPage = () => false;
    api.getDLCCount = () => 0;
    api.isDLCInstalled = () => false;
    api.installDLC = () => false;
    api.uninstallDLC = () => false;
    // Locally imported games are entitled by definition; several plugins
    // (e.g. MadeWithMv) hard-block on these checks.
    api.isSubscribedApp = () => true;
    api.isAppInstalled = () => true;
    return true;
  };

  if (install()) return;
  let attempts = 0;
  const timer = setInterval(() => {
    attempts += 1;
    if (install() || attempts >= 200) clearInterval(timer);
  }, 50);
})();