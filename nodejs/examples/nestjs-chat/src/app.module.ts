import { Module } from "@nestjs/common";
import { ConfigModule } from "@nestjs/config";
import { APP_GUARD } from "@nestjs/core";
import { TerminusModule } from "@nestjs/terminus";
import { AdvisorModule } from "./advisor/advisor.module";
import { UserGuard } from "./auth/user.guard";
import { AppCopilotModule } from "./copilot.config";
import { HealthController } from "./health/health.controller";

@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true }),
    AppCopilotModule,
    TerminusModule,
    AdvisorModule,
  ],
  controllers: [HealthController],
  providers: [{ provide: APP_GUARD, useClass: UserGuard }],
})
export class AppModule {}
