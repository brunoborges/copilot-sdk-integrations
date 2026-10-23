import { cp, mkdir } from "node:fs/promises";

const destination = new URL("../dist/src/resources/", import.meta.url);
await mkdir(destination, { recursive: true });
await cp(new URL("../src/resources/", import.meta.url), destination, { recursive: true });
