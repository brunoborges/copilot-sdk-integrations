"""An in-memory ticket store standing in for a database session."""

from __future__ import annotations

import threading
from dataclasses import dataclass, field

PRIORITIES = ("low", "normal", "urgent")


@dataclass
class Ticket:
    id: int
    owner: str
    title: str
    body: str
    priority: str = "normal"


@dataclass
class Store:
    tickets: dict[int, Ticket] = field(default_factory=dict)
    lock: threading.Lock = field(default_factory=threading.Lock)

    def get(self, ticket_id: int, owner: str) -> Ticket | None:
        ticket = self.tickets.get(ticket_id)
        return ticket if ticket is not None and ticket.owner == owner else None

    def add(self, ticket: Ticket) -> Ticket:
        with self.lock:
            self.tickets[ticket.id] = ticket
        return ticket


store = Store()


def get_store() -> Store:
    return store
