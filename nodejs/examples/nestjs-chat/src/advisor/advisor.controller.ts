import { randomUUID } from "node:crypto";
import { CopilotAgents, toSseEvents } from "@copilot-sdk-integrations/nestjs";
import { Body, Controller, Get, HttpCode, NotFoundException, Param, Post, Query, Sse, type MessageEvent } from "@nestjs/common";
import type { Observable } from "rxjs";
import { z } from "zod";
import { CurrentUser, type AppUser } from "../auth/user";
import { NodeAdvisor, type TermExplanation } from "./advisor.agent";
import { NotesService, type Note } from "./notes.service";
import { ZodPipe } from "./zod.pipe";

const Question = z.object({ question: z.string().trim().min(1).max(4_000) });
const Explain = z.object({ term: z.string().trim().min(1).max(100), audience: z.enum(["beginner", "intermediate", "advanced"]).default("beginner") });
const Stream = z.object({ q: z.string().trim().min(1).max(4_000) });
const PermissionAnswer = z.object({ approved: z.boolean(), feedback: z.string().max(500).optional() });

@Controller("api")
export class AdvisorController {
  constructor(
    private readonly advisor: NodeAdvisor,
    private readonly agents: CopilotAgents,
    private readonly notes: NotesService,
  ) {}

  /** Starts a conversation. Conversations are scoped to the caller (the principal is part of the session id). */
  @Post("conversations")
  async start(@Body(new ZodPipe(Question)) { question }: z.infer<typeof Question>): Promise<{ conversationId: string; answer: string }> {
    const conversationId = randomUUID();
    const answer = await this.agents.create(NodeAdvisor, conversationId).answer(question);
    return { conversationId, answer };
  }

  /** Continues a conversation; an unknown id (or another user's) is a 404 via `CopilotExceptionFilter`. */
  @Post("conversations/:id/messages")
  @HttpCode(200)
  async continue(@Param("id") id: string, @Body(new ZodPipe(Question)) { question }: z.infer<typeof Question>): Promise<{ answer: string }> {
    return { answer: await this.agents.bind(NodeAdvisor, id).answer(question) };
  }

  /** Server-Sent Events: `event: <type>` with JSON data. `permission-request` events wait for `POST .../permissions/:requestId`. */
  @Sse("conversations/:id/stream")
  stream(@Param("id") id: string, @Query(new ZodPipe(Stream)) { q }: z.infer<typeof Stream>): Observable<MessageEvent> {
    return this.advisor.chat(id, q).pipe(toSseEvents());
  }

  /** Answers an "ask-user" permission raised on the SSE stream. */
  @Post("conversations/:id/permissions/:requestId")
  @HttpCode(204)
  respond(@Param("id") id: string, @Param("requestId") requestId: string, @Body(new ZodPipe(PermissionAnswer)) answer: z.infer<typeof PermissionAnswer>): void {
    const response = answer.feedback === undefined ? { approved: answer.approved } : { approved: answer.approved, feedback: answer.feedback };
    if (!this.agents.respondToPermission(id, requestId, response)) throw new NotFoundException("No such pending permission request");
  }

  /** Structured output (`@Returns`). */
  @Get("explain")
  explain(@Query(new ZodPipe(Explain)) { term, audience }: z.infer<typeof Explain>): Promise<TermExplanation> {
    return this.advisor.explain(term, audience);
  }

  @Get("notes")
  listNotes(@CurrentUser() user: AppUser): Note[] {
    return this.notes.list(user.id);
  }
}
