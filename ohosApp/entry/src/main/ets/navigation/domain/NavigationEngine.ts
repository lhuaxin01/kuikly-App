import { GeoPoint, NavigationDestination, NavigationLocation, NavigationProgress, NavigationRoute } from './NavigationModels';

export interface NavigationEngineListener {
  /** The native map container exists; this does not imply SDK or tile readiness. */
  onMapViewCreated(): void;
  /** getMapAsync has supplied AMap and its listeners have been installed. */
  onMapReady(): void;
  /** The SDK has reported completion of its initial base-map content load. */
  onMapContentLoaded(): void;
  onLocation(location: NavigationLocation): void;
  /** Location permission/GPS failure does not make the already mounted map unusable. */
  onLocationFailure(message: string): void;
  onRoute(route: NavigationRoute): void;
  /** A route request was rejected; a preview failure can keep the map and GPS alive. */
  onRouteFailure(message: string): void;
  onStarted(): void;
  onProgress(progress: NavigationProgress): void;
  onArrived(): void;
  onFailure(message: string): void;
  onWarning(message: string): void;
  onExit(): void;
}

export interface NavigationEngine {
  initialize(listener: NavigationEngineListener): Promise<void>;
  locate(): void;
  /** Calculate a route using the mode selected by the shared Kuikly draft. */
  plan(origin: NavigationLocation, destination: NavigationDestination, waypoints?: GeoPoint[], travelMode?: string): void;
  clearRoute(): void;
  start(simulation: boolean): void;
  /** Leave guidance while retaining its calculated route, live map and preview. */
  exitGuidance(): string[];
  /** Stop guidance, voice and GPS while retaining the base map and SDK instance. */
  stopNavigation(): string[];
  // Must invalidate callbacks before removing listeners, stopping GPS, and destroying the engine.
  release(): string[];
}

export interface NavigationClock {
  now(): number;
  schedule(milliseconds: number, action: () => void): number;
  cancel(id: number): void;
}
