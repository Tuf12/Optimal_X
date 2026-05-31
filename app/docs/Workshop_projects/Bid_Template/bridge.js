/**
 * bridge.js — OptimalX ↔ panel runtime (local WebView only, not network).
 *
 * Loaded before script.js. Exposes global getState/runAction for Eidos call_panel_function.
 * App logic lives in script.js via panelGetState / panelHandleAction hooks.
 */

function registerBridgeTools() {
  if (!window.OptimalXPanelBridge || !window.OptimalXPanelBridge.registerTools) return;
  window.OptimalXPanelBridge.registerTools([
    { name: "getState", description: "Read current panel state", isMutating: false },
    { name: "runAction", description: "Run an action in panel runtime", isMutating: true },
  ]);
}

function emitBridgeEvent(name, payload) {
  if (!window.OptimalXPanelBridge || !window.OptimalXPanelBridge.emitEvent) return;
  window.OptimalXPanelBridge.emitEvent(name, payload);
}

async function getState(args = {}) {
  if (typeof window.panelGetState === "function") {
    return window.panelGetState(args);
  }
  return { status: "idle", argsEcho: args };
}

async function runAction(args = {}) {
  if (typeof window.panelHandleAction === "function") {
    return window.panelHandleAction(args);
  }
  return { ok: false, error: "no_panel_handler" };
}
window.registerBridgeTools = registerBridgeTools;

function bootPanelBridge() {
  registerBridgeTools();
  emitBridgeEvent("panel_loaded", {});
}

window.bootPanelBridge = bootPanelBridge;
window.addEventListener("optimalx-bridge-ready", registerBridgeTools);

bootPanelBridge();

