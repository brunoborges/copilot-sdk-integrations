import swc from "unplugin-swc";
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "node",
    projects: [
      {
        extends: true,
        test: {
          name: "packages",
          include: ["packages/*/test/**/*.test.ts"],
          exclude: ["**/node_modules/**", "packages/nestjs/**"],
        },
      },
      {
        // NestJS tests need decorator metadata (`emitDecoratorMetadata`), which esbuild doesn't emit.
        extends: true,
        plugins: [
          swc.vite({
            tsconfigFile: false,
            jsc: {
              target: "es2022",
              parser: { syntax: "typescript", decorators: true },
              transform: { legacyDecorator: true, decoratorMetadata: true, useDefineForClassFields: false },
            },
          }),
        ],
        test: {
          name: "nestjs",
          include: ["packages/nestjs/test/**/*.test.ts"],
        },
      },
    ],
  },
});
