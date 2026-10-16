import { common } from '@kit.AbilityKit';
import http from '@ohos.net.http';
import webSocket from '@ohos.net.webSocket';
import { BusinessError } from '@kit.BasicServicesKit';
import { preferences } from '@kit.ArkData';
import { util } from '@kit.ArkTS';
import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';

interface AgentRequest { type?: string; kind?: string; existing_task?: Object; request_id?: string; feedback_request_id?: string; trace_id?: string; session_id?: string; text?: string; group_id?: string; candidate_id?: string; rating?: number; accepted?: boolean; comment?: string; travel_mode?: string; location?: Object; }
/** The WebSocket endpoint is the sole navi-claw transport configuration. */
interface GatewayConfig { naviClawWebSocketUrl?: string; naviClawLoginPath?: string; naviClawRegisterPath?: string; naviClawChannelId?: string; }
interface PendingCall { callback: KuiklyRenderCallback; requestId: string; timer?: number; persistSessionId?: string; }
interface ChannelPeer { deviceId: string; }
interface ChannelCapabilities { supports_waypoints: boolean; max_waypoints: number; locale: string; }
interface ChannelMetadata { platform: string; client: string; }
interface ChannelAttachParams { channelId: string; peer: ChannelPeer; conversationId: string; sessionId?: string; capabilities: ChannelCapabilities; metadata: ChannelMetadata; attachMode: string; }
interface DisambiguationChoice { group_id: string; chosen_item_id: string; }
interface NavigationCapabilities { supports_waypoints: boolean; max_waypoints: number; locale: string; supports_feedback: boolean; }
interface NavigationPoint { lat: number; lng: number; }
interface NavigationLocationContext { point: NavigationPoint; }
interface NavigationUserContext { location?: NavigationLocationContext; }
interface TravelConstraints { travel_mode: string; }
interface NavigationMetadata { client: string; entry: string; action: string; }
interface NavigationSubmitParams { type: string; kind: string; request_id: string; session_id?: string; text?: string; existing_task?: Object; user_context?: NavigationUserContext; constraints?: TravelConstraints; client_capabilities?: NavigationCapabilities; disambiguation_choices?: DisambiguationChoice[]; metadata?: NavigationMetadata; }
interface FeedbackParams { session_id: string; request_id: string; trace_id: string; accepted: boolean; comment?: string; }
interface ConnectParams { accessToken?: string; }
interface SessionHistoryParams { sessionId: string; limit: number; }
interface QueuedFrame { frame: string; requestId: string; }
interface AuthProfile { access_token: string; user_id: string; display_name: string; username: string; bound_session_id: string; }
interface AuthRequestBody { username: string; password: string; displayName?: string; }

/** Harmony transport for navi-claw. Networking and token persistence stay outside shared Kotlin. */
export class KRNavigationAgentModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME: string = 'KRNavigationAgentModule';
  private static readonly TOKEN_STORE: string = 'navi_auth';
  private static readonly TOKEN_KEY: string = 'access_token';
  private context?: common.UIAbilityContext;
  private config: GatewayConfig = {};
  private socket?: webSocket.WebSocket;
  private socketOpen: boolean = false;
  private socketReady: boolean = false;
  private socketOpening: boolean = false;
  private connectSent: boolean = false;
  private destroyed: boolean = false;
  private sequence: number = 0;
  private deviceId: string = '';
  // Keep the same conversation namespace as MapTemplate. navi-claw uses the
  // authenticated user id in the route key, so Android and Harmony can resume
  // the same server-side session instead of creating parallel conversations.
  private readonly conversationId: string = 'home-map';
  private queuedFrames: QueuedFrame[] = [];
  private readyWaiters: Array<(ready: boolean) => void> = [];
  private controlCallbacks: Map<string, (ok: boolean, payload: Object) => void> = new Map();
  private socketGeneration: number = 0;
  private handshakeTimer?: number;
  private pending: Map<string, PendingCall> = new Map();

  constructor(context?: common.UIAbilityContext) { super(); this.context = context; this.config = this.loadConfig(); }
  syncMode(): boolean { return false; }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    if (this.destroyed) return null;
    if (method === 'auth') this.authenticateFromPayload(params, callback);
    else if (method === 'auth.restore') this.restoreAuth(callback);
    else if (method === 'login' || method === 'register') this.authenticate(method === 'register', params, callback);
    else if (method === 'request') this.request(params, callback);
    else if (method === 'sessions.list' || method === 'sessions.history' || method === 'session.create') this.sessionHttpRpc(method, params, callback);
    else if (method === 'session.attach') this.sessionRpc(method, params, callback);
    else if (method === 'cancel') { this.cancel(params); callback?.('{"ok":true}'); }
    else if (method === 'close') { this.closeSocket(); callback?.('{"ok":true}'); }
    else if (method === 'logout') { this.closeSocket(); this.clearToken().then(() => callback?.('{"ok":true}')).catch(() => callback?.('{"ok":false,"code":"AUTH_PERSIST_FAILED"}')); }
    return null;
  }

  private authenticateFromPayload(params: KRAny, callback: KuiklyRenderCallback | null): void {
    try {
      const body: Record<string, Object> = typeof params === 'string' ? JSON.parse(params) as Record<string, Object> : {};
      const register: boolean = body['register'] === true;
      this.authenticate(register, JSON.stringify(body), callback);
    } catch (_) { this.fail(callback, 'INVALID_AUTH_REQUEST', '登录参数格式无效'); }
  }

  private restoreAuth(callback: KuiklyRenderCallback | null): void {
    this.readProfile().then((profile: AuthProfile) => {
      const loggedIn: boolean = this.stringField(profile, 'access_token').length > 0;
      callback?.(JSON.stringify({
        ok: true,
        logged_in: loggedIn,
        username: this.stringField(profile, 'username'),
        display_name: this.stringField(profile, 'display_name'),
        user_id: this.stringField(profile, 'user_id'),
        bound_session_id: this.stringField(profile, 'bound_session_id')
      }));
    }).catch(() => callback?.('{"ok":true,"logged_in":false}'));
  }

  onDestroy(): void { this.destroyed = true; this.closeSocket(); this.pending.clear(); super.onDestroy(); }

  private request(params: KRAny, callback: KuiklyRenderCallback | null): void {
    let request: AgentRequest = {};
    try { request = typeof params === 'string' ? JSON.parse(params) as AgentRequest : {}; }
    catch (_) { this.fail(callback, 'INVALID_REQUEST', '导航请求格式无效'); return; }
    const requestId: string = request.request_id ?? `navi-${++this.sequence}`;
    if (!this.gatewayWebSocketUrl()) { this.fail(callback, 'AGENT_ENDPOINT_NOT_CONFIGURED', `navi-claw WebSocket 地址尚未配置（请求 ${requestId}）`); return; }
    if (callback !== null) {
      const pending: PendingCall = { callback, requestId };
      pending.timer = setTimeout(() => this.failPending(requestId, 'AGENT_TIMEOUT', '导航服务响应超时'), 30000);
      this.pending.set(requestId, pending);
    }
    this.ensureSocket().then(async (ready: boolean) => {
      if (!ready || !this.pending.has(requestId)) { if (!ready) this.failPending(requestId, 'AGENT_CONNECT_FAILED', '无法连接 navi-claw 导航服务'); return; }
      this.sendOrQueue(this.navigationFrame(request, requestId));
    }).catch((error: Object) => this.failPending(requestId, this.errorCode(error), '连接 navi-claw 导航服务失败'));
  }

  /** MapTemplate uses GatewayHttpClient.postRpc for session management. Keep
   * this path HTTP so both clients exercise the same authenticated /rpc API. */
  private sessionHttpRpc(method: string, params: KRAny, callback: KuiklyRenderCallback | null): void {
    const base: string = this.gatewayHttpBaseUrl();
    if (!base) { this.fail(callback, 'SESSION_ENDPOINT_NOT_CONFIGURED', 'navi-claw HTTP 地址尚未配置'); return; }
    this.readToken().then((token: string) => {
      if (!token) { this.fail(callback, 'AUTH_REQUIRED', '请先登录后再管理会话'); return; }
      let payload: Object = JSON.parse('{}') as Object;
      try { payload = typeof params === 'string' ? JSON.parse(params) as Object : JSON.parse('{}') as Object; } catch (_) {}
      const wireMethod: string = method === 'session.create' ? 'sessions.create' : method;
      const requestId: string = `sys-${++this.sequence}`;
      const request = http.createHttp();
      request.request(`${base}/rpc`, {
        method: http.RequestMethod.POST,
        header: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
        extraData: JSON.stringify({ type: 'req', id: requestId, method: wireMethod, params: payload }),
        expectDataType: http.HttpDataType.STRING
      }).then((response: http.HttpResponse) => {
        request.destroy();
        const raw: string = typeof response.result === 'string' ? response.result : JSON.stringify(response.result);
      if (response.responseCode < 200 || response.responseCode >= 300) {
        if (response.responseCode === 401 || response.responseCode === 403) this.clearToken().catch(() => {});
        this.fail(callback, response.responseCode === 401 || response.responseCode === 403 ? 'AUTH_REQUIRED' : `SESSION_HTTP_${response.responseCode}`, this.parseHttpError(raw, response.responseCode)); return;
      }
        try {
          const envelope: Object = JSON.parse(raw) as Object;
          if (this.objectField(envelope, 'error')) {
            const errorObject: Object = this.objectField(envelope, 'error') as Object;
            const errorCode: string = this.stringField(errorObject, 'code') || 'AGENT_REMOTE_ERROR';
            if (errorCode === 'UNAUTHORIZED' || errorCode === 'FORBIDDEN') this.clearToken().catch(() => {});
            this.fail(callback, errorCode === 'UNAUTHORIZED' || errorCode === 'FORBIDDEN' ? 'AUTH_REQUIRED' : errorCode, this.stringField(errorObject, 'message') || '会话请求失败'); return;
          }
          if ((envelope as Record<string, Object>)['ok'] !== true) { this.fail(callback, 'AGENT_REMOTE_ERROR', this.parseHttpError(raw, response.responseCode)); return; }
          const result: Object = this.objectField(envelope, 'result') ?? JSON.parse('{}') as Object;
          callback?.(JSON.stringify({ ok: true, response: result }));
        } catch (_) { this.fail(callback, 'SESSION_RESPONSE_INVALID', '会话响应格式无效'); }
      }).catch((error: Object) => { request.destroy(); this.fail(callback, this.errorCode(error), '会话请求失败'); });
    }).catch((error: Object) => this.fail(callback, this.errorCode(error), '读取登录状态失败'));
  }

  private sessionRpc(method: string, params: KRAny, callback: KuiklyRenderCallback | null): void {
    let input: Object = JSON.parse('{}') as Object;
    try { input = typeof params === 'string' ? JSON.parse(params) as Object : JSON.parse('{}') as Object; }
    catch (_) { this.fail(callback, 'INVALID_SESSION_REQUEST', '会话参数格式无效'); return; }
    const sessionId: string = this.stringField(input, 'sessionId') || this.stringField(input, 'session_id');
    if (method !== 'session.attach' || !sessionId.trim()) {
      this.fail(callback, 'INVALID_SESSION_REQUEST', '请选择有效会话后再绑定'); return;
    }
    const requestId: string = `sys-${++this.sequence}`;
    if (callback !== null) {
      const pending: PendingCall = { callback, requestId, persistSessionId: sessionId };
      pending.timer = setTimeout(() => this.failPending(requestId, 'AGENT_TIMEOUT', '会话请求超时'), 30000);
      this.pending.set(requestId, pending);
    }
    this.ensureSocket().then(async (ready: boolean) => {
      if (!ready || !this.pending.has(requestId)) { if (!ready) this.failPending(requestId, 'AGENT_CONNECT_FAILED', '无法连接 navi-claw 导航服务'); return; }
      const generation: number = this.socketGeneration;
      const deviceId: string = await this.readDeviceId();
      if (generation !== this.socketGeneration || !this.pending.has(requestId)) return;
      const attach: ChannelAttachParams = { channelId: this.config.naviClawChannelId ?? 'navigation-app', peer: { deviceId }, conversationId: this.conversationId, sessionId, capabilities: { supports_waypoints: true, max_waypoints: 3, locale: 'zh-CN' }, metadata: { platform: 'harmony', client: 'myapplication' }, attachMode: 'resume_only' };
      this.sendOrQueue(JSON.stringify({ type: 'req', id: requestId, method: 'channel.attach', params: attach }), requestId);
    }).catch((error: Object) => this.failPending(requestId, this.errorCode(error), '会话请求失败'));
  }

  private authenticate(register: boolean, params: KRAny, callback: KuiklyRenderCallback | null): void {
    let input: Object = JSON.parse('{}') as Object;
    try { input = typeof params === 'string' ? JSON.parse(params) as Object : JSON.parse('{}') as Object; }
    catch (_) { this.fail(callback, 'INVALID_AUTH_REQUEST', '登录参数格式无效'); return; }
    // MapTemplate/AuthApiClient sends exactly username/password and, for
    // registration, the optional camelCase displayName. Build a fresh object
    // here so the bridge-only register flag and legacy snake_case fields never
    // leak into navi-claw's strict zod request schema.
    const username: string = this.stringField(input, 'username').trim();
    const password: string = this.stringField(input, 'password');
    if (!username) { this.fail(callback, 'INVALID_AUTH_REQUEST', '用户名不能为空'); return; }
    if (!password.trim()) { this.fail(callback, 'INVALID_AUTH_REQUEST', '密码不能为空'); return; }
    const body: AuthRequestBody = { username, password };
    if (register) {
      const displayName: string = (this.stringField(input, 'displayName') || this.stringField(input, 'display_name')).trim();
      if (displayName) body.displayName = displayName;
    }
    const base: string = this.gatewayHttpBaseUrl();
    const configuredPath: string = register ? (this.config.naviClawRegisterPath ?? '/auth/register') : (this.config.naviClawLoginPath ?? '/auth/login');
    const path: string = configuredPath.startsWith('/') ? configuredPath : `/${configuredPath}`;
    if (!base) { this.fail(callback, 'AUTH_ENDPOINT_NOT_CONFIGURED', 'navi-claw 登录地址尚未配置'); return; }
    const request = http.createHttp();
    request.request(`${base}${path}`, { method: http.RequestMethod.POST, header: { 'Content-Type': 'application/json' }, extraData: JSON.stringify(body), expectDataType: http.HttpDataType.STRING }).then((response: http.HttpResponse) => {
      request.destroy();
      const raw: string = typeof response.result === 'string' ? response.result : JSON.stringify(response.result);
      if (response.responseCode < 200 || response.responseCode >= 300) { this.fail(callback, `AUTH_HTTP_${response.responseCode}`, this.parseHttpError(raw, response.responseCode)); return; }
      try { const parsed: Object = JSON.parse(raw) as Object; const token: string = this.stringField(parsed, 'accessToken') || this.stringField(parsed, 'access_token') || this.stringField(parsed, 'token'); if (!token) { this.fail(callback, 'AUTH_RESPONSE_INVALID', '登录响应未返回 access token'); return; } const user: Object | undefined = this.objectField(parsed, 'user'); const userId: string = this.stringField(parsed, 'userId') || this.stringField(parsed, 'user_id') || (user ? (this.stringField(user, 'userId') || this.stringField(user, 'user_id')) : ''); const displayName: string = this.stringField(parsed, 'displayName') || this.stringField(parsed, 'display_name') || (user ? (this.stringField(user, 'displayName') || this.stringField(user, 'display_name')) : ''); const username: string = this.stringField(parsed, 'username') || (user ? this.stringField(user, 'username') : ''); if (!userId || !username) { this.fail(callback, 'AUTH_RESPONSE_INVALID', '登录响应缺少用户资料'); return; } this.persistProfile(token, userId, displayName, username).then(() => callback?.(JSON.stringify({ ok: true, logged_in: true, user_id: userId, display_name: displayName, username }))).catch(() => this.fail(callback, 'AUTH_PERSIST_FAILED', '登录信息保存失败')); }
      catch (_) { this.fail(callback, 'AUTH_RESPONSE_INVALID', '登录响应格式无效'); }
    }).catch((error: Object) => { request.destroy(); this.fail(callback, this.errorCode(error), '登录请求失败'); });
  }

  private async ensureSocket(): Promise<boolean> {
    if (this.socketOpen && this.socketReady && this.socket) return true;
    if (this.socketOpening) return new Promise<boolean>((resolve) => this.readyWaiters.push(resolve));
    const url: string = this.gatewayWebSocketUrl(); if (!url) return false;
    this.socketOpening = true;
    const token: string = await this.readToken();
    if (!token) { this.finishSocket(false); return false; }
    const deviceId: string = await this.readDeviceId();
    const socket = webSocket.createWebSocket(); const generation: number = ++this.socketGeneration;
    this.socket = socket; this.socketOpen = false; this.socketReady = false;
    this.connectSent = false;
    this.handshakeTimer = setTimeout(() => { if (generation === this.socketGeneration) this.finishSocket(false); }, 15000);
    socket.on('open', () => {
      if (generation !== this.socketGeneration) return;
      this.socketOpen = true;
    });
    socket.on('message', (error: BusinessError<void>, data: string | ArrayBuffer): void => { if (!error && generation === this.socketGeneration) this.handleMessage(this.decodeMessage(data)); });
    socket.on('error', () => { if (generation === this.socketGeneration) this.finishSocket(false); });
    socket.on('close', () => { if (generation === this.socketGeneration) this.finishSocket(false); });
    // Match MapTemplate: authenticate the upgrade with both headers, then
    // answer navi-claw's connect.challenge with an empty connect request.
    const options: webSocket.WebSocketRequestOptions = {
      header: { Authorization: `Bearer ${token}`, 'X-Navi-Device-Id': deviceId }
    };
    try { await socket.connect(url, options); } catch (_) { this.finishSocket(false); }
    return new Promise<boolean>((resolve) => { if (this.socketReady) resolve(true); else if (!this.socketOpening) resolve(false); else this.readyWaiters.push(resolve); });
  }

  private attachChannelAfterConnect(): void {
    this.readProfile().then(async (profile: AuthProfile) => {
      // A persisted session may belong to a previous account or may have been
      // removed by the server. Retry by route key once instead of turning a
      // stale local id into a permanent "channel binding failed" state.
      return this.sendChannelAttach(profile.bound_session_id, true);
    }).catch((error: Object) => this.finishSocket(false, this.errorMessage(error)));
  }

  private async sendChannelAttach(requestedSessionId: string, retryWithoutSession: boolean): Promise<void> {
    const deviceId: string = await this.readDeviceId();
    const attach: ChannelAttachParams = {
      channelId: this.config.naviClawChannelId ?? 'navigation-app',
      peer: { deviceId },
      conversationId: this.conversationId,
      capabilities: { supports_waypoints: true, max_waypoints: 3, locale: 'zh-CN' },
      metadata: { platform: 'harmony', client: 'myapplication' },
      attachMode: 'resume_or_create'
    };
    if (requestedSessionId) attach.sessionId = requestedSessionId;
    this.sendControl('channel.attach', attach, (attached: boolean, payload: Object) => {
      if (attached) {
        this.saveBoundSession(payload);
        this.finishSocket(true);
      } else if (requestedSessionId && retryWithoutSession) {
        this.clearBoundSessionId();
        this.sendChannelAttach('', false).catch((error: Object) => this.finishSocket(false, this.errorMessage(error)));
      } else {
        this.finishSocket(false, this.errorMessage(payload));
      }
    });
  }

  private navigationFrame(request: AgentRequest, requestId: string): string {
    if (request.type === 'feedback') {
      // navi-claw correlates feedback with the original navigation request. The
      // transport frame id is only the acknowledgement id and must not be used here.
      const feedback: FeedbackParams = { session_id: request.session_id ?? '', request_id: request.feedback_request_id ?? request.request_id ?? requestId, trace_id: request.trace_id ?? '', accepted: request.accepted ?? ((request.rating ?? 0) >= 3), comment: request.comment };
      return JSON.stringify({ type: 'req', id: requestId, method: 'navigation.feedback', params: feedback });
    }
    const kind: string = request.type === 'candidate_selection' ? 'disambiguation_choice' : request.type === 'cancel' ? 'cancel' : request.kind === 'modify_task' ? 'modify_task' : 'new_intent';
    const capabilities: NavigationCapabilities = { supports_waypoints: true, max_waypoints: 3, locale: 'zh-CN', supports_feedback: false };
    const params: NavigationSubmitParams = { type: 'navigation', kind, request_id: requestId, session_id: request.session_id, text: request.text, client_capabilities: capabilities, metadata: { client: 'harmony-app', entry: 'kuikly-navigation', action: kind } } as NavigationSubmitParams;
    if (kind === 'modify_task') {
      if (!request.existing_task) throw new Error('修改路线缺少当前导航任务，请重新提交目的地');
      params.existing_task = request.existing_task;
    }
    if (request.travel_mode) { const constraints: TravelConstraints = { travel_mode: request.travel_mode }; params.constraints = constraints; }
    const location = this.objectField(request, 'location');
    if (location) {
      const latitude = this.numberField(location, 'latitude');
      const longitude = this.numberField(location, 'longitude');
      const lat = Number.isFinite(latitude) ? latitude : this.numberField(location, 'lat');
      const lng = Number.isFinite(longitude) ? longitude : this.numberField(location, 'lng');
      if (Number.isFinite(lat) && Math.abs(lat) <= 90 && Number.isFinite(lng) && Math.abs(lng) <= 180) {
        params.user_context = { location: { point: { lat, lng } } };
      }
    }
    if (kind === 'disambiguation_choice') params.disambiguation_choices = [{ group_id: request.group_id ?? '', chosen_item_id: request.candidate_id ?? '' }];
    return JSON.stringify({ type: 'req', id: requestId, method: 'navigation.submit', params });
  }
  private sendControl(method: string, params: Object, callback?: (ok: boolean, payload: Object) => void): void { const id: string = `sys-${++this.sequence}`; if (callback) this.controlCallbacks.set(id, callback); this.sendOrQueue(JSON.stringify({ type: 'req', id, method, params }), id); }
  private sendOrQueue(frame: string, requestId: string = ''): void { if (!this.socket || !this.socketOpen || (!this.socketReady && !requestId.startsWith('sys-'))) { this.queuedFrames.push({ frame, requestId }); return; } this.socket.send(frame).catch(() => this.queuedFrames.push({ frame, requestId })); }
  private flushQueue(): void { const frames: QueuedFrame[] = this.queuedFrames.splice(0); frames.forEach((item: QueuedFrame) => this.sendOrQueue(item.frame, item.requestId)); }
  private finishSocket(ready: boolean, failureMessage: string = 'navi-claw 连接或频道绑定失败'): void { if (!this.socketOpening && !ready && !this.socketReady) return; if (this.handshakeTimer !== undefined) { clearTimeout(this.handshakeTimer); this.handshakeTimer = undefined; } this.socketOpening = false; this.socketReady = ready; if (!ready) { this.socketOpen = false; this.connectSent = false; } const waiters = this.readyWaiters.splice(0); waiters.forEach((resolve: (ready: boolean) => void) => resolve(ready)); if (ready) this.flushQueue(); else { const pending = Array.from(this.pending.keys()); pending.forEach((id: string) => this.failPending(id, 'AGENT_CONNECT_FAILED', failureMessage)); this.queuedFrames = []; } }
  private saveBoundSession(payload: Object): void { const session: Object | undefined = this.objectField(payload, 'session'); const sessionId: string = session ? this.stringField(session, 'sessionId') : this.stringField(payload, 'sessionId'); this.saveBoundSessionId(sessionId); }
  private saveBoundSessionId(sessionId: string): void { if (!sessionId || !this.context) return; preferences.getPreferences(this.context, KRNavigationAgentModule.TOKEN_STORE).then((store) => store.put('bound_session_id', sessionId).then(() => store.flush())).catch(() => {}); }
  private clearBoundSessionId(): void { if (!this.context) return; preferences.getPreferences(this.context, KRNavigationAgentModule.TOKEN_STORE).then((store) => store.put('bound_session_id', '').then(() => store.flush())).catch(() => {}); }

  private handleMessage(raw: string): void {
    if (!raw) return; let message: Object; try { message = JSON.parse(raw) as Object; } catch (_) { return; }
    const event: string = this.stringField(message, 'event');
    const eventPayload: Object | undefined = this.objectField(message, 'payload');
    if (event === 'connect.challenge') {
      if (!this.connectSent && this.socketOpen) {
        this.connectSent = true;
        const connect: ConnectParams = {};
        this.sendControl('connect', connect, (ok: boolean, payload: Object) => {
          if (ok) this.attachChannelAfterConnect();
          else this.finishSocket(false, this.errorMessage(payload));
        });
      }
      return;
    }
    if (event === 'channel.attached') {
      // MapTemplate records the attachment event but completes the attach
      // request from the corresponding response frame.
      if (eventPayload) this.saveBoundSession(eventPayload);
      return;
    }
    // state.updated and other gateway events are notifications. Only the
    // response event carries a navigation result that can resolve a request.
    if (event && event !== 'navigation.response') return;
    let id: string = this.stringField(message, 'id');
    let payload: Object | undefined = eventPayload ?? this.objectField(message, 'result') ?? this.objectField(message, 'data');
    if (event === 'navigation.response' && eventPayload) {
      const nested = this.objectField(eventPayload, 'response');
      if (nested) { payload = nested; id = this.stringField(nested, 'request_id') || this.stringField(nested, 'requestId') || this.stringField(eventPayload, 'requestId'); }
    }
    if (!id && payload) id = this.stringField(payload, 'request_id') || this.stringField(payload, 'requestId');
    const errorObject = this.objectField(message, 'error');
    const control = id ? this.controlCallbacks.get(id) : undefined;
    if (control) { this.controlCallbacks.delete(id); control(!errorObject, payload ?? message); return; }
    const pending = id ? this.pending.get(id) : undefined; if (!pending) return; this.pending.delete(id); if (pending.timer !== undefined) clearTimeout(pending.timer);
    if (errorObject) { this.fail(pending.callback, this.stringField(errorObject, 'code') || 'AGENT_REMOTE_ERROR', this.stringField(errorObject, 'message') || '导航服务请求失败'); return; }
    if (!payload) payload = message;
    if (pending.persistSessionId) {
      const session = this.objectField(payload, 'session');
      const attachedSessionId: string = session ? this.stringField(session, 'sessionId') : this.stringField(payload, 'sessionId');
      if (attachedSessionId !== pending.persistSessionId) {
        this.fail(pending.callback, 'SESSION_BINDING_MISMATCH', '服务返回的会话与所选会话不一致，请重新选择'); return;
      }
      this.saveBoundSessionId(attachedSessionId);
    }
    pending.callback(JSON.stringify({ ok: true, response: payload }));
  }
  private cancel(params: KRAny): void { let requestId: string = ''; try { requestId = this.stringField(JSON.parse(typeof params === 'string' ? params : '{}') as Object, 'request_id'); } catch (_) {} if (requestId) { const pending = this.pending.get(requestId); if (pending?.timer !== undefined) clearTimeout(pending.timer); this.pending.delete(requestId); this.queuedFrames = this.queuedFrames.filter((item: QueuedFrame) => item.requestId !== requestId); } }
  private closeSocket(): void { this.socketGeneration++; if (this.handshakeTimer !== undefined) { clearTimeout(this.handshakeTimer); this.handshakeTimer = undefined; } this.socketOpening = false; this.socketOpen = false; this.socketReady = false; this.connectSent = false; this.queuedFrames = []; this.controlCallbacks.clear(); const waiters = this.readyWaiters.splice(0); waiters.forEach((resolve: (ready: boolean) => void) => resolve(false)); const pending = Array.from(this.pending.keys()); pending.forEach((id: string) => this.failPending(id, 'AGENT_CLOSED', '导航服务连接已关闭')); if (this.socket) this.socket.close().catch(() => {}); this.socket = undefined; }
  private gatewayHttpBaseUrl(): string {
    const configured: string = this.config.naviClawWebSocketUrl?.trim() || '';
    if (!configured) return '';
    let value: string = configured.replace(/^ws:/i, 'http:').replace(/^wss:/i, 'https:');
    value = value.replace(/\/ws(?:[/?#].*)?$/i, '');
    while (value.length > 1 && value.endsWith('/')) value = value.substring(0, value.length - 1);
    return value;
  }
  private gatewayWebSocketUrl(): string {
    const configured: string = this.config.naviClawWebSocketUrl?.trim() || '';
    if (!configured) return '';
    let value: string = configured.replace(/^http:/i, 'ws:').replace(/^https:/i, 'wss:');
    if (!/\/ws(?:[/?#]|$)/i.test(value)) {
      while (value.length > 1 && value.endsWith('/')) value = value.substring(0, value.length - 1);
      value += '/ws';
    }
    return value;
  }
  private parseHttpError(raw: string, statusCode: number): string {
    try {
      const parsed: Object = JSON.parse(raw) as Object;
      const direct: string = this.stringField(parsed, 'message');
      if (direct) return direct;
      const nested: Object | undefined = this.objectField(parsed, 'error');
      const nestedMessage: string = nested ? this.stringField(nested, 'message') : '';
      if (nestedMessage) return nestedMessage;
    } catch (_) {}
    return `请求失败，HTTP ${statusCode}`;
  }
  private errorMessage(value: Object): string {
    const error = this.objectField(value, 'error');
    const nested = error ? this.stringField(error, 'message') : '';
    return nested || this.stringField(value, 'message') || 'navi-claw 连接或频道绑定失败';
  }
  private async readDeviceId(): Promise<string> {
    if (this.deviceId) return this.deviceId;
    if (!this.context) { this.deviceId = 'harmony-navigation'; return this.deviceId; }
    try {
      const store = await preferences.getPreferences(this.context, KRNavigationAgentModule.TOKEN_STORE);
      const stored: Object = await store.get('device_id', '');
      if (typeof stored === 'string' && stored.trim()) { this.deviceId = stored; return this.deviceId; }
      this.deviceId = `harmony-navigation-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
      await store.put('device_id', this.deviceId);
      await store.flush();
      return this.deviceId;
    } catch (_) {
      this.deviceId = `harmony-navigation-${Date.now()}`;
      return this.deviceId;
    }
  }
  private loadConfig(): GatewayConfig { if (!this.context) return {}; try { const bytes: Uint8Array = this.context.resourceManager.getRawFileContentSync('navigation_config.json'); return JSON.parse(util.TextDecoder.create('utf-8').decodeWithStream(bytes)) as GatewayConfig; } catch (_) { return {}; } }
  private decodeMessage(data: string | ArrayBuffer): string {
    if (typeof data === 'string') return data;
    try { return util.TextDecoder.create('utf-8').decodeWithStream(new Uint8Array(data)); } catch (_) { return ''; }
  }
  private async readToken(): Promise<string> { const profile = await this.readProfile(); return this.stringField(profile, KRNavigationAgentModule.TOKEN_KEY); }
  private async readProfile(): Promise<AuthProfile> { if (!this.context) return { access_token: '', user_id: '', display_name: '', username: '', bound_session_id: '' }; try { const store = await preferences.getPreferences(this.context, KRNavigationAgentModule.TOKEN_STORE); const token: Object = await store.get(KRNavigationAgentModule.TOKEN_KEY, ''); const userId: Object = await store.get('user_id', ''); const displayName: Object = await store.get('display_name', ''); const username: Object = await store.get('username', ''); const bound: Object = await store.get('bound_session_id', ''); return { access_token: typeof token === 'string' ? token : '', user_id: typeof userId === 'string' ? userId : '', display_name: typeof displayName === 'string' ? displayName : '', username: typeof username === 'string' ? username : '', bound_session_id: typeof bound === 'string' ? bound : '' }; } catch (_) { return { access_token: '', user_id: '', display_name: '', username: '', bound_session_id: '' }; } }
  private persistProfile(token: string, userId: string, displayName: string, username: string): Promise<void> { if (!this.context || !token) return Promise.resolve(); return preferences.getPreferences(this.context, KRNavigationAgentModule.TOKEN_STORE).then((store) => store.put(KRNavigationAgentModule.TOKEN_KEY, token).then(() => store.put('user_id', userId)).then(() => store.put('display_name', displayName)).then(() => store.put('username', username)).then(() => store.put('bound_session_id', '')).then(() => store.flush())); }
  private clearToken(): Promise<void> { if (!this.context) return Promise.resolve(); return preferences.getPreferences(this.context, KRNavigationAgentModule.TOKEN_STORE).then((store) => store.put(KRNavigationAgentModule.TOKEN_KEY, '').then(() => store.put('user_id', '')).then(() => store.put('display_name', '')).then(() => store.put('username', '')).then(() => store.put('bound_session_id', '')).then(() => store.flush())); }
  private fail(callback: KuiklyRenderCallback | null, code: string, message: string): void { callback?.(JSON.stringify({ ok: false, code, message })); }
  private failPending(requestId: string, code: string, message: string): void { const pending = this.pending.get(requestId); if (!pending) return; this.pending.delete(requestId); if (pending.timer !== undefined) clearTimeout(pending.timer); this.fail(pending.callback, code, message); }
  private stringField(value: Object, key: string): string { const item: Object = (value as Record<string, Object>)[key]; return typeof item === 'string' ? item : ''; }
  private objectField(value: Object, key: string): Object | undefined { const item: Object = (value as Record<string, Object>)[key]; return item !== null && typeof item === 'object' ? item : undefined; }
  private numberField(value: Object, key: string): number { const item: Object = (value as Record<string, Object>)[key]; return typeof item === 'number' ? item : Number.NaN; }
  private errorCode(error: Object): string { const code: Object = (error as Record<string, Object>)['code']; return typeof code === 'string' ? code : 'AGENT_NETWORK_ERROR'; }
}
