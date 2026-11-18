# Frame Blending Motion Blur (Forge 1.8.9, OptiFine-friendly)

## Build bằng GitHub Actions (không cần cài gì)
1. Tạo repo mới trên GitHub, upload TOÀN BỘ nội dung thư mục này (nhớ cả thư mục `.github`).
2. Vào tab **Actions**: workflow "Build mod jar" tự chạy (hoặc bấm "Run workflow").
3. Chạy xong, mở lần chạy đó, kéo xuống **Artifacts**, tải `FrameMotionBlur-1.8.9` (file zip chứa jar).
4. Giải nén, bỏ jar vào `.minecraft/mods` cùng OptiFine.

Muốn jar nằm ở mục Releases: tạo tag, ví dụ `git tag v1.0.0 && git push --tags`.

## Dùng trong game
- `/motionblur <0-100>`: độ mạnh (mặc định 50); `/motionblur on|off`
- Gán phím "Toggle Motion Blur" trong Options > Controls.
- Hợp nhất khi 120+ FPS.
