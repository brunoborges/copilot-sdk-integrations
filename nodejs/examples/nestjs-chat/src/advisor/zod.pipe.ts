import { BadRequestException, type PipeTransform } from "@nestjs/common";
import type { z } from "zod";

/** Validates a request body or query against a Zod schema. */
export class ZodPipe<T extends z.ZodType> implements PipeTransform<unknown, z.infer<T>> {
  constructor(private readonly schema: T) {}

  transform(value: unknown): z.infer<T> {
    const result = this.schema.safeParse(value);
    if (!result.success) throw new BadRequestException(result.error.issues.map((issue) => `${issue.path.join(".") || "value"}: ${issue.message}`));
    return result.data;
  }
}
