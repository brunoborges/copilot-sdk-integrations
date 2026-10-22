import { CanActivate, ExecutionContext, Injectable, UnauthorizedException } from "@nestjs/common";
import { Reflector } from "@nestjs/core";
import { IS_PUBLIC } from "./public.decorator";
import type { AppUser } from "./user";

const USER_ID = /^[a-zA-Z0-9._-]{1,64}$/;

/**
 * Demo authentication: the caller identifies itself with an `x-user-id` header, which becomes
 * `request.user`. `CopilotContextInterceptor` turns `request.user.id` into the Copilot principal, so
 * conversations, tools, and notes are isolated per user. Replace with real authentication
 * (Passport, JWT, ...) in production.
 */
@Injectable()
export class UserGuard implements CanActivate {
  constructor(private readonly reflector: Reflector) {}

  canActivate(context: ExecutionContext): boolean {
    if (this.reflector.getAllAndOverride<boolean>(IS_PUBLIC, [context.getHandler(), context.getClass()])) return true;
    const request = context.switchToHttp().getRequest<{ headers: Record<string, string | string[] | undefined>; user?: AppUser }>();
    const header = request.headers["x-user-id"];
    const id = Array.isArray(header) ? header[0] : header;
    if (!id || !USER_ID.test(id)) throw new UnauthorizedException("Send an x-user-id header");
    request.user = { id };
    return true;
  }
}
