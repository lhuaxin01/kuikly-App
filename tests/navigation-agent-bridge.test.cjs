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
    throw new Error(`Unexpected dependency ${id}`);
  }
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
    assert.equal(result.params.accepted, undefined);
    assert.equal(result.params.rating, undefined);
  }
});
