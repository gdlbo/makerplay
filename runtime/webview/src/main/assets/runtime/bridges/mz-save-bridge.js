(() => {
  "use strict";
  const nativeBridge = globalThis.__MAKERPLAY_OBJECT_NAME__;
  if (!nativeBridge || globalThis.__makerplayMzSaveBridge) return;
  const pending = new Map();
  let nextId = 1;
  let keys = new Set();

  nativeBridge.onmessage = event => {
    let reply;
    try { reply = JSON.parse(event.data); } catch (_) { return; }
    const call = pending.get(reply.id);
    if (!call) return;
    pending.delete(reply.id);
    if (reply.ok) call.resolve(reply.data);
    else call.reject(new Error("Native save operation failed: " + (reply.error || "unknown")));
  };

  const request = (op, key, data) => new Promise((resolve, reject) => {
    const id = "s" + nextId++;
    pending.set(id, { resolve, reject });
    try {
      nativeBridge.postMessage(JSON.stringify({ v: 1, id, op, key, data }));
    } catch (error) {
      pending.delete(id);
      reject(error);
    }
  });

  const install = () => {
    const storage = globalThis.StorageManager;
    if (!storage || typeof storage.saveZip !== "function" || storage.__makerplayNative) return false;
    storage.__makerplayNative = true;
    storage.saveZip = (name, zip) => request("write", name, btoa(zip)).then(() => {
      keys.add(name);
    });
    storage.loadZip = name => request("read", name).then(data => data == null ? null : atob(data));
    storage.remove = name => request("delete", name).then(() => {
      keys.delete(name);
    });
    storage.exists = name => keys.has(name);
    storage.updateForageKeys = function() {
      this._forageKeysUpdated = false;
      return request("list").then(names => {
        keys = new Set(names || []);
        this._forageKeys = [];
        this._forageKeysUpdated = true;
        return 0;
      }).catch(error => {
        this._forageKeysUpdated = true;
        throw error;
      });
    };

    const data = globalThis.DataManager;
    if (data && typeof data.saveGame === "function" && typeof data.saveGlobalInfo === "function") {
      let globalWrite = Promise.resolve();
      const saveGame = data.saveGame;
      data.saveGlobalInfo = function() {
        globalWrite = storage.saveObject("global", this._globalInfo);
        return globalWrite;
      };
      data.saveGame = function(savefileId) {
        return saveGame.call(this, savefileId).then(result => globalWrite.then(() => result));
      };
    }
    globalThis.__makerplayMzSaveBridge = true;
    return true;
  };

  if (!install()) {
    const timer = setInterval(() => {
      if (install()) clearInterval(timer);
    }, 0);
  }
})();