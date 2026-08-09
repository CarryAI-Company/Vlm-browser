/**
 * ChromeClone WebView bridge shim.
 *
 * Injected into allowed-origin pages (via evaluateJavascript in onPageStarted,
 * and again in onPageFinished as a re-patch safety net). It:
 *
 *  1. Overrides navigator.mediaDevices.getDisplayMedia() so pages can request
 *     screen sharing inside an Android WebView (which has no native support).
 *     The request is forwarded to the native side through the
 *     `ChromeCloneNative` @JavascriptInterface, which starts the
 *     MediaProjection permission flow. Frames arrive through
 *     window.__onScreenFrame(dataUrl), are drawn onto a hidden canvas, and the
 *     canvas' captureStream() MediaStream is returned to the page.
 *
 *  2. Wraps enumerateDevices() so a virtual "Screen Capture (bridged)" display
 *     device shows up alongside native camera/mic devices.
 *
 *  3. Leaves getUserMedia() alone — WebView handles camera natively once the
 *     app holds CAMERA/RECORD_AUDIO runtime permissions, including
 *     { video: { deviceId: { exact: ... } } } for camera switching.
 *
 * Injection-timing note: this script can run after some page scripts have
 * already executed. Pages almost always call navigator.mediaDevices
 * .getDisplayMedia() at click-time, and navigator.mediaDevices is a shared
 * object, so patching its properties here (with defineProperty, plus a
 * DOMContentLoaded re-patch) is enough even for pages that parsed earlier.
 * The full installer is idempotent (window.__chromeCloneShimInstalled), and the
 * getDisplayMedia/enumerateDevices patch is separately re-appliable through
 * window.__chromeClonePatch().
 */
(function () {
    'use strict';

    var SCREEN_FRAME_CANVAS_ID = '__chromeCloneScreenCanvas';
    var SCREEN_FRAME_IMAGE_ID = '__chromeCloneScreenImage';

    // ------------------------------------------------------------------
    // Shared state (survives re-injection via window.__chromeCloneState)
    // ------------------------------------------------------------------

    var screenState = window.__chromeCloneState || {
        canvas: null,
        ctx: null,
        image: null,
        stream: null,
        active: false,
        pendingResolve: null,
        pendingReject: null,
        framesReceived: 0
    };
    window.__chromeCloneState = screenState;

    function ensureMediaDevices() {
        if (!navigator.mediaDevices) {
            navigator.mediaDevices = {};
        }
        return navigator.mediaDevices;
    }

    function hasNativeBridge() {
        return typeof window.ChromeCloneNative !== 'undefined'
            && typeof window.ChromeCloneNative.startScreenCapture === 'function';
    }

    // ------------------------------------------------------------------
    // getDisplayMedia patch — idempotent and separately re-appliable.
    // Exposed as window.__chromeClonePatch so onPageFinished re-injection can
    // re-apply it even when the full installer has already run.
    // ------------------------------------------------------------------

    function patchGetDisplayMedia() {
        var md = ensureMediaDevices();
        // Keep the original implementation (if any) exactly once; on re-patch
        // the current value may already be OUR function, so do not re-capture it.
        var nativeGetDisplayMedia = (window.__chromeCloneNativeGDM !== undefined)
            ? window.__chromeCloneNativeGDM
            : (md.getDisplayMedia ? md.getDisplayMedia.bind(md) : null);
        window.__chromeCloneNativeGDM = nativeGetDisplayMedia;
        var nativeEnumerateDevices = (window.__chromeCloneNativeEnum !== undefined)
            ? window.__chromeCloneNativeEnum
            : (md.enumerateDevices ? md.enumerateDevices.bind(md) : null);
        window.__chromeCloneNativeEnum = nativeEnumerateDevices;

        var getDisplayMedia = function (constraints) {
            // Prefer the native implementation when one exists AND no Java bridge
            // is present (stock Chromium). Inside ChromeClone the bridge wins —
            // but only when the native side reports it as available for this
            // origin (isBridgeAvailable is origin-gated).
            var bridgeUsable = false;
            try {
                bridgeUsable = hasNativeBridge()
                    && (typeof window.ChromeCloneNative.isBridgeAvailable !== 'function'
                        || window.ChromeCloneNative.isBridgeAvailable());
            } catch (e) {
                // Never let a JavaBridge exception kill getDisplayMedia: if the
                // availability probe throws, assume the bridge is unusable and
                // fall back to the native implementation (or reject cleanly).
                bridgeUsable = false;
            }
            if (!bridgeUsable) {
                if (nativeGetDisplayMedia) {
                    return nativeGetDisplayMedia(constraints);
                }
                return Promise.reject(
                    new DOMException('getDisplayMedia is not supported in this WebView', 'NotSupportedError'));
            }

            var fps = 10;
            if (constraints && constraints.video) {
                if (typeof constraints.video === 'object' && constraints.video.frameRate) {
                    var fr = constraints.video.frameRate;
                    fps = (typeof fr === 'object') ? (fr.ideal || fr.max || 10) : fr;
                }
            }

            var canvas = ensureCanvas(1280, 720);
            // Draw an initial placeholder frame so captureStream produces frames immediately.
            try {
                var ctx = canvas.getContext('2d');
                ctx.fillStyle = '#101418';
                ctx.fillRect(0, 0, canvas.width, canvas.height);
                ctx.fillStyle = '#4D8DFF';
                ctx.font = '32px sans-serif';
                ctx.fillText('ChromeClone: waiting for screen capture...', 40, canvas.height / 2);
            } catch (e) { /* ignore */ }

            var stream = canvas.captureStream(Math.min(Math.max(fps, 1), 30));
            screenState.stream = stream;
            screenState.framesReceived = 0;

            // When the page stops the track, tell native to stop the capture.
            stream.getVideoTracks().forEach(function (track) {
                var origStop = track.stop.bind(track);
                track.stop = function () {
                    origStop();
                    if (hasNativeBridge()) {
                        try { window.ChromeCloneNative.stopScreenCapture(); } catch (e) {}
                    }
                };
            });

            return new Promise(function (resolve, reject) {
                screenState.pendingResolve = resolve;
                screenState.pendingReject = reject;
                try {
                    window.ChromeCloneNative.startScreenCapture();
                } catch (e) {
                    screenState.pendingResolve = null;
                    screenState.pendingReject = null;
                    reject(new DOMException('Native bridge error: ' + e, 'NotAllowedError'));
                }
            });
        };

        // defineProperty so the patch sticks even if the page (or a previous
        // injection) re-assigned the property; configurable so future re-patches
        // keep working.
        try {
            Object.defineProperty(md, 'getDisplayMedia', {
                value: getDisplayMedia,
                configurable: true,
                writable: true
            });
        } catch (e) {
            md.getDisplayMedia = getDisplayMedia;
        }

        // --------------------------------------------------------------
        // enumerateDevices: append a virtual display-capture device
        // --------------------------------------------------------------
        var enumerateDevices = function () {
            var basePromise = nativeEnumerateDevices
                ? nativeEnumerateDevices()
                : Promise.resolve([]);
            return basePromise.then(function (devices) {
                var list = Array.prototype.slice.call(devices || []);
                // Virtual screen device, discoverable by pages that enumerate first.
                var screenDevice = {
                    deviceId: 'chromeclone-screen',
                    kind: 'videoinput',
                    label: 'Screen Capture (bridged)',
                    groupId: 'chromeclone-screen-group',
                    toJSON: function () {
                        return {
                            deviceId: this.deviceId,
                            kind: this.kind,
                            label: this.label,
                            groupId: this.groupId
                        };
                    }
                };
                list.push(screenDevice);
                return list;
            });
        };
        try {
            Object.defineProperty(md, 'enumerateDevices', {
                value: enumerateDevices,
                configurable: true,
                writable: true
            });
        } catch (e) {
            md.enumerateDevices = enumerateDevices;
        }
    }

    // Always (re-)apply the patch, even when the full installer already ran.
    window.__chromeClonePatch = patchGetDisplayMedia;
    patchGetDisplayMedia();

    // ------------------------------------------------------------------
    // Camera switching (native button support)
    // ------------------------------------------------------------------
    // Tracks the active camera stream and lets the native Switch Camera
    // button cycle front/back via facingMode (deviceId cycling is unreliable
    // in WebView, which often reports a single merged camera device).

    var camState = window.__chromeCloneCamState || {
        stream: null,
        facingMode: null,
        senders: [] // { sender, track } — WebRTC senders to replaceTrack
    };
    window.__chromeCloneCamState = camState;

    // Wrap getUserMedia so we know the active stream and its facingMode,
    // and can tell native whether a camera is live (drives keep-alive).
    if (!window.__chromeCloneGumPatched && navigator.mediaDevices
            && typeof navigator.mediaDevices.getUserMedia === 'function') {
        window.__chromeCloneGumPatched = true;
        var nativeGetUserMedia = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
        window.__chromeCloneNativeGum = nativeGetUserMedia;
        var wrappedGetUserMedia = function (constraints) {
            return nativeGetUserMedia(constraints).then(function (stream) {
                if (stream && stream.getVideoTracks && stream.getVideoTracks().length > 0) {
                    camState.stream = stream;
                    var settings = stream.getVideoTracks()[0].getSettings
                            ? stream.getVideoTracks()[0].getSettings() : {};
                    camState.facingMode = settings.facingMode || null;
                }
                // Notify native: camera is now in use (keeps app alive).
                try {
                    if (window.ChromeCloneNative && window.ChromeCloneNative.setCameraActive) {
                        window.ChromeCloneNative.setCameraActive(true);
                    }
                } catch (e) { /* ignore */ }
                // When any video track ends, tell native the camera is free
                // again so it can stop the keep-alive service and save power.
                if (stream && stream.getVideoTracks) {
                    stream.getVideoTracks().forEach(function (t) {
                        if (!t.__chromeCloneEndedHooked) {
                            t.__chromeCloneEndedHooked = true;
                            t.addEventListener('ended', function () {
                                try {
                                    if (window.ChromeCloneNative && window.ChromeCloneNative.setCameraActive) {
                                        window.ChromeCloneNative.setCameraActive(false);
                                    }
                                } catch (e) { /* ignore */ }
                            });
                        }
                    });
                }
                return stream;
            });
        };
        try {
            Object.defineProperty(navigator.mediaDevices, 'getUserMedia', {
                value: wrappedGetUserMedia, configurable: true, writable: true
            });
        } catch (e) {
            navigator.mediaDevices.getUserMedia = wrappedGetUserMedia;
        }
    }

    // Track WebRTC senders so switchCamera can replaceTrack() live tracks.
    if (!window.__chromeCloneRtcPatched && typeof RTCPeerConnection !== 'undefined'
            && RTCPeerConnection.prototype && RTCPeerConnection.prototype.addTrack) {
        window.__chromeCloneRtcPatched = true;
        var nativeAddTrack = RTCPeerConnection.prototype.addTrack;
        RTCPeerConnection.prototype.addTrack = function (track, stream) {
            var sender = nativeAddTrack.call(this, track, stream);
            camState.senders.push({ sender: sender, track: track });
            return sender;
        };
    }

    /**
     * Switches the active camera stream between 'user' and 'environment'
     * facingMode. Called by the native Switch Camera button.
     * Resolves { facingMode, videosUpdated, sendersReplaced }.
     */
    window.__chromeCloneSwitchCamera = function () {
        return new Promise(function (resolve, reject) {
            if (!camState.stream || !camState.stream.getVideoTracks
                    || camState.stream.getVideoTracks().length === 0) {
                reject(new DOMException('No active camera stream to switch',
                        'InvalidStateError'));
                return;
            }
            var current = camState.facingMode;
            var next = (current === 'environment') ? 'user' : 'environment';
            var constraints = { audio: false, video: { facingMode: { exact: next } } };
            var nativeGum = window.__chromeCloneNativeGum
                    || (navigator.mediaDevices && navigator.mediaDevices.getUserMedia);
            if (!nativeGum) {
                reject(new DOMException('getUserMedia unavailable', 'NotSupportedError'));
                return;
            }
            nativeGum(constraints).then(function (newStream) {
                var newTrack = newStream.getVideoTracks()[0];
                // 1. Live WebRTC: replaceTrack on every tracked sender.
                var sendersReplaced = 0;
                camState.senders.forEach(function (entry) {
                    try {
                        if (entry.sender && entry.sender.replaceTrack) {
                            entry.sender.replaceTrack(newTrack);
                            sendersReplaced++;
                        }
                    } catch (e) { /* keep going */ }
                });
                // 2. Local preview: swap srcObject on <video> showing old stream.
                var videosUpdated = 0;
                if (document.querySelectorAll) {
                    document.querySelectorAll('video').forEach(function (v) {
                        if (v.srcObject === camState.stream) {
                            v.srcObject = newStream;
                            videosUpdated++;
                        }
                    });
                }
                // 3. Stop old tracks, remember new state.
                camState.stream.getVideoTracks().forEach(function (t) {
                    try { t.stop(); } catch (e) { /* ignore */ }
                });
                camState.stream = newStream;
                camState.facingMode = next;
                resolve({ facingMode: next, videosUpdated: videosUpdated,
                        sendersReplaced: sendersReplaced });
            }).catch(function (err) {
                reject(err);
            });
        });
    };

    // Belt-and-braces: if a different mediaDevices object shows up before
    // DOMContentLoaded (some pages polyfill it early), re-patch at that point.
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () {
            window.__chromeClonePatch();
        });
    }

    if (window.__chromeCloneShimInstalled) {
        // Already fully installed: the patch above was re-applied, nothing else
        // to do. Callbacks/state/installer remain from the first injection.
        return;
    }
    window.__chromeCloneShimInstalled = true;

    function ensureCanvas(width, height) {
        if (!screenState.canvas) {
            var canvas = document.createElement('canvas');
            canvas.id = SCREEN_FRAME_CANVAS_ID;
            canvas.style.display = 'none';
            (document.body || document.documentElement).appendChild(canvas);
            screenState.canvas = canvas;
            screenState.ctx = canvas.getContext('2d');
        }
        if (!screenState.image) {
            screenState.image = new Image();
        }
        var w = width || 1280;
        var h = height || 720;
        if (screenState.canvas.width !== w) screenState.canvas.width = w;
        if (screenState.canvas.height !== h) screenState.canvas.height = h;
        return screenState.canvas;
    }

    // ------------------------------------------------------------------
    // Native -> JS callbacks
    // ------------------------------------------------------------------

    /** Called by the native ScreenCaptureService for every captured frame. */
    window.__onScreenFrame = function (dataUrl) {
        if (!dataUrl) return;
        // Treat the first frame as proof that capture is live: the
        // __onScreenStarted signal can be dropped when the WebView attaches
        // after capture begins, so never gate frames on it alone.
        screenState.active = true;
        if (screenState.framesReceived % 100 === 0) {
            console.log('[ChromeClone] __onScreenFrame received, total=' + screenState.framesReceived);
        }
        var img = screenState.image;
        if (!img) return;
        screenState.framesReceived++;
        img.onload = function () {
            try {
                var canvas = screenState.canvas;
                if (!canvas) return;
                if (canvas.width !== img.naturalWidth) canvas.width = img.naturalWidth;
                if (canvas.height !== img.naturalHeight) canvas.height = img.naturalHeight;
                screenState.ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
            } catch (e) {
                /* drawing errors are non-fatal */
            }
        };
        img.onerror = function () {
            console.warn('[ChromeClone] __onScreenFrame image decode error');
        };
        img.src = dataUrl;
        if (screenState.pendingResolve) {
            var resolve = screenState.pendingResolve;
            screenState.pendingResolve = null;
            screenState.pendingReject = null;
            resolve(screenState.stream);
        }
    };

    /** Called by native when the capture session has started. */
    window.__onScreenStarted = function () {
        console.log('[ChromeClone] __onScreenStarted');
        screenState.active = true;
    };

    /** Called by native when the capture session ends (user or system stop). */
    window.__onScreenEnded = function () {
        console.log('[ChromeClone] __onScreenEnded (frames=' + screenState.framesReceived + ')');
        screenState.active = false;
        if (screenState.stream) {
            screenState.stream.getTracks().forEach(function (t) { t.stop(); });
        }
        screenState.stream = null;
        if (screenState.pendingReject) {
            var reject = screenState.pendingReject;
            screenState.pendingResolve = null;
            screenState.pendingReject = null;
            reject(new DOMException('Screen capture ended', 'AbortError'));
        }
    };

    /** Called by native when it detects a stall and is recovering the pipeline. */
    window.__onScreenRecovering = function () {
        console.log('[ChromeClone] __onScreenRecovering');
    };

    /**
     * Called by native when the automatic capture-restart prompt was declined.
     * The old pipeline is already torn down, so the screen stream is truly
     * dead: stop its tracks and reset state (same as __onScreenEnded, but with
     * a distinct log so diagnostics can tell the two apart).
     */
    window.__onScreenRestartDeclined = function () {
        console.log('[ChromeClone] __onScreenRestartDeclined (frames=' + screenState.framesReceived + ')');
        screenState.active = false;
        if (screenState.stream) {
            screenState.stream.getTracks().forEach(function (t) { t.stop(); });
        }
        screenState.stream = null;
        if (screenState.pendingReject) {
            var reject = screenState.pendingReject;
            screenState.pendingResolve = null;
            screenState.pendingReject = null;
            reject(new DOMException('Screen capture restart declined', 'AbortError'));
        }
    };

    /** Called by native when the user denies the MediaProjection prompt or an error occurs. */
    window.__onScreenError = function (message) {
        console.log('[ChromeClone] __onScreenError: ' + message);
        screenState.active = false;
        if (screenState.pendingReject) {
            var reject = screenState.pendingReject;
            screenState.pendingResolve = null;
            screenState.pendingReject = null;
            reject(new DOMException(message || 'Screen capture failed', 'NotAllowedError'));
        }
    };

    /**
     * Called by native on activity resume. WebView renderer work is paused in
     * the background, which can kill camera tracks and freeze screen-share
     * canvases — both show up as a black preview on return. This restores:
     *   1. A camera stream -> re-acquire getUserMedia with the same facing
     *      UNCONDITIONALLY. Checking readyState is not enough: after
     *      backgrounding, tracks can report 'live' while frames have stopped.
     *   2. A screen-share stream -> ask native for a fresh frame.
     */
    window.__chromeCloneResume = function () {
        // 1. Camera: re-acquire the stream with the same facing mode and swap
        //    it into any <video> / WebRTC sender still holding the old one.
        if (camState.stream && camState.stream.getVideoTracks
                && camState.stream.getVideoTracks().length > 0) {
            var facing = camState.facingMode || 'user';
            var nativeGum = window.__chromeCloneNativeGum
                    || (navigator.mediaDevices && navigator.mediaDevices.getUserMedia);
            if (nativeGum) {
                nativeGum({ audio: false, video: { facingMode: { exact: facing } } })
                    .then(function (newStream) {
                        var old = camState.stream;
                        // Swap any <video> still showing the old stream.
                        document.querySelectorAll('video').forEach(function (v) {
                            if (v.srcObject === old) v.srcObject = newStream;
                        });
                        // Replace on WebRTC senders too.
                        camState.senders.forEach(function (entry) {
                            try {
                                if (entry.sender && entry.sender.replaceTrack) {
                                    entry.sender.replaceTrack(newStream.getVideoTracks()[0]);
                                }
                            } catch (e) { /* keep going */ }
                        });
                        old.getVideoTracks().forEach(function (t) {
                            try { t.stop(); } catch (e) { /* ignore */ }
                        });
                        camState.stream = newStream;
                    }).catch(function () { /* device busy; try on next resume */ });
            }
        }
        // 2. Screen share: ask native to push a fresh frame so the canvas
        //    (and its captureStream track) comes back to life.
        if (screenState.active && screenState.stream) {
            try {
                if (window.ChromeCloneNative && window.ChromeCloneNative.requestFrame) {
                    window.ChromeCloneNative.requestFrame();
                }
            } catch (e) { /* ignore */ }
        }
    };

    // ------------------------------------------------------------------
    // Bridge diagnostics marker
    // ------------------------------------------------------------------

    // Marker for diagnostics / unit-test verification.
    window.__chromeCloneBridge = {
        version: 2,
        getDisplayMediaBridged: true,
        hasNativeBridge: hasNativeBridge()
    };
})();
