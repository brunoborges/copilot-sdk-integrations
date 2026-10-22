from django.urls import path

from shop import views

urlpatterns = [
    path("ask/", views.ask),
    path("aask/", views.aask),
]
