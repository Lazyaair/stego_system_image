"""Vendored minimal subset of P2-weighting's guided_diffusion for UNet loading.

Upstream: https://github.com/jychoi118/P2-weighting (Apache 2.0 / MIT compatible)
Only `unet.py`, `nn.py`, `fp16_util.py` are used for loading the FFHQ P2 checkpoint.
Sampling / training / dataset modules are not imported.
"""
