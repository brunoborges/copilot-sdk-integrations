from django.contrib import admin
from django.urls import path

from helpdesk import views

urlpatterns = [
    path("admin/", admin.site.urls),
    path("tickets/<int:pk>/triage/", views.triage),
    path("tickets/<int:pk>/chat/", views.chat),
]
