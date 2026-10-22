---
title: Example project
---

# Example project

[`python/django/example`](https://github.com/brunoborges/copilot-sdk-integrations/tree/main/python/django/example)
is a small helpdesk site. A `TriageAgent` reads a ticket, sets its priority, and returns a
typed summary.

```python
# helpdesk/copilot_agents.py
class Triage(BaseModel):
    priority: Literal["low", "normal", "urgent"]
    summary: str


class TriageAgent(CopilotAgent):
    instructions = "You triage helpdesk tickets. Be concise and factual."
    tools = [get_ticket, set_priority]
    built_in_tools = False
    permissions = policies.allow_registered_tools()

    @prompt("Read ticket {{ ticket_id }}, set its priority, and summarize it in one sentence.")
    def triage(self, ticket_id: int) -> Triage: ...
```

The views expose `POST /tickets/<id>/triage/` (a typed one-shot call) and
`POST /tickets/<id>/chat/` (a per-ticket conversation). The tools only read and update tickets
owned by the logged-in user.

## Run it

```sh
cd python
uv venv && uv pip install -e core -e django
cd django/example
../../.venv/bin/python manage.py migrate
../../.venv/bin/python manage.py createsuperuser
../../.venv/bin/python manage.py copilot_download_runtime
GITHUB_TOKEN=... ../../.venv/bin/python manage.py runserver
```

Create a ticket in the admin at `/admin/`. Then, from the same browser session, send a `POST`
to `/tickets/1/triage/` with the CSRF token (Django's normal CSRF protection applies).

## Test it

```sh
../../.venv/bin/python manage.py test
```

The test scripts the agent to call both tools and reply with JSON, then checks that the ticket's
priority was really updated in the database.
