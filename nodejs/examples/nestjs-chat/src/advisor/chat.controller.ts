import { CopilotApprovalStore, pipeCopilotChatToResponse } from "@copilot-sdk-integrations/ai";
import { getCopilotContext } from "@copilot-sdk-integrations/agent-core";
import { CopilotAgents } from "@copilot-sdk-integrations/nestjs";
import { Body, Controller, Post, Res } from "@nestjs/common";
import type { UIMessage } from "ai";
import type { Response } from "express";
import { z } from "zod";
import { CurrentUser, type AppUser } from "../auth/user";
import { NodeAdvisor } from "./advisor.agent";
import { ZodPipe } from "./zod.pipe";

const ChatRequest = z.object({
  id: z.string().min(1).max(200),
  // `useChat` messages; `streamCopilotChat` reads only the last one (Copilot keeps the history).
  messages: z.array(z.custom<UIMessage>((m) => typeof m === "object" && m !== null && "role" in m && "parts" in m)).min(1),
});

/** The AI SDK `useChat` endpoint (UI message stream protocol), with tool approvals. */
@Controller("api")
export class ChatController {
  constructor(
    private readonly agents: CopilotAgents,
    private readonly approvals: CopilotApprovalStore,
  ) {}

  @Post("chat")
  async chat(@Body(new ZodPipe(ChatRequest)) body: z.infer<typeof ChatRequest>, @CurrentUser() user: AppUser, @Res() res: Response): Promise<void> {
    const signal = getCopilotContext()?.signal; // aborted when the client disconnects
    await pipeCopilotChatToResponse(res, {
      agent: this.agents.handle(NodeAdvisor),
      messages: body.messages,
      // Keyed per user so one user's new message never supersedes another user's pending approval.
      conversationId: `${user.id}:${body.id}`,
      approvals: this.approvals,
      ...(signal ? { signal } : {}),
    });
  }
}
