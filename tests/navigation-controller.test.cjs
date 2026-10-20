const assert = require('node:assert/strict');
const test = require('node:test');
const { NavigationController } = require('../artifacts/navigation-tests/NavigationController.js');
const { NavigationPhase: Phase, MapContentStatus: MapContent, NavigationDestination, GeoPoint } = require('../artifacts/navigation-tests/NavigationModels.js');

class FakeClock {
  time = 100000;
  nextId = 0;
  tasks = new Map();
  now() { return this.time; }
  schedule(delay, action) {
    const id = ++this.nextId;
    this.tasks.set(id, { at: this.time + delay, action });
    return id;
  }
  cancel(id) { this.tasks.delete(id); }
  advance(ms) {
    const target = this.time + ms;
    while (true) {
      const next = [...this.tasks.entries()].filter(([, task]) => task.at <= target)
        .sort((a, b) => a[1].at - b[1].at)[0];
      if (!next) break;
      this.time = next[1].at;
      this.tasks.delete(next[0]);
      next[1].action();
    }
    this.time = target;
  }
}

class FakeEngine {
  listeners = [];
  calls = [];
  releases = 0;
  stops = 0;
  guidanceExits = 0;
  guidanceExitErrors = [];
  stopErrors = [];
  releaseErrors = [];
  clearedRoutes = 0;
  plannedWaypoints = [];
  async initialize(listener) {
    this.listeners.push(listener);
    if (this.initializeError) throw this.initializeError;
    if (this.initializeGate) await this.initializeGate;
  }
  get listener() { return this.listeners.at(-1); }
  locate() { this.calls.push(['locate']); }
  clearRoute() { this.clearedRoutes++; }
  plan(origin, destination, waypoints, travelMode) {
    this.calls.push(['plan', origin, destination]);
    this.plannedWaypoints = waypoints;
    this.plannedTravelMode = travelMode;
  }
  start(simulation) { this.calls.push(['start', simulation]); }
  release() { this.releases++; return this.releaseErrors; }
  stopNavigation() { this.stops++; return this.stopErrors; }
  exitGuidance() { this.guidanceExits++; return this.guidanceExitErrors; }
}

const destination = new NavigationDestination('北京大学', new GeoPoint(39.992873, 116.310918));
const route = { id: 12, distanceMeters: 4200, durationSeconds: 600 };
const progress = { remainingMeters: 4000, remainingSeconds: 570, nextTurnMeters: 200,
  currentRoad: '当前路', nextRoad: '下一路', receivedAt: 100000 };
function fixture(onUpdate = () => {}) {
  const engine = new FakeEngine();
  const clock = new FakeClock();
  const updates = [];
  const controller = new NavigationController(engine, clock, state => { updates.push(state); onUpdate(state); });
  return { engine, clock, controller, updates };
}
function location(f, overrides = {}) {
  return { point: new GeoPoint(39.98, 116.30), accuracyMeters: 10, observedAt: f.clock.now(), ...overrides };
}
async function ready(f) {
  await f.controller.open();
  f.engine.listener.onMapViewCreated();
  f.engine.listener.onMapReady();
  f.engine.listener.onMapContentLoaded();
}
async function planned(f) {
  await ready(f);
  f.controller.locate();
  f.engine.listener.onLocation(location(f));
  f.controller.plan(destination);
  f.engine.listener.onRoute(route);
}

test('map → real location → fixed route → start callback → progress → stop', async () => {
  const f = fixture();
  await f.controller.open();
  assert.equal(f.controller.snapshot().phase, Phase.LoadingMap);
  assert.equal(f.controller.snapshot().mapVisible, true);
  f.controller.locate();
  assert.equal(f.engine.calls.length, 0, 'location waits for map readiness');
  f.engine.listener.onMapReady();
  f.controller.locate();
  const origin = location(f);
  f.engine.listener.onLocation(origin);
  f.controller.plan(destination);
  assert.deepEqual(f.engine.calls.at(-1), ['plan', origin, destination]);
  f.engine.listener.onRoute(route);
  assert.equal(f.controller.snapshot().phase, Phase.RouteReady);
  assert.equal(f.engine.calls.filter(call => call[0] === 'start').length, 0);
  f.controller.start();
  assert.equal(f.controller.snapshot().phase, Phase.Starting);
  f.engine.listener.onProgress(progress);
  assert.equal(f.controller.snapshot().progressCount, 0);
  f.engine.listener.onStarted();
  f.engine.listener.onProgress(progress);
  assert.equal(f.controller.snapshot().phase, Phase.Navigating);
  assert.equal(f.controller.snapshot().progressCount, 1);
  assert.equal(f.controller.snapshot().simulation, false);
  f.controller.stop();
  assert.equal(f.engine.releases, 0);
  assert.equal(f.engine.stops, 1);
  assert.equal(f.controller.snapshot().mapVisible, true);
  assert.equal(f.controller.snapshot().location, origin);
  assert.equal(f.controller.snapshot().route, undefined);
  assert.equal(f.clock.tasks.size, 0);
});

test('duplicate commands do not duplicate initialization, GPS, route or start requests', async () => {
  const f = fixture();
  await Promise.all([f.controller.open(), f.controller.open()]);
  assert.equal(f.engine.listeners.length, 1);
  f.engine.listener.onMapReady();
  f.controller.locate(); f.controller.locate();
  f.engine.listener.onLocation(location(f));
  f.controller.plan(destination); f.controller.plan(destination);
  f.engine.listener.onRoute(route);
  f.controller.start(); f.controller.start();
  assert.deepEqual(f.engine.calls.map(call => call[0]), ['locate', 'plan', 'start']);
});

test('AMap readiness during initialization is retained until initialization resolves', async () => {
  const f = fixture();
  assert.equal(f.controller.snapshot().mapContentStatus, MapContent.Unknown);
  let resolve;
  f.engine.initializeGate = new Promise(done => { resolve = done; });
  const pending = f.controller.open();
  f.engine.listener.onMapViewCreated();
  f.engine.listener.onMapReady();
  assert.equal(f.controller.snapshot().phase, Phase.Initializing);
  assert.equal(f.controller.snapshot().mapVisible, false);
  resolve(); await pending;
  assert.equal(f.controller.snapshot().phase, Phase.MapReady);
  assert.equal(f.controller.snapshot().mapVisible, true);
  assert.equal(f.controller.snapshot().mapContentStatus, MapContent.Waiting);
  f.engine.listener.onMapContentLoaded();
  assert.equal(f.controller.snapshot().mapContentStatus, MapContent.Loaded);
  assert.equal(f.clock.tasks.size, 0);
});

test('early content callback does not replace AMap readiness', async () => {
  const f = fixture();
  let resolve;
  f.engine.initializeGate = new Promise(done => { resolve = done; });
  const pending = f.controller.open();
  f.engine.listener.onMapViewCreated();
  f.engine.listener.onMapContentLoaded();
  assert.equal(f.controller.snapshot().mapContentStatus, MapContent.Loaded);
  resolve(); await pending;
  assert.equal(f.controller.snapshot().phase, Phase.LoadingMap);
  f.engine.listener.onMapReady();
  assert.equal(f.controller.snapshot().phase, Phase.MapReady);
  assert.equal(f.controller.snapshot().mapContentStatus, MapContent.Loaded);
  assert.equal(f.clock.tasks.size, 0);
  f.clock.advance(60000);
  assert.equal(f.controller.snapshot().warning, '');
});

test('synchronous map-ready and auto-location keep the GPS deadline instead of map timeout', async () => {
  const f = fixture(state => {
    if (state.phase === Phase.LoadingMap) {
      f.engine.listener.onMapViewCreated();
      f.engine.listener.onMapReady();
    } else if (state.phase === Phase.MapReady) {
      f.controller.locate();
    }
  });
  await f.controller.open();
  assert.equal(f.controller.snapshot().phase, Phase.Locating);
  assert.deepEqual(f.engine.calls.map(call => call[0]), ['locate']);
  f.clock.advance(30000);
  assert.equal(f.controller.snapshot().phase, Phase.Error);
  assert.match(f.controller.snapshot().message, /定位超时/);
  assert.doesNotMatch(f.controller.snapshot().message, /地图.*超时/);
  assert.equal(f.clock.tasks.size, 0);
});

test('synchronous auto-location completion leaves no stale map deadline', async () => {
  const f = fixture(state => {
    if (state.phase === Phase.LoadingMap) {
      f.engine.listener.onMapReady();
      f.engine.listener.onMapContentLoaded();
    } else if (state.phase === Phase.MapReady) {
      f.controller.locate();
    }
  });
  f.engine.locate = () => f.engine.listener.onLocation(location(f));
  await f.controller.open();
  assert.equal(f.controller.snapshot().phase, Phase.LocationReady);
  assert.equal(f.clock.tasks.size, 0);
  f.clock.advance(60000);
  assert.equal(f.controller.snapshot().phase, Phase.LocationReady);
  assert.equal(f.engine.releases, 0);
});

test('missing tile callback records unconfirmed status without changing route, message or SDK warning', async () => {
  const f = fixture();
  await f.controller.open();
  f.engine.listener.onMapViewCreated();
  f.engine.listener.onMapReady();
  f.controller.locate();
  f.engine.listener.onLocation(location(f));
  f.controller.plan(destination);
  f.engine.listener.onRoute(route);
  f.engine.listener.onWarning('GPS 信号较弱');
  assert.equal(f.controller.snapshot().mapContentStatus, MapContent.Waiting);
  const message = f.controller.snapshot().message;
  f.clock.advance(30000);
  assert.equal(f.controller.snapshot().phase, Phase.RouteReady);
  assert.equal(f.controller.snapshot().mapVisible, true);
  assert.equal(f.controller.snapshot().route.id, route.id);
  assert.equal(f.controller.snapshot().message, message);
  assert.equal(f.controller.snapshot().mapContentStatus, MapContent.Unconfirmed);
  assert.equal(f.engine.releases, 0);
  assert.equal(f.controller.snapshot().warning, 'GPS 信号较弱');
  f.engine.listener.onWarning('导航语音不可用');
  assert.equal(f.controller.snapshot().warning, '导航语音不可用');
  f.engine.listener.onMapContentLoaded();
  assert.equal(f.controller.snapshot().warning, '导航语音不可用');
  assert.equal(f.controller.snapshot().mapContentStatus, MapContent.Loaded);
  assert.equal(f.controller.snapshot().message, message);
  assert.equal(f.controller.snapshot().phase, Phase.RouteReady);
  assert.equal(f.clock.tasks.size, 0);
  const updateCount = f.updates.length;
  f.engine.listener.onMapContentLoaded();
  assert.equal(f.updates.length, updateCount, 'duplicate tile callbacks need not republish');
});

test('missing tile callback permits location, route, guidance and progress without a visible warning', async () => {
  const f = fixture();
  await f.controller.open();
  f.engine.listener.onMapReady();
  f.clock.advance(30000);
  assert.equal(f.controller.snapshot().phase, Phase.MapReady);
  assert.equal(f.controller.snapshot().mapContentStatus, MapContent.Unconfirmed);
  assert.equal(f.controller.snapshot().warning, '');
  f.controller.locate();
  f.engine.listener.onLocation(location(f));
  f.controller.plan(destination);
  f.engine.listener.onRoute(route);
  assert.equal(f.controller.snapshot().phase, Phase.RouteReady);
  f.controller.start();
  f.engine.listener.onStarted();
  f.engine.listener.onProgress(progress);
  assert.equal(f.controller.snapshot().phase, Phase.Navigating);
  assert.equal(f.controller.snapshot().progressCount, 1);
  assert.equal(f.controller.snapshot().mapContentStatus, MapContent.Unconfirmed,
    'GPS and navigation success do not prove the tile callback was received');
  assert.equal(f.controller.snapshot().warning, '');
  assert.equal(f.updates.some(state => /尚未收到底图/.test(state.warning + state.message)), false);
  assert.equal(f.engine.releases, 0);
});

test('map timeout distinguishes a missing native view from a missing AMap instance', async () => {
  const f = fixture();
  await f.controller.open();
  f.clock.advance(30000);
  assert.match(f.controller.snapshot().message, /地图容器创建超时/);
  assert.doesNotMatch(f.controller.snapshot().message, /Key|AppID/);
  await f.controller.open();
  f.engine.listener.onMapViewCreated();
  f.clock.advance(30000);
  assert.match(f.controller.snapshot().message, /地图引擎就绪超时/);
  assert.doesNotMatch(f.controller.snapshot().message, /Key|AppID/);
  assert.equal(f.engine.releases, 2);
});

test('removing a native map isolates late callbacks after reopening', async () => {
  const f = fixture();
  await f.controller.open();
  f.engine.listener.onMapReady();
  const old = f.engine.listener;
  f.controller.closeMap();
  assert.equal(f.controller.snapshot().mapContentStatus, MapContent.Unknown);
  assert.equal(f.clock.tasks.size, 0);
  await f.controller.open();
  old.onMapViewCreated(); old.onMapReady(); old.onMapContentLoaded();
  assert.equal(f.controller.snapshot().phase, Phase.LoadingMap);
  assert.equal(f.controller.snapshot().mapContentStatus, MapContent.Unknown);
  f.clock.advance(30000);
  assert.match(f.controller.snapshot().message, /地图容器创建超时/);
  assert.equal(f.controller.snapshot().warning, '');
});

test('an old tile callback cannot confirm tiles for a newly mounted map', async () => {
  const f = fixture();
  await f.controller.open();
  f.engine.listener.onMapReady();
  const old = f.engine.listener;
  f.controller.closeMap();
  await f.controller.open();
  f.engine.listener.onMapReady();
  f.clock.advance(30000);
  old.onMapContentLoaded();
  assert.equal(f.controller.snapshot().mapContentStatus, MapContent.Unconfirmed);
  assert.equal(f.controller.snapshot().warning, '');
  f.engine.listener.onMapContentLoaded();
  assert.equal(f.controller.snapshot().mapContentStatus, MapContent.Loaded);
  assert.equal(f.controller.snapshot().warning, '');
});

test('stopping from loading-map observer leaves no deadline or late SDK calls', async () => {
  const f = fixture(state => {
    if (state.phase === Phase.LoadingMap) f.controller.stop();
  });
  await f.controller.open();
  assert.equal(f.controller.snapshot().phase, Phase.Stopped);
  assert.equal(f.clock.tasks.size, 0);
  f.clock.advance(60000);
  assert.equal(f.controller.snapshot().phase, Phase.Stopped);
});

test('reject inaccurate, malformed, old and future locations; accept a new precise fix', async () => {
  const f = fixture();
  await ready(f);
  f.controller.locate();
  for (const invalid of [
    { accuracyMeters: 101 }, { accuracyMeters: NaN }, { accuracyMeters: -1 },
    { point: new GeoPoint(91, 116) }, { point: new GeoPoint(39, Infinity) },
    { observedAt: f.clock.now() - 30001 }, { observedAt: f.clock.now() + 5001 }
  ]) {
    f.engine.listener.onLocation(location(f, invalid));
    assert.equal(f.controller.snapshot().phase, Phase.Locating);
  }
  f.engine.listener.onLocation(location(f));
  assert.equal(f.controller.snapshot().phase, Phase.LocationReady);
  assert.equal(f.clock.tasks.size, 0);
});

test('expired origin requires a new fix and route before real navigation', async () => {
  const f = fixture();
  await planned(f);
  f.clock.advance(30001);
  f.controller.start();
  assert.equal(f.controller.snapshot().phase, Phase.Locating);
  assert.equal(f.controller.snapshot().route, undefined);
  assert.equal(f.engine.calls.some(call => call[0] === 'start'), false);
  f.engine.listener.onLocation(location(f));
  assert.equal(f.controller.snapshot().phase, Phase.Planning);
  f.engine.listener.onRoute({ ...route, id: 13 });
  assert.equal(f.controller.snapshot().phase, Phase.RouteReady);
  assert.equal(f.engine.calls.some(call => call[0] === 'start'), false, 'new preview still needs explicit start');
});

test('expired plan resumes its destination, ordered waypoints and mode after a fresh fix', async () => {
  const f = fixture();
  await ready(f);
  f.controller.locate(); f.engine.listener.onLocation(location(f));
  f.clock.advance(30001);
  const waypoints = [new GeoPoint(39.99, 116.31)];
  f.controller.plan(destination, waypoints, 'walk');
  assert.equal(f.controller.snapshot().phase, Phase.Locating);
  assert.equal(f.engine.calls.filter(call => call[0] === 'plan').length, 0);
  const fresh = location(f);
  f.engine.listener.onLocation(fresh);
  assert.equal(f.controller.snapshot().phase, Phase.Planning);
  assert.deepEqual(f.engine.calls.at(-1), ['plan', fresh, destination]);
  assert.deepEqual(f.engine.plannedWaypoints, waypoints);
  assert.equal(f.engine.plannedTravelMode, 'walk');
  f.engine.listener.onLocation(location(f));
  assert.equal(f.engine.calls.filter(call => call[0] === 'plan').length, 1);
});

test('a newer draft clears an expired plan while GPS is pending', async () => {
  const f = fixture();
  await planned(f);
  f.clock.advance(30001);
  f.controller.plan(destination);
  f.controller.clearRoute();
  f.engine.listener.onLocation(location(f));
  assert.equal(f.controller.snapshot().phase, Phase.LocationReady);
  assert.equal(f.engine.calls.filter(call => call[0] === 'plan').length, 1);
});

test('a synchronous new plan on location-ready supersedes the queued expired request', async () => {
  let replace = false;
  const newer = new NavigationDestination('新目的地', new GeoPoint(40.0, 116.5));
  const f = fixture(state => {
    if (replace && state.phase === Phase.LocationReady) {
      replace = false;
      f.controller.plan(newer, [], 'ride');
    }
  });
  await planned(f);
  f.clock.advance(30001);
  f.controller.plan(destination);
  replace = true;
  f.engine.listener.onLocation(location(f));
  assert.equal(f.engine.calls.filter(call => call[0] === 'plan').length, 2);
  assert.equal(f.engine.calls.at(-1)[2], newer);
  assert.equal(f.engine.plannedTravelMode, 'ride');
});

test('unknown route modes are rejected before invoking the SDK', async () => {
  const f = fixture();
  await ready(f);
  f.controller.locate(); f.engine.listener.onLocation(location(f));
  f.controller.plan(destination, [], 'flight');
  assert.equal(f.controller.snapshot().phase, Phase.Error);
  assert.equal(f.engine.calls.some(call => call[0] === 'plan'), false);
});

test('simulation is explicit and still requires SDK start and progress callbacks', async () => {
  const f = fixture();
  await planned(f);
  f.controller.start(true);
  assert.deepEqual(f.engine.calls.at(-1), ['start', true]);
  assert.equal(f.controller.snapshot().simulation, true);
  assert.equal(f.controller.snapshot().progressCount, 0);
  f.engine.listener.onStarted();
  f.engine.listener.onProgress(progress);
  assert.equal(f.controller.snapshot().progressCount, 1);
});

test('failed initialization releases partial resources and can be retried', async () => {
  const f = fixture();
  f.engine.initializeError = new Error('需要精确定位权限');
  await f.controller.open();
  assert.equal(f.controller.snapshot().phase, Phase.Error);
  assert.match(f.controller.snapshot().message, /精确定位/);
  assert.equal(f.controller.snapshot().mapVisible, false);
  assert.equal(f.engine.releases, 1);
  delete f.engine.initializeError;
  await ready(f);
  assert.equal(f.controller.snapshot().phase, Phase.MapReady);
});

test('stop while permission/initialization is pending prevents late mount', async () => {
  const f = fixture();
  let resolve;
  f.engine.initializeGate = new Promise(done => { resolve = done; });
  const pending = f.controller.open();
  const old = f.engine.listener;
  f.controller.stop();
  resolve(); await pending;
  old.onMapReady(); old.onFailure('late error');
  assert.equal(f.controller.snapshot().phase, Phase.Stopped);
  assert.equal(f.controller.snapshot().mapVisible, false);
  assert.equal(f.clock.tasks.size, 0);
});

test('callbacks from an earlier session cannot change a restarted session', async () => {
  const f = fixture();
  await planned(f);
  const old = f.engine.listener;
  f.controller.closeMap();
  await ready(f);
  old.onStarted(); old.onProgress(progress); old.onArrived(); old.onRoute(route);
  old.onFailure('old error'); old.onWarning('old warning'); old.onLocation(location(f));
  assert.equal(f.controller.snapshot().phase, Phase.MapReady);
  assert.equal(f.controller.snapshot().progressCount, 0);
  assert.equal(f.controller.snapshot().warning, '');
  assert.equal(f.controller.snapshot().location, undefined);
});

for (const stage of ['initialization', 'map', 'location', 'route', 'start']) {
  test(`${stage} timeout invalidates the action and preserves only a usable map`, async () => {
    const f = fixture();
    let resolve;
    let pending;
    if (stage === 'initialization') {
      f.engine.initializeGate = new Promise(done => { resolve = done; });
      pending = f.controller.open();
    } else {
      await f.controller.open();
      if (stage !== 'map') {
        f.engine.listener.onMapReady();
        f.controller.locate();
        if (stage !== 'location') {
          f.engine.listener.onLocation(location(f));
          f.controller.plan(destination);
          if (stage === 'start') {
            f.engine.listener.onRoute(route);
            f.controller.start();
          }
        }
      }
    }
    f.clock.advance(stage === 'initialization' ? 60000 : stage === 'start' ? 10000 : 30000);
    assert.equal(f.controller.snapshot().phase, Phase.Error);
    assert.match(f.controller.snapshot().message, /超时/);
    assert.equal(f.engine.releases, stage === 'location' ? 0 : 1);
    assert.equal(f.controller.snapshot().mapVisible, stage === 'location');
    assert.equal(f.clock.tasks.size, 0);
    if (resolve) { resolve(); await pending; }
    f.engine.listener.onMapReady(); f.engine.listener.onStarted();
    assert.equal(f.controller.snapshot().phase, Phase.Error);
  });
}

test('SDK failure or rejected route cannot lead to navigation', async () => {
  const f = fixture();
  await planned(f);
  f.engine.listener.onFailure('算路鉴权失败');
  f.controller.start();
  assert.equal(f.controller.snapshot().phase, Phase.Error);
  assert.equal(f.engine.releases, 1);
  await ready(f);
  f.controller.locate(); f.engine.listener.onLocation(location(f));
  f.controller.plan(destination);
  f.engine.listener.onRoute({ ...route, distanceMeters: 0 });
  assert.equal(f.controller.snapshot().phase, Phase.Error);
});

test('route error 24 preserves the reason, map and real location without retrying', async () => {
  const f = fixture();
  await ready(f);
  f.controller.locate();
  const fix = location(f);
  f.engine.listener.onLocation(fix);
  f.controller.plan(destination);
  const clears = f.engine.clearedRoutes;
  const reason = '算路失败（24 / USERKEY_PLAT_NOMATCH）：Key 平台不匹配，请使用鸿蒙平台 Key';
  f.engine.listener.onRouteFailure(reason);
  const state = f.controller.snapshot();
  assert.equal(state.phase, Phase.LocationReady);
  assert.equal(state.message, '路线未生成，请处理提示后重新规划');
  assert.equal(state.warning, reason);
  assert.equal(state.mapVisible, true);
  assert.equal(state.location, fix);
  assert.equal(state.route, undefined);
  assert.equal(state.progress, undefined);
  assert.equal(state.progressCount, 0);
  assert.equal(f.engine.clearedRoutes, clears + 1);
  assert.equal(f.engine.releases, 0);
  assert.equal(f.clock.tasks.size, 0);
  f.clock.advance(60000);
  const nextFix = location(f);
  f.engine.listener.onLocation(nextFix);
  assert.equal(f.controller.snapshot().location, nextFix, 'GPS stays usable after preview failure');
  assert.equal(f.engine.calls.filter(call => call[0] === 'plan').length, 1);
  assert.equal(f.controller.snapshot().phase, Phase.LocationReady);
});

test('manual retry after route failure can preview and start navigation', async () => {
  const f = fixture();
  await ready(f);
  f.controller.locate(); f.engine.listener.onLocation(location(f));
  f.controller.plan(destination);
  f.engine.listener.onRouteFailure('算路失败，错误码 24');
  f.controller.start();
  assert.equal(f.engine.calls.some(call => call[0] === 'start'), false);
  f.controller.plan(destination);
  assert.equal(f.controller.snapshot().phase, Phase.Planning);
  assert.equal(f.controller.snapshot().warning, '');
  f.engine.listener.onRoute(route);
  f.controller.start(); f.engine.listener.onStarted();
  assert.equal(f.controller.snapshot().phase, Phase.Navigating);
  assert.equal(f.engine.calls.filter(call => call[0] === 'plan').length, 2);
  assert.equal(f.engine.releases, 0);
});

test('tile status never overwrites route errors and retry clears only the route error', async () => {
  const f = fixture();
  await f.controller.open(); f.engine.listener.onMapReady();
  f.controller.locate(); f.engine.listener.onLocation(location(f));
  f.engine.listener.onWarning('导航语音不可用');
  f.controller.plan(destination);
  f.engine.listener.onRouteFailure('算路失败，错误码 24');
  const warning = '算路失败，错误码 24；导航语音不可用';
  assert.equal(f.controller.snapshot().warning, warning);
  f.clock.advance(30000);
  assert.equal(f.controller.snapshot().mapContentStatus, MapContent.Unconfirmed);
  assert.equal(f.controller.snapshot().warning, warning);
  f.engine.listener.onMapContentLoaded();
  assert.equal(f.controller.snapshot().mapContentStatus, MapContent.Loaded);
  assert.equal(f.controller.snapshot().warning, warning);
  f.controller.plan(destination);
  assert.equal(f.controller.snapshot().warning, '导航语音不可用');
});

test('duplicate and late route callbacks after a failure cannot restore a preview', async () => {
  const f = fixture();
  await ready(f);
  f.controller.locate(); f.engine.listener.onLocation(location(f));
  f.controller.plan(destination);
  f.engine.listener.onRouteFailure('第一条算路失败');
  const clears = f.engine.clearedRoutes;
  f.engine.listener.onRouteFailure('重复算路失败');
  f.engine.listener.onRoute(route);
  assert.equal(f.controller.snapshot().phase, Phase.LocationReady);
  assert.equal(f.controller.snapshot().warning, '第一条算路失败');
  assert.equal(f.controller.snapshot().route, undefined);
  assert.equal(f.engine.clearedRoutes, clears);
  assert.equal(f.engine.releases, 0);
});

test('synchronous route callbacks during failed-preview cleanup cannot recurse or restore a route', async () => {
  const f = fixture();
  await ready(f);
  f.controller.locate(); f.engine.listener.onLocation(location(f));
  f.controller.plan(destination);
  let clears = 0;
  f.engine.clearRoute = () => {
    clears++;
    f.engine.listener.onRouteFailure('清理期间的重复失败');
    f.engine.listener.onRoute(route);
  };
  f.engine.listener.onRouteFailure('算路失败，错误码 24');
  assert.equal(clears, 1);
  assert.equal(f.controller.snapshot().phase, Phase.LocationReady);
  assert.equal(f.controller.snapshot().route, undefined);
  assert.match(f.controller.snapshot().warning, /错误码 24/);
  assert.equal(f.engine.releases, 0);
});

test('failed-preview cleanup errors release the engine instead of presenting an unsafe retry', async () => {
  const f = fixture();
  await ready(f);
  f.controller.locate(); f.engine.listener.onLocation(location(f));
  f.controller.plan(destination);
  f.engine.clearRoute = () => { throw new Error('无法清除路线图层'); };
  f.engine.listener.onRouteFailure('算路失败，错误码 24');
  assert.equal(f.controller.snapshot().phase, Phase.Error);
  assert.match(f.controller.snapshot().message, /错误码 24.*清理旧路线失败.*无法清除路线图层/);
  assert.equal(f.controller.snapshot().mapVisible, false);
  assert.equal(f.engine.releases, 1);
  f.engine.listener.onRouteFailure('重复失败');
  assert.equal(f.engine.releases, 1);
});

test('a fatal SDK callback during failed-preview cleanup is not overwritten by recovery', async () => {
  const f = fixture();
  await ready(f);
  f.controller.locate(); f.engine.listener.onLocation(location(f));
  f.controller.plan(destination);
  f.engine.clearRoute = () => f.engine.listener.onFailure('地图引擎已失效');
  f.engine.listener.onRouteFailure('算路失败，错误码 24');
  assert.equal(f.controller.snapshot().phase, Phase.Error);
  assert.equal(f.controller.snapshot().message, '地图引擎已失效');
  assert.equal(f.engine.releases, 1);
});

test('route failures outside planning or guidance are ignored', async () => {
  const f = fixture();
  await f.controller.open();
  f.engine.listener.onRouteFailure('地图加载期间的迟到失败');
  assert.equal(f.controller.snapshot().phase, Phase.LoadingMap);
  f.engine.listener.onMapReady(); f.engine.listener.onMapContentLoaded();
  f.controller.locate();
  f.engine.listener.onRouteFailure('定位期间的迟到失败');
  assert.equal(f.controller.snapshot().phase, Phase.Locating);
  f.engine.listener.onLocation(location(f));
  f.controller.plan(destination); f.engine.listener.onRoute(route);
  f.engine.listener.onRouteFailure('已接受路线后的迟到失败');
  assert.equal(f.controller.snapshot().phase, Phase.RouteReady);
  assert.equal(f.controller.snapshot().route, route);
  assert.equal(f.engine.releases, 0);
});

test('an old session route failure cannot interrupt a restarted route request', async () => {
  const f = fixture();
  await planned(f);
  const old = f.engine.listener;
  f.controller.closeMap();
  await ready(f);
  f.controller.locate(); f.engine.listener.onLocation(location(f));
  f.controller.plan(destination);
  old.onRouteFailure('旧会话算路失败');
  assert.equal(f.controller.snapshot().phase, Phase.Planning);
  assert.equal(f.controller.snapshot().warning, '');
  assert.equal(f.engine.releases, 1);
  f.engine.listener.onRoute(route);
  assert.equal(f.controller.snapshot().phase, Phase.RouteReady);
});

for (const started of [false, true]) {
  test(`route failure during ${started ? 'active guidance' : 'guidance startup'} stops and releases once`, async () => {
    const f = fixture();
    await planned(f);
    f.controller.start();
    if (started) f.engine.listener.onStarted();
    f.engine.listener.onRouteFailure('导航重算失败，错误码 24');
    assert.equal(f.controller.snapshot().phase, Phase.Error);
    assert.match(f.controller.snapshot().message, /错误码 24.*导航已停止/);
    assert.equal(f.controller.snapshot().mapVisible, false);
    assert.equal(f.clock.tasks.size, 0);
    assert.equal(f.engine.releases, 1);
    f.engine.listener.onRouteFailure('重复失败');
    f.engine.listener.onRoute(route);
    assert.equal(f.engine.releases, 1);
  });
}

test('guidance reroute preserves navigation and ignores malformed progress', async () => {
  const f = fixture();
  await planned(f);
  f.controller.start(); f.engine.listener.onStarted();
  f.engine.listener.onRoute({ ...route, id: 13 });
  assert.equal(f.controller.snapshot().phase, Phase.Navigating);
  assert.equal(f.controller.snapshot().route.id, 13);
  for (const invalid of [{ remainingMeters: NaN }, { remainingSeconds: -1 }, { nextTurnMeters: Infinity }]) {
    f.engine.listener.onProgress({ ...progress, ...invalid });
  }
  assert.equal(f.controller.snapshot().progressCount, 0);
  f.engine.listener.onProgress(progress);
  assert.equal(f.controller.snapshot().progressCount, 1);
});

test('arrival stops guidance once and retains the map despite repeated end callbacks', async () => {
  const f = fixture();
  await planned(f);
  f.controller.start(true); f.engine.listener.onStarted();
  f.engine.listener.onArrived(); f.engine.listener.onArrived();
  assert.equal(f.engine.releases, 0);
  assert.equal(f.engine.stops, 1);
  assert.equal(f.controller.snapshot().phase, Phase.Arrived);
  assert.equal(f.controller.snapshot().mapVisible, true);
});

test('background stop, disposal and repeated cleanup ignore late events', async () => {
  const f = fixture();
  await planned(f);
  f.controller.start(); f.engine.listener.onStarted();
  f.controller.stop('应用进入后台'); f.controller.stop();
  assert.equal(f.engine.releases, 0);
  assert.equal(f.engine.stops, 1);
  assert.match(f.controller.snapshot().message, /后台/);
  f.controller.dispose();
  assert.equal(f.engine.releases, 1);
  const count = f.updates.length;
  f.controller.dispose(); f.controller.stop(); await f.controller.open();
  f.engine.listener.onProgress(progress); f.engine.listener.onFailure('late');
  f.clock.advance(120000);
  assert.equal(f.controller.snapshot().phase, Phase.Disposed);
  assert.equal(f.updates.length, count);
});

test('cleanup failures are visible rather than reporting complete release', async () => {
  const f = fixture();
  await ready(f);
  f.engine.releaseErrors = ['停止定位失败', '释放地图失败'];
  f.engine.stopErrors = ['停止定位失败'];
  f.controller.stop();
  assert.match(f.controller.snapshot().message, /部分资源清理失败/);
  assert.match(f.controller.snapshot().warning, /停止定位失败.*释放地图失败/);
});

test('editing a planned route removes its overlay and requires replanning', async () => {
  const f = fixture();
  await planned(f);
  const cleared = f.engine.clearedRoutes;
  f.controller.clearRoute();
  assert.equal(f.engine.clearedRoutes, cleared + 1);
  assert.equal(f.controller.snapshot().phase, Phase.LocationReady);
  assert.equal(f.controller.snapshot().route, undefined);
  assert.equal(f.controller.snapshot().mapVisible, true);
  f.controller.start();
  assert.equal(f.engine.calls.some(call => call[0] === 'start'), false);
  f.engine.listener.onRoute(route);
  assert.equal(f.controller.snapshot().route, undefined, 'late route cannot restore a cleared preview');
});

for (const mode of ['drive', 'walk', 'ride']) {
  test(`${mode} accepts ten ordered waypoints and rejects eleven before SDK calculation`, async () => {
    const f = fixture();
    await ready(f);
    f.controller.locate(); f.engine.listener.onLocation(location(f));
    const waypoints = Array.from({ length: 10 }, (_, index) => new GeoPoint(39.98 + index * 0.001, 116.31));
    f.controller.plan(destination, waypoints, mode);
    assert.deepEqual(f.engine.plannedWaypoints, waypoints);
    assert.equal(f.engine.plannedTravelMode, mode);
    f.engine.listener.onRoute(route);
    f.controller.plan(destination, [...waypoints, new GeoPoint(40, 116.4)], mode);
    assert.equal(f.controller.snapshot().phase, Phase.Error);
    assert.equal(f.engine.calls.filter(call => call[0] === 'plan').length, 1);
  });
}

test('arrival can reuse the base map without accepting old progress', async () => {
  const f = fixture();
  await planned(f);
  f.controller.start(true); f.engine.listener.onStarted();
  const old = f.engine.listener;
  old.onArrived();
  await ready(f);
  old.onProgress(progress); old.onArrived();
  assert.equal(f.controller.snapshot().phase, Phase.MapReady);
  assert.equal(f.controller.snapshot().progressCount, 0);
  assert.equal(f.engine.listeners.length, 1);
  assert.equal(f.engine.releases, 0);
});

test('stop and reopen reuse the map but require a fresh route before guidance', async () => {
  const f = fixture();
  await planned(f);
  f.controller.start(); f.engine.listener.onStarted();
  f.controller.stop();
  assert.equal(f.controller.snapshot().phase, Phase.Stopped);
  assert.equal(f.controller.snapshot().mapVisible, true);
  await f.controller.open();
  assert.equal(f.engine.listeners.length, 1, 'do not reinitialize or unmount the map');
  assert.equal(f.controller.snapshot().phase, Phase.MapReady);
  f.controller.start();
  assert.equal(f.controller.snapshot().phase, Phase.MapReady);
  f.controller.locate(); f.engine.listener.onLocation(location(f));
  f.controller.plan(destination); f.engine.listener.onRoute(route);
  assert.equal(f.controller.snapshot().phase, Phase.RouteReady);
  f.controller.dispose();
  assert.equal(f.engine.releases, 1);
  assert.equal(f.controller.snapshot().mapVisible, false);
});

for (const status of [MapContent.Waiting, MapContent.Unconfirmed, MapContent.Loaded]) {
  test(`stop and reopen preserve ${status} tile status without a warning or another map initialization`, async () => {
    const f = fixture();
    await f.controller.open();
    f.engine.listener.onMapReady();
    if (status === MapContent.Unconfirmed) f.clock.advance(30000);
    if (status === MapContent.Loaded) f.engine.listener.onMapContentLoaded();
    assert.equal(f.controller.snapshot().mapContentStatus, status);
    f.controller.stop();
    assert.equal(f.controller.snapshot().mapContentStatus, status);
    assert.equal(f.controller.snapshot().mapVisible, true);
    assert.equal(f.clock.tasks.size, 0);
    await f.controller.open();
    assert.equal(f.engine.listeners.length, 1);
    assert.equal(f.engine.releases, 0);
    assert.equal(f.controller.snapshot().mapContentStatus, status);
    assert.equal(f.controller.snapshot().phase, Phase.MapReady);
    assert.equal(f.controller.snapshot().warning, '');
    f.engine.listener.onMapContentLoaded();
    assert.equal(f.controller.snapshot().mapContentStatus, MapContent.Loaded);
    assert.equal(f.controller.snapshot().warning, '');
    assert.equal(f.controller.snapshot().phase, Phase.MapReady);
  });
}

test('disposing an unconfirmed map resets diagnostic status and ignores late tile callbacks', async () => {
  const f = fixture();
  await f.controller.open();
  f.engine.listener.onMapReady();
  f.clock.advance(30000);
  assert.equal(f.controller.snapshot().mapContentStatus, MapContent.Unconfirmed);
  f.controller.dispose();
  assert.equal(f.controller.snapshot().mapContentStatus, MapContent.Unknown);
  assert.equal(f.controller.snapshot().mapVisible, false);
  assert.equal(f.engine.releases, 1);
  const updateCount = f.updates.length;
  f.engine.listener.onMapContentLoaded();
  f.engine.listener.onMapReady();
  f.clock.advance(60000);
  assert.equal(f.controller.snapshot().mapContentStatus, MapContent.Unknown);
  assert.equal(f.controller.snapshot().phase, Phase.Disposed);
  assert.equal(f.updates.length, updateCount);
  assert.equal(f.clock.tasks.size, 0);
});

test('location denial keeps the map mounted and supports a later explicit retry', async () => {
  const f = fixture();
  await ready(f);
  f.controller.locate();
  f.engine.listener.onLocationFailure('需要精确定位权限');
  assert.equal(f.controller.snapshot().phase, Phase.Error);
  assert.equal(f.controller.snapshot().mapVisible, true);
  assert.equal(f.engine.releases, 0);
  assert.equal(f.clock.tasks.size, 0);
  await f.controller.open();
  f.controller.locate(); f.engine.listener.onLocation(location(f));
  assert.equal(f.controller.snapshot().phase, Phase.LocationReady);
  assert.equal(f.engine.listeners.length, 1);
});

test('leaving real guidance preserves its route and allows an explicit simulation restart', async () => {
  const f = fixture();
  await planned(f);
  const origin = f.controller.snapshot().location;
  f.controller.start(); f.engine.listener.onStarted(); f.engine.listener.onProgress(progress);
  const clears = f.engine.clearedRoutes;
  assert.equal(f.controller.exitGuidance(), true);
  assert.equal(f.controller.snapshot().phase, Phase.RouteReady);
  assert.equal(f.controller.snapshot().route, route);
  assert.equal(f.controller.snapshot().location, origin);
  assert.equal(f.controller.snapshot().mapVisible, true);
  assert.equal(f.controller.snapshot().progress, undefined);
  assert.equal(f.controller.snapshot().progressCount, 0);
  assert.equal(f.controller.snapshot().simulation, false);
  assert.match(f.controller.snapshot().message, /路线预览/);
  assert.equal(f.engine.guidanceExits, 1);
  assert.equal(f.engine.clearedRoutes, clears);
  assert.equal(f.engine.stops, 0);
  assert.equal(f.engine.releases, 0);
  assert.equal(f.engine.calls.filter(call => call[0] === 'start').length, 1,
    'returning to preview never starts guidance automatically');
  assert.equal(f.controller.exitGuidance(), false, 'a second Back can leave the preview page');
  f.controller.start(true);
  assert.deepEqual(f.engine.calls.at(-1), ['start', true]);
  f.engine.listener.onStarted();
  assert.equal(f.controller.snapshot().phase, Phase.Navigating);
  assert.equal(f.controller.snapshot().simulation, true);
});

test('native exit returns to preview without accepting late route, arrival or progress callbacks', async () => {
  const f = fixture();
  await planned(f);
  f.controller.start(true); f.engine.listener.onStarted();
  f.engine.listener.onExit();
  f.engine.listener.onExit();
  f.engine.listener.onProgress(progress);
  f.engine.listener.onArrived();
  f.engine.listener.onStarted();
  f.engine.listener.onRoute({ ...route, id: 45 });
  f.engine.listener.onRouteFailure('已退出导航的迟到重算失败');
  assert.equal(f.controller.snapshot().phase, Phase.RouteReady);
  assert.equal(f.controller.snapshot().route, route);
  assert.equal(f.controller.snapshot().progressCount, 0);
  assert.equal(f.controller.snapshot().simulation, false);
  assert.equal(f.engine.guidanceExits, 1);
  assert.equal(f.engine.releases, 0);
});

test('exit during guidance startup cancels its deadline and keeps the preview', async () => {
  const f = fixture();
  await planned(f);
  f.controller.start();
  assert.equal(f.controller.snapshot().phase, Phase.Starting);
  assert.equal(f.controller.exitGuidance(), true);
  f.engine.listener.onStarted();
  f.clock.advance(20000);
  assert.equal(f.controller.snapshot().phase, Phase.RouteReady);
  assert.equal(f.controller.snapshot().route, route);
  assert.equal(f.clock.tasks.size, 0);
  assert.equal(f.engine.releases, 0);
});

test('synchronous SDK cleanup callbacks cannot re-enter exit or mutate the retained preview', async () => {
  const f = fixture();
  await planned(f);
  f.controller.start(); f.engine.listener.onStarted();
  const origin = f.controller.snapshot().location;
  const updateCount = f.updates.length;
  f.engine.exitGuidance = () => {
    f.engine.guidanceExits++;
    f.engine.listener.onExit();
    f.engine.listener.onProgress(progress);
    f.engine.listener.onArrived();
    f.engine.listener.onStarted();
    f.engine.listener.onRoute({ ...route, id: 46 });
    f.engine.listener.onRouteFailure('停止期间的路线回调');
    f.engine.listener.onFailure('停止期间的失败回调');
    f.engine.listener.onWarning('停止期间的语音警告');
    f.engine.listener.onLocation(location(f, { point: new GeoPoint(39.99, 116.31) }));
    return [];
  };
  f.controller.exitGuidance();
  assert.equal(f.updates.length, updateCount + 1, 'only the completed preview is published');
  assert.equal(f.controller.snapshot().phase, Phase.RouteReady);
  assert.equal(f.controller.snapshot().route, route);
  assert.equal(f.controller.snapshot().location, origin);
  assert.equal(f.controller.snapshot().warning, '');
  assert.equal(f.engine.guidanceExits, 1);
  assert.equal(f.engine.stops, 0);
  assert.equal(f.engine.releases, 0);
});

test('an expired real restart after preview exit replans the same destination, waypoints and mode', async () => {
  const f = fixture();
  await ready(f);
  f.controller.locate(); f.engine.listener.onLocation(location(f));
  const waypoints = [new GeoPoint(39.99, 116.31), new GeoPoint(40.00, 116.32)];
  f.controller.plan(destination, waypoints, 'ride');
  f.engine.listener.onRoute(route);
  f.controller.start(true); f.engine.listener.onStarted();
  f.controller.exitGuidance();
  f.clock.advance(30001);
  f.controller.start(false);
  assert.equal(f.controller.snapshot().phase, Phase.Locating);
  f.engine.listener.onLocation(location(f));
  assert.deepEqual(f.engine.calls.at(-1), ['plan', f.controller.snapshot().location, destination]);
  assert.deepEqual(f.engine.plannedWaypoints, waypoints);
  assert.equal(f.engine.plannedTravelMode, 'ride');
  f.engine.listener.onRoute({ ...route, id: 47 });
  assert.equal(f.controller.snapshot().phase, Phase.RouteReady);
  assert.equal(f.engine.calls.filter(call => call[0] === 'start').length, 1);
  f.controller.start(false);
  assert.deepEqual(f.engine.calls.at(-1), ['start', false]);
});

test('full stop after leaving guidance still clears route and disposal releases the map', async () => {
  const f = fixture();
  await planned(f);
  f.controller.start(); f.engine.listener.onStarted();
  f.controller.exitGuidance();
  f.controller.stop('页面已关闭');
  assert.equal(f.controller.snapshot().phase, Phase.Stopped);
  assert.equal(f.controller.snapshot().route, undefined);
  assert.equal(f.engine.stops, 1);
  f.controller.dispose();
  assert.equal(f.controller.snapshot().phase, Phase.Disposed);
  assert.equal(f.controller.snapshot().mapVisible, false);
  assert.equal(f.engine.releases, 1);
  assert.equal(f.controller.exitGuidance(), false);
});

for (const throwing of [false, true]) {
  test(`a ${throwing ? 'thrown' : 'reported'} guidance-exit failure releases unsafe state`, async () => {
    const f = fixture();
    await planned(f);
    f.controller.start(); f.engine.listener.onStarted();
    if (throwing) f.engine.exitGuidance = () => { throw new Error('停止语音失败'); };
    else f.engine.guidanceExitErrors = ['停止语音失败'];
    assert.equal(f.controller.exitGuidance(), true);
    assert.equal(f.controller.snapshot().phase, Phase.Error);
    assert.match(f.controller.snapshot().message, /退出导航失败.*停止语音失败/);
    assert.equal(f.controller.snapshot().route, undefined);
    assert.equal(f.controller.snapshot().mapVisible, false);
    assert.equal(f.engine.releases, 1);
    assert.equal(f.clock.tasks.size, 0);
    f.engine.listener.onStarted(); f.engine.listener.onProgress(progress);
    assert.equal(f.controller.snapshot().phase, Phase.Error);
  });
}

test('exitGuidance is inert before guidance and does not discard an in-flight route request', async () => {
  const f = fixture();
  assert.equal(f.controller.exitGuidance(), false);
  await ready(f);
  f.controller.locate(); f.engine.listener.onLocation(location(f));
  f.controller.plan(destination);
  assert.equal(f.controller.exitGuidance(), false);
  assert.equal(f.controller.snapshot().phase, Phase.Planning);
  f.engine.listener.onRoute(route);
  assert.equal(f.controller.exitGuidance(), false);
  assert.equal(f.controller.snapshot().route, route);
  assert.equal(f.engine.guidanceExits, 0);
});
