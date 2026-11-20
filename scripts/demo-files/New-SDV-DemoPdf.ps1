<#
.SYNOPSIS
  Writes a small text-only demo PDF (local demo data only; never part of a product build).

.DESCRIPTION
  Used to make loan-file-14-pages.pdf, the 14-page demo document on contract 300000000092 (task 0006 timing,
  task 0023 seed). Each page has a heading and a few lines of made-up text in Helvetica, so the file stays a few KB.
  The output is the same every time for the same arguments (no dates or random IDs inside).

.EXAMPLE
  .\scripts\demo-files\New-SDV-DemoPdf.ps1 -OutFile .\scripts\demo-files\loan-file-14-pages.pdf -Pages 14 -Title 'Loan file'
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$OutFile,
    [ValidateRange(1, 200)][int]$Pages = 14,
    [string]$Title = 'Loan file'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Esc([string]$s) { $s.Replace('\', '\\').Replace('(', '\(').Replace(')', '\)') }

$lines = @(
    'Demo document for the local Vero development stack.',
    'Made-up content: no real person, account or loan.',
    'Contract 300000000092 - Lena Patel - Mortgage.'
)
# Objects: 1 catalog, 2 pages, 3 font, then a page and its content stream for each page.
$objects = New-Object System.Collections.Generic.List[string]
$objects.Add('<< /Type /Catalog /Pages 2 0 R >>')
$kids = (1..$Pages | ForEach-Object { "$(2 + 2 * $_) 0 R" }) -join ' '
$objects.Add("<< /Type /Pages /Kids [$kids] /Count $Pages >>")
$objects.Add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
for ($p = 1; $p -le $Pages; $p++) {
    $text = New-Object System.Text.StringBuilder
    [void]$text.Append("BT /F1 22 Tf 72 740 Td ($(Esc "$Title - page $p of $Pages")) Tj ET`n")
    $y = 700
    foreach ($l in $lines) { [void]$text.Append("BT /F1 12 Tf 72 $y Td ($(Esc $l)) Tj ET`n"); $y -= 20 }
    $stream = $text.ToString()
    $objects.Add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents $(3 + 2 * $p) 0 R >>")
    $objects.Add("<< /Length $([Text.Encoding]::ASCII.GetByteCount($stream)) >>`nstream`n$stream" + "endstream")
}

$sb = New-Object System.Text.StringBuilder
[void]$sb.Append("%PDF-1.4`n")
$offsets = New-Object System.Collections.Generic.List[int]
for ($i = 0; $i -lt $objects.Count; $i++) {
    $offsets.Add([Text.Encoding]::ASCII.GetByteCount($sb.ToString()))
    [void]$sb.Append("$($i + 1) 0 obj`n$($objects[$i])`nendobj`n")
}
$xref = [Text.Encoding]::ASCII.GetByteCount($sb.ToString())
[void]$sb.Append("xref`n0 $($objects.Count + 1)`n0000000000 65535 f `n")
foreach ($o in $offsets) { [void]$sb.Append(('{0:D10} 00000 n ' -f $o) + "`n") }
[void]$sb.Append("trailer`n<< /Size $($objects.Count + 1) /Root 1 0 R >>`nstartxref`n$xref`n%%EOF`n")

$full = [IO.Path]::GetFullPath($OutFile)
[IO.File]::WriteAllBytes($full, [Text.Encoding]::ASCII.GetBytes($sb.ToString()))
Write-Host "Wrote $full ($Pages pages, $((Get-Item -LiteralPath $full).Length) bytes)"
