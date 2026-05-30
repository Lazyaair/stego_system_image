#!/usr/bin/env python3
"""
图像质量量化实验：PSNR / SSIM / FID
含密图像 vs 原始扩散模型生成图像（同 seed 配对）

运行: mamba activate sage && python test/image_quality_experiment.py
输出: test/bench_results/image_quality.json + image_quality_table.tex
"""
import os
import sys
import io
import json
import time
import random
import string
from datetime import datetime
from pathlib import Path

os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")

PROJECT_ROOT = Path(__file__).resolve().parent.parent
SERVER_DIR = PROJECT_ROOT / "server"
PULSAR_DIR = str(PROJECT_ROOT / "pulsar")

if str(SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(SERVER_DIR))

import numpy as np
import torch
from PIL import Image

from services.pulsar_service import PulsarService
from services.sparsample_service import SparSampleService

RESULTS_DIR = PROJECT_ROOT / "test" / "bench_results"


def random_key(length=24):
    return ''.join(random.choices(string.ascii_lowercase + string.digits, k=length))


# ── 指标计算 ──────────────────────────────────────────────

def compute_psnr(img_a: np.ndarray, img_b: np.ndarray) -> float:
    mse = np.mean((img_a.astype(np.float64) - img_b.astype(np.float64)) ** 2)
    if mse == 0:
        return float('inf')
    return float(10 * np.log10(255.0 ** 2 / mse))


def compute_ssim(img_a: np.ndarray, img_b: np.ndarray) -> float:
    a = img_a.astype(np.float64)
    b = img_b.astype(np.float64)
    mu_a, mu_b = a.mean(), b.mean()
    sigma_a_sq = ((a - mu_a) ** 2).mean()
    sigma_b_sq = ((b - mu_b) ** 2).mean()
    sigma_ab = ((a - mu_a) * (b - mu_b)).mean()
    C1 = (0.01 * 255) ** 2
    C2 = (0.03 * 255) ** 2
    num = (2 * mu_a * mu_b + C1) * (2 * sigma_ab + C2)
    den = (mu_a ** 2 + mu_b ** 2 + C1) * (sigma_a_sq + sigma_b_sq + C2)
    return float(num / den)


def tensor_to_uint8(t: torch.Tensor) -> np.ndarray:
    """[-1,1] float tensor (1,3,H,W) or (3,H,W) → uint8 (H,W,3)"""
    t = t.detach().cpu().float()
    if t.dim() == 4:
        t = t[0]
    t = ((t + 1.0) * 127.5).clamp(0, 255).byte()
    return t.permute(1, 2, 0).numpy()


# ── InceptionV3 FID ──────────────────────────────────────

class InceptionFID:
    """InceptionV3 pool3 (2048-d) 特征提取 + FID 计算."""

    def __init__(self, device="cuda"):
        from torchvision.models import inception_v3, Inception_V3_Weights
        self.device = device
        self.model = inception_v3(weights=Inception_V3_Weights.DEFAULT)
        self.model.fc = torch.nn.Identity()
        self.model.to(device)
        self.model.eval()

    @torch.no_grad()
    def extract(self, images: np.ndarray) -> np.ndarray:
        """(N,H,W,3) uint8 → (N,2048) float64"""
        from torchvision import transforms
        transform = transforms.Compose([
            transforms.Resize((299, 299)),
            transforms.ToTensor(),
            transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
        ])
        feats = []
        for i in range(0, len(images), 16):
            batch = images[i:i + 16]
            tensors = [transform(Image.fromarray(img)) for img in batch]
            x = torch.stack(tensors).to(self.device)
            f = self.model(x)
            feats.append(f.cpu().numpy())
        return np.concatenate(feats, axis=0).astype(np.float64)

    def compute(self, imgs_a: np.ndarray, imgs_b: np.ndarray) -> float:
        """FID between two image sets."""
        from scipy.linalg import sqrtm
        fa = self.extract(imgs_a)
        fb = self.extract(imgs_b)
        mu_a, mu_b = fa.mean(0), fb.mean(0)
        ca, cb = np.cov(fa, rowvar=False), np.cov(fb, rowvar=False)
        diff = mu_a - mu_b
        cov_mean = sqrtm(ca @ cb)
        if np.iscomplexobj(cov_mean):
            cov_mean = cov_mean.real
        return float(diff @ diff + np.trace(ca + cb - 2 * cov_mean))


# ── Pulsar 配对 ──────────────────────────────────────────

def generate_pulsar_pairs(n_pairs: int, message: str) -> list:
    """
    一次 generate(to_hide=message) 同时获得:
      all0  = 全零编码扩散结果 (干净基线)
      hidden = 含密编码扩散结果
    两者共享相同初始噪声和扩散轨迹, 仅编码步不同.
    """
    print(f"\n{'='*60}")
    print(f"Pulsar: 生成 {n_pairs} 对 (clean=all0, stego=hidden)")
    print(f"{'='*60}")

    pairs = []
    msg_bytes = message.encode("utf-8")

    for i in range(n_pairs):
        key = random_key(24).encode("utf-8")
        try:
            orig_dir = os.getcwd()
            os.chdir(PULSAR_DIR)

            cap = PulsarService.get_capacity("celebahq", key)
            if len(msg_bytes) > cap:
                os.chdir(orig_dir)
                print(f"  [{i+1}/{n_pairs}] SKIP (len>{cap})")
                continue

            # generate_with_regions 内部会调 estimate_regions + generate
            # 一次调用同时得到 all0 (干净基线) 和 hidden (含密)
            svc = PulsarService.get_instance("celebahq", key)
            results = svc.generate_with_regions(msg_bytes)
            last = svc.scheduler.num_inference_steps - 1

            clean_arr = tensor_to_uint8(results["samples"][last]["all0"])
            stego_arr = tensor_to_uint8(results["samples"][last]["hidden"])

            os.chdir(orig_dir)

            psnr = compute_psnr(clean_arr, stego_arr)
            ssim = compute_ssim(clean_arr, stego_arr)
            pairs.append({"clean": clean_arr, "stego": stego_arr, "psnr": psnr, "ssim": ssim})
            print(f"  [{i+1}/{n_pairs}] PSNR={psnr:.2f} dB  SSIM={ssim:.4f}")

        except Exception as e:
            try:
                os.chdir(orig_dir)
            except:
                pass
            print(f"  [{i+1}/{n_pairs}] ERROR: {type(e).__name__}: {e}")

    return pairs


# ── SparSample 配对 ──────────────────────────────────────

def generate_sparsample_pairs(n_pairs: int, message: str) -> list:
    """
    同一扩散运行获得:
      clean = 从 N(mu, sigma) 无约束随机采样 (无消息嵌入)
      stego = sparsample_embed 算术编码嵌入结果
    两者共享相同 mu/sigma, 仅像素选择方式不同.
    """
    from services.sparsample.core import (
        setup_determinism_env, derive_seeds, apply_determinism,
        load_model, make_respaced_betas, sample_to_x1,
        compute_last_step_distribution, sparsample_embed, pack_message,
        gaussian_quantize_256,
        DEFAULT_MODEL_PATH, DEFAULT_NUM_STEPS, DEFAULT_BLOCK_SIZE, NUM_PIXELS,
    )

    print(f"\n{'='*60}")
    print(f"SparSample: 生成 {n_pairs} 对 (clean=random sample, stego=embedded)")
    print(f"{'='*60}")

    device = "cuda" if torch.cuda.is_available() else "cpu"
    model, v_range = load_model(str(DEFAULT_MODEL_PATH), device)
    betas, timestep_map = make_respaced_betas(
        num_steps=DEFAULT_NUM_STEPS, total=1000, device=device
    )

    pairs = []
    for i in range(n_pairs):
        key = random_key(24)
        try:
            setup_determinism_env()
            diffusion_seed, sparsample_seed = derive_seeds(key)

            # 扩散采样到 x_1, 计算最后一步分布
            apply_determinism(diffusion_seed, sparsample_seed)
            x_1 = sample_to_x1(model, betas, timestep_map, device, v_range)
            mu_flat, sigma_flat = compute_last_step_distribution(
                model, x_1, betas, timestep_map, device, v_range
            )

            # --- clean: 从 N(mu,sigma) 量化分布中无约束随机采样 ---
            rng_state = random.getstate()
            np_rng_state = np.random.get_state()
            random.seed(sparsample_seed)
            np.random.seed(sparsample_seed % (2**31))

            clean_flat = np.zeros(NUM_PIXELS, dtype=np.uint8)
            for j in range(NUM_PIXELS):
                probs = gaussian_quantize_256(mu_flat[j], sigma_flat[j])
                clean_flat[j] = int(np.random.choice(256, p=probs))
            clean_arr = clean_flat.reshape(3, 256, 256).transpose(1, 2, 0)

            # --- stego: 算术编码嵌入 ---
            random.seed(sparsample_seed)  # 复位 python random 给 sparsample_embed
            bits, num_blocks = pack_message(message, block_size=DEFAULT_BLOCK_SIZE)
            stego_flat = sparsample_embed(mu_flat, sigma_flat, bits, num_blocks, DEFAULT_BLOCK_SIZE)
            stego_arr = stego_flat.reshape(3, 256, 256).transpose(1, 2, 0)

            # 恢复 RNG
            random.setstate(rng_state)
            np.random.set_state(np_rng_state)

            psnr = compute_psnr(clean_arr, stego_arr)
            ssim = compute_ssim(clean_arr, stego_arr)
            pairs.append({"clean": clean_arr, "stego": stego_arr, "psnr": psnr, "ssim": ssim})
            print(f"  [{i+1}/{n_pairs}] PSNR={psnr:.2f} dB  SSIM={ssim:.4f}")

        except Exception as e:
            print(f"  [{i+1}/{n_pairs}] ERROR: {type(e).__name__}: {e}")

    return pairs


# ── LaTeX 输出 ───────────────────────────────────────────

def generate_latex_table(results: dict) -> str:
    lines = [
        r"\begin{table}[htbp]",
        r"    \caption{含密图像与原始生成图像的质量对比}",
        r"    \centering",
        r"    \begin{tabular}{lccc}",
        r"        \hline",
        r"        \textbf{算法 / 模型} & \textbf{PSNR (dB)} & \textbf{SSIM} & \textbf{FID} \\",
        r"        \hline",
    ]
    for name, d in results.items():
        lines.append(
            f"        {name} & {d['psnr_mean']:.2f} & "
            f"{d['ssim_mean']:.4f} & {d['fid']:.2f} \\\\"
        )
    lines += [
        r"        \hline",
        r"    \end{tabular}",
        r"    \label{tab:image_quality}",
        r"\end{table}",
    ]
    return "\n".join(lines)


# ── 主流程 ───────────────────────────────────────────────

def main():
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    print(f"图像质量量化实验  {timestamp}")
    print(f"GPU: {torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'CPU'}")

    N_PAIRS = 20
    MESSAGE = "Hello, this is a test message for steganography quality evaluation!"

    t_start = time.perf_counter()

    # 生成配对
    pulsar_pairs = generate_pulsar_pairs(N_PAIRS, MESSAGE)
    sparsample_pairs = generate_sparsample_pairs(N_PAIRS, MESSAGE)

    # FID
    fid_pulsar, fid_sparsample = float('nan'), float('nan')
    try:
        device = "cuda" if torch.cuda.is_available() else "cpu"
        fid_calc = InceptionFID(device=device)
        if len(pulsar_pairs) >= 2:
            fid_pulsar = fid_calc.compute(
                np.stack([p["clean"] for p in pulsar_pairs]),
                np.stack([p["stego"] for p in pulsar_pairs]),
            )
            print(f"\n  Pulsar     FID = {fid_pulsar:.2f}")
        if len(sparsample_pairs) >= 2:
            fid_sparsample = fid_calc.compute(
                np.stack([p["clean"] for p in sparsample_pairs]),
                np.stack([p["stego"] for p in sparsample_pairs]),
            )
            print(f"  SparSample FID = {fid_sparsample:.2f}")
    except Exception as e:
        print(f"\n  FID 计算失败: {type(e).__name__}: {e}")

    # 汇总
    results = {}
    for name, pairs, fid in [
        ("Pulsar / CelebA-HQ DDIM", pulsar_pairs, fid_pulsar),
        ("SparSample / FFHQ IDDPM P2", sparsample_pairs, fid_sparsample),
    ]:
        if not pairs:
            continue
        psnrs = [p["psnr"] for p in pairs]
        ssims = [p["ssim"] for p in pairs]
        results[name] = {
            "n_pairs": len(pairs),
            "psnr_mean": round(float(np.mean(psnrs)), 2),
            "psnr_std": round(float(np.std(psnrs, ddof=1)), 2) if len(psnrs) > 1 else 0,
            "ssim_mean": round(float(np.mean(ssims)), 4),
            "ssim_std": round(float(np.std(ssims, ddof=1)), 4) if len(ssims) > 1 else 0,
            "fid": round(fid, 2),
        }

    elapsed = time.perf_counter() - t_start
    print(f"\n总耗时: {elapsed:.0f}s")

    # 保存
    summary = {
        "timestamp": timestamp,
        "gpu": torch.cuda.get_device_name(0) if torch.cuda.is_available() else "CPU",
        "elapsed_sec": round(elapsed, 1),
        "n_pairs_per_algo": N_PAIRS,
        "message": MESSAGE,
        "results": results,
    }
    json_path = RESULTS_DIR / "image_quality.json"
    with open(json_path, "w") as f:
        json.dump(summary, f, indent=2, ensure_ascii=False)
    print(f"JSON → {json_path}")

    tex = generate_latex_table(results)
    tex_path = RESULTS_DIR / "image_quality_table.tex"
    with open(tex_path, "w") as f:
        f.write(tex)
    print(f"LaTeX → {tex_path}")

    # 打印
    print(f"\n{'='*60}")
    print("实验结果汇总")
    print(f"{'='*60}")
    for name, d in results.items():
        print(f"  {name}")
        print(f"    PSNR = {d['psnr_mean']:.2f} ± {d['psnr_std']:.2f} dB")
        print(f"    SSIM = {d['ssim_mean']:.4f} ± {d['ssim_std']:.4f}")
        print(f"    FID  = {d['fid']:.2f}")
    print(f"\n{tex}")


if __name__ == "__main__":
    main()
