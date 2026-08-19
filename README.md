# MakerPlay

> [!NOTE]
> Early-access Android runtime for locally running RPG Maker MV and MZ games

## Features

- Play RPG Maker MV and MZ games locally on Android
- Automatic decryption of game images and audio: MV (`.rpgmvp`, `.rpgmvo`, `.rpgmvm`) and MZ (`.png_`, `.ogg_`, `.m4a_`)
- Built-in support for LZ-String Base64 and Pako/zlib encoded game data
- Multithreaded file loading for smoother gameplay and faster access to game assets
- Automatic compatibility fixes for older games, plugins, missing optional files, fonts, and desktop-only features
- Steam and Greenworks compatibility for games that expect desktop services
- Per-game sandbox with isolated saves, writable files, settings, and web data
- Safe folder import with the option to copy a game into private storage or run it directly from its original folder
- On-screen controls with customizable layouts, plus keyboard and physical gamepad support
- Per-game display, performance, compatibility, and runtime settings

## Build

Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

Linux:

```bash
./gradlew :app:assembleDebug
```