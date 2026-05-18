// bridge.js – Eidos / OptimalX WebView bridge (local only)
window.panelGetState = function() {
  return window.currentBidState || {};
};

window.panelHandleAction = function(action, payload) {
  if (action === 'loadExample') {
    window.loadExampleData && window.loadExampleData();
  }
  if (action === 'reset') {
    location.reload();
  }
};