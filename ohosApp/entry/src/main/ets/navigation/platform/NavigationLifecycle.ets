import { NavigationController } from '../domain/NavigationController';

/** This milestone owns a foreground session only. No singleton SDK can survive its page/ability. */
export class NavigationLifecycle {
  private static current: NavigationController | undefined = undefined;
  static bind(controller: NavigationController): void {
    if (NavigationLifecycle.current !== controller) NavigationLifecycle.current?.dispose();
    NavigationLifecycle.current = controller;
  }
  static stop(reason: string): void { NavigationLifecycle.current?.stop(reason); }
  static unbind(controller: NavigationController): void {
    if (NavigationLifecycle.current === controller) NavigationLifecycle.current = undefined;
  }
  static dispose(): void {
    NavigationLifecycle.current?.dispose();
    NavigationLifecycle.current = undefined;
  }
}
