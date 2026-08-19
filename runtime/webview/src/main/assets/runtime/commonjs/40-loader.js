  var cache = Object.create(null);
  function readText(path) { return fsModule.readFileSync(path, "utf8"); }
  function fileCandidate(path) {
    var candidates = [path, path + ".js", path + ".json"];
    for (var i = 0; i < candidates.length; i++) if (fsModule.existsSync(candidates[i])) return candidates[i];
    var packagePath = pathModule.join(path, "package.json");
    if (fsModule.existsSync(packagePath)) {
      var manifest = JSON.parse(readText(packagePath));
      if (manifest.main) {
        var main = fileCandidate(pathModule.join(path, manifest.main));
        if (main) return main;
      }
    }
    var indexes = [pathModule.join(path, "index.js"), pathModule.join(path, "index.json")];
    for (var j = 0; j < indexes.length; j++) if (fsModule.existsSync(indexes[j])) return indexes[j];
    return null;
  }

  function resolveRequest(id, parentFilename) {
    id = String(id);
    if (id.indexOf("node:") === 0) id = id.slice(5);
    if (Object.prototype.hasOwnProperty.call(builtins, id)) return "node:" + id;
    var parentDirectory = parentFilename ? pathModule.dirname(parentFilename) : "/game";
    var resolved;
    if (id.charAt(0) === "/") resolved = fileCandidate(normalize(id));
    else if (id.indexOf("./") === 0 || id.indexOf("../") === 0) resolved = fileCandidate(pathModule.resolve(parentDirectory, id));
    else {
      var current = parentDirectory;
      while (current.indexOf("/game") === 0) {
        resolved = fileCandidate(pathModule.join(current, "node_modules", id));
        if (resolved) break;
        if (current === "/game") break;
        current = pathModule.dirname(current);
      }
    }
    if (!resolved || (resolved !== "/game" && resolved.indexOf("/game/") !== 0)) {
      var error = new Error("Cannot find module '" + id + "'"); error.code = "MODULE_NOT_FOUND"; throw error;
    }
    return resolved;
  }

  function load(id, parent) {
    var filename = resolveRequest(id, parent && parent.filename);
    if (filename.indexOf("node:") === 0) return builtins[filename.slice(5)];
    if (cache[filename]) return cache[filename].exports;
    var module = { id: filename, filename: filename, exports: {}, loaded: false, parent: parent || null, children: [] };
    cache[filename] = module;
    if (parent) parent.children.push(module);
    try {
      if (pathModule.extname(filename) === ".json") module.exports = JSON.parse(readText(filename));
      else {
        var localRequire = makeRequire(module);
        module.require = localRequire;
        module.paths = localRequire.resolve.paths(filename);
        var wrapper = new Function("exports", "require", "module", "__filename", "__dirname", readText(filename) + "\n//# sourceURL=" + filename);
        wrapper.call(module.exports, module.exports, localRequire, module, filename, pathModule.dirname(filename));
      }
      module.loaded = true;
      return module.exports;
    } catch (error) {
      delete cache[filename];
      throw error;
    }
  }

  function makeRequire(parent) {
    function require(id) { return load(id, parent); }
    require.resolve = function(id) { return resolveRequest(id, parent && parent.filename); };
    require.resolve.paths = function(id) {
      if (Object.prototype.hasOwnProperty.call(builtins, String(id).replace(/^node:/, ""))) return null;
      var paths = [], current = parent && parent.filename ? pathModule.dirname(parent.filename) : "/game";
      while (current.indexOf("/game") === 0) {
        paths.push(pathModule.join(current, "node_modules"));
        if (current === "/game") break;
        current = pathModule.dirname(current);
      }
      return paths;
    };
    require.cache = cache;
    require.main = mainModule;
    require.__makerplayCommonJs = true;
    return require;
  }
  var mainModule = { id: ".", filename: "/game/index.html", exports: {}, loaded: true, parent: null, children: [], paths: ["/game/node_modules"] };
  processModule.mainModule = mainModule;
  var globalRequire = makeRequire(mainModule);
  mainModule.require = globalRequire;
  // Browser scripts are not the CommonJS entry module. This keeps CLI-only
  // plugin branches such as `require.main === module` from running in WebView.
  globalRequire.main = undefined;
  builtin("module", { createRequire: function(filename) { return makeRequire({ id: filename, filename: filename, exports: {}, loaded: true, parent: null, children: [] }); }, builtinModules: Object.keys(builtins) });

  root.require = globalRequire;
  root.process = root.process || processModule;
  root.Buffer = root.Buffer || Buffer;
  root.nw = root.nw || nwGui;
  root.global = root;
  root.__dirname = root.__dirname || "/game";
  root.__filename = root.__filename || "/game/index.html";
  root.setImmediate = root.setImmediate || function(callback) { var args = Array.prototype.slice.call(arguments, 1); return setTimeout(function() { callback.apply(null, args); }, 0); };
  root.clearImmediate = root.clearImmediate || clearTimeout;
  root.addEventListener("pagehide", function(event) {
    if (event.persisted) return;
    pendingAsync.forEach(function(call) { call.reject(nodeError(call.op, call.path, "closed")); });
    pendingAsync.clear();
    fileDescriptors = Object.create(null);
    Object.keys(cache).forEach(function(key) { delete cache[key]; });
    mainModule.children.length = 0;
    nwWindow.removeAllListeners();
    nwApp.removeAllListeners();
    clipboardText = "";
    if (asyncBridge) asyncBridge.onmessage = null;
  }, { once: true });
})(globalThis);