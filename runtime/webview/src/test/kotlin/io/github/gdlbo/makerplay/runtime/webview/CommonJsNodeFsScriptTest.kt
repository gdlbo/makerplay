package io.github.gdlbo.makerplay.runtime.webview

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class CommonJsNodeFsScriptTest {
    @Test
    fun `maps dirents, path resolution and error codes through the native bridge`() {
        runHarness(
            """
            const fs = globalThis.require("fs");
            const path = globalThis.require("path");

            // The plugin pattern from SDReadFile.js: enumerate a content folder that is addressed
            // through the package root, then descend into its sub-directories.
            responses.set("readdirStat /game/www/img/pictures", {
              ok: true,
              data: [
                { name: "hero.rpgmvp", file: true, directory: false },
                { name: "scenes", file: false, directory: true },
              ],
            });
            const dirents = fs.readdirSync("/game/www/img/pictures", { withFileTypes: true });
            assert.deepEqual(dirents.map((entry) => entry.name), ["hero.rpgmvp", "scenes"]);
            assert.equal(dirents[0].isFile(), true);
            assert.equal(dirents[0].isDirectory(), false);
            assert.equal(dirents[1].isFile(), false);
            assert.equal(dirents[1].isDirectory(), true);
            assert.equal(dirents[0].isSymbolicLink(), false);
            assert.equal(dirents[0].isBlockDevice(), false);
            assert.equal(dirents[0].isCharacterDevice(), false);
            assert.equal(dirents[0].isFIFO(), false);
            assert.equal(dirents[0].isSocket(), false);
            assert.equal(dirents[0].parentPath, "/game/www/img/pictures");
            assert.equal(calls[calls.length - 1].path, "/game/www/img/pictures");

            // Relative paths resolve against the mounted game root, never the WebView origin.
            responses.set("readdir /game/www/img/pictures", { ok: true, data: ["hero.rpgmvp", "scenes"] });
            assert.deepEqual(fs.readdirSync("www/img/pictures"), ["hero.rpgmvp", "scenes"]);
            assert.equal(calls[calls.length - 1].path, "/game/www/img/pictures");
            assert.equal(path.resolve("www/img/pictures"), "/game/www/img/pictures");

            // Encoded reads keep working for files addressed through the package root.
            responses.set("read /game/www/index.html", {
              ok: true,
              data: Buffer.from("<html></html>").toString("base64"),
            });
            assert.equal(fs.readFileSync("www/index.html", "utf8"), "<html></html>");

            const reasonCodes = [
              ["missing", "ENOENT"],
              ["notdir", "ENOTDIR"],
              ["isdir", "EISDIR"],
              ["notempty", "ENOTEMPTY"],
              ["exists", "EEXIST"],
              ["busy", "EBUSY"],
              ["forbidden", "EACCES"],
              ["closed", "EBADF"],
              ["unsupported", "ENOSYS"],
              ["invalid", "EINVAL"],
            ];
            for (const [reason, code] of reasonCodes) {
              const probe = "/game/probe-" + reason;
              responses.set("stat " + probe, { ok: false, error: reason });
              assert.throws(
                () => fs.statSync(probe),
                (error) => {
                  assert.equal(error.code, code, reason);
                  assert.equal(error.syscall, "stat");
                  assert.equal(error.path, probe);
                  return true;
                },
              );
            }

            // Node stat promises an mtime; plugins use it for cache invalidation.
            responses.set("stat /game/save/file1.rpgsave", {
              ok: true,
              data: { file: true, directory: false, size: 12, mtimeMs: 1700000000000 },
            });
            const stats = fs.statSync("/game/save/file1.rpgsave");
            assert.equal(stats.size, 12);
            assert.equal(stats.mtimeMs, 1700000000000);
            assert.equal(stats.mtime instanceof Date, true);
            assert.equal(stats.isFile(), true);
            assert.equal(stats.isFIFO(), false);
            """,
        )
    }

    @Test
    fun `writes descriptors through ranged native writes instead of rewriting the file`() {
        runHarness(
            """
            const fs = globalThis.require("fs");

            responses.set("exists /game/save/log.txt", { ok: true, data: false });
            responses.set("write /game/save/log.txt", { ok: true });
            responses.set("writeRange /game/save/log.txt", { ok: true, data: 5 });
            const fd = fs.openSync("/game/save/log.txt", "w");
            assert.equal(fs.writeSync(fd, "hello"), 5);
            assert.equal(calls[calls.length - 1].op, "writeRange");
            assert.equal(calls[calls.length - 1].append, false);
            assert.equal(calls[calls.length - 1].position, 0);
            assert.equal(Buffer.from(calls[calls.length - 1].data, "base64").toString(), "hello");
            fs.closeSync(fd);

            // Appends must never read the whole file first.
            responses.set("exists /game/save/append.txt", { ok: true, data: true });
            responses.set("stat /game/save/append.txt", {
              ok: true,
              data: { file: true, directory: false, size: 3, mtimeMs: 1 },
            });
            responses.set("writeRange /game/save/append.txt", { ok: true, data: 2 });
            const appendFd = fs.openSync("/game/save/append.txt", "a");
            assert.equal(fs.writeSync(appendFd, "!!"), 2);
            assert.equal(calls[calls.length - 1].op, "writeRange");
            assert.equal(calls[calls.length - 1].append, true);
            fs.closeSync(appendFd);

            // Positional writes still target the requested offset.
            responses.set("write /game/save/pos.txt", { ok: true });
            responses.set("exists /game/save/pos.txt", { ok: true, data: true });
            responses.set("writeRange /game/save/pos.txt", { ok: true, data: 3 });
            const positional = fs.openSync("/game/save/pos.txt", "r+");
            fs.writeSync(positional, Buffer.from("abc"), 0, 3, 4);
            assert.equal(calls[calls.length - 1].position, 4);
            fs.closeSync(positional);

            assert.equal(fs.constants.ENOENT, 2);
            assert.equal(fs.constants.F_OK, 0);

            // mkdtemp must create a unique directory without escaping the mounted root.
            const made = fs.mkdtempSync("/data/tmp/run-");
            assert.equal(made.startsWith("/data/tmp/run-"), true);
            assert.equal(made.length > "/data/tmp/run-".length, true);
            assert.equal(calls[calls.length - 1].op, "mkdir");
            """,
        )
    }

    @Test
    fun `implements node buffer encodings views and binary accessors`() {
        runHarness(
            """
            const assert = require("node:assert/strict");
            // The harness keeps Node's own global Buffer, so opt into the shim explicitly.
            const Buffer = globalThis.require("buffer").Buffer;

            // Encodings follow Node byte semantics (latin1 is not windows-1252).
            assert.equal(Buffer.from("h\u00e9", "latin1").length, 2);
            assert.equal(Buffer.from([0x80, 0xff]).toString("latin1"), "\u0080\u00ff");
            assert.equal(Buffer.from([0x80, 0xff]).toString("binary"), "\u0080\u00ff");
            assert.equal(Buffer.from([0xc4, 0xff]).toString("ascii"), "\u0044\u007f");
            assert.equal(Buffer.from("AB", "utf16le").length, 4);
            assert.equal(Buffer.from([0x41, 0x00, 0x42, 0x00]).toString("utf16le"), "AB");
            assert.equal(Buffer.from("AB", "ucs2").toString("utf16le"), "AB");
            assert.equal(Buffer.from("AB", "" ).toString("hex"), "4142");
            assert.equal(Buffer.isEncoding("utf16le"), true);
            assert.equal(Buffer.isEncoding("utf16"), false);
            assert.equal(Buffer.isEncoding(""), false);
            assert.throws(() => Buffer.from("x", "nope"), TypeError);

            // Base64 tolerates padding, whitespace and stray characters like Node does.
            assert.equal(Buffer.from("aGVs bG8=", "base64").toString(), "hello");
            assert.equal(Buffer.from("aGVsbG8", "base64").toString(), "hello");
            assert.equal(Buffer.from("hello").toString("base64"), "aGVsbG8=");
            assert.equal(Buffer.from("a-b_c", "base64url").length, 3);
            assert.equal(Buffer.from("deadBEEF", "hex").toString("hex"), "deadbeef");
            assert.equal(Buffer.from("deadBEEFzz", "hex").length, 4);
            assert.equal(Buffer.from([0x00, 0x0f, 0xff]).toString("hex"), "000fff");

            // String chunks larger than the internal codec chunk keep their order.
            const big = Buffer.from("x".repeat(70000));
            assert.equal(big.length, 70000);
            assert.equal(big.toString("hex").length, 140000);
            assert.equal(Buffer.from(big.toString("base64"), "base64").equals(big), true);

            // subarray/slice are views, not copies.
            const source = Buffer.from([1, 2, 3, 4]);
            const view = source.subarray(1, 3);
            assert.equal(Buffer.isBuffer(view), true);
            view[0] = 9;
            assert.equal(source[1], 9);
            const sliced = source.slice(1);
            sliced[0] = 7;
            assert.equal(source[1], 7);
            assert.equal(Buffer.isBuffer(source.subarray(0)), true);

            // concat truncates like Node instead of throwing.
            assert.equal(Buffer.concat([Buffer.from("ab"), Buffer.from("cd")], 3).toString(), "abc");
            assert.equal(Buffer.concat([Buffer.from("ab"), Buffer.from("cd")]).toString(), "abcd");
            assert.equal(Buffer.concat([Buffer.from("ab")], 4).toString("hex"), "61620000");
            assert.equal(Buffer.concat([], 4).length, 0);

            // copy/equals/compare/indexOf/write/fill.
            const target = Buffer.alloc(6, 0);
            assert.equal(Buffer.from("abc").copy(target, 1), 3);
            assert.equal(target.toString("latin1"), "\u0000abc\u0000\u0000");
            assert.equal(Buffer.from("abc").equals(Buffer.from("abc")), true);
            assert.equal(Buffer.from("abc").equals(Buffer.from("abd")), false);
            assert.equal(Buffer.compare(Buffer.from("abc"), Buffer.from("abd")), -1);
            assert.equal(Buffer.from("abd").compare(Buffer.from("abc")), 1);
            assert.equal(Buffer.from("hello world").indexOf("world"), 6);
            assert.equal(Buffer.from("hello world").includes(Buffer.from("lo w")), true);
            assert.equal(Buffer.from("abc").indexOf("z"), -1);
            const written = Buffer.alloc(5);
            assert.equal(written.write("hello", 0, "utf8"), 5);
            assert.equal(written.toString(), "hello");
            assert.equal(Buffer.alloc(4, "ab").toString(), "abab");

            // Binary accessors used by save and asset parsers.
            const binary = Buffer.alloc(16);
            assert.equal(binary.writeUInt8(255, 0), 1);
            assert.equal(binary.writeUInt16LE(0x1234, 1), 3);
            assert.equal(binary.writeUInt32BE(0xdeadbeef, 4), 8);
            assert.equal(binary.writeInt16LE(-2, 8), 10);
            assert.equal(binary.writeFloatLE(1.5, 12), 16);
            assert.equal(binary.readUInt8(0), 255);
            assert.equal(binary.readUInt16LE(1), 0x1234);
            assert.equal(binary.readUInt32BE(4), 0xdeadbeef);
            assert.equal(binary.readInt16LE(8), -2);
            assert.equal(binary.readFloatLE(12), 1.5);
            assert.throws(() => binary.readUInt32LE(14), RangeError);
            """,
        )
    }

    @Test
    fun `provides process events util os crypto assert and string decoding helpers`() {
        runHarness(
            """
            const util = globalThis.require("util");
            const os = globalThis.require("os");
            const crypto = globalThis.require("crypto");
            const assertModule = globalThis.require("assert");
            const events = globalThis.require("events");
            const decoderModule = globalThis.require("string_decoder");
            const nodeProcess = globalThis.require("process");

            // process surface used by memory guards and crash handlers.
            assert.equal(typeof nodeProcess.memoryUsage().heapUsed, "number");
            assert.equal(nodeProcess.memoryUsage().heapUsed >= 0, true);
            const hrtime = nodeProcess.hrtime();
            assert.equal(Array.isArray(hrtime), true);
            assert.equal(hrtime[0] >= 0, true);
            assert.equal(typeof nodeProcess.umask(), "number");
            assert.equal(nodeProcess.stdin.isTTY, false);

            const failures = [];
            nodeProcess.on("uncaughtException", (error) => failures.push(error.message));
            listeners.get("error").forEach((handler) => handler({ message: "boom", error: new Error("boom") }));
            assert.deepEqual(failures, ["boom"]);

            // util helpers commonly required by libraries.
            assert.equal(util.types.isPromise(Promise.resolve()), true);
            assert.equal(util.types.isTypedArray(new Uint8Array(1)), true);
            assert.equal(util.types.isDate(new Date()), true);
            assert.equal(util.isDeepStrictEqual({ a: [1, 2] }, { a: [1, 2] }), true);
            assert.equal(util.isDeepStrictEqual({ a: 1 }, { a: "1" }), false);
            assert.equal(new util.TextDecoder("utf-8").decode(Buffer.from("hi")), "hi");
            let called = null;
            util.callbackify(async () => 42)((error, value) => { called = [error, value]; });
            assert.equal(typeof util.stripVTControlCharacters("\u001b[31mred\u001b[0m"), "string");
            await new Promise((resolve) => setTimeout(resolve, 0));
            assert.deepEqual(called, [null, 42]);

            // os.cpus() is used to size worker pools.
            assert.equal(Array.isArray(os.cpus()), true);
            assert.equal(os.cpus().length >= 1, true);
            assert.equal(os.cpus()[0].model.length > 0, true);
            assert.equal(os.tmpdir(), "/data/tmp");
            assert.equal(os.EOL, "\n");
            assert.equal(typeof os.uptime(), "number");

            // crypto helpers.
            const uuid = crypto.randomUUID();
            assert.equal(uuid.length, 36);
            assert.equal(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/.test(uuid), true);
            assert.equal(crypto.randomBytes(8).length, 8);
            assert.equal(crypto.randomInt(3, 4), 3);
            const filled = crypto.randomFillSync(new Uint8Array(8));
            assert.equal(filled.length, 8);

            // assert module.
            assertModule.strictEqual(1, 1);
            assertModule.deepStrictEqual({ a: [1, 2] }, { a: [1, 2] });
            assertModule.notStrictEqual(1, 2);
            assertModule.throws(() => { throw new TypeError("nope"); }, TypeError);
            assertModule.throws(() => { throw new Error("nope"); });
            assertModule.doesNotThrow(() => 1);
            assertModule.ok(true);
            assertModule.ifError(null);
            assert.throws(() => assertModule.ok(false), assertModule.AssertionError);

            // EventEmitter ordering helpers.
            const emitter = new events();
            const order = [];
            emitter.on("tick", () => order.push("on"));
            emitter.prependListener("tick", () => order.push("prepend"));
            emitter.prependOnceListener("tick", () => order.push("prependOnce"));
            emitter.emit("tick");
            emitter.emit("tick");
            assert.deepEqual(order, ["prependOnce", "prepend", "on", "prepend", "on"]);
            assert.equal(emitter.rawListeners("tick").length, 2);

            // StringDecoder keeps split multi-byte sequences.
            const decoder = new decoderModule.StringDecoder("utf8");
            const euro = Buffer.from("\u20ac", "utf8");
            assert.equal(euro.length, 3);
            assert.equal(decoder.write(euro.subarray(0, 1)), "");
            assert.equal(decoder.write(euro.subarray(1)), "\u20ac");
            """,
        )
    }

    @Test
    fun `routes promise file reads through the async bridge with a bounded queue`() {
        runHarness(
            """
            const fs = globalThis.require("fs");

            assert.equal(await fs.promises.readFile("/game/www/index.html", "utf8"), "async");
            assert.equal(asyncCalls[0].op, "read");
            assert.equal(asyncCalls[0].path, "/game/www/index.html");

            // A game must not be able to park unbounded payload bytes in the queue.
            const results = [];
            for (let index = 0; index < 64; index++) {
              results.push(
                fs.promises.writeFile("/data/big" + index, "x".repeat(1024 * 1024))
                  .then(() => "ok", (error) => error.code),
              );
            }
            const codes = await Promise.all(results);
            assert.equal(codes.filter((code) => code === "EBUSY").length > 0, true);
            assert.equal(codes.filter((code) => code === "ok").length > 0, true);
            """,
        )
    }

    @Test
    fun `matches node posix path semantics for the sandboxed module`() {
        runHarness(
            """
            const shim = globalThis.require("path");
            const node = require("node:path").posix;
            const attempt = (fn) => {
              try { return "ok:" + JSON.stringify(fn()); }
              catch (error) { return "err:" + (error && (error.code || error.name)); }
            };
            const mismatches = [];
            const compare = (name, fn) => {
              const actual = attempt(() => fn(shim));
              const expected = attempt(() => fn(node));
              if (actual !== expected) mismatches.push(name + " shim=" + actual + " node=" + expected);
            };
            const paths = [
              "", ".", "/", "//", "a", "a/b", "a/b/", "/a/b", "/a/b/c", "a/../b", "../a",
              "a/./b", "/a//b///c", "a//", "foo.txt", "/a/foo.txt", ".hidden", "a/.hidden",
              "a/b/..", "./a", "c:", "c:/a", "..", "/..", "/.", "a/..", "a/b/../..",
              "/a/b.txt.tar.gz", "a.", "a..b", "/.a.b", "/..a", "a/b/",
            ];
            for (const value of paths) {
              compare("normalize(" + value + ")", (m) => m.normalize(value));
              compare("dirname(" + value + ")", (m) => m.dirname(value));
              compare("basename(" + value + ")", (m) => m.basename(value));
              compare("basename(" + value + ",.txt)", (m) => m.basename(value, ".txt"));
              compare("extname(" + value + ")", (m) => m.extname(value));
              compare("isAbsolute(" + value + ")", (m) => m.isAbsolute(value));
              compare("parse(" + value + ")", (m) => m.parse(value));
            }
            for (const from of ["/a/b", "/a/b/c", "/x", "/a", "/a/b/c/d"]) {
              for (const to of ["/a/b", "/a/b/c/d", "/a", "/a/x", "/"]) {
                compare("relative(" + from + "," + to + ")", (m) => m.relative(from, to));
              }
            }
            for (const values of [["a", "b"], ["/a", "b"], ["/a", "/b"], ["a", ""], ["", "b"], ["/", "a"], ["/a/", "/b/"], ["a", "b", "c"]]) {
              compare("join(" + values.join(",") + ")", (m) => m.join.apply(m, values));
            }
            compare("format-dir-base", (m) => m.format({ dir: "/a", base: "b.txt" }));
            compare("format-name-ext", (m) => m.format({ name: "b", ext: ".txt" }));
            compare("format-ext-no-dot", (m) => m.format({ dir: "/a", name: "b", ext: "txt" }));
            compare("format-root", (m) => m.format({ root: "/", name: "b" }));
            compare("format-empty", (m) => m.format({}));
            assert.deepEqual(mismatches, []);
            """,
        )
    }

    @Test
    fun `hashes and compresses through the crypto and zlib builtins`() {
        runHarness(
            """
            const crypto = globalThis.require("crypto");
            const zlib = globalThis.require("zlib");
            const nodeCrypto = require("node:crypto");
            const nodeZlib = require("node:zlib");

            const text = "hello world";
            for (const algorithm of ["md5", "sha1", "sha256", "sha512"]) {
              assert.equal(
                crypto.createHash(algorithm).update(text).digest("hex"),
                nodeCrypto.createHash(algorithm).update(text).digest("hex"),
              );
            }
            const multi = crypto.createHash("sha-256");
            multi.update("hello ");
            multi.update(Buffer.from("world"));
            assert.equal(multi.digest("base64"), nodeCrypto.createHash("sha256").update(text).digest("base64"));
            assert.equal(
              crypto.createHmac("sha256", "key").update(text).digest("hex"),
              nodeCrypto.createHmac("sha256", "key").update(text).digest("hex"),
            );
            assert.equal(crypto.timingSafeEqual(Buffer.from("abc"), Buffer.from("abc")), true);
            assert.equal(crypto.timingSafeEqual(Buffer.from("abc"), Buffer.from("abd")), false);
            assert.throws(() => crypto.createHash("nope"), TypeError);

            // Sync transforms interoperate with node's zlib in both directions.
            const payload = Buffer.from("x".repeat(50000) + "\u20ac");
            const pairs = [
              ["deflateSync", "inflateSync"],
              ["deflateRawSync", "inflateRawSync"],
              ["gzipSync", "gunzipSync"],
            ];
            for (const [compress, decompress] of pairs) {
              const produced = zlib[compress](payload);
              assert.equal(produced.length < payload.length, true);
              assert.equal(nodeZlib[decompress](produced).equals(payload), true);
              assert.equal(zlib[decompress](nodeZlib[compress](payload)).equals(payload), true);
              assert.equal(zlib[decompress](produced).equals(payload), true);
            }
            assert.equal(zlib.constants.Z_BEST_COMPRESSION, 9);

            // Malformed streams surface node's code instead of a generic failure.
            responses.set("zlib ", { ok: false, error: "zdata" });
            assert.throws(
              () => zlib.inflateSync(Buffer.from("not a stream")),
              (error) => error.code === "Z_DATA_ERROR",
            );
            responses.delete("zlib ");

            // Promise and callback forms run through the async bridge.
            const asyncGzip = await zlib.gzip(payload);
            assert.equal(nodeZlib.gunzipSync(asyncGzip).equals(payload), true);
            const callbackResult = await new Promise((resolve, reject) => {
              zlib.deflateRaw(payload, { level: 9 }, (error, result) => error ? reject(error) : resolve(result));
            });
            assert.equal(zlib.inflateRawSync(callbackResult).equals(payload), true);
            assert.equal(asyncCalls.filter((call) => call.op === "zlib").length >= 2, true);

            // Streaming helpers buffer and emit on end.
            const chunks = [];
            const gzipStream = zlib.createGzip();
            gzipStream.on("data", (chunk) => chunks.push(Buffer.from(chunk)));
            const ended = new Promise((resolve) => gzipStream.on("end", resolve));
            gzipStream.end(payload);
            await ended;
            assert.equal(nodeZlib.gunzipSync(Buffer.concat(chunks)).equals(payload), true);
            """,
        )
    }

    @Test
    fun `pipes bytes through the stream module and fs stream helpers`() {
        runHarness(
            """
            const fs = globalThis.require("fs");
            const stream = globalThis.require("stream");

            responses.set("read /game/save/log.txt", {
              ok: true,
              data: Buffer.from("line1\nline2\n").toString("base64"),
            });
            const chunks = [];
            const reader = fs.createReadStream("/game/save/log.txt", { highWaterMark: 4 });
            reader.on("data", (chunk) => chunks.push(Buffer.from(chunk)));
            await new Promise((resolve, reject) => {
              reader.on("end", resolve);
              reader.on("error", reject);
            });
            assert.equal(chunks.length > 1, true);
            assert.equal(Buffer.concat(chunks).toString(), "line1\nline2\n");

            // Reading a missing file reports the node error code.
            const missing = fs.createReadStream("/game/save/missing.txt");
            const failure = await new Promise((resolve) => {
              missing.on("error", resolve);
              missing.on("end", () => resolve(null));
            });
            assert.equal(failure && failure.code, "ENOENT");

            // Readable -> Writable pipe drains and finishes the destination.
            const collected = [];
            const sink = new stream.Writable({
              write(chunk, callback) { collected.push(Buffer.from(chunk)); callback(); },
            });
            const source = new stream.Readable({
              read() { this.push("a"); this.push("b"); this.push(null); },
            });
            await new Promise((resolve) => { sink.on("finish", resolve); source.pipe(sink); });
            assert.equal(Buffer.concat(collected).toString(), "ab");

            // pipeline + PassThrough + finished.
            const output = [];
            await stream.promises.pipeline(
              new stream.Readable({ read() { this.push("xy"); this.push(null); } }),
              new stream.PassThrough(),
              new stream.Writable({ write(chunk, callback) { output.push(Buffer.from(chunk)); callback(); } }),
            );
            assert.equal(Buffer.concat(output).toString(), "xy");

            const transform = new stream.Transform({
              transform(chunk, encoding, callback) { callback(null, Buffer.from(String(chunk).toUpperCase())); },
            });
            const transformed = [];
            transform.on("data", (chunk) => transformed.push(Buffer.from(chunk)));
            await new Promise((resolve) => {
              transform.on("end", resolve);
              transform.end("abc");
            });
            assert.equal(Buffer.concat(transformed).toString(), "ABC");

            // fs.createWriteStream writes chunks through the ranged native op.
            responses.set("write /game/save/out.txt", { ok: true });
            responses.set("writeRange /game/save/out.txt", { ok: true, data: 2 });
            const writer = fs.createWriteStream("/game/save/out.txt");
            await new Promise((resolve, reject) => {
              writer.on("finish", resolve);
              writer.on("error", reject);
              writer.end("hi");
            });
            const writes = calls.filter((call) => call.op === "writeRange" && call.path === "/game/save/out.txt");
            assert.equal(writes.length, 1);
            assert.equal(Buffer.from(writes[0].data, "base64").toString(), "hi");
            assert.equal(writes[0].append, false);
            """,
        )
    }

    private fun runHarness(body: String) {
        val bundle = COMMON_JS_PARTS.joinToString(separator = "") { runtimeAsset(it) }
            .replace("__MAKERPLAY_NODE_TOKEN__", "\"test-token\"")
        val harness = """
            const assert = require("node:assert/strict");
            const responses = new Map();
            const calls = [];
            const asyncCalls = [];
            const listeners = new Map();
            globalThis.document = { title: "", documentElement: {}, head: null, body: null };
            globalThis.navigator = { userAgent: "test", clipboard: null };
            globalThis.innerWidth = 1280;
            globalThis.innerHeight = 720;
            globalThis.addEventListener = (type, handler) => {
              const handlers = listeners.get(type) || [];
              handlers.push(handler);
              listeners.set(type, handlers);
            };
            const nodeCrypto = require("node:crypto");
            const nodeZlib = require("node:zlib");
            const ZLIB_NODE_OPS = {
              deflate: "deflateSync", inflate: "inflateSync", deflateRaw: "deflateRawSync",
              inflateRaw: "inflateRawSync", gzip: "gzipSync", gunzip: "gunzipSync",
            };
            // Stands in for the platform (Kotlin/JDK) implementation of the crypto/zlib ops.
            const bridgeTransform = (value) => {
              if (value.op === "hash") {
                return { ok: true, data: nodeCrypto.createHash(value.algo).update(Buffer.from(value.data, "base64")).digest("base64") };
              }
              if (value.op === "hmac") {
                return { ok: true, data: nodeCrypto.createHmac(value.algo, Buffer.from(value.key, "base64")).update(Buffer.from(value.data, "base64")).digest("base64") };
              }
              if (value.op === "zlib") {
                const options = typeof value.level === "number" && value.level >= 0 ? { level: value.level } : undefined;
                return { ok: true, data: nodeZlib[ZLIB_NODE_OPS[value.format]](Buffer.from(value.data, "base64"), options).toString("base64") };
              }
              return null;
            };
            globalThis.makerplayNodeAsyncNative = {
              postMessage(text) {
                const value = JSON.parse(text);
                asyncCalls.push(value);
                const canned = responses.get(value.op + " " + value.path) || bridgeTransform(value) ||
                  { ok: true, data: Buffer.from("async").toString("base64") };
                setTimeout(() => {
                  if (this.onmessage) {
                    this.onmessage({ data: JSON.stringify(Object.assign({ v: 1, id: value.id }, canned)) });
                  }
                }, 0);
              },
            };
            globalThis.makerplayNodeNative = {
              transact(token, request) {
                assert.equal(token, "test-token");
                const value = JSON.parse(request);
                calls.push(value);
                const response = responses.get(value.op + " " + value.path);
                if (response) return JSON.stringify(Object.assign({ v: 1, id: value.id }, response));
                if (value.op === "mkdir" && value.path.indexOf("/data/tmp/run-") === 0) {
                  return JSON.stringify({ v: 1, id: value.id, ok: true });
                }
                const transformed = bridgeTransform(value);
                if (transformed) return JSON.stringify(Object.assign({ v: 1, id: value.id }, transformed));
                return JSON.stringify({ v: 1, id: value.id, ok: false, error: "invalid-response" });
              },
            };

            $bundle

            (async () => {
            $body
            })().then(() => {
              process.exitCode = 0;
            }, (error) => {
              console.error(error && error.stack ? error.stack : error);
              process.exitCode = 1;
            });

            setTimeout(() => {
              console.error("CommonJS harness timed out");
              process.exit(1);
            }, 10000).unref();
        """.trimIndent()
        val scriptFile = Files.createTempFile("commonjs-node-fs", ".js")
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

    private companion object {
        val COMMON_JS_PARTS = listOf(
            "commonjs/bootstrap.js",
            "commonjs/buffer.js",
            "commonjs/path.js",
            "commonjs/events.js",
            "commonjs/process.js",
            "commonjs/util.js",
            "commonjs/stream.js",
            "commonjs/fs.js",
            "commonjs/crypto.js",
            "commonjs/zlib.js",
            "commonjs/assert.js",
            "commonjs/host.js",
            "commonjs/unsupported.js",
            "commonjs/nw.js",
            "commonjs/loader.js",
        )
    }
}
