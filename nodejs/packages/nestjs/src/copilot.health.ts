import type { Copilot } from "@copilot-sdk-integrations/core";
import { Inject, Injectable } from "@nestjs/common";
import { COPILOT } from "./constants.js";

export interface CopilotHealthResult {
  [key: string]: { status: "up" | "down"; [detail: string]: unknown };
}

const DOWN_STATES = new Set(["stopping", "stopped", "failed"]);

/**
 * Terminus-compatible health indicator (no hard dependency on `@nestjs/terminus`):
 * `() => copilotHealth.isHealthy("copilot")` inside `HealthCheckService.check([...])`.
 * Reports client state, runtime version and admission capacity; never force-starts a lazy client.
 * A lazy client that hasn't started yet is reported as up.
 */
@Injectable()
export class CopilotHealthIndicator {
  constructor(@Inject(COPILOT) private readonly copilot: Copilot) {}

  async isHealthy(key = "copilot"): Promise<CopilotHealthResult> {
    try {
      const health = await this.copilot.health();
      const details: Record<string, unknown> = { state: health.state, capacity: health.capacity };
      if (health.version !== undefined) details.version = health.version;
      return { [key]: { status: DOWN_STATES.has(health.state) ? "down" : "up", ...details } };
    } catch (error) {
      return { [key]: { status: "down", state: this.copilot.state, error: (error as Error).message } };
    }
  }
}
