from django.conf import settings
from django.db import models


class Ticket(models.Model):
    PRIORITIES = [("low", "Low"), ("normal", "Normal"), ("urgent", "Urgent")]

    title = models.CharField(max_length=200)
    body = models.TextField()
    priority = models.CharField(max_length=10, choices=PRIORITIES, default="normal")
    owner = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE)

    def __str__(self):
        return self.title
