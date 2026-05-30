#!/usr/bin/env python3
"""
Pulsar vs SparSample 算法对比实验
直接调用 service 层，不经过 server API
需要在 sage 环境中运行: mamba activate sage && python test/benchmark_comparison.py
"""
import os
import sys
import io
import csv
import json
import time
import random
import string
import tempfile
from datetime import datetime
from pathlib import Path

# ── 环境 ──────────────────────────────────────────────
os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")

PROJECT_ROOT = Path(__file__).resolve().parent.parent
SERVER_DIR = PROJECT_ROOT / "server"

# 确保 import 路径正确
if str(SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(SERVER_DIR))

import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

# ── 导入两个算法 service ──────────────────────────────────
from services.pulsar_service import PulsarService
from services.sparsample_service import SparSampleService

# Pulsar 的 SageCode._SCRIPT_DIR = "sage/" 是相对路径，需要从 pulsar/ 目录运行
PULSAR_DIR = str(PROJECT_ROOT / "pulsar")

RESULTS_DIR = PROJECT_ROOT / "test" / "bench_results"

# ── 工具函数 ──────────────────────────────────────────

def random_key(length=24):
    return ''.join(random.choices(string.ascii_lowercase + string.digits, k=length))


def compute_psnr(img_a: np.ndarray, img_b: np.ndarray) -> float:
    """计算 PSNR (uint8 图像)"""
    mse = np.mean((img_a.astype(np.float64) - img_b.astype(np.float64)) ** 2)
    if mse == 0:
        return float('inf')
    return 10 * np.log10(255.0 ** 2 / mse)


def compute_ssim(img_a: np.ndarray, img_b: np.ndarray) -> float:
    """简化 SSIM (全图)"""
    a = img_a.astype(np.float64)
    b = img_b.astype(np.float64)
    mu_a, mu_b = a.mean(), b.mean()
    sigma_a_sq = ((a - mu_a) ** 2).mean()
    sigma_b_sq = ((b - mu_b) ** 2).mean()
    sigma_ab = ((a - mu_a) * (b - mu_b)).mean()
    C1, C2 = (0.01 * 255) ** 2, (0.03 * 255) ** 2
    return float(
        ((2 * mu_a * mu_b + C1) * (2 * sigma_ab + C2)) /
        ((mu_a ** 2 + mu_b ** 2 + C1) * (sigma_a_sq + sigma_b_sq + C2))
    )


def png_to_array(png_bytes: bytes) -> np.ndarray:
    from PIL import Image
    return np.array(Image.open(io.BytesIO(png_bytes)).convert("RGB"))


def save_sample_image(png_bytes: bytes, path: Path):
    from PIL import Image
    Image.open(io.BytesIO(png_bytes)).save(str(path))


# ── 实验 ──────────────────────────────────────────────

def run_capacity_test(keys: list[str]) -> list[dict]:
    """实验 1: 不同密钥下的嵌入容量"""
    print("\n" + "=" * 60)
    print("实验 1: 嵌入容量测试")
    print("=" * 60)
    results = []
    for key in keys:
        key_bytes = key.encode("utf-8")
        # Pulsar (需要在 pulsar/ 目录下运行，因为 SageCode 使用相对路径)
        try:
            orig_dir = os.getcwd()
            os.chdir(PULSAR_DIR)
            t0 = time.perf_counter()
            cap_p = PulsarService.get_capacity("celebahq", key_bytes)
            t_p = time.perf_counter() - t0
            os.chdir(orig_dir)
            results.append({"algorithm": "Pulsar", "key": key, "capacity_bytes": cap_p, "init_time": round(t_p, 2)})
            print(f"  Pulsar     | {key[:20]:20s} | {cap_p:6d} bytes | init {t_p:.1f}s")
        except Exception as e:
            os.chdir(orig_dir)
            print(f"  Pulsar     | {key[:20]:20s} | ERROR: {e}")
            results.append({"algorithm": "Pulsar", "key": key, "capacity_bytes": -1, "error": str(e)})
        # SparSample
        try:
            t0 = time.perf_counter()
            cap_s = SparSampleService.get_capacity("ffhq_p2", key_bytes)
            t_s = time.perf_counter() - t0
            results.append({"algorithm": "SparSample", "key": key, "capacity_bytes": cap_s, "init_time": round(t_s, 2)})
            print(f"  SparSample | {key[:20]:20s} | {cap_s:6d} bytes | init {t_s:.1f}s")
        except Exception as e:
            print(f"  SparSample | {key[:20]:20s} | ERROR: {e}")
            results.append({"algorithm": "SparSample", "key": key, "capacity_bytes": -1, "error": str(e)})
    return results


def run_roundtrip_test(keys: list[str], messages: list[tuple[str, str]]) -> list[dict]:
    """实验 2: 嵌入+提取往返测试"""
    print("\n" + "=" * 60)
    print("实验 2: 嵌入/提取往返测试")
    print("=" * 60)
    results = []
    for key in keys:
        key_bytes = key.encode("utf-8")
        for msg_label, msg in messages:
            for algo_name, Service, model_id, needs_chdir in [
                ("Pulsar", PulsarService, "celebahq", True),
                ("SparSample", SparSampleService, "ffhq_p2", False),
            ]:
                try:
                    if needs_chdir:
                        orig_dir = os.getcwd()
                        os.chdir(PULSAR_DIR)
                    # 检查容量
                    cap = Service.get_capacity(model_id, key_bytes)
                    msg_bytes = msg.encode("utf-8")
                    if len(msg_bytes) > cap:
                        if needs_chdir:
                            os.chdir(orig_dir)
                        print(f"  {algo_name:10s} | {key[:16]}... | {msg_label:8s} | SKIP (len={len(msg_bytes)} > cap={cap})")
                        continue

                    # 嵌入
                    t0 = time.perf_counter()
                    png_data = Service.embed(msg_bytes, model_id, key_bytes)
                    embed_time = time.perf_counter() - t0

                    # 提取
                    t0 = time.perf_counter()
                    extracted = Service.extract(png_data, model_id, key_bytes)
                    extract_time = time.perf_counter() - t0

                    if needs_chdir:
                        os.chdir(orig_dir)

                    # Pulsar ECC 编码会填充到容量上限，去掉尾部 null 再比较
                    extracted_stripped = extracted.rstrip(b'\x00')
                    correct = (extracted_stripped == msg_bytes)

                    # 图像统计
                    img_arr = png_to_array(png_data)

                    row = {
                        "algorithm": algo_name,
                        "key": key,
                        "msg_label": msg_label,
                        "msg_len": len(msg_bytes),
                        "capacity": cap,
                        "embed_time": round(embed_time, 3),
                        "extract_time": round(extract_time, 3),
                        "correct": correct,
                        "img_shape": list(img_arr.shape),
                        "img_mean": round(float(img_arr.mean()), 2),
                        "img_std": round(float(img_arr.std()), 2),
                    }
                    results.append(row)
                    status = "OK" if correct else "FAIL"
                    print(f"  {algo_name:10s} | {key[:16]}... | {msg_label:8s} | "
                          f"embed={embed_time:6.2f}s extract={extract_time:6.2f}s | {status}")

                    # 保存样本图片
                    sample_dir = RESULTS_DIR / "samples"
                    sample_dir.mkdir(parents=True, exist_ok=True)
                    sample_path = sample_dir / f"{algo_name}_{key[:8]}_{msg_label}.png"
                    save_sample_image(png_data, sample_path)

                except Exception as e:
                    if needs_chdir:
                        os.chdir(orig_dir)
                    print(f"  {algo_name:10s} | {key[:16]}... | {msg_label:8s} | ERROR: {type(e).__name__}: {e}")
                    results.append({
                        "algorithm": algo_name, "key": key, "msg_label": msg_label,
                        "msg_len": len(msg.encode("utf-8")), "error": str(e),
                    })
    return results


def run_cross_key_test(keys: list[str]) -> list[dict]:
    """实验 3: 跨密钥提取（错误密钥应失败）"""
    print("\n" + "=" * 60)
    print("实验 3: 跨密钥安全性测试")
    print("=" * 60)
    results = []
    msg = "secret"
    for algo_name, Service, model_id, needs_chdir in [
        ("Pulsar", PulsarService, "celebahq", True),
        ("SparSample", SparSampleService, "ffhq_p2", False),
    ]:
        key_a = keys[0]
        key_b = keys[1] if len(keys) > 1 else random_key()
        key_a_bytes = key_a.encode("utf-8")
        key_b_bytes = key_b.encode("utf-8")
        try:
            if needs_chdir:
                orig_dir = os.getcwd()
                os.chdir(PULSAR_DIR)
            cap = Service.get_capacity(model_id, key_a_bytes)
            if len(msg) > cap:
                if needs_chdir:
                    os.chdir(orig_dir)
                print(f"  {algo_name} | SKIP (cap={cap})")
                continue
            png_data = Service.embed(msg.encode("utf-8"), model_id, key_a_bytes)
            # 用错误密钥提取
            try:
                wrong = Service.extract(png_data, model_id, key_b_bytes)
                cross_match = (wrong.rstrip(b'\x00') == msg.encode("utf-8"))
            except Exception:
                cross_match = False
            if needs_chdir:
                os.chdir(orig_dir)
            safe = not cross_match
            results.append({
                "algorithm": algo_name,
                "embed_key": key_a, "extract_key": key_b,
                "cross_key_match": cross_match, "safe": safe,
            })
            print(f"  {algo_name:10s} | embed_key={key_a[:12]}... extract_key={key_b[:12]}... | "
                  f"{'安全' if safe else '不安全!'}")
        except Exception as e:
            if needs_chdir:
                os.chdir(orig_dir)
            print(f"  {algo_name:10s} | ERROR: {e}")
    return results


# ── 图表生成 ──────────────────────────────────────────

def generate_charts(capacity_data, roundtrip_data):
    fig, axes = plt.subplots(1, 3, figsize=(15, 5))
    colors = {"Pulsar": "#4A90D9", "SparSample": "#E8744F"}

    # 图 1: 容量
    ax = axes[0]
    cap_by = {}
    for r in capacity_data:
        if r.get("capacity_bytes", -1) > 0:
            cap_by.setdefault(r["algorithm"], []).append(r["capacity_bytes"])
    if cap_by:
        algos = sorted(cap_by.keys())
        means = [np.mean(cap_by[a]) for a in algos]
        stds = [np.std(cap_by[a]) for a in algos] if any(len(cap_by[a]) > 1 for a in algos) else None
        bars = ax.bar(algos, means, yerr=stds, color=[colors[a] for a in algos],
                      capsize=6, width=0.5, edgecolor='white', linewidth=1.5)
        for bar, m in zip(bars, means):
            ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 3,
                    f'{m:.0f}', ha='center', va='bottom', fontsize=11, fontweight='bold')
    ax.set_ylabel('Capacity (bytes)', fontsize=12)
    ax.set_title('Embedding Capacity', fontsize=13, fontweight='bold')
    ax.grid(axis='y', alpha=0.3)

    # 图 2: 嵌入耗时
    ax = axes[1]
    embed_by = {}
    for r in roundtrip_data:
        if "embed_time" in r and "error" not in r:
            embed_by.setdefault(r["algorithm"], []).append(r["embed_time"])
    if embed_by:
        algos = sorted(embed_by.keys())
        means = [np.mean(embed_by[a]) for a in algos]
        stds = [np.std(embed_by[a]) for a in algos] if any(len(embed_by[a]) > 1 for a in algos) else None
        bars = ax.bar(algos, means, yerr=stds, color=[colors[a] for a in algos],
                      capsize=6, width=0.5, edgecolor='white', linewidth=1.5)
        for bar, m in zip(bars, means):
            ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.3,
                    f'{m:.1f}s', ha='center', va='bottom', fontsize=11, fontweight='bold')
    ax.set_ylabel('Time (seconds)', fontsize=12)
    ax.set_title('Embedding Time', fontsize=13, fontweight='bold')
    ax.grid(axis='y', alpha=0.3)

    # 图 3: 提取耗时
    ax = axes[2]
    extract_by = {}
    for r in roundtrip_data:
        if "extract_time" in r and "error" not in r:
            extract_by.setdefault(r["algorithm"], []).append(r["extract_time"])
    if extract_by:
        algos = sorted(extract_by.keys())
        means = [np.mean(extract_by[a]) for a in algos]
        stds = [np.std(extract_by[a]) for a in algos] if any(len(extract_by[a]) > 1 for a in algos) else None
        bars = ax.bar(algos, means, yerr=stds, color=[colors[a] for a in algos],
                      capsize=6, width=0.5, edgecolor='white', linewidth=1.5)
        for bar, m in zip(bars, means):
            ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.1,
                    f'{m:.1f}s', ha='center', va='bottom', fontsize=11, fontweight='bold')
    ax.set_ylabel('Time (seconds)', fontsize=12)
    ax.set_title('Extraction Time', fontsize=13, fontweight='bold')
    ax.grid(axis='y', alpha=0.3)

    plt.tight_layout()
    path = RESULTS_DIR / "comparison_chart.png"
    plt.savefig(path, dpi=200, bbox_inches='tight')
    plt.close()
    print(f"  图表 -> {path}")


def generate_latex(capacity_data, roundtrip_data, cross_data) -> str:
    lines = [
        r"\begin{table}[htb!]",
        r"    \caption{Pulsar 与 SparSample 算法性能对比}",
        r"    \centering",
        r"    \begin{tabular}{lcc}",
        r"        \hline",
        r"        \textbf{指标} & \textbf{Pulsar (DDIM)} & \textbf{SparSample (IDDPM P2)} \\",
        r"        \hline",
    ]

    # 容量
    cap_by = {}
    for r in capacity_data:
        if r.get("capacity_bytes", -1) > 0:
            cap_by.setdefault(r["algorithm"], []).append(r["capacity_bytes"])
    if cap_by:
        p = np.mean(cap_by.get("Pulsar", [0]))
        s = np.mean(cap_by.get("SparSample", [0]))
        lines.append(f"        平均嵌入容量 (bytes) & {p:.0f} & {s:.0f} \\\\")

    # 耗时
    eb, xb = {}, {}
    for r in roundtrip_data:
        if "error" in r:
            continue
        if "embed_time" in r:
            eb.setdefault(r["algorithm"], []).append(r["embed_time"])
        if "extract_time" in r:
            xb.setdefault(r["algorithm"], []).append(r["extract_time"])
    if eb:
        lines.append(f"        平均嵌入耗时 (s) & {np.mean(eb.get('Pulsar', [0])):.1f} & {np.mean(eb.get('SparSample', [0])):.1f} \\\\")
    if xb:
        lines.append(f"        平均提取耗时 (s) & {np.mean(xb.get('Pulsar', [0])):.1f} & {np.mean(xb.get('SparSample', [0])):.1f} \\\\")

    # 正确率
    ok, total = {}, {}
    for r in roundtrip_data:
        if "error" in r:
            continue
        a = r["algorithm"]
        total[a] = total.get(a, 0) + 1
        if r.get("correct"):
            ok[a] = ok.get(a, 0) + 1
    if total:
        p_acc = ok.get("Pulsar", 0) / max(total.get("Pulsar", 1), 1) * 100
        s_acc = ok.get("SparSample", 0) / max(total.get("SparSample", 1), 1) * 100
        lines.append(f"        提取正确率 & {p_acc:.0f}\\% & {s_acc:.0f}\\% \\\\")

    # 跨密钥
    if cross_data:
        p_safe = any(r["algorithm"] == "Pulsar" and r.get("safe") for r in cross_data)
        s_safe = any(r["algorithm"] == "SparSample" and r.get("safe") for r in cross_data)
        lines.append(f"        跨密钥安全 & {'是' if p_safe else '否'} & {'是' if s_safe else '否'} \\\\")

    lines += [
        r"        输出图像格式 & 16-bit PNG & 8-bit PNG \\",
        r"        图像分辨率 & 256$\times$256 & 256$\times$256 \\",
        r"        可证安全性 & 是 & 是 \\",
        r"        \hline",
        r"    \end{tabular}",
        r"    \label{tab:algo_comparison}",
        r"\end{table}",
    ]

    tex = "\n".join(lines)
    path = RESULTS_DIR / "comparison_table.tex"
    with open(path, "w") as f:
        f.write(tex)
    print(f"  LaTeX -> {path}")
    return tex


# ── 主流程 ──────────────────────────────────────────────

def main():
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    print(f"Pulsar vs SparSample 对比实验")
    print(f"时间: {timestamp}")
    print(f"结果: {RESULTS_DIR}")

    keys = [random_key(24) for _ in range(5)]
    messages = [
        ("short", "Hello!"),
        ("medium", "This is a benchmark test message for steganography comparison."),
        ("long", "A" * 150),
    ]

    t_start = time.perf_counter()

    cap_data = run_capacity_test(keys)
    rt_data = run_roundtrip_test(keys, messages)
    cross_data = run_cross_key_test(keys)

    elapsed = time.perf_counter() - t_start
    print(f"\n总耗时: {elapsed:.0f}s")

    # 生成图表
    print("\n=== 生成图表 ===")
    generate_charts(cap_data, rt_data)
    tex = generate_latex(cap_data, rt_data, cross_data)

    # 保存原始数据
    summary = {
        "timestamp": timestamp,
        "elapsed_sec": round(elapsed, 1),
        "keys": keys,
        "capacity": cap_data,
        "roundtrip": rt_data,
        "crosskey": cross_data,
    }
    json_path = RESULTS_DIR / f"summary_{timestamp}.json"
    with open(json_path, "w") as f:
        json.dump(summary, f, indent=2, ensure_ascii=False)
    print(f"  JSON -> {json_path}")

    # 打印 LaTeX 表格
    print("\n=== LaTeX 表格 ===")
    print(tex)


if __name__ == "__main__":
    main()
