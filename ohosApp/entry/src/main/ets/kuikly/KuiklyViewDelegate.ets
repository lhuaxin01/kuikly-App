import {
  IKuiklyViewDelegate,
  KRRenderModuleExportCreator,
  KRRenderViewExportCreator
} from '@kuikly-open/render';
import { HarmonyNavigationSession } from '../navigation/platform/HarmonyNavigationSession';
import { KRNavigationMapView } from './components/KRNavigationMapView';
import { KRNavigationModule } from './modules/KRNavigationModule';
import { KRNavigationAgentModule } from './modules/KRNavigationAgentModule';
import { KRNavigationVoiceModule } from './modules/KRNavigationVoiceModule';
import { HarmonyVoiceInput } from '../navigation/platform/HarmonyVoiceInput';

export class KuiklyViewDelegate extends IKuiklyViewDelegate {
  private session: HarmonyNavigationSession;
  private voice: HarmonyVoiceInput;
  constructor(session: HarmonyNavigationSession) {
    super();
    this.session = session;
    this.voice = new HarmonyVoiceInput(session.getContext(), (active: boolean) => session.setVoiceInputActive(active));
  }
  cancelVoiceInput(): void { this.voice.cancel(); }
  disposeVoiceInput(): void { this.voice.dispose(); }
  getCustomRenderViewCreatorRegisterMap(): Map<string, KRRenderViewExportCreator> {
    const map: Map<string, KRRenderViewExportCreator> = new Map();
    map.set(KRNavigationMapView.VIEW_NAME, () => new KRNavigationMapView(this.session));
    return map;
  }

  getCustomRenderModuleCreatorRegisterMap(): Map<string, KRRenderModuleExportCreator> {
    const map: Map<string, KRRenderModuleExportCreator> = new Map();
    map.set(KRNavigationModule.MODULE_NAME, () => new KRNavigationModule(this.session));
    map.set(KRNavigationAgentModule.MODULE_NAME, () => new KRNavigationAgentModule(this.session.getContext()));
    map.set(KRNavigationVoiceModule.MODULE_NAME, () => new KRNavigationVoiceModule(this.voice));
    return map;
  }
}
