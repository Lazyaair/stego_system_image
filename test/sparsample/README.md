# SparSamp 图像隐写 Demo (Phase 1)

把 SparSamp 算法(原为文本隐写)适配到基于 DDPM 扩散模型的图像隐写。用 FFHQ P2 (learn_sigma) UNet 在末步产生每像素的 Gaussian 分布,量化成 256 bin,用 SparSamp 的 arithmetic-coding 状态机逐像素选 bin,bin 索引 == PNG 的 uint8 像素值。

设计文档: [`docs/superpowers/specs/2026-05-03-sparsample-integration-design.md`](../../docs/superpowers/specs/2026-05-03-sparsample-integration-design.md)

## 当前状态 (2026-05-03)

✅ **可跑通**。默认 10 步扩散下,CPU embed ~23s / extract ~9s,roundtrip 100% 一致。

## 前提

- Python 环境: `mamba activate sage`(项目已有,`torch 2.4 / scipy 1.15 / PIL 10 / numpy 1.26`)
- 模型: `~/bishe/models/ffhq_p2.pt`(P2-weighting FFHQ DDPM 预训练权重)
- 默认路径自动解析为项目根 `models/ffhq_p2.pt`,**不管从哪里 cd 都能跑**

## 使用

```bash
# 嵌入 —— 不论你在哪个目录,--model 默认都会找到 bishe/models/ffhq_p2.pt
python embed.py --msg "woshizya" --key "123456" --out t.png

# 提取 —— key 要和嵌入时完全一致
python extract.py --img t.png --key "123456"

# 默认扩散步数 10(demo 快速);想图像质量更高 → --steps 250(慢 ~4 倍)
python embed.py --msg "..." --key "..." --out ... --steps 250
python extract.py --img ... --key ... --steps 250   # 必须和 embed 一致!

# 自测(跑一遍固定用例 Hello, SparSamp!)
python test_roundtrip.py
```

⚠️ **extract 的 `--steps` / `--block-size` / `--model` 必须和 embed 一致**,否则 μ/σ 对不上,解码垃圾。

## 文件结构

```
test/sparsample/
├── guided_diffusion/           # 从 P2-weighting 仓库下载的 UNet 结构代码
│   ├── unet.py                 #   (注意:这不是权重,是模型架构)
│   ├── nn.py
│   ├── fp16_util.py
│   ├── logger.py               # stub,只在训练路径用到
│   └── __init__.py
├── core.py                     # 全部业务逻辑: seed 派生 / 量化 / encode_step /
│                               #   扩散前向 / sparsample_embed / sparsample_extract
├── embed.py                    # CLI
├── extract.py                  # CLI
├── test_roundtrip.py           # 端到端自测
└── README.md                   # 本文件
```

## 调试 / 排查

- **`FileNotFoundError: model not found`** — 检查 `~/bishe/models/ffhq_p2.pt` 是否存在,CLI 默认按项目根相对路径 `models/ffhq_p2.pt` 找。可以用 `--model /绝对/路径/ffhq_p2.pt` 覆盖。
- **extract 结果乱码 / 抛 `DecodeError: length header out of range`** — key 错了,或者 `--steps / --block-size / --model` 和 embed 时不一致。
- **CUDA 可用但想在 CPU 跑** — 加 `--device cpu`。GPU (`cuda`) 可能更快但要注意 cuDNN 非确定性:第一次 embed 跟第二次可能不是 bit-exact 一致 —— 两端必须同一台机器、同一 torch 版本才保证。
- **生成图像看起来像噪点而不是人脸** — 10 步扩散质量差是正常的;加 `--steps 250` 看质量。

## 当前已知限制 (Phase 1)

- 只做一个模型 (FFHQ P2),不支持 celebahq / bedroom 等(那些没用 learn_sigma)。
- 容量上限 ~24KB payload(经 32-bit 长度头编码后的 196608 像素),具体可嵌字节数取决于 key 下分布的熵 —— 小消息肯定够用。
- 错 key 会抛 DecodeError,不会"安静地"给出乱码 —— 是正常行为。
- 不抗 JPEG 压缩 / 缩放 / 噪声;保证 PNG lossless 管线。
- 不接 FastAPI / Web / Android —— 那是 Phase 2 要做的。

## 下一步 (Phase 2,尚未开始)

把 `sparsample_embed` / `sparsample_extract` 搬到 `server/services/sparsample_service.py`,API 层增加 `algorithm=pulsar|sparsample` 参数,前端增加算法选择器。需另写一份 Phase 2 的 spec。
