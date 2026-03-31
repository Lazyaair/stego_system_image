from dataclasses import dataclass
from datetime import datetime


@dataclass
class User:
    id: str
    username: str
    password_hash: str
    created_at: datetime | None = None
