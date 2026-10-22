import { Injectable } from "@nestjs/common";

/** Local reference data, like the Spring Boot example's `ReferenceTools`. */
@Injectable()
export class ReferenceService {
  private readonly terms = new Map<string, string>([
    ["event loop", "The event loop runs JavaScript callbacks on a single thread and hands I/O to libuv."],
    ["libuv", "libuv is the C library that gives Node.js its asynchronous, cross-platform I/O."],
    ["streams", "Streams process data piece by piece with backpressure instead of buffering it all in memory."],
    ["worker threads", "Worker threads run JavaScript in parallel isolates that communicate by message passing."],
    ["require(esm)", "Since Node.js 20.19 and 22.12, CommonJS code can require() synchronous ES modules."],
    ["dependency injection", "Dependencies are supplied to an object rather than constructed inside it, as NestJS providers are."],
  ]);

  describe(term: string): string | undefined {
    return this.terms.get(term.trim().toLowerCase());
  }

  list(): string[] {
    return [...this.terms.keys()];
  }
}
