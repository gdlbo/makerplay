  function Dirent(name, directory) { this.name = name; this._directory = directory; }
  Dirent.prototype.isFile = function() { return !this._directory; };
  Dirent.prototype.isDirectory = function() { return this._directory; };
  Dirent.prototype.isSymbolicLink = function() { return false; };
  var fileDescriptors = Object.create(null);
  var nextFileDescriptor = 100;
  var MAX_FILE_DESCRIPTORS = 256;
  function openDescriptor(resolved, flags, knownExists, initialized, initialPosition) {
    flags = flags || "r";
    if (!/^(?:r|r\+|rs|rs\+|w|wx|w\+|wx\+|a|ax|a\+|ax\+)$/.test(flags)) throw nodeError("open", resolved, "invalid");
    var exists = knownExists === undefined ? fsModule.existsSync(resolved) : knownExists;
    if (!exists && flags.charAt(0) === "r") throw nodeError("open", resolved, "missing");
    if (exists && flags.indexOf("x") !== -1) { var exclusive = nodeError("open", resolved, "exists"); exclusive.code = "EEXIST"; throw exclusive; }
    if (!initialized && (!exists || flags.charAt(0) === "w")) transact("write", resolved, "");
    if (Object.keys(fileDescriptors).length >= MAX_FILE_DESCRIPTORS) { var limit = nodeError("open", resolved, "busy"); limit.code = "EMFILE"; throw limit; }
    var fd = nextFileDescriptor++;
    fileDescriptors[fd] = { path: resolved, flags: flags, position: initialPosition === undefined ? flags.charAt(0) === "a" && exists ? fsModule.statSync(resolved).size : 0 : initialPosition };
    return fd;
  }
  var fsModule = {
    existsSync: function(path) { try { return !!transact("exists", pathModule.resolve(path)); } catch (_) { return false; } },
    readFileSync: function(path, options) {
      var value = Buffer.from(transact("read", pathModule.resolve(path)), "base64");
      var encoding = encodingOf(options);
      return encoding ? value.toString(encoding) : value;
    },
    writeFileSync: function(path, data, options) {
      var op = options && typeof options === "object" && String(options.flag || "w").charAt(0) === "a" ? "append" : "write";
      transact(op, pathModule.resolve(path), Buffer.from(data, encodingOf(options)).toString("base64"));
    },
    appendFileSync: function(path, data, options) {
      transact("append", pathModule.resolve(path), Buffer.from(data, encodingOf(options)).toString("base64"));
    },
    unlinkSync: function(path) { transact("unlink", pathModule.resolve(path)); },
    mkdirSync: function(path) { transact("mkdir", pathModule.resolve(path)); },
    rmdirSync: function(path) { transact("rmdir", pathModule.resolve(path)); },
    renameSync: function(oldPath, newPath) { transact("rename", pathModule.resolve(oldPath), undefined, pathModule.resolve(newPath)); },
    readdirSync: function(path, options) {
      var resolved = pathModule.resolve(path);
      if (options && options.withFileTypes) {
        return transact("readdirStat", resolved).map(function(entry) { return new Dirent(entry.name, entry.directory); });
      }
      return transact("readdir", resolved);
    },
    statSync: function(path) { return new Stats(transact("stat", pathModule.resolve(path))); },
    lstatSync: function(path) { return fsModule.statSync(path); },
    accessSync: function(path) { if (!fsModule.existsSync(path)) throw new Error("ENOENT"); },
    realpathSync: function(path) {
      var resolved = pathModule.resolve(path);
      fsModule.accessSync(resolved);
      return resolved;
    },
    openSync: function(path, flags) {
      var resolved = pathModule.resolve(path);
      return openDescriptor(resolved, flags);
    },
    closeSync: function(fd) {
      if (!fileDescriptors[fd]) throw new Error("EBADF");
      delete fileDescriptors[fd];
    },
    fsyncSync: function(fd) { if (!fileDescriptors[fd]) throw new Error("EBADF"); },
    writeSync: function(fd, data, offset, length, position) {
      var descriptor = fileDescriptors[fd];
      if (!descriptor) throw new Error("EBADF");
      if (descriptor.flags.charAt(0) === "r" && descriptor.flags.indexOf("+") === -1) throw new Error("EBADF");
      var chunk;
      if (typeof data === "string") {
        chunk = Buffer.from(data, typeof length === "string" ? length : "utf8");
        position = typeof offset === "number" ? offset : null;
      } else {
        var source = Buffer.from(data);
        offset = Number(offset) || 0;
        length = length === undefined ? source.length - offset : Number(length);
        chunk = Buffer.from(source.subarray(offset, offset + length));
      }
      var append = descriptor.flags.charAt(0) === "a";
      var targetPosition = append ? fsModule.statSync(descriptor.path).size : position == null ? descriptor.position : Number(position);
      var current = fsModule.existsSync(descriptor.path) ? fsModule.readFileSync(descriptor.path) : Buffer.alloc(0);
      var output = Buffer.alloc(Math.max(current.length, targetPosition + chunk.length));
      output.set(current); output.set(chunk, targetPosition);
      fsModule.writeFileSync(descriptor.path, output);
      if (!append && position == null) descriptor.position = targetPosition + chunk.length;
      return chunk.length;
    },
    readSync: function(fd, buffer, offset, length, position) {
      var descriptor = fileDescriptors[fd];
      if (!descriptor) throw new Error("EBADF");
      if (descriptor.flags.charAt(0) === "w" && descriptor.flags.indexOf("+") === -1) throw new Error("EBADF");
      var source = fsModule.readFileSync(descriptor.path);
      offset = Number(offset) || 0;
      length = Math.min(Number(length) || 0, buffer.length - offset);
      var sourcePosition = position == null ? descriptor.position : Number(position);
      var chunk = source.subarray(sourcePosition, sourcePosition + length);
      if (!buffer || typeof buffer.set !== "function") throw new TypeError("buffer must be a Buffer or Uint8Array");
      buffer.set(chunk, offset);
      if (position == null) descriptor.position += chunk.length;
      return chunk.length;
    },
    copyFileSync: function(source, target) {
      transact("copy", pathModule.resolve(source), undefined, pathModule.resolve(target));
    },
    truncateSync: function(path, size) { transact("truncate", pathModule.resolve(path), undefined, undefined, { size: size || 0 }); },
    rmSync: function(path, options) { options = options || {}; transact("rm", pathModule.resolve(path), undefined, undefined, { recursive: options.recursive === true, force: options.force === true }); },
    constants: { F_OK: 0, R_OK: 4, W_OK: 2, X_OK: 1, COPYFILE_EXCL: 1 }
  };
  function settle(promise, callback, transform) {
    promise.then(function(value) { callback(null, transform ? transform(value) : value); }, function(error) { callback(error); });
  }
  function decodedFile(value, options) {
    var bytes = Buffer.from(value, "base64"), encoding = encodingOf(options);
    return encoding ? bytes.toString(encoding) : bytes;
  }
  function decodedEntries(value, options) {
    return options && options.withFileTypes ? value.map(function(entry) { return new Dirent(entry.name, entry.directory); }) : value;
  }
  fsModule.exists = function(path, callback) {
    asyncTransact("exists", pathModule.resolve(path)).then(function(value) { callback(!!value); }, function() { callback(false); });
  };
  fsModule.open = function(path, flags, mode, callback) {
    if (typeof mode === "function") callback = mode;
    var resolved = pathModule.resolve(path);
    flags = flags || "r";
    if (!/^(?:r|r\+|rs|rs\+|w|wx|w\+|wx\+|a|ax|a\+|ax\+)$/.test(flags)) { callback(nodeError("open", resolved, "invalid")); return; }
    asyncTransact("exists", resolved).then(function(exists) {
      if (exists && flags.indexOf("x") !== -1) { callback(nodeError("open", resolved, "exists")); return; }
      if (!exists && flags.charAt(0) === "r") { callback(nodeError("open", resolved, "missing")); return; }
      var initialize = !exists || flags.charAt(0) === "w";
      var ready = initialize ? asyncTransact("write", resolved, "") : Promise.resolve();
      ready.then(function() {
        var position = flags.charAt(0) === "a" && exists ? asyncTransact("stat", resolved).then(function(value) { return value.size || 0; }) : Promise.resolve(0);
        position.then(function(value) {
          try { callback(null, openDescriptor(resolved, flags, !!exists, initialize, value)); }
          catch (error) { callback(error); }
        }, callback);
      }, callback);
    }, callback);
  };
  fsModule.close = function(fd, callback) {
    asyncCall(function() { return fsModule.closeSync(fd); }, callback);
  };
  fsModule.fsync = function(fd, callback) {
    asyncCall(function() { return fsModule.fsyncSync(fd); }, callback);
  };
  fsModule.write = function(fd, data, offset, length, position, callback) {
    callback = typeof callback === "function" ? callback : typeof position === "function" ? position : typeof length === "function" ? length : typeof offset === "function" ? offset : null;
    if (!callback) throw new TypeError("callback must be a function");
    var descriptor = fileDescriptors[fd];
    if (!descriptor) { callback(new Error("EBADF")); return; }
    if (descriptor.flags.charAt(0) === "r" && descriptor.flags.indexOf("+") === -1) { callback(new Error("EBADF")); return; }
    var chunk, targetPosition, result;
    if (typeof data === "string") {
      if (typeof offset === "function") { callback = offset; offset = null; length = "utf8"; }
      else if (typeof length === "function") { callback = length; length = "utf8"; }
      else if (typeof position === "function") callback = position;
      chunk = Buffer.from(data, typeof length === "string" ? length : "utf8");
      targetPosition = typeof offset === "number" ? offset : descriptor.position;
      result = data;
    } else {
      if (typeof offset === "function") { callback = offset; offset = 0; length = data.length; position = null; }
      else if (typeof length === "function") { callback = length; length = data.length - (Number(offset) || 0); position = null; }
      else if (typeof position === "function") { callback = position; position = null; }
      var source = Buffer.from(data);
      offset = Number(offset) || 0;
      length = length === undefined ? source.length - offset : Number(length);
      chunk = Buffer.from(source.subarray(offset, offset + length));
      targetPosition = position == null ? descriptor.position : Number(position);
      result = data;
    }
    var append = descriptor.flags.charAt(0) === "a";
    asyncTransact("writeRange", descriptor.path, chunk.toString("base64"), undefined, { position: targetPosition, append: append }).then(function(bytesWritten) {
      if (!append && (typeof data === "string" || position == null)) descriptor.position = targetPosition + bytesWritten;
      callback(null, bytesWritten, result);
    }, callback);
  };
  fsModule.read = function(fd, buffer, offset, length, position, callback) {
    callback = typeof callback === "function" ? callback : typeof position === "function" ? position : typeof length === "function" ? length : typeof offset === "function" ? offset : null;
    if (!callback) throw new TypeError("callback must be a function");
    var descriptor = fileDescriptors[fd];
    if (!descriptor) { callback(new Error("EBADF")); return; }
    if (descriptor.flags.charAt(0) === "w" && descriptor.flags.indexOf("+") === -1) { callback(new Error("EBADF")); return; }
    if (typeof offset === "function") { offset = 0; length = buffer.length; position = null; }
    else if (typeof length === "function") { length = buffer.length - (Number(offset) || 0); position = null; }
    else if (typeof position === "function") position = null;
    offset = Number(offset) || 0;
    length = Math.min(Number(length) || 0, buffer.length - offset);
    var sourcePosition = position == null ? descriptor.position : Number(position);
    asyncTransact("readRange", descriptor.path, undefined, undefined, { position: sourcePosition, size: length }).then(function(value) {
      var chunk = Buffer.from(value, "base64");
      if (!buffer || typeof buffer.set !== "function") throw new TypeError("buffer must be a Buffer or Uint8Array");
      buffer.set(chunk, offset);
      if (position == null) descriptor.position += chunk.length;
      callback(null, chunk.length, buffer);
    }, callback);
  };
  fsModule.readFile = function(path, options, callback) {
    if (typeof options === "function") { callback = options; options = undefined; }
    settle(asyncTransact("read", pathModule.resolve(path)), callback, function(value) { return decodedFile(value, options); });
  };
  fsModule.readdir = function(path, options, callback) {
    if (typeof options === "function") { callback = options; options = undefined; }
    var op = options && options.withFileTypes ? "readdirStat" : "readdir";
    settle(asyncTransact(op, pathModule.resolve(path)), callback, function(value) { return decodedEntries(value, options); });
  };
  ["stat", "lstat"].forEach(function(name) {
    fsModule[name] = function(path, options, callback) {
      if (typeof options === "function") callback = options;
      settle(asyncTransact("stat", pathModule.resolve(path)), callback, function(value) { return new Stats(value); });
    };
  });
  fsModule.realpath = function(path, options, callback) {
    if (typeof options === "function") callback = options;
    var resolved = pathModule.resolve(path);
    settle(asyncTransact("stat", resolved), callback, function() { return resolved; });
  };
  ["writeFile", "appendFile"].forEach(function(name) {
    fsModule[name] = function(path, data, options, callback) {
      if (typeof options === "function") { callback = options; options = undefined; }
      var op = name === "appendFile" || options && typeof options === "object" && String(options.flag || "w").charAt(0) === "a" ? "append" : "write";
      settle(asyncTransact(op, pathModule.resolve(path), Buffer.from(data, encodingOf(options)).toString("base64")), callback);
    };
  });
  ["unlink", "mkdir", "rmdir"].forEach(function(name) {
    fsModule[name] = function(path, options, callback) {
      if (typeof options === "function") { callback = options; options = undefined; }
      settle(asyncTransact(name, pathModule.resolve(path)), callback);
    };
  });
  fsModule.rename = function(source, target, callback) {
    settle(asyncTransact("rename", pathModule.resolve(source), undefined, pathModule.resolve(target)), callback);
  };
  fsModule.copyFile = function(source, target, flags, callback) {
    if (typeof flags === "function") callback = flags;
    settle(asyncTransact("copy", pathModule.resolve(source), undefined, pathModule.resolve(target)), callback);
  };
  fsModule.truncate = function(path, size, callback) {
    if (typeof size === "function") { callback = size; size = 0; }
    settle(asyncTransact("truncate", pathModule.resolve(path), undefined, undefined, { size: size || 0 }), callback);
  };
  fsModule.rm = function(path, options, callback) {
    if (typeof options === "function") { callback = options; options = {}; }
    options = options || {};
    settle(asyncTransact("rm", pathModule.resolve(path), undefined, undefined, { recursive: options.recursive === true, force: options.force === true }), callback);
  };
  fsModule.access = function(path, mode, callback) {
    if (typeof mode === "function") callback = mode;
    settle(asyncTransact("stat", pathModule.resolve(path)), callback, function() {});
  };
  fsModule.promises = {
    readFile: function(path, options) { return asyncTransact("read", pathModule.resolve(path)).then(function(value) { return decodedFile(value, options); }); },
    writeFile: function(path, data, options) { var op = options && typeof options === "object" && String(options.flag || "w").charAt(0) === "a" ? "append" : "write"; return asyncTransact(op, pathModule.resolve(path), Buffer.from(data, encodingOf(options)).toString("base64")); },
    appendFile: function(path, data, options) { return asyncTransact("append", pathModule.resolve(path), Buffer.from(data, encodingOf(options)).toString("base64")); },
    readdir: function(path, options) { var op = options && options.withFileTypes ? "readdirStat" : "readdir"; return asyncTransact(op, pathModule.resolve(path)).then(function(value) { return decodedEntries(value, options); }); },
    stat: function(path) { return asyncTransact("stat", pathModule.resolve(path)).then(function(value) { return new Stats(value); }); },
    lstat: function(path) { return asyncTransact("stat", pathModule.resolve(path)).then(function(value) { return new Stats(value); }); },
    realpath: function(path) { var resolved = pathModule.resolve(path); return asyncTransact("stat", resolved).then(function() { return resolved; }); },
    access: function(path) { return asyncTransact("stat", pathModule.resolve(path)).then(function() {}); },
    mkdir: function(path) { return asyncTransact("mkdir", pathModule.resolve(path)); },
    unlink: function(path) { return asyncTransact("unlink", pathModule.resolve(path)); },
    rmdir: function(path) { return asyncTransact("rmdir", pathModule.resolve(path)); },
    rm: function(path, options) { options = options || {}; return asyncTransact("rm", pathModule.resolve(path), undefined, undefined, { recursive: options.recursive === true, force: options.force === true }); },
    truncate: function(path, size) { return asyncTransact("truncate", pathModule.resolve(path), undefined, undefined, { size: size || 0 }); },
    rename: function(source, target) { return asyncTransact("rename", pathModule.resolve(source), undefined, pathModule.resolve(target)); },
    copyFile: function(source, target) { return asyncTransact("copy", pathModule.resolve(source), undefined, pathModule.resolve(target)); },
    constants: fsModule.constants
  };
  function fileHandle(fd) {
    return {
      fd: fd,
      close: function() { return new Promise(function(resolve, reject) { fsModule.close(fd, function(error) { if (error) reject(error); else resolve(); }); }); },
      read: function(buffer, offset, length, position) { return new Promise(function(resolve, reject) { fsModule.read(fd, buffer, offset || 0, length === undefined ? buffer.length : length, position == null ? null : position, function(error, bytesRead, value) { if (error) reject(error); else resolve({ bytesRead: bytesRead, buffer: value }); }); }); },
      write: function(data, offset, length, position) { return new Promise(function(resolve, reject) { fsModule.write(fd, data, offset, length, position, function(error, bytesWritten) { if (error) reject(error); else resolve({ bytesWritten: bytesWritten, buffer: data }); }); }); },
      stat: function() { var descriptor = fileDescriptors[fd]; return descriptor ? fsModule.promises.stat(descriptor.path) : Promise.reject(new Error("EBADF")); },
      sync: function() { return new Promise(function(resolve, reject) { fsModule.fsync(fd, function(error) { if (error) reject(error); else resolve(); }); }); }
    };
  }
  fsModule.promises.open = function(path, flags, mode) { return new Promise(function(resolve, reject) { fsModule.open(path, flags, mode, function(error, fd) { if (error) reject(error); else resolve(fileHandle(fd)); }); }); };