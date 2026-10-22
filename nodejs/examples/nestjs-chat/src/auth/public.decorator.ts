import { SetMetadata } from "@nestjs/common";

export const IS_PUBLIC = "app:is-public";

/** Skips `UserGuard` (health checks). */
export const Public = () => SetMetadata(IS_PUBLIC, true);
