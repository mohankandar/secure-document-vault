[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$BaseUrl,
    [Parameter(Mandatory=$true)][string]$SourceSubmissionId,
    [Parameter(Mandatory=$true)][string[]]$PdfPaths,
    [Parameter(Mandatory=$true)][string[]]$SourceDocumentIds,
    [Parameter(Mandatory=$true)][string]$MetadataJsonPath,
    [string]$AccessToken,
    [string]$CorrelationId,
    [string]$OutputJson,
    [switch]$ReplayFirstDocument,

    # LOCAL ACCEPTANCE ONLY. Production producers should use -AccessToken.
    [string]$LocalDevTenantId,
    [string]$LocalDevSourceId,
    [string]$LocalDevSubject='third-party-producer'
)

$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest

if ($PdfPaths.Count -lt 1) { throw 'At least one PDF is required.' }
if ($PdfPaths.Count -ne $SourceDocumentIds.Count) { throw 'PdfPaths and SourceDocumentIds must contain the same number of entries.' }
if ([string]::IsNullOrWhiteSpace($CorrelationId)) { $CorrelationId='producer-' + ([guid]::NewGuid().ToString('N')) }

function New-ProducerHeaders {
    $headers=@{'X-Correlation-Id'=$CorrelationId}
    if (-not [string]::IsNullOrWhiteSpace($AccessToken)) {
        $headers['Authorization']="Bearer $AccessToken"
        return $headers
    }
    if ([string]::IsNullOrWhiteSpace($LocalDevTenantId) -or [string]::IsNullOrWhiteSpace($LocalDevSourceId)) {
        throw 'Provide AccessToken for production use, or both LocalDevTenantId and LocalDevSourceId for local acceptance.'
    }
    $headers['X-SDV-Tenant-Id']=$LocalDevTenantId
    $headers['X-SDV-Source-Id']=$LocalDevSourceId
    $headers['X-SDV-Subject']=$LocalDevSubject
    return $headers
}

function Invoke-ProducerJson([string]$Method,[string]$Uri,[hashtable]$Headers,[object]$Body) {
    try {
        $bodyText=if ($null -eq $Body) { $null } else { $Body | ConvertTo-Json -Depth 30 }
        $r=Invoke-WebRequest -Uri $Uri -Method $Method -Headers $Headers -ContentType 'application/json' -Body $bodyText -UseBasicParsing -TimeoutSec 30
        $parsed=if ([string]::IsNullOrWhiteSpace($r.Content)) { $null } else { $r.Content | ConvertFrom-Json }
        return [PSCustomObject]@{StatusCode=[int]$r.StatusCode;Body=$parsed}
    } catch {
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            $reader=New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $content=$reader.ReadToEnd()
            $parsed=if ([string]::IsNullOrWhiteSpace($content)) { $null } else { $content | ConvertFrom-Json }
            return [PSCustomObject]@{StatusCode=[int]$_.Exception.Response.StatusCode;Body=$parsed}
        }
        throw
    }
}

function Convert-PropertiesToHashtable([object]$Object) {
    $result=@{}
    foreach ($property in $Object.PSObject.Properties) { $result[$property.Name]=$property.Value }
    return $result
}

$headers=New-ProducerHeaders
$metadataObject=(Get-Content -LiteralPath $MetadataJsonPath -Raw) | ConvertFrom-Json
$metadata=Convert-PropertiesToHashtable $metadataObject
$expected=@()
for ($i=0; $i -lt $SourceDocumentIds.Count; $i++) { $expected += @{sourceDocumentId=$SourceDocumentIds[$i]} }

$createBody=@{
    sourceSubmissionId=$SourceSubmissionId
    expectedDocumentCount=$PdfPaths.Count
    expectedDocuments=$expected
    metadata=$metadata
}

$created=Invoke-ProducerJson 'POST' "$BaseUrl/api/v1/ingestion/submissions" $headers $createBody
if ($created.StatusCode -notin @(200,201)) { throw "Create submission failed with HTTP $($created.StatusCode)." }
$submissionId=[string]$created.Body.submissionId

$createReplay=Invoke-ProducerJson 'POST' "$BaseUrl/api/v1/ingestion/submissions" $headers $createBody
if ($createReplay.StatusCode -ne 200 -or [string]$createReplay.Body.submissionId -ne $submissionId -or -not [bool]$createReplay.Body.idempotentReplay) {
    throw 'Submission create replay did not resolve idempotently to the original SDV submission.'
}

$receipts=@()
$firstReplay=$null
for ($i=0; $i -lt $PdfPaths.Count; $i++) {
    $pdf=$PdfPaths[$i]
    if (-not (Test-Path -LiteralPath $pdf -PathType Leaf)) { throw "PDF does not exist: $pdf" }
    $sourceDocumentId=$SourceDocumentIds[$i]
    $uploadHeaders=@{} + $headers
    $uploadHeaders['X-SDV-Source-Document-Id']=$sourceDocumentId
    $uploadHeaders['X-SDV-Filename']=[System.IO.Path]::GetFileName($pdf)
    $uploadHeaders['Idempotency-Key']="$SourceSubmissionId::$sourceDocumentId"

    $upload=Invoke-WebRequest -Uri "$BaseUrl/api/v1/ingestion/submissions/$submissionId/documents" -Method Post -Headers $uploadHeaders -ContentType 'application/pdf' -InFile $pdf -UseBasicParsing -TimeoutSec 60
    if ([int]$upload.StatusCode -notin @(200,201)) { throw "Document upload failed with HTTP $($upload.StatusCode)." }
    $receipt=$upload.Content | ConvertFrom-Json
    $receipts += $receipt

    if ($ReplayFirstDocument -and $i -eq 0) {
        $replay=Invoke-WebRequest -Uri "$BaseUrl/api/v1/ingestion/submissions/$submissionId/documents" -Method Post -Headers $uploadHeaders -ContentType 'application/pdf' -InFile $pdf -UseBasicParsing -TimeoutSec 60
        $firstReplay=$replay.Content | ConvertFrom-Json
        if ([int]$replay.StatusCode -ne 200 -or -not [bool]$firstReplay.idempotentReplay -or [string]$firstReplay.documentId -ne [string]$receipt.documentId) {
            throw 'Document replay did not resolve idempotently to the original SDV document.'
        }
    }
}

$byId=Invoke-ProducerJson 'GET' "$BaseUrl/api/v1/ingestion/submissions/$submissionId" $headers $null
$encoded=[uri]::EscapeDataString($SourceSubmissionId)
$bySource=Invoke-ProducerJson 'GET' "$BaseUrl/api/v1/ingestion/submissions?sourceSubmissionId=$encoded" $headers $null
$documents=Invoke-ProducerJson 'GET' "$BaseUrl/api/v1/ingestion/submissions/$submissionId/documents" $headers $null
if ($byId.StatusCode -ne 200 -or $bySource.StatusCode -ne 200 -or $documents.StatusCode -ne 200) { throw 'Producer retrieval lifecycle failed.' }
if ([string]$bySource.Body.submissionId -ne $submissionId) { throw 'sourceSubmissionId retrieval returned a different submission.' }

$result=[ordered]@{
    contract='sdv-ingestion-v1'
    sourceSubmissionId=$SourceSubmissionId
    submissionId=$submissionId
    createStatusCode=$created.StatusCode
    createIdempotentReplay=[bool]$createReplay.Body.idempotentReplay
    submissionStatus=[string]$byId.Body.status
    documentCount=@($documents.Body).Count
    documentIds=@($receipts | ForEach-Object {[string]$_.documentId})
    receiptAcknowledgements=@($receipts | ForEach-Object {[string]$_.receiptAcknowledgement})
    firstDocumentReplay=if ($null -eq $firstReplay) { $null } else { [bool]$firstReplay.idempotentReplay }
}
$json=$result | ConvertTo-Json -Depth 20
if (-not [string]::IsNullOrWhiteSpace($OutputJson)) { Set-Content -LiteralPath $OutputJson -Value $json -Encoding UTF8 }
$json
