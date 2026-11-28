# Chạy FocusChange AI offline bằng PowerShell (có sẵn trên Windows, không cần cài Python).
# Máy chủ tĩnh chỉ nghe trên máy này (localhost). Dùng:  powershell -ExecutionPolicy Bypass -File serve.ps1
param([int]$Port = 8765)
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$mime = @{ '.html'='text/html; charset=utf-8'; '.js'='text/javascript; charset=utf-8'; '.mjs'='text/javascript; charset=utf-8'; '.css'='text/css; charset=utf-8';
  '.json'='application/json; charset=utf-8'; '.wasm'='application/wasm'; '.onnx'='application/octet-stream'; '.task'='application/octet-stream';
  '.tflite'='application/octet-stream'; '.png'='image/png'; '.svg'='image/svg+xml'; '.ico'='image/x-icon'; '.webmanifest'='application/manifest+json'; '.txt'='text/plain; charset=utf-8' }
$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add("http://localhost:$Port/")
try { $listener.Start() } catch { Write-Host "Khong mo duoc cong $Port (co the dang duoc dung). Thu: serve.ps1 -Port 8766"; Read-Host "Nhan Enter de thoat"; exit 1 }
$url = "http://localhost:$Port/dashboard/"
Write-Host "FocusChange AI dang chay offline tai $url"
Write-Host "Giu cua so nay mo khi dung. Dong cua so de tat."
Start-Process $url
while ($listener.IsListening) {
  $ctx = $listener.GetContext()
  try {
    $req = $ctx.Request; $res = $ctx.Response
    $rel = [System.Uri]::UnescapeDataString($req.Url.LocalPath).TrimStart('/')
    if ($rel -eq '' ) { $rel = 'index.html' }
    $path = Join-Path $root $rel
    if (Test-Path $path -PathType Container) { $path = Join-Path $path 'index.html' }
    $full = [System.IO.Path]::GetFullPath($path)
    $status = 200
    if (-not $full.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase) -or -not (Test-Path $full -PathType Leaf)) {
      $status = 404; $full = Join-Path $root '404.html'
    }
    $ext = [System.IO.Path]::GetExtension($full).ToLower()
    $res.StatusCode = $status
    $res.ContentType = $(if ($mime.ContainsKey($ext)) { $mime[$ext] } else { 'application/octet-stream' })
    $heavy = @('.wasm','.onnx','.task','.tflite') -contains $ext
    $res.Headers.Add('Cache-Control', $(if ($heavy) { 'public, max-age=604800' } else { 'no-cache' }))
    if (Test-Path $full -PathType Leaf) {
      $fs = [System.IO.File]::OpenRead($full)
      $res.ContentLength64 = $fs.Length
      if ($req.HttpMethod -ne 'HEAD') { $fs.CopyTo($res.OutputStream) }
      $fs.Close()
    }
  } catch { } finally { try { $ctx.Response.OutputStream.Close() } catch { } }
}
