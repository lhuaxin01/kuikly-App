import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import { GeoPoint, NavigationDestination, validPoint } from '../domain/NavigationModels';

interface DestinationConfig {
  name: string;
  latitude: number;
  longitude: number;
}

interface ConfigFile {
  amapHarmonyApiKey: string;
  fixedDestination: DestinationConfig;
}

export class NavigationConfig {
  apiKey: string = '';
  source: string = 'navigation_config.json';
  destination: NavigationDestination = new NavigationDestination('示例终点', new GeoPoint(39.992873, 116.310918));

  static load(context: common.UIAbilityContext): NavigationConfig {
    let bytes: Uint8Array;
    let source = 'navigation.local.json';
    try {
      bytes = context.resourceManager.getRawFileContentSync('navigation.local.json');
    } catch (_) {
      source = 'navigation_config.json';
      bytes = context.resourceManager.getRawFileContentSync('navigation_config.json');
    }
    const text = util.TextDecoder.create('utf-8').decodeWithStream(bytes);
    const file = JSON.parse(text) as ConfigFile;
    if (typeof file.amapHarmonyApiKey !== 'string' || !file.fixedDestination ||
      typeof file.fixedDestination.name !== 'string') throw new Error('导航配置文件格式错误');
    const config = new NavigationConfig();
    config.source = source;
    config.apiKey = file.amapHarmonyApiKey.trim();
    config.destination = new NavigationDestination(file.fixedDestination.name,
      new GeoPoint(file.fixedDestination.latitude, file.fixedDestination.longitude));
    if (!config.destination.name.trim() || !validPoint(config.destination.point)) {
      throw new Error('固定终点配置无效，需要 GCJ-02 经纬度');
    }
    return config;
  }
}
