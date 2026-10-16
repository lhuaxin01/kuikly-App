import { NavigationClock, NavigationEngine, NavigationEngineListener } from './NavigationEngine';
import {
  GeoPoint, MapContentStatus, NavigationDestination, NavigationLocation, NavigationPhase, NavigationProgress,
  NavigationRoute, NavigationSnapshot, validPoint
} from './NavigationModels';

const LOCATION_MAX_AGE_MS = 30000;
const LOCATION_MAX_ACCURACY_METERS = 100;

class RouteRequest {
  destination: NavigationDestination;
  waypoints: GeoPoint[];
  travelMode: string;

  constructor(destination: NavigationDestination, waypoints: GeoPoint[], travelMode: string) {
    this.destination = destination;
    this.waypoints = waypoints.slice();
    this.travelMode = travelMode;
  }
}

/** Owns one foreground navigation session. UI and SDK cannot advance the workflow independently. */
export class NavigationController {
  private engine: NavigationEngine;
  private clock: NavigationClock;
  private observer: (state: NavigationSnapshot) => void;
  private state: NavigationSnapshot = new NavigationSnapshot();
  private generation: number = 0;
  private timer: number = -1;
  private mapContentTimer: number = -1;
  private initializationComplete: boolean = false;
  private mapViewCreated: boolean = false;
  private mapReady: boolean = false;
  private sdkWarning: string = '';
  private routeWarning: string = '';
  private recoveringRouteFailure: boolean = false;
  private exitingGuidance: boolean = false;
  private disposed: boolean = false;
  private pendingPlan: RouteRequest | undefined = undefined;
  private currentPlan: RouteRequest | undefined = undefined;

  constructor(engine: NavigationEngine, clock: NavigationClock, observer: (state: NavigationSnapshot) => void) {
    this.engine = engine;
    this.clock = clock;
    this.observer = observer;
  }

  snapshot(): NavigationSnapshot {
    const copy = new NavigationSnapshot();
    copy.phase = this.state.phase;
    copy.message = this.state.message;
    copy.warning = this.state.warning;
    copy.mapVisible = this.state.mapVisible;
    copy.mapContentStatus = this.state.mapContentStatus;
    copy.location = this.state.location;
    copy.route = this.state.route;
    copy.progress = this.state.progress;
    copy.progressCount = this.state.progressCount;
    copy.simulation = this.state.simulation;
    return copy;
  }

  async open(): Promise<void> {
    if (this.disposed || (this.state.phase !== NavigationPhase.Idle &&
      this.state.phase !== NavigationPhase.Stopped && this.state.phase !== NavigationPhase.Error &&
      this.state.phase !== NavigationPhase.Arrived)) return;
    if (this.initializationComplete && this.mapReady && this.state.mapVisible) {
      this.state.progress = undefined;
      this.state.progressCount = 0;
      this.state.simulation = false;
      this.sdkWarning = '';
      this.routeWarning = '';
      this.updateWarning();
      this.transition(NavigationPhase.MapReady, '地图已就绪，正在准备当前位置');
      return;
    }
    const generation = ++this.generation;
    this.state = new NavigationSnapshot();
    this.initializationComplete = false;
    this.mapViewCreated = false;
    this.mapReady = false;
    this.sdkWarning = '';
    this.routeWarning = '';
    this.recoveringRouteFailure = false;
    this.deadline(60000, '地图初始化超时，请检查网络与配置后重试');
    this.transition(NavigationPhase.Initializing, '正在初始化地图');
    if (!this.active(generation)) return;
    const listener: NavigationEngineListener = {
      onMapViewCreated: () => {
        if (this.active(generation)) this.mapViewCreated = true;
      },
      onMapReady: () => {
        if (!this.active(generation)) return;
        this.mapReady = true;
        this.acceptMapReady();
      },
      onMapContentLoaded: () => {
        if (!this.active(generation)) return;
        this.clearMapContentDeadline();
        if (this.state.mapContentStatus === MapContentStatus.Loaded) return;
        this.state.mapContentStatus = MapContentStatus.Loaded;
        this.publish();
      },
      onLocation: (location: NavigationLocation) => {
        if (this.active(generation)) this.acceptLocation(location);
      },
      onLocationFailure: (message: string) => {
        if (this.active(generation) && this.state.phase === NavigationPhase.Locating) {
          this.finishNavigation(NavigationPhase.Error, message);
        }
      },
      onRoute: (route: NavigationRoute) => {
        if (this.active(generation)) this.acceptRoute(route);
      },
      onRouteFailure: (message: string) => {
        if (this.active(generation)) this.acceptRouteFailure(message);
      },
      onStarted: () => {
        if (!this.active(generation) || this.exitingGuidance || this.state.phase !== NavigationPhase.Starting) return;
        this.clearDeadline();
        this.transition(NavigationPhase.Navigating, this.state.simulation ? '模拟导航中，不代表真实行驶' : '真实导航已启动，等待位置与进度更新');
      },
      onProgress: (progress: NavigationProgress) => {
        if (!this.active(generation) || this.exitingGuidance || this.state.phase !== NavigationPhase.Navigating) return;
        if (!Number.isFinite(progress.remainingMeters) || progress.remainingMeters < 0 ||
          !Number.isFinite(progress.remainingSeconds) || progress.remainingSeconds < 0 ||
          !Number.isFinite(progress.nextTurnMeters) || progress.nextTurnMeters < 0) return;
        this.state.progress = progress;
        this.state.progressCount++;
        this.publish();
      },
      onArrived: () => {
        if (!this.active(generation) || this.exitingGuidance || this.state.phase !== NavigationPhase.Navigating) return;
        this.finishNavigation(NavigationPhase.Arrived, '已到达终点，地图保持显示');
      },
      onFailure: (message: string) => { if (this.active(generation) && !this.exitingGuidance) this.fail(message); },
      onWarning: (message: string) => {
        if (!this.active(generation) || this.exitingGuidance) return;
        this.sdkWarning = message;
        this.updateWarning();
        this.publish();
      },
      onExit: () => { if (this.active(generation) && !this.exitingGuidance) this.exitGuidance(); }
    };
    try {
      await this.engine.initialize(listener);
      if (!this.active(generation)) return;
      this.initializationComplete = true;
      this.clearDeadline();
      this.state.mapVisible = true;
      // Publishing mounts the native view and can synchronously report map-ready,
      // which in turn can start GPS. Install this timer before that reentrant path.
      this.mapReadyDeadline();
      this.transition(NavigationPhase.LoadingMap, '正在创建高德地图');
      if (this.active(generation)) this.acceptMapReady();
    } catch (error) {
      if (this.active(generation)) this.fail((error as Error).message || '地图初始化失败');
    }
  }

  locate(): void {
    this.requestLocation();
  }

  private requestLocation(pendingPlan?: RouteRequest): void {
    if (this.disposed || (this.state.phase !== NavigationPhase.MapReady &&
      this.state.phase !== NavigationPhase.LocationReady && this.state.phase !== NavigationPhase.RouteReady)) return;
    this.pendingPlan = pendingPlan;
    this.currentPlan = undefined;
    this.state.route = undefined;
    this.state.location = undefined;
    this.sdkWarning = '';
    this.updateWarning();
    this.deadline(30000, '定位超时，请检查精确定位权限和卫星信号后重试');
    this.transition(NavigationPhase.Locating, '正在取得当前位置，请保持系统定位开启');
    if (!this.inPhase(NavigationPhase.Locating)) return;
    try { this.engine.clearRoute(); this.engine.locate(); }
    catch (error) { this.finishNavigation(NavigationPhase.Error, (error as Error).message); }
  }

  clearRoute(): void {
    // A newer shared draft supersedes even a route waiting on a fresh GPS fix.
    this.pendingPlan = undefined;
    this.currentPlan = undefined;
    if (this.disposed || (this.state.phase !== NavigationPhase.MapReady &&
      this.state.phase !== NavigationPhase.LocationReady && this.state.phase !== NavigationPhase.RouteReady)) return;
    try { this.engine.clearRoute(); } catch (error) { this.fail((error as Error).message); return; }
    this.state.route = undefined;
    this.transition(this.state.location ? NavigationPhase.LocationReady : NavigationPhase.MapReady, '地点已更新，请重新规划路线');
  }

  plan(destination: NavigationDestination, waypoints: GeoPoint[] = [], travelMode: string = 'drive'): void {
    if (this.disposed || (this.state.phase !== NavigationPhase.LocationReady &&
      this.state.phase !== NavigationPhase.RouteReady)) return;
    if (!validPoint(destination.point) || !destination.name.trim() || waypoints.length > 3 ||
      waypoints.some((point: GeoPoint) => !validPoint(point))) {
      this.fail('起终点或途经点配置无效，请检查 GCJ-02 经纬度和数量');
      return;
    }
    if (travelMode !== 'drive' && travelMode !== 'walk' && travelMode !== 'ride') {
      this.fail('不支持的出行方式');
      return;
    }
    this.routeWarning = '';
    this.updateWarning();
    const request = new RouteRequest(destination, waypoints, travelMode);
    const location = this.state.location;
    if (!location || this.clock.now() - location.observedAt > LOCATION_MAX_AGE_MS) {
      this.requestLocation(request);
      return;
    }
    this.pendingPlan = undefined;
    this.currentPlan = request;
    this.state.route = undefined;
    const modeLabel = travelMode === 'walk' ? '步行' : travelMode === 'ride' ? '骑行' : '驾车';
    this.deadline(30000, '路线规划超时，请检查网络和 Key 后重试');
    this.transition(NavigationPhase.Planning, `正在规划前往${destination.name}的${modeLabel}路线`);
    if (!this.inPhase(NavigationPhase.Planning)) return;
    try { this.engine.plan(location, destination, waypoints, travelMode); } catch (error) { this.fail((error as Error).message); }
  }

  start(simulation: boolean = false): void {
    if (this.disposed || this.state.phase !== NavigationPhase.RouteReady || !this.state.route) return;
    if (!simulation && (!this.state.location || this.clock.now() - this.state.location.observedAt > LOCATION_MAX_AGE_MS)) {
      // Rebuild the preview from the fresh origin. Starting navigation still needs
      // a user action after the replacement route is ready.
      this.requestLocation(this.currentPlan);
      return;
    }
    this.state.simulation = simulation;
    this.state.progress = undefined;
    this.state.progressCount = 0;
    this.deadline(10000, '导航启动超时，请重新加载地图');
    this.transition(NavigationPhase.Starting, simulation ? '正在启动模拟导航' : '正在启动真实导航');
    if (!this.inPhase(NavigationPhase.Starting)) return;
    try { this.engine.start(simulation); } catch (error) { this.fail((error as Error).message); }
  }

  stop(message: string = '已停止导航，地图保持显示'): void {
    if (this.disposed || this.state.phase === NavigationPhase.Stopped || this.state.phase === NavigationPhase.Idle) return;
    this.finishNavigation(NavigationPhase.Stopped, message);
  }

  /** Returns true when a guidance exit was consumed; never discards the destination/route. */
  exitGuidance(): boolean {
    if (this.disposed) return false;
    if (this.exitingGuidance) return true;
    if (this.state.phase !== NavigationPhase.Starting && this.state.phase !== NavigationPhase.Navigating) return false;
    const generation = this.generation;
    const phase = this.state.phase;
    this.clearDeadline();
    this.pendingPlan = undefined;
    this.exitingGuidance = true;
    let errors: string[] = [];
    try { errors = this.engine.exitGuidance(); }
    catch (error) { errors = [(error as Error).message || '停止导航引导失败']; }
    this.exitingGuidance = false;
    if (!this.active(generation) || this.state.phase !== phase) return true;
    if (errors.length > 0 || !this.state.route) {
      this.fail(`退出导航失败：${errors.join('；') || '当前路线不可用'}，请重新规划`);
      return true;
    }
    this.state.progress = undefined;
    this.state.progressCount = 0;
    this.state.simulation = false;
    this.sdkWarning = '';
    this.routeWarning = '';
    this.updateWarning();
    // Keep currentPlan for a fresh-origin replan when the next real start needs GPS.
    this.transition(NavigationPhase.RouteReady, '已返回路线预览，可选择真实导航或模拟导航');
    return true;
  }

  /** A removed native surface cannot be reused, even if its host session survives. */
  closeMap(): void {
    if (!this.disposed) this.finish(NavigationPhase.Stopped, '地图页面已关闭，资源已释放');
  }

  dispose(): void {
    if (this.disposed) return;
    this.finish(NavigationPhase.Disposed, '页面已关闭，资源已释放');
    this.disposed = true;
    this.observer = () => {};
  }

  private active(generation: number): boolean {
    return !this.disposed && this.generation === generation;
  }

  private inPhase(phase: NavigationPhase): boolean {
    return !this.disposed && this.state.phase === phase;
  }

  private acceptMapReady(): void {
    if (!this.initializationComplete || !this.mapReady || this.state.phase !== NavigationPhase.LoadingMap) return;
    this.clearDeadline();
    // This callback is diagnostic, not a map availability or authentication test.
    // A normally rendered map may never report it. Keep missing confirmation out
    // of user-facing warnings, and never claim tiles loaded based on GPS/routes.
    if (this.state.mapContentStatus !== MapContentStatus.Loaded) {
      this.state.mapContentStatus = MapContentStatus.Waiting;
      const generation = this.generation;
      this.mapContentTimer = this.clock.schedule(30000, () => {
        if (!this.active(generation) || this.state.mapContentStatus === MapContentStatus.Loaded) return;
        this.mapContentTimer = -1;
        this.state.mapContentStatus = MapContentStatus.Unconfirmed;
        this.publish();
      });
    }
    this.transition(NavigationPhase.MapReady, '地图引擎已就绪，请获取当前位置');
  }

  private updateWarning(): void {
    this.state.warning = [this.routeWarning, this.sdkWarning]
      .filter((value: string) => value.length > 0).join('；');
  }

  private mapReadyDeadline(): void {
    this.clearDeadline();
    const generation = this.generation;
    this.timer = this.clock.schedule(30000, () => {
      if (!this.active(generation)) return;
      this.fail(this.mapViewCreated ?
        '地图引擎就绪超时：地图容器已创建，但未取得 AMap 实例，请重新加载并检查 SDK 日志' :
        '地图容器创建超时：尚未收到原生地图视图创建回调，请重新加载并检查页面挂载日志');
    });
  }

  private clearMapContentDeadline(): void {
    if (this.mapContentTimer >= 0) this.clock.cancel(this.mapContentTimer);
    this.mapContentTimer = -1;
  }

  private acceptLocation(location: NavigationLocation): void {
    if (this.exitingGuidance) return;
    if (!validPoint(location.point) || !Number.isFinite(location.accuracyMeters) ||
      location.accuracyMeters < 0 || location.accuracyMeters > LOCATION_MAX_ACCURACY_METERS ||
      !Number.isFinite(location.observedAt) || location.observedAt > this.clock.now() + 5000 ||
      this.clock.now() - location.observedAt > LOCATION_MAX_AGE_MS) return;
    if (this.state.phase === NavigationPhase.Locating) {
      this.clearDeadline();
      this.state.location = location;
      this.transition(NavigationPhase.LocationReady, '已取得当前位置，可以规划前往目的地的路线');
      this.resumePendingPlan();
    } else if (this.state.phase === NavigationPhase.LocationReady || this.state.phase === NavigationPhase.RouteReady ||
      this.state.phase === NavigationPhase.Planning || this.state.phase === NavigationPhase.Starting ||
      this.state.phase === NavigationPhase.Navigating) {
      this.state.location = location;
      this.publish();
    }
  }

  private resumePendingPlan(): void {
    // Publishing LocationReady may synchronously dispatch a newer shared draft.
    // Only resume if that callback has not already replaced/cancelled this request.
    const pendingPlan = this.pendingPlan;
    if (!pendingPlan || this.state.phase !== NavigationPhase.LocationReady) return;
    this.pendingPlan = undefined;
    this.plan(pendingPlan.destination, pendingPlan.waypoints, pendingPlan.travelMode);
  }

  private acceptRoute(route: NavigationRoute): void {
    if (this.recoveringRouteFailure || this.exitingGuidance) return;
    if (this.state.phase !== NavigationPhase.Planning && this.state.phase !== NavigationPhase.Navigating) return;
    if (!Number.isFinite(route.id) || !Number.isFinite(route.distanceMeters) || route.distanceMeters <= 0 ||
      !Number.isFinite(route.durationSeconds) || route.durationSeconds < 0) {
      this.fail('算路返回了无效路线，请重新加载地图');
      return;
    }
    this.state.route = route;
    if (this.state.phase === NavigationPhase.Navigating) {
      this.state.message = '路线已重新规划，继续导航';
      this.publish();
      return;
    }
    this.clearDeadline();
    this.transition(NavigationPhase.RouteReady, '路线已生成，请选择真实导航或模拟导航');
  }

  private acceptRouteFailure(message: string): void {
    if (this.recoveringRouteFailure || this.exitingGuidance) return;
    const reason = message || '算路失败，请处理问题后重新规划路线';
    if (this.state.phase === NavigationPhase.Starting || this.state.phase === NavigationPhase.Navigating) {
      this.fail(`${reason}；导航已停止，请处理问题后重新加载地图`);
      return;
    }
    // Late or duplicate callbacks must not discard an accepted route or start a retry.
    if (this.state.phase !== NavigationPhase.Planning) return;
    const generation = this.generation;
    this.recoveringRouteFailure = true;
    this.clearDeadline();
    this.pendingPlan = undefined;
    this.currentPlan = undefined;
    this.state.route = undefined;
    this.state.progress = undefined;
    this.state.progressCount = 0;
    this.state.simulation = false;
    this.routeWarning = reason;
    this.updateWarning();
    try {
      // Clear the failed preview before publishing the editable state. Native
      // cleanup may synchronously emit more SDK callbacks, which are ignored.
      this.engine.clearRoute();
    } catch (error) {
      this.recoveringRouteFailure = false;
      if (this.active(generation)) this.fail(`${reason}；清理旧路线失败：${(error as Error).message || '未知错误'}`);
      return;
    }
    this.recoveringRouteFailure = false;
    if (!this.active(generation)) return;
    // Publish last: the shared page may synchronously request location from MapReady.
    this.transition(this.state.location ? NavigationPhase.LocationReady : NavigationPhase.MapReady,
      '路线未生成，请处理提示后重新规划');
  }

  private fail(message: string): void {
    this.finish(NavigationPhase.Error, message || '导航发生错误，请重试');
  }

  private finishNavigation(phase: NavigationPhase, message: string): void {
    if (!this.initializationComplete || !this.mapReady || !this.state.mapVisible) {
      this.finish(phase, message);
      return;
    }
    this.pendingPlan = undefined;
    this.currentPlan = undefined;
    this.clearDeadline();
    this.clearMapContentDeadline();
    this.recoveringRouteFailure = true;
    // Set the terminal phase before stopping the SDK: cleanup can synchronously
    // report a final progress or arrival callback.
    this.state.phase = phase;
    const errors = this.engine.stopNavigation();
    this.recoveringRouteFailure = false;
    if (errors.length > 0) {
      this.finish(NavigationPhase.Error, `${message}；${errors.join('；')}`);
      return;
    }
    this.state.route = undefined;
    this.state.progress = undefined;
    this.state.progressCount = 0;
    this.state.simulation = false;
    this.sdkWarning = '';
    this.routeWarning = '';
    this.updateWarning();
    this.transition(phase, message);
  }

  private finish(phase: NavigationPhase, message: string): void {
    ++this.generation;
    this.pendingPlan = undefined;
    this.currentPlan = undefined;
    this.clearDeadline();
    this.clearMapContentDeadline();
    this.sdkWarning = '';
    this.routeWarning = '';
    this.recoveringRouteFailure = false;
    const cleanupErrors = this.engine.release();
    this.initializationComplete = false;
    this.mapReady = false;
    this.state.mapVisible = false;
    this.state.mapContentStatus = MapContentStatus.Unknown;
    this.state.location = undefined;
    this.state.route = undefined;
    this.state.warning = cleanupErrors.join('；');
    if (cleanupErrors.length > 0) message = '导航已停止，但部分资源清理失败，请退出页面后重试';
    this.transition(phase, message);
  }

  private deadline(milliseconds: number, message: string): void {
    this.clearDeadline();
    const generation = this.generation;
    this.timer = this.clock.schedule(milliseconds, () => {
      if (!this.active(generation)) return;
      if (this.state.phase === NavigationPhase.Locating) this.finishNavigation(NavigationPhase.Error, message);
      else this.fail(message);
    });
  }

  private clearDeadline(): void {
    if (this.timer >= 0) this.clock.cancel(this.timer);
    this.timer = -1;
  }

  private transition(phase: NavigationPhase, message: string): void {
    this.state.phase = phase;
    this.state.message = message;
    this.publish();
  }

  private publish(): void { this.observer(this.snapshot()); }
}
