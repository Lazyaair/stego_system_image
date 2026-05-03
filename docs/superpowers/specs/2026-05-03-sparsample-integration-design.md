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
| 理论保证     | 可证安全(Pulsar 论文);ECC 用于 Pulsar 自身二元通道的概率误码  | 可证安全(SparSamp 论文);本 demo 的 PNG 通路无损,不涉 ECC  |
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
   - msg_bytes = msg.encode("utf-8");  L = len(msg_bytes)
   - bits = 32-bit BE length header (value = L) || bits(msg_bytes)
   - num_blocks = ceil(len(bits) / block_size)
   - pad bits to num_blocks * block_size with trailing zeros (0..block_size-1 zero bits)
   - num_blocks 是整个协议的停止计数器:sender/receiver 都迭代到"第 num_blocks 个
     block 关闭"即停止 sparsample 循环。

3. 加载模型
   - UNetModel with FFHQ_P2_CONFIG hyper-params (见 §3.2.5)
   - state_dict = torch.load(models/ffhq_p2.pt, map_location=device)
   - model.load_state_dict(state_dict); model.eval(); model.to(device)
   - 确定性开关(§3.2.6):torch.use_deterministic_algorithms(True); cudnn.deterministic=True

4. 正向扩散采样(前 249 步,正常采样)
   - x_T = torch.randn((1, 3, 256, 256))     (被 diffusion_seed 控制)
   - for t in respaced_timesteps[:-1]:        # 索引 0..248
       model_out = model(x_t, t)             # (1, 6, 256, 256) FP32
       eps, v    = model_out.chunk(2, dim=1) # each (1, 3, 256, 256)
       μ_t, σ_t  = p_mean_variance(eps, v, x_t, t)     # 公式见 §3.2.5
       noise     = torch.randn_like(x_t)               (seed 控制)
       x_{t-1}   = μ_t + σ_t * noise                   # 标准 IDDPM 采样

5. 末步量化嵌入(t_last = respaced_timesteps[-1],即最后一步)
   - model_out = model(x_1, t_last)          # (1, 6, 256, 256)
   - eps_last, v_last = model_out.chunk(2, dim=1)
   - μ_last, σ_last   = p_mean_variance(eps_last, v_last, x_1, t_last)
   - 栅格铺平(C→H→W,numpy/torch 默认 flatten 顺序):
       MU    = μ_last.flatten().to(float64).cpu().numpy()    # (196608,)
       SIGMA = σ_last.flatten().to(float64).cpu().numpy()
   - 状态初始化:
       blocks_done = 0
       m_index     = 0
       n_m         = 2^block_size
       k_m         = int(bits[0:block_size], 2)
       pixel_flat  = np.zeros(196608, dtype=np.uint8)
   - 逐像素循环(i = 0, 1, ..., 196607):
       probs = gaussian_quantize_256(MU[i], SIGMA[i])   # np.ndarray[256] FP64
       if blocks_done < num_blocks:
           # 正常编码路径:encode_step 内部会调用一次 random.random(),必须如此
           # 才能与 receiver 的 random.random() 消费节奏一一对应。
           bin_idx, n_m, k_m = encode_step(
               torch.tensor(probs, dtype=torch.float64), n_m, k_m
           )
           if n_m == 1:
               blocks_done += 1
               m_index     += block_size
               if blocks_done < num_blocks:
                   n_m = 2**block_size
                   k_m = int(bits[m_index:m_index + block_size], 2)
               else:
                   # 最后一个 block 刚关闭;跳出主循环,后续像素用填充路径
                   last_encoded_i = i
                   pixel_flat[i]  = bin_idx
                   break        # 必须 break — receiver 也在同 i 处停止
       pixel_flat[i] = bin_idx
   - 容量不足检查:
       if blocks_done < num_blocks:
           raise CapacityError(blocks_done, num_blocks, m_index, len(bits))
   - 后续像素填充(i = last_encoded_i + 1 .. 196607):
       # 不再调用 random.random() / encode_step;使用 numpy RNG 按分布随机采,
       # 保持图像自然;receiver 不迭代这些像素,故 python random 状态分叉无害。
       for i in range(last_encoded_i + 1, 196608):
           probs = gaussian_quantize_256(MU[i], SIGMA[i])
           pixel_flat[i] = int(np.random.choice(256, p=probs))

6. 保存 PNG(标准 8-bit RGB,lossless)
   - arr = pixel_flat.reshape(3, 256, 256).transpose(1, 2, 0)   # (256, 256, 3)
   - Image.fromarray(arr, mode="RGB").save(out_path, format="PNG")
```

**提取(`extract.py`)**

```
INPUT: stego_png, key

1. seed 派生(同嵌入)
   - 同时设置 torch.manual_seed / np.random.seed / random.seed,和嵌入侧一致顺序

2. 读 PNG
   - img      = np.array(Image.open(stego_png), dtype=np.uint8)   # (256, 256, 3)
   - pixel    = img.transpose(2, 0, 1).reshape(-1)                # (196608,) C→H→W

3. 加载模型(同嵌入;确定性开关同样打开)

4. 复现扩散 249 步得 x_1(与嵌入侧的 x_1 逐 bit 相同)
   - 同 seed + 同确定性设置 → 同 initial noise → 同每步 variance_noise → 同 x_1
   - 浮点位级一致性依赖 §3.2.6 的 cuDNN 配置

5. 末步得 μ_last, σ_last
   - μ_last, σ_last 完全由 x_1 和模型决定,与嵌入消息无关
   - 同嵌入一样 flatten 成 MU, SIGMA (196608,)FP64 numpy

6. 逐像素 decode 循环(最多 196608 次,但会提前在 num_blocks_expected 达到时停止)
   - 状态初始化:
       n_m           = 2^block_size
       temp0_arr     = []
       n_m_arr       = []
       decoded_blocks   = []      # List[int],每个元素是一个 block_size-bit 数
       blocks_done      = 0
       num_blocks_expected = None     # 解出首块后填入
   - for i in range(196608):
       # ★ 必须先调用 random.random(),与嵌入侧 encode_step 内部同序消费 RNG
       r = random.random()
       probs = gaussian_quantize_256(MU[i], SIGMA[i])
       cumulative = torch.tensor(probs, dtype=torch.float64).cumsum(0)
       bin_idx  = pixel[i]
       SE       = get_lower_upper_bound(cumulative, bin_idx)    # utils.py:17 移植
       temp0    = ceil((SE[0] - r) * n_m)
       temp1    = ceil((SE[1] - r) * n_m)
       n_m      = temp1 - temp0
       temp0_arr.append(temp0)
       n_m_arr.append(n_m)
       if n_m == 1:
           # 回溯计算当前 block_size-bit 块(原 decode_spar:120-131 逻辑)
           count = len(temp0_arr) - 2
           k_m   = temp0_arr[count + 1]
           while count >= 0:
               nn   = n_m_arr[count]
               k_m  = temp0_arr[count] + ((k_m + nn) % nn)
               count -= 1
           k_m = (k_m + 2**block_size) % 2**block_size
           decoded_blocks.append(k_m)
           temp0_arr, n_m_arr, n_m = [], [], 2**block_size
           blocks_done += 1

           # 首块读出长度头,推断剩余总块数
           if blocks_done == 1:
               L = decoded_blocks[0]                             # 32-bit = block_size 时直接是长度
               # 防御:L 超出合理上界 → key 错或 PNG 损坏
               max_payload_bytes = (196608 - 32) // 8            # 粗上界
               if L < 0 or L > max_payload_bytes:
                   raise DecodeError(f"length header {L} out of range, key/params mismatch")
               num_blocks_expected = ceil((32 + 8 * L) / block_size)
           if num_blocks_expected is not None and blocks_done >= num_blocks_expected:
               break         # 与 sender 同 i 处停止 — RNG 双链此后分叉无害

   # 若遍历完 196608 像素仍未 break:key 错 / PNG 被改 / 模型不一致
   if num_blocks_expected is None or blocks_done < num_blocks_expected:
       raise DecodeError("insufficient blocks decoded — key or params mismatch")

7. 解消息
   - all_bits = "".join(format(b, f"0{block_size}b") for b in decoded_blocks)
   - L        = int(all_bits[0:32], 2)            # 重新读一遍(与第一步一致,作校验)
   - payload  = all_bits[32:32 + 8 * L]
   - msg_bytes = int(payload, 2).to_bytes(L, "big")   # 或等价的 bitstring→bytes
   - return msg_bytes.decode("utf-8")                 # UTF-8 失败即抛 DecodeError
```

**sender/receiver 同步不变量证明(关键)**

1. 前 249 步扩散:seed 相同 + cuDNN 确定性 → x_1 逐 bit 相同。
2. 末步模型调用:x_1 相同 → (eps_last, v_last) 相同 → MU, SIGMA 相同 → probs 相同。
3. 主循环:两端均在 i = 0..last_encoded_i 每步调用一次 random.random()。
   - sender:encode_step 内部调 random.random()(编码路径)
   - receiver:循环开头调 random.random()(解码路径)
   - 每个 i 处两端消费完全相同的 r 值序列。
4. 停止点对齐:sender 在第 num_blocks 个 block 关闭(n_m==1)后 break;receiver 在
   blocks_done 达到 num_blocks_expected = num_blocks(由首块长度头解出)后 break。
   两者的 break 发生在同一个 i。
5. break 后:sender 调用 numpy 路径填充剩余像素,不消费 python random;receiver 不
   迭代剩余像素。两端的 python random 状态此后自然分叉,但不影响任何共同消费的量。

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
    """
    mu, sigma: 已 cast 为 np.float64(见下文数值约定)
    返回: np.ndarray(dtype=float64, shape=(256,)), 元素 >=0, sum==1.0
    """
    edges = np.linspace(lo, hi, 257, dtype=np.float64)   # 257 boundaries → 256 bins
    z     = (edges - mu) / sigma                          # FP64
    cdf   = scipy.stats.norm.cdf(z)                       # FP64
    probs = cdf[1:] - cdf[:-1]
    probs[0]  += cdf[0]                                   # (-∞, lo) → bin 0
    probs[-1] += 1.0 - cdf[-1]                            # [hi, +∞) → bin 255
    probs = np.maximum(probs, 0.0)                        # 防浮点负值
    probs /= probs.sum()                                  # 数值归一,消除舍入漂移
    return probs
```

**数值精度约定(sender/receiver 必须完全一致的步骤顺序)**

1. Model forward 输出是 FP32 tensor。
2. μ_last, σ_last 在 `p_mean_variance`(§3.2.5)内部用 FP32 计算完毕。
3. flatten 后**再** `.to(torch.float64).cpu().numpy()`,FP32→FP64 的 cast 顺序两端一致。
4. `gaussian_quantize_256` 全程 FP64 计算。
5. `encode_step` 将返回的 `np.ndarray[256]` 包装成 `torch.tensor(dtype=torch.float64)` 后使用。

**bin index ↔ uint8 约定**:`bin_idx ∈ {0..255}` 直接等于 PNG 的 uint8 像素值;bin i 代表的 x_0 区间是 `[-1 + 2i/256, -1 + 2(i+1)/256)`,仅在计算概率时用到,不反算连续值。**不做**"取 bin 中心 → 重新量化"这一往返,避免二次量化误差。

**假设:模型输出在 [-1, 1] 像素范围**:`p_mean_variance` 内部会对 `pred_x_0` 做 `clamp(-1, 1)`(见 §3.2.5),保证分布主支在 `[lo, hi]` 内。端点尾部由 bin 0 / bin 255 吸收。

**性能备注**:朴素实现对每个像素独立调 `scipy.stats.norm.cdf` 共 196608 次,估计 CPU 上数十秒。可一次性对整 `edges` 向量用 `scipy.special.ndtr` 批量化(两端实现一致即可)。但 Phase 1 demo 以正确性优先,不必一上来就批量化。

#### 3.2.3 SparSamp encode_step / decode_step 移植与适配

**`encode_step` — 直接从 `sparsample/Artifact new/Basic Test/sparsamp.py:8-23` 抄过来,一字不改**:

```python
def encode_step(probs, n_m, k_m):
    r = random.random()                                    # ★ 必调,消耗 1 次 python random
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

**`decode_step` — 原 `decode_spar`(`sparsamp.py:86-134`)中的单步,我们剥成独立函数供解码主循环调用**。原 `decode_spar` 的 for-loop + n_m==1 回溯逻辑在 §3.1 步骤 6 的主循环里直接展开,不另包成单独函数;但它的**每步核心更新**等价于:

```python
def decode_step(probs, bin_idx, n_m):
    """
    返回 (SE, temp0, temp1, n_m_new),由主循环写进 temp0_arr / n_m_arr。
    每次调用前主循环必须已调 r = random.random() —— 与 encode_step 的 r 对齐。
    """
    # r 由主循环传入或共享作用域,不在本函数内 draw
    cumulative_probs = probs.cumsum(0)
    SE = get_lower_upper_bound(cumulative_probs, bin_idx)   # utils.py:17
    temp0 = ceil((SE[0] - r) * n_m)
    temp1 = ceil((SE[1] - r) * n_m)
    n_m_new = temp1 - temp0
    return SE, temp0, temp1, n_m_new
```

为减少状态耦合,§3.1 步骤 6 的主循环把 `r = random.random()` / `cumulative_probs` / `temp0_arr` / `n_m_arr` / `n_m==1 回溯` 全部**直接内联展开**,不抽 `decode_step` 独立函数 — 对应 §4 文件布局里 `encode_step` 单独列、`decode_step` 不列(§4 同步更新)。

**原文本版外层循环(per-token + 模型 forward)不抄**:我们用自己的 for-loop 喂预计算好的 probs 序列;`get_probs_past` / `indices.sort` / `top_p` 均不使用(256 bin 小,无需裁剪;bin index 本身就是最终索引,无需 `indices` 反查)。

**容量报错细化**:`CapacityError` 需要把 `blocks_done / num_blocks / m_index / len(bits)` 都带上,stderr 打印样例:
```
CapacityError: embedded 4 of 6 blocks (192 of 320 payload bits) before exhausting
196608 pixel positions. Try a shorter message, a larger block_size, or a different key.
```

#### 3.2.4 数值稳定性

- σ 在某步若极小(例如 σ < 1e-7),高斯 CDF 会饱和到 0/1,`probs.sum()` 可能略偏离 1 — 由 `probs /= probs.sum()` 归一吸收。
- `probs = np.maximum(probs, 0.0)` 作为安全网,防止浮点误差产生负极小值破坏 `cumsum` 单调性。
- `encode_step` / decode 主循环用 `torch.float64`,与原作者保持一致。
- **边界 bin 的危险情形**:若 μ 恰好落在某 bin 边界、σ 又很小,发送/接收两端的 FP64 `(cumulative_probs > r_i_m).nonzero()[0]` 可能因任何浮点抖动选到相邻 bin。§3.2.6 的 cuDNN 确定性设置是防止这一点的主要机制;若实测仍偶发失败,fallback 方案是在 `gaussian_quantize_256` 里主动把极小概率(<1e-12)压到 0 并重新归一化,降低边界抖动概率。

#### 3.2.5 `p_mean_variance` — learn_sigma DDPM 的逐像素 μ, σ

采用 IDDPM 论文(Nichol & Dhariwal 2021)的公式,**参考实现路径**为 `guided_diffusion/gaussian_diffusion.py` 里的 `p_mean_variance`(P2-weighting fork 与原 OpenAI repo 此函数等价)。

```python
def p_mean_variance(eps_pred, v_pred, x_t, t, betas):
    """
    eps_pred, v_pred: each FP32 Tensor of shape (1, 3, 256, 256)
    x_t:              FP32 Tensor (1, 3, 256, 256)
    t:                int (timestep index into the respaced schedule)
    betas:            respaced β schedule, shape (num_respaced,), FP64 precomputed
    返回:  mu (1,3,256,256) FP32, sigma (1,3,256,256) FP32
    """
    alphas              = 1.0 - betas
    alphas_cumprod      = torch.cumprod(alphas, dim=0)                 # ᾱ_t
    alphas_cumprod_prev = torch.cat([torch.tensor([1.0]),
                                     alphas_cumprod[:-1]])             # ᾱ_{t-1}
    # 后验方差 β̃_t = β_t * (1 - ᾱ_{t-1}) / (1 - ᾱ_t)
    posterior_variance  = betas * (1 - alphas_cumprod_prev) / (1 - alphas_cumprod)
    posterior_log_var_clipped = torch.log(
        torch.cat([posterior_variance[1:2], posterior_variance[1:]])
    )  # t=0 时 β̃=0,用 t=1 的值 clamp

    # -------- 1. 预测 x_0 --------
    sqrt_recip_alphas_cumprod     = (1.0 / alphas_cumprod[t]).sqrt()
    sqrt_recipm1_alphas_cumprod   = ((1.0 - alphas_cumprod[t]) / alphas_cumprod[t]).sqrt()
    pred_x0 = sqrt_recip_alphas_cumprod * x_t - sqrt_recipm1_alphas_cumprod * eps_pred
    pred_x0 = pred_x0.clamp(-1.0, 1.0)    # ★ guided_diffusion 默认开启 clip_denoised

    # -------- 2. 后验均值 μ --------
    # μ = (sqrt(ᾱ_{t-1}) * β_t / (1 - ᾱ_t)) * x_0 + (sqrt(α_t) * (1 - ᾱ_{t-1}) / (1 - ᾱ_t)) * x_t
    coef1 = (alphas_cumprod_prev[t]).sqrt() * betas[t]          / (1 - alphas_cumprod[t])
    coef2 = (alphas[t]).sqrt()              * (1 - alphas_cumprod_prev[t]) / (1 - alphas_cumprod[t])
    mu = coef1 * pred_x0 + coef2 * x_t

    # -------- 3. 学到的 σ(IDDPM variance interpolation) --------
    # v_pred 的原始输出 ∈ [-1, 1](模型最后一层无 activation,但训练目标使其落入此范围)
    # 转成 [0, 1] 的插值系数
    min_log = posterior_log_var_clipped[t]          # scalar
    max_log = torch.log(betas[t])                    # scalar
    frac    = (v_pred + 1) / 2                       # (1,3,256,256)
    log_variance = frac * max_log + (1 - frac) * min_log
    sigma = (0.5 * log_variance).exp()               # sqrt of variance

    return mu, sigma
```

**实现要点**:

- betas 采用 guided_diffusion 的 `get_named_beta_schedule("linear", 1000)`,然后按 `space_timesteps(1000, "250")` respace。respace 逻辑必须从 guided_diffusion 的 `respace.py` 抠过来(它同时产出新 β 序列,不能简单取子集)。
- `clip_denoised=True` 是 guided_diffusion 的推理默认,必须在 §3.2.5 的 pred_x0 step 保留 `clamp(-1, 1)`。
- `v_pred` 的取值范围约定:某些 P2-weighting 版本输出 `v ∈ [-1, 1]`,某些版本输出 `[0, 1]`。**实现第一步必须**加载 `ffhq_p2.pt` 后对 `v_pred` 做一次 min/max 统计(放在 `test_roundtrip.py` 的 setup 断言里),若发现范围是 `[0, 1]` 则去掉 `frac = (v_pred + 1) / 2`,直接令 `frac = v_pred`。规格以实测为准。

#### 3.2.6 CUDA 确定性与可复现

sender 与 receiver 两侧的 x_1 必须 bit-exact 一致,否则 μ/σ/probs 均偏、边界 bin 翻转。所有会破坏确定性的来源都要关掉:

```python
import os, torch
os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")   # 必须在 import torch 前或首次 CUDA op 前
torch.use_deterministic_algorithms(True, warn_only=False)
torch.backends.cudnn.deterministic = True
torch.backends.cudnn.benchmark     = False
torch.manual_seed(diffusion_seed)
torch.cuda.manual_seed_all(diffusion_seed)
np.random.seed(diffusion_seed)
random.seed(sparsample_seed)
```

`CUBLAS_WORKSPACE_CONFIG` 环境变量**必须**在 CUDA 第一次初始化之前设置,否则 `use_deterministic_algorithms(True)` 会报错。embed.py / extract.py 的开头第一行 python 代码就 `os.environ.setdefault(...)`。

**跨平台前提**:同一机器、同一 CUDA driver、同一 torch 版本之间应可确定复现。跨机器 / 跨 CUDA 版本不在本 demo 的保证范围内,README 写明。

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
│   ├── encode_step                                            # 移植自 sparsamp.py:8-23 (一字不改)
│   │                                                           # decode 逻辑内联在 sparsample_extract 主循环
│   ├── sparsample_embed(probs_list, bits, seed, block_size)
│   ├── sparsample_extract(probs_list, uint8_flat, seed, block_size)
│   ├── pack_message / unpack_message                          # 长度头 + utf-8
├── embed.py                       # CLI
├── extract.py                     # CLI
├── test_roundtrip.py              # 端到端自测
└── README.md                      # 运行说明 + Known Limitations
```

### 设计取舍

- **`core.py` 一个大文件**:demo 阶段反对过早拆分,一个文件看完整条管线。Phase 2 接入 service 时再拆。
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

错误态(exit 1 + 报错信息):
- 模型文件不存在 → `FileNotFoundError: models/ffhq_p2.pt`
- key 为空字符串 → `ValueError: --key must be non-empty`
- 消息超容量 → `CapacityError: embedded X of Y blocks ... Try shorter message / larger block_size / different key`(详见 §3.2.3)

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

错误态:
- PNG 不存在 / 非 RGB 256×256 → `ValueError: expected 256x256 RGB PNG`
- 长度头解出 `L < 0` 或 `L > (196608 - 32) // 8 = 24572` 字节 → `DecodeError: length header ... out of range, key/params mismatch`
- 迭代完所有像素仍未凑齐 `num_blocks_expected` 个块 → `DecodeError: insufficient blocks decoded — key or params mismatch`
- UTF-8 decode 失败 → `DecodeError: payload is not valid utf-8 — key or params mismatch`

所有 `DecodeError` 统一 exit 1。

## 6. 测试与验收

### 6.1 `test_roundtrip.py`

三个固定用例:

```python
# Case 1: 短英文
assert extract(embed("Hello, SparSamp!", "demo-key-2024")) == "Hello, SparSamp!"

# Case 2: 中文 + emoji
assert extract(embed("可证安全隐写 — 🎯", "毕设-演示")) == "可证安全隐写 — 🎯"

# Case 3: 错 key 不应巧合还原 (强化: 若未抛异常,要求 bit 错误率 > 30%)
from test.sparsample.core import extract_raw_bits    # 返回提取出的原始 bit 串,不做 utf-8 decode
try:
    wrong_msg = extract(stego_case1, "wrong-key")
    # 能走到这里说明 DecodeError 没被触发;这种情况下要进一步断言内容差异大
    original_bits = bytes_to_bits("Hello, SparSamp!".encode("utf-8"))
    wrong_bits    = extract_raw_bits(stego_case1, "wrong-key")[:len(original_bits)]
    bit_err_rate  = sum(a != b for a, b in zip(original_bits, wrong_bits)) / len(original_bits)
    assert bit_err_rate > 0.30, f"wrong key recovered too much structure ({bit_err_rate:.2%} error)"
except (DecodeError, UnicodeDecodeError, ValueError):
    pass   # 抛异常也算通过 — 实测错 key 下最常见的路径
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

Phase 1 demo 验证通过后,Phase 2 会开一份独立 spec。大致方向:在 `server/` / `web/` / `android/` 上把 SparSamp 作为与 Pulsar 并列的算法选项接入,包括 API 层 `algorithm` 参数、聊天协议层算法字段、前端 UI 选择器。具体协议设计(尤其是聊天中发送/接收双方如何声明算法、如何处理版本不一致)留到 Phase 2 brainstorm。

## 8. 已知风险与缓解

| 风险                                                                 | 缓解                                                                 |
|---------------------------------------------------------------------|---------------------------------------------------------------------|
| FFHQ P2 UNet 架构在 P2 repo 和 guided-diffusion 原版有细微差异            | 以 `ffhq_p2.pt` 能成功 `load_state_dict` 为准,差异排查到对齐             |
| `p_mean_variance` 实现在不同 fork 间有差异(clip / v_pred 范围 / log_var clamp) | §3.2.5 已 pin 到 guided_diffusion 原版公式 + 明确 `v_pred` 范围需实测断言   |
| CPU 单次 embed 3-5 分钟,用户等待体验差                                    | Demo 阶段接受;README 提示使用 GPU                                      |
| σ 接近 0 导致分布退化(sparsample 选择退化成确定性)                         | `learn_sigma=True` 保障非零 σ;quantize 函数 max(0)+归一化兜底             |
| cuDNN 非确定性导致两端 x_1 不一致,边界 bin 翻转                             | §3.2.6 强制 `use_deterministic_algorithms(True)` + cudnn.deterministic |
| FP32 模型输出 → FP64 概率转换顺序两端不一致                                 | §3.2.2 明确 cast 顺序(flatten → float64 → numpy),两端完全照搬           |
| Python `random.random()` + torch `manual_seed` 跨平台可复现性              | 假设同机器同 CUDA 版本;跨平台不保证,README 写明                           |
| 消息已嵌完后 sender 用 numpy 填像素 / receiver 停循环,python random 状态分叉 | 不影响正确性;见 §3.1 "sender/receiver 同步不变量证明" 第 5 点               |
| 长度头被错 key 解成非法值                                                 | §3.1 步骤 6 显式检查 `L` 范围并 raise `DecodeError`                     |
| PNG save/load 精度                                                   | 标准 PIL PNG 8-bit RGB 无损,uint8 bit-exact 保真                      |

## 9. 参考

- SparSamp 论文:原作者在 `sparsample/` 目录下的 README 与文本版实现
- StegaDDPM [39] in 论文:Peng et al., 2023(论文方法的直接前身)
- P2-weighting repo:https://github.com/jychoi118/P2-weighting
- guided-diffusion 原版:https://github.com/openai/guided-diffusion
- 现有 Pulsar 集成:`server/services/pulsar_service.py`、`server/api/v1/stego.py`
