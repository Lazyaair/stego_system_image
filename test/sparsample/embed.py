#!/usr/bin/env python3
"""SparSamp embed CLI: msg + key → stego.png"""
import argparse
import os
import sys

# Must be set BEFORE torch is imported for CUDA determinism
os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")

# Make this script runnable from anywhere
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from core import embed, DEFAULT_MODEL_PATH


def main():
    ap = argparse.ArgumentParser(description="SparSamp image steganography — embed")
    ap.add_argument("--msg", required=True, help="plaintext message (UTF-8)")
    ap.add_argument("--key", required=True, help="shared secret key")
    ap.add_argument("--out", required=True, help="output PNG path")
    ap.add_argument("--model", default=DEFAULT_MODEL_PATH,
                    help=f"path to ffhq_p2.pt (default: {DEFAULT_MODEL_PATH})")
    ap.add_argument("--steps", type=int, default=10,
                    help="diffusion steps (default 10 for demo speed; 250 for quality)")
    ap.add_argument("--block-size", type=int, default=32, help="sparsample block size (default 32)")
    ap.add_argument("--device", default="auto", choices=["auto", "cpu", "cuda"])
    args = ap.parse_args()

    try:
        embed(
            msg=args.msg,
            key=args.key,
            out_path=args.out,
            model_path=args.model,
            num_steps=args.steps,
            block_size=args.block_size,
            device=args.device,
        )
    except Exception as e:
        print(f"[embed] ERROR: {type(e).__name__}: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
