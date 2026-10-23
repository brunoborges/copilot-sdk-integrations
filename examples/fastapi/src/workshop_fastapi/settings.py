from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class WorkshopSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="WORKSHOP_", extra="ignore")
    browser: Literal["chromium", "chrome", "msedge", "firefox", "webkit"]
    headless: bool
