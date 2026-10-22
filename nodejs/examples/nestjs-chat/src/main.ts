import "reflect-metadata";
import { NestFactory } from "@nestjs/core";
import { AppModule } from "./app.module";

async function bootstrap(): Promise<void> {
  const app = await NestFactory.create(AppModule);
  // Drain in-flight Copilot turns on SIGTERM before the runtime stops.
  app.enableShutdownHooks();
  await app.listen(Number(process.env.PORT ?? 3000));
  console.log(`NestJS chat example listening on ${await app.getUrl()}`);
}

void bootstrap();
