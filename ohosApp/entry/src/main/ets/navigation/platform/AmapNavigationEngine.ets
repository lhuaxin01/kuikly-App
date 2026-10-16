import { abilityAccessCtrl, common, Permissions } from '@kit.AbilityKit';
import { geoLocationManager } from '@kit.LocationKit';
import { ArrayList } from '@kit.ArkTS';
import hilog from '@ohos.hilog';
import { AMapPrivacyAgreeStatus, AMapPrivacyInfoStatus, AMapPrivacyShowStatus } from '@amap/amap_lbs_common';
import {
  AMap, AMapNaviFactory, CameraPosition, CameraUpdateFactory, IAMapCalcRouteResult, IAMapNavi, IAMapNaviListener,
  IAMapNaviLocation, INaviInfo, INaviLatLng, LatLng, LatLngBounds, MapView, MapViewCreateCallback,
  MapViewManager, MapsInitializer, Marker, MarkerOptions, NaviLatLng, NaviSetting,
  NaviType, PathPlanningStrategy, RouteOverlay, TravelStrategy
} from '@amap/amap_lbs_navi';
import { NavigationEngine, NavigationEngineListener } from '../domain/NavigationEngine';
import {
  GeoPoint, NavigationDestination, NavigationLocation, NavigationProgress, NavigationRoute, validPoint
} from '../domain/NavigationModels';
import { NavigationConfig } from './NavigationConfig';
import { amapRouteErrorMessage, maskedAmapKey, redactAmapDiagnostic } from './AmapRouteErrors';

/** Only this adapter imports the map/navigation SDK. Listener identities and SDK ownership live here. */
export class AmapNavigationEngine implements NavigationEngine {
  mapViewName: string = 'navi-map-idle';
  private context: common.UIAbilityContext;
  private config: NavigationConfig;
  private navi: IAMapNavi | undefined = undefined;
  private naviListener: IAMapNaviListener | undefined = undefined;
  private mapCallback: MapViewCreateCallback | undefined = undefined;
  private mapView: MapView | undefined = undefined;
  private map: AMap | undefined = undefined;
  private marker: Marker | undefined = undefined;
  private overlay: RouteOverlay | undefined = undefined;
  private generation: number = 0;
  private navigationGeneration: number = 0;
  private locationAttempt: number = 0;
  private listener: NavigationEngineListener | undefined = undefined;
  private routeGeneration: number = 0;
  private guiding: boolean = false;
  private starting: boolean = false;
  private simulation: boolean = false;
  private locating: boolean = false;
  private ownsInstance: boolean = false;
  private following: boolean = true;
  private recenterRequested: boolean = false;
  private lastPosition: GeoPoint | undefined = undefined;
  private lastRealPosition: GeoPoint | undefined = undefined;
  private routeBounds: LatLngBounds | undefined = undefined;
  private selectedRouteId: number | undefined = undefined;
  private travelMode: string = 'drive';
  private heading: number = 0;
  private hasHeading: boolean = false;
  private headingObservedAt: number = 0;
  private routeStartPosition: GeoPoint | undefined = undefined;
  private routeStartHeading: number | undefined = undefined;
  private initializedAt: number = 0;
  private routeAttempt: number = 0;
  private routePending: boolean = false;
  private routeFailureReported: boolean = false;
  private routeFailureTimer: number = -1;
  private onPoint: (point: GeoPoint) => void;

  constructor(context: common.UIAbilityContext, config: NavigationConfig, onPoint: (point: GeoPoint) => void = () => {}) {
    this.context = context;
    this.config = config;
    this.onPoint = onPoint;
  }

  async initialize(listener: NavigationEngineListener): Promise<void> {
    const generation = ++this.generation;
    this.initializedAt = Date.now();
    if (!this.config.apiKey) throw new Error('尚未配置高德鸿蒙 Key，请填写配置后重新打包');
    this.listener = listener;
    // Map startup follows the navigation page lifecycle. System location
    // authorization is requested separately, only when obtaining a location.
    const appContext = this.context.getApplicationContext();
    NaviSetting.updatePrivacyShow(AMapPrivacyShowStatus.DidShow, AMapPrivacyInfoStatus.DidContain, appContext);
    NaviSetting.updatePrivacyAgree(AMapPrivacyAgreeStatus.DidAgree, appContext);
    MapsInitializer.updatePrivacyShow(AMapPrivacyShowStatus.DidShow, AMapPrivacyInfoStatus.DidContain, appContext);
    MapsInitializer.updatePrivacyAgree(AMapPrivacyAgreeStatus.DidAgree, appContext);
    MapsInitializer.setApiKey(this.config.apiKey);
    MapsInitializer.initialize(appContext);
    this.navi = AMapNaviFactory.getAMapNaviInstance(appContext, this.config.apiKey, false);
    this.ownsInstance = true;
    hilog.info(0x0000, 'NaviRoute', 'sdk=%{public}s config=%{public}s keyId=%{public}s',
      this.navi.getVersion(), this.config.source, maskedAmapKey(this.config.apiKey));
    this.navi.stopGPS();
    this.navi.setUseInnerVoice(false);
    this.mapViewName = `navi-map-${Date.now()}-${generation}`;
    this.traceMap('sdk_initialized');
    const viewName = this.mapViewName;
    this.mapCallback = (view?: MapView, name?: string) => {
      if (!view || generation !== this.generation || this.mapView) return;
      // Some SDK callbacks omit the optional name; the view still owns its identity.
      if ((name ?? view.getMapViewName()) !== viewName) return;
      this.mapView = view;
      this.traceMap('map_view_created');
      listener.onMapViewCreated();
      if (generation !== this.generation) return;
      try {
        view.onCreate();
        view.onResume();
        view.getMapAsync((map: AMap) => {
          if (generation !== this.generation || this.map) return;
          this.map = map;
          try {
            // Install the first-frame listener before moving the camera. A ready
            // AMap object and loaded network tiles are separate milestones.
            let contentLoaded = false;
            map.setOnMapLoadedListener(() => {
              if (generation !== this.generation || contentLoaded) return;
              contentLoaded = true;
              this.traceMap('map_content_loaded');
              listener.onMapContentLoaded();
            });
            map.setOnMapLongClickListener((point: LatLng) => {
              if (generation === this.generation && !this.guiding) this.onPoint(new GeoPoint(point.latitude, point.longitude));
            });
            map.getUiSettings()?.setZoomControlsEnabled(true);
            this.updateCameraGestures();
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(
              this.config.destination.point.latitude, this.config.destination.point.longitude), 13));
            this.traceMap('map_controller_ready');
            listener.onMapReady();
          } catch (error) {
            if (generation === this.generation) listener.onFailure(this.mapError('地图对象配置失败', error as Error));
          }
        });
      } catch (error) {
        if (generation === this.generation) listener.onFailure(this.mapError('地图视图初始化失败', error as Error));
      }
    };
    MapViewManager.getInstance().registerMapViewCreatedCallback(this.mapCallback);
    this.ensureNavigationListener();
  }

  private ensureNavigationListener(): void {
    const listener = this.listener;
    if (this.naviListener || !this.navi || !listener) return;
    const generation = this.generation;
    const navigationGeneration = ++this.navigationGeneration;
    const active = (): boolean => generation === this.generation && navigationGeneration === this.navigationGeneration;
    this.naviListener = {
      onInitNaviFailure: () => {
        if (active()) listener.onFailure('导航 SDK 初始化失败，请核对鸿蒙 Key 和签名 AppID');
      },
      onLocationChange: (location: IAMapNaviLocation | null) => {
        if (!active() || !location?.coord || (!this.locating && !this.guiding)) return;
        const point = new GeoPoint(location.coord.latitude, location.coord.longitude);
        if (!validPoint(point)) return;
        if (this.simulation) {
          this.showPosition(point, true, location);
          return; // Simulator coordinates must never become the next real-world origin.
        }
        const update: NavigationLocation = {
          point, accuracyMeters: location.accuracy, observedAt: Date.now()
        };
        listener.onLocation(update);
        if (location.accuracy >= 0 && location.accuracy <= 100) {
          this.lastRealPosition = point;
          this.showPosition(point, this.guiding, location);
        }
      },
      onCalculateRouteSuccess: (result: IAMapCalcRouteResult | null) => {
        if (active() && (this.routePending || this.guiding) && !this.routeFailureReported) {
          this.clearRouteFailureTimer();
          this.acceptRoute(result, listener, generation).catch(() => {
            if (active()) listener.onFailure('读取路线失败，请重新加载地图');
          });
        }
      },
      onCalculateRouteFailureForResult: (result: IAMapCalcRouteResult | null) => {
        if (!active()) return;
        this.receiveRouteFailure(result?.errorCode ?? -1, result?.errorDescription ?? '',
          result?.errorDetail ?? '', result?.calcRouteType ?? -1, true, listener, generation);
      },
      onCalculateRouteFailure: (code: number) => {
        if (!active()) return;
        this.receiveRouteFailure(code, '', '', -1, false, listener, generation);
      },
      onStartNavi: () => {
        if (!active() || !this.starting) return;
        this.starting = false;
        this.guiding = true;
        // A route preview is normally zoomed out. Lock onto the last good fix
        // immediately, rather than waiting for the next GPS/simulator callback.
        this.focusPosition(true);
        listener.onStarted();
      },
      onNaviInfoUpdate: (info: INaviInfo) => {
        if (!active() || !this.guiding) return;
        const progress: NavigationProgress = {
          remainingMeters: info.mRouteRemainDis, remainingSeconds: info.mRouteRemainTime,
          nextTurnMeters: info.mSegRemainDis, currentRoad: info.mCurRoadName ?? '',
          nextRoad: info.mNextRoadName ?? '', receivedAt: Date.now()
        };
        listener.onProgress(progress);
      },
      onArriveDestination: () => { if (active() && this.guiding) listener.onArrived(); },
      onEndEmulatorNavi: () => { if (active() && this.guiding) listener.onArrived(); },
      onGpsOpenStatus: (enabled: boolean) => {
        if (active() && !enabled && !this.simulation && (this.locating || this.guiding)) {
          if (this.guiding) listener.onFailure('系统定位已关闭，请开启定位后重试');
          else listener.onLocationFailure('系统定位已关闭，请开启定位后重试');
        }
      },
      onGpsSignalWeak: (weak: boolean) => {
        if (active() && !this.simulation) listener.onWarning(weak ? '卫星信号较弱，进度可能暂时不更新' : '');
      }
    };
    this.navi.addAMapNaviListener(this.naviListener);
  }

  locate(): void {
    if (!this.navi || !this.map) throw new Error('地图尚未就绪');
    const generation = this.generation;
    const attempt = ++this.locationAttempt;
    this.ensureNavigationListener();
    this.requestLocationPermission(generation, attempt).catch((error: Error) => {
      if (generation === this.generation && attempt === this.locationAttempt) {
        this.listener?.onLocationFailure(error.message || '获取定位权限失败，请在系统设置中检查授权');
      }
    });
  }

  private async requestLocationPermission(generation: number, attempt: number): Promise<void> {
    const permissions: Permissions[] = ['ohos.permission.APPROXIMATELY_LOCATION', 'ohos.permission.LOCATION'];
    const result = await abilityAccessCtrl.createAtManager().requestPermissionsFromUser(this.context, permissions);
    if (generation !== this.generation || attempt !== this.locationAttempt || !this.navi) return;
    if (result.authResults.length !== 2 || result.authResults.some((code: number) => code !== 0)) {
      throw new Error('需要精确定位权限；地图仍可浏览，请在系统设置中授权后重试定位');
    }
    if (!geoLocationManager.isLocationEnabled()) throw new Error('系统定位未开启，请开启后重试');
    this.locating = true;
    this.recenterRequested = true;
    if (!this.navi.startGPS(1, 0)) throw new Error('无法启动定位，请检查定位权限');
  }

  clearRoute(): void {
    ++this.routeGeneration;
    ++this.routeAttempt;
    this.routePending = false;
    this.clearRouteFailureTimer();
    this.overlay?.removeFromMap();
    this.overlay?.destroy();
    this.overlay = undefined;
    this.routeBounds = undefined;
    this.selectedRouteId = undefined;
    this.routeStartPosition = undefined;
    this.routeStartHeading = undefined;
  }

  setOverview(overview: boolean): void {
    this.following = !overview;
    this.updateCameraGestures();
    const map = this.map;
    if (!map) return;
    if (overview) {
      const bounds = this.routeBounds ?? this.navi?.getNaviPath()?.getBoundsForPath();
      if (bounds) this.showRouteOverview(bounds);
    } else {
      // MapTemplate's recoverLockView also recenters immediately. Switching a
      // boolean alone leaves a stationary user stuck in the overview.
      this.focusPosition(this.guiding || this.starting);
    }
  }

  plan(origin: NavigationLocation, destination: NavigationDestination, waypoints: GeoPoint[] = [], travelMode: string = 'drive'): void {
    if (!this.navi || !this.map) throw new Error('导航引擎尚未就绪');
    this.ensureNavigationListener();
    const engine = this.navi;
    this.travelMode = travelMode;
    this.lastRealPosition = origin.point;
    this.lastPosition = origin.point;
    ++this.routeAttempt;
    ++this.routeGeneration;
    this.routePending = true;
    this.routeFailureReported = false;
    this.clearRouteFailureTimer();
    const from = new ArrayList<INaviLatLng>();
    const to = new ArrayList<INaviLatLng>();
    const via = new ArrayList<INaviLatLng>();
    from.add(new NaviLatLng(origin.point.latitude, origin.point.longitude));
    to.add(new NaviLatLng(destination.point.latitude, destination.point.longitude));
    waypoints.forEach((point: GeoPoint) => via.add(new NaviLatLng(point.latitude, point.longitude)));
    // The SDK recommends the modern default. acceptRoute selects one returned
    // route ID so the preview and started route remain the same.
    const sdkWaypoints = waypoints.length > 0 ? via : null;
    const strategy = travelMode === 'drive' ? PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT : TravelStrategy.SINGLE;
    hilog.info(0x0000, 'NaviRoute', 'attempt=%{public}d mode=%{public}s strategy=%{public}d waypoints=%{public}d',
      this.routeAttempt, travelMode, strategy, waypoints.length);
    let accepted = false;
    if (travelMode === 'walk' || travelMode === 'ride') {
      // Harmony AMap exposes one shared API for walking and cycling. The
      // transport type is 3 for walking and 2 for bicycle riding.
      engine.setIsNaviTravelView(true);
      accepted = engine.calculateWalkRideRouteLatLng(from, to, sdkWaypoints,
        strategy, travelMode === 'walk' ? 3 : 2);
    } else {
      engine.setIsNaviTravelView(false);
      accepted = engine.calculateDriveRoute(from, to, sdkWaypoints, strategy);
    }
    // A synchronous detailed callback already reports the actionable SDK error.
    if (!accepted && !this.routeFailureReported && this.routeFailureTimer < 0) {
      this.routePending = false;
      throw new Error('算路请求未受理，请重新加载地图');
    }
  }

  start(simulation: boolean): void {
    if (!this.navi) throw new Error('导航引擎尚未就绪');
    this.ensureNavigationListener();
    const selectedRouteId = this.selectedRouteId;
    if (selectedRouteId !== undefined && !this.navi.selectRouteId(selectedRouteId)) {
      throw new Error('无法恢复已规划路线，请重新规划');
    }
    this.starting = true;
    this.simulation = simulation;
    this.following = true;
    this.recenterRequested = false;
    // Keep a recent measured course. Otherwise only use the first route
    // segment while near its start; a destination bearing is not a road course.
    if (simulation || !this.hasHeading || Date.now() - this.headingObservedAt > 15000) {
      this.hasHeading = false;
      const point = this.lastPosition;
      this.heading = point && this.routeStartPosition && this.routeStartHeading !== undefined &&
        this.distanceMeters(point, this.routeStartPosition) <= 50 ? this.routeStartHeading :
        (this.map?.getCameraPosition()?.bearing ?? 0);
    }
    this.updateCameraGestures();
    this.navi.setUseInnerVoice(true, true);
    if (simulation) this.navi.stopGPS();
    // IAMapNavi 11.2 explicitly guarantees that startNavi(GPS) starts satellite
    // positioning, including after exitGuidance stopped it. Let this result
    // report startup failure rather than opening a second GPS request here.
    if (!this.navi.startNavi(simulation ? NaviType.EMULATOR : NaviType.GPS)) {
      this.starting = false;
      this.updateCameraGestures();
      throw new Error('导航启动失败');
    }
  }

  exitGuidance(): string[] {
    // Invalidate SDK callbacks and an in-flight automatic reroute before
    // stopping: some SDK versions deliver their final callbacks synchronously.
    ++this.navigationGeneration;
    ++this.locationAttempt;
    ++this.routeGeneration;
    ++this.routeAttempt;
    this.routePending = false;
    this.routeFailureReported = false;
    this.clearRouteFailureTimer();
    this.guiding = false;
    this.starting = false;
    this.locating = false;
    this.simulation = false;
    this.following = false;
    this.recenterRequested = false;
    this.hasHeading = false;
    this.heading = 0;
    const errors: string[] = [];
    this.cleanup(() => this.updateCameraGestures(), '恢复地图手势', errors);
    const engine = this.navi;
    const listener = this.naviListener;
    this.naviListener = undefined;
    if (engine && listener) this.cleanup(() => engine.removeAMapNaviListener(listener), '移除导航监听', errors);
    if (engine) {
      this.cleanup(() => engine.stopNavi(), '停止导航', errors);
      this.cleanup(() => { engine.stopGPS(); }, '停止定位', errors);
      this.cleanup(() => engine.setUseInnerVoice(false), '停止语音', errors);
    }
    // An emulator fix belongs only to the just-finished guidance. Keep the
    // confirmed real position for preview and a later real navigation start.
    this.lastPosition = this.lastRealPosition;
    const realPosition = this.lastRealPosition;
    if (realPosition) this.cleanup(() => this.showPosition(realPosition, false), '恢复当前位置', errors);
    this.cleanup(() => this.setOverview(true), '恢复路线全览', errors);
    return errors;
  }

  stopNavigation(): string[] {
    ++this.navigationGeneration;
    ++this.locationAttempt;
    this.guiding = false;
    this.starting = false;
    this.locating = false;
    this.simulation = false;
    this.following = true;
    this.lastPosition = this.lastRealPosition;
    this.hasHeading = false;
    this.recenterRequested = false;
    this.routeFailureReported = false;
    const errors: string[] = [];
    this.cleanup(() => this.updateCameraGestures(), '恢复地图手势', errors);
    const engine = this.navi;
    const listener = this.naviListener;
    this.naviListener = undefined;
    if (engine && listener) this.cleanup(() => engine.removeAMapNaviListener(listener), '移除导航监听', errors);
    if (engine) {
      this.cleanup(() => engine.stopNavi(), '停止导航', errors);
      this.cleanup(() => { engine.stopGPS(); }, '停止定位', errors);
      this.cleanup(() => engine.setUseInnerVoice(false), '停止语音', errors);
    }
    this.cleanup(() => this.clearRoute(), '清理路线', errors);
    return errors;
  }

  release(): string[] {
    this.traceMap('release');
    ++this.generation;
    ++this.navigationGeneration;
    ++this.locationAttempt;
    ++this.routeGeneration;
    ++this.routeAttempt;
    this.routePending = false;
    this.routeFailureReported = false;
    this.clearRouteFailureTimer();
    this.guiding = false;
    this.starting = false;
    this.locating = false;
    this.simulation = false;
    this.recenterRequested = false;
    const errors: string[] = [];
    const engine = this.navi;
    const naviListener = this.naviListener;
    const mapCallback = this.mapCallback;
    const overlay = this.overlay;
    const view = this.mapView;
    this.navi = undefined;
    this.listener = undefined;
    this.naviListener = undefined;
    this.mapCallback = undefined;
    this.overlay = undefined;
    this.mapView = undefined;
    this.map = undefined;
    this.marker = undefined;
    this.lastPosition = undefined;
    this.lastRealPosition = undefined;
    this.routeBounds = undefined;
    this.selectedRouteId = undefined;
    this.routeStartPosition = undefined;
    this.routeStartHeading = undefined;
    this.hasHeading = false;
    // Attempt every cleanup even if a previous SDK call throws.
    if (engine && naviListener) this.cleanup(() => engine.removeAMapNaviListener(naviListener), '移除导航监听', errors);
    if (mapCallback) this.cleanup(() => MapViewManager.getInstance().unregisterMapViewCreatedCallback(mapCallback), '移除地图监听', errors);
    if (engine) {
      this.cleanup(() => engine.stopNavi(), '停止导航', errors);
      this.cleanup(() => { engine.stopGPS(); }, '停止定位', errors);
      this.cleanup(() => engine.setUseInnerVoice(false), '停止语音', errors);
    }
    if (overlay) {
      this.cleanup(() => overlay.removeFromMap(), '移除路线', errors);
      this.cleanup(() => overlay.destroy(), '释放路线', errors);
    }
    if (view) {
      this.cleanup(() => view.onPause(), '暂停地图', errors);
      this.cleanup(() => view.onDestroy(), '释放地图', errors);
    }
    if (this.ownsInstance) {
      this.ownsInstance = false;
      this.cleanup(() => AMapNaviFactory.destroyAMapNaviInstance(), '释放导航引擎', errors);
    }
    return errors;
  }

  private traceMap(stage: string): void {
    hilog.info(0x0000, 'NaviMap', 'stage=%{public}s view=%{public}s elapsedMs=%{public}d',
      stage, this.mapViewName, this.initializedAt > 0 ? Date.now() - this.initializedAt : 0);
  }

  private mapError(stage: string, error: Error): string {
    const detail = redactAmapDiagnostic(error.message || 'SDK 未提供错误详情', this.config.apiKey);
    hilog.error(0x0000, 'NaviMap', '%{public}s: %{public}s', stage, detail);
    return `${stage}：${detail}`;
  }

  private receiveRouteFailure(code: number, description: string, detail: string, calcRouteType: number,
    detailed: boolean, listener: NavigationEngineListener, generation: number): void {
    if (generation !== this.generation || (!this.routePending && !this.guiding && !this.routeFailureReported)) return;
    // The legacy callback may arrive first. Keep the rich callback for diagnostics
    // instead of releasing the SDK immediately and losing errorDetail.
    hilog.error(0x0000, 'NaviRoute', 'attempt=%{public}d code=%{public}d type=%{public}d description=%{public}s detail=%{public}s',
      this.routeAttempt, code, calcRouteType, redactAmapDiagnostic(description, this.config.apiKey),
      redactAmapDiagnostic(detail, this.config.apiKey));
    if (this.routeFailureReported) return;
    if (detailed) {
      this.clearRouteFailureTimer();
      this.reportRouteFailure(code, listener);
    } else if (this.routeFailureTimer < 0) {
      const attempt = this.routeAttempt;
      this.routeFailureTimer = setTimeout(() => {
        this.routeFailureTimer = -1;
        if (generation === this.generation && attempt === this.routeAttempt && !this.routeFailureReported) {
          this.reportRouteFailure(code, listener);
        }
      }, 50);
    }
  }

  private reportRouteFailure(code: number, listener: NavigationEngineListener): void {
    ++this.routeGeneration;
    this.routeFailureReported = true;
    this.routePending = false;
    listener.onRouteFailure(amapRouteErrorMessage(code));
  }

  private clearRouteFailureTimer(): void {
    if (this.routeFailureTimer >= 0) clearTimeout(this.routeFailureTimer);
    this.routeFailureTimer = -1;
  }

  private async acceptRoute(result: IAMapCalcRouteResult | null, listener: NavigationEngineListener,
    generation: number): Promise<void> {
    const map = this.map;
    const engine = this.navi;
    const routeIds = result?.routeId;
    if (!result || result.errorCode !== 0 || !routeIds || routeIds.length === 0 || !map || !engine) {
      listener.onFailure('算路结果为空，请重试');
      return;
    }
    const id = routeIds[0];
    // During guidance the SDK already owns the active reroute; selectRouteId is a pre-navigation API.
    if (!this.guiding && !engine.selectRouteId(id)) {
      listener.onFailure('无法选中已规划路线');
      return;
    }
    const path = engine.getNaviPath();
    if (!path) { listener.onFailure('未取得路线详情'); return; }
    const route: NavigationRoute = { id, distanceMeters: path.getAllLength(), durationSeconds: path.getAllTime() };
    const routeGeneration = ++this.routeGeneration;
    const overlay = new RouteOverlay(this.context, map, path);
    try {
      await overlay.build();
      if (generation !== this.generation || routeGeneration !== this.routeGeneration) {
        overlay.destroy();
        return;
      }
      this.overlay?.removeFromMap();
      this.overlay?.destroy();
      this.overlay = overlay;
      overlay.setTrafficLine(true);
      overlay.addToMap();
      const bounds = path.getBoundsForPath();
      this.routeBounds = bounds ?? undefined;
      this.selectedRouteId = id;
      this.rememberRouteStart(path.getCoordList());
      if (bounds && !this.guiding) this.showRouteOverview(bounds);
      this.routePending = false;
      listener.onRoute(route);
    } catch (_) {
      const cleanupErrors: string[] = [];
      this.cleanup(() => overlay.destroy(), '释放路线', cleanupErrors);
      if (generation === this.generation && routeGeneration === this.routeGeneration) {
        listener.onFailure('路线绘制失败，请重新加载地图');
      }
    }
  }

  private showPosition(point: GeoPoint, follow: boolean, location?: IAMapNaviLocation): void {
    const map = this.map;
    if (!map) return;
    this.lastPosition = point;
    if (location) this.updateHeading(location);
    const position = new LatLng(point.latitude, point.longitude);
    const firstPosition = !this.marker;
    if (!this.marker) {
      this.marker = map.addMarker(new MarkerOptions().setPosition(position).setTitle('当前位置'));
    } else {
      this.marker.setPosition(position);
    }
    if (this.recenterRequested || (follow && this.following) || (firstPosition && !this.overlay && !this.guiding)) {
      this.focusPosition(follow);
    }
    // A location button press recenters once; later GPS fixes must not hide the
    // route overview while the user is reviewing a calculated route.
    this.recenterRequested = false;
  }

  private updateHeading(location: IAMapNaviLocation): void {
    const bearing = location.bearing;
    if (!Number.isFinite(bearing) || bearing < 0 || bearing > 360) return;
    // AMapNaviLocation speed is km/h, not m/s. Do not trust the first bearing
    // while stationary, a network fix, or a coarse GPS fix. Walking needs a
    // lower threshold than driving/cycling so a normal walk can turn the map.
    const minimumSpeed = this.travelMode === 'walk' ? 2 : 3;
    if (!this.simulation && (location.locationType !== 0 || !Number.isFinite(location.speed) ||
      location.speed < minimumSpeed || !Number.isFinite(location.accuracy) ||
      location.accuracy < 0 || location.accuracy > 30)) return;
    const next = bearing % 360;
    const delta = ((next - this.heading + 540) % 360) - 180;
    // Camera animation already smooths the transition. Mixing old/new
    // headings again leaves the road diagonally behind a genuine turn.
    if (!this.hasHeading || Math.abs(delta) >= 3) this.heading = next;
    this.hasHeading = true;
    this.headingObservedAt = Date.now();
  }

  private rememberRouteStart(points: ArrayList<NaviLatLng> | null): void {
    this.routeStartPosition = undefined;
    this.routeStartHeading = undefined;
    if (!points) return;
    for (let index = 0; index < points.length; index++) {
      const coordinate: NaviLatLng = points[index];
      const point = new GeoPoint(coordinate.latitude, coordinate.longitude);
      if (!validPoint(point)) continue;
      const start = this.routeStartPosition;
      if (!start) {
        this.routeStartPosition = point;
      } else if (this.distanceMeters(start, point) >= 5) {
        const latitude1 = start.latitude * Math.PI / 180;
        const latitude2 = point.latitude * Math.PI / 180;
        const longitudeDelta = (point.longitude - start.longitude) * Math.PI / 180;
        const y = Math.sin(longitudeDelta) * Math.cos(latitude2);
        const x = Math.cos(latitude1) * Math.sin(latitude2) -
          Math.sin(latitude1) * Math.cos(latitude2) * Math.cos(longitudeDelta);
        this.routeStartHeading = (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
        return;
      }
    }
  }

  private distanceMeters(first: GeoPoint, second: GeoPoint): number {
    const latitudeDelta = (second.latitude - first.latitude) * Math.PI / 180;
    const longitudeDelta = (second.longitude - first.longitude) * Math.PI / 180;
    const x = longitudeDelta * Math.cos((first.latitude + second.latitude) * Math.PI / 360);
    return Math.sqrt(x * x + latitudeDelta * latitudeDelta) * 6371000;
  }

  private updateCameraGestures(): void {
    const settings = this.map?.getUiSettings();
    const locked = this.following && (this.guiding || this.starting);
    settings?.setRotateGesturesEnabled(!locked);
    settings?.setTiltGesturesEnabled(!locked);
  }

  private showRouteOverview(bounds: LatLngBounds): void {
    const map = this.map;
    if (!map) return;
    const camera = map.getCameraPosition();
    if (camera) map.moveCamera(CameraUpdateFactory.newCameraPosition(
      new CameraPosition(camera.target, camera.zoom, 0, 0)));
    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80));
  }

  private focusPosition(guidance: boolean): void {
    const map = this.map;
    const point = this.lastPosition;
    if (!map || !point) return;
    // The reference locks guidance onto a street-level fix. Harmony uses a
    // plain MapView, so we explicitly supply the camera that NaviView owns on
    // Android. Walking keeps an overhead view; driving/cycling show ahead.
    const zoom = !guidance ? 16 : (this.travelMode === 'walk' ? 18 : this.travelMode === 'ride' ? 17.7 : 17.5);
    const tilt = !guidance || this.travelMode === 'walk' ? 0 : (this.travelMode === 'ride' ? 35 : 45);
    const camera = new CameraPosition(new LatLng(point.latitude, point.longitude), zoom, tilt,
      guidance ? this.heading : 0);
    // Harmony 11.2 signature is (update, callback?, durationMs), unlike Android.
    map.animateCamera(CameraUpdateFactory.newCameraPosition(camera), undefined, 450);
  }

  private cleanup(action: () => void, label: string, errors: string[]): void {
    try { action(); } catch (_) { errors.push(`${label}失败`); }
  }
}
