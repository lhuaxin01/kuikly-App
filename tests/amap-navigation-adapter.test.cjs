const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require(process.argv[2] || 'C:/Program Files/Huawei/DevEco Studio/tools/hvigor/hvigor/node_modules/typescript');

const navigationRoot = path.resolve(__dirname, '../ohosApp/entry/src/main/ets/navigation');
const fakeKey = 'test-only-harmony-key-do-not-use';

class FakeTimers {
  nextId = 0;
  now = 0;
  tasks = new Map();
  setTimeout(action, delay = 0) {
    const id = ++this.nextId;
    this.tasks.set(id, { action, at: this.now + delay });
    return id;
  }
  clearTimeout(id) { this.tasks.delete(id); }
  advance(ms) {
    const target = this.now + ms;
    while (true) {
      const next = [...this.tasks.entries()].filter(([, task]) => task.at <= target)
        .sort((a, b) => a[1].at - b[1].at)[0];
      if (!next) break;
      this.now = next[1].at;
      this.tasks.delete(next[0]);
      next[1].action();
    }
    this.now = target;
  }
}

// Transpile the repository's real .ets/.ts sources, then evaluate them in a
// separate context. Only SDK imports are mocked; adapter/error logic is real.
function loadSources(sdk, timers) {
  const cache = new Map();
  function load(file) {
    if (cache.has(file)) return cache.get(file).exports;
    const module = { exports: {} };
    cache.set(file, module);
    const source = fs.readFileSync(file, 'utf8');
    const output = ts.transpileModule(source, {
      fileName: file.replace(/\.ets$/, '.ts'),
      compilerOptions: { target: ts.ScriptTarget.ES2021, module: ts.ModuleKind.CommonJS }
    }).outputText;
    function mockedRequire(id) {
      if (Object.prototype.hasOwnProperty.call(sdk, id)) return sdk[id];
      assert.ok(id.startsWith('.'), `Unexpected external dependency ${id}`);
      const absolute = path.resolve(path.dirname(file), id);
      const resolved = ['.ts', '.ets'].map(ext => absolute + ext).find(candidate => fs.existsSync(candidate));
      assert.ok(resolved, `Missing source dependency ${id}`);
      return load(resolved);
    }
    const context = vm.createContext({
      module, exports: module.exports, require: mockedRequire,
      setTimeout: timers.setTimeout.bind(timers), clearTimeout: timers.clearTimeout.bind(timers),
      console
    });
    vm.runInContext(output, context, { filename: file });
    return module.exports;
  }
  return load;
}

function fixture() {
  const timers = new FakeTimers();
  const calls = [];
  const logs = [];
  const routeFailures = [];
  const fatalFailures = [];
  const routes = [];
  const listeners = [];
  const lifecycle = [];
  const locationFailures = [];
  const navigationEvents = [];
  const cameraCalls = [];
  const positions = [];
  const locations = [];
  const overlays = [];
  const gestures = { rotate: true, tilt: true };
  let cameraPosition = { target: { latitude: 39.98, longitude: 116.3 }, zoom: 13, tilt: 0, bearing: 0 };
  let routePoints = [];
  let permissionResult = { authResults: [0, 0] };
  let permissionGate;
  let viewCallback;
  let mapKey;
  let naviKey;
  let overlayBuildGate;
  const pathResult = {
    getAllLength: () => 2400, getAllTime: () => 480,
    getBoundsForPath: () => ({ marker: 'route-bounds' }),
    getCoordList() { const points = new ArrayList(); routePoints.forEach(point => points.add(point)); return points; }
  };
  const navi = {
    getVersion: () => '11.2.0-test',
    stopGPS() { lifecycle.push('stopGPS'); },
    startGPS() { lifecycle.push('startGPS'); return true; },
    setUseInnerVoice(enabled) { lifecycle.push(enabled ? 'voiceOn' : 'voiceOff'); },
    stopSpeak() { lifecycle.push('stopSpeak'); },
    startSpeak() { lifecycle.push('startSpeak'); },
    addAMapNaviListener(listener) { listeners.push(listener); },
    removeAMapNaviListener() { lifecycle.push('removeListener'); },
    stopNavi() { lifecycle.push('stopNavi'); },
    startNavi(type) { lifecycle.push(`startNavi:${type}`); return true; },
    setIsNaviTravelView(value) { calls.push(['travelView', value]); },
    calculateDriveRoute(...args) { calls.push(['drive', ...args]); return true; },
    calculateWalkRideRouteLatLng(...args) { calls.push(['travel', ...args]); return true; },
    selectRouteId(id) { calls.push(['select', id]); return true; },
    getNaviPath: () => pathResult
  };
  const map = {
    setOnMapLoadedListener(callback) { this.loaded = callback; },
    setOnMapLongClickListener() {}, getUiSettings: () => ({
      setZoomControlsEnabled() {},
      setRotateGesturesEnabled(value) { gestures.rotate = value; },
      setTiltGesturesEnabled(value) { gestures.tilt = value; }
    }),
    getCameraPosition: () => cameraPosition,
    moveCamera(update) {
      if (update.camera) cameraPosition = update.camera;
      cameraCalls.push({ kind: 'move', update });
    },
    animateCamera(update, callback, duration) {
      if (update.camera) cameraPosition = update.camera;
      cameraCalls.push({ kind: 'animate', update, callback, duration });
    },
    addMarker(options) {
      positions.push(options.position);
      return { setPosition(position) { positions.push(position); } };
    }
  };
  class LatLng {
    constructor(latitude, longitude) { this.latitude = latitude; this.longitude = longitude; }
  }
  class ArrayList {
    values = [];
    add(value) { this[this.values.length] = value; this.values.push(value); }
    get length() { return this.values.length; }
  }
  class CameraPosition {
    constructor(target, zoom, tilt, bearing) { Object.assign(this, { target, zoom, tilt, bearing }); }
  }
  class MarkerOptions {
    setPosition(position) { this.position = position; return this; }
    setTitle() { return this; }
  }
  class Overlay {
    constructor() { overlays.push(this); }
    async build() { calls.push(['overlayBuild']); if (overlayBuildGate) await overlayBuildGate; }
    removeFromMap() { this.removed = true; } destroy() { this.destroyed = true; }
    setTrafficLine() {} addToMap() { this.added = true; }
  }
  const manager = {
    registerMapViewCreatedCallback(callback) { viewCallback = callback; },
    unregisterMapViewCreatedCallback() { viewCallback = undefined; }
  };
  const privacy = { updatePrivacyShow() {}, updatePrivacyAgree() {} };
  const sdk = {
    '@kit.AbilityKit': { abilityAccessCtrl: { createAtManager: () => ({
      requestPermissionsFromUser: async () => {
        lifecycle.push('requestPermission');
        if (permissionGate) await permissionGate;
        return permissionResult;
      }
    }) } },
    '@kit.LocationKit': { geoLocationManager: { isLocationEnabled: () => true } },
    '@kit.ArkTS': { ArrayList },
    '@ohos.hilog': { default: {
      info(...args) { logs.push(args); }, warn(...args) { logs.push(args); }, error(...args) { logs.push(args); }
    } },
    '@amap/amap_lbs_common': {
      AMapPrivacyAgreeStatus: { DidAgree: 1 }, AMapPrivacyInfoStatus: { DidContain: 1 },
      AMapPrivacyShowStatus: { DidShow: 1 }
    },
    '@amap/amap_lbs_navi': {
      LatLng, CameraPosition, MarkerOptions, NaviLatLng: LatLng, RouteOverlay: Overlay,
      AMapNaviFactory: {
        getAMapNaviInstance(_context, key) { naviKey = key; return navi; },
        destroyAMapNaviInstance() { lifecycle.push('destroyEngine'); }
      },
      MapViewManager: { getInstance: () => manager },
      MapsInitializer: { ...privacy, initialize() {}, setApiKey(key) { mapKey = key; } },
      NaviSetting: { ...privacy }, CameraUpdateFactory: {
        newLatLngZoom: (target, zoom) => ({ target, zoom }),
        newLatLngBounds: (bounds, padding) => ({ bounds, padding }),
        newCameraPosition: camera => ({ camera })
      },
      NaviType: { EMULATOR: 1, GPS: 0 },
      PathPlanningStrategy: { DRIVING_DEFAULT: 0, DRIVING_MULTIPLE_ROUTES_DEFAULT: 10 },
      TravelStrategy: { SINGLE: 1000 }
    }
  };
  const load = loadSources(sdk, timers);
  const { AmapNavigationEngine } = load(path.join(navigationRoot, 'platform/AmapNavigationEngine.ets'));
  const errors = load(path.join(navigationRoot, 'platform/AmapRouteErrors.ts'));
  const destination = { name: '测试终点', point: { latitude: 39.992, longitude: 116.31 } };
  const origin = { point: { latitude: 39.98, longitude: 116.3 }, accuracyMeters: 8, observedAt: Date.now() };
  const engine = new AmapNavigationEngine({ getApplicationContext: () => ({}) },
    { apiKey: fakeKey, destination, source: 'test-memory-config' });
  const listener = {
    onMapViewCreated() {}, onMapReady() {}, onMapContentLoaded() {},
    onLocation(location) { locations.push(location); },
    onStarted() { navigationEvents.push('started'); },
    onProgress() { navigationEvents.push('progress'); },
    onArrived() { navigationEvents.push('arrived'); }, onWarning() {},
    onLocationFailure(message) { locationFailures.push(message); },
    onRoute(route) { routes.push(route); },
    onRouteFailure(message) { routeFailures.push(message); },
    onFailure(message) { fatalFailures.push(message); }
  };
  async function ready() {
    await engine.initialize(listener);
    viewCallback({
      getMapViewName: () => engine.mapViewName,
      onCreate() { lifecycle.push('createMap'); }, onResume() {}, onPause() {},
      onDestroy() { lifecycle.push('destroyMap'); },
      getMapAsync(callback) { callback(map); }
    }, engine.mapViewName);
  }
  return { timers, calls, logs, navi, engine, errors, origin, destination, routes, fatalFailures, routeFailures,
    lifecycle, locationFailures, navigationEvents, cameraCalls, positions, locations, overlays, gestures,
    setRoutePoints(points) { routePoints = points; },
    setCamera(camera) { cameraPosition = { ...cameraPosition, ...camera }; },
    denyPermission() { permissionResult = { authResults: [-1, -1] }; },
    delayPermissionUntil(gate) { permissionGate = gate; },
    ready, delayOverlayUntil(gate) { overlayBuildGate = gate; },
    get sdkListener() { return listeners.at(-1); }, get mapKey() { return mapKey; }, get naviKey() { return naviKey; } };
}

function begin(f, mode = 'drive', waypoints = []) { f.engine.plan(f.origin, f.destination, waypoints, mode); }
const richError = (detail = 'INVALID_USER_SCODE') => ({ errorCode: 24, errorDescription: '导航鉴权失败', errorDetail: detail });
const success = () => ({ errorCode: 0, routeId: [7, 9] });
const flush = () => new Promise(resolve => setImmediate(resolve));
const fix = (latitude = 39.981, longitude = 116.302, bearing = 90, speed = 5, accuracy = 8) => ({
  coord: { latitude, longitude }, accuracy, bearing, speed, locationType: 0
});

test('voice input suppresses navigation speech and restores it only while guidance remains active', async () => {
  const f = fixture(); await f.ready(); begin(f); f.engine.start(false); f.sdkListener.onStartNavi();
  f.engine.setVoiceInputActive(true);
  assert.equal(f.lifecycle.at(-1), 'voiceOff');
  assert.equal(f.lifecycle.at(-2), 'stopSpeak');
  f.engine.setVoiceInputActive(false);
  assert.equal(f.lifecycle.at(-2), 'voiceOn');
  assert.equal(f.lifecycle.at(-1), 'startSpeak');
  f.engine.setVoiceInputActive(true); f.engine.exitGuidance(); f.engine.setVoiceInputActive(false);
  assert.equal(f.lifecycle.at(-1), 'voiceOff', 'input completion cannot resume exited navigation speech');
});

test('navigation started during voice input stays muted until input ends', async () => {
  const f = fixture(); await f.ready(); begin(f); f.engine.setVoiceInputActive(true);
  const before = f.lifecycle.length; f.engine.start(false);
  assert.ok(!f.lifecycle.slice(before).includes('voiceOn'));
  f.engine.setVoiceInputActive(false);
  assert.equal(f.lifecycle.at(-2), 'voiceOn');
  assert.equal(f.lifecycle.at(-1), 'startSpeak');
});

test('guidance immediately leaves the route overview and smoothly tracks real street-level fixes', async () => {
  const f = fixture();
  await f.ready(); begin(f);
  f.sdkListener.onCalculateRouteSuccess(success()); await flush();
  assert.ok(f.cameraCalls.at(-1).update.bounds);
  f.engine.start(false); f.sdkListener.onStartNavi();
  let camera = f.cameraCalls.at(-1);
  assert.equal(camera.kind, 'animate');
  assert.equal(camera.duration, 450);
  assert.equal(camera.callback, undefined, 'Harmony callback is the second argument');
  assert.equal(camera.update.camera.target.latitude, f.origin.point.latitude);
  assert.equal(camera.update.camera.zoom, 17.5);
  assert.equal(camera.update.camera.tilt, 45);
  f.sdkListener.onLocationChange(fix());
  camera = f.cameraCalls.at(-1);
  assert.equal(camera.update.camera.target.longitude, 116.302);
  assert.equal(camera.update.camera.bearing, 90);
  assert.equal(f.locations.length, 1);
  assert.equal(f.positions.at(-1).longitude, 116.302);
});

test('overview keeps showing the route while fixes update, and follow recenters without awaiting another fix', async () => {
  const f = fixture();
  await f.ready(); begin(f); f.sdkListener.onCalculateRouteSuccess(success()); await flush();
  f.engine.start(false); f.sdkListener.onStartNavi();
  f.engine.setOverview(true);
  const count = f.cameraCalls.length;
  f.sdkListener.onLocationChange(fix());
  assert.equal(f.cameraCalls.length, count, 'creating the first marker must not override overview');
  f.engine.setOverview(false);
  assert.equal(f.cameraCalls.length, count + 1);
  assert.equal(f.cameraCalls.at(-1).update.camera.target.latitude, 39.981);
  assert.equal(f.cameraCalls.at(-1).update.camera.zoom, 17.5);
});

test('heading follows the measured turn across north without double smoothing and ignores stationary jitter', async () => {
  const f = fixture();
  await f.ready(); begin(f); f.engine.start(false); f.sdkListener.onStartNavi();
  f.sdkListener.onLocationChange(fix(39.981, 116.302, 350));
  f.sdkListener.onLocationChange(fix(39.982, 116.303, 10));
  assert.equal(f.cameraCalls.at(-1).update.camera.bearing, 10);
  f.sdkListener.onLocationChange(fix(39.982, 116.303, 180, 0));
  assert.equal(f.cameraCalls.at(-1).update.camera.bearing, 10);
  const count = f.cameraCalls.length;
  f.sdkListener.onLocationChange(fix(95, 116.303));
  f.sdkListener.onLocationChange(fix(39.982, 116.303, 90, 5, 150));
  assert.equal(f.cameraCalls.length, count);
});

test('startup uses the first distinct route segment near the origin, not a straight line to the destination', async () => {
  const f = fixture();
  await f.ready();
  const origin = f.origin.point;
  f.setRoutePoints([origin, origin, { latitude: origin.latitude, longitude: origin.longitude - 0.001 }]);
  begin(f); f.sdkListener.onCalculateRouteSuccess(success()); await flush();
  f.engine.start(false); f.sdkListener.onStartNavi();
  const initialBearing = f.cameraCalls.at(-1).update.camera.bearing;
  assert.ok(Math.abs(initialBearing - 270) < 0.01, 'the initial road goes west although the destination is northeast');
  f.sdkListener.onLocationChange(fix(origin.latitude, origin.longitude, 0, 0));
  assert.equal(f.cameraCalls.at(-1).update.camera.bearing, initialBearing, 'first stationary/default bearing cannot reset the map north');
  f.sdkListener.onLocationChange(fix(origin.latitude, origin.longitude, 180, 8));
  assert.equal(f.cameraCalls.at(-1).update.camera.bearing, 180, 'measured moving course replaces the initial route hint');
});

test('a route start far from the current position cannot rotate the camera toward an unrelated segment', async () => {
  const f = fixture();
  await f.ready();
  f.setRoutePoints([{ latitude: 39.9, longitude: 116.3 }, { latitude: 39.9, longitude: 116.299 }]);
  begin(f); f.sdkListener.onCalculateRouteSuccess(success()); await flush();
  f.setCamera({ bearing: 40 });
  f.engine.start(false); f.sdkListener.onStartNavi();
  assert.equal(f.cameraCalls.at(-1).update.camera.bearing, 40);
});

test('missing route shape and an unreliable first fix preserve the existing orientation until motion is reliable', async () => {
  const f = fixture();
  await f.ready(); begin(f);
  f.setCamera({ bearing: 130 });
  f.engine.start(false); f.sdkListener.onStartNavi();
  f.sdkListener.onLocationChange(fix(39.981, 116.302, 0, 0));
  assert.equal(f.cameraCalls.at(-1).update.camera.bearing, 130);
  f.sdkListener.onLocationChange(fix(39.981, 116.302, 75, 3));
  assert.equal(f.cameraCalls.at(-1).update.camera.bearing, 75);
});

test('network, coarse, missing-speed and sub-walking-speed fixes update position without changing heading', async () => {
  const f = fixture();
  await f.ready(); begin(f); f.engine.start(false); f.sdkListener.onStartNavi();
  f.sdkListener.onLocationChange(fix(39.981, 116.302, 90, 8));
  for (const invalidHeadingFix of [
    { ...fix(39.982, 116.303, 180, 8), locationType: 1 },
    { ...fix(39.982, 116.303, 180, 8), locationType: -1 },
    fix(39.982, 116.303, 180, 8, 50),
    fix(39.982, 116.303, 180, Number.NaN),
    fix(39.982, 116.303, 180, 0.6),
    fix(39.982, 116.303, 361, 8),
    fix(39.982, 116.303, -1, 8)
  ]) {
    f.sdkListener.onLocationChange(invalidHeadingFix);
    assert.equal(f.cameraCalls.at(-1).update.camera.bearing, 90);
    assert.equal(f.cameraCalls.at(-1).update.camera.target.latitude, 39.982);
  }
});

test('normal walking can orient the map, while tiny course jitter and a later stop cannot rotate it', async () => {
  const f = fixture();
  await f.ready(); begin(f, 'walk'); f.engine.start(false); f.sdkListener.onStartNavi();
  f.sdkListener.onLocationChange(fix(39.981, 116.302, 90, 2.5));
  assert.equal(f.cameraCalls.at(-1).update.camera.bearing, 90);
  f.sdkListener.onLocationChange(fix(39.981, 116.302, 92, 2.5));
  assert.equal(f.cameraCalls.at(-1).update.camera.bearing, 90);
  f.sdkListener.onLocationChange(fix(39.981, 116.302, 95, 2.5));
  assert.equal(f.cameraCalls.at(-1).update.camera.bearing, 95);
  f.sdkListener.onLocationChange(fix(39.981, 116.302, 210, 0));
  assert.equal(f.cameraCalls.at(-1).update.camera.bearing, 95);
});

test('a recent reliable course obtained before starting is kept instead of resetting north or using the route hint', async () => {
  const f = fixture();
  await f.ready();
  f.engine.locate(); await flush();
  f.sdkListener.onLocationChange(fix(39.981, 116.302, 210, 8));
  f.setRoutePoints([f.origin.point, { latitude: f.origin.point.latitude, longitude: f.origin.point.longitude + 0.001 }]);
  begin(f); f.sdkListener.onCalculateRouteSuccess(success()); await flush();
  f.engine.start(false); f.sdkListener.onStartNavi();
  assert.equal(f.cameraCalls.at(-1).update.camera.bearing, 210);
});

test('follow locks rotation and tilt, overview restores north-up browsing, and returning follows the current course', async () => {
  const f = fixture();
  await f.ready(); begin(f); f.sdkListener.onCalculateRouteSuccess(success()); await flush();
  f.engine.start(false); f.sdkListener.onStartNavi();
  assert.deepEqual(f.gestures, { rotate: false, tilt: false });
  f.sdkListener.onLocationChange(fix(39.981, 116.302, 130, 8));
  f.engine.setOverview(true);
  assert.deepEqual(f.gestures, { rotate: true, tilt: true });
  assert.equal(f.cameraCalls.at(-2).update.camera.bearing, 0);
  assert.equal(f.cameraCalls.at(-2).update.camera.tilt, 0);
  assert.ok(f.cameraCalls.at(-1).update.bounds);
  f.engine.setOverview(false);
  assert.deepEqual(f.gestures, { rotate: false, tilt: false });
  assert.equal(f.cameraCalls.at(-1).update.camera.bearing, 130);
  f.engine.exitGuidance();
  assert.deepEqual(f.gestures, { rotate: true, tilt: true });
  assert.equal(f.cameraCalls.at(-2).update.camera.bearing, 0);
});

test('full stop and a rejected navigation start restore browsing gestures', async () => {
  const f = fixture();
  await f.ready(); begin(f); f.engine.start(true); f.sdkListener.onStartNavi();
  f.engine.stopNavigation();
  assert.deepEqual(f.gestures, { rotate: true, tilt: true });
  begin(f); f.navi.startNavi = () => false;
  assert.throws(() => f.engine.start(false), /导航启动失败/);
  assert.deepEqual(f.gestures, { rotate: true, tilt: true });
});

for (const [mode, zoom, tilt] of [['walk', 18, 0], ['ride', 17.7, 35]]) {
  test(`${mode} guidance uses a close camera suitable for its travel mode`, async () => {
    const f = fixture();
    await f.ready(); begin(f, mode); f.engine.start(true); f.sdkListener.onStartNavi();
    f.sdkListener.onLocationChange(fix());
    const camera = f.cameraCalls.at(-1).update.camera;
    assert.equal(camera.zoom, zoom);
    assert.equal(camera.tilt, tilt);
    assert.equal(camera.target.latitude, 39.981);
    assert.equal(f.locations.length, 0, 'emulator positions never update the real-world origin');
  });
}

test('exiting simulation preserves the overlay and selected route, restores real position, and supports another guidance', async () => {
  const f = fixture();
  await f.ready(); begin(f); f.sdkListener.onCalculateRouteSuccess(success()); await flush();
  const overlay = f.overlays[0];
  f.engine.start(true); f.sdkListener.onStartNavi();
  f.sdkListener.onLocationChange(fix(39.991, 116.31));
  const old = f.sdkListener;
  assert.equal(f.engine.exitGuidance().length, 0);
  assert.equal(overlay.removed, undefined);
  assert.equal(overlay.destroyed, undefined);
  assert.equal(f.positions.at(-1).latitude, f.origin.point.latitude);
  assert.ok(f.cameraCalls.at(-1).update.bounds);
  assert.equal(f.lifecycle.includes('destroyMap'), false);
  assert.equal(f.lifecycle.includes('destroyEngine'), false);
  const count = f.cameraCalls.length;
  old.onLocationChange(fix()); old.onNaviInfoUpdate({}); old.onArriveDestination();
  old.onCalculateRouteSuccess(success());
  await flush();
  assert.equal(f.cameraCalls.length, count);
  assert.equal(f.routes.length, 1);
  assert.deepEqual(f.navigationEvents, ['started']);
  f.engine.start(false);
  assert.notEqual(f.sdkListener, old);
  assert.deepEqual(f.calls.at(-1), ['select', 7]);
  f.sdkListener.onStartNavi(); f.sdkListener.onLocationChange(fix());
  assert.equal(f.cameraCalls.at(-1).update.camera.target.latitude, 39.981);
  assert.equal(f.locations.length, 1);
  assert.ok(f.lifecycle.includes('startNavi:0'));
  assert.equal(f.overlays.length, 1, 'restarting guidance must reuse the preview route');
});

test('exit during an automatic reroute keeps the displayed route and discards its unfinished replacement', async () => {
  const f = fixture();
  await f.ready(); begin(f); f.sdkListener.onCalculateRouteSuccess(success()); await flush();
  f.engine.start(false); f.sdkListener.onStartNavi();
  let finishOverlay;
  f.delayOverlayUntil(new Promise(resolve => { finishOverlay = resolve; }));
  f.sdkListener.onCalculateRouteSuccess({ errorCode: 0, routeId: [11] });
  f.engine.exitGuidance(); finishOverlay(); await flush();
  assert.equal(f.routes.length, 1);
  assert.equal(f.overlays[0].destroyed, undefined);
  assert.equal(f.overlays[1].destroyed, true);
  f.engine.start(true);
  assert.deepEqual(f.calls.at(-1), ['select', 7]);
});

test('a rejected real navigation restart reports failure and cannot accept a late started callback', async () => {
  const f = fixture();
  await f.ready(); begin(f); f.sdkListener.onCalculateRouteSuccess(success()); await flush();
  f.engine.start(true); f.sdkListener.onStartNavi();
  f.engine.exitGuidance();
  f.navi.startNavi = type => {
    assert.equal(type, 0, 'real mode owns automatic GPS startup in SDK 11.2');
    return false;
  };
  assert.throws(() => f.engine.start(false), /导航启动失败/);
  f.sdkListener.onStartNavi(); f.sdkListener.onNaviInfoUpdate({});
  assert.deepEqual(f.navigationEvents, ['started']);
  assert.equal(f.overlays[0].destroyed, undefined);
});

test('exit ignores synchronous SDK stop callbacks and reports cleanup errors without clearing the preview', async () => {
  const f = fixture();
  await f.ready(); begin(f); f.sdkListener.onCalculateRouteSuccess(success()); await flush();
  f.engine.start(false); f.sdkListener.onStartNavi();
  const old = f.sdkListener;
  f.navi.stopNavi = () => {
    old.onLocationChange(fix()); old.onArriveDestination(); old.onCalculateRouteFailure(24);
    throw new Error('fake stop failure');
  };
  const errors = f.engine.exitGuidance();
  assert.equal(errors.length, 1);
  assert.match(errors[0], /停止导航/);
  assert.deepEqual(f.navigationEvents, ['started']);
  assert.equal(f.routeFailures.length, 0);
  assert.equal(f.lifecycle.at(-1), 'voiceOff');
  assert.equal(f.overlays[0].destroyed, undefined);
});

test('map and navigation SDK initialize with the same configured Harmony key', async () => {
  const f = fixture();
  await f.ready();
  assert.equal(f.mapKey, fakeKey);
  assert.equal(f.naviKey, fakeKey);
  assert.equal(f.fatalFailures.length, 0);
  assert.equal(f.lifecycle.includes('requestPermission'), false, 'map display does not require location permission');
});

test('location permission denial leaves the base map alive and reports a location-specific failure', async () => {
  const f = fixture();
  f.denyPermission();
  await f.ready();
  f.engine.locate();
  await flush();
  assert.equal(f.locationFailures.length, 1);
  assert.match(f.locationFailures[0], /精确定位权限/);
  assert.equal(f.fatalFailures.length, 0);
  assert.equal(f.lifecycle.includes('destroyMap'), false);
  assert.equal(f.lifecycle.includes('startGPS'), false);
});

test('stop during the system permission dialog prevents GPS from starting after the response', async () => {
  const f = fixture();
  await f.ready();
  let finishPermission;
  f.delayPermissionUntil(new Promise(resolve => { finishPermission = resolve; }));
  f.engine.locate();
  f.engine.stopNavigation();
  finishPermission();
  await flush();
  assert.equal(f.lifecycle.includes('startGPS'), false);
  assert.equal(f.lifecycle.includes('destroyMap'), false);
  assert.equal(f.locationFailures.length, 0);
});

test('stopped navigation retains the map and isolates old route and progress listeners on reuse', async () => {
  const f = fixture();
  await f.ready();
  begin(f);
  const old = f.sdkListener;
  f.engine.start(false); old.onStartNavi();
  assert.deepEqual(f.navigationEvents, ['started']);
  assert.equal(f.engine.stopNavigation().length, 0);
  assert.equal(f.lifecycle.includes('destroyMap'), false);
  assert.equal(f.lifecycle.includes('destroyEngine'), false);
  assert.ok(f.lifecycle.includes('stopNavi'));
  assert.equal(f.lifecycle.at(-1), 'voiceOff');
  begin(f, 'walk');
  assert.notEqual(f.sdkListener, old);
  old.onCalculateRouteSuccess(success());
  old.onCalculateRouteFailureForResult(richError());
  old.onStartNavi(); old.onNaviInfoUpdate({}); old.onArriveDestination();
  old.onInitNaviFailure();
  await flush();
  assert.equal(f.routes.length, 0);
  assert.equal(f.routeFailures.length, 0);
  assert.equal(f.fatalFailures.length, 0);
  assert.deepEqual(f.navigationEvents, ['started']);
  f.sdkListener.onCalculateRouteSuccess(success());
  await flush();
  assert.equal(f.routes.length, 1);
  f.engine.start(false); f.sdkListener.onStartNavi();
  assert.deepEqual(f.navigationEvents, ['started', 'started']);
  assert.ok(f.lifecycle.includes('voiceOn'));
  f.engine.release();
  assert.equal(f.lifecycle.filter(item => item === 'destroyMap').length, 1);
  assert.equal(f.lifecycle.filter(item => item === 'destroyEngine').length, 1);
});

test('stopping invalidates an unfinished route overlay without destroying the map', async () => {
  const f = fixture();
  await f.ready();
  let finishOverlay;
  f.delayOverlayUntil(new Promise(resolve => { finishOverlay = resolve; }));
  begin(f); f.sdkListener.onCalculateRouteSuccess(success());
  f.engine.stopNavigation();
  finishOverlay();
  await flush();
  assert.equal(f.routes.length, 0);
  assert.equal(f.lifecycle.includes('destroyMap'), false);
});

test('a rejected old route overlay cannot report a fatal error after the map is reused', async () => {
  const f = fixture();
  await f.ready();
  let rejectOldOverlay;
  f.delayOverlayUntil(new Promise((_, reject) => { rejectOldOverlay = reject; }));
  begin(f); f.sdkListener.onCalculateRouteSuccess(success());
  f.engine.stopNavigation();
  f.delayOverlayUntil(undefined);
  begin(f, 'walk'); f.sdkListener.onCalculateRouteSuccess({ errorCode: 0, routeId: [10] });
  await flush();
  assert.equal(f.routes.length, 1);
  rejectOldOverlay(new Error('late native drawing failure'));
  await flush();
  assert.equal(f.fatalFailures.length, 0);
  assert.equal(f.routes[0].id, 10);
  assert.equal(f.lifecycle.includes('destroyMap'), false);
});

test('drive sets travel mode before calculation and uses strategy 10 with ordered coordinates', async () => {
  const f = fixture();
  await f.ready();
  const via = [{ latitude: 39.981, longitude: 116.301 }, { latitude: 39.985, longitude: 116.305 }];
  begin(f, 'drive', via);
  assert.deepEqual(f.calls[0], ['travelView', false]);
  const [kind, from, to, waypoints, strategy] = f.calls[1];
  assert.equal(kind, 'drive');
  assert.equal(strategy, 10);
  assert.equal(from.values[0].latitude, f.origin.point.latitude);
  assert.equal(to.values[0].longitude, f.destination.point.longitude);
  assert.deepEqual(waypoints.values.map(p => [p.latitude, p.longitude]), via.map(p => [p.latitude, p.longitude]));
});

for (const [mode, sdkType] of [['walk', 3], ['ride', 2]]) {
  test(`${mode} sets travel mode before calculation with correct SDK transport and null empty waypoints`, async () => {
    const f = fixture();
    await f.ready();
    begin(f, mode);
    assert.deepEqual(f.calls[0], ['travelView', true]);
    const [kind, , , waypoints, strategy, type] = f.calls[1];
    assert.equal(kind, 'travel');
    assert.equal(waypoints, null);
    assert.equal(strategy, 1000);
    assert.equal(type, sdkType);
  });
}

test('legacy error 24 is decoded as a recoverable route failure without releasing the engine', async () => {
  const f = fixture();
  await f.ready();
  begin(f);
  f.sdkListener.onCalculateRouteFailure(24);
  assert.equal(f.routeFailures.length, 0, 'wait briefly for the richer SDK callback');
  f.timers.advance(100);
  assert.equal(f.routeFailures.length, 1);
  assert.match(f.routeFailures[0], /24/);
  assert.ok(f.routeFailures[0].length > '算路失败，错误码 24'.length);
  assert.equal(f.fatalFailures.length, 0);
});

for (const order of ['legacy-rich', 'rich-legacy']) {
  test(`${order} keeps service diagnostics and reports one failure`, async () => {
    const f = fixture();
    await f.ready();
    begin(f);
    const legacy = () => f.sdkListener.onCalculateRouteFailure(24);
    const rich = () => f.sdkListener.onCalculateRouteFailureForResult(richError());
    if (order === 'legacy-rich') { legacy(); rich(); } else { rich(); legacy(); }
    f.timers.advance(100);
    assert.equal(f.routeFailures.length, 1);
    const diagnostics = JSON.stringify([f.routeFailures, f.logs]);
    assert.match(diagnostics, /INVALID_USER_SCODE/);
    assert.match(diagnostics, /导航鉴权失败/);
    assert.equal(f.fatalFailures.length, 0);
  });
}

test('route callback errors are ignored before any plan is active', async () => {
  const f = fixture();
  await f.ready();
  f.sdkListener.onCalculateRouteFailure(24);
  f.sdkListener.onCalculateRouteFailureForResult(richError());
  f.timers.advance(100);
  assert.equal(f.routeFailures.length, 0);
  assert.equal(f.fatalFailures.length, 0);
});

test('release cancels deferred legacy failures and ignores old SDK callbacks', async () => {
  const f = fixture();
  await f.ready();
  begin(f);
  const oldListener = f.sdkListener;
  oldListener.onCalculateRouteFailure(24);
  f.engine.release();
  oldListener.onCalculateRouteFailureForResult(richError());
  f.timers.advance(100);
  assert.equal(f.routeFailures.length, 0);
  assert.equal(f.fatalFailures.length, 0);
});

test('new route request cancels the preceding deferred legacy failure', async () => {
  const f = fixture();
  await f.ready();
  begin(f);
  f.sdkListener.onCalculateRouteFailure(24);
  f.engine.clearRoute();
  begin(f, 'walk');
  f.timers.advance(100);
  assert.equal(f.routeFailures.length, 0);
  f.sdkListener.onCalculateRouteSuccess(success());
  await flush();
  assert.equal(f.routes.length, 1);
});

test('success does not revive a route after its failure has been delivered', async () => {
  const f = fixture();
  await f.ready();
  begin(f);
  f.sdkListener.onCalculateRouteFailureForResult(richError());
  f.timers.advance(100);
  f.sdkListener.onCalculateRouteSuccess(success());
  await flush();
  assert.equal(f.routeFailures.length, 1);
  assert.equal(f.routes.length, 0);
});

test('retry after a reported failure can accept a new successful route', async () => {
  const f = fixture();
  await f.ready();
  begin(f);
  f.sdkListener.onCalculateRouteFailureForResult(richError());
  f.timers.advance(100);
  begin(f, 'ride');
  f.sdkListener.onCalculateRouteSuccess(success());
  await flush();
  assert.equal(f.routeFailures.length, 1);
  assert.equal(f.routes.length, 1);
  assert.equal(f.routes[0].id, 7, 'first route must be explicitly selected for display and guidance');
  assert.ok(f.calls.some(call => call[0] === 'select' && call[1] === 7));
});

test('asynchronous overlay completion cannot revive a route rejected while drawing', async () => {
  const f = fixture();
  await f.ready();
  let finishOverlay;
  f.delayOverlayUntil(new Promise(resolve => { finishOverlay = resolve; }));
  begin(f);
  f.sdkListener.onCalculateRouteSuccess(success());
  f.sdkListener.onCalculateRouteFailureForResult(richError());
  f.timers.advance(100);
  finishOverlay();
  await flush();
  assert.equal(f.routeFailures.length, 1);
  assert.equal(f.routes.length, 0);
});

test('superseding a plan prevents its unfinished overlay from publishing an old route', async () => {
  const f = fixture();
  await f.ready();
  let finishOverlay;
  f.delayOverlayUntil(new Promise(resolve => { finishOverlay = resolve; }));
  begin(f);
  f.sdkListener.onCalculateRouteSuccess(success());
  begin(f, 'walk');
  finishOverlay();
  await flush();
  assert.equal(f.routes.length, 0);
  f.sdkListener.onCalculateRouteSuccess({ errorCode: 0, routeId: [10] });
  await flush();
  assert.equal(f.routes.length, 1);
  assert.equal(f.routes[0].id, 10);
});

test('a synchronous rich SDK failure is retained when calculation also returns false', async () => {
  const f = fixture();
  await f.ready();
  f.navi.calculateDriveRoute = () => {
    f.sdkListener.onCalculateRouteFailureForResult(richError());
    return false;
  };
  assert.doesNotThrow(() => begin(f));
  assert.equal(f.routeFailures.length, 1);
  assert.match(f.routeFailures[0], /24/);
});

test('route failure diagnostics redact the configured key from UI and logs', async () => {
  const f = fixture();
  await f.ready();
  begin(f);
  f.sdkListener.onCalculateRouteFailureForResult(richError(`INVALID_USER_KEY; key=${fakeKey}`));
  f.timers.advance(100);
  const output = JSON.stringify([f.routeFailures, f.logs]);
  assert.ok(!output.includes(fakeKey));
  assert.match(output, /INVALID_USER_KEY/);
});

test('error helpers retain unknown codes and never expose a complete configured key', () => {
  const f = fixture();
  const unknown = f.errors.amapRouteErrorMessage(98765);
  assert.match(unknown, /98765/);
  for (const key of [fakeKey, 'tiny', '']) {
    const masked = f.errors.maskedAmapKey(key);
    assert.equal(typeof masked, 'string');
    if (key) assert.notEqual(masked, key);
  }
  const redacted = f.errors.redactAmapDiagnostic(`request ${fakeKey}; echoed=${fakeKey}`, fakeKey);
  assert.ok(!redacted.includes(fakeKey));
  assert.match(redacted, /request/);
});

test('diagnostics redact request credentials beyond the configured key and keep useful error identifiers', () => {
  const f = fixture();
  const raw = 'INVALID_USER_KEY\nhttps://example.invalid/route?key=other-key&token=other-token\t' +
    ' api_key="quoted-secret"; Authorization: Bearer hidden-bearer; ' +
    'scode=hidden-signature signature=other-signature 0123456789abcdef0123456789abcdef';
  const redacted = f.errors.redactAmapDiagnostic(raw, fakeKey);
  for (const secret of ['other-key', 'other-token', 'quoted-secret', 'hidden-bearer',
    'hidden-signature', 'other-signature', '0123456789abcdef0123456789abcdef']) {
    assert.ok(!redacted.includes(secret), `must redact ${secret}`);
  }
  assert.match(redacted, /INVALID_USER_KEY/);
  assert.ok(!/[\r\n\t]/.test(redacted));
  assert.ok(f.errors.redactAmapDiagnostic('x'.repeat(2000), fakeKey).length <= 1600);
});
