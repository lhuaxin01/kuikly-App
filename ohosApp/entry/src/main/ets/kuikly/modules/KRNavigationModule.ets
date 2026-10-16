import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { HarmonyNavigationSession, NavigationCommand } from '../../navigation/platform/HarmonyNavigationSession';

interface CommandFailure { ok: boolean; message: string; }

export class KRNavigationModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME: string = 'KRNavigationModule';
  private session: HarmonyNavigationSession;
  private destroyed: boolean = false;
  constructor(session: HarmonyNavigationSession) { super(); this.session = session; }
  syncMode(): boolean { return false; }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    if (this.destroyed) return null;
    try {
      if (method === 'subscribe') {
        if (callback) this.session.subscribe((json: string) => { if (!this.destroyed) callback(json); });
      } else if (method === 'unsubscribe') {
        this.session.unsubscribe();
      } else {
        const command: NavigationCommand = typeof params === 'string' ? JSON.parse(params) as NavigationCommand : {};
        this.session.execute(method, command).then(() => {
          if (!this.destroyed) callback?.('{"ok":true}');
        }).catch((error: Error) => {
          const failure: CommandFailure = { ok: false, message: error.message };
          if (!this.destroyed) callback?.(JSON.stringify(failure));
        });
      }
    } catch (error) {
      const failure: CommandFailure = { ok: false, message: (error as Error).message };
      callback?.(JSON.stringify(failure));
    }
    return null;
  }

  onDestroy(): void {
    this.destroyed = true;
    this.session.unsubscribe();
    this.session.stop('导航模块已关闭');
    super.onDestroy();
  }
}
