(function () {
    "use strict";

    const intervalFrames = 10;
    let stateRevision = 0;

    function installFrameTextCache() {
        // Reuse identical escape expansions within one frame; dynamic values refresh next frame
        const manager = globalThis.PluginManagerEx;
        if (!manager || manager.__makerplayFrameTextCache ||
            typeof manager.convertEscapeCharactersEx !== "function") {
            return false;
        }
        const original = manager.convertEscapeCharactersEx;
        let cacheFrame = -1;
        let cache = new Map();
        manager.convertEscapeCharactersEx = function (text, data) {
            // A frame-local cache cannot retain an old variable, actor, or party value
            if (data != null) return original.apply(this, arguments);
            const frame = globalThis.Graphics ? Graphics.frameCount : -1;
            if (frame !== cacheFrame) {
                cacheFrame = frame;
                cache = new Map();
            }
            const key = String(text);
            if (cache.has(key)) return cache.get(key);
            const result = original.apply(this, arguments);
            cache.set(key, result);
            return result;
        };
        Object.defineProperty(manager, "__makerplayFrameTextCache", { value: true });
        return true;
    }

    function installTextColorCache() {
        // Color indexes are stable between windowskin changes, avoiding repeated canvas reads
        const windowBase = globalThis.Window_Base;
        if (!windowBase || windowBase.prototype.__makerplayTextColorCache ||
            typeof windowBase.prototype.textColor !== "function") {
            return false;
        }
        const originalTextColor = windowBase.prototype.textColor;
        const originalLoadWindowskin = windowBase.prototype.loadWindowskin;
        const colors = new Map();
        windowBase.prototype.textColor = function (index) {
            if (colors.has(index)) return colors.get(index);
            const color = originalTextColor.apply(this, arguments);
            colors.set(index, color);
            return color;
        };
        if (typeof originalLoadWindowskin === "function") {
            windowBase.prototype.loadWindowskin = function () {
                colors.clear();
                return originalLoadWindowskin.apply(this, arguments);
            };
        }
        Object.defineProperty(windowBase.prototype, "__makerplayTextColorCache", { value: true });
        return true;
    }

    function installWebGlCapabilityCache() {
        // WebGL capability cannot change during a page lifetime, so probe it only once
        const graphics = globalThis.Graphics;
        if (!graphics || graphics.__makerplayWebGlCapabilityCache ||
            typeof graphics.hasWebGL !== "function") {
            return false;
        }
        const original = graphics.hasWebGL;
        let cached;
        graphics.hasWebGL = function () {
            if (cached === undefined) cached = Boolean(original.apply(this, arguments));
            return cached;
        };
        Object.defineProperty(graphics, "__makerplayWebGlCapabilityCache", { value: true });
        return true;
    }

    function installStaticTerraxMaskCache() {
        // Static Terrax maps do not need a full-screen mask redraw every frame
        const lightmask = globalThis.Lightmask;
        if (!lightmask || lightmask.prototype.__makerplayTerraxMaskCache ||
            typeof lightmask.prototype._updateMask !== "function") {
            return false;
        }
        const original = lightmask.prototype._updateMask;
        lightmask.prototype._updateMask = function () {
            const variables = globalThis.$gameVariables;
            const map = globalThis.$gameMap;
            const player = globalThis.$gamePlayer;
            if (!variables || !map || !player ||
                typeof variables.GetDaynightSpeed !== "function" ||
                typeof variables.GetRadius !== "function" ||
                typeof variables.GetRadiusTarget !== "function" ||
                typeof variables.GetFire !== "function") {
                return original.apply(this, arguments);
            }
            const events = typeof map.events === "function" ? map.events() : [];
            const moving = typeof player.isMoving === "function" && player.isMoving() ||
                events.some(event => typeof event.isMoving === "function" && event.isMoving());
            const staticLighting = variables.GetDaynightSpeed() <= 0 &&
                variables.GetRadius() === variables.GetRadiusTarget() &&
                !variables.GetFire() && !moving;
            if (!staticLighting) {
                this.__makerplayTerraxMaskKey = null;
                return original.apply(this, arguments);
            }
            const key = [
                map.mapId(), map.displayX(), map.displayY(),
                player._realX, player._realY, player._direction,
                variables.GetRadius(), variables.GetPlayerColor && variables.GetPlayerColor(),
                variables.GetFlashlight && variables.GetFlashlight(),
                variables.GetFlashlightLength && variables.GetFlashlightLength(),
                variables.GetFlashlightWidth && variables.GetFlashlightWidth(),
                variables.GetPlayerBrightness && variables.GetPlayerBrightness(),
            ].join("|");
            if (this.__makerplayTerraxMaskKey === key) return;
            this.__makerplayTerraxMaskKey = key;
            return original.apply(this, arguments);
        };
        Object.defineProperty(lightmask.prototype, "__makerplayTerraxMaskCache", { value: true });
        return true;
    }

    function observeStateChanges(prototype) {
        if (!prototype || prototype.__makerplayPerformanceObserved) return;
        const original = prototype.setValue;
        if (typeof original !== "function") return;
        Object.defineProperty(prototype, "__makerplayPerformanceObserved", { value: true });
        prototype.setValue = function () {
            stateRevision++;
            return original.apply(this, arguments);
        };
    }

    function stateKey(pictures) {
        const actor = pictures._actor;
        return [
            stateRevision,
            actor ? actor.hp : "",
            actor ? actor.mp : "",
            actor ? actor.tp : "",
            actor ? actor._states.join(",") : "",
            globalThis.$gameMessage && $gameMessage.isBusy(),
            globalThis.SceneManager && SceneManager._scene && SceneManager._scene.constructor.name,
        ].join("|");
    }

    function installStandPictureOptimization() {
        // CharacterPictureManager rechecks many layers every frame; throttle unchanged state
        observeStateChanges(globalThis.Game_Switches && Game_Switches.prototype);
        observeStateChanges(globalThis.Game_Variables && Game_Variables.prototype);
        const StandPicture = globalThis.Sprite_StandPicture;
        if (!StandPicture || StandPicture.prototype.__makerplayPerformanceOptimization) return false;

        const originalUpdate = StandPicture.prototype.update;
        StandPicture.prototype.update = function () {
            const pictures = this._pictures;
            if (pictures && !pictures.__makerplayPerformanceOptimization) {
                const originalNeedUpdate = pictures.isNeedUpdatePicture;
                if (typeof originalNeedUpdate === "function") {
                    pictures.__makerplayPerformanceOptimization = true;
                    pictures.isNeedUpdatePicture = function () {
                        const key = stateKey(this);
                        const frame = globalThis.Graphics ? Graphics.frameCount : 0;
                        if (key !== this.__makerplayPerformanceState ||
                            frame - (this.__makerplayPerformanceFrame || 0) >= intervalFrames) {
                            this.__makerplayPerformanceState = key;
                            this.__makerplayPerformanceFrame = frame;
                            return originalNeedUpdate.apply(this, arguments);
                        }
                        return false;
                    };
                }
            }
            return originalUpdate.apply(this, arguments);
        };
        Object.defineProperty(StandPicture.prototype, "__makerplayPerformanceOptimization", { value: true });
        return true;
    }

    let attempts = 0;
    const timer = globalThis.setInterval(function () {
        installFrameTextCache();
        installTextColorCache();
        installWebGlCapabilityCache();
        installStaticTerraxMaskCache();
        installStandPictureOptimization();
        attempts++;
        if (attempts >= 400) globalThis.clearInterval(timer);
    }, 50);
}());