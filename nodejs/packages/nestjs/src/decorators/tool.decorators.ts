import "reflect-metadata";
import { CopilotConfigurationError, type CopilotAgentEvent, type StructuredSchema } from "@copilot-sdk-integrations/core";
import { Inject, type Type } from "@nestjs/common";
import {
  COPILOT,
  COPILOT_CLIENT,
  EVENT_LISTENER_METADATA,
  HOOK_METADATA,
  TOOL_INTERCEPTORS_METADATA,
  TOOL_METADATA,
  getAgentToken,
} from "../constants.js";
import type { CopilotToolInvocation, SessionHooks } from "../interfaces.js";

export interface CopilotToolOptions<T = any> {
  /** Tool name. Default: the method name. */
  name?: string;
  description: string;
  /** Zod schema (preferred) or raw JSON Schema for the tool arguments. */
  input?: StructuredSchema<T> | Record<string, unknown>;
  skipPermission?: boolean;
  overridesBuiltInTool?: boolean;
}

/** Exposes a provider method as a Copilot tool. It receives the parsed input and a `CopilotToolInvocation`. */
export function CopilotTool(options: CopilotToolOptions): MethodDecorator {
  if (!options || typeof options.description !== "string" || options.description.trim() === "") {
    throw new CopilotConfigurationError("@CopilotTool requires a description");
  }
  return (target, key) => {
    Reflect.defineMetadata(TOOL_METADATA, { ...options }, target, key);
  };
}

/** Details of a tool call passed to `CopilotToolInterceptor`s. */
export interface CopilotToolExecutionContext {
  toolName: string;
  input: unknown;
  invocation: CopilotToolInvocation;
  /** The DI-managed provider instance. */
  instance: object;
  methodName: string;
  providerClass: Type<unknown>;
}

/** Cross-cutting behavior around `@CopilotTool` methods (logging, metrics, authorization). */
export interface CopilotToolInterceptor {
  intercept(context: CopilotToolExecutionContext, next: () => Promise<unknown>): unknown | Promise<unknown>;
}

export type CopilotToolInterceptorRef = Type<CopilotToolInterceptor> | CopilotToolInterceptor;

/** Applies tool interceptors (classes resolved from DI, or instances) to a provider class or a tool method. */
export function UseToolInterceptors(...interceptors: CopilotToolInterceptorRef[]): ClassDecorator & MethodDecorator {
  return ((target: object, key?: string | symbol) => {
    if (key === undefined) {
      const existing = Reflect.getOwnMetadata(TOOL_INTERCEPTORS_METADATA, target) ?? [];
      Reflect.defineMetadata(TOOL_INTERCEPTORS_METADATA, [...existing, ...interceptors], target);
    } else {
      const existing = Reflect.getOwnMetadata(TOOL_INTERCEPTORS_METADATA, target, key) ?? [];
      Reflect.defineMetadata(TOOL_INTERCEPTORS_METADATA, [...existing, ...interceptors], target, key);
    }
  }) as ClassDecorator & MethodDecorator;
}

/** Hook names: `SessionHooks` keys without the `on` prefix (`preToolUse`, `postToolUse`, ...). */
export type CopilotHookType = {
  [K in keyof SessionHooks]-?: K extends `on${infer Rest}` ? Uncapitalize<Rest> : never;
}[keyof SessionHooks];

export interface CopilotHookOptions {
  /** Restrict to these agents (classes or names). Default: all agents. */
  agents?: ((abstract new (...args: any[]) => unknown) | string)[];
}

export interface CopilotHookMetadata extends CopilotHookOptions {
  type: CopilotHookType;
}

/** Registers a provider method as a session hook (`preToolUse`, `postToolUse`, `userPromptSubmitted`, ...). */
export function CopilotHook(type: CopilotHookType, options: CopilotHookOptions = {}): MethodDecorator {
  if (typeof type !== "string" || type.trim() === "") {
    throw new CopilotConfigurationError("@CopilotHook requires a hook type");
  }
  return (target, key) => {
    const existing: CopilotHookMetadata[] = Reflect.getOwnMetadata(HOOK_METADATA, target, key) ?? [];
    Reflect.defineMetadata(HOOK_METADATA, [...existing, { ...options, type }], target, key);
  };
}

export type CopilotEventType = CopilotAgentEvent["type"] | "*";

/** Delivers matching `CopilotAgentEvent`s (from every agent) to a provider method `(event, context)`. Failures are logged. */
export function OnCopilotEvent(...types: CopilotEventType[]): MethodDecorator {
  return (target, key) => {
    Reflect.defineMetadata(EVENT_LISTENER_METADATA, types.length > 0 ? types : ["*"], target, key);
  };
}

/** Injects the core `Copilot` instance. */
export const InjectCopilot = (): PropertyDecorator & ParameterDecorator => Inject(COPILOT);
/** Injects a `CopilotClientAccessor` (`() => Promise<ClientLike>`) for the raw SDK client. */
export const InjectCopilotClient = (): PropertyDecorator & ParameterDecorator => Inject(COPILOT_CLIENT);
/** Injects the core `AgentHandle` of a functional agent registered with `forFeature([defineAgent(...)])`. */
export const InjectAgent = (name: string): PropertyDecorator & ParameterDecorator => Inject(getAgentToken(name));
