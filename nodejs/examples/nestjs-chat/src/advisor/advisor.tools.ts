import { CopilotTool, type CopilotToolInvocation } from "@copilot-sdk-integrations/nestjs";
import { Injectable, Logger } from "@nestjs/common";
import { z } from "zod";
import { NotesService } from "./notes.service";
import { ReferenceService } from "./reference.service";

const TermInput = z.object({ term: z.string().min(1).describe("Node.js term to describe") });
const NoteInput = z.object({ title: z.string().min(1).max(80), body: z.string().min(1).max(2_000) });

/** Tools run on this DI-managed provider, inside the caller's request context. */
@Injectable()
export class AdvisorTools {
  private readonly logger = new Logger(AdvisorTools.name);

  constructor(
    private readonly reference: ReferenceService,
    private readonly notes: NotesService,
  ) {}

  @CopilotTool({ description: "Look up a short definition of a Node.js term from local reference data", input: TermInput })
  describeTerm({ term }: z.infer<typeof TermInput>) {
    const definition = this.reference.describe(term);
    if (!definition) throw new Error(`Unknown reference term "${term}". Known terms: ${this.reference.list().join(", ")}`);
    return { term, definition };
  }

  @CopilotTool({ description: "List the notes the current user has saved" })
  listNotes(_input: unknown, invocation: CopilotToolInvocation) {
    return this.notes.list(this.userOf(invocation));
  }

  @CopilotTool({ description: "Save a note to the current user's notebook (requires the user's approval)", input: NoteInput })
  saveNote({ title, body }: z.infer<typeof NoteInput>, invocation: CopilotToolInvocation) {
    const user = this.userOf(invocation);
    this.logger.log(`Saving note "${title}" for ${user}`);
    return this.notes.add(user, title, body);
  }

  private userOf(invocation: CopilotToolInvocation): string {
    const principal = invocation.context.principal;
    if (!principal) throw new Error("No authenticated user");
    return principal;
  }
}
