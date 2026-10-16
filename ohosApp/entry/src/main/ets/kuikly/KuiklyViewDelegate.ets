import {
  IKuiklyViewDelegate,
  KRRenderModuleExportCreator,
  KRRenderViewExportCreator
} from '@kuikly-open/render';
import { HarmonyNavigationSession } from '../navigation/platform/HarmonyNavigationSession';
import { KRNavigationMapView } from './components/KRNavigationMapView';
import { KRNavigationModule } from './modules/KRNavigationModule';
import { KRNavigationAgentModule } from './modules/KRNavigationAgentModule';

export class KuiklyViewDelegate extends IKuiklyViewDelegate {
  private session: HarmonyNavigationSession;
  constructor(session: HarmonyNavigationSession) { super(); this.session = session; }
  getCustomRenderViewCreatorRegisterMap(): Map<string, KRRenderViewExportCreator> {
    const map: Map<string, KRRenderViewExportCreator> = new Map();
    map.set(KRNavigationMapView.VIEW_NAME, () => new KRNavigationMapView(this.session));
    return map;
  }

  getCustomRenderModuleCreatorRegisterMap(): Map<string, KRRenderModuleExportCreator> {
    const map: Map<string, KRRenderModuleExportCreator> = new Map();
    map.set(KRNavigationModule.MODULE_NAME, () => new KRNavigationModule(this.session));
    map.set(KRNavigationAgentModule.MODULE_NAME, () => new KRNavigationAgentModule(this.session.getContext()));
    return map;
  }
}
