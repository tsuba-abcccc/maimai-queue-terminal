param(
    [string]$ChromePath = "C:\Program Files\Google\Chrome\Application\chrome.exe",
    [string]$OutputPath = "",
    [int]$FrameRate = 30,
    [switch]$SkipFrameRender
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$htmlPath = Join-Path $PSScriptRoot "player-guide-motion.html"
$rendererPath = Join-Path $PSScriptRoot "render-motion-frames.mjs"
$framesPath = Join-Path $root ".codex-tmp\promo-video-motion-frames"
$previewPath = Join-Path $PSScriptRoot "player-guide-contact-sheet.png"
$metadataPath = Join-Path $framesPath "render.json"
$buildFile = Join-Path $root "app\build.gradle.kts"
$versionMatch = [regex]::Match((Get-Content -LiteralPath $buildFile -Raw -Encoding UTF8), 'appVersionName\s*=\s*"([^"]+)"')
$versionName = if ($versionMatch.Success) { $versionMatch.Groups[1].Value } else { "current" }

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $root "output\promo\maimai-Q-player-guide-$versionName.mp4"
}

if (-not (Test-Path -LiteralPath $ChromePath)) {
    throw "Chrome not found: $ChromePath"
}

New-Item -ItemType Directory -Force -Path $framesPath | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path $OutputPath) | Out-Null

if (-not $SkipFrameRender) {
    & node --experimental-websocket $rendererPath `
        --chrome $ChromePath `
        --html $htmlPath `
        --frames $framesPath `
        --fps $FrameRate `
        --width 1080 `
        --height 1920
    if ($LASTEXITCODE -ne 0) {
        throw "Motion frame rendering failed with exit code $LASTEXITCODE"
    }
} elseif (-not (Test-Path -LiteralPath $metadataPath)) {
    throw "Existing frame metadata not found: $metadataPath"
}

$metadata = Get-Content -LiteralPath $metadataPath -Raw -Encoding UTF8 | ConvertFrom-Json
$ffmpeg = (Get-Command ffmpeg -ErrorAction Stop).Source
$inputPattern = Join-Path $framesPath "frame-%06d.jpg"
& $ffmpeg `
    -hide_banner `
    -loglevel warning `
    -framerate $metadata.fps `
    -start_number 0 `
    -i $inputPattern `
    -frames:v $metadata.frameCount `
    -vf "fps=30,format=yuv420p" `
    -an `
    -c:v libx264 `
    -preset medium `
    -crf 19 `
    -pix_fmt yuv420p `
    -movflags +faststart `
    -y `
    $OutputPath
if ($LASTEXITCODE -ne 0) {
    throw "ffmpeg failed with exit code $LASTEXITCODE"
}

Add-Type -AssemblyName System.Drawing
$sheet = [System.Drawing.Bitmap]::new(1080, 1920)
$graphics = [System.Drawing.Graphics]::FromImage($sheet)
$graphics.Clear([System.Drawing.Color]::FromArgb(235, 235, 238))
$graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
$graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality

for ($index = 0; $index -lt 16; $index++) {
    $frameIndex = [Math]::Min(
        [int]$metadata.frameCount - 1,
        [Math]::Round(($index + 0.5) * [double]$metadata.frameCount / 16)
    )
    $sourcePath = Join-Path $framesPath ("frame-{0:D6}.jpg" -f $frameIndex)
    $source = [System.Drawing.Image]::FromFile($sourcePath)
    $column = $index % 4
    $row = [Math]::Floor($index / 4)
    $x = 20 + ($column * 260)
    $y = 20 + ($row * 474)
    $graphics.DrawImage(
        $source,
        [System.Drawing.Rectangle]::new($x, $y, 250, 444),
        0,
        0,
        $source.Width,
        $source.Height,
        [System.Drawing.GraphicsUnit]::Pixel
    )
    $source.Dispose()
}

$graphics.Dispose()
$sheet.Save($previewPath, [System.Drawing.Imaging.ImageFormat]::Png)
$sheet.Dispose()

Write-Output "Video: $OutputPath"
Write-Output "Contact sheet: $previewPath"
Write-Output "Duration: $($metadata.duration) seconds"
Write-Output "Rendered frames: $($metadata.frameCount) at $($metadata.fps) fps"
