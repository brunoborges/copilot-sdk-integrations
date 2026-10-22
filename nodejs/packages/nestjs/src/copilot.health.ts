import type { Copilot } from "@copilot-sdk-integrations/core";
import { Inject, Injectable } from "@nestjs/common";
import { COPILOT } from "./constants.js";

export interface CopilotHealthResult {
  [key: string]: { status: "up" | "down"; [detail: string]: unknown };
}

const DOWN_STATES = new Set(["stopping", "stopped", "failed"]);

interface HealthIndicatorSessionLike {
  up(data: Record<string, unknown>): CopilotHealthResult;
  down(data: Record<string, unknown>): CopilotHealthResult;
}
interface HealthIndicatorServiceLike {
  check(key: string): HealthIndicatorSessionLike;
}

let terminus: Promise<HealthIndicatorServiceLike | undefined> | undefined;

/** `@nestjs/terminus` stays optional: it is imported on first use, and a plain result is built without it. */
function loadHealthIndicatorService(): Promise<HealthIndicatorServiceLike | undefined> {
  terminus ??= import("@nestjs/terminus").then(
    (module) => new module.HealthIndicatorService() as unknown as HealthIndicatorServiceLike,
    () => undefined,
  );
  return terminus;
}

const fallback: HealthIndicatorServiceLike = {
  check: (key) => ({
    up: (data) => ({ [key]: { ...data, status: "up" } }),
    down: (data) => ({ [key]: { ...data, status: "down" } }),
  }),
};

/**
 * Terminus health indicator: `() => copilotHealth.isHealthy("copilot")` inside
 * `HealthCheckService.check([...])`. Reports client state, runtime version, and admission
 * capacity, and never force-starts a lazy client. The indicator is `down` (so the health check
 * answers `503`) when the runtime is `failed` (start failure or lost connection), `stopping`, or
 * `stopped`; a lazy client that hasn't started yet is `up`.
 *
 * Results are built with Terminus's `HealthIndicatorService` (`check(key).up()/down()`).
 * `@nestjs/terminus` is an optional peer dependency, loaded on first use.
 */
@Injectable()
export class CopilotHealthIndicator {
  constructor(@Inject(COPILOT) private readonly copilot: Copilot) {}

  async isHealthy(key = "copilot"): Promise<CopilotHealthResult> {
    const indicator = ((await loadHealthIndicatorService()) ?? fallback).check(key);
    try {
      const health = await this.copilot.health();
      const details: Record<string, unknown> = { state: health.state, capacity: health.capacity };
      if (health.version !== undefined) details.version = health.version;
      return DOWN_STATES.has(health.state) ? indicator.down(details) : indicator.up(details);
    } catch (error) {
      return indicator.down({ state: this.copilot.state, error: (error as Error).message });
    }
  }
}
