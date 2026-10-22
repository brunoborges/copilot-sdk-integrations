import type { PermissionRequestResult } from "@github/copilot-sdk";
import { CopilotConfigurationError } from "../errors.js";
import type { PermissionDecision, PermissionPolicy, PermissionPolicyContext, PermissionRequest } from "../types.js";

/** Permission request kinds emitted by the runtime (see the SDK's `PermissionRequest` union). */
export const PermissionKinds = {
  SHELL: "shell",
  WRITE: "write",
  READ: "read",
  MCP: "mcp",
  CUSTOM_TOOL: "custom-tool",
  URL: "url",
  MEMORY: "memory",
  HOOK: "hook",
} as const;

const DECISIONS: ReadonlySet<string> = new Set(["approve", "deny", "ask-user", "abstain"]);

function field(request: PermissionRequest, key: string): unknown {
  return (request as unknown as Record<string, unknown>)[key];
}

function text(request: PermissionRequest, key: string): string | undefined {
  const value = field(request, key);
  return typeof value === "string" ? value : undefined;
}

/** Requests the runtime marks `managedApprovalRequired` need a human; built-in policies never auto-approve them. */
function managed(request: PermissionRequest): boolean {
  return request.managedApprovalRequired === true;
}

/** Builds a policy that approves matching requests and abstains otherwise. */
function allowWhen(rule: (request: PermissionRequest, ctx: PermissionPolicyContext) => boolean): PermissionPolicy {
  return (request, ctx) => (!managed(request) && rule(request, ctx) ? "approve" : "abstain");
}

function names(values: readonly string[] | undefined, description: string): ReadonlySet<string> {
  if (!Array.isArray(values)) {
    throw new CopilotConfigurationError(`${description} must be an array of strings`);
  }
  for (const value of values) {
    if (typeof value !== "string" || value.trim() === "") {
      throw new CopilotConfigurationError(`${description} must not contain blank entries`);
    }
  }
  return new Set(values);
}

async function decide(
  policy: PermissionPolicy,
  request: PermissionRequest,
  ctx: PermissionPolicyContext,
): Promise<PermissionDecision> {
  try {
    const decision = await policy(request, ctx);
    return DECISIONS.has(decision) ? decision : "deny";
  } catch {
    return "deny";
  }
}

/**
 * Ready-made permission policies. Policies that only approve return `"abstain"` for requests they do
 * not match, so they compose with `anyOf`/`allOf`; `evaluatePermission` maps a final `"abstain"` to deny.
 */
export const permissions = {
  /** Denies everything. This is the default when an agent configures no policy. */
  denyAll(): PermissionPolicy {
    return () => "deny";
  },

  /**
   * Approves everything except requests the runtime marks `managedApprovalRequired` (left to a human,
   * like the SDK's `approveAll`). **Dangerous**: shell commands, file writes, and URL fetches run unreviewed.
   */
  allowAll(): PermissionPolicy {
    return allowWhen(() => true);
  },

  /** Approves `read` requests and MCP requests flagged `readOnly`. */
  allowReadOnly(): PermissionPolicy {
    return allowWhen(
      (request) =>
        request.kind === PermissionKinds.READ ||
        (request.kind === PermissionKinds.MCP && field(request, "readOnly") === true),
    );
  },

  /**
   * Approves `custom-tool` requests for the agent's own tools (`ctx.customToolNames`), optionally
   * narrowed to `toolNames`.
   */
  allowCustomTools(toolNames?: string[]): PermissionPolicy {
    const allowed = toolNames === undefined ? undefined : names(toolNames, "allowCustomTools names");
    return allowWhen((request, ctx) => {
      if (request.kind !== PermissionKinds.CUSTOM_TOOL) return false;
      const name = text(request, "toolName");
      return name !== undefined && ctx.customToolNames.has(name) && (allowed === undefined || allowed.has(name));
    });
  },

  /** Approves `custom-tool` and `mcp` requests whose `toolName` is listed (MCP tools use the server's own tool name). */
  allowTools(toolNames: string[]): PermissionPolicy {
    const allowed = names(toolNames, "allowTools names");
    return allowWhen((request) => {
      if (request.kind !== PermissionKinds.CUSTOM_TOOL && request.kind !== PermissionKinds.MCP) return false;
      const name = text(request, "toolName");
      return name !== undefined && allowed.has(name);
    });
  },

  /** Approves `mcp` requests whose `serverName` is listed. */
  allowMcpServers(serverNames: string[]): PermissionPolicy {
    const allowed = names(serverNames, "allowMcpServers names");
    return allowWhen((request) => {
      const server = text(request, "serverName");
      return request.kind === PermissionKinds.MCP && server !== undefined && allowed.has(server);
    });
  },

  /** Approves requests whose `kind` is listed. */
  allowKinds(kinds: string[]): PermissionPolicy {
    const allowed = names(kinds, "allowKinds kinds");
    return allowWhen((request) => allowed.has(request.kind));
  },

  /**
   * Routes requests to a human. With a `policy`, its `"approve"`/`"deny"` decisions are final and
   * everything else (`"abstain"`, `"ask-user"`, or a throw-free non-decision) becomes `"ask-user"`.
   * A throwing inner policy denies (fail closed).
   */
  askUser(policy?: PermissionPolicy): PermissionPolicy {
    return async (request, ctx) => {
      if (!policy) return "ask-user";
      const decision = await decide(policy, request, ctx);
      return decision === "approve" || decision === "deny" ? decision : "ask-user";
    };
  },

  /**
   * Consults policies in order; the first `"approve"` wins and stops evaluation (as in Spring's
   * `anyOf`). A policy that throws counts as `"deny"` for that policy only; evaluation continues.
   * When nothing approved: `"ask-user"` if any policy asked, else `"deny"` if any denied, else
   * `"abstain"` (so `anyOf` itself composes). An empty `anyOf` abstains.
   */
  anyOf(...policies: PermissionPolicy[]): PermissionPolicy {
    assertPolicies(policies, "anyOf");
    return async (request, ctx) => {
      let asked = false;
      let denied = false;
      for (const policy of policies) {
        const decision = await decide(policy, request, ctx);
        if (decision === "approve") return "approve";
        if (decision === "ask-user") asked = true;
        else if (decision === "deny") denied = true;
      }
      return asked ? "ask-user" : denied ? "deny" : "abstain";
    };
  },

  /**
   * Approves only when every policy approves. The first `"deny"` (or throw) wins and stops evaluation;
   * otherwise `"ask-user"` if any policy asked; otherwise `"abstain"` if any policy abstained. An empty
   * `allOf` abstains.
   */
  allOf(...policies: PermissionPolicy[]): PermissionPolicy {
    assertPolicies(policies, "allOf");
    return async (request, ctx) => {
      if (policies.length === 0) return "abstain";
      let asked = false;
      let abstained = false;
      for (const policy of policies) {
        const decision = await decide(policy, request, ctx);
        if (decision === "deny") return "deny";
        if (decision === "ask-user") asked = true;
        else if (decision === "abstain") abstained = true;
      }
      return asked ? "ask-user" : abstained ? "abstain" : "approve";
    };
  },
};

function assertPolicies(policies: unknown[], name: string): void {
  for (const policy of policies) {
    if (typeof policy !== "function") {
      throw new CopilotConfigurationError(`${name}() accepts only permission policies (functions)`);
    }
  }
}

/**
 * Evaluates `policy` for a request. A missing policy denies everything (deny by default); a policy
 * that throws or returns an unknown value denies; `"abstain"` denies. Never returns `"abstain"`.
 */
export async function evaluatePermission(
  policy: PermissionPolicy | undefined,
  request: PermissionRequest,
  ctx: PermissionPolicyContext,
): Promise<PermissionDecision> {
  if (!policy) return "deny";
  const decision = await decide(policy, request, ctx);
  return decision === "abstain" ? "deny" : decision;
}

/**
 * Maps a final decision to the SDK permission result:
 * `"approve"` → `{ kind: "approve-once" }`, `"deny"` → `{ kind: "reject", feedback? }`,
 * `"user-not-available"` → `{ kind: "user-not-available" }` (an "ask-user" nobody could answer).
 */
export function toPermissionResult(
  decision: "approve" | "deny" | "user-not-available",
  feedback?: string,
): PermissionRequestResult {
  switch (decision) {
    case "approve":
      return { kind: "approve-once" };
    case "user-not-available":
      return { kind: "user-not-available" };
    default:
      return feedback === undefined ? { kind: "reject" } : { kind: "reject", feedback };
  }
}
