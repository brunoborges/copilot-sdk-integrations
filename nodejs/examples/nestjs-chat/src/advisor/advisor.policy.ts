import type { CopilotPermissionPolicy } from "@copilot-sdk-integrations/nestjs";
import type { PermissionDecision, PermissionPolicyContext, PermissionRequest } from "@copilot-sdk-integrations/agent-core";
import { Injectable, Logger } from "@nestjs/common";

const READ_ONLY_TOOLS = new Set(["describeTerm", "listNotes"]);
const APPROVAL_TOOLS = new Set(["saveNote"]);

/**
 * The advisor's permission policy: read-only tools run, writes ask the user (a `tool-approval-request`
 * in `useChat`, a `permission-request` SSE event), and everything else (shell, file writes, URLs, MCP)
 * is denied.
 */
@Injectable()
export class AdvisorPermissionPolicy implements CopilotPermissionPolicy {
  private readonly logger = new Logger(AdvisorPermissionPolicy.name);

  evaluate(request: PermissionRequest, { customToolNames, context }: PermissionPolicyContext): PermissionDecision {
    const toolName = "toolName" in request && typeof request.toolName === "string" ? request.toolName : undefined;
    let decision: PermissionDecision = "deny";
    if (request.kind === "custom-tool" && toolName !== undefined && customToolNames.has(toolName)) {
      if (READ_ONLY_TOOLS.has(toolName)) decision = "approve";
      else if (APPROVAL_TOOLS.has(toolName)) decision = "ask-user";
    }
    this.logger.debug(`${request.kind}${toolName ? ` ${toolName}` : ""} for ${context.principal ?? "anonymous"}: ${decision}`);
    return decision;
  }
}
