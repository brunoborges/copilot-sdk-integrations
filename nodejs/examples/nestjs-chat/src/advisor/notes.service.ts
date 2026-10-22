import { Injectable } from "@nestjs/common";

export interface Note {
  title: string;
  body: string;
  createdAt: string;
}

/** In-memory, per-user notebook. */
@Injectable()
export class NotesService {
  private readonly notes = new Map<string, Note[]>();

  add(userId: string, title: string, body: string): Note {
    const note = { title, body, createdAt: new Date().toISOString() };
    this.notes.set(userId, [...(this.notes.get(userId) ?? []), note]);
    return note;
  }

  list(userId: string): Note[] {
    return [...(this.notes.get(userId) ?? [])];
  }
}
