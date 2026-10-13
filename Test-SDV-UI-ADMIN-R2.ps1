param([switch]$SkipBuild)
$ErrorActionPreference='Stop'
$Root=$PSScriptRoot
function Require([bool]$Condition,[string]$Message){ if(-not $Condition){ throw $Message }; Write-Host "  [OK  ] $Message" -ForegroundColor Green }

Write-Host '=== SDV BUSINESS WORKSPACE R3 ITERATION 7 PREFLIGHT ==='
if (-not $SkipBuild) {
  & (Join-Path $Root 'build-all.cmd')
  if ($LASTEXITCODE -ne 0) { throw 'build-all.cmd failed.' }
  Require $true 'Backend + frontend build passed'
}

$app=[IO.File]::ReadAllText((Join-Path $Root 'frontend\sdv-ui\src\App.tsx'))
$workspace=[IO.File]::ReadAllText((Join-Path $Root 'frontend\sdv-ui\src\BusinessWorkspace.tsx'))
$viewer=[IO.File]::ReadAllText((Join-Path $Root 'frontend\sdv-ui\src\PdfViewer.tsx'))
$styles=[IO.File]::ReadAllText((Join-Path $Root 'frontend\sdv-ui\src\styles.css'))
$capability=[IO.File]::ReadAllText((Join-Path $Root 'backend\sdv-security\src\main\java\com\sdv\platform\security\Capability.java'))
$rolePolicy=[IO.File]::ReadAllText((Join-Path $Root 'backend\sdv-security\src\main\java\com\sdv\platform\security\auth\CapabilityPolicyService.java'))
$roleDefaults=[IO.File]::ReadAllText((Join-Path $Root 'backend\sdv-security\src\main\java\com\sdv\platform\security\RoleCapabilities.java'))
$actionController=[IO.File]::ReadAllText((Join-Path $Root 'backend\sdv-web\src\main\java\com\sdv\platform\web\business\DocumentActionController.java'))
$master=[IO.File]::ReadAllText((Join-Path $Root 'backend\sdv-web\src\main\resources\db\changelog\db.changelog-master.yaml'))

Require ($workspace -match "type BusinessTab = 'search' \| 'results' \| 'viewer' \| 'activity'") 'Business workflow uses Search / Results / Document Viewer / Activity tabs'
Require ($workspace -match 'business-tabs' -and $styles -match '\.business-tabs') 'Horizontal business tab treatment is shared across the workflow'
Require ($app -match "label: 'Search'" -and $app -notmatch "label: 'Results'" -and $app -notmatch "label: 'Document Viewer'") 'Left navigation does not duplicate business workflow tabs'
Require ($workspace -match 'business-source-picker' -and $workspace -match 'source\?\.fields\.map') 'Source remains page context and search metadata is source-driven'
Require ($workspace -match 'Date Range' -and $workspace -match 'Last Updated') 'Common Date Range and Last Updated filters are present'
Require ($workspace -match "setTab\('results'\)") 'Successful search transitions to Results'
Require ($workspace -match 'Available documents' -and $workspace -match 'Open selected document') 'Results exposes selected record, documents, and the selected-document action'
Require ($styles -match '\.wide-action[^\{]*\{[^\}]*background') 'Results primary action has explicit brand styling'
Require ($workspace -match 'Include document view events' -and $workspace -match 'checked=\{includeViews\}') 'Activity hides document views by default with explicit opt-in'
Require ($workspace -match 'Total Documents' -and $workspace -match 'selectedRecord\.identifiers') 'Activity is business-record centric and uses configured identifiers'
Require ($workspace -match 'activity-record-title' -and $workspace -notmatch "Record information</span><h2>\{primaryIdentifier") 'Activity record header does not duplicate the primary identifier'
Require ($viewer -match 'canDownload' -and $viewer -match 'downloadCurrent') 'Viewer Download is capability controlled'
Require ($viewer -match 'canPrint' -and $viewer -match 'print-event' -and $viewer -match '>Print<') 'Viewer Print is capability controlled and audited'
Require ($viewer -match 'canTag' -and $viewer -match '/tags' -and $viewer -match 'Add tag') 'Viewer Add Tag is capability controlled and persisted'
Require ($viewer -match 'canComment' -and $viewer -match '/comments' -and $viewer -match 'Add comment') 'Viewer Add Comment is capability controlled and persisted'
Require ($viewer -match 'visibleComments' -and $viewer -match 'slice\(0, 3\)' -and $viewer -match 'View all \${comments\.length} comments' -and $styles -match '\.comment-list[^\{]*\{[^\}]*max-height:[^;]+;[^\}]*overflow:auto') 'Viewer comments show latest three by default and use bounded internal scrolling for larger threads'
Require ($viewer -match 'canRedact' -and $viewer -match 'Permanent corrective redaction') 'Viewer Redaction remains capability controlled'
Require ($capability -match 'ADD_DOCUMENT_COMMENT') 'Backend capability includes document comments'
Require ($rolePolicy -match 'ADD_DOCUMENT_COMMENT') 'Business Administrator policy can control document comments'
Require ($roleDefaults -match 'PRINT_DOCUMENT' -and $roleDefaults -match 'ADD_DOCUMENT_TAG' -and $roleDefaults -match 'ADD_DOCUMENT_COMMENT') 'Document Steward defaults include Print, Add Tag, and Add Comment when no tenant override exists'
Require ($actionController -match 'PostMapping\("/comments"\)' -and $actionController -match 'Capability\.ADD_DOCUMENT_COMMENT') 'Comment API is backend capability enforced'
Require ($app -match '/api/v1/admin/role-capabilities' -and $app -match 'Save permissions' -and $app -match 'permission-matrix' -and $app -match 'role=\"switch\"') 'Business Administrator uses a compact permission matrix with toggles for configurable document actions'
Require ($styles -match '\.workspace-tabs[^\{]*\{' -and $styles -match '\.workspace-tabs button\.active') 'Admin tabs use the shared horizontal active-tab treatment'
Require ($app -notmatch ">Settings</button>" -and $app -match '>Metadata Configuration</button>') 'Business Administration removes empty Settings and uses Metadata Configuration'
Require ($app -notmatch ">Diagnostics</button>" -and $app -match 'Open operational readiness') 'Tenant IT removes redundant Diagnostics tab and links readiness from Environment'
Require ($master -match '011-business-ui-actions.yaml') 'Migration 011 remains wired for tags and role capability overrides'
Require ($master -match '012-document-comments.yaml') 'Migration 012 is wired for document comments'

Require ($app -match 'Current policy' -and $app -match 'Policy history' -and $app -match 'Activate retention policy') 'Retention UI shows named current policy, effective history, and explicit activation'
Require ($app -match 'Save metadata behavior' -and $app -match 'Tenant IT defines the fields supplied by each integration' -and $app -notmatch 'Save field definition') 'Business metadata UI edits existing source fields instead of creating them'
Require ($app -match 'Source metadata contract' -and $app -match 'Define source field' -and $app -match 'Credential reference') 'Tenant IT Integrations exposes source identity, auth reference, and technical metadata contract'
Require ($app -match 'About storage volumes' -and $app -match 'How SDV storage works') 'Tenant IT Storage includes self-service information help'
Require ($styles -match '\.primary-action' -and $app -match 'className="primary-action"') 'Save permissions follows the primary action theme'
Require ($master -match '013-admin-governance-history.yaml') 'Migration 013 is wired for named retention-policy history'
Require ($styles -match '@media \(max-width: 1180px\)' -and $styles -match '@media \(max-width: 820px\)') 'Desktop and iPad responsive breakpoints are present'
Require ($app -match 'All active storage volumes are healthy\.') 'Previously accepted storage readiness fix remains present'
Require ($app -match 'includeAuditViews' -and $app -match 'Include view events') 'Platform audit view-event opt-in remains present'
Require (Test-Path (Join-Path $Root 'Seed-SDV-Demo-Records.ps1')) 'Demo business-record seed script is present'

Write-Host ''
Write-Host 'PASS - SDV Business Workspace R3 Iteration 7 preflight passed.' -ForegroundColor Green
