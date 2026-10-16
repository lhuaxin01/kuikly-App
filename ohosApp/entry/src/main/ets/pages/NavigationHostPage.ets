import { common } from '@kit.AbilityKit';
import { KeyboardAvoidMode } from '@kit.ArkUI';
import { ErrorReason, Kuikly } from '@kuikly-open/render';
import { KuiklyViewDelegate } from '../kuikly/KuiklyViewDelegate';
import globalNativeManager from '../kuikly/NavigationNativeManager';
import { HarmonyNavigationSession } from '../navigation/platform/HarmonyNavigationSession';

@Entry
@Component
struct NavigationHostPage {
  private session?: HarmonyNavigationSession;
  private delegate?: KuiklyViewDelegate;
  @State private ready: boolean = false;
  @State private failure: string = '';

  aboutToAppear(): void {
    try {
      // Let ArkUI resize the single Kuikly viewport. Kuikly propagates its new
      // root size to Kotlin, so bottom-anchored input/panels move together while
      // the native map stays mounted. Do not also add keyboard-height offsets.
      this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
      this.session = new HarmonyNavigationSession(this.getUIContext().getHostContext() as common.UIAbilityContext);
      this.delegate = new KuiklyViewDelegate(this.session);
      this.ready = true;
    } catch (error) { this.failure = (error as Error).message; }
  }
  onPageShow(): void { this.delegate?.pageDidAppear(); }
  onPageHide(): void { this.session?.stop('已离开导航页面'); this.delegate?.pageDidDisappear(); }
  onBackPress(): boolean {
    if (this.session?.exitGuidance()) return true;
    this.session?.stop('已退出导航页面');
    return false;
  }
  aboutToDisappear(): void { this.ready = false; this.session?.dispose(); this.session = undefined; }

  build() {
    Stack() {
      if (this.ready && this.delegate) {
        Kuikly({
          pagerName: 'navigation', pagerData: {}, delegate: this.delegate, nativeManager: globalNativeManager,
          onRenderException: (error: Error, reason: ErrorReason) => {
            this.session?.stop('页面渲染异常，导航已停止');
            this.failure = `导航页面加载失败：${error.message}`;
          }
        }).width('100%').height('100%')
      }
      if (this.failure) {
        Column({ space: 12 }) {
          Text('导航暂时不可用').fontSize(22)
          Text(this.failure).fontSize(14)
        }.padding(24).width('100%').height('100%').backgroundColor('#F5F7FB').justifyContent(FlexAlign.Center)
      }
    }.width('100%').height('100%')
  }
}
