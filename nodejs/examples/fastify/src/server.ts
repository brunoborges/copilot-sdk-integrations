import { buildApp } from "./app.js";

const app = await buildApp({
  // Without a token, the logged-in Copilot CLI user is used. The runtime starts on the first turn.
  client: {
    ...(process.env.GITHUB_TOKEN ? { githubToken: process.env.GITHUB_TOKEN } : {}),
    ...(process.env.COPILOT_CLI_URL ? { cliUrl: process.env.COPILOT_CLI_URL } : {}),
  },
  logger: true,
});

const port = Number(process.env.PORT ?? 3000);
await app.listen({ port, host: process.env.HOST ?? "localhost" });

// app.close() drains in-flight turns (up to the plugin's shutdownGracePeriod) and stops the runtime.
let closing = false;
async function shutdown(signal: NodeJS.Signals): Promise<void> {
  if (closing) process.exit(1);
  closing = true;
  app.log.info({ signal }, "shutting down");
  try {
    await app.close();
    process.exit(0);
  } catch (error) {
    app.log.error(error);
    process.exit(1);
  }
}
process.on("SIGINT", (signal) => void shutdown(signal));
process.on("SIGTERM", (signal) => void shutdown(signal));
