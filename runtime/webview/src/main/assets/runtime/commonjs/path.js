  // --- path (posix) -----------------------------------------------------------------------
  // Ported from Node's posix implementation so plugin code behaves identically. Backslashes are
  // normalised first because MV plugins routinely build Windows-style paths.
  // Collapse "." / ".." and duplicate separators, keeping a trailing separator.
  function normalize(path) {
    path = String(path || ".").replace(/\\/g, "/");
    var absolute = path.charAt(0) === "/";
    var trailingSeparator = path.length > 0 && path.charAt(path.length - 1) === "/";
    var parts = [];
    path.split("/").forEach(function(part) {
      if (!part || part === ".") return;
      if (part === "..") {
        if (parts.length && parts[parts.length - 1] !== "..") parts.pop();
        else if (!absolute) parts.push(part);
      } else parts.push(part);
    });
    var result = (absolute ? "/" : "") + parts.join("/");
    result = result || (absolute ? "/" : ".");
    return trailingSeparator && result !== "/" ? result + "/" : result;
  }

  // Strip one trailing separator; resolve() must never return one.
  function trimTrailingSeparator(path) {
    return path.length > 1 && path.charAt(path.length - 1) === "/" ? path.slice(0, -1) : path;
  }

  // Node's posix path semantics, with backslashes accepted as separators because NW.js games
  // routinely build Windows-style paths before passing them to the runtime.
  var SLASH = 47;
  var DOT_CODE = 46;
  function asPosixPath(path) { return String(path).replace(/\\/g, "/"); }

  // Node dirname: string surgery without normalisation ("a/../b" -> "a/..").
  function dirnameOf(path) {
    path = asPosixPath(path);
    if (path.length === 0) return ".";
    var hasRoot = path.charCodeAt(0) === SLASH;
    var end = -1, matchedSlash = true;
    for (var i = path.length - 1; i >= 1; --i) {
      if (path.charCodeAt(i) === SLASH) {
        if (!matchedSlash) { end = i; break; }
      } else {
        matchedSlash = false;
      }
    }
    if (end === -1) return hasRoot ? "/" : ".";
    if (hasRoot && end === 1) return "//";
    return path.slice(0, end);
  }

  // Node basename, with an optional suffix stripped from the result.
  function basenameOf(path, suffix) {
    path = asPosixPath(path);
    var start = 0, end = -1, matchedSlash = true;
    if (suffix !== undefined && suffix.length > 0 && suffix.length <= path.length) {
      if (suffix === path) return "";
      var extIdx = suffix.length - 1, firstNonSlashEnd = -1;
      for (var i = path.length - 1; i >= 0; --i) {
        var code = path.charCodeAt(i);
        if (code === SLASH) {
          if (!matchedSlash) { start = i + 1; break; }
        } else {
          if (firstNonSlashEnd === -1) { matchedSlash = false; firstNonSlashEnd = i + 1; }
          if (extIdx >= 0) {
            if (code === suffix.charCodeAt(extIdx)) {
              if (--extIdx === -1) end = i;
            } else {
              extIdx = -1;
              end = firstNonSlashEnd;
            }
          }
        }
      }
      if (start === end) end = firstNonSlashEnd;
      else if (end === -1) end = path.length;
      return path.slice(start, end);
    }
    for (var j = path.length - 1; j >= 0; --j) {
      if (path.charCodeAt(j) === SLASH) {
        if (!matchedSlash) { start = j + 1; break; }
      } else if (end === -1) {
        matchedSlash = false;
        end = j + 1;
      }
    }
    return end === -1 ? "" : path.slice(start, end);
  }

  // Node extname, including the ".." and ".hidden" special cases.
  function extnameOf(path) {
    path = asPosixPath(path);
    var startDot = -1, startPart = 0, end = -1, matchedSlash = true, preDotState = 0;
    for (var i = path.length - 1; i >= 0; --i) {
      var code = path.charCodeAt(i);
      if (code === SLASH) {
        if (!matchedSlash) {
          startPart = i + 1;
          break;
        }
        continue;
      }
      if (end === -1) {
        matchedSlash = false;
        end = i + 1;
      }
      if (code === DOT_CODE) {
        if (startDot === -1) startDot = i;
        else if (preDotState !== 1) preDotState = 1;
      } else if (startDot !== -1) {
        preDotState = -1;
      }
    }
    if (
      startDot === -1 ||
      end === -1 ||
      preDotState === 0 ||
      (preDotState === 1 && startDot === end - 1 && startDot === startPart + 1)
    ) return "";
    return path.slice(startDot, end);
  }

  // Node parse: root/dir/base/ext/name for unnormalised input.
  function parseOf(path) {
    path = asPosixPath(path);
    var result = { root: "", dir: "", base: "", ext: "", name: "" };
    if (path.length === 0) return result;
    var isAbsolute = path.charCodeAt(0) === SLASH;
    var start = isAbsolute ? 1 : 0;
    if (isAbsolute) result.root = "/";
    var startDot = -1, startPart = 0, end = -1, matchedSlash = true, preDotState = 0;
    var i = path.length - 1;
    for (; i >= start; --i) {
      var code = path.charCodeAt(i);
      if (code === SLASH) {
        if (!matchedSlash) {
          startPart = i + 1;
          break;
        }
        continue;
      }
      if (end === -1) {
        matchedSlash = false;
        end = i + 1;
      }
      if (code === DOT_CODE) {
        if (startDot === -1) startDot = i;
        else if (preDotState !== 1) preDotState = 1;
      } else if (startDot !== -1) {
        preDotState = -1;
      }
    }
    if (end !== -1) {
      var partStart = startPart === 0 && isAbsolute ? 1 : startPart;
      if (
        startDot === -1 ||
        preDotState === 0 ||
        (preDotState === 1 && startDot === end - 1 && startDot === startPart + 1)
      ) {
        result.base = result.name = path.slice(partStart, end);
      } else {
        result.name = path.slice(partStart, startDot);
        result.base = path.slice(partStart, end);
        result.ext = path.slice(startDot, end);
      }
    }
    if (startPart > 0) result.dir = path.slice(0, startPart - 1);
    else if (isAbsolute) result.dir = "/";
    return result;
  }

  // Public module. resolve() anchors to the mounted game root; the rest matches posix.
  var pathModule = {
    resolve: function() {
      var resolved = "";
      for (var i = arguments.length - 1; i >= -1; i--) {
        var part = i >= 0 ? arguments[i] : "/game";
        if (!part) continue;
        resolved = String(part) + "/" + resolved;
        if (String(part).charAt(0) === "/") break;
      }
      return trimTrailingSeparator(normalize(resolved));
    },
    normalize: normalize,
    join: function() {
      var parts = Array.prototype.slice.call(arguments).filter(function(part) { return part !== ""; });
      return normalize(parts.length ? parts.join("/") : ".");
    },
    dirname: dirnameOf,
    basename: basenameOf,
    extname: extnameOf,
    relative: function(from, to) {
      var left = pathModule.resolve(from).split("/").filter(Boolean);
      var right = pathModule.resolve(to).split("/").filter(Boolean);
      while (left.length && right.length && left[0] === right[0]) { left.shift(); right.shift(); }
      return left.map(function() { return ".."; }).concat(right).join("/");
    },
    isAbsolute: function(path) { return String(path).charAt(0) === "/"; },
    parse: parseOf,
    format: function(value) {
      value = value || {};
      var dir = value.dir || value.root;
      var ext = value.ext || "";
      if (ext && ext.charAt(0) !== ".") ext = "." + ext;
      var base = value.base || ((value.name || "") + ext);
      if (!dir) return base;
      if (dir === value.root) return dir + base;
      return dir + "/" + base;
    },
    sep: "/",
    delimiter: ":"
  };
  pathModule.posix = pathModule;

  defineBuiltin("path", pathModule);
