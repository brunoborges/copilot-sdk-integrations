import { createCopilot } from "@copilot-sdk-integrations/core";
import { createApp } from "./app.js";

const copilot = createCopilot({
  // Without a token, the logged-in Copilot CLI user is used.
  ...(process.env.GITHUB_TOKEN ? { githubToken: process.env.GITHUB_TOKEN } : {}),
  ...(process.env.COPILOT_CLI_URL ? { cliUrl: process.env.COPILOT_CLI_URL } : {}),
});

const port = Number(process.env.PORT ?? 3000);
const server = createApp({ copilot }).listen(port, () => {
  console.log(`Express example listening on http://localhost:${port}`);
});

async function shutdown(): Promise<void> {
  server.close();
  await copilot.stop({ gracePeriodMs: 10_000 });
  process.exit(0);
}
process.once("SIGINT", () => void shutdown());
process.once("SIGTERM", () => void shutdown());
