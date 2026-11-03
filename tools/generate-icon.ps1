param(
    [string]$OutputPath = (Join-Path $PSScriptRoot '../desktopApp/src/main/resources/hh-clicker.ico')
)

Add-Type -AssemblyName System.Drawing

$sizes = @(16, 20, 24, 32, 40, 48, 64, 128, 256)
$images = [System.Collections.Generic.List[byte[]]]::new()

foreach ($size in $sizes) {
    $bitmap = [System.Drawing.Bitmap]::new($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.Clear([System.Drawing.Color]::Transparent)

    $scale = $size / 24.0
    $handScale = 0.82
    $radius = 5.5 * $scale
    $background = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $diameter = $radius * 2
    $background.AddArc(1.5 * $scale, 1.5 * $scale, $diameter, $diameter, 180, 90)
    $background.AddArc($size - 1.5 * $scale - $diameter, 1.5 * $scale, $diameter, $diameter, 270, 90)
    $background.AddArc($size - 1.5 * $scale - $diameter, $size - 1.5 * $scale - $diameter, $diameter, $diameter, 0, 90)
    $background.AddArc(1.5 * $scale, $size - 1.5 * $scale - $diameter, $diameter, $diameter, 90, 90)
    $background.CloseFigure()
    $brush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(255, 255, 91, 10))
    $graphics.FillPath($brush, $background)

    $pen = [System.Drawing.Pen]::new([System.Drawing.Color]::White, [Math]::Max(1.1, 1.35 * $scale))
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round

    function P([double]$x, [double]$y) {
        $centeredX = 12 + (($x - 12) * $handScale)
        $centeredY = 12 + (($y - 12) * $handScale)
        [System.Drawing.PointF]::new([float]($centeredX * $scale), [float]($centeredY * $scale))
    }

    $finger = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $finger.AddLine((P 8 13), (P 8 4.5))
    $finger.AddBezier((P 8 4.5), (P 8 2.5), (P 11 2.5), (P 11 4.5))
    $finger.AddLine((P 11 4.5), (P 11 12))
    $graphics.DrawPath($pen, $finger)

    $middle = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $middle.AddLine((P 11 5.5), (P 11 3.5))
    $middle.AddBezier((P 11 3.5), (P 11 1.5), (P 14 1.5), (P 14 3.5))
    $middle.AddLine((P 14 3.5), (P 14 12))
    $graphics.DrawPath($pen, $middle)

    $ring = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $ring.AddBezier((P 14 10.5), (P 14 8.5), (P 17 8.5), (P 17 10.5))
    $ring.AddLine((P 17 10.5), (P 17 12))
    $graphics.DrawPath($pen, $ring)

    $palm = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $palm.StartFigure()
    $palm.AddBezier((P 17 11.5), (P 17 9.5), (P 20 9.5), (P 20 11.5))
    $palm.AddLine((P 20 11.5), (P 20 16))
    $palm.AddBezier((P 20 16), (P 20 19.4), (P 17.4 22), (P 14 22))
    $palm.AddLine((P 14 22), (P 12.2 22))
    $palm.AddBezier((P 12.2 22), (P 10.2 22), (P 8.3 21), (P 7.2 19.3))
    $palm.AddLine((P 7.2 19.3), (P 3.7 13.3))
    $palm.AddBezier((P 3.7 13.3), (P 3.2 12.5), (P 3.5 11.5), (P 4.3 11.1))
    $palm.AddBezier((P 4.3 11.1), (P 5.1 10.7), (P 6.1 10.9), (P 6.6 11.5))
    $palm.AddLine((P 6.6 11.5), (P 8 13))
    $graphics.DrawPath($pen, $palm)

    $stream = [System.IO.MemoryStream]::new()
    $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
    $images.Add($stream.ToArray())

    $stream.Dispose()
    $palm.Dispose()
    $ring.Dispose()
    $middle.Dispose()
    $finger.Dispose()
    $pen.Dispose()
    $brush.Dispose()
    $background.Dispose()
    $graphics.Dispose()
    $bitmap.Dispose()
}

$outputDirectory = Split-Path -Parent $OutputPath
[System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
$file = [System.IO.File]::Create($OutputPath)
$writer = [System.IO.BinaryWriter]::new($file)

$writer.Write([uint16]0)
$writer.Write([uint16]1)
$writer.Write([uint16]$sizes.Count)

$offset = 6 + 16 * $sizes.Count
for ($index = 0; $index -lt $sizes.Count; $index++) {
    $size = $sizes[$index]
    $writer.Write([byte]$(if ($size -eq 256) { 0 } else { $size }))
    $writer.Write([byte]$(if ($size -eq 256) { 0 } else { $size }))
    $writer.Write([byte]0)
    $writer.Write([byte]0)
    $writer.Write([uint16]1)
    $writer.Write([uint16]32)
    $writer.Write([uint32]$images[$index].Length)
    $writer.Write([uint32]$offset)
    $offset += $images[$index].Length
}

foreach ($image in $images) {
    $writer.Write($image)
}

$writer.Dispose()
$file.Dispose()

Write-Host "Generated $OutputPath"
