import { ComponentContent } from '@kit.ArkUI';
import { KRAny, KuiklyRenderBaseView, KuiklyRenderCallback } from '@kuikly-open/render';
import { MapViewComponent } from '@amap/amap_lbs_navi';
import { HarmonyNavigationSession, MapSlotState } from '../../navigation/platform/HarmonyNavigationSession';

@Observed
export class KRNavigationMapView extends KuiklyRenderBaseView {
  static readonly VIEW_NAME: string = 'KRNavigationMapView';
  visible: boolean = false;
  mapName: string = '';
  private session: HarmonyNavigationSession;
  private observerId: number = -1;
  constructor(session: HarmonyNavigationSession) { super(); this.session = session; }

  createArkUIView(): ComponentContent<KuiklyRenderBaseView> {
    this.session.unsubscribeMap(this.observerId);
    this.observerId = this.session.subscribeMap((state: MapSlotState) => {
      this.mapName = state.name;
      this.visible = state.visible;
    });
    return new ComponentContent<KuiklyRenderBaseView>(this.getUIContext() as UIContext,
      wrapBuilder<[KuiklyRenderBaseView]>(createNavigationMap), this);
  }
  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): void {}
  onDestroy(): void {
    this.session.unsubscribeMap(this.observerId);
    this.session.closeMap();
    super.onDestroy();
  }
}

@Component
struct NavigationMapContent {
  @ObjectLink view: KRNavigationMapView;
  build() {
    Stack() {
      if (this.view.visible && this.view.mapName.length > 0) {
        // mapViewName is a plain SDK field, not a reactive @Prop. Recreate the
        // native surface for each session even if stop/open updates are coalesced.
        ForEach([this.view.mapName], (mapName: string) => {
          MapViewComponent({ mapViewName: mapName, soName: 'AMapOpenNaviJNI' }).width('100%').height('100%')
        }, (mapName: string) => mapName)
      } else {
        Column({ space: 8 }) {
          Text('地图导航').fontSize(22).fontColor('#30465F')
          Text('地图准备中').fontSize(13).fontColor('#627389')
        }.padding(20)
      }
    }.width('100%').height('100%').backgroundColor('#E7EDF4')
  }
}

@Builder
function createNavigationMap(view: KuiklyRenderBaseView) {
  NavigationMapContent({ view: view as KRNavigationMapView })
}
