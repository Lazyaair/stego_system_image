# 可证安全图像隐写系统 - 设计文档

## 概述

本系统为跨平台图像隐写应用，支持 Android 和 Web 两种前端，通过 Python 后端提供隐写服务。当前阶段采用硬编码演示模式，后续迁移真实算法。

## 技术栈

| 组件 | 技术 |
|------|------|
| Server | Python + FastAPI |
| Android | Kotlin |
| Web | Vue.js 3 + TypeScript |

## 项目结构

```
bishe/
├── server/
│   ├── main.py
│   ├── api/v1/stego.py
│   ├── demo_assets/
│   │   └── stego_demo.png
│   └── requirements.txt
├── android/
│   └── (Android Studio 项目)
├── web/
│   └── (Vite + Vue3 项目)
└── README.md
```

## 实现阶段

1. **Python Server** - 硬编码 API，返回占位图像
2. **Android App** - 功能入口完整（嵌入/提取页面）
3. **Vue.js Web** - 功能入口完整
4. **算法迁移** - 集成 Jupyter 真实算法
5. **界面美化** - 优化交互体验

## API 设计

### POST /api/v1/stego/embed

消息嵌入接口（演示阶段返回占位图）

**请求：**
- `cover_image`: 载体图像文件
- `secret_message`: 秘密消息
- `key`: 加密密钥
- `embed_rate`: 嵌入率 (0.1-1.0)

**响应：**
```json
{
  "status": "success",
  "stego_image": "data:image/png;base64,...",
  "is_demo": true
}
```

### POST /api/v1/stego/extract

消息提取接口（演示阶段返回固定消息）

**请求：**
- `stego_image`: 含密图像文件
- `key`: 解密密钥

**响应：**
```json
{
  "status": "success",
  "secret_message": "演示提取消息",
  "is_demo": true
}
```

## 演示资源

使用占位图像作为演示含密图像，存放于 `server/demo_assets/stego_demo.png`，后续替换为真实算法生成的图像。

## 约束

- 演示阶段所有 API 返回 `is_demo: true` 标识
- 算法模块 `algorithms/` 预留，暂不实现
- 前端需明确展示"演示模式"提示
