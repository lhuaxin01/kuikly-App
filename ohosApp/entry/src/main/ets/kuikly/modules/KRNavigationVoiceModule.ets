import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { HarmonyVoiceInput, VoiceInputEvent } from '../../navigation/platform/HarmonyVoiceInput';

interface VoiceCommand { requestId?: string; hotwords?: string[]; }
interface VoiceCommandResult { ok: boolean; message?: string; }

export class KRNavigationVoiceModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME: string = 'KRNavigationVoiceModule';
  private voice: HarmonyVoiceInput;
  private destroyed: boolean = false;
  constructor(voice: HarmonyVoiceInput) { super(); this.voice = voice; }
  syncMode(): boolean { return false; }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    if (this.destroyed) return null;
    try {
      if (method === 'subscribe') {
        if (callback) this.voice.subscribe((event: VoiceInputEvent) => {
          if (!this.destroyed) callback(JSON.stringify(event));
        });
        return null;
      }
      const command: VoiceCommand = typeof params === 'string' ? JSON.parse(params) as VoiceCommand : {};
      if (method === 'start') this.voice.start(command.requestId ?? '', command.hotwords ?? []);
      else if (method === 'stop') this.voice.stop(command.requestId ?? '');
      else if (method === 'cancel') this.voice.cancel(command.requestId);
      else if (method === 'unsubscribe') this.voice.unsubscribe();
      else throw new Error(`不支持的语音输入操作：${method}`);
      callback?.('{"ok":true}');
    } catch (error) {
      const result: VoiceCommandResult = { ok: false, message: (error as Error).message };
      callback?.(JSON.stringify(result));
    }
    return null;
  }

  onDestroy(): void {
    this.destroyed = true;
    this.voice.unsubscribe();
    super.onDestroy();
  }
}
