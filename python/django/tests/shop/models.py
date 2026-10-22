from django.conf import settings
from django.db import models


class Order(models.Model):
    status = models.CharField(max_length=20, default="open")
    owner = models.ForeignKey(settings.AUTH_USER_MODEL, null=True, on_delete=models.CASCADE)

    class Meta:
        permissions = [("refund_order", "Can refund orders")]
