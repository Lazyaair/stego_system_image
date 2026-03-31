import os
import secrets

# JWT
JWT_SECRET = os.getenv("JWT_SECRET", secrets.token_hex(32))
JWT_ALGORITHM = "HS256"
JWT_EXPIRE_DAYS = int(os.getenv("JWT_EXPIRE_DAYS", "7"))

# Database
DATABASE_PATH = os.getenv("DATABASE_PATH", "stego.db")

# Message Queue
QUEUE_DEFAULT_TTL = int(os.getenv("QUEUE_DEFAULT_TTL", "86400"))  # seconds

# Invite
INVITE_CODE_LENGTH = int(os.getenv("INVITE_CODE_LENGTH", "8"))

# Revoke
REVOKE_TIME_LIMIT = int(os.getenv("REVOKE_TIME_LIMIT", "120"))  # seconds
