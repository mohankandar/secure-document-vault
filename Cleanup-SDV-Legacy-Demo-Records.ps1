[CmdletBinding(SupportsShouldProcess=$true, ConfirmImpact='High')]
param(
    [string]$SourceDisplayName = 'laxavault',
    [switch]$Force
)

$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$Tenant='11111111-1111-1111-1111-111111111111'
$LegacySubmissionIds=@(
    'demo-contract-100000000001',
    'demo-contract-100000000002',
    'demo-contract-100000000003'
)

function DbRows([string]$Sql) {
    $raw = & docker exec sdv-postgres psql -U sdv -d sdv -At -F "`t" -v ON_ERROR_STOP=1 -c $Sql
    if($LASTEXITCODE -ne 0){ throw 'PostgreSQL query failed.' }
    return @($raw)
}
function DbExec([string]$Sql) {
    & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c $Sql | Out-Null
    if($LASTEXITCODE -ne 0){ throw 'PostgreSQL cleanup failed.' }
}

Write-Host '=== SDV LEGACY DEMO CLEANUP ==='
Write-Host 'This removes only the three exact pre-R3 demo submissions under the laxavault source.'

$escapedName=$SourceDisplayName.Replace("'","''")
$sourceRows=DbRows "select source_id::text || E'\\t' || display_name from ingestion_sources where tenant_id='$Tenant' and lower(display_name)=lower('$escapedName') order by created_at desc limit 1;"
if($sourceRows.Count -ne 1){ throw "Source '$SourceDisplayName' was not found uniquely." }
$parts=$sourceRows[0] -split "`t",2
$SourceId=$parts[0]
$ResolvedName=$parts[1]

$idsSql=($LegacySubmissionIds | ForEach-Object { "'" + $_.Replace("'","''") + "'" }) -join ','
$subRows=DbRows "select submission_id::text || E'\\t' || source_submission_id from submissions where tenant_id='$Tenant' and source_id='$SourceId' and source_submission_id in ($idsSql) order by source_submission_id;"
if($subRows.Count -eq 0){ Write-Host 'No legacy demo submissions found. Nothing to clean.' -ForegroundColor Green; return }

$submissionUuids=@()
foreach($row in $subRows){ $p=$row -split "`t",2; $submissionUuids += $p[0]; Write-Host "  Found legacy demo: $($p[1])" }
$subUuidSql=($submissionUuids | ForEach-Object { "'$_'::uuid" }) -join ','

$redactionCount=(DbRows "select count(*) from document_redaction_operations where tenant_id='$Tenant' and submission_id in ($subUuidSql);")[0]
if([int]$redactionCount -gt 0){ throw 'Legacy demo data contains permanent-redaction operations. Cleanup refuses to proceed automatically.' }

$storageRows=DbRows @"
select d.document_id::text || E'\t' || v.root_path || E'\t' || r.object_key
from documents d
join document_storage_refs r on r.tenant_id=d.tenant_id and r.document_id=d.document_id
join storage_volumes v on v.tenant_id=r.tenant_id and v.volume_id=r.storage_volume_id
where d.tenant_id='$Tenant' and d.submission_id in ($subUuidSql);
"@

$target="legacy SDV demo submissions in source '$ResolvedName'"
if(-not $Force -and -not $PSCmdlet.ShouldProcess($target,'Permanently delete demo database rows and encrypted demo objects')){ return }

# Delete encrypted objects first. The exact paths come from committed storage refs.
foreach($row in $storageRows){
    $p=$row -split "`t",3
    $path=Join-Path $p[1] ($p[2].Replace('/',[IO.Path]::DirectorySeparatorChar))
    if(Test-Path -LiteralPath $path){ Remove-Item -LiteralPath $path -Force; Write-Host "  Deleted encrypted demo object: $($p[2])" }
}

DbExec @"
begin;
delete from document_comments where tenant_id='$Tenant' and document_id in (select document_id from documents where tenant_id='$Tenant' and submission_id in ($subUuidSql));
delete from document_tags where tenant_id='$Tenant' and document_id in (select document_id from documents where tenant_id='$Tenant' and submission_id in ($subUuidSql));
delete from audit_events where tenant_id='$Tenant' and target_id in (
  select document_id::text from documents where tenant_id='$Tenant' and submission_id in ($subUuidSql)
  union all select submission_id::text from submissions where tenant_id='$Tenant' and submission_id in ($subUuidSql)
);
delete from submission_expected_documents where tenant_id='$Tenant' and submission_id in ($subUuidSql);
delete from document_crypto_metadata where tenant_id='$Tenant' and document_id in (select document_id from documents where tenant_id='$Tenant' and submission_id in ($subUuidSql));
delete from document_storage_refs where tenant_id='$Tenant' and document_id in (select document_id from documents where tenant_id='$Tenant' and submission_id in ($subUuidSql));
delete from documents where tenant_id='$Tenant' and submission_id in ($subUuidSql);
delete from business_metadata_values where tenant_id='$Tenant' and submission_id in ($subUuidSql);
delete from submissions where tenant_id='$Tenant' and submission_id in ($subUuidSql);
commit;
"@

Write-Host 'Legacy demo records removed. R3 demo records were not touched.' -ForegroundColor Green
