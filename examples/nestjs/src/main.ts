import { prepareCommand, completeCommand } from "@workshop/node-common";
import { createApplication } from "./app.js";

async function main() {
  const command = await prepareCommand(process.argv.slice(2));
  try {
    const { app, agent } = await createApplication(command.definition);
    try {
      const raw = await agent.send({ prompt: command.prompt });
      console.log(JSON.stringify(await completeCommand(command, raw), null, 2));
    } finally {
      await app.close();
    }
  } finally {
    await command.dispose();
  }
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});
