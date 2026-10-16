import { NavigationClock } from '../domain/NavigationEngine';

export class HarmonyNavigationClock implements NavigationClock {
  now(): number { return Date.now(); }
  schedule(milliseconds: number, action: () => void): number { return setTimeout(action, milliseconds); }
  cancel(id: number): void { clearTimeout(id); }
}
