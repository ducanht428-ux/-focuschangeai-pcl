# Hướng dẫn nhanh — Dashboard FocusChangeAI (bản PC, offline)

Bản giao diện web cục bộ đủ 8 màn theo đặc tả mục 5. Mọi thứ chạy trên máy giáo viên,
không đám mây, không gửi ảnh/video ra ngoài. Mở bằng trình duyệt tại `http://127.0.0.1:8730`.

## 1. Cài đặt

1. Cài Python 3.12 (python.org), nhớ tick **Add python.exe to PATH**.
2. Mở Command Prompt trong thư mục dự án, cài thư viện tối thiểu cho giao diện:
   `python -m pip install pyyaml`
3. (Tuỳ chọn, để bật camera đo thật) cài thêm:
   `python -m pip install mediapipe ultralytics opencv-python pillow numpy`
   và chép file `face_landmarker.task` (từ app Android `app/src/main/assets/`) vào thư mục dự án.
   Thiếu những thứ này giao diện vẫn chạy; màn Camera sẽ liệt kê đúng thứ còn thiếu.

## 2. Chạy

- Cách nhanh nhất: nhấp đúp **`Chay-Dashboard.bat`**. Trình duyệt tự mở.
- Hoặc thủ công: `python dashboard/server.py` (thêm `--no-open` nếu không muốn tự mở trình duyệt).
- Dừng: `Ctrl+C` ở cửa sổ dòng lệnh.

## 3. Tám màn hình

| Màn | Chức năng chính |
|---|---|
| Tổng quan | 4 thẻ số, camera trực tiếp + FPS, chỉ số trung bình lớp (EAR/MAR/góc/chú ý), biểu đồ 30 phút, hoạt động gần đây, trạng thái hệ thống |
| Camera | Bắt đầu/Dừng, chụp ảnh màn hình, toàn màn hình; độ phân giải, số mặt, tần suất quét, độ nhạy; bật/tắt hộp, số liệu đè, tăng sáng; thẻ từng em; phím F Y O G H N |
| Học sinh | Thêm/sửa/xoá, nhập/xuất CSV, công tắc đồng ý (rút đồng ý = xoá vector mặt ngay), xoá dây chuyền khi chuyển trường |
| Phân tích AI | Biểu đồ theo buổi / theo tiết của từng em, so với baseline cá nhân; dự báo & gợi ý nghỉ là tính năng Ultra (khoá) |
| Cảnh báo | Mức 1–3, lọc theo em/loại/mức/ngày/chưa xem, đánh dấu đã xem, âm báo |
| Báo cáo | Phiên đo / ngày / tuần; In–lưu PDF; xuất CSV (bậc Pro); mẫu phụ huynh (Pro Max, khoá) |
| Sao lưu | Sao lưu/khôi phục bằng API backup SQLite, sao lưu tự động (Pro), thời gian lưu dữ liệu, nạp/xoá dữ liệu mẫu |
| Cài đặt | Bậc sản phẩm + bảng cổng tính năng, ngưỡng cảnh báo, giao diện sáng/tối, ngôn ngữ, nhật ký kiểm toán |

## 4. Dữ liệu mẫu (DEMO)

Máy chưa đo thật vẫn xem được đầy đủ hình dạng báo cáo/biểu đồ:
vào **Sao lưu → Nạp dữ liệu mẫu**. Bộ mẫu gồm 5 em "HS Mẫu", 6 buổi/em, cảnh báo và biểu đồ 30 phút,
**luôn gắn nhãn DEMO** (băng cam trên đầu màn hình). Nút **Xoá dữ liệu mẫu** chỉ xoá đúng dấu vết demo,
không đụng dữ liệu thật.

## 5. Riêng tư & an toàn

- Không lưu ảnh/video. Khung camera chỉ nằm trong bộ nhớ để phát trực tiếp, tắt là mất.
- Nút "Chụp ảnh màn hình" là chụp màn hình phía trình duyệt (có xin quyền), không trích khung mặt.
- Chỉ nhận diện/lưu vector khi có đồng ý; rút đồng ý xoá vector ngay.
- Server chỉ nghe `127.0.0.1` (máy này), chưa mở ra mạng lớp. Đăng nhập/phân quyền và ghép nối
  điện thoại là giai đoạn sau (đặc tả mục 9 và 6).
- Số liệu chỉ để hỗ trợ giáo viên, không dùng làm căn cứ kỷ luật duy nhất.

## 6. Kiểm thử tự động

- `python tests/test_dashboard.py` — 47 kiểm thử tầng giao diện/dữ liệu (migration, cổng bậc,
  demo cách ly, báo cáo/CSV, sao lưu–khôi phục, retention, đã xem, xoá dây chuyền).
- `python tests/test_local_store.py` — kiểm thử tầng lưu trữ lõi.
Cả hai dùng database tạm, không đụng dữ liệu thật.

## 7. Chưa làm ở đợt này (theo lộ trình đặc tả mục 12)

- Đăng nhập / vai trò / khoá tạm / 2 bước (giai đoạn 1–2, mục 9).
- Ghép nối điện thoại làm camera / màn xem qua wifi lớp, PIN/QR + TLS (giai đoạn 3).
- Nhiều lớp / nhiều giáo viên, báo cáo phụ huynh, nhận diện bối cảnh YOLO (Pro Max, giai đoạn 5).
- Dự báo AI, trợ lý hỏi đáp, cổng phụ huynh (Ultra, giai đoạn 6).
- Lõi camera chưa ghi bảng `metrics` từng khung hình (bảng đã dựng sẵn; hiện chuỗi phút chỉ có ở dữ liệu mẫu).