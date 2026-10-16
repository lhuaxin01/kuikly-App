import { bundleManager, common } from '@kit.AbilityKit';
import hilog from '@ohos.hilog';
import { NavigationController } from '../domain/NavigationController';
import { GeoPoint, NavigationDestination, NavigationPhase, NavigationSnapshot, validPoint } from '../domain/NavigationModels';
import { NavigationConfig } from './NavigationConfig';
import { AmapNavigationEngine } from './AmapNavigationEngine';
import { HarmonyNavigationClock } from './HarmonyNavigationClock';
import { NavigationLifecycle } from './NavigationLifecycle';
import { maskedAmapKey } from './AmapRouteErrors';

export interface MapSlotState { visible: boolean; name: string; }
export interface BridgePlace { name: string; latitude: number; longitude: number; }
export interface NavigationCommand {
  simulation?: boolean;
  destination?: BridgePlace;
  waypoints?: BridgePlace[];
  travelMode?: string;
  revision?: number;
}
interface SessionEvent {
  type: string;
  state?: NavigationSnapshot;
  routeRevision?: number;
  latitude?: number;
  longitude?: number;
  destination?: BridgePlace;
  keyConfigured?: boolean;
  appId?: string;
  configSource?: string;
  keyHint?: string;
  naviSdkVersion?: string;
  maxWaypoints?: number;
  travelModes?: string[];
}

/** One instance per Kuikly host; both custom module and custom map view receive this same instance. */
export class HarmonyNavigationSession {
  private context: common.UIAbilityContext;
  private config: NavigationConfig;
  private engine: AmapNavigationEngine;
  private controller: NavigationController;
  private snapshot: NavigationSnapshot = new NavigationSnapshot();
  private disposed: boolean = false;
  private routeRevision: number = -1;
  private events: ((json: string) => void) | undefined = undefined;
  private mapObservers: Map<number, (state: MapSlotState) => void> = new Map();
  private observerSequence: number = 0;

  constructor(context: common.UIAbilityContext) {
    this.context = context;
    this.config = NavigationConfig.load(context);
    this.engine = new AmapNavigationEngine(context, this.config, (point: GeoPoint) => {
      const event: SessionEvent = { type: 'point', latitude: point.latitude, longitude: point.longitude };
      this.emit(event);
    });
    this.controller = new NavigationController(this.engine, new HarmonyNavigationClock(), (state: NavigationSnapshot) => {
      // Log only changes, without exposing SDK callback diagnostics in the
      // navigation warning panel or repeating them on every GPS update.
      if (state.mapContentStatus !== this.snapshot.mapContentStatus) {
        hilog.info(0x0000, 'NaviMap', 'stage=map_content_observation view=%{public}s status=%{public}s',
          this.engine.mapViewName, state.mapContentStatus);
      }
      this.snapshot = state;
      this.publish();
    });
    NavigationLifecycle.bind(this.controller);
  }

  /** Context bridge for platform modules that own HTTP, WebSocket and token storage. */
  getContext(): common.UIAbilityContext { return this.context; }

  subscribe(callback: (json: string) => void): void {
    if (this.disposed) throw new Error('导航页面已关闭');
    this.events = callback;
    let appId = '';
    try { appId = bundleManager.getBundleInfoForSelfSync(bundleManager.BundleFlag.GET_BUNDLE_INFO_WITH_SIGNATURE_INFO).signatureInfo.appId; }
    catch (_) { appId = '当前环境无法读取'; }
    const event: SessionEvent = {
      type: 'environment', keyConfigured: this.config.apiKey.length > 0, appId, maxWaypoints: 3,
      configSource: this.config.source, keyHint: maskedAmapKey(this.config.apiKey), naviSdkVersion: '11.2.0',
      travelModes: ['drive', 'walk', 'ride'],
      destination: { name: this.config.destination.name, latitude: this.config.destination.point.latitude,
        longitude: this.config.destination.point.longitude }
    };
    this.emit(event);
    this.publish();
  }

  unsubscribe(): void { this.events = undefined; }
  subscribeMap(observer: (state: MapSlotState) => void): number {
    const id = ++this.observerSequence;
    this.mapObservers.set(id, observer);
    observer(this.mapState());
    return id;
  }
  unsubscribeMap(id: number): void { this.mapObservers.delete(id); }

  async execute(method: string, command: NavigationCommand): Promise<void> {
    if (this.disposed) throw new Error('导航页面已关闭');
    switch (method) {
      case 'open':
        await this.controller.open();
        break;
      case 'locate': this.controller.locate(); break;
      case 'plan': {
        const travelMode = command.travelMode ?? 'drive';
        if (travelMode !== 'drive' && travelMode !== 'walk' && travelMode !== 'ride') {
          throw new Error(`不支持的出行方式：${travelMode}`);
        }
        const destination = this.place(command.destination);
        const waypoints = command.waypoints ?? [];
        if (!Array.isArray(waypoints) || waypoints.length > 3) throw new Error('最多支持 3 个途经点');
        const points: GeoPoint[] = waypoints.map((item: BridgePlace) => this.place(item).point);
        const revision = command.revision;
        if (typeof revision !== 'number' || !Number.isInteger(revision) || revision < 0) throw new Error('路线版本无效');
        if (this.snapshot.phase !== NavigationPhase.LocationReady && this.snapshot.phase !== NavigationPhase.RouteReady) {
          throw new Error('请先取得当前位置');
        }
        this.routeRevision = revision;
        this.controller.plan(destination, points, travelMode);
        break;
      }
      case 'start':
        if (this.routeRevision < 0 || command.revision !== this.routeRevision) throw new Error('路线已失效，请重新规划');
        this.controller.start(command.simulation === true);
        break;
      case 'clearRoute': this.routeRevision = -1; this.controller.clearRoute(); break;
      case 'exitGuidance':
        // A queued UI command may arrive after an SDK arrival/exit callback.
        // Republish that settled state so the shared busy indicator cannot stick.
        if (!this.controller.exitGuidance()) this.publish();
        break;
      case 'stop': this.controller.stop(); break;
      case 'overview': this.engine.setOverview(true); break;
      case 'follow': this.engine.setOverview(false); break;
      case 'privacy':
        await this.context.startAbility({ action: 'ohos.want.action.viewData', uri: 'https://lbs.amap.com/pages/privacy/' });
        break;
      default: throw new Error(`未支持的导航操作：${method}`);
    }
  }

  stop(reason: string): void { if (!this.disposed) this.controller.stop(reason); }
  /** Back leaves guidance first; a second Back from preview may leave the page. */
  exitGuidance(): boolean { return !this.disposed && this.controller.exitGuidance(); }
  closeMap(): void { if (!this.disposed) this.controller.closeMap(); }
  dispose(): void {
    if (this.disposed) return;
    this.controller.dispose();
    this.disposed = true;
    NavigationLifecycle.unbind(this.controller);
    this.events = undefined;
    this.mapObservers.clear();
  }

  private place(value?: BridgePlace): NavigationDestination {
    if (!value || typeof value.name !== 'string' || !value.name.trim()) throw new Error('地点名称缺失');
    const point = new GeoPoint(value.latitude, value.longitude);
    if (!validPoint(point)) throw new Error('地点需要有效的 GCJ-02 经纬度');
    return new NavigationDestination(value.name, point);
  }
  private mapState(): MapSlotState { return { visible: this.snapshot.mapVisible, name: this.engine.mapViewName }; }
  private publish(): void {
    const mapState = this.mapState();
    this.mapObservers.forEach((observer: (state: MapSlotState) => void) => observer(mapState));
    const event: SessionEvent = { type: 'state', state: this.snapshot, routeRevision: this.routeRevision };
    this.emit(event);
  }
  private emit(event: SessionEvent): void { if (!this.disposed) this.events?.(JSON.stringify(event)); }
}
