// --- fs ---------------------------------------------------------------------------------
// Filesystem API over the native bridge: sync, callback and promise forms, file descriptors,
// Dirents, Stats and stream helpers. Paths resolve against the mounted game root only.

// readdir({withFileTypes}) entry; the type predicates mirror Node.
function Dirent(name, directory, parentPath) {
  this.name = name;
  this._directory = directory;
  this.parentPath = parentPath === undefined ? null : parentPath;
  this.path = this.parentPath;
}

// Random directory suffix for mkdtemp, using crypto when available.
function randomSuffix() {
  var alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
  var bytes = new Uint8Array(8);
  if (root.crypto && typeof root.crypto.getRandomValues === "function")
    root.crypto.getRandomValues(bytes);
  else
    for (var index = 0; index < bytes.length; index++)
      bytes[index] = Math.floor(Math.random() * 256);
  var text = "";
  for (var position = 0; position < bytes.length; position++)
    text += alphabet[bytes[position] % alphabet.length];
  return text;
}
Dirent.prototype.isFile = function () {
  return !this._directory;
};
Dirent.prototype.isDirectory = function () {
  return this._directory;
};
Dirent.prototype.isSymbolicLink = function () {
  return false;
};
Dirent.prototype.isBlockDevice = function () {
  return false;
};
Dirent.prototype.isCharacterDevice = function () {
  return false;
};
Dirent.prototype.isFIFO = function () {
  return false;
};
Dirent.prototype.isSocket = function () {
  return false;
};
// Open descriptor table; write position is tracked per descriptor.
var fileDescriptors = Object.create(null);
var nextFileDescriptor = 100;
var MAX_FILE_DESCRIPTORS = 256;
// Validate flags, create or truncate when needed and register a descriptor.
function openDescriptor(resolved, flags, knownExists, initialized, initialPosition) {
  flags = flags || "r";
  if (!/^(?:r|r\+|rs|rs\+|w|wx|w\+|wx\+|a|ax|a\+|ax\+)$/.test(flags))
    throw nodeError("open", resolved, "invalid");
  var exists = knownExists === undefined ? fsModule.existsSync(resolved) : knownExists;
  if (!exists && flags.charAt(0) === "r") throw nodeError("open", resolved, "missing");
  if (exists && flags.indexOf("x") !== -1) {
    var exclusive = nodeError("open", resolved, "exists");
    exclusive.code = "EEXIST";
    throw exclusive;
  }
  if (!initialized && (!exists || flags.charAt(0) === "w")) transact("write", resolved, "");
  if (Object.keys(fileDescriptors).length >= MAX_FILE_DESCRIPTORS) {
    var limit = nodeError("open", resolved, "busy");
    limit.code = "EMFILE";
    throw limit;
  }
  var fd = nextFileDescriptor++;
  fileDescriptors[fd] = {
    path: resolved,
    flags: flags,
    position:
      initialPosition === undefined
        ? flags.charAt(0) === "a" && exists
          ? fsModule.statSync(resolved).size
          : 0
        : initialPosition,
  };
  return fd;
}
// fs.Stats: type flags, size and the modification times plugin caches key on.
// fs.Stats: type flags, size and the modification times plugin caches key on.
function Stats(value) {
  this.size = value.size || 0;
  this._file = !!value.file;
  this._directory = !!value.directory;
  var modified = Number(value.mtimeMs);
  if (!Number.isFinite(modified) || modified < 0) modified = 0;
  this.mtimeMs = modified;
  this.atimeMs = modified;
  this.ctimeMs = modified;
  this.birthtimeMs = modified;
  this.mtime = new Date(modified);
  this.atime = this.mtime;
  this.ctime = this.mtime;
  this.birthtime = this.mtime;
}
Stats.prototype.isFile = function () {
  return this._file;
};
Stats.prototype.isDirectory = function () {
  return this._directory;
};
Stats.prototype.isSymbolicLink = function () {
  return false;
};
Stats.prototype.isBlockDevice = function () {
  return false;
};
Stats.prototype.isCharacterDevice = function () {
  return false;
};
Stats.prototype.isFIFO = function () {
  return false;
};
Stats.prototype.isSocket = function () {
  return false;
};

// Keep descriptor reads within the caller's buffer and bridge payload bounds.
function readWindow(buffer, offset, length) {
  if (!buffer || typeof buffer.set !== "function" || typeof buffer.length !== "number") {
    throw new TypeError("buffer must be a Buffer or Uint8Array");
  }
  offset = Number(offset);
  length = Number(length);
  if (!Number.isSafeInteger(offset) || offset < 0 || offset > buffer.length) {
    throw new RangeError("offset is outside the buffer");
  }
  if (!Number.isSafeInteger(length) || length < 0) {
    throw new RangeError("length must be a non-negative integer");
  }
  return { offset: offset, length: Math.min(length, buffer.length - offset) };
}

function readPosition(position) {
  if (typeof position === "bigint") {
    if (position < 0n || position > BigInt(Number.MAX_SAFE_INTEGER)) {
      throw new RangeError("position must be a non-negative integer");
    }
    position = Number(position);
  } else {
    position = Number(position);
  }
  if (!Number.isSafeInteger(position) || position < 0) {
    throw new RangeError("position must be a non-negative integer");
  }
  return position;
}

function abortError() {
  var error = new Error("The operation was aborted");
  error.name = "AbortError";
  error.code = "ABORT_ERR";
  return error;
}

function isReadOptions(value) {
  return (
    !!value &&
    typeof value === "object" &&
    typeof value.set !== "function" &&
    !ArrayBuffer.isView(value)
  );
}

// Positional arguments and Node's options object share one shape.
function normalizeReadCall(buffer, offset, length, position, callback) {
  var encoding = null;
  var signal = null;
  if (typeof buffer === "function") {
    callback = buffer;
    buffer = undefined;
  } else if (isReadOptions(buffer)) {
    var options = buffer;
    callback = typeof offset === "function" ? offset : callback;
    encoding = options.encoding || null;
    signal = options.signal || null;
    buffer = options.buffer;
    offset = options.offset;
    length = options.length;
    position = options.position;
  } else if (isReadOptions(offset)) {
    var positioned = offset;
    callback = typeof length === "function" ? length : callback;
    encoding = positioned.encoding || null;
    signal = positioned.signal || null;
    offset = positioned.offset;
    length = positioned.length;
    position = positioned.position;
  }
  if (typeof offset === "function") {
    callback = offset;
    offset = undefined;
    length = undefined;
    position = undefined;
  } else if (typeof length === "function") {
    callback = length;
    length = undefined;
    position = undefined;
  } else if (typeof position === "function") {
    callback = position;
    position = undefined;
  }
  if (!buffer) {
    var size = length === undefined ? 16384 : Number(length);
    buffer = Buffer.alloc(size);
    offset = 0;
    length = size;
  }
  if (offset === undefined || offset === null) offset = 0;
  if (length === undefined || length === null) length = buffer.length - Number(offset);
  return {
    buffer: buffer,
    offset: offset,
    length: length,
    position: position,
    callback: callback,
    encoding: encoding,
    signal: signal,
  };
}

function readInto(fd, buffer, offset, length, position) {
  var descriptor = fileDescriptors[fd];
  if (!descriptor) throw Object.assign(new Error("EBADF"), { code: "EBADF" });
  if (descriptor.flags.charAt(0) === "w" && descriptor.flags.indexOf("+") === -1) {
    throw Object.assign(new Error("EBADF"), { code: "EBADF" });
  }
  var window = readWindow(buffer, offset, length);
  var explicit = position != null;
  var sourcePosition = explicit ? readPosition(position) : descriptor.position;
  var chunk = Buffer.from(
    transact("readRange", descriptor.path, undefined, undefined, {
      position: sourcePosition,
      size: window.length,
    }),
    "base64",
  );
  if (chunk.length > window.length) chunk = chunk.subarray(0, window.length);
  buffer.set(chunk, window.offset);
  if (!explicit) descriptor.position += chunk.length;
  return { bytesRead: chunk.length, offset: window.offset };
}

// Sync core of the module; the callback and promise APIs wrap the same bridge ops.
var fsModule = {
  existsSync: function (path) {
    try {
      return !!transact("exists", pathModule.resolve(path));
    } catch (_) {
      return false;
    }
  },
  readFileSync: function (path, options) {
    var value = Buffer.from(transact("read", pathModule.resolve(path)), "base64");
    var encoding = encodingOf(options);
    return encoding ? value.toString(encoding) : value;
  },
  writeFileSync: function (path, data, options) {
    var op =
      options && typeof options === "object" && String(options.flag || "w").charAt(0) === "a"
        ? "append"
        : "write";
    transact(
      op,
      pathModule.resolve(path),
      Buffer.from(data, encodingOf(options)).toString("base64"),
    );
  },
  appendFileSync: function (path, data, options) {
    transact(
      "append",
      pathModule.resolve(path),
      Buffer.from(data, encodingOf(options)).toString("base64"),
    );
  },
  unlinkSync: function (path) {
    transact("unlink", pathModule.resolve(path));
  },
  mkdirSync: function (path) {
    transact("mkdir", pathModule.resolve(path));
  },
  rmdirSync: function (path) {
    transact("rmdir", pathModule.resolve(path));
  },
  renameSync: function (oldPath, newPath) {
    transact("rename", pathModule.resolve(oldPath), undefined, pathModule.resolve(newPath));
  },
  readdirSync: function (path, options) {
    var resolved = pathModule.resolve(path);
    var op = options && options.withFileTypes ? "readdirStat" : "readdir";
    return decodedEntries(transact(op, resolved), options, resolved);
  },
  statSync: function (path) {
    return new Stats(transact("stat", pathModule.resolve(path)));
  },
  lstatSync: function (path) {
    return fsModule.statSync(path);
  },
  accessSync: function (path) {
    if (!fsModule.existsSync(path)) throw new Error("ENOENT");
  },
  realpathSync: function (path) {
    var resolved = pathModule.resolve(path);
    fsModule.accessSync(resolved);
    return resolved;
  },
  openSync: function (path, flags) {
    var resolved = pathModule.resolve(path);
    return openDescriptor(resolved, flags);
  },
  closeSync: function (fd) {
    if (!fileDescriptors[fd]) throw new Error("EBADF");
    delete fileDescriptors[fd];
  },
  fsyncSync: function (fd) {
    if (!fileDescriptors[fd]) throw new Error("EBADF");
  },
  writeSync: function (fd, data, offset, length, position) {
    var descriptor = fileDescriptors[fd];
    if (!descriptor) throw new Error("EBADF");
    if (descriptor.flags.charAt(0) === "r" && descriptor.flags.indexOf("+") === -1)
      throw new Error("EBADF");
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
    var targetPosition = append ? 0 : position == null ? descriptor.position : Number(position);
    var written = transact("writeRange", descriptor.path, chunk.toString("base64"), undefined, {
      position: targetPosition,
      append: append,
    });
    if (position == null) {
      if (append && descriptor.flags.indexOf("+") !== -1)
        descriptor.position = fsModule.statSync(descriptor.path).size;
      else if (!append) descriptor.position = targetPosition + written;
    }
    return written;
  },
  readSync: function (fd, buffer, offset, length, position) {
    var args = normalizeReadCall(buffer, offset, length, position);
    if (args.signal && args.signal.aborted) throw abortError();
    return readInto(fd, args.buffer, args.offset, args.length, args.position).bytesRead;
  },
  readvSync: function (fd, buffers, position) {
    if (!Array.isArray(buffers))
      throw new TypeError("buffers must be an array of Buffer or Uint8Array");
    var descriptor = fileDescriptors[fd];
    if (!descriptor) throw Object.assign(new Error("EBADF"), { code: "EBADF" });
    var explicit = position != null;
    var cursor = explicit ? readPosition(position) : descriptor.position;
    var total = 0;
    for (var index = 0; index < buffers.length; index++) {
      var view = buffers[index];
      var read = readInto(fd, view, 0, view.length, cursor);
      total += read.bytesRead;
      cursor += read.bytesRead;
      if (read.bytesRead < view.length) break;
    }
    if (!explicit) descriptor.position = cursor;
    return total;
  },
  copyFileSync: function (source, target) {
    transact("copy", pathModule.resolve(source), undefined, pathModule.resolve(target));
  },
  truncateSync: function (path, size) {
    transact("truncate", pathModule.resolve(path), undefined, undefined, { size: size || 0 });
  },
  mkdtempSync: function (prefix, options) {
    var base = pathModule.resolve(prefix);
    var encoding = encodingOf(options);
    for (var attempt = 0; attempt < 16; attempt++) {
      var candidate = base + randomSuffix();
      try {
        fsModule.mkdirSync(candidate);
        return encoding === "buffer" ? Buffer.from(candidate) : candidate;
      } catch (error) {
        if (error && (error.code === "EEXIST" || error.code === "EISDIR")) continue;
        throw error;
      }
    }
    throw nodeError("mkdtemp", base, "exists");
  },
  rmSync: function (path, options) {
    options = options || {};
    transact("rm", pathModule.resolve(path), undefined, undefined, {
      recursive: options.recursive === true,
      force: options.force === true,
    });
  },
  // Read the file once (bounded) and push chunks as the consumer pulls them.
  createReadStream: function (path, options) {
    if (typeof options === "string") options = { encoding: options };
    options = options || {};
    var resolved = pathModule.resolve(path);
    var encoding = options.encoding;
    var start = options.start === undefined ? 0 : Math.max(0, Number(options.start) || 0);
    var end = options.end === undefined ? null : Number(options.end);
    var highWaterMark = Number(options.highWaterMark) || 64 * 1024;
    var signal = options.signal;
    var position = start;
    var stopped = false;
    if (signal && signal.aborted) {
      var aborted = new Readable({ read: function () {} });
      setTimeout(function () {
        aborted.destroy(abortError());
      }, 0);
      return aborted;
    }
    var stream = new Readable({
      encoding: encoding,
      highWaterMark: highWaterMark,
      read: function () {
        if (stopped) return;
        if (signal && signal.aborted) {
          stopped = true;
          this.destroy(abortError());
          return;
        }
        var remaining = end == null ? highWaterMark : Math.min(highWaterMark, end - position + 1);
        if (remaining <= 0) {
          this.push(null);
          return;
        }
        try {
          var raw = transact("readRange", resolved, undefined, undefined, {
            position: position,
            size: remaining,
          });
          var chunk = Buffer.from(raw, "base64");
          if (chunk.length > remaining) chunk = chunk.subarray(0, remaining);
          if (chunk.length === 0) {
            this.push(null);
            return;
          }
          position += chunk.length;
          this.push(chunk);
        } catch (error) {
          stopped = true;
          this.destroy(error);
        }
      },
    });
    if (signal && typeof signal.addEventListener === "function") {
      var onAbort = function () {
        if (!stopped) {
          stopped = true;
          stream.destroy(abortError());
        }
      };
      signal.addEventListener("abort", onAbort, { once: true });
      stream.on("close", function () {
        signal.removeEventListener("abort", onAbort);
      });
    }
    return stream;
  },
  // Ranged writes; the first write truncates unless the flags append.
  createWriteStream: function (path, options) {
    options = options || {};
    var resolved = pathModule.resolve(path);
    var encoding = typeof options === "string" ? options : options.encoding;
    var flags = String(options.flags || "w");
    var append = flags.charAt(0) === "a";
    var position = options.start === undefined ? 0 : Math.max(0, Number(options.start) || 0);
    var initialized = false;
    return new Writable({
      encoding: encoding,
      write: function (chunk, callback) {
        try {
          var buffer = chunk instanceof Uint8Array ? chunk : Buffer.from(chunk, encoding || "utf8");
          if (!initialized) {
            initialized = true;
            // 'w' truncates, 'a' keeps the existing tail.
            if (!append) transact("write", resolved, "");
          }
          transact("writeRange", resolved, buffer.toString("base64"), undefined, {
            position: append ? 0 : position,
            append: append,
          });
          if (!append) position += buffer.length;
          if (callback) callback(null);
        } catch (error) {
          if (callback) callback(error);
        }
      },
    });
  },
  constants: {
    F_OK: 0,
    R_OK: 4,
    W_OK: 2,
    X_OK: 1,
    COPYFILE_EXCL: 1,
    EPERM: 1,
    ENOENT: 2,
    EBADF: 9,
    EACCES: 13,
    EBUSY: 16,
    EEXIST: 17,
    ENOTDIR: 20,
    EISDIR: 21,
    EINVAL: 22,
    EMFILE: 24,
    ENOSPC: 28,
    EROFS: 30,
    ENAMETOOLONG: 36,
    ENOSYS: 38,
    ENOTEMPTY: 39,
    ELOOP: 40,
  },
};
// Adapt an async bridge promise to the node callback convention.
function settle(promise, callback, transform) {
  promise.then(
    function (value) {
      callback(null, transform ? transform(value) : value);
    },
    function (error) {
      callback(error);
    },
  );
}
// Decode a base64 bridge payload into a Buffer or a string.
function decodedFile(value, options) {
  var bytes = Buffer.from(value, "base64"),
    encoding = encodingOf(options);
  return encoding ? bytes.toString(encoding) : bytes;
}
// Map readdirStat rows to Dirents when withFileTypes was requested.
function decodedEntries(value, options, parentPath) {
  return options && options.withFileTypes
    ? value.map(function (entry) {
        return new Dirent(entry.name, entry.directory, parentPath);
      })
    : value;
}
fsModule.exists = function (path, callback) {
  asyncTransact("exists", pathModule.resolve(path)).then(
    function (value) {
      callback(!!value);
    },
    function () {
      callback(false);
    },
  );
};
fsModule.open = function (path, flags, mode, callback) {
  if (typeof mode === "function") callback = mode;
  var resolved = pathModule.resolve(path);
  flags = flags || "r";
  if (!/^(?:r|r\+|rs|rs\+|w|wx|w\+|wx\+|a|ax|a\+|ax\+)$/.test(flags)) {
    callback(nodeError("open", resolved, "invalid"));
    return;
  }
  asyncTransact("exists", resolved).then(function (exists) {
    if (exists && flags.indexOf("x") !== -1) {
      callback(nodeError("open", resolved, "exists"));
      return;
    }
    if (!exists && flags.charAt(0) === "r") {
      callback(nodeError("open", resolved, "missing"));
      return;
    }
    var initialize = !exists || flags.charAt(0) === "w";
    var ready = initialize ? asyncTransact("write", resolved, "") : Promise.resolve();
    ready.then(function () {
      var position =
        flags.charAt(0) === "a" && exists
          ? asyncTransact("stat", resolved).then(function (value) {
              return value.size || 0;
            })
          : Promise.resolve(0);
      position.then(function (value) {
        try {
          callback(null, openDescriptor(resolved, flags, !!exists, initialize, value));
        } catch (error) {
          callback(error);
        }
      }, callback);
    }, callback);
  }, callback);
};
fsModule.close = function (fd, callback) {
  asyncCall(function () {
    return fsModule.closeSync(fd);
  }, callback);
};
fsModule.fsync = function (fd, callback) {
  asyncCall(function () {
    return fsModule.fsyncSync(fd);
  }, callback);
};
fsModule.write = function (fd, data, offset, length, position, callback) {
  callback =
    typeof callback === "function"
      ? callback
      : typeof position === "function"
        ? position
        : typeof length === "function"
          ? length
          : typeof offset === "function"
            ? offset
            : null;
  if (!callback) throw new TypeError("callback must be a function");
  var descriptor = fileDescriptors[fd];
  if (!descriptor) {
    callback(new Error("EBADF"));
    return;
  }
  if (descriptor.flags.charAt(0) === "r" && descriptor.flags.indexOf("+") === -1) {
    callback(new Error("EBADF"));
    return;
  }
  var chunk, targetPosition, result;
  if (typeof data === "string") {
    if (typeof offset === "function") {
      callback = offset;
      offset = null;
      length = "utf8";
    } else if (typeof length === "function") {
      callback = length;
      length = "utf8";
    } else if (typeof position === "function") callback = position;
    chunk = Buffer.from(data, typeof length === "string" ? length : "utf8");
    targetPosition = typeof offset === "number" ? offset : descriptor.position;
    result = data;
  } else {
    if (typeof offset === "function") {
      callback = offset;
      offset = 0;
      length = data.length;
      position = null;
    } else if (typeof length === "function") {
      callback = length;
      length = data.length - (Number(offset) || 0);
      position = null;
    } else if (typeof position === "function") {
      callback = position;
      position = null;
    }
    var source = Buffer.from(data);
    offset = Number(offset) || 0;
    length = length === undefined ? source.length - offset : Number(length);
    chunk = Buffer.from(source.subarray(offset, offset + length));
    targetPosition = position == null ? descriptor.position : Number(position);
    result = data;
  }
  var append = descriptor.flags.charAt(0) === "a";
  asyncTransact("writeRange", descriptor.path, chunk.toString("base64"), undefined, {
    position: targetPosition,
    append: append,
  }).then(function (bytesWritten) {
    if (!append && (typeof data === "string" || position == null))
      descriptor.position = targetPosition + bytesWritten;
    callback(null, bytesWritten, result);
  }, callback);
};
fsModule.read = function (fd, buffer, offset, length, position, callback) {
  var args = normalizeReadCall(buffer, offset, length, position, callback);
  callback = args.callback;
  if (typeof callback !== "function") throw new TypeError("callback must be a function");
  if (args.signal && args.signal.aborted) {
    callback(abortError());
    return;
  }
  var descriptor = fileDescriptors[fd];
  if (!descriptor) {
    callback(Object.assign(new Error("EBADF"), { code: "EBADF" }));
    return;
  }
  var finished = false;
  var stop = function () {};
  if (args.signal && typeof args.signal.addEventListener === "function") {
    var onAbort = function () {
      if (finished) return;
      finished = true;
      callback(abortError());
    };
    args.signal.addEventListener("abort", onAbort, { once: true });
    stop = function () {
      args.signal.removeEventListener("abort", onAbort);
    };
  }
  var explicit = args.position != null;
  var sourcePosition;
  try {
    sourcePosition = explicit ? readPosition(args.position) : descriptor.position;
    var window = readWindow(args.buffer, args.offset, args.length);
  } catch (error) {
    stop();
    callback(error);
    return;
  }
  asyncTransact("readRange", descriptor.path, undefined, undefined, {
    position: sourcePosition,
    size: window.length,
  }).then(
    function (value) {
      if (finished) return;
      finished = true;
      stop();
      var chunk = Buffer.from(value, "base64");
      if (chunk.length > window.length) chunk = chunk.subarray(0, window.length);
      args.buffer.set(chunk, window.offset);
      if (!explicit) descriptor.position += chunk.length;
      var payload = args.encoding
        ? args.buffer.toString(args.encoding, window.offset, window.offset + chunk.length)
        : args.buffer;
      callback(null, chunk.length, payload);
    },
    function (error) {
      if (finished) return;
      finished = true;
      stop();
      callback(error);
    },
  );
};
fsModule.readv = function (fd, buffers, position, callback) {
  if (typeof position === "function") {
    callback = position;
    position = undefined;
  }
  if (typeof callback !== "function") throw new TypeError("callback must be a function");
  setTimeout(function () {
    try {
      callback(null, fsModule.readvSync(fd, buffers, position), buffers);
    } catch (error) {
      callback(error);
    }
  }, 0);
};
fsModule.readFile = function (path, options, callback) {
  if (typeof options === "function") {
    callback = options;
    options = undefined;
  }
  if (options && options.signal && options.signal.aborted) {
    callback(abortError());
    return;
  }
  settle(asyncTransact("read", pathModule.resolve(path)), callback, function (value) {
    return decodedFile(value, options);
  });
};
fsModule.readdir = function (path, options, callback) {
  if (typeof options === "function") {
    callback = options;
    options = undefined;
  }
  var resolved = pathModule.resolve(path);
  var op = options && options.withFileTypes ? "readdirStat" : "readdir";
  settle(asyncTransact(op, resolved), callback, function (value) {
    return decodedEntries(value, options, resolved);
  });
};
["stat", "lstat"].forEach(function (name) {
  fsModule[name] = function (path, options, callback) {
    if (typeof options === "function") callback = options;
    settle(asyncTransact("stat", pathModule.resolve(path)), callback, function (value) {
      return new Stats(value);
    });
  };
});
fsModule.realpath = function (path, options, callback) {
  if (typeof options === "function") callback = options;
  var resolved = pathModule.resolve(path);
  settle(asyncTransact("stat", resolved), callback, function () {
    return resolved;
  });
};
["writeFile", "appendFile"].forEach(function (name) {
  fsModule[name] = function (path, data, options, callback) {
    if (typeof options === "function") {
      callback = options;
      options = undefined;
    }
    var op =
      name === "appendFile" ||
      (options && typeof options === "object" && String(options.flag || "w").charAt(0) === "a")
        ? "append"
        : "write";
    settle(
      asyncTransact(
        op,
        pathModule.resolve(path),
        Buffer.from(data, encodingOf(options)).toString("base64"),
      ),
      callback,
    );
  };
});
["unlink", "mkdir", "rmdir"].forEach(function (name) {
  fsModule[name] = function (path, options, callback) {
    if (typeof options === "function") {
      callback = options;
      options = undefined;
    }
    settle(asyncTransact(name, pathModule.resolve(path)), callback);
  };
});
fsModule.rename = function (source, target, callback) {
  settle(
    asyncTransact("rename", pathModule.resolve(source), undefined, pathModule.resolve(target)),
    callback,
  );
};
fsModule.copyFile = function (source, target, flags, callback) {
  if (typeof flags === "function") callback = flags;
  settle(
    asyncTransact("copy", pathModule.resolve(source), undefined, pathModule.resolve(target)),
    callback,
  );
};
fsModule.truncate = function (path, size, callback) {
  if (typeof size === "function") {
    callback = size;
    size = 0;
  }
  settle(
    asyncTransact("truncate", pathModule.resolve(path), undefined, undefined, { size: size || 0 }),
    callback,
  );
};
fsModule.rm = function (path, options, callback) {
  if (typeof options === "function") {
    callback = options;
    options = {};
  }
  options = options || {};
  settle(
    asyncTransact("rm", pathModule.resolve(path), undefined, undefined, {
      recursive: options.recursive === true,
      force: options.force === true,
    }),
    callback,
  );
};
fsModule.access = function (path, mode, callback) {
  if (typeof mode === "function") callback = mode;
  settle(asyncTransact("stat", pathModule.resolve(path)), callback, function () {});
};
// Promise view over the same bridge ops. The one-operation wrappers are generated so the
// callback and promise surfaces cannot drift apart.
fsModule.promises = {
  readFile: function (path, options) {
    return asyncTransact("read", pathModule.resolve(path)).then(function (value) {
      return decodedFile(value, options);
    });
  },
  writeFile: function (path, data, options) {
    var op =
      options && typeof options === "object" && String(options.flag || "w").charAt(0) === "a"
        ? "append"
        : "write";
    return asyncTransact(
      op,
      pathModule.resolve(path),
      Buffer.from(data, encodingOf(options)).toString("base64"),
    );
  },
  appendFile: function (path, data, options) {
    return asyncTransact(
      "append",
      pathModule.resolve(path),
      Buffer.from(data, encodingOf(options)).toString("base64"),
    );
  },
  readdir: function (path, options) {
    var resolved = pathModule.resolve(path);
    var op = options && options.withFileTypes ? "readdirStat" : "readdir";
    return asyncTransact(op, resolved).then(function (value) {
      return decodedEntries(value, options, resolved);
    });
  },
  stat: function (path) {
    return asyncTransact("stat", pathModule.resolve(path)).then(function (value) {
      return new Stats(value);
    });
  },
  lstat: function (path) {
    return fsModule.promises.stat(path);
  },
  realpath: function (path) {
    var resolved = pathModule.resolve(path);
    return asyncTransact("stat", resolved).then(function () {
      return resolved;
    });
  },
  access: function (path) {
    return asyncTransact("stat", pathModule.resolve(path)).then(function () {});
  },
  rm: function (path, options) {
    options = options || {};
    return asyncTransact("rm", pathModule.resolve(path), undefined, undefined, {
      recursive: options.recursive === true,
      force: options.force === true,
    });
  },
  truncate: function (path, size) {
    return asyncTransact("truncate", pathModule.resolve(path), undefined, undefined, {
      size: size || 0,
    });
  },
  mkdtemp: function (prefix, options) {
    return new Promise(function (resolve) {
      setTimeout(function () {
        resolve(fsModule.mkdtempSync(prefix, options));
      }, 0);
    });
  },
  constants: fsModule.constants,
};
["mkdir", "unlink", "rmdir"].forEach(function (name) {
  fsModule.promises[name] = function (path) {
    return asyncTransact(name, pathModule.resolve(path));
  };
});
["rename", "copyFile"].forEach(function (name) {
  var op = name === "copyFile" ? "copy" : "rename";
  fsModule.promises[name] = function (source, target) {
    return asyncTransact(op, pathModule.resolve(source), undefined, pathModule.resolve(target));
  };
});
// Handle returned by fs.promises.open(), bound to a descriptor.
function fileHandle(fd) {
  return {
    fd: fd,
    close: function () {
      return new Promise(function (resolve, reject) {
        fsModule.close(fd, function (error) {
          if (error) reject(error);
          else resolve();
        });
      });
    },
    read: function (buffer, offset, length, position) {
      return new Promise(function (resolve, reject) {
        var finish = function (error, bytesRead, value) {
          if (error) reject(error);
          else resolve({ bytesRead: bytesRead, buffer: value });
        };
        if (isReadOptions(buffer)) fsModule.read(fd, buffer, finish);
        else fsModule.read(fd, buffer, offset, length, position, finish);
      });
    },
    readv: function (buffers, position) {
      return new Promise(function (resolve, reject) {
        fsModule.readv(fd, buffers, position, function (error, bytesRead, value) {
          if (error) reject(error);
          else resolve({ bytesRead: bytesRead, buffers: value });
        });
      });
    },
    write: function (data, offset, length, position) {
      return new Promise(function (resolve, reject) {
        fsModule.write(fd, data, offset, length, position, function (error, bytesWritten) {
          if (error) reject(error);
          else resolve({ bytesWritten: bytesWritten, buffer: data });
        });
      });
    },
    stat: function () {
      var descriptor = fileDescriptors[fd];
      return descriptor
        ? fsModule.promises.stat(descriptor.path)
        : Promise.reject(new Error("EBADF"));
    },
    readFile: function (options) {
      var descriptor = fileDescriptors[fd];
      return descriptor
        ? fsModule.promises.readFile(descriptor.path, options)
        : Promise.reject(new Error("EBADF"));
    },
    writeFile: function (data, options) {
      var descriptor = fileDescriptors[fd];
      return descriptor
        ? fsModule.promises.writeFile(descriptor.path, data, options)
        : Promise.reject(new Error("EBADF"));
    },
    truncate: function (size) {
      var descriptor = fileDescriptors[fd];
      return descriptor
        ? fsModule.promises.truncate(descriptor.path, size === undefined ? 0 : size)
        : Promise.reject(new Error("EBADF"));
    },
    sync: function () {
      return new Promise(function (resolve, reject) {
        fsModule.fsync(fd, function (error) {
          if (error) reject(error);
          else resolve();
        });
      });
    },
  };
}

fsModule.fstatSync = function (fd) {
  var descriptor = fileDescriptors[fd];
  if (!descriptor) throw Object.assign(new Error("EBADF"), { code: "EBADF" });
  return fsModule.statSync(descriptor.path);
};
fsModule.fstat = function (fd, callback) {
  if (typeof callback !== "function") throw new TypeError("callback must be a function");
  try {
    callback(null, fsModule.fstatSync(fd));
  } catch (error) {
    callback(error);
  }
};
fsModule.ftruncateSync = function (fd, size) {
  var descriptor = fileDescriptors[fd];
  if (!descriptor) throw Object.assign(new Error("EBADF"), { code: "EBADF" });
  transact("truncate", descriptor.path, undefined, undefined, { size: size || 0 });
};
fsModule.ftruncate = function (fd, size, callback) {
  if (typeof size === "function") {
    callback = size;
    size = 0;
  }
  try {
    fsModule.ftruncateSync(fd, size);
    callback(null);
  } catch (error) {
    callback(error);
  }
};
fsModule.writeFile = function (path, data, options, callback) {
  if (typeof options === "function") {
    callback = options;
    options = undefined;
  }
  var op =
    options && typeof options === "object" && String(options.flag || "w").charAt(0) === "a"
      ? "append"
      : "write";
  settle(
    asyncTransact(
      op,
      pathModule.resolve(path),
      Buffer.from(data, encodingOf(options)).toString("base64"),
    ),
    callback,
    function () {},
  );
};
fsModule.appendFile = function (path, data, options, callback) {
  if (typeof options === "function") {
    callback = options;
    options = undefined;
  }
  settle(
    asyncTransact(
      "append",
      pathModule.resolve(path),
      Buffer.from(data, encodingOf(options)).toString("base64"),
    ),
    callback,
    function () {},
  );
};
fsModule.mkdir = function (path, options, callback) {
  if (typeof options === "function") {
    callback = options;
    options = undefined;
  }
  settle(asyncTransact("mkdir", pathModule.resolve(path)), callback, function () {});
};
fsModule.cpSync = function (source, target, options) {
  options = options || {};
  var from = pathModule.resolve(source);
  var to = pathModule.resolve(target);
  var info = fsModule.statSync(from);
  if (info.isDirectory()) {
    if (options.recursive !== true) throw nodeError("cp", from, "isdir");
    fsModule.mkdirSync(to);
    var entries = fsModule.readdirSync(from);
    for (var index = 0; index < entries.length; index++) {
      fsModule.cpSync(
        pathModule.join(from, entries[index]),
        pathModule.join(to, entries[index]),
        options,
      );
    }
    return;
  }
  fsModule.copyFileSync(from, to);
};
fsModule.writevSync = function (fd, buffers, position) {
  if (!Array.isArray(buffers))
    throw new TypeError("buffers must be an array of Buffer or Uint8Array");
  var descriptor = fileDescriptors[fd];
  if (!descriptor) throw Object.assign(new Error("EBADF"), { code: "EBADF" });
  var explicit = position != null;
  var cursor = explicit ? readPosition(position) : descriptor.position;
  var total = 0;
  for (var index = 0; index < buffers.length; index++) {
    var view = buffers[index];
    var written = fsModule.writeSync(fd, view, 0, view.length, cursor);
    total += written;
    cursor += written;
  }
  if (!explicit) descriptor.position = cursor;
  return total;
};
function unsupportedFs(name) {
  var failure = function () {
    var error = new Error(name + " is not supported by MakerPlay");
    error.code = "ENOSYS";
    return error;
  };
  fsModule[name] = function () {
    throw failure();
  };
  fsModule.promises[name] = function () {
    return Promise.reject(failure());
  };
}
[
  "watch",
  "watchFile",
  "unwatchFile",
  "chmod",
  "chmodSync",
  "chown",
  "chownSync",
  "lchmod",
  "lchmodSync",
  "lchown",
  "lchownSync",
  "lutimes",
  "lutimesSync",
  "utimes",
  "utimesSync",
  "symlink",
  "symlinkSync",
  "link",
  "linkSync",
  "readlink",
  "readlinkSync",
  "statfs",
  "statfsSync",
].forEach(unsupportedFs);
fsModule.promises.open = function (path, flags) {
  return new Promise(function (resolve, reject) {
    fsModule.open(path, flags, undefined, function (error, fd) {
      if (error) reject(error);
      else resolve(fileHandle(fd));
    });
  });
};
fsModule.promises.writeFile =
  fsModule.promises.writeFile ||
  function (path, data, options) {
    var op =
      options && typeof options === "object" && String(options.flag || "w").charAt(0) === "a"
        ? "append"
        : "write";
    return asyncTransact(
      op,
      pathModule.resolve(path),
      Buffer.from(data, encodingOf(options)).toString("base64"),
    );
  };
fsModule.promises.appendFile =
  fsModule.promises.appendFile ||
  function (path, data, options) {
    return asyncTransact(
      "append",
      pathModule.resolve(path),
      Buffer.from(data, encodingOf(options)).toString("base64"),
    );
  };
fsModule.promises.cp = function (source, target, options) {
  options = options || {};
  return fsModule.promises.stat(source).then(function (info) {
    if (!info.isDirectory()) return fsModule.promises.copyFile(source, target);
    if (options.recursive !== true)
      return Promise.reject(nodeError("cp", pathModule.resolve(source), "isdir"));
    return fsModule.promises
      .mkdir(target)
      .then(function () {
        return fsModule.promises.readdir(source);
      })
      .then(function (entries) {
        var chain = Promise.resolve();
        for (var index = 0; index < entries.length; index++) {
          (function (name) {
            chain = chain.then(function () {
              return fsModule.promises.cp(
                pathModule.join(source, name),
                pathModule.join(target, name),
                options,
              );
            });
          })(entries[index]);
        }
        return chain;
      });
  });
};

function encodingOf(options) {
  return typeof options === "string" ? options : options && options.encoding;
}
function asyncCall(action, callback) {
  callback = typeof callback === "function" ? callback : function () {};
  setTimeout(function () {
    try {
      callback(null, action());
    } catch (error) {
      callback(error);
    }
  }, 0);
}

defineBuiltin("fs", fsModule);
defineBuiltin("fs/promises", fsModule.promises);
