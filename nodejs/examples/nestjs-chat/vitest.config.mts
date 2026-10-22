import swc from "unplugin-swc";
import { defineConfig } from "vitest/config";

export default defineConfig({
  // Nest needs decorator metadata (`emitDecoratorMetadata`) for constructor injection; esbuild doesn't emit it.
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
  test: { environment: "node", include: ["test/**/*.test.ts"] },
});
