/** AMap HarmonyOS navigation error codes, not HTTP / Web-service error codes. */
export function amapRouteErrorMessage(code: number): string {
  let reason: string;
  switch (code) {
    case 2: reason = '网络连接失败，请检查网络后重试'; break;
    case 3: reason = '起点无效，请重新获取当前位置'; break;
    case 6: reason = '终点无效，请重新选择目的地'; break;
    case 10: reason = '起点附近没有可通行道路，请移动到道路附近后重新定位'; break;
    case 11: reason = '终点附近没有可通行道路，请调整目的地'; break;
    case 12: reason = '途经点附近没有可通行道路，请调整途经点'; break;
    case 13: reason = '高德 Key 无效或已过期，请核对当前安装包配置'; break;
    case 16: reason = '当前 Key 无权使用此导航服务，请核对高德服务权限'; break;
    case 17: reason = '高德算路配额已用尽，请检查服务配额'; break;
    case 18: reason = '算路参数不合法，请检查起终点、途经点和出行方式'; break;
    case 20: case 26: reason = '路线距离超出当前出行方式支持范围，请选择较近的目的地'; break;
    case 21: reason = '途经点无效，请重新选择'; break;
    case 22: reason = '高德应用签名鉴权未通过，请核对当前安装包的 AppID 与 Key 绑定关系'; break;
    case 23: reason = '算路请求过于频繁，请稍后重试'; break;
    case 24:
      reason = '高德返回 Key 平台不匹配（USERKEY_PLAT_NOMATCH）。请在“我的 → 地图服务诊断”核对实际配置；若已确认是鸿蒙 Key，请结合 NaviRoute 详细日志核查高德导航鉴权';
      break;
    case 25: reason = '起点、终点或途经点不在服务范围，请检查坐标'; break;
    case 28: reason = '缺少有效导航路线，请先完成路线规划'; break;
    case 29: reason = '算路方式与当前导航类型不一致，请停止导航后重新规划'; break;
    case 2999: reason = '算路被新的请求替代，请等待当前请求完成'; break;
    default: reason = '高德未能生成路线，请查看 NaviRoute 详细日志后重试'; break;
  }
  return `算路失败（${code}）：${reason}`;
}

/** A short identifier for matching an installed app to its config; never the full key. */
export function maskedAmapKey(apiKey: string): string {
  const key = apiKey.trim();
  if (key.length === 0) return '未配置';
  if (key.length <= 12) return '已配置（已隐藏）';
  return `${key.substring(0, 4)}…${key.substring(key.length - 4)}`;
}

/** SDK details may contain request URLs, keys or headers. Redact before logging. */
export function redactAmapDiagnostic(value: string, apiKey: string): string {
  let result = value;
  if (apiKey.length > 0) {
    result = result.split(apiKey).join('[redacted]');
    result = result.split(encodeURIComponent(apiKey)).join('[redacted]');
  }
  result = result.replace(/Bearer\s+[^\s,;"']+/gi, 'Bearer [redacted]');
  result = result.replace(/(["']?(?:key|appkey|apikey|api_key|token|access_token|authorization|scode|signature)["']?\s*[:=]\s*)("[^"]*"|'[^']*'|[^\s,;&}]+)/gi, '$1[redacted]');
  result = result.replace(/\b[0-9a-f]{32}\b/gi, '[redacted]');
  return result.replace(/[\r\n\t]/g, ' ').substring(0, 1600);
}
