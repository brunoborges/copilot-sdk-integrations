from django.contrib.auth.models import User
from django.test import TestCase

from copilot_django.test import CopilotTestMixin

from .models import Ticket


class TriageTests(CopilotTestMixin, TestCase):
    def test_triage_sets_priority(self):
        user = User.objects.create_user("ana", password="pw")
        ticket = Ticket.objects.create(title="Down", body="Site is down", owner=user)
        self.copilot.agent("triage").calls_tool("get_ticket", {"ticket_id": ticket.pk}).calls_tool(
            "set_priority", {"ticket_id": ticket.pk, "priority": "urgent"}
        ).replies_json({"priority": "urgent", "summary": "The site is down."})

        self.client.force_login(user)
        response = self.client.post(f"/tickets/{ticket.pk}/triage/")

        self.assertEqual(response.json(), {"priority": "urgent", "summary": "The site is down."})
        ticket.refresh_from_db()
        self.assertEqual(ticket.priority, "urgent")
