import type { CopilotContext } from "@copilot-sdk-integrations/agent-core";
import { Injectable } from "@nestjs/common";

/**
 * Resolves the caller's GitHub token for a turn, used as the per-session token.
 * Provide an implementation as `{ provide: CopilotTokenResolver, useClass: MyResolver }`.
 */
export abstract class CopilotTokenResolver {
  abstract resolve(context: CopilotContext): string | undefined | Promise<string | undefined>;
}

/**
 * Reads the access token of a Passport GitHub strategy from `request.user` (captured by
 * `CopilotContextInterceptor` as `attributes.user`). Looks for `accessToken`, `githubToken`,
 * or `github.accessToken`; the verify callback must store the token on the user.
 */
@Injectable()
export class PassportGitHubTokenResolver extends CopilotTokenResolver {
  resolve(context: CopilotContext): string | undefined {
    const user = context.attributes.user as Record<string, any> | undefined;
    if (!user || typeof user !== "object") return undefined;
    const token = user.accessToken ?? user.githubToken ?? user.github?.accessToken;
    return typeof token === "string" && token !== "" ? token : undefined;
  }
}
