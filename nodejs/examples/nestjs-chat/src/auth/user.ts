import { createParamDecorator, type ExecutionContext } from "@nestjs/common";

export interface AppUser {
  id: string;
}

/** Injects the `request.user` set by `UserGuard`. */
export const CurrentUser = createParamDecorator((_data: unknown, ctx: ExecutionContext): AppUser => ctx.switchToHttp().getRequest().user);
