import json

from django.contrib.auth.decorators import login_required
from django.http import JsonResponse
from django.shortcuts import get_object_or_404
from django.views.decorators.http import require_POST

from .copilot_agents import TriageAgent
from .models import Ticket


@login_required
@require_POST
def triage(request, pk):
    ticket = get_object_or_404(Ticket, pk=pk, owner=request.user)
    result = TriageAgent().triage(ticket.pk)
    return JsonResponse(result.model_dump())


@login_required
@require_POST
def chat(request, pk):
    ticket = get_object_or_404(Ticket, pk=pk, owner=request.user)
    message = json.loads(request.body)["message"]
    answer = TriageAgent().conversation(f"ticket-{ticket.pk}").send(message)
    return JsonResponse({"answer": answer})
