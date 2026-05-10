"""生成 E2EE 黄金测试向量。Web / Android 两端实现必须能复现每一个字段。

运行:
    python scripts/emit_e2ee_fixture.py

输出:
    docs/superpowers/specs/e2ee-test-vectors.json

常量必须与
    docs/superpowers/specs/2026-05-10-e2e-encrypted-chat-design.md §4
完全一致。
"""

from __future__ import annotations

import hashlib
import json
import os

from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


SEED_SALT = b"stegochat-seed-v1"
MKEY_SALT = b"stegochat-mkey-v1"
MKEY_INFO = b"e2ee"
PBKDF2_ITER = 100_000


def derive_user_key(phrase: str) -> bytes:
    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA256(),
        length=32,
        salt=SEED_SALT,
        iterations=PBKDF2_ITER,
    )
    return kdf.derive(phrase.strip().encode("utf-8"))


def derive_mkey(self_key: bytes, peer_key: bytes) -> bytes:
    if self_key <= peer_key:
        canon = self_key + peer_key
    else:
        canon = peer_key + self_key
    kdf = HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=MKEY_SALT,
        info=MKEY_INFO,
    )
    return kdf.derive(canon)


def xor_bytes(a: bytes, b: bytes) -> bytes:
    assert len(a) == len(b), "xor: length mismatch"
    return bytes(x ^ y for x, y in zip(a, b))


def fingerprint(user_key: bytes) -> bytes:
    return hashlib.sha256(user_key).digest()[:8]


def main() -> None:
    phrase_a = "alice-phrase-2026"
    phrase_b = "bob secret phrase"
    invite_a = "AB12CD34"
    invite_b = "EF56GH78"
    plaintext = "Hello, world!".encode("utf-8")
    fixed_nonce = bytes.fromhex("000102030405060708090a0b")

    uk_a = derive_user_key(phrase_a)
    uk_b = derive_user_key(phrase_b)
    mkey = derive_mkey(uk_a, uk_b)
    assert derive_mkey(uk_b, uk_a) == mkey, "mkey must be symmetric"

    seed = xor_bytes(
        invite_a.encode("utf-8"),
        invite_b.encode("utf-8"),
    )

    aesgcm = AESGCM(mkey)
    ct = aesgcm.encrypt(fixed_nonce, plaintext, associated_data=None)

    fixture = {
        "phrase_A": phrase_a,
        "phrase_B": phrase_b,
        "invite_A": invite_a,
        "invite_B": invite_b,
        "plaintext_utf8": plaintext.decode("utf-8"),
        "fixed_nonce_hex": fixed_nonce.hex(),
        "expected": {
            "user_key_A_hex": uk_a.hex(),
            "user_key_B_hex": uk_b.hex(),
            "fingerprint_A_hex": fingerprint(uk_a).hex(),
            "fingerprint_B_hex": fingerprint(uk_b).hex(),
            "mkey_hex": mkey.hex(),
            "seed_hex": seed.hex(),
            "ciphertext_with_tag_hex": ct.hex(),
        },
    }

    out_dir = os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "..",
        "docs",
        "superpowers",
        "specs",
    )
    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, "e2ee-test-vectors.json")

    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(fixture, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print("wrote", out_path)


if __name__ == "__main__":
    main()
