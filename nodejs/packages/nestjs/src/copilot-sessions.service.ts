import {
  createSessionCleanup,
  type CopilotAgentDefinition,
  type CopilotSessionMessage,
  type CopilotSessionMetadata,
  type CopilotSessions,
  type SessionCleanup,
} from "@copilot-sdk-integrations/agent-core";
import { Inject, Injectable, Logger, type OnApplicationBootstrap, type OnModuleDestroy } from "@nestjs/common";
import { CopilotClientManager, nestCopilotLogger } from "./copilot-client.manager.js";

/**
 * Lists, reads, and deletes persisted Copilot sessions (the core `copilot.sessions`). Inject it to
 * build a conversation list, restore a transcript, or let users delete their history.
 */
@Injectable()
export class CopilotSessionsService implements CopilotSessions {
  constructor(@Inject(CopilotClientManager) private readonly manager: CopilotClientManager) {}

  private get sessions(): CopilotSessions {
    return this.manager.copilot.sessions;
  }

  list(): Promise<CopilotSessionMetadata[]> {
    return this.sessions.list();
  }

  get(sessionId: string): Promise<CopilotSessionMetadata | undefined> {
    return this.sessions.get(sessionId);
  }

  exists(sessionId: string): Promise<boolean> {
    return this.sessions.exists(sessionId);
  }

  getMessages(sessionId: string): Promise<CopilotSessionMessage[] | undefined> {
    return this.sessions.getMessages(sessionId);
  }

  delete(sessionId: string): Promise<boolean> {
    return this.sessions.delete(sessionId);
  }

  sessionId(agent: string | CopilotAgentDefinition, conversationId: string, options?: { principal?: string }): string {
    return this.sessions.sessionId(agent, conversationId, options);
  }

  deleteExpired(maxAgeMs: number): Promise<number> {
    return this.sessions.deleteExpired(maxAgeMs);
  }
}

/**
 * Periodically deletes sessions older than `sessionCleanup.maxAge` (module option). Disabled unless
 * that option is set. Starts at application bootstrap and stops when the module is destroyed.
 */
@Injectable()
export class CopilotSessionCleanup implements OnApplicationBootstrap, OnModuleDestroy {
  private readonly cleanup?: SessionCleanup;

  constructor(@Inject(CopilotClientManager) manager: CopilotClientManager) {
    const options = manager.options.sessionCleanup;
    if (options) {
      this.cleanup = createSessionCleanup(manager.copilot.sessions, options, nestCopilotLogger(new Logger("CopilotSessionCleanup")));
    }
  }

  /** Whether `sessionCleanup` is configured. */
  get enabled(): boolean {
    return this.cleanup !== undefined;
  }

  /** The configured maximum session age and sweep interval, or `undefined` when disabled. */
  get settings(): { maxAge: number; interval: number } | undefined {
    return this.cleanup ? { maxAge: this.cleanup.maxAge, interval: this.cleanup.interval } : undefined;
  }

  onApplicationBootstrap(): void {
    this.cleanup?.start();
  }

  onModuleDestroy(): void {
    this.cleanup?.stop();
  }

  /** Runs one sweep now and returns the number of sessions deleted (0 when disabled). */
  sweep(): Promise<number> {
    return this.cleanup ? this.cleanup.sweep() : Promise.resolve(0);
  }
}
