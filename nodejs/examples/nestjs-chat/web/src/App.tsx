import { useChat } from "@ai-sdk/react";
import { DefaultChatTransport, getToolName, isToolUIPart, lastAssistantMessageIsCompleteWithApprovalResponses, type UIMessage } from "ai";
import { useMemo, useState, type FormEvent } from "react";

type Part = UIMessage["parts"][number];

export function App() {
  const [user, setUser] = useState("ada");
  return (
    <main>
      <header>
        <h1>Node.js advisor</h1>
        <label>
          Signed in as{" "}
          <select value={user} onChange={(e) => setUser(e.target.value)}>
            <option value="ada">ada</option>
            <option value="grace">grace</option>
          </select>
        </label>
      </header>
      {/* A new chat per user: conversations and notes are scoped to the principal. */}
      <Chat key={user} user={user} />
    </main>
  );
}

function Chat({ user }: { user: string }) {
  const transport = useMemo(() => new DefaultChatTransport({ api: "/api/chat", headers: { "x-user-id": user } }), [user]);
  const { messages, sendMessage, addToolApprovalResponse, status, error, stop } = useChat({
    transport,
    // After the user answers every approval, resend so the server resumes the suspended turn.
    sendAutomaticallyWhen: lastAssistantMessageIsCompleteWithApprovalResponses,
  });
  const [input, setInput] = useState("");
  const busy = status === "submitted" || status === "streaming";

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!input.trim() || busy) return;
    void sendMessage({ text: input });
    setInput("");
  };

  return (
    <section className="chat">
      <ol className="messages">
        {messages.map((message) => (
          <li key={message.id} className={message.role}>
            {message.parts.map((part, index) => (
              <PartView key={index} part={part} onApprove={(id, approved) => addToolApprovalResponse({ id, approved })} />
            ))}
          </li>
        ))}
      </ol>
      {error && <p className="error">Something went wrong: {error.message}</p>}
      <form onSubmit={submit}>
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder='Try "What is libuv?" or "Remember that streams need backpressure"'
          autoFocus
        />
        {busy ? (
          <button type="button" onClick={() => void stop()}>
            Stop
          </button>
        ) : (
          <button type="submit">Send</button>
        )}
      </form>
    </section>
  );
}

function PartView({ part, onApprove }: { part: Part; onApprove: (id: string, approved: boolean) => void }) {
  if (part.type === "text") return <p>{part.text}</p>;
  if (part.type === "reasoning") return <p className="reasoning">{part.text}</p>;
  if (!isToolUIPart(part)) return null;

  const name = getToolName(part);
  return (
    <div className={`tool ${part.state}`}>
      <code>
        {name}({JSON.stringify(part.input ?? {})})
      </code>
      {part.state === "approval-requested" && (
        <span className="approval">
          Allow this tool call?
          <button onClick={() => onApprove(part.approval.id, true)}>Approve</button>
          <button onClick={() => onApprove(part.approval.id, false)}>Deny</button>
        </span>
      )}
      {part.state === "output-available" && <pre>{JSON.stringify(part.output, null, 2)}</pre>}
      {part.state === "output-denied" && <em>Denied</em>}
      {part.state === "output-error" && <em className="error">{part.errorText}</em>}
    </div>
  );
}
