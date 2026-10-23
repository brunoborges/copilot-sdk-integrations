import type { CopilotAgentEvent } from "@copilot-sdk-integrations/agent-core";
import { ConversationId, CopilotAgent, implementedByCopilot, Message, P, Returns, Signal, UserMessage } from "@copilot-sdk-integrations/nestjs";
import { Observable } from "rxjs";
import { z } from "zod";
import { AdvisorPermissionPolicy } from "./advisor.policy";
import { AdvisorTools } from "./advisor.tools";

export const TermExplanation = z.object({
  term: z.string(),
  summary: z.string(),
  level: z.enum(["beginner", "intermediate", "advanced"]),
  related: z.array(z.string()),
});
export type TermExplanation = z.infer<typeof TermExplanation>;

/**
 * A declarative agent: `CopilotModule.forFeature([NodeAdvisor])` implements the stub methods.
 * The model and BYOK provider can be overridden per environment (`agents["node-advisor"]`).
 */
@CopilotAgent({
  name: "node-advisor",
  instructions:
    "You are a concise Node.js and NestJS advisor. Use describeTerm for definitions, listNotes to recall the user's notes, " +
    "and saveNote only when the user asks you to remember something.",
  tools: [AdvisorTools],
  permissions: AdvisorPermissionPolicy,
})
export abstract class NodeAdvisor {
  /** Persistent conversation turn returning the reply text. */
  @UserMessage()
  answer(@ConversationId() conversationId: string, @Message() question: string): Promise<string> {
    return implementedByCopilot();
  }

  /** Structured output validated against `TermExplanation`. */
  @UserMessage('Explain the Node.js term "{{term}}" for a {{audience}} developer. Use describeTerm when it knows the term.')
  @Returns(TermExplanation)
  explain(@P("term") term: string, @P("audience") audience: string): Promise<TermExplanation> {
    return implementedByCopilot();
  }

  /** Streaming turn; unsubscribing (client disconnect) aborts it. */
  @UserMessage()
  chat(@ConversationId() conversationId: string, @Message() question: string, @Signal() signal?: AbortSignal): Observable<CopilotAgentEvent> {
    return implementedByCopilot();
  }
}
