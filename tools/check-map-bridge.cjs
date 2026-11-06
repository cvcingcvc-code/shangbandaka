// Contract check for the bundled map's click payload. This is not visual map/device acceptance.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/map.html'), 'utf8');
const inline = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(match => match[1]);
assert.equal(inline.length, 1);
const events = {};
const clicks = [];
const map = {
  setView() { return this; }, invalidateSize() {}, getZoom() { return 16; },
  on(name, callback) { events[name] = callback; }, removeLayer() {}
};
const tiles = { addTo() { return this; }, on() {} };
const sandbox = {
  L: {
    map() { return map; }, tileLayer() { return tiles; },
    marker() { return { addTo() { return this; }, setLatLng() {} }; }
  },
  setTimeout() { return 1; }, clearTimeout() {}, requestAnimationFrame() {},
  AndroidBridge: { onMapClick(...args) { clicks.push(args); } }
};
sandbox.window = sandbox;
vm.runInNewContext(inline[0], sandbox, { timeout: 1000 });
sandbox.setInteractionContext(7);
events.click({ latlng: { lat: 31.2, lng: 121.4 } });
assert.deepEqual(clicks.pop(), [31.2, 121.4, 7]);
sandbox.setCenter(31.3, 121.5, 8, false);
events.click({ latlng: { lat: 31.4, lng: 121.6 } });
assert.deepEqual(clicks.pop(), [31.4, 121.6, 8]);
sandbox.clearSelection();
sandbox.setInteractionContext(9);
events.click({ latlng: { lat: 31.5, lng: 121.7 } });
assert.deepEqual(clicks.pop(), [31.5, 121.7, 9]);
process.stdout.write('PASS: bundled map click payload preserves the active context (3 cases)\n');
