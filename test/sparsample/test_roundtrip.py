#!/usr/bin/env python3
"""End-to-end roundtrip self-test for SparSamp demo."""
import os
import sys
import tempfile
import traceback

os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from core import embed, extract, DecodeError


def run_case(name, msg, key, wrong_key=None):
    print(f"\n{'=' * 60}\n[case] {name}\n{'=' * 60}")
    tmp = tempfile.NamedTemporaryFile(suffix=".png", delete=False)
    tmp.close()
    try:
        embed(msg=msg, key=key, out_path=tmp.name)
        recovered = extract(img_path=tmp.name, key=key)
        assert recovered == msg, f"mismatch:\n  expected={msg!r}\n  got={recovered!r}"
        print(f"[case] {name}: roundtrip OK ({len(msg.encode('utf-8'))} bytes)")

        if wrong_key is not None:
            try:
                wrong = extract(img_path=tmp.name, key=wrong_key)
                assert wrong != msg, f"wrong-key recovered original message, expected garbage or error"
                print(f"[case] {name}: wrong-key test OK (returned non-matching string)")
            except (DecodeError, UnicodeDecodeError, ValueError) as e:
                print(f"[case] {name}: wrong-key test OK (raised {type(e).__name__}: {e})")
    finally:
        os.unlink(tmp.name)


def main():
    try:
        run_case(
            "short-ascii",
            msg="Hello, SparSamp!",
            key="demo-key-2024",
            wrong_key="wrong-key",
        )
    except Exception:
        traceback.print_exc()
        print("FAILED")
        sys.exit(1)

    print("\n" + "=" * 60)
    print("ALL TESTS PASSED")
    print("=" * 60)


if __name__ == "__main__":
    main()
