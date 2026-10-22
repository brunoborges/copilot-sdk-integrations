import threading
from types import SimpleNamespace

import pytest
from django.contrib.auth.models import Permission
from django.db import transaction

from copilot_django import CopilotReply
from shop import copilot_tools
from shop.copilot_agents import SupportAgent
from shop.models import Order


@pytest.fixture
def refunder(django_user_model):
    user = django_user_model.objects.create_user("refunder")
    user.user_permissions.add(Permission.objects.get(codename="refund_order"))
    return django_user_model.objects.get(pk=user.pk)


@pytest.mark.django_db
def test_tool_runs_on_caller_thread_and_sees_uncommitted_rows(copilot_runtime):
    with transaction.atomic():
        order = Order.objects.create(status="shipped")
        copilot_runtime.agent("support").calls_tool("lookup_order", {"order_id": order.pk}).replies("done")
        reply = SupportAgent(user=None).ask("where?", result=CopilotReply)
    assert reply.text == "done"
    (call,) = copilot_tools.calls
    assert call["thread"] == threading.get_ident()
    assert copilot_runtime.tool_calls("support")[0].text == f'{{"id": {order.pk}, "status": "shipped"}}'


@pytest.mark.django_db
def test_missing_objects_become_not_found(copilot_runtime):
    copilot_runtime.agent("support").calls_tool("lookup_order", {"order_id": 999}).replies("sorry")
    SupportAgent(user=None).ask("x")
    call = copilot_runtime.tool_calls("support")[0]
    assert not call.ok and call.text == "Not found."


@pytest.mark.django_db
def test_permission_required_denies_users_without_permission(copilot_runtime, django_user_model):
    user = django_user_model.objects.create_user("nobody")
    order = Order.objects.create()
    copilot_runtime.agent("support").calls_tool("refund_order", {"order_id": order.pk}).replies("no")
    SupportAgent(user=user).ask("refund")
    call = copilot_runtime.tool_calls("support")[0]
    assert call.approved and not call.ok
    assert "Permission denied" in call.text
    order.refresh_from_db()
    assert order.status == "open"


@pytest.mark.django_db
def test_permission_required_allows_and_atomic_rolls_back(copilot_runtime, refunder):
    ok, failing = Order.objects.create(), Order.objects.create()
    copilot_runtime.agent("support").calls_tool("refund_order", {"order_id": ok.pk}).calls_tool(
        "refund_order", {"order_id": failing.pk, "fail": True}
    ).replies("done")
    SupportAgent(user=refunder).ask("refund both")
    first, second = copilot_runtime.tool_calls("support")
    assert first.ok and first.text == "refunded"
    assert not second.ok and "payment gateway" not in second.text
    ok.refresh_from_db()
    failing.refresh_from_db()
    assert (ok.status, failing.status) == ("refunded", "open")


@pytest.mark.django_db
def test_callers_transaction_rollback_undoes_tool_writes(copilot_runtime, refunder):
    order = Order.objects.create()
    copilot_runtime.agent("support").calls_tool("refund_order", {"order_id": order.pk}).replies("done")
    with pytest.raises(ZeroDivisionError), transaction.atomic():
        SupportAgent(user=refunder).ask("refund")
        1 / 0
    order.refresh_from_db()
    assert order.status == "open"


def test_atomic_requires_sync_tool():
    from copilot_django import CopilotConfigurationError, copilot_tool

    with pytest.raises(CopilotConfigurationError):

        @copilot_tool(atomic=True, register=False)
        async def nope() -> str:
            """No."""
            return ""


@pytest.mark.django_db(transaction=True)
@pytest.mark.asyncio
async def test_async_api_with_sync_and_async_tools(copilot_runtime):
    order = await Order.objects.acreate(status="packed")
    copilot_runtime.agent("support").calls_tool("lookup_order", {"order_id": order.pk}).calls_tool(
        "count_orders"
    ).replies("async done")
    reply = await SupportAgent(user=None).aask("status?", result=CopilotReply)
    assert reply.text == "async done"
    lookup, count = copilot_runtime.tool_calls("support")
    assert lookup.ok and '"packed"' in lookup.text
    assert count.ok and count.text == "1"
    assert copilot_tools.calls[0]["thread"] != threading.get_ident()


@pytest.mark.django_db
def test_tools_are_plain_callables():
    order = Order.objects.create(status="x")
    ctx = SimpleNamespace(user=None, conversation_id=None)
    assert copilot_tools.lookup_order(order.pk, ctx=ctx)["status"] == "x"
