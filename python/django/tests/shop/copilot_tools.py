import threading

from copilot_django import ToolContext, copilot_tool

from .models import Order

calls: list[dict] = []


@copilot_tool
def lookup_order(order_id: int, ctx: ToolContext) -> dict:
    """Look up an order by number."""
    order = Order.objects.get(pk=order_id)
    calls.append({"thread": threading.get_ident(), "user": ctx.user, "conversation_id": ctx.conversation_id})
    return {"id": order.pk, "status": order.status}


@copilot_tool(permission_required="shop.refund_order", atomic=True)
def refund_order(order_id: int, fail: bool = False) -> str:
    """Refund an order."""
    order = Order.objects.get(pk=order_id)
    order.status = "refunded"
    order.save()
    if fail:
        raise RuntimeError("payment gateway down")
    return "refunded"


@copilot_tool
async def count_orders() -> int:
    """Count all orders."""
    return await Order.objects.acount()
