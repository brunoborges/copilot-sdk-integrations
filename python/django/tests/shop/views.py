from django.http import JsonResponse

from .copilot_agents import SupportAgent


def ask(request):
    agent = SupportAgent()
    conversation = agent.conversation("chat")
    return JsonResponse({"answer": conversation.send(request.GET["q"]), "session": conversation.session_id})


async def aask(request):
    agent = SupportAgent()
    return JsonResponse({"answer": await agent.aask(request.GET["q"], conversation_id="chat")})
