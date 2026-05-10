import os
import secrets
from pathlib import Path


def _load_or_create_jwt_secret() -> str:
    env = os.getenv("JWT_SECRET")
    if env:
        return env
    # 持久化自动生成的 secret,避免每次 server 重启作废所有已签发 token
    secret_file = Path(__file__).parent / ".jwt_secret"
    if secret_file.exists():
        return secret_file.read_text().strip()
    secret = secrets.token_hex(32)
    secret_file.write_text(secret)
    try:
        os.chmod(secret_file, 0o600)
    except OSError:
        pass
    return secret


# JWT
JWT_SECRET = _load_or_create_jwt_secret()
JWT_ALGORITHM = "HS256"
JWT_EXPIRE_DAYS = int(os.getenv("JWT_EXPIRE_DAYS", "7"))

# Database — 默认放在 server/ 目录下,避免随启动 cwd 飘移
DATABASE_PATH = os.getenv(
    "DATABASE_PATH", str(Path(__file__).parent / "stego.db")
)

# Message Queue
QUEUE_DEFAULT_TTL = int(os.getenv("QUEUE_DEFAULT_TTL", "86400"))  # seconds

# Invite
INVITE_CODE_LENGTH = int(os.getenv("INVITE_CODE_LENGTH", "8"))

# Revoke
REVOKE_TIME_LIMIT = int(os.getenv("REVOKE_TIME_LIMIT", "120"))  # seconds
