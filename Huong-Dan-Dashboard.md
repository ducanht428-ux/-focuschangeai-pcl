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
   Từ đợt này file model đã nằm sẵn ở gốc repo (`face_landmarker.task`), config trỏ thẳng vào đó.
4. (Tuỳ chọn, để nghe khẩu lệnh giáo viên bằng micro) cài thêm:
   `python -m pip install SpeechRecognition PyAudio`
   Thiếu hai gói này, thẻ **Khẩu lệnh giáo viên** vẫn cho chọn ngữ cảnh bằng tay, chỉ không nghe được.

## 2. Chạy

- Cách nhanh nhất: nhấp đúp **`Chay-Dashboard.bat`**. Trình duyệt tự mở.
- Hoặc thủ công: `python dashboard/server.py` (thêm `--no-open` nếu không muốn tự mở trình duyệt).
- Dừng: `Ctrl+C` ở cửa sổ dòng lệnh.

## 3. Tám màn hình

| Màn | Chức năng chính |
|---|---|
| Tổng quan | 5 thẻ số — thêm thẻ **"Đang mất tập trung"**: đếm số em lệch ngữ cảnh (OFF_TASK) ngay lúc đó, **bấm vào thẻ** mở danh sách cụ thể từng em (tên, ngữ cảnh, lý do, giây lệch/cửa sổ, cộng dồn cả phiên, SEI); danh sách tự cập nhật khi còn mở, kèm nhóm "đang theo dõi" chưa vượt cửa sổ. Camera chưa bật thì thẻ hiện "--" chứ không hiện 0 giả. Camera trực tiếp + FPS, chỉ số trung bình lớp (EAR/MAR/góc/chú ý), biểu đồ 30 phút, hoạt động gần đây, trạng thái hệ thống |
| Camera | Bắt đầu/Dừng, chụp ảnh màn hình, toàn màn hình; độ phân giải, số mặt, tần suất quét, độ nhạy; bật/tắt hộp, số liệu đè, tăng sáng; thẻ từng em (Gắn tên/Đổi tên/Bỏ tên); **khẩu lệnh giáo viên** (xem mục 5.1); phím F Y O G H N |
| Học sinh | Thêm/sửa/xoá, nhập/xuất CSV, công tắc đồng ý (rút đồng ý = xoá vector mặt ngay), xoá dây chuyền khi chuyển trường |
| Phân tích AI | Biểu đồ theo buổi / theo tiết của từng em, so với baseline cá nhân; cột **Lệch ngữ cảnh** trong bảng phiên; thẻ **Theo dõi dấu hiệu Tic** đọc dọc nhiều tuần (mục 5.3); dự báo & gợi ý nghỉ là tính năng Ultra (khoá) |
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

### 5.1 Khẩu lệnh giáo viên (ngoại lệ duy nhất về audio)

Màn **Camera** có thẻ *Khẩu lệnh giáo viên*: chọn **Nghe giảng / Ghi bài / Thảo luận nhóm /
Làm bài tập / Hỏi – đáp** bằng tay (5 ngữ cảnh theo tài liệu thiết kế mới),
hoặc bật công tắc *Nghe khẩu lệnh bằng micro* để hệ thống tự nhận câu lệnh như "bắt đầu thảo luận",
"cả lớp chú ý", "ghi bài vào vở", "các em tự làm bài nhé", "ai trả lời câu hỏi này".
Ngữ cảnh này đổi nhãn hoạt động của lớp (cột phải màn Tổng quan / camera) và là đầu vào của
**đánh giá tập trung từng em** (mục 5.2), và **hết 3 phút không có lệnh mới thì tự trả về
"chưa có khẩu lệnh"** — im lặng không bị suy diễn thành "đang nghe giảng".

- **Micro mặc định TẮT.** Bấm bật sẽ hỏi xác nhận vì nhận dạng dùng Google Speech:
  **đoạn ghi âm được gửi ra internet**. Đây là ngoại lệ duy nhất của dự án; phần đo lường
  (ảnh, số đo, SQLite) vẫn offline hoàn toàn. Không bật nếu giáo viên không đồng ý.
- Chọn bằng tay thì không thu âm gì cả, và nhãn ghi rõ nguồn: "(GV chọn)" khác "(khẩu lệnh GV)".
- Không có khẩu lệnh, hoặc khung hình không có mặt nào đo được / không có ai trong khung,
  thì nhãn hoạt động vẫn là của luật viết tay đọc từ hình ảnh — không bịa hoạt động.
- Bản web công khai ở chế độ DEMO chỉ **mô phỏng** khẩu lệnh trong trình duyệt, không thu âm thật.

### 5.2 Đánh giá tập trung TỪNG EM theo ngữ cảnh (khuyến nghị sư phạm)

Nguồn: *Huong_dan_AI_3_ngu_canh_va_hoat_dong_nhom_MediaPipe.docx* và
*Thiet_ke_AI_nhan_dien_tap_trung_theo_ngu_canh_GV.docx*. Khi CÓ khẩu lệnh, mỗi em được đánh giá
theo tiêu chí riêng của ngữ cảnh đó (`focusedge/context_eval.py`), hiện ngay trên thẻ người ở màn
Camera và trong feed "Hoạt động gần đây":

| Ngữ cảnh | Tiêu chí tập trung (góc LỆCH so với trung tính đã hiệu chuẩn của em) | Cửa sổ trước khi báo |
|---|---|---|
| Nghe giảng | \|yaw\| ≤ 25°, pitch −15°…+20° (nhìn bảng) | 7 s |
| Ghi bài | \|yaw\| ≤ 30°, pitch −42°…−10° (cúi nhìn vở) | 15 s |
| Làm bài tập | \|yaw\| ≤ 30°, pitch −42°…+15° (được ngước lên suy nghĩ) | 20 s |
| Thảo luận nhóm | Quay về bạn (20° ≤ \|yaw\| ≤ 75°) hoặc cùng cúi nhìn phiếu chung (−40°…−12°) **và** có trao đổi (mấp máy môi trong 15 s gần nhất, hoặc gật đầu) **và** mắt mở | 10 s |
| Hỏi – đáp | Hướng về GV / bạn trả lời (như nghe giảng) | 7 s |

- **Gục mặt (pitch < −45°) bị bắt trước tiên ở MỌI ngữ cảnh** — cúi chép bài khác gục xuống bàn.
- Ba trạng thái: *Tập trung* → *Theo dõi* (lệch nhưng chưa quá cửa sổ, không báo động giả) →
  *Mất tập trung*. Mỗi lần vượt cửa sổ là MỘT sự kiện (episode), không phải chuỗi cảnh báo mỗi khung.
- **Cộng dồn cả phiên**: tổng thời gian lệch ≥ 3 phút = mức trung bình, ≥ 10 phút = mức nặng
  (mất kết nối với mạch bài) — các nhịp lệch 5–10 giây rời rạc vẫn góp vào tổng.
- Tiêu chí "xích lại gần bạn" của giờ nhóm là **tín hiệu mềm** (khoảng cách pixel chỉ tin được
  tương đối trên một camera); mắt không đọc được ở góc nghiêng thì KHÔNG bị kết luận là nhắm mắt.
- **Không có khẩu lệnh hoặc mặt không đo được → không phán gì** (trung lập, đồng hồ dừng).
- Đây là lớp KHUYẾN NGHỊ chạy song song: SEI, 15 mức cảnh báo và ngưỡng của điện thoại giữ nguyên;
  sự kiện ngữ cảnh đi vào `notes` JSON của phiên và feed khuyến nghị, không đếm vào cảnh báo parity.
- Giới hạn có chủ đích: **không phát hiện giơ tay** (cần PoseLandmarker, máy dự án không chạy pose);
  hết 3 phút im lặng trả về UNKNOWN thay vì LECTURE như tài liệu gốc (im lặng không phải bài giảng).
  Micro nên dùng loại cài áo (lavalier) để nhận lệnh rõ trong lớp ồn; camera hiệu quả nhất ở khoảng
  0,5–2,5 m.

### 5.3 Theo dõi dấu hiệu Tic — KHÔNG phải chẩn đoán

Nguồn: *Tai_lieu_dau_hieu_va_thong_so_benh_Tic.docx* (DSM-5, YGTSS, BV Nhi Trung ương).
`focusedge/tic_watch.py` đếm MẪU HÌNH cử động lặp lại của từng em, không bao giờ nêu tên bệnh:

- **Mắt**: chùm ≥ 3 nháy nhanh (< 1 s/nháy) cách nhau ≤ 1,5 s; ≥ 4 chùm lặp đều mỗi 4–20 s trong
  10 phút → mức 1 "nên theo dõi". Dày đặc (≥ 6 chùm, mỗi ≤ 5 s, kéo dài ≥ 5 phút) → mức 2
  "nên trao đổi với phụ huynh và cân nhắc khám chuyên khoa". Nhắm mắt dài ≥ 1 s (ngủ gật) bị loại.
- **Miệng**: giật mở–khép < 1 s, ≥ 5 nhịp đều đặn — **tín hiệu YẾU** vì một camera không phân biệt
  được với nói chuyện thầm; chỉ đếm ngoài giờ thảo luận nhóm và đã loại ngáp. Giao diện ghi rõ điều này.
- **Mốc 4 tuần (DSM-5) chỉ đọc từ LỊCH SỬ**: thẻ *Theo dõi dấu hiệu Tic* ở màn Phân tích AI đếm
  số ngày có mẫu hình trên TỔNG số ngày quan sát (ngày không mẫu hình vẫn được ghi làm mẫu số).
  Kéo dài ≥ 28 ngày + ≥ 10 ngày có mẫu hình + tỉ lệ ≥ 50% → khuyến nghị đi khám. Dưới mốc đó chỉ
  khuyên tiếp tục theo dõi.
- Mọi thẻ/huy hiệu đều kèm câu **"Đây KHÔNG phải chẩn đoán y khoa…"** và 4 tài liệu tham khảo
  (PMC3038221, PMC6690237, PMC10687404, BV Nhi Trung ương) để người xem tự đối chiếu.
- Số liệu Tic đi vào `notes` JSON của phiên (`tic_watch`) — không đụng chuỗi cảnh báo parity,
  không dùng làm căn cứ kỷ luật.

## 6. Kiểm thử tự động

- `python tests/test_dashboard.py` — 47 kiểm thử tầng giao diện/dữ liệu (migration, cổng bậc,
  demo cách ly, báo cáo/CSV, sao lưu–khôi phục, retention, đã xem, xoá dây chuyền).
- `python tests/test_local_store.py` — kiểm thử tầng lưu trữ lõi.
- `python tests/test_teacher_voice.py` — 16 kiểm thử khẩu lệnh: từ điển tiếng Việt 5 ngữ cảnh
  (kể cả SEATWORK / Q_AND_A và luật khớp dài-nhất-trước), hết hạn trả về UNKNOWN, phân biệt
  nghe được / bấm chọn, cảnh báo riêng tư.
- `python -m unittest tests.test_context_eval` — 21 kiểm thử đánh giá từng em: cửa sổ riêng từng
  ngữ cảnh, cổng gục mặt, tiêu chí nhóm (nói / gật / mắt / khoảng cách mềm), trung lập khi không
  có lệnh / mất mặt, cộng dồn 3 phút / 10 phút, vòng đời episode.
- `python -m unittest tests.test_tic_watch` — 24 kiểm thử dấu hiệu Tic: nháy thường không thành
  mẫu hình, chùm / chuỗi / dày đặc, loại ngáp và giờ nhóm, chuỗi thất thường không tính, lịch sử
  4 tuần (mẫu số ngày quan sát), disclaimer luôn hiện.
- `python tests/test_activity.py` — 36 kiểm thử luật ngữ cảnh lớp, gồm 7 test hàng rào chống việc
  khẩu lệnh ghi đè nhãn khi chưa có lệnh thật / không có mặt / phòng trống.
Các test tầng dữ liệu dùng database tạm, không đụng dữ liệu thật.

## 7. Bản web công khai — bấm link là thấy giao diện

Link: **https://focuschange-dashboard-hkvmgndp8ax.qoder.website** (công khai, không cần đăng nhập).
Bấm link là thấy ngay giao diện 8 màn ở mọi máy, mọi trình duyệt.

- **DEMO**: máy mở link chưa chạy server cục bộ → giao diện hiện đầy đủ với **lớp học mô phỏng
  đang chạy ngay khi mở link**: camera demo vẽ 4 em trong khung, số liệu nhảy mỗi giây,
  cảnh báo nổ theo trạng thái (nhắm mắt / ngáp / quay đầu), biểu đồ cập nhật theo phút.
  Mọi nơi gắn nhãn DEMO/MÔ PHỎNG, không phải số đo thật. Bấm **Dừng** / **Bắt đầu** để
  tắt/bật mô phỏng; các màn Học sinh, Cảnh báo, Báo cáo, Sao lưu, Cài đặt đều thao tác được.
- **LIVE**: máy mở link đang chạy `Chay-Dashboard.bat` → bản web đọc thẳng số liệu máy đó
  (chip "Bản web · LIVE máy này"), camera đo thật bằng webcam. Chrome có thể hỏi một lần
  quyền "truy cập mạng cục bộ" — bấm **Cho phép/Allow**; hoặc bấm nút **Kết nối LIVE** trên
  băng DEMO để dò lại, nút **Mở bản cục bộ** mở thẳng app đầy đủ trên máy này.
- **Bấm Bắt đầu là thấy mặt mình**: nếu bản web lỡ mở ở DEMO trong khi máy này đang chạy
  dashboard, bấm **Bắt đầu** sẽ tự chuyển sang LIVE và bật camera thật ngay (một lần bấm).
  Nếu trình duyệt chặn luồng video MJPEG, giao diện tự đổi sang kéo từng ảnh qua
  `/api/camera/snapshot` — mặt vẫn hiện, chỉ chậm hơn một chút. Muốn chủ động xem mô phỏng
  thì mở link kèm `?mode=demo`.
- **Gắn tên thì mới lưu phiên**: máy này không chạy nhận diện khuôn mặt (ArcFace lỗi trên
  laptop), nên mọi khuôn mặt mới vào đều là `guest-N`: đo trực tiếp thì có số (SEI, góc đầu,
  khoảng cách) nhưng lõi KHÔNG ghi phiên của guest vào SQLite, và EAR/MAR/PERCLOS chỉ hiện
  khi phiên đã mở. Trên thẻ mỗi người ở màn Camera có nút **Gắn tên** (đổi tên / bỏ tên sau
  khi gắn): gõ tên vào là track thành học sinh `hs1…` với `identity_conf = manual` — đúng
  thẩm quyền phím M của cửa sổ lõi, tên ghi vào `roster.json`. Đo tiếp rồi bấm **Dừng**
  (hoặc rời khỏi khung hình) là phiên được chốt vào `data/focuschange.db`, xem lại ở các màn
  Tổng quan / Học sinh / Phân tích / Báo cáo. Guest chưa gắn tên luôn kèm dòng chữ
  "Chưa gắn tên nên không lưu phiên" để không ai tưởng nhầm là đã lưu.
- **Khẩu lệnh giáo viên**: thẻ này có cả ở DEMO (mô phỏng trong trình duyệt, không thu âm) và ở LIVE
  (micro thật của máy đang chạy dashboard, có hỏi xác nhận trước khi bật — xem mục 5.1).
- Link cũ `focuschangeai-pc-….qoder.website` là bản webcam cũ, KHÔNG phải giao diện này.
- Riêng tư: bản web không gửi dữ liệu lớp lên đám mây; LIVE chỉ đọc máy của chính người mở,
  DEMO chỉ là số mẫu. Muốn riêng tư lại: đổi chế độ truy cập trong bảng điều khiển site Qoder.
- Mã nguồn bản web: thư mục `site/` (sinh từ `dashboard/web/` bằng `python tools/build_site.py`;
  dựng số mẫu bằng `python tools/make_site_fixture.py` khi server cục bộ đang chạy và đã nạp demo).

## 8. Chưa làm ở đợt này (theo lộ trình đặc tả mục 12)

- Đăng nhập / vai trò / khoá tạm / 2 bước (giai đoạn 1–2, mục 9).
- Ghép nối điện thoại làm camera / màn xem qua wifi lớp, PIN/QR + TLS (giai đoạn 3).
- Nhiều lớp / nhiều giáo viên, báo cáo phụ huynh, nhận diện bối cảnh YOLO (Pro Max, giai đoạn 5).
- Dự báo AI, trợ lý hỏi đáp, cổng phụ huynh (Ultra, giai đoạn 6).
- Lõi camera chưa ghi bảng `metrics` từng khung hình (bảng đã dựng sẵn; hiện chuỗi phút chỉ có ở dữ liệu mẫu).
- Đánh giá ngữ cảnh chưa có phát hiện giơ tay (cần PoseLandmarker — chủ đích không chạy trên máy yếu);
  tín hiệu giật miệng là tín hiệu yếu, không phân biệt được nói chuyện thầm bằng một camera.