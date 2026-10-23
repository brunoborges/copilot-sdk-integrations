import "reflect-metadata";
import { CopilotConfigurationError, type CopilotAgentDefinition, type PermissionPolicy, type StructuredSchema } from "@copilot-sdk-integrations/agent-core";
import type { Type } from "@nestjs/common";
import {
  AGENT_METADATA,
  AGENT_TOOLS_METADATA,
  PARAMS_METADATA,
  RETURNS_METADATA,
  USER_MESSAGE_METADATA,
} from "../constants.js";
import type { CopilotPermissionPolicy, CopilotToolSource, MCPServerConfig, ProviderConfig } from "../interfaces.js";

/** `@CopilotAgent` options: the core agent definition fields, with Nest-friendly tools, providers, and policies. */
export interface CopilotAgentOptions
  extends Omit<CopilotAgentDefinition, "name" | "tools" | "provider" | "mcpServers" | "permissions"> {
  /** Agent name (part of the session identity). Default: the class name. */
  name?: string;
  /** Provider classes with `@CopilotTool` methods, and/or core tool inputs. */
  tools?: CopilotToolSource[];
  /** A named provider from the module's `providers`, or an inline BYOK provider config. */
  provider?: string | ProviderConfig;
  /** Names from the module's `mcpServers`, or inline server configs. */
  mcpServers?: string[] | Record<string, MCPServerConfig>;
  /** A `permissions.*` policy, an injectable `CopilotPermissionPolicy` class, or an instance. */
  permissions?: PermissionPolicy | Type<CopilotPermissionPolicy> | CopilotPermissionPolicy;
}

/** How a method's result is delivered. */
export type AgentMethodResult = "promise" | "observable" | "async-iterable";

export interface UserMessageOptions {
  /** Prompt template with `{{name}}` placeholders bound by `@P(name)` (and `{{message}}` by `@Message()`). */
  template?: string;
  /**
   * Return an `Observable` or `AsyncIterable` of `CopilotAgentEvent`s instead of a `Promise`.
   * Inferred as `"observable"` from emitted decorator metadata when the declared return type is `Observable`.
   */
  stream?: Exclude<AgentMethodResult, "promise">;
}

export type ParamKind = "message" | "var" | "conversationId" | "signal";
export interface ParamMetadata {
  index: number;
  kind: ParamKind;
  name?: string;
}
export interface UserMessageMetadata extends Omit<UserMessageOptions, "template"> {
  template?: string;
  templateFile?: string | URL;
}

/** Declares an agent class whose `@UserMessage` methods are implemented by `CopilotModule`. */
export function CopilotAgent(options: CopilotAgentOptions = {}): ClassDecorator {
  return (target) => {
    Reflect.defineMetadata(AGENT_METADATA, { ...options }, target);
  };
}

/** Marks a method as an agent turn. `template` uses `{{name}}` placeholders; without it the `@Message()` argument is sent verbatim. */
export function UserMessage(
  templateOrOptions?: string | UserMessageOptions,
  options: Omit<UserMessageOptions, "template"> = {},
): MethodDecorator {
  const resolved: UserMessageMetadata =
    typeof templateOrOptions === "string" ? { ...options, template: templateOrOptions } : { ...options, ...templateOrOptions };
  return (target, key) => {
    Reflect.defineMetadata(USER_MESSAGE_METADATA, resolved, target, key);
  };
}

/**
 * Like `@UserMessage(template)`, with the template loaded from a file at bootstrap. Relative paths
 * resolve against the `templatesDir` module option (default: `process.cwd()`); absolute paths and
 * `file:` URLs are used as is, so `@UserMessageFrom(new URL("./prompt.md", import.meta.url))` loads
 * a template next to the agent's source file.
 */
export function UserMessageFrom(path: string | URL, options: Omit<UserMessageOptions, "template"> = {}): MethodDecorator {
  const resolved: UserMessageMetadata = { ...options, templateFile: path };
  return (target, key) => {
    Reflect.defineMetadata(USER_MESSAGE_METADATA, resolved, target, key);
  };
}

function param(kind: ParamKind, name?: string): ParameterDecorator {
  return (target, key, index) => {
    if (key === undefined) {
      throw new CopilotConfigurationError(`Copilot parameter decorators can only decorate method parameters`);
    }
    const existing: ParamMetadata[] = Reflect.getOwnMetadata(PARAMS_METADATA, target, key) ?? [];
    Reflect.defineMetadata(PARAMS_METADATA, [...existing, { index, kind, name }], target, key);
  };
}

/** The user message text. */
export function Message(): ParameterDecorator {
  return param("message");
}

/** A template variable bound to `{{name}}`. */
export function P(name: string): ParameterDecorator {
  if (typeof name !== "string" || name.trim() === "") {
    throw new CopilotConfigurationError("@P(name) requires a non-blank name");
  }
  return param("var", name);
}

/** The conversation key (create-or-resume). Omit for ephemeral turns. */
export function ConversationId(): ParameterDecorator {
  return param("conversationId");
}

/** An `AbortSignal` that cancels the turn. */
export function Signal(): ParameterDecorator {
  return param("signal");
}

/** Zod schema for a structured result (types are erased at runtime, so the schema is explicit). */
export function Returns(schema: StructuredSchema<unknown>): MethodDecorator {
  return (target, key) => {
    Reflect.defineMetadata(RETURNS_METADATA, schema, target, key);
  };
}

/** Tool providers (or core tool inputs) available to the agent (class) or to a single call (method). */
export function Tools(...tools: CopilotToolSource[]): ClassDecorator & MethodDecorator {
  return ((target: object, key?: string | symbol) => {
    if (key === undefined) {
      const existing: CopilotToolSource[] = Reflect.getOwnMetadata(AGENT_TOOLS_METADATA, target) ?? [];
      Reflect.defineMetadata(AGENT_TOOLS_METADATA, [...existing, ...tools], target);
    } else {
      const existing: CopilotToolSource[] = Reflect.getOwnMetadata(AGENT_TOOLS_METADATA, target, key) ?? [];
      Reflect.defineMetadata(AGENT_TOOLS_METADATA, [...existing, ...tools], target, key);
    }
  }) as ClassDecorator & MethodDecorator;
}

/**
 * Body for declarative agent methods. TypeScript doesn't allow decorators on abstract methods, so
 * agent methods are declared with this placeholder body; `CopilotModule` replaces them.
 *
 * ```ts
 * @UserMessage()
 * answer(@ConversationId() id: string, @Message() question: string): Promise<string> {
 *   return implementedByCopilot();
 * }
 * ```
 */
export function implementedByCopilot(): never {
  throw new CopilotConfigurationError(
    "This method is implemented by CopilotModule: register the agent with CopilotModule.forFeature([...]) and inject it",
  );
}
