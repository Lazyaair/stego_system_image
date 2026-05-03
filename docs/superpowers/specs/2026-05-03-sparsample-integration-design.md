# SparSamp 图像隐写算法集成设计

**日期**:2026-05-03
**作者**:zya + Claude
**阶段**:Phase 1 — 可行性验证 Demo
**状态**:Design approved, pending spec review

## 1. 背景与动机

项目目前已集成 Pulsar 算法作为唯一图像隐写实现(`server/services/pulsar_service.py`),基于 DDPM 扩散模型 + 双轨 all0/all1 变量噪声 + Sage 纠错码,每像素嵌入 1 bit。

现需再集成一个 **SparSamp** 算法作为并列选项。SparSamp 源于论文 *"SparSamp: Efficient Provably Secure Steganography based on Sparse Sampling"*,原作者在其 `./sparsample/` 目录下仅提供了**文本隐写**版本(GPT-2/Llama/Qwen 上逐 token 采样)。论文中图像版本的说明:

> Image generation. We employ a learning-free method for controlling the generation of unconditional DDPM, which is trained on FFHQ dataset. We quantize the continuous probabilities from the last layer into discrete probabilities that correspond to 256 pixel values for sampling, which are then saved as 8-bit images based on the StegaDDPM approach.

源码中**没有**图像分支的实现。项目需要自行把 SparSamp 的"按分布选 token"核心机制,适配到"按分布选像素值"的图像场景。

**SparSamp 与 Pulsar 的关键差异**

| 维度        | Pulsar                                   | SparSamp (图像适配)                             |
|------------|------------------------------------------|-----------------------------------------------|
| 嵌入单位     | 每像素 1 bit(二元选择 all0/all1)         | 每像素 1 个 uint8 值(256 选 1)                 |
| 分布来源     | 固定二元 ~[0.5, 0.5]                      | 扩散末步每像素 N(μ, σ²) 量化成 256 格          |
| 编码机制     | ECC(Sage 库) + 直接 bit-to-pixel 映射     | 算术编码状态机 (n_m, k_m) + top_p 裁剪         |
| 理论保证     | 可证安全,经 ECC 抗信道错误                | 可证安全,无需 ECC(lossless PNG 保真)          |
| 容量         | 固定 = estimate_regions 预估              | 可变 = 图像总熵 / block_size                   |

## 2. 范围

### In Scope(Phase 1 Demo)

- 独立目录 `test/sparsample/`,不动任何 `server/`、`android/`、`web/` 现有文件
- 两个 CLI 脚本:`embed.py`(消息+key → 图像)、`extract.py`(图像+key → 消息)
- 复用用户已下载的 `models/ffhq_p2.pt`(P2-weighting repo 训练的 FFHQ 256 unconditional DDPM,`learn_sigma=True`)
- 内嵌最小化的 `guided_diffusion/` 子模块(从 [P2-weighting](https://github.com/jychoi118/P2-weighting) 抠 UNet 架构代码)
- roundtrip 自测脚本,端到端验证 embed→save→load→extract 一致性

### Out of Scope(留给 Phase 2)

- FastAPI 端点(不修改 `server/api/v1/stego.py`、`server/services/*`)
- Web / Android 前端算法选择器
- 聊天模式下算法协商(WebSocket 协议不动)
- 多模型支持(只跑 FFHQ P2,不含 celebahq/church/...)
- 容量预检接口 / FID / SSIM / 抗扰动实验
- 性能优化(并行嵌入、CDF 查表、GPU 栅格化)

### 非目标(永不做)

- 不抗 JPEG / 缩放 / 噪声(PNG lossless 通路即可)
- 不做 ECC(SparSamp 自身的可证安全不依赖 ECC)
- 不实现多密钥协商 / 密钥交换

## 3. 算法设计

### 3.1 端到端流程

**嵌入(`embed.py`)**

```
INPUT: msg (UTF-8 string), key (string), out_path

1. seed 派生
   - digest = sha256(key)
   - diffusion_seed  = uint32(digest[0:4])    → torch/numpy RNG
   - sparsample_seed = uint32(digest[4:8])    → python random

2. 消息编码
   - msg_bytes = msg.encode("utf-8")
   - bits = 32-bit length header (bytes count, big-endian) || bits(msg_bytes)
   - pad bits to multiple of block_size (=32) with trailing zeros

3. 加载模型
   - UNetModel with FFHQ_P2_CONFIG hyper-params
   - state_dict = torch.load(models/ffhq_p2.pt)
   - model.load_state_dict(state_dict); model.eval(); model.to(device)

4. 正向扩散采样(前 249 步,正常采样)
   - x_T = torch.randn((1, 3, 256, 256))     (被 diffusion_seed 控制)
   - for t in respaced_timesteps[:-1]:
       (eps, v) = split(model(x_t, t))        # learn_sigma: 6 channels
       μ, σ = compute_posterior(eps, v, x_t, t)
       noise = torch.randn_like(x_t)           (seed 控制)
       x_{t-1} = μ + σ * noise

5. 末步量化嵌入(t_last = respaced_timesteps[-1])
   - (eps_last, v_last) = split(model(x_1, t_last))
   - μ_last, σ_last     = compute_posterior(eps_last, v_last, x_1, t_last)
   - 按 C→H→W 栅格遍历 196608 个像素位置:
       probs_256 = gaussian_quantize(μ_last[c,i,j], σ_last[c,i,j], lo=-1, hi=1, bins=256)
       bin_idx, n_m, k_m = sparsample.encode_step(probs_256, n_m, k_m)
       if n_m == 1:
           m_index += block_size
           if m_index >= len(bits):   done = True
           else:                       n_m = 2^block_size; k_m = int(bits[m_index:m_index+block_size], 2)
       if done:  bin_idx = np.random.choice(256, p=probs_256)   # 剩余像素按分布随机采
       pixel_uint8[c,i,j] = bin_idx
   - if not done: raise CapacityError

6. 保存 PNG(标准 8-bit RGB,lossless)
   - Image.fromarray(pixel_uint8.transpose(1,2,0)).save(out_path, format="PNG")
```

**提取(`extract.py`)**

```
INPUT: stego_png, key

1. seed 派生(同嵌入)

2. 读 PNG
   - pixel_uint8 = np.array(Image.open(stego_png))[..., :3].transpose(2,0,1)  # (3,256,256)

3. 加载模型(同嵌入)

4. 复现扩散 249 步得 x_1(与嵌入侧的 x_1 逐 bit 相同)
   - 同 seed → 同 initial noise → 同每步 variance_noise → 同 x_1

5. 末步得 μ_last, σ_last(完全由 x_1 和模型决定,与嵌入消息无关)

6. 按相同栅格顺序遍历 196608 像素:
   - probs_256 = gaussian_quantize(μ_last[c,i,j], σ_last[c,i,j], lo=-1, hi=1, bins=256)
   - bin_idx = pixel_uint8[c,i,j]
   - 调 sparsample.decode_spar 的核心逻辑:
       - 用 bin_idx 定位 SE(cumulative_probs 上 bin_idx 的 lower/upper)
       - 更新 n_m, k_m, temp0_arr, temp1_arr, n_m_arr
       - 当 n_m == 1: 回溯计算当前 block_size-bit 块,追加到 message_bits

7. 解消息
   - 去掉 32-bit 长度头,读出原字节数 L
   - msg_bytes = bits_to_bytes(message_bits[32:32 + 8*L])
   - return msg_bytes.decode("utf-8")
```

### 3.2 关键技术细节

#### 3.2.1 seed 派生

```python
def derive_seeds(key: str) -> tuple[int, int]:
    digest = hashlib.sha256(key.encode("utf-8")).digest()   # 32 bytes
    diffusion_seed  = int.from_bytes(digest[0:4], "big")    # uint32
    sparsample_seed = int.from_bytes(digest[4:8], "big")    # uint32
    return diffusion_seed, sparsample_seed
```

两条链独立,避免互相干扰。`torch.manual_seed`、`np.random.seed` 用 diffusion_seed;`random.seed` 用 sparsample_seed(原 `encode_step` 里的 `r = random.random()` 依赖它)。

#### 3.2.2 高斯 → 256 bin 量化

```python
def gaussian_quantize_256(mu: float, sigma: float, lo=-1.0, hi=1.0) -> np.ndarray:
    edges = np.linspace(lo, hi, 257)                 # 257 boundaries → 256 bins
    z = (edges - mu) / sigma
    cdf = scipy.stats.norm.cdf(z)
    probs = cdf[1:] - cdf[:-1]
    probs[0]  += cdf[0]                              # (-∞, lo) → bin 0
    probs[-1] += 1.0 - cdf[-1]                       # [hi, +∞) → bin 255
    probs = np.maximum(probs, 0.0)                   # 防浮点负值
    probs /= probs.sum()                             # 数值归一
    return probs
```

**bin index ↔ uint8 约定**:`bin_idx ∈ {0..255}` 直接等于 PNG 的 uint8 像素值,跳过"取 bin 中心还原为 float x_0 再量化 uint8"这一往返(那样会引入二次量化误差)。bin i 代表的 x_0 区间是 `[-1 + 2i/256, -1 + 2(i+1)/256)`,仅在我们**计算概率**时用到,不用来"反算"连续值。

#### 3.2.3 SparSamp `encode_step` 移植

直接从 `sparsample/Artifact new/Basic Test/sparsamp.py` 抄过来,**一字不改**:

```python
def encode_step(probs, n_m, k_m):
    r = random.random()
    cumulative_probs = probs.cumsum(0)
    r_i_m = func_mrn(k_m, n_m, r)
    token_index = (cumulative_probs > r_i_m).nonzero()[0].item()
    SE = get_lower_upper_bound(cumulative_probs, token_index)
    temp0 = ceil((SE[0] - r) * n_m)
    temp1 = ceil((SE[1] - r) * n_m)
    if k_m + r * n_m >= n_m:   k_m = k_m - n_m - temp0
    else:                      k_m = k_m - temp0
    n_m = temp1 - temp0
    return token_index, n_m, k_m
```

原作者的文本版外层循环(per-token + 模型 forward)**不抄**,我们用自己的 for-loop 喂预计算好的 probs 序列。`decode_spar` 的主循环同样移植,只把 `for tokenID in generated_ids` 换成 `for (bin_idx, probs) in zip(pixel_vals, probs_list)`。

#### 3.2.4 数值稳定性

- σ 在末步若极小(<1e-6),高斯 CDF 会饱和,`probs.sum()` 可能小于 1 — 归一化处理。
- `probs = np.maximum(probs, 0.0)` 作为安全网,防止浮点误差产生负极小值破坏 `cumsum` 单调性。
- `encode_step` 用 `torch.float64` 精度计算,与原作者保持一致。
- 全链 FP32 推理,不启用 FP16。

## 4. 模块与文件布局

```
test/sparsample/
├── guided_diffusion/              # 从 P2-weighting 抠最小子集
│   ├── __init__.py
│   ├── unet.py                    # UNetModel 类(必须与训练架构一致才能 load_state_dict)
│   ├── nn.py                      # SiLU / timestep_embedding / GroupNorm32 辅助层
│   ├── fp16_util.py               # convert_module_to_{f16,f32} 辅助(保留以兼容可能的 fp16 权重)
│   └── LICENSE                    # 原仓库的 license
├── core.py                        # 业务逻辑(单文件)
│   ├── FFHQ_P2_CONFIG             # 硬编码 UNet/diffusion 超参
│   ├── load_model(pt_path, device)
│   ├── derive_seeds(key)
│   ├── make_respaced_betas(num_steps=250, total=1000)
│   ├── sample_to_x1(model, seed, betas, timesteps)           # 前 N-1 步
│   ├── compute_last_step_distribution(model, x1, t_last, betas)
│   ├── gaussian_quantize_256(mu, sigma)
│   ├── encode_step / decode_step                              # 移植自 sparsamp.py
│   ├── sparsample_embed(probs_list, bits, seed, block_size)
│   ├── sparsample_extract(probs_list, uint8_flat, seed, block_size)
│   ├── pack_message / unpack_message                          # 长度头 + utf-8
├── embed.py                       # CLI
├── extract.py                     # CLI
├── test_roundtrip.py              # 端到端自测
└── README.md                      # 运行说明 + Known Limitations
```

### 设计取舍

- **`core.py` 一个大文件**:demo 阶段反抗过早拆分,一个文件看完整条管线。Phase 2 接入 service 时再拆。
- **`sparsample_embed/extract` 不依赖扩散模型**:签名 `(probs_list, bits) → pixel_vals`,只管"分布 → 像素"。可独立测试,可在 Phase 2 替换模型。
- **`guided_diffusion/` 内嵌**:demo 自包含,不做 submodule/pip install,环境零额外配置。保留原 license 合规。

## 5. CLI

### embed.py

```bash
python test/sparsample/embed.py \
    --msg "Hello, SparSamp!" \
    --key "demo-key-2024" \
    --out test/sparsample/outputs/stego.png \
    [--model models/ffhq_p2.pt] \
    [--steps 250] \
    [--block-size 32] \
    [--device cuda|cpu|auto]
```

标准输出样例:
```
[embed] key-seeds: diffusion=0x1a2b3c4d, sparsample=0x5e6f7a8b
[embed] loading model: models/ffhq_p2.pt ... ok (device=cuda)
[embed] message: 16 bytes → 128 bits + 32-bit header → 192 bits padded
[embed] running diffusion (250 steps) ...
[embed] quantizing last step (196608 pixel positions) ...
[embed] sparsample embedding ... done (192 bits embedded)
[embed] saved to test/sparsample/outputs/stego.png
```

错误态(exit 1 + 报错信息):模型文件不存在、key 为空、消息超容量。

### extract.py

```bash
python test/sparsample/extract.py \
    --img test/sparsample/outputs/stego.png \
    --key "demo-key-2024" \
    [--model models/ffhq_p2.pt] \
    [--steps 250] \
    [--block-size 32] \
    [--device cuda|cpu|auto]
```

**硬性约束**:`--model / --steps / --block-size` 必须和嵌入时一致,否则 μ/σ/probs 对不上 → 解码垃圾。README 加粗说明。

错误态:长度头不合理(>图像容量字节数)、UTF-8 decode 失败 — 均提示"key 或参数不匹配"。

## 6. 测试与验收

### 6.1 `test_roundtrip.py`

三个固定用例:

```python
# Case 1: 短英文
assert extract(embed("Hello, SparSamp!", "demo-key-2024")) == "Hello, SparSamp!"

# Case 2: 中文 + emoji
assert extract(embed("可证安全隐写 — 🎯", "毕设-演示")) == "可证安全隐写 — 🎯"

# Case 3: 错 key 不应巧合还原
try:
    wrong = extract(stego_case1, "wrong-key")
    assert wrong != "Hello, SparSamp!"
except Exception:
    pass   # 抛异常算通过
```

GPU 可用时全跑;CPU 只跑 Case 1(Case 2/3 可选,避免 demo 时间过长)。

### 6.2 验收标准

Phase 1 demo 算完成需同时满足:

1. `python test/sparsample/test_roundtrip.py` 在 `mamba activate sage` 环境里打印 `ALL TESTS PASSED` 并 exit 0
2. 输出 `stego.png` 肉眼看起来像合理的人脸图像(不是纯噪点 / 明显像素级瑕疵)— 证明 sparsample 的像素选择没有严重破坏自然图像统计
3. 用错的 key 跑 extract 不会巧合还原原消息(抛异常或得到乱码均算通过)

### 6.3 明确不要求的

- 不跑抗 JPEG / 抗缩放 / 抗噪声实验
- 不跑容量穷举(不找最大可嵌入字节数)
- 不跑 FID / SSIM / KL 等图像质量指标
- 不和 Pulsar 做性能或容量横向对比
- 不测并发、不测内存占用上限

## 7. Phase 2 集成路径(预览,不在本次实现范围)

Phase 1 demo 验证通过后,Phase 2 的步骤初步设想(详细设计留到 Phase 2 的 spec):

1. 把 `test/sparsample/core.py` 中的 `sparsample_embed / sparsample_extract` 迁移到 `server/services/sparsample_service.py`,签名统一为 `(message: bytes, key: bytes) → png_bytes` / `(png_bytes, key: bytes) → bytes`,与 `PulsarService.embed / extract` 对齐
2. `server/api/v1/stego.py` 在 `embed / extract / max-capacity / capacity` 上新增 `algorithm: "pulsar" | "sparsample"` Form 参数,分支分发
3. 聊天 WebSocket 协议:消息体中加 `algorithm` 字段,发送方写、接收方读。两端按此字段选算法进行 extract
4. Web UI:`StegoToolView` / `ChatView` 增加算法下拉选择器
5. Android UI:`StegoToolScreen` / `ChatScreen` 同样增加

聊天密钥派生规则不变(sender_invite_code + receiver_invite_code),但下发给不同算法的 seed 派生函数不同(Pulsar 直接用 bytes;SparSamp 用本设计 §3.2.1 的 sha256 两条链)。

## 8. 已知风险与缓解

| 风险                                                          | 缓解                                                                  |
|---------------------------------------------------------------|--------------------------------------------------------------------|
| FFHQ P2 UNet 架构在 P2 repo 和 guided-diffusion 原版有细微差异  | 以 `ffhq_p2.pt` 能成功 `load_state_dict` 为准,差异排查到对齐       |
| CPU 单次 embed 3-5 分钟,用户等待体验差                          | Demo 阶段接受;README 提示使用 GPU                                   |
| σ 接近 0 导致分布退化(sparsample 选择退化成确定性)              | `learn_sigma=True` 保障非零 σ;quantize 函数有归一化兜底             |
| Python `random.random()` + torch `manual_seed` 跨平台可复现性  | 假设 Linux x86_64 / Python 3.10 下两端机器一致;跨平台不测           |
| PNG save/load 精度                                            | 标准 PIL PNG 8-bit RGB 无损,uint8 bit-exact 保真                    |

## 9. 参考

- SparSamp 论文:原作者在 `sparsample/` 目录下的 README 与文本版实现
- StegaDDPM [39] in 论文:Peng et al., 2023(论文方法的直接前身)
- P2-weighting repo:https://github.com/jychoi118/P2-weighting
- guided-diffusion 原版:https://github.com/openai/guided-diffusion
- 现有 Pulsar 集成:`server/services/pulsar_service.py`、`server/api/v1/stego.py`
