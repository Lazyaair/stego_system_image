#!/usr/bin/env python3
"""SparSamp extract CLI: stego.png + key → msg"""
import argparse
import os
import sys

os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from core import extract, DEFAULT_MODEL_PATH


def main():
    ap = argparse.ArgumentParser(description="SparSamp image steganography — extract")
    ap.add_argument("--img", required=True, help="input stego PNG path")
    ap.add_argument("--key", required=True, help="shared secret key")
    ap.add_argument("--model", default=DEFAULT_MODEL_PATH,
                    help=f"path to ffhq_p2.pt (default: {DEFAULT_MODEL_PATH})")
    ap.add_argument("--steps", type=int, default=10, help="must match embed (default 10)")
    ap.add_argument("--block-size", type=int, default=32, help="must match embed")
    ap.add_argument("--device", default="auto", choices=["auto", "cpu", "cuda"])
    args = ap.parse_args()

    try:
        msg = extract(
            img_path=args.img,
            key=args.key,
            model_path=args.model,
            num_steps=args.steps,
            block_size=args.block_size,
            device=args.device,
        )
        print("-" * 60)
        print("[extract] recovered message:")
        print(msg)
    except Exception as e:
        print(f"[extract] ERROR: {type(e).__name__}: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
