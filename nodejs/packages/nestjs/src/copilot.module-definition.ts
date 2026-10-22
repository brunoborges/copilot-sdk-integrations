import { ConfigurableModuleBuilder } from "@nestjs/common";
import type { CopilotModuleOptions } from "./interfaces.js";

export const {
  ConfigurableModuleClass: CopilotConfigurableModule,
  MODULE_OPTIONS_TOKEN: COPILOT_MODULE_OPTIONS,
  OPTIONS_TYPE: COPILOT_OPTIONS_TYPE,
  ASYNC_OPTIONS_TYPE: COPILOT_ASYNC_OPTIONS_TYPE,
} = new ConfigurableModuleBuilder<CopilotModuleOptions>({ moduleName: "Copilot" })
  .setClassMethodName("forRoot")
  .setExtras({ isGlobal: true }, (definition, extras) => ({ ...definition, global: extras.isGlobal }))
  .build();

export type CopilotModuleAsyncOptions = typeof COPILOT_ASYNC_OPTIONS_TYPE;
export type CopilotModuleRootOptions = typeof COPILOT_OPTIONS_TYPE;
