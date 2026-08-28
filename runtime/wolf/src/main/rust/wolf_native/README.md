# `wolf_native`

Rust Wolf runtime. SDL3 stays a vendored C shared library.

| Module | Role |
|--------|------|
| `session` | session table + load/destroy/pause/exit/input |
| `renderer` / `gles` | letterbox GLES2 present |
| `jni_bridge` | `WolfNativeJni` exports |
| `sdl` | `SDL_GetVersion` / `SDL_SetMainReady` |

CMake builds SDL3, compiles this crate as a staticlib, then links `wolf_link_stub.cpp` + Rust + SDL3 into `libwolf_native.so`.
