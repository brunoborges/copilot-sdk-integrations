import { CopilotHealthIndicator } from "@copilot-sdk-integrations/nestjs";
import { Controller, Get } from "@nestjs/common";
import { HealthCheck, HealthCheckService } from "@nestjs/terminus";
import { Public } from "../auth/public.decorator";

@Controller("health")
export class HealthController {
  constructor(
    private readonly health: HealthCheckService,
    private readonly copilot: CopilotHealthIndicator,
  ) {}

  /** Never starts a lazy runtime: before the first turn it reports `state: "not-started"`. */
  @Get()
  @Public()
  @HealthCheck()
  check() {
    return this.health.check([() => this.copilot.isHealthy("copilot")]);
  }
}
