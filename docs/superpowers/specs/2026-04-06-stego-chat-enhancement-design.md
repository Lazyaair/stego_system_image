# Stego Chat Enhancement Design

## Overview

Complete the steganographic image messaging feature in the chat system. Currently, stego image messages have broken display on Web, no support on Android, a hardcoded key, and no in-chat extraction capability. This design adds proper send/display/extract flows on both platforms.

## Key Mechanism

- **Key formula**: `sender_invite_code + receiver_invite_code` (direct concatenation, no separator)
- Both parties derive the same key for any given message because the key is always `sender_code + receiver_code`
- Invite codes are preloaded when entering a chat window

### Invite Code Loading

- Own code: `GET /api/v1/invite/my-code`
- Peer's code: `GET /api/v1/invite/user-code/{user_id}` (new endpoint, distinct path prefix to avoid route conflict with existing `GET /{code}`)
- Both cached in chat state for the duration of the conversation
- Stego mode toggle is **disabled** until both invite codes have been successfully loaded

## Capacity

- The Pulsar service exposes `get_capacity(model_id, key)` which returns `max_message_len` in **bytes**
- A new server endpoint `GET /api/v1/stego/max-capacity?key=<key>&model=<model>` returns `{ "max_capacity": <int> }` without requiring a message parameter. This is used on stego mode switch to fetch the max capacity for the current key.
- **Capacity unit is bytes**. The UI displays: `秘密消息: 15/48 字节` (字节 = bytes). The client computes `message.encode('utf-8').length` (or equivalent) for the current input and compares against `max_capacity`.
- Exceeding capacity turns the indicator red and disables the send button.

## Send Flow (Web & Android)

1. User toggles stego mode in chat input (only available after invite codes are loaded)
2. Input placeholder changes to "输入秘密消息..."
3. Capacity indicator appears: `秘密消息: 15/48 字节`
   - Max capacity fetched via `GET /api/v1/stego/max-capacity` on mode switch
   - Client computes UTF-8 byte length of input text and compares locally
4. User taps send → client calls `POST /api/v1/stego/embed` with `message=<secret>`, `key=<sender_code+receiver_code>`, `model=celebahq`
5. Client receives response with `stego_image` field containing `data:image/png;base64,<base64>`. **Client strips the `data:image/png;base64,` prefix** before storing/sending, keeping only the pure base64 string.
6. Client sends via WebSocket:
   ```json
   {
     "type": "chat",
     "payload": {
       "to_user_id": "<id>",
       "content": "",
       "content_type": "stego",
       "stego_image": "<pure_base64_no_prefix>"
     }
   }
   ```
7. `content` field is empty — secret message is embedded in the image, not transmitted as plaintext

## Display

- Stego messages render the image (decoded from base64)
- No text content displayed (content is empty)
- Base64 storage convention: **store pure base64 without `data:image/png;base64,` prefix**; add prefix only at render time

## Extract Flow (Both Platforms)

1. **Trigger**: Long-press (Android) or right-click (Web) on the stego image
2. **Context menu items**:
   - "提取秘密消息" — calls `POST /api/v1/stego/extract` with the image, key, and `model=celebahq`
   - "保存图像" — saves/downloads the image as PNG
3. **Key for extraction**: `sender_invite_code + receiver_invite_code` (sender = whoever sent the message)
4. **Image conversion for API call**:
   - Web: convert base64 string to a `Blob`, then wrap as `File` object for the multipart upload
   - Android: convert base64 string to a byte array, write to a temp file, then construct `MultipartBody.Part`
5. **Model parameter**: always `"celebahq"` (hardcoded, matching the embed call)
6. **Result display**: Extracted text appears below the image in a styled area with light background, similar to chat translation results
7. **Both sender and receiver** can extract from any stego message in the conversation

## Server Changes

### New Endpoint: Get User Invite Code

`GET /api/v1/invite/user-code/{user_id}`

- Path uses `user-code/` prefix to avoid conflict with existing `GET /api/v1/invite/{code}` route
- Requires JWT authentication
- Only allows querying invite codes of users who are contacts of the requester (prevents arbitrary code lookup)
- Auto-generates a code if the target user doesn't have one
- Response: `{ "code": "ABC123", "user_id": "<id>", "username": "<name>" }`

### New Endpoint: Max Capacity

`GET /api/v1/stego/max-capacity`

- Query params: `key` (string), `model` (string, default `celebahq`)
- Calls `PulsarService.get_capacity(model, key_bytes)` directly (no message needed)
- Response: `{ "max_capacity": 48 }` (in bytes)

### No Other Server Changes

- WebSocket message forwarding: unchanged (already passes through stego fields)
- Embed/extract APIs: unchanged
- Offline message queue: unchanged (large base64 payloads in queue acknowledged as acceptable for current scale)

## Web Changes

### MessageInput.vue

- Stego mode toggle (existing) — disabled until invite codes are loaded
- Add capacity indicator below input when in stego mode: `秘密消息: X/Y 字节`
- Byte length computed client-side via `new TextEncoder().encode(text).length`
- Disable send button when over capacity or empty input

### ChatView.vue

- Replace hardcoded `'default-key'` with `senderCode + receiverCode`
- `sendStegoMessage` called with only `stegoImage` param; `content` is hardcoded to `""` inside the store method (not passed by caller, preventing accidental secret leakage)
- Strip `data:image/png;base64,` prefix from embed API response before passing to store
- Show loading state during embed API call

### MessageBubble.vue

- Fix double base64 prefix bug: always add prefix at render time (`'data:image/png;base64,' + stegoImage`)
- Render stego image when `content_type === 'stego'` and `stego_image` exists
- Do not display `content` text for stego messages (content is empty)
- Add `@contextmenu.prevent` handler for right-click menu:
  - "提取秘密消息": convert base64 to Blob/File → call extract API → display result below image
  - "保存图像": trigger browser download of the PNG
- Extracted text area: light background div below image, shown after extraction

### chat.ts Store

- Add `myInviteCode` and `peerInviteCode` state
- Load both codes when entering a chat (called from ChatView mount)
- `sendStegoMessage(stegoImage: string)`: hardcodes `content: ""` internally, no content parameter
- Add `getStegoKey(isOutgoing: boolean)` helper:
  - Outgoing: `myInviteCode + peerInviteCode`
  - Incoming: `peerInviteCode + myInviteCode`
  - Both produce the same key = `senderCode + receiverCode`

### stego.ts API

- Add `getMaxCapacity(key: string, model: string)` method calling new endpoint

## Android Changes

### ChatViewModel

- Add `myInviteCode` / `peerInviteCode` state, load on chat init
- Add `stegoMode` state toggle, disabled until codes loaded
- Add `maxCapacity` state + `fetchMaxCapacity()` method (calls new max-capacity endpoint)
- Add `sendStegoMessage(secretMessage: String)`:
  - Construct key → call embed API → strip base64 prefix → send via WebSocket with `content=""`, `content_type="stego"`, `stego_image=<pure_base64>`
- Add `extractMessage(stegoImageBase64: String, senderUserId: String)`:
  - Construct key → convert base64 to temp file → call extract API with `model=celebahq` → return secret text

### ChatScreen

- Add stego mode toggle button (lock icon) next to input field, disabled until codes loaded
- In stego mode:
  - Input hint: "输入秘密消息..."
  - Capacity indicator above input: `秘密消息: 15/48 字节`, red when exceeded
  - Byte length computed via `text.toByteArray(Charsets.UTF_8).size`
  - Send button disabled when over capacity or empty
  - Loading indicator during embed

### MessageBubble (in ChatScreen)

- When `contentType == "stego"` and `stegoImage != null`:
  - Decode base64 → display as `Image` composable (via `BitmapFactory.decodeByteArray`)
  - Do not display content text
- Long-press on image → `DropdownMenu`:
  - "提取秘密消息": call extractMessage → show result below image
  - "保存图像": save to gallery via `MediaStore` API
- Extracted text: `Text` composable below image with background color

### StegoApi.kt

- Add `getMaxCapacity(key: String, model: String)` method calling new endpoint

## Bug Fixes

1. **Web double base64 prefix**: Normalize storage to pure base64 (strip `data:image/png;base64,` prefix from server response before storing). Add prefix only at render time.
2. **Secret leakage in content field**: `sendStegoMessage` hardcodes `content=""` internally on both platforms. Callers never pass the secret text as content.
3. **Android extract from base64**: Convert in-memory base64 string to a temporary file to construct the `MultipartBody.Part` required by the extract API.
4. **Web extract from base64**: Convert in-memory base64 string to `Blob` then `File` for the multipart upload to extract API.

## Out of Scope

- UI/visual polish (deferred to Phase 5)
- Message encryption beyond steganography
- Chunked upload for large images
- Server-side stego processing
- Offline queue size optimization for stego payloads (acceptable at current scale)
