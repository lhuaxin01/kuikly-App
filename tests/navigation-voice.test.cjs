const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require(process.argv[2] || 'C:/Program Files/Huawei/DevEco Studio/tools/hvigor/hvigor/node_modules/typescript');

const flush = () => new Promise(resolve => setImmediate(resolve));
function gate() { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; }

function fixture() {
  const calls = [], events = [], engines = [], capturers = [], timers = new Map(), logs = [];
  let permission = [0], permissionGate, engineGate, captureGate, startGate, stopGate, supported = true, nextTimer = 0, now = 0;
  function advance(milliseconds) {
    const end = now + milliseconds;
    while (true) {
      const next = [...timers].filter(([, timer]) => timer.due <= end).sort((a, b) => a[1].due - b[1].due)[0];
      if (!next) break;
      timers.delete(next[0]); now = next[1].due; next[1].action();
    }
    now = end;
  }
  const sdk = {
    '@kit.AbilityKit': { abilityAccessCtrl: { createAtManager: () => ({
      async requestPermissionsFromUser(_context, permissions) {
        calls.push(['permission', permissions]);
        if (permissionGate) await permissionGate;
        return { authResults: permission };
      }
    }) } },
    '@kit.BasicServicesKit': {},
    '@ohos.hilog': { default: { info(...args) { logs.push(args); } } },
    '@kit.CoreSpeechKit': { speechRecognizer: {
      async createEngine(params) {
        calls.push(['engine', params]);
        if (engineGate) await engineGate;
        const engine = {
          writes: [], writeTimes: [], finishCount: 0, cancelCount: 0, shutdownCount: 0,
          setListener(listener) { this.listener = listener; },
          startListening(params) { this.params = params; calls.push(['startListening']); },
          writeAudio(id, bytes) { this.writes.push([...bytes]); this.writeTimes.push(now); calls.push(['write', id, bytes.length]); },
          finish() { this.finishCount++; }, cancel() { this.cancelCount++; }, shutdown() { this.shutdownCount++; }
        };
        engines.push(engine);
        return engine;
      }
    } },
    '@kit.AudioKit': { audio: {
      AudioSamplingRate: { SAMPLE_RATE_16000: 16000 }, AudioChannel: { CHANNEL_1: 1 },
      AudioSampleFormat: { SAMPLE_FORMAT_S16LE: 1 }, AudioEncodingType: { ENCODING_TYPE_RAW: 0 },
      SourceType: { SOURCE_TYPE_VOICE_RECOGNITION: 1 },
      async createAudioCapturer(params) {
        calls.push(['capturer', params]);
        if (captureGate) await captureGate;
        const capturer = {
          startCount: 0, stopCount: 0, releaseCount: 0,
          on(_type, callback) { this.read = callback; }, off() { this.read = undefined; },
          async start() { this.startCount++; if (startGate) await startGate; },
          async stop() { this.stopCount++; if (stopGate) await stopGate; }, async release() { this.releaseCount++; }
        };
        capturers.push(capturer);
        return capturer;
      }
    } },
    '@kuikly-open/render': { KuiklyRenderBaseModule: class { onDestroy() {} } }
  };
  const cache = new Map();
  function load(file) {
    if (cache.has(file)) return cache.get(file).exports;
    const module = { exports: {} }; cache.set(file, module);
    const output = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
      fileName: file.replace(/\.ets$/, '.ts'),
      compilerOptions: { target: ts.ScriptTarget.ES2021, module: ts.ModuleKind.CommonJS }
    }).outputText;
    vm.runInNewContext(output, {
      module, exports: module.exports,
      require(id) {
        if (sdk[id]) return sdk[id];
        return load(path.resolve(path.dirname(file), id + '.ets'));
      },
      canIUse: () => supported,
      Date: class extends Date { static now() { return now; } },
      setTimeout(action, delay) { const id = ++nextTimer; timers.set(id, { action, delay, due: now + delay }); return id; },
      clearTimeout(id) { timers.delete(id); }
    }, { filename: file });
    return module.exports;
  }
  const root = path.resolve(__dirname, '../ohosApp/entry/src/main/ets');
  const { HarmonyVoiceInput } = load(path.join(root, 'navigation/platform/HarmonyVoiceInput.ets'));
  const { KRNavigationVoiceModule } = load(path.join(root, 'kuikly/modules/KRNavigationVoiceModule.ets'));
  const voice = new HarmonyVoiceInput({}, active => calls.push(['speechActive', active]));
  voice.subscribe(event => events.push(JSON.parse(JSON.stringify(event))));
  async function prepared(id = 'request-1') { voice.start(id); await flush(); return engines.at(-1); }
  async function listening(id = 'request-1') {
    const engine = await prepared(id);
    engine.listener.onStart(engine.params.sessionId, 'started');
    await flush();
    capturers.at(-1).read(new Uint8Array(3840).buffer);
    advance(100);
    engine.writes = []; engine.writeTimes = [];
    return engine;
  }
  return {
    voice, events, calls, engines, capturers, prepared, listening, advance, logs, timers,
    module: new KRNavigationVoiceModule(voice),
    permission(value) { permission = value; }, unsupported() { supported = false; },
    delayPermission(promise) { permissionGate = promise; }, delayEngine(promise) { engineGate = promise; },
    delayCapture(promise) { captureGate = promise; }, delayStart(promise) { startGate = promise; },
    delayStop(promise) { stopGate = promise; },
    timeout(delay) { advance(delay); },
    result(engine, result, isFinal = false, isLast = false) {
      engine.listener.onResult(engine.params.sessionId, { result, isFinal, isLast });
    }
  };
}

test('microphone is requested on explicit start; system ASR uses offline Chinese and PCM16', async () => {
  const f = fixture();
  assert.equal(f.calls.length, 0);
  const engine = await f.listening();
  assert.deepEqual(f.events.map(event => event.state), ['starting', 'listening']);
  assert.equal(f.calls.find(call => call[0] === 'engine')[1].online, 1);
  assert.equal(f.calls.find(call => call[0] === 'engine')[1].extraParams.recognizerMode, 'long');
  assert.equal(engine.params.audioInfo.sampleRate, 16000);
  assert.equal(f.capturers[0].startCount, 1);
  assert.ok(f.calls.some(call => call[0] === 'speechActive' && call[1] === true));
});

test('arbitrary PCM callback sizes are reframed and stop flushes the last audio before finish', async () => {
  const f = fixture(); const engine = await f.listening();
  f.capturers[0].read(new Uint8Array(1000).fill(7).buffer);
  f.capturers[0].read(new Uint8Array(400).fill(8).buffer);
  f.advance(40);
  assert.deepEqual(engine.writes.map(frame => frame.length), [640, 640]);
  f.voice.stop('request-1'); await flush(); f.advance(20); await flush();
  assert.equal(f.events.at(-1).state, 'finishing');
  assert.equal(engine.finishCount, 1);
  assert.equal(engine.cancelCount, 0);
  assert.equal(f.capturers[0].stopCount, 1);
  assert.equal(engine.writes.length, 3);
  assert.equal(engine.writes[2][119], 8);
  assert.equal(engine.writes[2][120], 0);
  f.result(engine, '先去车站再去医院', true, true); await flush();
  assert.deepEqual(f.events.at(-1), {
    requestId: 'request-1', state: 'idle', text: '先去车站再去医院', isFinal: true, error: ''
  });
  assert.equal(f.capturers[0].releaseCount, 1);
  assert.equal(engine.shutdownCount, 1);
  assert.deepEqual(f.calls.at(-1), ['speechActive', false]);
});

test('partial text is replaced and final clauses accumulate for multi-stop sentences', async () => {
  const f = fixture(); const engine = await f.listening();
  f.result(engine, '先去车'); f.result(engine, '先去车站，', true);
  f.result(engine, '再去医'); f.result(engine, '再去医院。', true);
  assert.equal(f.events.at(-1).text, '先去车站，再去医院。');
  assert.equal(f.events.at(-1).isFinal, false);
  assert.equal(f.events.at(-1).state, 'listening');
  f.voice.stop('request-1'); await flush();
  f.result(engine, '最后去公园', true, true);
  assert.equal(f.events.at(-1).text, '先去车站，再去医院。最后去公园');
});

test('partial results stream immediately before stop and remain provisional until completion', async () => {
  const f = fixture(); const engine = await f.listening();
  for (const text of ['去', '去火车', '去火车站']) {
    f.result(engine, text);
    assert.equal(f.events.at(-1).text, text);
    assert.equal(f.events.at(-1).state, 'listening');
    assert.equal(f.events.at(-1).isFinal, false);
    assert.equal(engine.finishCount, 0);
  }
  f.voice.stop('request-1'); await flush();
  f.result(engine, '去火车站北广场', true, true);
  assert.equal(f.events.at(-1).text, '去火车站北广场');
  assert.equal(f.events.at(-1).isFinal, true);
});

test('audio emitted before capturer start resolves reaches ASR without losing the beginning', async () => {
  const f = fixture(); const audioStart = gate(); f.delayStart(audioStart.promise);
  const engine = await f.prepared();
  // Before ASR acknowledges onStart no microphone data may be forwarded.
  f.capturers[0].read(new Uint8Array(640).fill(1).buffer);
  assert.equal(engine.writes.length, 0);
  engine.listener.onStart(engine.params.sessionId, 'started');
  f.capturers[0].read(new Uint8Array(640).fill(2).buffer);
  f.advance(0);
  assert.equal(engine.writes.length, 1);
  assert.equal(engine.writes[0][0], 2);
  f.result(engine, '先去');
  assert.equal(f.events.at(-1).text, '先去');
  f.capturers[0].read(new Uint8Array(3200).buffer);
  audioStart.resolve(); await flush();
  assert.equal(f.events.at(-1).state, 'listening');
  assert.equal(f.events.at(-1).text, '先去');
});

test('empty last markers preserve unconfirmed text without falsely marking it final', async () => {
  const f = fixture(); const engine = await f.listening();
  f.result(engine, '经过车站，', true);
  f.result(engine, '再去人民医院'); f.result(engine, '');
  assert.equal(f.events.at(-1).text, '经过车站，再去人民医院');
  f.voice.stop('request-1'); await flush();
  f.result(engine, '', true, true);
  assert.equal(f.events.at(-1).text, '经过车站，再去人民医院');
  assert.equal(f.events.at(-1).isFinal, false);
  assert.equal(f.events.at(-1).state, 'finishing');
  assert.equal(f.events.at(-1).error, '');
  f.advance(10000); await flush();
  assert.equal(f.events.at(-1).text, '经过车站，再去人民医院');
  assert.match(f.events.at(-1).error, /最终结果未确认/);
  assert.equal(f.events.at(-1).isFinal, false);
});

test('onComplete stops capture while keeping late final recognition available', async () => {
  const f = fixture(); const engine = await f.listening();
  f.result(engine, '去火车');
  const oldRead = f.capturers[0].read;
  engine.listener.onComplete(engine.params.sessionId, 'complete');
  assert.equal(f.events.at(-1).state, 'finishing');
  oldRead(new Uint8Array(640).buffer);
  assert.equal(engine.writes.length, 0);
  await flush(); assert.equal(f.capturers[0].stopCount, 1);
  f.result(engine, '去火车站', true, true); f.timeout(500); await flush();
  assert.equal(f.events.at(-1).text, '去火车站');
  assert.equal(f.events.filter(event => event.state === 'idle').length, 1);
  assert.equal(f.capturers[0].releaseCount, 1);
});

test('stop during permission prompt invalidates the attempt before a late permission grant', async () => {
  const f = fixture(); const permission = gate(); f.delayPermission(permission.promise);
  f.voice.start('request-1'); await flush(); f.voice.stop('request-1');
  permission.resolve(); await flush();
  assert.equal(f.engines.length, 0);
  assert.equal(f.events.at(-1).state, 'idle');
});

test('cancel releases a late created ASR engine without starting microphone capture', async () => {
  const f = fixture(); const creation = gate(); f.delayEngine(creation.promise);
  f.voice.start('request-1'); await flush(); f.voice.cancel();
  creation.resolve(); await flush();
  assert.equal(f.engines[0].shutdownCount, 1);
  assert.equal(f.capturers.length, 0);
});

test('late audio capturer creation is released after cancellation', async () => {
  const f = fixture(); const creation = gate(); f.delayCapture(creation.promise);
  f.voice.start('request-1'); await flush(); f.voice.cancel();
  creation.resolve(); await flush();
  assert.equal(f.engines[0].shutdownCount, 1);
  assert.equal(f.capturers[0].releaseCount, 1);
  assert.equal(f.capturers[0].startCount, 0);
});

test('cancel while microphone start is pending waits for it then stops and releases', async () => {
  const f = fixture(); const audioStart = gate(); f.delayStart(audioStart.promise);
  const engine = await f.prepared(); engine.listener.onStart(engine.params.sessionId, 'started');
  f.voice.cancel(); audioStart.resolve(); await flush();
  assert.equal(f.capturers[0].stopCount, 1);
  assert.equal(f.capturers[0].releaseCount, 1);
  assert.equal(f.events.at(-1).state, 'idle');
  assert.equal(f.events.filter(event => event.state === 'listening').length, 0);
});

test('denied microphone permission has an actionable message and creates no engine', async () => {
  const f = fixture(); f.permission([-1]); await f.prepared();
  assert.match(f.events.at(-1).error, /麦克风权限.*系统设置/);
  assert.equal(f.engines.length, 0);
});

test('devices without system speech support fail clearly without requesting microphone', async () => {
  const f = fixture(); f.unsupported(); await f.prepared();
  assert.match(f.events.at(-1).error, /不支持系统语音识别/);
  assert.equal(f.calls.filter(call => call[0] === 'permission').length, 0);
});

test('duplicate starts are rejected and an old request cannot stop the current microphone', async () => {
  const f = fixture(); const engine = await f.listening();
  assert.throws(() => f.voice.start('request-2'), /先结束/);
  f.voice.stop('old-request'); f.voice.cancel('old-request');
  assert.equal(f.events.at(-1).state, 'listening');
  assert.equal(engine.cancelCount, 0);
});

test('old callbacks cannot modify a subsequent voice request', async () => {
  const f = fixture(); const old = await f.listening(); f.voice.cancel();
  const current = await f.listening('request-2');
  f.result(old, '过期文字', true, true);
  old.listener.onError(old.params.sessionId, 1002200001, 'old error');
  assert.equal(f.events.at(-1).requestId, 'request-2');
  assert.equal(f.events.at(-1).text, '');
  f.result(current, '新的地点', true, true);
  assert.equal(f.events.at(-1).text, '新的地点');
});

test('finish timeout releases both resources and reports an error', async () => {
  const f = fixture(); const engine = await f.listening();
  f.voice.stop('request-1'); await flush(); f.timeout(10000); await flush();
  assert.match(f.events.at(-1).error, /未检测到明显声音/);
  assert.equal(engine.shutdownCount, 1);
  assert.equal(f.capturers[0].releaseCount, 1);
});

test('microphone is finished at 55 seconds rather than exceeding ASR audio limit', async () => {
  const f = fixture(); const engine = await f.listening(); f.timeout(55000); await flush();
  assert.equal(f.events.at(-1).state, 'finishing');
  assert.equal(engine.finishCount, 1);
  assert.equal(f.capturers[0].stopCount, 1);
});

test('completion accommodates a final result delivered immediately after onComplete', async () => {
  const f = fixture(); const engine = await f.listening();
  engine.listener.onComplete(engine.params.sessionId, 'complete');
  f.result(engine, '故宫', true, true); f.timeout(500);
  assert.equal(f.events.at(-1).text, '故宫');
  assert.equal(f.events.filter(event => event.state === 'idle').length, 1);
});

test('empty speech completion ends input with a useful explanation', async () => {
  const f = fixture(); const engine = await f.listening();
  engine.listener.onComplete(engine.params.sessionId, 'complete'); f.timeout(10000); await flush();
  assert.match(f.events.at(-1).error, /未检测到明显声音/);
  assert.equal(f.events.at(-1).isFinal, false);
});

test('SDK errors never leak raw service messages and free recording resources', async () => {
  const f = fixture(); const engine = await f.listening();
  engine.listener.onError(engine.params.sessionId, 1002200006, 'private diagnostic'); await flush();
  assert.match(f.events.at(-1).error, /系统语音服务忙/);
  assert.doesNotMatch(JSON.stringify(f.events), /private diagnostic/);
  assert.equal(f.capturers[0].releaseCount, 1);
});

test('unsubscribe and dispose cancel recording and suppress subsequent callbacks', async () => {
  const f = fixture(); const engine = await f.listening(); f.voice.unsubscribe(); await flush();
  const count = f.events.length; f.result(engine, '迟到文本', true, true);
  assert.equal(f.events.length, count);
  assert.equal(f.capturers[0].releaseCount, 1);
  f.voice.dispose(); assert.throws(() => f.voice.start('next'), /已关闭/);
});

test('Kuikly module keeps subscription separate from acknowledgements and forwards request id', async () => {
  const f = fixture(); const events = [], replies = [];
  f.module.call('subscribe', null, json => events.push(JSON.parse(json)));
  f.module.call('start', '{"requestId":"bridge-1"}', json => replies.push(JSON.parse(json)));
  await flush();
  assert.deepEqual(replies, [{ ok: true }]);
  assert.equal(events[0].requestId, 'bridge-1');
  f.module.call('stop', '{"requestId":"bridge-1"}', null);
  assert.equal(events.at(-1).state, 'idle');
  f.module.call('unknown', '{}', json => replies.push(JSON.parse(json)));
  assert.equal(replies.at(-1).ok, false);
});

test('listening is announced only after actual startup PCM and never waits for speech volume', async () => {
  const f = fixture(); const engine = await f.prepared();
  engine.listener.onStart(engine.params.sessionId, 'started'); await flush();
  assert.equal(f.events.at(-1).state, 'starting');
  f.capturers[0].read(new Uint8Array(3200).buffer); f.advance(100);
  assert.equal(f.events.at(-1).state, 'starting', 'first 100ms remains the documented startup mute window');
  f.capturers[0].read(new Uint8Array(640).buffer); f.advance(0);
  assert.equal(f.events.at(-1).state, 'listening', 'silence must not deadlock the prompt waiting for the user to speak');
});

test('missing microphone callbacks are diagnosed separately from silent PCM', async () => {
  const f = fixture(); const engine = await f.prepared();
  engine.listener.onStart(engine.params.sessionId, 'started'); await flush();
  f.advance(5000); await flush();
  assert.match(f.events.at(-1).error, /未收到麦克风音频/);
  assert.equal(f.events.at(-1).isFinal, false);
  assert.equal(f.capturers[0].releaseCount, 1);
});

test('received audible PCM without transcription is distinguished from microphone silence', async () => {
  const f = fixture(); const engine = await f.listening();
  f.capturers[0].read(new Int16Array(320).fill(5000).buffer); f.advance(20);
  f.voice.stop('request-1'); await flush(); f.advance(10000); await flush();
  assert.match(f.events.at(-1).error, /已收到声音，但未识别出文字/);
  assert.equal(engine.shutdownCount, 1);
});

test('a valid recognized result is never rejected because measured audio was quiet', async () => {
  const f = fixture(); const engine = await f.listening();
  f.result(engine, '中山公园', true, true); await flush();
  assert.equal(f.events.at(-1).isFinal, true);
  assert.equal(f.events.at(-1).error, '');
});

test('a final correction later than 500ms is accepted instead of submitting the partial draft', async () => {
  const f = fixture(); const engine = await f.listening();
  f.result(engine, '中山公园'); engine.listener.onComplete(engine.params.sessionId, 'complete');
  f.advance(1500); await flush();
  assert.equal(f.events.at(-1).state, 'finishing'); assert.equal(f.events.at(-1).isFinal, false);
  f.result(engine, '中山公馆', true, true); await flush();
  assert.equal(f.events.at(-1).text, '中山公馆'); assert.equal(f.events.at(-1).isFinal, true);
  assert.equal(f.events.filter(event => event.state === 'idle').length, 1);
});

test('confirmed clauses followed by an empty final sentinel complete successfully', async () => {
  const f = fixture(); const engine = await f.listening();
  f.result(engine, '先去公园，', true); f.result(engine, '再去医院', true);
  f.result(engine, '', true, true); await flush();
  assert.equal(f.events.at(-1).text, '先去公园，再去医院');
  assert.equal(f.events.at(-1).isFinal, true); assert.equal(f.events.at(-1).error, '');
});

test('isLast alone cannot confirm an interim result', async () => {
  const f = fixture(); const engine = await f.listening();
  f.result(engine, '先去公园', false, true); f.advance(10000); await flush();
  assert.equal(f.events.at(-1).text, '先去公园'); assert.equal(f.events.at(-1).isFinal, false);
  assert.match(f.events.at(-1).error, /最终结果未确认/);
});

test('stop keeps the final capturer buffer and waits for paced audio drain before finish', async () => {
  const f = fixture(); const engine = await f.listening(); const stopping = gate(); f.delayStop(stopping.promise);
  f.capturers[0].read(new Uint8Array(1280).fill(1).buffer);
  f.voice.stop('request-1'); await flush();
  assert.equal(typeof f.capturers[0].read, 'function');
  f.capturers[0].read(new Uint8Array(400).fill(2).buffer);
  assert.equal(engine.finishCount, 0);
  stopping.resolve(); await flush();
  assert.equal(f.capturers[0].read, undefined);
  f.advance(60); await flush();
  assert.equal(engine.writes.length, 3); assert.equal(engine.writes[2][399], 2);
  assert.equal(engine.writes[2][400], 0); assert.equal(engine.finishCount, 1);
  assert.deepEqual(engine.writeTimes.slice(1).map((time, index) => time - engine.writeTimes[index]), [20, 20]);
});

test('cancel during audio drain clears scheduled frames and never calls finish or submits text', async () => {
  const f = fixture(); const engine = await f.listening();
  f.capturers[0].read(new Uint8Array(12800).buffer);
  f.voice.stop('request-1'); await flush(); f.advance(40);
  const sent = engine.writes.length; f.voice.cancel(); await flush();
  f.advance(20000); await flush();
  assert.equal(engine.writes.length, sent); assert.equal(engine.finishCount, 0);
  assert.equal(f.events.at(-1).isFinal, false); assert.equal(f.timers.size, 0);
  assert.equal(f.capturers[0].releaseCount, 1);
});

test('audio queue overload is bounded and stops with a useful message outside readData', async () => {
  const f = fixture(); const engine = await f.listening();
  f.capturers[0].read(new Uint8Array(251 * 640).buffer);
  assert.equal(f.capturers[0].stopCount, 0, 'capturer APIs must not be called inside readData');
  f.advance(20); await flush();
  assert.match(f.events.at(-1).error, /处理延迟过大/);
  assert.equal(engine.writes.length, 0); assert.equal(f.capturers[0].releaseCount, 1);
  assert.equal(f.timers.size, 0);
});

test('preparation stop reports not-ready and cannot be mistaken for successful transcription', async () => {
  const f = fixture(); await f.prepared(); f.voice.stop('request-1'); await flush();
  assert.equal(f.events.at(-1).state, 'idle'); assert.equal(f.events.at(-1).isFinal, false);
  assert.match(f.events.at(-1).error, /尚未就绪/);
});

test('module hotwords are passed through and sanitized for the system ASR dictionary', async () => {
  const f = fixture();
  const words = [' 人民医院 ', '人民医院', 'A', '', null, 123, 'x'.repeat(21), '中山公馆'];
  for (let i = 0; i < 205; i++) words.push(`地点${i}`);
  f.module.call('start', JSON.stringify({ requestId: 'hotwords-request', hotwords: words }), () => {});
  await flush();
  const lexicon = f.calls.find(call => call[0] === 'engine')[1].extraParams.sysGeneralLexicon;
  assert.equal(lexicon.length, 200);
  assert.deepEqual(Array.from(lexicon).slice(0, 2), ['人民医院', '中山公馆']);
  assert.equal(new Set(lexicon).size, 200);
  f.voice.cancel(); await flush();
  f.module.call('start', '{"requestId":"invalid-words","hotwords":{"unexpected":true}}', () => {}); await flush();
  assert.equal(f.calls.filter(call => call[0] === 'engine').at(-1)[1].extraParams.sysGeneralLexicon.length, 0);
});

test('voice diagnostics report PCM metrics and result flags without recording transcript or raw audio', async () => {
  const f = fixture(); const engine = await f.listening();
  f.capturers[0].read(new Int16Array(320).fill(1000).buffer); f.advance(20);
  f.result(engine, '绝不写入日志的转写内容', true, true); await flush();
  const logs = JSON.stringify(f.logs);
  assert.match(logs, /first_frame/); assert.match(logs, /inputBytes/); assert.match(logs, /peak/);
  assert.match(logs, /rms/); assert.match(logs, /isFinal/); assert.match(logs, /isLast/);
  assert.doesNotMatch(logs, /绝不写入日志的转写内容/);
  assert.equal(f.logs.some(entry => entry.some(value => ArrayBuffer.isView(value))), false);
});

test('a listening observer can cancel synchronously without orphan timers or SDK writes', async () => {
  const f = fixture();
  f.voice.subscribe(event => {
    f.events.push(JSON.parse(JSON.stringify(event)));
    if (event.state === 'listening') f.voice.cancel(event.requestId);
  });
  const engine = await f.prepared();
  engine.listener.onStart(engine.params.sessionId, 'started'); await flush();
  f.capturers[0].read(new Uint8Array(3840).buffer); f.advance(0); await flush();
  assert.equal(engine.writes.length, 0);
  assert.equal(f.events.at(-1).state, 'idle');
  assert.equal(f.timers.size, 0);
  assert.equal(f.capturers[0].releaseCount, 1);
});

test('finishing and result observers may cancel synchronously without continuing completion', async () => {
  for (const boundary of ['finishing', 'result']) {
    const f = fixture(); const engine = await f.listening();
    f.voice.subscribe(event => {
      f.events.push(JSON.parse(JSON.stringify(event)));
      if ((boundary === 'finishing' && event.state === 'finishing') ||
          (boundary === 'result' && event.state === 'listening' && event.text.length)) f.voice.cancel(event.requestId);
    });
    if (boundary === 'finishing') f.voice.stop('request-1');
    else f.result(engine, '目的地', true, true);
    await flush(); f.advance(20000); await flush();
    assert.equal(f.events.at(-1).state, 'idle');
    assert.equal(f.events.at(-1).isFinal, false);
    assert.equal(engine.finishCount, 0);
    assert.equal(f.timers.size, 0);
    assert.equal(f.capturers[0].releaseCount, 1);
  }
});

test('onComplete cannot submit an earlier clause while the last clause has not arrived', async () => {
  const f = fixture(); const engine = await f.listening();
  f.result(engine, '先去公园，', true, false);
  engine.listener.onComplete(engine.params.sessionId, 'complete');
  f.advance(1500); await flush();
  assert.equal(f.events.at(-1).state, 'finishing');
  assert.equal(f.events.at(-1).isFinal, false);
  assert.equal(engine.shutdownCount, 0);
  f.result(engine, '再去医院', true, true); await flush();
  assert.equal(f.events.at(-1).text, '先去公园，再去医院');
  assert.equal(f.events.at(-1).isFinal, true);
});

test('completion without a last result preserves confirmed clauses as a draft instead of assuming the rest is empty', async () => {
  const f = fixture(); const engine = await f.listening();
  f.result(engine, '先去公园，', true, false);
  engine.listener.onComplete(engine.params.sessionId, 'complete');
  f.advance(10000); await flush();
  assert.equal(f.events.at(-1).text, '先去公园，');
  assert.equal(f.events.at(-1).isFinal, false);
  assert.match(f.events.at(-1).error, /最终结果未确认/);
});

test('natural completion during audio drain resolves the drain without a later SDK finish', async () => {
  const f = fixture(); const engine = await f.listening();
  f.capturers[0].read(new Uint8Array(12800).buffer);
  f.voice.stop('request-1'); await flush(); f.advance(40);
  const sent = engine.writes.length;
  engine.listener.onComplete(engine.params.sessionId, 'complete'); await flush();
  f.advance(1500); await flush();
  assert.equal(engine.writes.length, sent);
  assert.equal(engine.finishCount, 0);
  f.result(engine, '人民医院', true, true); await flush();
  assert.equal(f.events.at(-1).isFinal, true);
  assert.equal(f.timers.size, 0);
});
