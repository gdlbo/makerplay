  // --- nw.js / nw.gui ---------------------------------------------------------------------
  // NW.js surface MV/MZ games detect and call: window chrome, app paths and clipboard.
  // nw.Window.get() shim; unsupported window chrome is registered as no-ops below.
  var nwWindow = new EventEmitter();
  Object.assign(nwWindow, {
    menu: null,
    window: root,
    title: document.title,
    x: 0,
    y: 0,
    width: root.innerWidth,
    height: root.innerHeight,
    zoomLevel: 0,
    focus: function() { root.focus(); },
    close: function() { this.emit("close"); },
    showDevTools: function() { return nwWindow; },
    isDevToolsOpen: function() { return false; },
    reload: function() { root.location.reload(); },
    enterFullscreen: function() { if (document.documentElement.requestFullscreen) document.documentElement.requestFullscreen(); },
    leaveFullscreen: function() { if (document.exitFullscreen) document.exitFullscreen(); },
    toggleFullscreen: function() { return document.fullscreenElement ? this.leaveFullscreen() : this.enterFullscreen(); },
    isFullscreen: false,
  });
  // Window chrome games poke at; harmless no-ops keep them from throwing.
  ["show", "hide", "maximize", "unmaximize", "minimize", "restore", "setAlwaysOnTop", "setResizable",
   "setMinimumSize", "resizeTo", "moveTo", "setPosition", "setMaximumSize", "requestAttention",
   "setProgressBar", "closeDevTools"]
    .forEach(function(name) { nwWindow[name] = function() {}; });
  function NwMenu() { this.items = []; }
  NwMenu.prototype.append = function(item) { this.items.push(item); };
  NwMenu.prototype.createMacBuiltin = function() {};
  function NwMenuItem(options) { Object.assign(this, options || {}); }
  // nw.App shim: argv, dataPath and the paths games compute at boot.
  var nwApp = new EventEmitter();
  Object.assign(nwApp, {
    argv: [],
    fullArgv: [],
    filteredArgv: [],
    dataPath: "/data",
    startPath: "/game",
    manifest: {},
    quit: function() { this.emit("quit"); },
    closeAllWindows: function() { nwWindow.close(); },
    clearCache: function() {}
  });
  var clipboardText = "";
  // Clipboard backed by navigator.clipboard when the WebView allows it.
  var nwClipboard = {
    get: function() { return clipboardText; },
    set: function(value, type) {
      if (type && type !== "text") {
        var error = new Error("Clipboard type '" + type + "' is not supported by MakerPlay");
        error.code = "ENOSYS";
        throw error;
      }
      clipboardText = String(value);
      if (navigator.clipboard && typeof navigator.clipboard.writeText === "function") {
        navigator.clipboard.writeText(clipboardText).catch(function(error) {
          console.warn("Unable to write system clipboard", error);
        });
      }
      return true;
    },
    clear: function() {
      clipboardText = "";
      if (navigator.clipboard && typeof navigator.clipboard.writeText === "function") {
        navigator.clipboard.writeText("").catch(function() {});
      }
    }
  };
  var nwGui = {
    Window: { get: function() { return nwWindow; }, open: function(url, options, callback) { if (callback) callback(nwWindow); return nwWindow; } },
    Menu: NwMenu,
    MenuItem: NwMenuItem,
    App: nwApp,
    Clipboard: { get: function() { return nwClipboard; } },
    Shell: { openExternal: function() {}, openItem: function() {}, showItemInFolder: function() {} }
  };
  defineBuiltin("nw.gui", nwGui);
  defineBuiltin("nw", nwGui);
