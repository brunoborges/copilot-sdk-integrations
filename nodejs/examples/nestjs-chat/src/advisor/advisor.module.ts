import { createApprovalStore, CopilotApprovalStore } from "@copilot-sdk-integrations/ai";
import { CopilotModule } from "@copilot-sdk-integrations/nestjs";
import { Module } from "@nestjs/common";
import { AdvisorController } from "./advisor.controller";
import { NodeAdvisor } from "./advisor.agent";
import { AdvisorPermissionPolicy } from "./advisor.policy";
import { AdvisorTools } from "./advisor.tools";
import { ChatController } from "./chat.controller";
import { NotesService } from "./notes.service";
import { ReferenceService } from "./reference.service";

@Module({
  imports: [CopilotModule.forFeature([NodeAdvisor])],
  controllers: [AdvisorController, ChatController],
  providers: [
    AdvisorTools,
    AdvisorPermissionPolicy,
    ReferenceService,
    NotesService,
    // Turns suspended on a useChat tool approval wait here between requests (use sticky sessions with replicas).
    { provide: CopilotApprovalStore, useFactory: () => createApprovalStore({ ttlMs: 10 * 60_000 }) },
  ],
  exports: [NotesService],
})
export class AdvisorModule {}
