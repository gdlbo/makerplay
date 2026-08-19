(function() {
  "use strict";
  var token = __MAKERPLAY_SESSION_TOKEN__;
  var nextId = 1;
  var installAttempts = 0;
  var installGuardChecks = 0;
  var stableInstallChecks = 0;
  var dataManagerInstallAttempts = 0;
  var knownKeys = null;
  var readCache = new Map();
  var installedStorageFunctions = null;
  var cachedChars = 0;
  var maxCachedChars = 4 * 1024 * 1024;

  function keyFor(savefileId) {
    var id = Number(savefileId);
    if (Number.isFinite(id) && Math.floor(id) === id) {
      if (id < 0) return "config";
      if (id === 0) return "global";
      return "file" + id;
    }
    if (typeof savefileId === "string" && /^[A-Za-z0-9][A-Za-z0-9._-]{0,106}$/.test(savefileId)) {
      return "plugin-" + savefileId;
    }
    throw new Error("Invalid save ID");
  }

  function backupKey(savefileId) {
    return keyFor(savefileId) + "-engine-backup";
  }

  function transaction(op, key, data) {
    var id = "mv-" + nextId++;
    var request = { v: 1, id: id, op: op, key: key };
    if (data !== undefined) request.data = data;
    var nativeBridge = window.__MAKERPLAY_OBJECT_NAME__;
    if (!nativeBridge) throw new Error("Native save bridge unavailable");
    var response = JSON.parse(nativeBridge.transact(token, JSON.stringify(request)));
    if (response.v !== 1 || response.id !== id || response.ok !== true) {
      throw new Error("Native save operation failed");
    }
    return response.data === undefined ? null : response.data;
  }

  function encodeAscii(value) {
    return btoa(value);
  }

  function decodeAscii(value) {
    return atob(value);
  }

  function refreshKnownKeys() {
    var keys = transaction("list", "entries");
    knownKeys = new Set(Array.isArray(keys) ? keys : []);
  }

  function cacheDelete(key) {
    var cached = readCache.get(key);
    if (cached === undefined) return;
    cachedChars -= cached.length;
    readCache.delete(key);
  }

  function cacheGet(key) {
    var cached = readCache.get(key);
    if (cached === undefined) return undefined;
    readCache.delete(key);
    readCache.set(key, cached);
    return cached;
  }

  function cachePut(key, value) {
    cacheDelete(key);
    if (value.length > maxCachedChars) return;
    readCache.set(key, value);
    cachedChars += value.length;
    while (cachedChars > maxCachedChars && readCache.size > 0) {
      cacheDelete(readCache.keys().next().value);
    }
  }

  function loadByKey(key) {
    if (knownKeys && !knownKeys.has(key)) return "";
    var cached = cacheGet(key);
    if (cached !== undefined) return cached;
    var data = transaction("read", key);
    if (data === null) {
      if (knownKeys) knownKeys.delete(key);
      return "";
    }
    var json = LZString.decompressFromBase64(decodeAscii(data));
    if (typeof json !== "string") return "";
    cachePut(key, json);
    return json;
  }

  function installGlobalInfoFastPath() {
    var data = window.DataManager;
    if (!data || !window.JsonEx || !window.StorageManager) {
      if (++dataManagerInstallAttempts < 1000) setTimeout(installGlobalInfoFastPath, 10);
      return;
    }
    if (data.__makerplayGlobalInfoFastPath) return;
    var original = data.loadGlobalInfo;
    if (typeof original !== "function") {
      if (++dataManagerInstallAttempts < 1000) setTimeout(installGlobalInfoFastPath, 10);
      return;
    }
    var source = Function.prototype.toString.call(original);
    if (source.indexOf("_globaInfoCache") < 0 || source.indexOf("reconstructGlobalInfo") < 0) {
      if (++dataManagerInstallAttempts < 1000) setTimeout(installGlobalInfoFastPath, 10);
      return;
    }
    Object.defineProperty(data, "__makerplayGlobalInfoFastPath", { value: true });
    data.loadGlobalInfo = function() {
      if (this._globaInfoCache) return this._globaInfoCache;
      try {
        var json = StorageManager.load(0);
        if (json) {
          var globalInfo = JsonEx.parse(json);
          if (Array.isArray(globalInfo)) return this._globaInfoCache = globalInfo;
        }
      } catch (_) {
        // Preserve the game's full reconstruction fallback.
      }
      return original.call(this);
    };
  }

  function maintainStoragePatch() {
    var storage = window.StorageManager;
    var changed = false;
    if (storage && installedStorageFunctions) {
      Object.keys(installedStorageFunctions).forEach(function(name) {
        if (storage[name] !== installedStorageFunctions[name]) {
          storage[name] = installedStorageFunctions[name];
          changed = true;
        }
      });
    }
    stableInstallChecks = changed ? 0 : stableInstallChecks + 1;
    var loading = !window.document || document.readyState !== "complete";
    if (++installGuardChecks < 400 && (loading || stableInstallChecks < 20)) {
      setTimeout(maintainStoragePatch, 25);
    }
  }

  function install() {
    if (!window.StorageManager || !window.LZString) {
      if (++installAttempts < 1000) setTimeout(install, 10);
      return;
    }
    var storage = window.StorageManager;
    if (!storage.__makerplayNativeSaveInstalled) {
      Object.defineProperty(storage, "__makerplayNativeSaveInstalled", { value: true });
    }
    refreshKnownKeys();

    storage.save = function(savefileId, json) {
      var key = keyFor(savefileId);
      var compressed = LZString.compressToBase64(json);
      transaction("write", key, encodeAscii(compressed));
      knownKeys.add(key);
      cachePut(key, json);
    };
    storage.load = function(savefileId) {
      return loadByKey(keyFor(savefileId));
    };
    storage.exists = function(savefileId) {
      return knownKeys.has(keyFor(savefileId));
    };
    storage.remove = function(savefileId) {
      var key = keyFor(savefileId);
      transaction("delete", key);
      knownKeys.delete(key);
      cacheDelete(key);
    };
    storage.backup = function(savefileId) {
      var sourceKey = keyFor(savefileId);
      var targetKey = backupKey(savefileId);
      var data = transaction("read", sourceKey);
      if (data !== null) {
        transaction("write", targetKey, data);
        knownKeys.add(targetKey);
        var cached = cacheGet(sourceKey);
        if (cached !== undefined) cachePut(targetKey, cached);
      }
    };
    storage.backupExists = function(savefileId) {
      return knownKeys.has(backupKey(savefileId));
    };
    storage.cleanBackup = function(savefileId) {
      var key = backupKey(savefileId);
      transaction("delete", key);
      knownKeys.delete(key);
      cacheDelete(key);
    };
    storage.restoreBackup = function(savefileId) {
      var sourceKey = backupKey(savefileId);
      var targetKey = keyFor(savefileId);
      var data = transaction("read", sourceKey);
      if (data !== null) {
        transaction("write", targetKey, data);
        transaction("delete", sourceKey);
        knownKeys.add(targetKey);
        knownKeys.delete(sourceKey);
        cacheDelete(targetKey);
        var cached = cacheGet(sourceKey);
        cacheDelete(sourceKey);
        if (cached !== undefined) cachePut(targetKey, cached);
      }
    };
    installedStorageFunctions = {
      save: storage.save,
      load: storage.load,
      exists: storage.exists,
      remove: storage.remove,
      backup: storage.backup,
      backupExists: storage.backupExists,
      cleanBackup: storage.cleanBackup,
      restoreBackup: storage.restoreBackup
    };
    setTimeout(maintainStoragePatch, 0);
    setTimeout(installGlobalInfoFastPath, 0);
  }

  install();
})();