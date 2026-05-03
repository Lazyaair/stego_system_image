"""
SparSamp image steganography demo — core algorithm.

Pipeline (embed / extract symmetric):
    key -> (diffusion_seed, sparsample_seed)
    diffusion_seed -> torch.randn initial noise + per-step variance_noise
    249-step IDDPM sampling (FFHQ P2 learn_sigma UNet) to reach x_1
    last-step learn_sigma model call -> per-pixel (mu, sigma)
    for each pixel: quantize N(mu, sigma) over [-1, 1] into 256 bins -> probs_256
    sparsample encode_step (arithmetic-coding-like state) picks bin_idx per pixel
    bin_idx is saved directly as PNG uint8 value

See docs/superpowers/specs/2026-05-03-sparsample-integration-design.md for the full design.
"""
from __future__ import annotations

import hashlib
import os
import random
from math import ceil
from pathlib import Path
from typing import Tuple, List, Optional

import numpy as np
import torch
from scipy.stats import norm
from PIL import Image

from guided_diffusion.unet import UNetModel


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# FFHQ P2 default hyper-params from P2-weighting README:
# MODEL_FLAGS="--attention_resolutions 16 --class_cond False --diffusion_steps 1000
#   --image_size 256 --learn_sigma True --noise_schedule linear --num_channels 128
#   --num_head_channels 64 --num_res_blocks 1 --resblock_updown True --use_fp16 False
#   --use_scale_shift_norm True"
FFHQ_P2_CONFIG = dict(
    image_size=256,
    in_channels=3,
    model_channels=128,
    out_channels=6,  # learn_sigma=True → eps (3) + v_interp (3)
    num_res_blocks=1,
    attention_resolutions=(16,),  # tuple of downsample ratios
    dropout=0.0,
    channel_mult=(1, 1, 2, 2, 4, 4),  # 256 → 128 → 64 → 32 → 16 → 8 → 4
    conv_resample=True,
    dims=2,
    num_classes=None,
    use_checkpoint=False,
    use_fp16=False,
    num_heads=-1,           # ignored when num_head_channels set
    num_head_channels=64,
    num_heads_upsample=-1,
    use_scale_shift_norm=True,
    resblock_updown=True,
    use_new_attention_order=False,
)

IMAGE_SHAPE = (1, 3, 256, 256)
NUM_PIXELS = 3 * 256 * 256
DEFAULT_NUM_STEPS = 10   # demo default; bump via --steps for quality
DEFAULT_TOTAL_STEPS = 1000
DEFAULT_BLOCK_SIZE = 32

# Project root = two levels up from this file (test/sparsample/core.py → bishe/)
PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
DEFAULT_MODEL_PATH = str(PROJECT_ROOT / "models" / "ffhq_p2.pt")


# ---------------------------------------------------------------------------
# Determinism & seeding
# ---------------------------------------------------------------------------

def setup_determinism_env() -> None:
    """Must be called BEFORE any torch.cuda op. Set via os.environ."""
    os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")


def apply_determinism(diffusion_seed: int, sparsample_seed: int) -> None:
    """Seed all RNGs and pin algorithms to deterministic variants."""
    torch.use_deterministic_algorithms(True, warn_only=True)  # warn for ops w/o det kernel
    torch.backends.cudnn.deterministic = True
    torch.backends.cudnn.benchmark = False
    torch.manual_seed(diffusion_seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(diffusion_seed)
    np.random.seed(diffusion_seed)
    random.seed(sparsample_seed)


def derive_seeds(key: str) -> Tuple[int, int]:
    """key -> (diffusion_seed, sparsample_seed), both uint32."""
    if not key:
        raise ValueError("key must be non-empty")
    digest = hashlib.sha256(key.encode("utf-8")).digest()
    diffusion_seed = int.from_bytes(digest[0:4], "big")
    sparsample_seed = int.from_bytes(digest[4:8], "big")
    return diffusion_seed, sparsample_seed


# ---------------------------------------------------------------------------
# Message packing / unpacking
# ---------------------------------------------------------------------------

def pack_message(msg: str, block_size: int = DEFAULT_BLOCK_SIZE) -> Tuple[str, int]:
    """
    msg -> bit-string with 32-bit BE length header, padded to block_size multiple.
    Returns (bits_str, num_blocks).
    """
    msg_bytes = msg.encode("utf-8")
    L = len(msg_bytes)
    if L >= (1 << 32):
        raise ValueError(f"message too long: {L} bytes")
    header_bits = format(L, "032b")
    payload_bits = "".join(format(b, "08b") for b in msg_bytes)
    bits = header_bits + payload_bits
    num_blocks = (len(bits) + block_size - 1) // block_size
    pad_len = num_blocks * block_size - len(bits)
    bits = bits + "0" * pad_len
    return bits, num_blocks


def unpack_message(bits: str) -> str:
    """Inverse of pack_message: strip header + payload, decode utf-8."""
    if len(bits) < 32:
        raise ValueError("bits too short for length header")
    L = int(bits[0:32], 2)
    max_payload_bytes = (NUM_PIXELS - 32) // 8
    if L < 0 or L > max_payload_bytes:
        raise ValueError(
            f"length header {L} out of [0, {max_payload_bytes}], key/params mismatch"
        )
    payload_bits = bits[32:32 + 8 * L]
    if len(payload_bits) < 8 * L:
        raise ValueError("not enough bits for declared payload length")
    msg_bytes = bytes(
        int(payload_bits[i:i + 8], 2) for i in range(0, 8 * L, 8)
    )
    return msg_bytes.decode("utf-8")


# ---------------------------------------------------------------------------
# Gaussian quantization
# ---------------------------------------------------------------------------

def gaussian_quantize_256(mu: float, sigma: float,
                          lo: float = -1.0, hi: float = 1.0) -> np.ndarray:
    """
    Returns np.ndarray shape=(256,), dtype=float64, sum==1.0, nonneg.
    Edge bins absorb (-inf, lo) and [hi, +inf).
    """
    edges = np.linspace(lo, hi, 257, dtype=np.float64)  # 257 edges → 256 bins
    sigma = max(float(sigma), 1e-8)                     # guard against 0
    z = (edges - float(mu)) / sigma
    cdf = norm.cdf(z)
    probs = cdf[1:] - cdf[:-1]
    probs[0] += cdf[0]
    probs[-1] += 1.0 - cdf[-1]
    probs = np.maximum(probs, 0.0)
    s = probs.sum()
    if s <= 0.0:
        # degenerate: put all mass on the nearest bin
        nearest = min(255, max(0, int((float(mu) - lo) / (hi - lo) * 256)))
        probs = np.zeros(256, dtype=np.float64)
        probs[nearest] = 1.0
        return probs
    probs /= s
    return probs


# ---------------------------------------------------------------------------
# SparSamp encode_step (verbatim from sparsample/Artifact new/Basic Test/sparsamp.py)
# ---------------------------------------------------------------------------

def _func_mrn(k_m: int, n_m: int, r: float) -> float:
    """r_i_m = (k_m / n_m + r) mod 1"""
    result = (k_m / n_m) + r
    if result >= 1.0:
        result = result - 1.0
    return result


def _get_lower_upper_bound(cumulative_probs: torch.Tensor, v: int) -> List[float]:
    """SE = [lower, upper] of bin v in cumulative_probs."""
    lower = cumulative_probs[v - 1].item() if v > 0 else 0.0
    upper = cumulative_probs[v].item() if v < len(cumulative_probs) - 1 else 1.0
    return [lower, upper]


def encode_step(probs: torch.Tensor, n_m: int, k_m: int) -> Tuple[int, int, int]:
    """
    One step of SparSamp arithmetic-coding-style state update.
    probs: torch.Tensor(dtype=float64, shape=(256,))
    Returns (bin_idx, new_n_m, new_k_m).
    Always consumes exactly one random.random() call — must stay in sync with decoder.
    """
    r = random.random()
    cumulative_probs = probs.cumsum(0)
    r_i_m = _func_mrn(k_m, n_m, r)
    # find smallest index where cumulative > r_i_m
    mask = cumulative_probs > r_i_m
    nz = mask.nonzero(as_tuple=False)
    if nz.numel() == 0:
        bin_idx = len(probs) - 1  # fallback, shouldn't happen after normalization
    else:
        bin_idx = nz[0].item()
    SE = _get_lower_upper_bound(cumulative_probs, bin_idx)
    temp0 = ceil((SE[0] - r) * n_m)
    temp1 = ceil((SE[1] - r) * n_m)
    if k_m + r * n_m >= n_m:
        k_m = k_m - n_m - temp0
    else:
        k_m = k_m - temp0
    n_m = temp1 - temp0
    return bin_idx, n_m, k_m


# ---------------------------------------------------------------------------
# SparSamp embed / extract — operate on precomputed probs + uint8 pixel streams
# ---------------------------------------------------------------------------

def sparsample_embed(
    mu_flat: np.ndarray,            # shape (NUM_PIXELS,), float64
    sigma_flat: np.ndarray,         # shape (NUM_PIXELS,), float64
    bits: str,
    num_blocks: int,
    block_size: int = DEFAULT_BLOCK_SIZE,
) -> np.ndarray:
    """Returns pixel_flat uint8 array shape=(NUM_PIXELS,)."""
    assert mu_flat.shape == (NUM_PIXELS,), mu_flat.shape
    assert len(bits) == num_blocks * block_size

    pixel_flat = np.zeros(NUM_PIXELS, dtype=np.uint8)
    blocks_done = 0
    m_index = 0
    n_m = 2 ** block_size
    k_m = int(bits[0:block_size], 2)
    last_encoded_i = -1

    for i in range(NUM_PIXELS):
        probs = gaussian_quantize_256(mu_flat[i], sigma_flat[i])
        probs_t = torch.tensor(probs, dtype=torch.float64)

        bin_idx, n_m, k_m = encode_step(probs_t, n_m, k_m)
        pixel_flat[i] = bin_idx

        if n_m == 1:
            blocks_done += 1
            m_index += block_size
            if blocks_done >= num_blocks:
                last_encoded_i = i
                break
            n_m = 2 ** block_size
            k_m = int(bits[m_index:m_index + block_size], 2)

    if blocks_done < num_blocks:
        raise CapacityError(blocks_done, num_blocks, m_index, len(bits))

    # Fill remaining pixels with numpy-random samples (sender only; receiver won't visit).
    # This keeps the image looking natural but uses np RNG, leaving python random untouched.
    for i in range(last_encoded_i + 1, NUM_PIXELS):
        probs = gaussian_quantize_256(mu_flat[i], sigma_flat[i])
        pixel_flat[i] = int(np.random.choice(256, p=probs))

    return pixel_flat


def sparsample_extract(
    mu_flat: np.ndarray,
    sigma_flat: np.ndarray,
    pixel_flat: np.ndarray,
    block_size: int = DEFAULT_BLOCK_SIZE,
) -> str:
    """Returns full bit-string (header + payload + padding zeros)."""
    assert mu_flat.shape == (NUM_PIXELS,)
    assert pixel_flat.shape == (NUM_PIXELS,)

    n_m = 2 ** block_size
    temp0_arr: List[int] = []
    n_m_arr: List[int] = []
    decoded_blocks: List[int] = []
    blocks_done = 0
    num_blocks_expected: Optional[int] = None

    for i in range(NUM_PIXELS):
        r = random.random()                         # MUST match encode_step timing
        probs = gaussian_quantize_256(mu_flat[i], sigma_flat[i])
        cumulative = torch.tensor(probs, dtype=torch.float64).cumsum(0)
        bin_idx = int(pixel_flat[i])
        SE = _get_lower_upper_bound(cumulative, bin_idx)

        temp0 = ceil((SE[0] - r) * n_m)
        temp1 = ceil((SE[1] - r) * n_m)
        n_m = temp1 - temp0
        temp0_arr.append(temp0)
        n_m_arr.append(n_m)

        if n_m == 1:
            # backtrack to recover the k_m for this block
            count = len(temp0_arr) - 2
            k_m = temp0_arr[count + 1]
            while count >= 0:
                nn_val = n_m_arr[count]
                k_m = temp0_arr[count] + ((k_m + nn_val) % nn_val)
                count -= 1
            k_m = (k_m + 2 ** block_size) % (2 ** block_size)
            decoded_blocks.append(k_m)
            temp0_arr, n_m_arr, n_m = [], [], 2 ** block_size
            blocks_done += 1

            # After first block, read length header and compute total blocks.
            if blocks_done == 1:
                L = decoded_blocks[0]
                max_payload_bytes = (NUM_PIXELS - 32) // 8
                if L < 0 or L > max_payload_bytes:
                    raise DecodeError(
                        f"length header {L} out of [0, {max_payload_bytes}] — "
                        "key / params / PNG mismatch"
                    )
                num_blocks_expected = (32 + 8 * L + block_size - 1) // block_size

            if num_blocks_expected is not None and blocks_done >= num_blocks_expected:
                break

    if num_blocks_expected is None or blocks_done < num_blocks_expected:
        raise DecodeError(
            f"only {blocks_done} of {num_blocks_expected} blocks decoded — "
            "key / params / PNG mismatch"
        )

    bits = "".join(format(b, f"0{block_size}b") for b in decoded_blocks)
    return bits


class CapacityError(Exception):
    def __init__(self, blocks_done, num_blocks, m_index, total_bits):
        super().__init__(
            f"embedded {blocks_done} of {num_blocks} blocks "
            f"({m_index} of {total_bits} payload bits) before exhausting pixel capacity. "
            f"Try shorter message, larger --block-size, or different --key."
        )
        self.blocks_done = blocks_done
        self.num_blocks = num_blocks


class DecodeError(Exception):
    pass


# ---------------------------------------------------------------------------
# Respaced betas + timestep_map  (from guided_diffusion/respace.py equivalent)
# ---------------------------------------------------------------------------

def make_respaced_betas(
    num_steps: int = DEFAULT_NUM_STEPS,
    total: int = DEFAULT_TOTAL_STEPS,
    device: str = "cpu",
    dtype: torch.dtype = torch.float64,
) -> Tuple[torch.Tensor, List[int]]:
    """
    Returns:
      betas_respaced: torch.Tensor shape (num_steps,) on given device/dtype
      timestep_map:   list[int] of length num_steps, maps respaced_idx → original_timestep
    """
    # linear β schedule scaled so that β_start/β_end match training
    beta_start = 0.0001 * 1000.0 / total
    beta_end = 0.02 * 1000.0 / total
    betas_orig = np.linspace(beta_start, beta_end, total, dtype=np.float64)
    alphas_orig = 1.0 - betas_orig
    alphas_cumprod_orig = np.cumprod(alphas_orig)

    # uniform-interval selection
    step_size = total / num_steps
    timestep_map = [int(i * step_size) for i in range(num_steps)]

    # reverse derivation of respaced betas from ᾱ at selected steps
    last_alpha_cumprod = 1.0
    betas_respaced_np = []
    for orig_t in timestep_map:
        cur = alphas_cumprod_orig[orig_t]
        betas_respaced_np.append(1.0 - cur / last_alpha_cumprod)
        last_alpha_cumprod = cur
    betas_respaced_np = np.asarray(betas_respaced_np, dtype=np.float64)
    betas_respaced = torch.tensor(betas_respaced_np, dtype=dtype, device=device)
    return betas_respaced, timestep_map


# ---------------------------------------------------------------------------
# p_mean_variance (learn_sigma IDDPM)
# ---------------------------------------------------------------------------

def p_mean_variance(
    eps_pred: torch.Tensor,     # (1, 3, 256, 256) float32
    v_pred: torch.Tensor,       # (1, 3, 256, 256) float32
    x_t: torch.Tensor,          # (1, 3, 256, 256) float32
    t_respaced: int,
    betas: torch.Tensor,        # shape (num_steps,) float64 (or float32)
    v_range: str = "[-1,1]",    # or "[0,1]" — probed by load_model
) -> Tuple[torch.Tensor, torch.Tensor]:
    """Returns (mu, sigma) both shape (1, 3, 256, 256) float32."""
    device, dtype = betas.device, betas.dtype
    alphas = 1.0 - betas
    alphas_cumprod = torch.cumprod(alphas, dim=0)
    alphas_cumprod_prev = torch.cat([
        torch.tensor([1.0], device=device, dtype=dtype),
        alphas_cumprod[:-1],
    ])

    # Posterior variance β̃_t = β_t * (1 - ᾱ_{t-1}) / (1 - ᾱ_t)
    posterior_variance = betas * (1 - alphas_cumprod_prev) / (1 - alphas_cumprod)
    posterior_log_var_clipped = torch.log(torch.cat([
        posterior_variance[1:2], posterior_variance[1:]
    ]))  # clamp index 0 to index 1's value for t=0

    t = t_respaced

    # -------- 1. Predict x_0 --------
    sqrt_recip_alphas_cumprod = (1.0 / alphas_cumprod[t]).sqrt()
    sqrt_recipm1_alphas_cumprod = ((1.0 - alphas_cumprod[t]) / alphas_cumprod[t]).sqrt()
    x_t_64 = x_t.to(dtype)
    eps_64 = eps_pred.to(dtype)
    pred_x0 = sqrt_recip_alphas_cumprod * x_t_64 - sqrt_recipm1_alphas_cumprod * eps_64
    pred_x0 = pred_x0.clamp(-1.0, 1.0)

    # -------- 2. Posterior mean --------
    coef1 = alphas_cumprod_prev[t].sqrt() * betas[t] / (1 - alphas_cumprod[t])
    coef2 = alphas[t].sqrt() * (1 - alphas_cumprod_prev[t]) / (1 - alphas_cumprod[t])
    mu = coef1 * pred_x0 + coef2 * x_t_64

    # -------- 3. Interpolated log-variance --------
    min_log = posterior_log_var_clipped[t]
    max_log = torch.log(betas[t])
    v_64 = v_pred.to(dtype)
    if v_range == "[-1,1]":
        frac = (v_64 + 1.0) / 2.0
    elif v_range == "[0,1]":
        frac = v_64
    else:
        raise ValueError(f"unknown v_range: {v_range}")
    log_variance = frac * max_log + (1.0 - frac) * min_log
    sigma = (0.5 * log_variance).exp()

    return mu.to(torch.float32), sigma.to(torch.float32)


# ---------------------------------------------------------------------------
# Model loading (with v_pred range auto-probe)
# ---------------------------------------------------------------------------

def load_model(pt_path: str, device: str):
    """
    Returns (model, v_range).
    v_range ∈ {"[-1,1]", "[0,1]"} — probed from a zero-input forward pass.
    """
    pt_path = str(pt_path)
    if not os.path.exists(pt_path):
        raise FileNotFoundError(f"model not found: {pt_path}")

    model = UNetModel(**FFHQ_P2_CONFIG)
    state_dict = torch.load(pt_path, map_location="cpu", weights_only=False)
    # P2 ckpts sometimes wrap state in {'state_dict': ...} or similar — normalize
    if isinstance(state_dict, dict) and "state_dict" in state_dict:
        state_dict = state_dict["state_dict"]
    missing, unexpected = model.load_state_dict(state_dict, strict=False)
    if missing or unexpected:
        print(f"[load_model] missing keys: {len(missing)}, unexpected: {len(unexpected)}")
        if len(missing) > 5 or len(unexpected) > 5:
            # show a few to debug mismatches
            print(f"  first missing: {list(missing)[:3]}")
            print(f"  first unexpected: {list(unexpected)[:3]}")
    model.eval()
    model.to(device)

    # Probe v_pred range with a dummy forward pass
    with torch.no_grad():
        x = torch.zeros(IMAGE_SHAPE, device=device, dtype=torch.float32)
        t = torch.tensor([500], device=device, dtype=torch.long)
        out = model(x, t)
        assert out.shape == (1, 6, 256, 256), f"unexpected output shape: {out.shape}"
        v = out.chunk(2, dim=1)[1]
        v_min, v_max = v.min().item(), v.max().item()
        # P2 / IDDPM outputs v ∈ roughly [-1, 1] after training; some forks clamp to [0, 1]
        if v_min >= -0.05 and v_max <= 1.05:
            # could be [0, 1] or narrow subrange of [-1, 1]; check negative prevalence
            if v_min >= -0.001:
                v_range = "[0,1]"
            else:
                v_range = "[-1,1]"
        else:
            v_range = "[-1,1]"
        print(f"[load_model] v_pred observed range: [{v_min:.3f}, {v_max:.3f}] → assuming {v_range}")

    return model, v_range


# ---------------------------------------------------------------------------
# Diffusion sampling up to x_1 (last pre-encoding step)
# ---------------------------------------------------------------------------

@torch.no_grad()
def sample_to_x1(
    model,
    betas: torch.Tensor,
    timestep_map: List[int],
    device: str,
    v_range: str,
) -> torch.Tensor:
    """
    Run IDDPM sampling from t=T-1 down to t=1, returning x_1.
    RNG state (torch + python random) must already be seeded by apply_determinism().
    """
    num_steps = len(timestep_map)
    x_t = torch.randn(IMAGE_SHAPE, device=device, dtype=torch.float32)

    # iterate t_respaced from num_steps-1 down to 1  (we keep x_1 for the caller)
    for t_respaced in range(num_steps - 1, 0, -1):
        orig_t = timestep_map[t_respaced]
        t_tensor = torch.tensor([orig_t], device=device, dtype=torch.long)
        model_out = model(x_t, t_tensor)
        eps_pred, v_pred = model_out.chunk(2, dim=1)
        mu, sigma = p_mean_variance(eps_pred, v_pred, x_t, t_respaced, betas, v_range)
        noise = torch.randn_like(x_t)
        x_t = mu + sigma * noise

    return x_t  # this is x_1 (one step before t=0)


@torch.no_grad()
def compute_last_step_distribution(
    model,
    x_1: torch.Tensor,
    betas: torch.Tensor,
    timestep_map: List[int],
    device: str,
    v_range: str,
) -> Tuple[np.ndarray, np.ndarray]:
    """Returns (mu_flat, sigma_flat) both np.float64 shape (NUM_PIXELS,), C→H→W order."""
    orig_t = timestep_map[0]
    t_tensor = torch.tensor([orig_t], device=device, dtype=torch.long)
    model_out = model(x_1, t_tensor)
    eps_pred, v_pred = model_out.chunk(2, dim=1)
    mu, sigma = p_mean_variance(eps_pred, v_pred, x_1, 0, betas, v_range)
    # Pinned cast order: .flatten().to(float64).cpu().numpy()  (both sides must match)
    mu_flat = mu.flatten().to(torch.float64).cpu().numpy()
    sigma_flat = sigma.flatten().to(torch.float64).cpu().numpy()
    return mu_flat, sigma_flat


# ---------------------------------------------------------------------------
# High-level embed / extract
# ---------------------------------------------------------------------------

def embed(
    msg: str,
    key: str,
    out_path: str,
    model_path: str = DEFAULT_MODEL_PATH,
    num_steps: int = DEFAULT_NUM_STEPS,
    block_size: int = DEFAULT_BLOCK_SIZE,
    device: str = "auto",
    verbose: bool = True,
) -> None:
    setup_determinism_env()
    if device == "auto":
        device = "cuda" if torch.cuda.is_available() else "cpu"

    diffusion_seed, sparsample_seed = derive_seeds(key)
    if verbose:
        print(f"[embed] seeds: diffusion=0x{diffusion_seed:08x}, sparsample=0x{sparsample_seed:08x}")

    apply_determinism(diffusion_seed, sparsample_seed)

    if verbose:
        print(f"[embed] loading model: {model_path} (device={device})")
    model, v_range = load_model(model_path, device)

    betas, timestep_map = make_respaced_betas(num_steps, DEFAULT_TOTAL_STEPS, device=device)

    bits, num_blocks = pack_message(msg, block_size)
    if verbose:
        msg_bytes_len = len(msg.encode("utf-8"))
        print(f"[embed] message: {msg_bytes_len} bytes → {8*msg_bytes_len} payload bits + "
              f"32 header bits → {num_blocks} blocks of {block_size} bits")

    if verbose:
        print(f"[embed] running diffusion ({num_steps} steps) ...")
    # reseed torch/np right before diffusion so receiver sees identical state
    apply_determinism(diffusion_seed, sparsample_seed)
    x_1 = sample_to_x1(model, betas, timestep_map, device, v_range)

    if verbose:
        print(f"[embed] computing last-step distribution ...")
    mu_flat, sigma_flat = compute_last_step_distribution(model, x_1, betas, timestep_map, device, v_range)

    if verbose:
        print(f"[embed] sparsample embedding {num_blocks} blocks ...")
    pixel_flat = sparsample_embed(mu_flat, sigma_flat, bits, num_blocks, block_size)

    # Save as PNG: pixel_flat is (3, 256, 256) flattened in C→H→W order
    arr = pixel_flat.reshape(3, 256, 256).transpose(1, 2, 0)  # (256, 256, 3)
    Path(out_path).parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(arr, mode="RGB").save(out_path, format="PNG")
    if verbose:
        print(f"[embed] saved {out_path}")


def extract(
    img_path: str,
    key: str,
    model_path: str = DEFAULT_MODEL_PATH,
    num_steps: int = DEFAULT_NUM_STEPS,
    block_size: int = DEFAULT_BLOCK_SIZE,
    device: str = "auto",
    verbose: bool = True,
) -> str:
    setup_determinism_env()
    if device == "auto":
        device = "cuda" if torch.cuda.is_available() else "cpu"

    diffusion_seed, sparsample_seed = derive_seeds(key)
    if verbose:
        print(f"[extract] seeds: diffusion=0x{diffusion_seed:08x}, sparsample=0x{sparsample_seed:08x}")

    apply_determinism(diffusion_seed, sparsample_seed)

    if verbose:
        print(f"[extract] loading image: {img_path}")
    img = np.array(Image.open(img_path).convert("RGB"), dtype=np.uint8)
    if img.shape != (256, 256, 3):
        raise ValueError(f"expected 256x256 RGB PNG, got {img.shape}")
    pixel_flat = img.transpose(2, 0, 1).reshape(-1)  # C→H→W

    if verbose:
        print(f"[extract] loading model: {model_path} (device={device})")
    model, v_range = load_model(model_path, device)

    betas, timestep_map = make_respaced_betas(num_steps, DEFAULT_TOTAL_STEPS, device=device)

    if verbose:
        print(f"[extract] running diffusion ({num_steps} steps) ...")
    apply_determinism(diffusion_seed, sparsample_seed)
    x_1 = sample_to_x1(model, betas, timestep_map, device, v_range)

    if verbose:
        print(f"[extract] computing last-step distribution ...")
    mu_flat, sigma_flat = compute_last_step_distribution(model, x_1, betas, timestep_map, device, v_range)

    if verbose:
        print(f"[extract] sparsample extracting ...")
    bits = sparsample_extract(mu_flat, sigma_flat, pixel_flat, block_size)

    msg = unpack_message(bits)
    if verbose:
        print(f"[extract] recovered {len(msg.encode('utf-8'))} bytes")
    return msg
