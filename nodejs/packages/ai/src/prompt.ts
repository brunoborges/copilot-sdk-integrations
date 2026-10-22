import type {
  LanguageModelV4Message,
  LanguageModelV4Prompt,
  LanguageModelV4ToolResultPart,
  SharedV4Warning,
} from "@ai-sdk/provider";

export interface CopilotPrompt {
  /** System messages, joined. Merged into the agent instructions. */
  system?: string;
  /** Prompt sent to the Copilot session. */
  prompt: string;
  warnings: SharedV4Warning[];
}

/**
 * Converts an AI SDK language model prompt into a Copilot prompt.
 *
 * - `latest` (used with a `conversationId`): the session keeps the history, so only the user
 *   messages after the last assistant/tool message are sent.
 * - `transcript` (stateless calls): a single user message is sent as is; a longer history is
 *   rendered as a transcript followed by the last user message.
 *
 * File parts are not supported and are replaced by a placeholder (with a warning).
 */
export function toCopilotPrompt(prompt: LanguageModelV4Prompt, mode: "latest" | "transcript"): CopilotPrompt {
  const warnings: SharedV4Warning[] = [];
  const system = prompt
    .filter((m): m is Extract<LanguageModelV4Message, { role: "system" }> => m.role === "system")
    .map((m) => m.content)
    .filter((c) => c.trim() !== "")
    .join("\n\n");
  const messages = prompt.filter((m) => m.role !== "system");
  const fileWarning = () => {
    if (!warnings.some((w) => w.type === "unsupported" && w.feature === "file parts")) {
      warnings.push({ type: "unsupported", feature: "file parts", details: "Copilot prompts are text only; files were replaced by a placeholder." });
    }
  };

  let lastNonUser = -1;
  messages.forEach((m, i) => {
    if (m.role !== "user") lastNonUser = i;
  });
  const trailingUsers = messages.slice(lastNonUser + 1);
  const trailingText = trailingUsers.map((m) => renderUser(m, fileWarning)).join("\n\n");

  let text: string;
  if (mode === "latest" || lastNonUser === -1) {
    text = trailingText;
  } else {
    const history = messages
      .slice(0, trailingUsers.length > 0 ? lastNonUser + 1 : messages.length)
      .map((m) => renderMessage(m, fileWarning))
      .filter((line) => line !== "")
      .join("\n\n");
    text =
      trailingUsers.length > 0
        ? `Conversation so far:\n\n${history}\n\nReply to the latest user message:\n\n${trailingText}`
        : `Conversation so far:\n\n${history}\n\nContinue the conversation.`;
  }
  if (text.trim() === "") {
    throw new TypeError("The prompt has no user text to send to Copilot");
  }
  const result: CopilotPrompt = { prompt: text, warnings };
  if (system !== "") result.system = system;
  return result;
}

function renderUser(message: LanguageModelV4Message, onFile: () => void): string {
  if (message.role !== "user") return "";
  return message.content
    .map((part) => {
      if (part.type === "text") return part.text;
      onFile();
      return `[file${part.filename ? ` ${part.filename}` : ""} (${part.mediaType}) omitted]`;
    })
    .join("\n");
}

function renderMessage(message: LanguageModelV4Message, onFile: () => void): string {
  switch (message.role) {
    case "user":
      return `User: ${renderUser(message, onFile)}`;
    case "assistant": {
      const lines: string[] = [];
      for (const part of message.content) {
        if (part.type === "text" && part.text !== "") lines.push(`Assistant: ${part.text}`);
        else if (part.type === "tool-call") lines.push(`Assistant called tool ${part.toolName} with ${stringify(part.input)}`);
        else if (part.type === "tool-result") lines.push(renderToolResult(part));
        else if (part.type === "file") {
          onFile();
          lines.push("Assistant: [file omitted]");
        }
      }
      return lines.join("\n");
    }
    case "tool":
      return message.content
        .filter((part): part is LanguageModelV4ToolResultPart => part.type === "tool-result")
        .map(renderToolResult)
        .join("\n");
    default:
      return "";
  }
}

function renderToolResult(part: LanguageModelV4ToolResultPart): string {
  const output = part.output as { type: string; value?: unknown; reason?: string };
  switch (output.type) {
    case "text":
    case "error-text":
      return `Tool ${part.toolName} ${output.type === "text" ? "returned" : "failed"}: ${String(output.value)}`;
    case "json":
    case "error-json":
      return `Tool ${part.toolName} ${output.type === "json" ? "returned" : "failed"}: ${stringify(output.value)}`;
    case "execution-denied":
      return `Tool ${part.toolName} was denied${output.reason ? `: ${output.reason}` : ""}`;
    default:
      return `Tool ${part.toolName} returned: ${stringify(output.value ?? output)}`;
  }
}

function stringify(value: unknown): string {
  if (typeof value === "string") return value;
  try {
    return JSON.stringify(value) ?? String(value);
  } catch {
    return String(value);
  }
}
