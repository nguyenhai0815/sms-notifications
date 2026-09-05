# SMS Notifications

App Android đọc tin nhắn đến trên máy rồi chuyển nguyên văn về server. Dùng chung
cho nhiều project — mỗi project là một **đích đến** trong app.

App **không phân tích gì cả**: không biết ngân hàng, không biết hoá đơn. Việc tách
số tiền, dò mã, khớp đơn là của server từng project.

## Cách nó chạy

1. Tin nhắn tới → `SmsReceiver` bắt ngay, ghép các mảnh của tin dài, lưu vào SQLite.
2. Lưu xong mới xếp vào hàng đợi của từng đích khớp bộ lọc người gửi.
3. `RelayWorker` gửi lên server. Gửi hỏng thì giữ lại, WorkManager giãn giờ thử lại
   cho tới khi server nhận. **Mất mạng không mất tin.**
4. Mỗi 15 phút chạy một nhịp nền: quét lại hộp tin để vớt tin mà receiver bỏ sót
   (máy tắt nguồn, app bị giết), gửi nốt hàng đợi, và báo còn sống.

Chống trùng bằng `message_id` = SHA-256 của `người gửi | nội dung`. Nhờ vậy bản bắt
lúc tin tới và bản quét lại từ hộp tin không thành hai tin.

## Cấu hình

Mỗi đích gồm: tên, URL nhận tin, token, và hai bộ lọc — ngăn bằng dấu phẩy, để
trống là bỏ qua lớp đó:

- **Người gửi** phải chứa một trong các tên đã khai (`MB Bank,Vietcombank`).
- **Nội dung** phải chứa một trong các từ khoá đã khai. Đặt đúng prefix mã phiếu
  của project (`MATHOLYM`) thì chỉ tin chuyển khoản cho project đó mới đi lên;
  OTP, quảng cáo, và tiền của việc khác ở lại máy. Muốn mọi tin biến động số dư
  đều lên để server tự phân loại thì đặt `GD:,SD:`.

Tin không qua được bộ lọc vẫn nằm trong máy, chỉ không gửi đi.

Thêm tay trong app, hoặc bấm **Nhập JSON** rồi dán:

```json
[
  {
    "name": "Math Olympian",
    "url": "https://api.example.com/internal/sms/incoming",
    "token": "chuoi-bi-mat",
    "senders": "MB Bank,Vietcombank",
    "keywords": "MATHOLYM",
    "enabled": true
  }
]
```

## Server nhận gì

`POST <url>` với header `Authorization: Bearer <token>`, thân là JSON.

Tin nhắn:

```json
{
  "type": "sms",
  "message_id": "a1b2c3...",
  "sender": "MB Bank",
  "body": "TK 99xxx368 GD: +2,000VND ...",
  "received_at": 1756000000000,
  "sim": "0",
  "source": "push",
  "device": "Xiaomi Redmi 9A"
}
```

Báo còn sống, 15 phút một lần:

```json
{
  "type": "heartbeat",
  "device": "Xiaomi Redmi 9A",
  "sent_at": 1756000000000,
  "pending": 0
}
```

Nút **Gửi thử** trong màn sửa đích bắn `{"type":"test", ...}`.

Quy ước trả lời:

- **2xx** — đã nhận, app xoá khỏi hàng đợi.
- **4xx** (trừ 408 và 429) — chê hẳn, app không thử lại nữa, đánh dấu lỗi.
- **5xx, timeout, mất mạng** — app giữ lại và thử lại sau.

Server phải tự chống trùng theo `message_id`, vì app có thể gửi lại cùng một tin.

## Build

Không cần cài gì ở máy. Đẩy code lên nhánh `main`, GitHub Actions build rồi đăng
APK lên release `latest`.

Cài vào điện thoại: mở trang Releases của repo bằng trình duyệt trên máy đó, tải
`sms-notifications.apk`, cho phép cài từ nguồn không xác định.

## Trên điện thoại

- Cấp quyền đọc tin nhắn khi app hỏi.
- Tắt tối ưu pin cho app, không thì Android giết nhịp nền.
- Máy nên cắm điện và giữ mạng liên tục.
- Đừng bấm "buộc dừng": app bị buộc dừng thì không nhận được tin nào nữa cho tới
  khi mở lại bằng tay.
