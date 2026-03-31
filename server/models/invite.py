from dataclasses import dataclass
from datetime import datetime


@dataclass
class InviteCode:
    code: str
    user_id: str
    created_at: datetime | None = None
