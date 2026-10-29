const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const Bridge = vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/viewer/artifact-interactions.js'), 'utf8') + '\nArtifactInteractions;', { EventTarget });
const fixture = () => Object.assign(Object.create(Bridge.prototype), {
  active: true, composerPosition: null,
  frame: { getBoundingClientRect: () => ({ left: 0, top: 48, width: 800, height: 600 }) },
  overlay: { style: {} },
  composer: { hidden: false, offsetWidth: 320, offsetHeight: 200, style: {} },
  bounds: { left: 100, top: 100, width: 100, height: 50 },
  selection: { relativeX: 0.5, relativeY: 0.5 }
});

test('manual popup placement survives anchor measurements until reset', () => {
  const bridge = fixture();
  bridge.layout();
  assert.equal(bridge.composer.style.left, '162px');
  assert.equal(bridge.composer.style.top, '137px');
  bridge.setComposerPosition({ x: 300, y: 250 });
  bridge.bounds = { left: 10, top: 10, width: 20, height: 20 };
  bridge.layout();
  assert.equal(bridge.composer.style.left, '300px');
  assert.equal(bridge.composer.style.top, '250px');
  bridge.setComposerPosition(null);
  assert.equal(bridge.composer.style.left, '32px');
  assert.equal(bridge.composer.style.top, '32px');
});

test('manual placement is clamped after resizing and remains usable if its element disappears', () => {
  const bridge = fixture();
  bridge.setComposerPosition({ x: 900, y: 900 });
  assert.equal(bridge.composer.style.left, '472px');
  assert.equal(bridge.composer.style.top, '392px');
  bridge.frame.getBoundingClientRect = () => ({ left: 0, top: 48, width: 400, height: 300 });
  bridge.bounds = null;
  bridge.layout();
  assert.equal(bridge.composer.style.left, '72px');
  assert.equal(bridge.composer.style.top, '92px');
  bridge.setComposerPosition({ x: -50, y: -50 });
  assert.equal(bridge.composer.style.left, '8px');
  assert.equal(bridge.composer.style.top, '8px');
});
