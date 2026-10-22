import { toJSONSchema as zodToJSONSchema } from "zod";
import { CopilotConfigurationError } from "../errors.js";
import type { StructuredSchema } from "../types.js";

/** True for anything with a `parse` function (Zod schemas and other Zod-compatible validators). */
export function isStructuredSchema(value: unknown): value is StructuredSchema<unknown> {
  return isObjectLike(value) && typeof (value as { parse?: unknown }).parse === "function";
}

/**
 * Converts a tool input schema to a JSON Schema object.
 *
 * - Zod 4 schemas (`_zod` internals) are converted with `z.toJSONSchema` (input view, so fields with
 *   defaults are optional; unrepresentable types become `{}`).
 * - Other schemas exposing `toJSONSchema()` use that method.
 * - Raw JSON Schema objects pass through.
 * - `undefined` becomes an empty object schema.
 *
 * The top-level `$schema` key is always stripped.
 */
export function toJsonSchema(
  schema: StructuredSchema<unknown> | Record<string, unknown> | undefined,
): Record<string, unknown> {
  if (schema === undefined || schema === null) {
    return { type: "object", properties: {} };
  }
  if (!isObjectLike(schema)) {
    throw new CopilotConfigurationError(`Tool input schema must be an object, got ${typeof schema}`);
  }
  let json: unknown;
  if ("_zod" in schema) {
    try {
      json = zodToJSONSchema(schema as never, { io: "input", unrepresentable: "any" });
    } catch (error) {
      throw new CopilotConfigurationError(`Cannot convert Zod schema to JSON Schema: ${messageOf(error)}`, {
        cause: error,
      });
    }
  } else if (typeof (schema as StructuredSchema<unknown>).toJSONSchema === "function") {
    json = (schema as Required<StructuredSchema<unknown>>).toJSONSchema();
  } else if (isStructuredSchema(schema)) {
    throw new CopilotConfigurationError(
      "Tool input schema has parse() but no JSON Schema representation; use a Zod 4 schema or a raw JSON Schema object",
    );
  } else {
    json = schema;
  }
  if (!isObjectLike(json) || Array.isArray(json)) {
    throw new CopilotConfigurationError("Tool input schema did not produce a JSON Schema object");
  }
  const { $schema: _ignored, ...rest } = json as Record<string, unknown>;
  return rest;
}

export function isObjectLike(value: unknown): value is object {
  return (typeof value === "object" || typeof value === "function") && value !== null;
}

export function messageOf(error: unknown): string {
  if (error instanceof Error) return error.message;
  if (typeof error === "string") return error;
  try {
    return JSON.stringify(error) ?? String(error);
  } catch {
    return String(error);
  }
}
