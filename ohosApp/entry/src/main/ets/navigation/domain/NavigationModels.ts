// Provider-independent types. All coordinates crossing this boundary are GCJ-02.
export enum NavigationPhase {
  Idle = 'idle', Initializing = 'initializing', LoadingMap = 'loading_map', MapReady = 'map_ready',
  Locating = 'locating', LocationReady = 'location_ready', Planning = 'planning', RouteReady = 'route_ready',
  Starting = 'starting', Navigating = 'navigating', Arrived = 'arrived', Stopped = 'stopped',
  Error = 'error', Disposed = 'disposed'
}

/** SDK callback observation only; absence is not evidence of a blank or failed map. */
export enum MapContentStatus {
  Unknown = 'unknown', Waiting = 'waiting', Loaded = 'loaded', Unconfirmed = 'unconfirmed'
}

export class GeoPoint {
  latitude: number;
  longitude: number;
  constructor(latitude: number, longitude: number) {
    this.latitude = latitude;
    this.longitude = longitude;
  }
}

export class NavigationDestination {
  name: string;
  point: GeoPoint;
  constructor(name: string, point: GeoPoint) {
    this.name = name;
    this.point = point;
  }
}

export interface NavigationLocation {
  point: GeoPoint;
  accuracyMeters: number;
  observedAt: number;
}

export interface NavigationRoute {
  id: number;
  distanceMeters: number;
  durationSeconds: number;
}

export interface NavigationProgress {
  remainingMeters: number;
  remainingSeconds: number;
  nextTurnMeters: number;
  currentRoad: string;
  nextRoad: string;
  receivedAt: number;
}

export class NavigationSnapshot {
  phase: NavigationPhase = NavigationPhase.Idle;
  message: string = '正在准备地图';
  warning: string = '';
  mapVisible: boolean = false;
  mapContentStatus: MapContentStatus = MapContentStatus.Unknown;
  location: NavigationLocation | undefined = undefined;
  route: NavigationRoute | undefined = undefined;
  progress: NavigationProgress | undefined = undefined;
  progressCount: number = 0;
  simulation: boolean = false;
}

export function validPoint(point: GeoPoint): boolean {
  return Number.isFinite(point.latitude) && Math.abs(point.latitude) <= 90 &&
    Number.isFinite(point.longitude) && Math.abs(point.longitude) <= 180;
}

export function phaseLabel(phase: NavigationPhase): string {
  switch (phase) {
    case NavigationPhase.Idle: return '尚未开始';
    case NavigationPhase.Initializing: return '正在初始化';
    case NavigationPhase.LoadingMap: return '正在加载地图';
    case NavigationPhase.MapReady: return '地图已就绪';
    case NavigationPhase.Locating: return '正在定位';
    case NavigationPhase.LocationReady: return '已取得当前位置';
    case NavigationPhase.Planning: return '正在规划路线';
    case NavigationPhase.RouteReady: return '路线已就绪';
    case NavigationPhase.Starting: return '正在启动导航';
    case NavigationPhase.Navigating: return '导航进行中';
    case NavigationPhase.Arrived: return '已到达终点';
    case NavigationPhase.Stopped: return '已停止';
    case NavigationPhase.Error: return '需要处理';
    case NavigationPhase.Disposed: return '资源已释放';
  }
}
