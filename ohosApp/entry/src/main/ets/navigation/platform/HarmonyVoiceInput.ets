import { abilityAccessCtrl, common, Permissions } from '@kit.AbilityKit';
import { audio } from '@kit.AudioKit';
import { speechRecognizer } from '@kit.CoreSpeechKit';
import { BusinessError } from '@kit.BasicServicesKit';
import hilog from '@ohos.hilog';

export interface VoiceInputEvent {
  requestId: string;
  state: string;
  text: string;
  isFinal: boolean;
  error: string;
}

class VoiceCapture {
  requestId: string;
  sessionId: string;
  state: string = 'starting';
  engine?: speechRecognizer.SpeechRecognitionEngine;
  capturer?: audio.AudioCapturer;
  audioStarting?: Promise<void>;
  audioStopping?: Promise<void>;
  recording: boolean = false;
  acceptingAudio: boolean = false;
  engineCompleted: boolean = false;
  lastResultReceived: boolean = false;
  finishRequested: boolean = false;
  startedAt: number = Date.now();
  firstFrameAt: number = -1;
  audioBytes: number = 0;
  sentBytes: number = 0;
  sampleCount: number = 0;
  sampleSquares: number = 0;
  peak: number = 0;
  resultCount: number = 0;
  frames: Uint8Array[] = [];
  queueOverflow: boolean = false;
  audioTimer: number = -1;
  nextWriteAt: number = 0;
  resolveDrain?: () => void;
  committed: string = '';
  partial: string = '';
  pending: Uint8Array = new Uint8Array(0);
  deadline: number = -1;
  readData?: (buffer: ArrayBuffer) => void;
  constructor(requestId: string, sequence: number) {
    this.requestId = requestId;
    this.sessionId = `navigation_voice_${Date.now()}_${sequence}`;
  }
  text(): string { return this.committed + this.partial; }
}

/** Owns microphone and system ASR for one host. Audio is never stored or sent to navi-claw. */
export class HarmonyVoiceInput {
  private current?: VoiceCapture;
  private events?: (event: VoiceInputEvent) => void;
  private sequence: number = 0;
  private disposed: boolean = false;
  private setup: Promise<void> = Promise.resolve();
  private cleanup: Promise<void> = Promise.resolve();
  private context: common.UIAbilityContext;
  private setVoiceInputActive: (active: boolean) => void;

  constructor(context: common.UIAbilityContext, setVoiceInputActive: (active: boolean) => void) {
    this.context = context;
    this.setVoiceInputActive = setVoiceInputActive;
  }

  subscribe(callback: (event: VoiceInputEvent) => void): void { this.events = callback; }
  unsubscribe(): void { this.cancel(); this.events = undefined; }

  start(requestId: string, hotwords: string[] = []): void {
    if (this.disposed) throw new Error('语音输入页面已关闭');
    if (!requestId) throw new Error('语音输入请求标识不能为空');
    if (this.current) throw new Error('正在进行语音输入，请先结束本次录音');
    const attempt = new VoiceCapture(requestId, ++this.sequence);
    this.current = attempt;
    this.emit(attempt);
    // Serialize a cancelled permission/engine creation with the next start.
    // A late resource must be released before another session can own it.
    const previousSetup = this.setup;
    this.setup = this.prepare(attempt, previousSetup, this.normalizeHotwords(hotwords));
  }

  stop(requestId: string): void {
    const attempt = this.current;
    if (!attempt || attempt.requestId !== requestId || attempt.state === 'finishing') return;
    if (attempt.state === 'starting' && !attempt.acceptingAudio) {
      this.terminate(attempt, '语音输入尚未就绪，请重新点击语音并等待开始提示', false);
      return;
    }
    attempt.state = 'finishing';
    this.emit(attempt);
    if (!this.active(attempt)) return;
    this.armDeadline(attempt, 15000, '语音结束处理超时，请检查文字后重试');
    this.finish(attempt);
  }

  cancel(requestId?: string): void {
    const attempt = this.current;
    if (attempt && (!requestId || requestId === attempt.requestId)) this.terminate(attempt, '', false);
  }

  dispose(): void {
    this.cancel();
    this.disposed = true;
    this.events = undefined;
  }

  private async prepare(attempt: VoiceCapture, previousSetup: Promise<void>, hotwords: string[]): Promise<void> {
    try {
      await previousSetup;
      await this.cleanup;
      if (!this.active(attempt)) return;
      if (!canIUse('SystemCapability.AI.SpeechRecognizer')) {
        this.terminate(attempt, '当前设备不支持系统语音识别，请使用文字输入', false);
        return;
      }
      const permissions: Permissions[] = ['ohos.permission.MICROPHONE'];
      const result = await abilityAccessCtrl.createAtManager().requestPermissionsFromUser(this.context, permissions);
      if (!this.active(attempt)) return;
      if (result.authResults.length !== 1 || result.authResults[0] !== 0) {
        this.terminate(attempt, '麦克风权限未开启，请在系统设置中允许本应用使用麦克风后重试', false);
        return;
      }
      this.armDeadline(attempt, 15000, '语音输入启动超时，请重试或使用文字输入');
      this.trace(attempt, 'create_engine');
      const engine = await speechRecognizer.createEngine({
        language: 'zh-CN', online: 1,
        extraParams: { locate: 'CN', recognizerMode: 'long', sysGeneralLexicon: hotwords }
      });
      if (!this.active(attempt)) { engine.shutdown(); return; }
      attempt.engine = engine;
      const capturer = await audio.createAudioCapturer({
        streamInfo: {
          samplingRate: audio.AudioSamplingRate.SAMPLE_RATE_16000,
          channels: audio.AudioChannel.CHANNEL_1,
          sampleFormat: audio.AudioSampleFormat.SAMPLE_FORMAT_S16LE,
          encodingType: audio.AudioEncodingType.ENCODING_TYPE_RAW
        },
        capturerInfo: { source: audio.SourceType.SOURCE_TYPE_VOICE_RECOGNITION, capturerFlags: 0 }
      });
      if (!this.active(attempt)) { await capturer.release(); return; }
      attempt.capturer = capturer;
      attempt.readData = (buffer: ArrayBuffer) => this.acceptAudio(attempt, buffer);
      capturer.on('readData', attempt.readData);
      engine.setListener(this.listener(attempt));
      this.setVoiceInputActive(true);
      engine.startListening({
        sessionId: attempt.sessionId,
        audioInfo: { audioType: 'pcm', sampleRate: 16000, soundChannel: 1, sampleBit: 16 }
      });
    } catch (error) {
      if (this.active(attempt)) {
        this.trace(attempt, 'prepare_error', (error as BusinessError).code);
        this.terminate(attempt, this.errorMessage(error as BusinessError), false);
      }
    }
  }

  private listener(attempt: VoiceCapture): speechRecognizer.RecognitionListener {
    return {
      onStart: (sessionId: string, _message: string) => {
        if (!this.matches(attempt, sessionId) || attempt.audioStarting || attempt.state !== 'starting') return;
        this.trace(attempt, 'engine_started');
        attempt.audioStarting = this.beginAudio(attempt);
      },
      onEvent: (_sessionId: string, _eventCode: number, _message: string) => {},
      onResult: (sessionId: string, result: speechRecognizer.SpeechRecognitionResult) => {
        if (!this.matches(attempt, sessionId)) return;
        const text = result.result.trim();
        ++attempt.resultCount;
        hilog.info(0x0000, 'NaviVoice',
          'stage=result elapsedMs=%{public}d count=%{public}d chars=%{public}d isFinal=%{public}s isLast=%{public}s',
          Date.now() - attempt.startedAt, attempt.resultCount, text.length,
          `${result.isFinal}`, `${result.isLast}`);
        // CoreSpeech is clause based: isFinal commits a clause; isLast closes the session.
        // A control-only empty result must not erase a partial already shown to
        // the user (especially an empty isLast marker following finish()).
        if (text.length > 0) {
          if (result.isFinal) { attempt.committed += text; attempt.partial = ''; }
          else attempt.partial = text;
        }
        this.emit(attempt);
        if (!this.active(attempt)) return;
        if (result.isLast) {
          attempt.engineCompleted = true;
          attempt.lastResultReceived = true;
        }
        if (attempt.engineCompleted) this.waitForConfirmedResult(attempt);
      },
      onComplete: (sessionId: string, _message: string) => {
        if (!this.matches(attempt, sessionId)) return;
        attempt.engineCompleted = true;
        this.trace(attempt, 'engine_complete');
        this.waitForConfirmedResult(attempt);
      },
      onError: (sessionId: string, code: number, _message: string) => {
        if (this.matches(attempt, sessionId)) {
          this.trace(attempt, 'engine_error', code);
          this.terminate(attempt, this.errorMessageWithCode(code), false);
        }
      }
    };
  }

  private async beginAudio(attempt: VoiceCapture): Promise<void> {
    try {
      // readData may arrive before AudioCapturer.start() resolves. ASR has
      // already acknowledged onStart, so accept those first microphone frames.
      attempt.acceptingAudio = true;
      await attempt.capturer?.start();
      attempt.recording = true;
      if (!this.active(attempt) || attempt.state !== 'starting') return;
      this.trace(attempt, 'capture_started');
      this.armDeadline(attempt, 5000, '未收到麦克风音频，请检查麦克风或远程测试设备的声音输入');
      this.markListening(attempt);
    } catch (error) {
      if (this.active(attempt)) {
        this.trace(attempt, 'capture_error', (error as BusinessError).code);
        this.terminate(attempt, this.errorMessage(error as BusinessError), false);
      }
    }
  }

  private acceptAudio(attempt: VoiceCapture, buffer: ArrayBuffer): void {
    if (!this.active(attempt) || !attempt.acceptingAudio || !attempt.engine) return;
    const incoming = new Uint8Array(buffer);
    if (incoming.length === 0) return;
    attempt.audioBytes += incoming.length;
    if (attempt.firstFrameAt < 0) {
      attempt.firstFrameAt = Date.now();
      this.trace(attempt, 'first_frame');
    }
    // At most five seconds of PCM can be waiting for the ASR consumer. A
    // stalled event loop must not accumulate an unbounded recording in memory.
    if (attempt.frames.length + Math.floor((attempt.pending.length + incoming.length) / 640) > 250) {
      attempt.queueOverflow = true;
      attempt.acceptingAudio = false;
      this.scheduleAudio(attempt);
      return;
    }
    for (let index = 0; index + 1 < incoming.length; index += 2) {
      let sample = incoming[index] | (incoming[index + 1] << 8);
      if (sample >= 32768) sample -= 65536;
      attempt.peak = Math.max(attempt.peak, Math.abs(sample));
      attempt.sampleSquares += sample * sample;
      ++attempt.sampleCount;
    }
    const bytes = new Uint8Array(attempt.pending.length + incoming.length);
    bytes.set(attempt.pending);
    bytes.set(incoming, attempt.pending.length);
    let offset = 0;
    // Keep readData limited to copying/counting PCM. In particular, SDK errors
    // and synchronous recognition callbacks must not stop AudioCapturer here.
    while (offset + 640 <= bytes.length) {
      attempt.frames.push(bytes.slice(offset, offset + 640));
      offset += 640;
    }
    attempt.pending = bytes.slice(offset);
    this.scheduleAudio(attempt);
  }

  private markListening(attempt: VoiceCapture): void {
    // AudioCapturer documents ~100 ms of muted startup PCM. Do not invite
    // speech until actual frames beyond that window have arrived.
    if (!this.active(attempt) || attempt.state !== 'starting' || !attempt.recording || attempt.audioBytes < 3840) return;
    attempt.state = 'listening';
    this.trace(attempt, 'listening_ready');
    this.emit(attempt);
    if (!this.active(attempt) || attempt.state !== 'listening') return;
    this.clearDeadline(attempt);
    attempt.deadline = setTimeout(() => this.stop(attempt.requestId), 55000);
  }

  private scheduleAudio(attempt: VoiceCapture): void {
    if (!this.active(attempt) || attempt.engineCompleted || attempt.audioTimer >= 0 ||
      (attempt.frames.length === 0 && !attempt.queueOverflow)) return;
    attempt.audioTimer = setTimeout(() => {
      attempt.audioTimer = -1;
      if (!this.active(attempt) || attempt.engineCompleted) return;
      if (attempt.queueOverflow) {
        this.trace(attempt, 'audio_queue_overflow');
        this.terminate(attempt, '录音处理延迟过大，已停止录音，请稍后重试', false);
        return;
      }
      this.markListening(attempt);
      if (!this.active(attempt) || attempt.engineCompleted) return;
      const frame = attempt.frames.shift();
      if (!frame) return;
      try {
        attempt.engine?.writeAudio(attempt.sessionId, frame);
        attempt.sentBytes += frame.length;
        attempt.nextWriteAt = Date.now() + 20;
        if (attempt.frames.length > 0) this.scheduleAudio(attempt);
        else { attempt.resolveDrain?.(); attempt.resolveDrain = undefined; }
      } catch (error) {
        this.trace(attempt, 'write_error', (error as BusinessError).code);
        this.terminate(attempt, this.errorMessage(error as BusinessError), false);
      }
    }, Math.max(0, attempt.nextWriteAt - Date.now()));
  }

  private drainAudio(attempt: VoiceCapture): Promise<void> {
    if (attempt.frames.length === 0 || !this.active(attempt) || attempt.engineCompleted) return Promise.resolve();
    return new Promise<void>((resolve) => { attempt.resolveDrain = resolve; this.scheduleAudio(attempt); });
  }

  private async finish(attempt: VoiceCapture): Promise<void> {
    try {
      await this.stopAudio(attempt);
      if (!this.active(attempt)) return;
      // Pad the last partial frame, preserving the end of the final spoken word.
      if (attempt.pending.length > 0) {
        const tail = new Uint8Array(640);
        tail.set(attempt.pending);
        attempt.pending = new Uint8Array(0);
        attempt.frames.push(tail);
      }
      await this.drainAudio(attempt);
      if (this.active(attempt) && !attempt.engineCompleted) {
        attempt.finishRequested = true;
        this.trace(attempt, 'finish_requested');
        this.armFinalDeadline(attempt);
        attempt.engine?.finish(attempt.sessionId);
      }
    } catch (error) {
      if (this.active(attempt)) {
        this.trace(attempt, 'finish_error', (error as BusinessError).code);
        this.terminate(attempt, this.errorMessage(error as BusinessError), false);
      }
    }
  }

  private waitForConfirmedResult(attempt: VoiceCapture): void {
    if (!this.active(attempt)) return;
    attempt.state = 'finishing';
    this.emit(attempt);
    if (!this.active(attempt)) return;
    this.stopAudio(attempt).catch(() => {});
    this.clearAudioQueue(attempt);
    // finish() can trigger onComplete before the remaining result callbacks.
    // An earlier confirmed clause is not evidence that the last clause arrived.
    if (attempt.lastResultReceived && attempt.committed.trim().length > 0 && attempt.partial.length === 0) {
      this.terminate(attempt, '', true);
    } else if (!attempt.finishRequested) {
      attempt.finishRequested = true;
      this.armFinalDeadline(attempt);
    }
  }

  private armFinalDeadline(attempt: VoiceCapture): void {
    this.clearDeadline(attempt);
    attempt.deadline = setTimeout(() => {
      if (!this.active(attempt)) return;
      this.terminate(attempt, this.unconfirmedMessage(attempt), false);
    }, 10000);
  }

  private unconfirmedMessage(attempt: VoiceCapture): string {
    if (attempt.text().trim().length > 0) return '语音最终结果未确认，已保留文字，请检查后发送';
    if (attempt.audioBytes === 0) return '未收到麦克风音频，请检查麦克风或远程测试设备的声音输入';
    const rms = attempt.sampleCount > 0 ? Math.sqrt(attempt.sampleSquares / attempt.sampleCount) : 0;
    if (attempt.peak < 256 && rms < 64) return '未检测到明显声音，请靠近麦克风并确认设备声音输入后重试';
    return '已收到声音，但未识别出文字，请说普通话并稍后重试';
  }

  private clearAudioQueue(attempt: VoiceCapture): void {
    if (attempt.audioTimer >= 0) clearTimeout(attempt.audioTimer);
    attempt.audioTimer = -1;
    attempt.frames = [];
    attempt.resolveDrain?.();
    attempt.resolveDrain = undefined;
  }

  private terminate(attempt: VoiceCapture, error: string, isFinal: boolean): void {
    if (!this.active(attempt)) return;
    this.current = undefined;
    this.clearDeadline(attempt);
    this.clearAudioQueue(attempt);
    this.trace(attempt, error ? 'ended_with_error' : isFinal ? 'confirmed' : 'cancelled');
    if (error) hilog.info(0x0000, 'NaviVoice', 'stage=error reason=%{public}s', error);
    const engine = attempt.engine;
    attempt.engine = undefined;
    // Invalidate callbacks before cancel; SDKs may dispatch cancellation synchronously.
    if (engine) {
      try { if (!isFinal) engine.cancel(attempt.sessionId); } catch (_) {}
      try { engine.shutdown(); } catch (_) {}
    }
    this.cleanup = this.releaseAudio(attempt);
    try { this.setVoiceInputActive(false); } catch (_) {}
    attempt.state = 'idle';
    this.emit(attempt, isFinal, error);
  }

  private async stopAudio(attempt: VoiceCapture): Promise<void> {
    if (attempt.audioStopping) { await attempt.audioStopping; return; }
    attempt.audioStopping = this.stopCapture(attempt);
    await attempt.audioStopping;
  }

  private async stopCapture(attempt: VoiceCapture): Promise<void> {
    const capturer = attempt.capturer;
    if (!capturer) return;
    if (attempt.audioStarting) await attempt.audioStarting;
    try {
      if (attempt.recording) { attempt.recording = false; await capturer.stop(); }
    } finally {
      // The capturer may deliver its final buffer while stop() is pending.
      attempt.acceptingAudio = false;
      if (attempt.readData) { capturer.off('readData', attempt.readData); attempt.readData = undefined; }
    }
  }

  private async releaseAudio(attempt: VoiceCapture): Promise<void> {
    const capturer = attempt.capturer;
    try { await this.stopAudio(attempt); } catch (_) {}
    attempt.capturer = undefined;
    if (capturer) { try { await capturer.release(); } catch (_) {} }
  }

  private active(attempt: VoiceCapture): boolean { return !this.disposed && this.current === attempt; }
  private matches(attempt: VoiceCapture, sessionId: string): boolean {
    return this.active(attempt) && attempt.sessionId === sessionId;
  }
  private emit(attempt: VoiceCapture, isFinal: boolean = false, error: string = ''): void {
    this.events?.({ requestId: attempt.requestId, state: attempt.state, text: attempt.text(), isFinal, error });
  }
  private clearDeadline(attempt: VoiceCapture): void {
    if (attempt.deadline >= 0) clearTimeout(attempt.deadline);
    attempt.deadline = -1;
  }
  private armDeadline(attempt: VoiceCapture, milliseconds: number, message: string): void {
    this.clearDeadline(attempt);
    attempt.deadline = setTimeout(() => this.terminate(attempt, message, false), milliseconds);
  }
  private errorMessage(error: BusinessError): string { return this.errorMessageWithCode(error.code); }
  private normalizeHotwords(hotwords: string[]): string[] {
    const result: string[] = [];
    if (!Array.isArray(hotwords)) return result;
    for (const value of hotwords) {
      if (typeof value !== 'string') continue;
      const word = value.trim();
      if (word.length >= 2 && word.length <= 20 && result.indexOf(word) < 0) result.push(word);
      if (result.length >= 200) break;
    }
    return result;
  }
  private trace(attempt: VoiceCapture, stage: string, code: number = 0): void {
    const rms = attempt.sampleCount > 0 ? Math.sqrt(attempt.sampleSquares / attempt.sampleCount) : 0;
    hilog.info(0x0000, 'NaviVoice',
      'stage=%{public}s elapsedMs=%{public}d inputBytes=%{public}d sentBytes=%{public}d peak=%{public}d rms=%{public}d results=%{public}d code=%{public}d',
      stage, Date.now() - attempt.startedAt, attempt.audioBytes, attempt.sentBytes,
      attempt.peak, Math.round(rms), attempt.resultCount, code || 0);
  }
  private errorMessageWithCode(code: number): string {
    if (code === 201) return '麦克风权限未开启，请在系统设置中允许本应用使用麦克风后重试';
    if (code === 1002200006 || code === 1002200008) return '系统语音服务忙，请稍后重试';
    if (code === 1002200001 || code === 801) return '系统离线语音识别暂不可用，请检查系统语音服务或使用文字输入';
    return `语音输入失败${code ? `（${code}）` : ''}，请重试或使用文字输入`;
  }
}
