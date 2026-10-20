const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require(process.argv[2] || 'C:/Program Files/Huawei/DevEco Studio/tools/hvigor/hvigor/node_modules/typescript');

// Exercise the actual Harmony wire adapter; no device, keys or network are used.
const file = path.resolve(__dirname, '../ohosApp/entry/src/main/ets/kuikly/modules/KRNavigationAgentModule.ets');
const moduleFixture = { exports: {} };
const output = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
  fileName: 'KRNavigationAgentModule.ts',
  compilerOptions: { target: ts.ScriptTarget.ES2021, module: ts.ModuleKind.CommonJS }
}).outputText;
vm.runInNewContext(output, {
  module: moduleFixture, exports: moduleFixture.exports,
  require(id) {
    if (id === '@kuikly-open/render') return { KuiklyRenderBaseModule: class {} };
    if (['@ohos.net.http', '@ohos.net.webSocket', '@kit.AbilityKit', '@kit.BasicServicesKit', '@kit.ArkData', '@kit.ArkTS'].includes(id)) return {};
    if (id === '../../navigation/domain/NavigationCapabilities') {
      const source = path.resolve(path.dirname(file), `${id}.ts`);
      const fixture = { exports: {} };
      const compiled = ts.transpileModule(fs.readFileSync(source, 'utf8'), {
        compilerOptions: { target: ts.ScriptTarget.ES2021, module: ts.ModuleKind.CommonJS }
      }).outputText;
      vm.runInNewContext(compiled, { module: fixture, exports: fixture.exports });
      return fixture.exports;
    }
    throw new Error(`Unexpected dependency ${id}`);
  },
  setTimeout: () => 1, clearTimeout() {}
}, { filename: file });

function frame(request) {
  const bridge = new moduleFixture.exports.KRNavigationAgentModule();
  return JSON.parse(bridge.navigationFrame(request, 'fixture-request'));
}

test('new destination keeps the original sentence and never carries an old task', () => {
  const result = frame({ type: 'text', kind: 'new_intent', text: '步行去人民医院，先经过车站',
    travel_mode: 'walk', location: { latitude: 29.56, longitude: 106.55 }, existing_task: { stops: [] } });
  assert.equal(result.method, 'navigation.submit');
  assert.equal(result.params.kind, 'new_intent');
  assert.equal(result.params.text, '步行去人民医院，先经过车站');
  assert.equal(result.params.constraints.travel_mode, 'walk');
  assert.equal(result.params.existing_task, undefined);
  assert.deepEqual(result.params.user_context.location.point, { lat: 29.56, lng: 106.55 });
});

test('guidance modification forwards existing stop roles and coordinates without replacement', () => {
  const existing = { travel_mode: 'drive', stops: [
    { role: 'origin', poi: { name: '当前位置', location: { lat: 29.56, lng: 106.55 } } },
    { role: 'destination', poi: { name: '原终点', location: { lat: 29.6, lng: 106.6 } } }
  ] };
  const result = frame({ type: 'text', kind: 'modify_task', text: '终点改成重庆北站', existing_task: existing });
  assert.equal(result.params.kind, 'modify_task');
  assert.deepEqual(result.params.existing_task, existing);
  assert.equal(result.params.metadata.action, 'modify_task');
});

test('modification without route context fails instead of pretending to be a new destination', () => {
  assert.throws(() => frame({ type: 'text', kind: 'modify_task', text: '顺路加油' }), /当前导航任务/);
});

test('candidate choice preserves its group identity and pending travel mode', () => {
  const result = frame({ type: 'candidate_selection', group_id: 'destination-2', candidate_id: 'hospital-west',
    travel_mode: 'walk', location: { latitude: 29.56, longitude: 106.55 } });
  assert.equal(result.params.kind, 'disambiguation_choice');
  assert.deepEqual(result.params.disambiguation_choices, [{ group_id: 'destination-2', chosen_item_id: 'hospital-west' }]);
  assert.equal(result.params.constraints.travel_mode, 'walk');
  assert.deepEqual(result.params.user_context.location.point, { lat: 29.56, lng: 106.55 });
});

test('invalid or incomplete location is omitted rather than silently converted to a place', () => {
  for (const location of [{ latitude: 91, longitude: 106 }, { latitude: 29 }, { latitude: NaN, longitude: 106 }]) {
    assert.equal(frame({ type: 'text', text: '去车站', location }).params.user_context, undefined);
  }
});

test('every Harmony navigation request explicitly opts out of rating without sending a rating', () => {
  for (const request of [
    { type: 'text', text: '去车站' },
    { type: 'text', kind: 'modify_task', text: '改成步行', existing_task: { stops: [] } },
    { type: 'candidate_selection', group_id: 'g', candidate_id: 'p' },
    { type: 'cancel' }
  ]) {
    const result = frame(request);
    assert.equal(result.method, 'navigation.submit');
    assert.equal(result.params.client_capabilities.supports_feedback, false);
    assert.equal(result.params.client_capabilities.max_waypoints, 10);
    assert.equal(result.params.client_capabilities.supports_waypoints, true);
    assert.equal(result.params.accepted, undefined);
    assert.equal(result.params.rating, undefined);
  }
});

test('initial channel attach and existing-session attach advertise the same ten-waypoint limit', async () => {
  const bridge = new moduleFixture.exports.KRNavigationAgentModule();
  bridge.socketOpening = true;
  bridge.readDeviceId = async () => 'fixture-device';
  const frames = [];
  bridge.sendControl = (method, params) => frames.push({ method, params });
  await bridge.sendChannelAttach('', false);
  assert.equal(frames[0].method, 'channel.attach');
  assert.equal(frames[0].params.capabilities.max_waypoints, 10);
  bridge.ensureSocket = async () => true;
  bridge.sendOrQueue = (raw) => frames.push(JSON.parse(raw));
  bridge.sessionRpc('session.attach', JSON.stringify({ sessionId: 'fixture-session' }), () => {});
  await new Promise(setImmediate);
  assert.equal(frames[1].method, 'channel.attach');
  assert.equal(frames[1].params.capabilities.max_waypoints, 10);
  assert.equal(frames[1].params.sessionId, 'fixture-session');
});

class FakeTimers {
  now = 0;
  sequence = 0;
  tasks = new Map();
  setTimeout(action, delay) {
    const id = ++this.sequence; this.tasks.set(id, { action, due: this.now + delay }); return id;
  }
  clearTimeout(id) { this.tasks.delete(id); }
  advance(milliseconds) {
    const end = this.now + milliseconds;
    while (true) {
      const next = [...this.tasks].filter(([, timer]) => timer.due <= end)
        .sort((a, b) => a[1].due - b[1].due)[0];
      if (!next) break;
      this.tasks.delete(next[0]); this.now = next[1].due; next[1].action();
    }
    this.now = end;
  }
}
const flush = () => new Promise(setImmediate);
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

// Transpile and exercise the real bridge's public commands and gateway events.
// Only native networking/persistence and wall-clock timers are replaced.
function transportFixture() {
  const timers = new FakeTimers(), sockets = [], callbacks = [];
  const fixture = { exports: {} };
  let tokenGate;
  const websocket = {
    createWebSocket() {
      const handlers = new Map();
      const socket = {
        sent: [], closeCount: 0, sendBehavior: undefined, connectGate: undefined,
        on(event, callback) { handlers.set(event, callback); },
        async connect(url, options) {
          this.url = url; this.options = options;
          if (this.connectGate) return this.connectGate;
          return true;
        },
        send(raw) {
          const frame = JSON.parse(raw); this.sent.push(frame);
          return this.sendBehavior ? this.sendBehavior(frame) : Promise.resolve(true);
        },
        close() { this.closeCount++; return Promise.resolve(true); },
        open() { handlers.get('open')?.(); },
        message(frame) { handlers.get('message')?.(null, JSON.stringify(frame)); },
        error() { handlers.get('error')?.(); },
        closed() { handlers.get('close')?.(); }
      };
      sockets.push(socket); return socket;
    }
  };
  vm.runInNewContext(output, {
    module: fixture, exports: fixture.exports,
    require(id) {
      if (id === '@kuikly-open/render') return { KuiklyRenderBaseModule: class { onDestroy() {} } };
      if (id === '@ohos.net.webSocket') return { default: websocket };
      if (id === '../../navigation/domain/NavigationCapabilities') return { MAX_NAVIGATION_WAYPOINTS: 10 };
      if (['@ohos.net.http', '@kit.AbilityKit', '@kit.BasicServicesKit', '@kit.ArkData', '@kit.ArkTS'].includes(id)) return {};
      throw new Error(`Unexpected dependency ${id}`);
    },
    setTimeout: timers.setTimeout.bind(timers), clearTimeout: timers.clearTimeout.bind(timers)
  }, { filename: file });
  const bridge = new fixture.exports.KRNavigationAgentModule();
  bridge.config = { naviClawWebSocketUrl: 'ws://gateway.invalid/ws' };
  bridge.readToken = async () => { if (tokenGate) await tokenGate; return 'fixture-token'; };
  bridge.readDeviceId = async () => 'fixture-device';
  bridge.readProfile = async () => ({ bound_session_id: '' });
  function request(id = 'navigation-1') {
    bridge.call('request', JSON.stringify({ request_id: id, type: 'text', text: '先去车站再去医院，最后去公园' }),
      raw => callbacks.push(JSON.parse(raw)));
  }
  async function attach(socket = sockets.at(-1)) {
    socket.open();
    socket.message({ type: 'event', event: 'connect.challenge', payload: { nonce: 'fixture' } }); await flush();
    const connect = socket.sent.find(frame => frame.method === 'connect');
    socket.message({ type: 'res', id: connect.id, ok: true, payload: {} }); await flush();
    const attach = socket.sent.find(frame => frame.method === 'channel.attach');
    socket.message({ type: 'res', id: attach.id, ok: true, payload: { session: { sessionId: 'session-fixture' } } });
    await flush(); return socket;
  }
  async function ready(id = 'navigation-1') { request(id); await flush(); return attach(); }
  function response(socket, id = 'navigation-1', viaEvent = true) {
    const payload = { type: 'navigation', status: 'ok', request_id: id, response_type: 'navigation_task', task_complete: true };
    socket.message(viaEvent
      ? { type: 'event', event: 'navigation.response', payload: { requestId: id, sessionId: 'session-fixture', response: payload } }
      : { type: 'res', id, ok: true, payload });
  }
  return { bridge, timers, sockets, callbacks, request, attach, ready, response,
    delayToken(promise) { tokenGate = promise; } };
}

test('multi-stop processing can exceed 30 seconds after a slow handshake and resolves exactly once', async () => {
  const f = transportFixture(); f.request(); await flush();
  f.timers.advance(20000); const socket = await f.attach();
  assert.equal(socket.sent.filter(frame => frame.method === 'navigation.submit').length, 1);
  f.timers.advance(180000);
  assert.equal(f.callbacks.length, 0, 'client must not fail a still-running multi-tool request');
  f.response(socket); f.response(socket, 'navigation-1', false);
  assert.equal(f.callbacks.length, 1);
  assert.equal(f.callbacks[0].ok, true);
  assert.equal(f.bridge.pending.size, 0);
  assert.equal(f.timers.tasks.size, 0);
});

test('a delayed final res frame works without a navigation.response notification', async () => {
  const f = transportFixture(); const socket = await f.ready();
  f.timers.advance(120000); f.response(socket, 'navigation-1', false);
  assert.equal(f.callbacks.length, 1); assert.equal(f.callbacks[0].ok, true);
});

test('progress and unrelated events do not consume pending requests or expose reasoning content', async () => {
  const f = transportFixture(); const socket = await f.ready();
  for (const event of ['navigation.step.started', 'navigation.reasoning.delta', 'navigation.tool.started', 'navigation.state.updated']) {
    socket.message({ type: 'event', event, payload: { requestId: 'navigation-1', sessionId: 'session-fixture', delta: 'private reasoning' } });
  }
  socket.message({ type: 'req', id: 'navigation-1', payload: { response_type: 'navigation_task' } });
  f.response(socket, 'unrelated-request');
  assert.equal(f.callbacks.length, 0); assert.equal(f.bridge.pending.size, 1);
  f.response(socket); assert.equal(f.callbacks.length, 1);
  assert.doesNotMatch(JSON.stringify(f.callbacks), /private reasoning/);
});

test('a mismatched nested navigation id cannot consume the pending request addressed by the envelope', async () => {
  const f = transportFixture(); const socket = await f.ready();
  socket.message({ type: 'event', event: 'navigation.response', payload: {
    requestId: 'navigation-1', response: { request_id: 'business-request', response_type: 'chat_reply', reply: '完成' }
  } });
  assert.equal(f.callbacks.length, 0); assert.equal(f.bridge.pending.size, 1);
  f.response(socket); assert.equal(f.callbacks.length, 1); assert.equal(f.callbacks[0].ok, true);
});

test('a definitive navigation response with missing or mismatched request id fails visibly', async () => {
  for (const payload of [{ request_id: 'other', response_type: 'chat_reply' }, { response_type: 'chat_reply' }]) {
    const f = transportFixture(); const socket = await f.ready();
    socket.message({ type: 'res', id: 'navigation-1', ok: true, payload });
    assert.equal(f.callbacks.length, 1); assert.equal(f.callbacks[0].code, 'AGENT_RESPONSE_INVALID');
    assert.equal(f.bridge.pending.size, 0);
  }
});

test('feedback acknowledgement does not require the transport id inside its payload', async () => {
  const f = transportFixture(); const socket = await f.ready(); f.response(socket);
  f.bridge.call('request', JSON.stringify({ type: 'feedback', request_id: 'feedback-1',
    feedback_request_id: 'original-navigation', session_id: 'session-fixture', accepted: true }),
  raw => f.callbacks.push(JSON.parse(raw)));
  await flush();
  socket.message({ type: 'res', id: 'feedback-1', ok: true, payload: { accepted: true, recorded: true } });
  assert.equal(f.callbacks.length, 2); assert.equal(f.callbacks.at(-1).ok, true);
});

test('send rejection fails promptly instead of leaving an undrainable queued request', async () => {
  const f = transportFixture(); const socket = await f.ready(); f.response(socket);
  socket.sendBehavior = () => Promise.reject(new Error('socket write failed'));
  f.request('navigation-2'); await flush();
  assert.equal(f.callbacks.at(-1).code, 'AGENT_SEND_FAILED');
  assert.equal(f.bridge.queuedFrames.length, 0); assert.equal(f.bridge.pending.size, 0);
  assert.equal(socket.closeCount, 1);
  f.request('navigation-3'); await flush(); assert.equal(f.sockets.length, 2);
  const newSocket = await f.attach(); f.response(newSocket, 'navigation-3');
  assert.equal(f.callbacks.at(-1).ok, true);
});

test('a false WebSocket send result is treated as a failed write', async () => {
  const f = transportFixture(); const socket = await f.ready(); f.response(socket);
  socket.sendBehavior = () => Promise.resolve(false); f.request('navigation-2'); await flush();
  assert.equal(f.callbacks.at(-1).code, 'AGENT_SEND_FAILED');
  assert.equal(f.bridge.queuedFrames.length, 0);
});

test('a stalled socket write has its own deadline without imposing a navigation processing timeout', async () => {
  const f = transportFixture(); const socket = await f.ready(); f.response(socket);
  const send = deferred(); socket.sendBehavior = () => send.promise;
  f.request('navigation-2'); await flush(); f.timers.advance(30000);
  assert.equal(f.callbacks.at(-1).code, 'AGENT_SEND_TIMEOUT');
  send.resolve(true); await flush();
  assert.equal(f.bridge.pending.size, 0); assert.equal(f.timers.tasks.size, 0);
});

test('correlated server progress proves delivery while a native send promise is still pending', async () => {
  const f = transportFixture(); const socket = await f.ready(); f.response(socket);
  const send = deferred(); socket.sendBehavior = () => send.promise;
  f.request('navigation-2'); await flush();
  socket.message({ type: 'event', event: 'navigation.step.started', payload: { requestId: 'navigation-2', step: 1 } });
  f.timers.advance(90000);
  assert.equal(f.callbacks.length, 1); assert.equal(f.bridge.pending.size, 1);
  send.reject(new Error('late native completion after confirmed delivery')); await flush();
  assert.equal(socket.closeCount, 0); assert.equal(f.bridge.pending.size, 1);
  f.response(socket, 'navigation-2'); await flush();
  assert.equal(f.callbacks.length, 2); assert.equal(f.callbacks.at(-1).ok, true);
  assert.equal(f.timers.tasks.size, 0);
});

test('a rejected write from a cancelled request cannot fail a newer request on the same connection', async () => {
  const f = transportFixture(); const socket = await f.ready(); f.response(socket);
  const oldSend = deferred(); socket.sendBehavior = () => oldSend.promise;
  f.request('cancelled-request'); await flush();
  f.bridge.call('cancel', '{"request_id":"cancelled-request"}', null);
  socket.sendBehavior = () => Promise.resolve(true); f.request('new-request'); await flush();
  oldSend.reject(new Error('cancelled write settled late')); await flush();
  assert.equal(socket.closeCount, 0); assert.equal(f.callbacks.length, 1);
  assert.equal(f.bridge.pending.has('new-request'), true);
  f.response(socket, 'new-request'); assert.equal(f.callbacks.at(-1).ok, true);
});

test('a settled response isolates a later failed native write from the next request', async () => {
  const f = transportFixture(); const socket = await f.ready(); f.response(socket);
  const oldSend = deferred(); socket.sendBehavior = () => oldSend.promise;
  f.request('completed-request'); await flush(); f.response(socket, 'completed-request');
  socket.sendBehavior = () => Promise.resolve(true); f.request('new-request'); await flush();
  oldSend.reject(new Error('response already confirms delivery')); await flush();
  assert.equal(socket.closeCount, 0); assert.equal(f.bridge.pending.has('new-request'), true);
  f.response(socket, 'new-request'); assert.equal(f.callbacks.length, 3);
});

test('a cancelled request id reused later retains the newer write deadline', async () => {
  const f = transportFixture(); const socket = await f.ready(); f.response(socket);
  const oldSend = deferred(), newSend = deferred(); socket.sendBehavior = () => oldSend.promise;
  f.request('reused-id'); await flush(); f.bridge.call('cancel', '{"request_id":"reused-id"}', null);
  socket.sendBehavior = () => newSend.promise; f.request('reused-id'); await flush();
  oldSend.resolve(true); await flush(); f.timers.advance(30000);
  assert.equal(f.callbacks.at(-1).code, 'AGENT_SEND_TIMEOUT');
  newSend.resolve(true); await flush();
});

test('an unrelated progress event cannot hide a genuinely stalled socket write', async () => {
  const f = transportFixture(); const socket = await f.ready(); f.response(socket);
  socket.sendBehavior = () => new Promise(() => {}); f.request('navigation-2'); await flush();
  socket.message({ type: 'event', event: 'navigation.step.started', payload: { requestId: 'old-navigation', step: 1 } });
  f.timers.advance(30000); assert.equal(f.callbacks.at(-1).code, 'AGENT_SEND_TIMEOUT');
});

test('handshake deadline closes old socket and late messages cannot attach or resolve a later request', async () => {
  const f = transportFixture(); f.request(); await flush(); const oldSocket = f.sockets[0];
  f.timers.advance(30000); await flush();
  assert.equal(f.callbacks[0].code, 'AGENT_CONNECT_TIMEOUT'); assert.equal(oldSocket.closeCount, 1);
  const socket = await f.ready('navigation-2');
  oldSocket.open(); oldSocket.message({ type: 'event', event: 'connect.challenge', payload: {} });
  f.response(oldSocket, 'navigation-2'); oldSocket.error();
  assert.equal(f.callbacks.length, 1);
  f.response(socket, 'navigation-2'); assert.equal(f.callbacks.at(-1).ok, true);
});

test('closing during token lookup cannot create a socket after asynchronous lookup finishes', async () => {
  const f = transportFixture(); const token = deferred(); f.delayToken(token.promise);
  f.request(); await flush(); f.bridge.call('close', null, null); token.resolve(); await flush();
  assert.equal(f.sockets.length, 0); assert.equal(f.callbacks[0].code, 'AGENT_CLOSED');
  assert.equal(f.timers.tasks.size, 0);
});

test('cancel before attachment never sends the cancelled navigation and rejects late results', async () => {
  const f = transportFixture(); f.request(); await flush();
  f.bridge.call('cancel', '{"request_id":"navigation-1"}', null);
  const socket = await f.attach();
  assert.equal(socket.sent.filter(frame => frame.method === 'navigation.submit').length, 0);
  f.response(socket); assert.equal(f.callbacks.length, 0);
});

test('queued frames carry the real request id and are removed by cancellation', async () => {
  const f = transportFixture(); const socket = await f.ready(); f.response(socket);
  // Simulate readiness changing after ensureSocket resolves, before dispatch.
  f.bridge.ensureSocket = async () => true; f.bridge.socketReady = false;
  f.request('queued-navigation'); await flush();
  assert.equal(f.bridge.queuedFrames[0].requestId, 'queued-navigation');
  f.bridge.call('cancel', '{"request_id":"queued-navigation"}', null);
  f.bridge.socketReady = true; f.bridge.flushQueue();
  assert.equal(f.bridge.queuedFrames.length, 0);
  assert.equal(socket.sent.some(frame => frame.id === 'queued-navigation'), false);
});

test('explicit session attach receives its full response budget after handshake', async () => {
  const f = transportFixture(); const replies = [];
  f.bridge.call('session.attach', '{"sessionId":"chosen-session"}', raw => replies.push(JSON.parse(raw)));
  await flush(); f.timers.advance(20000); const socket = await f.attach();
  const attach = socket.sent.filter(frame => frame.method === 'channel.attach').at(-1);
  assert.equal(attach.params.sessionId, 'chosen-session');
  f.timers.advance(20000); assert.equal(replies.length, 0);
  socket.message({ type: 'res', id: attach.id, ok: true, payload: { session: { sessionId: 'chosen-session' } } });
  assert.equal(replies[0].ok, true);
});

test('malformed failures are not treated as successful navigation and duplicate ids cannot replace callbacks', async () => {
  const f = transportFixture(); const socket = await f.ready(); f.request();
  assert.equal(f.callbacks[0].code, 'AGENT_DUPLICATE_REQUEST');
  assert.equal(socket.sent.filter(frame => frame.method === 'navigation.submit').length, 1);
  socket.message({ type: 'res', id: 'navigation-1', ok: false });
  assert.equal(f.callbacks[1].code, 'AGENT_REMOTE_ERROR');
  assert.equal(f.bridge.pending.size, 0);
});
