$ErrorActionPreference = 'Stop'
$src = 'E:\Applied Energistics 2 Acceleration\_tb_src\Thunderbolt-Core-feature-generic-conflict-solver\src'
$dst = 'e:\Applied Energistics 2 Acceleration\AE2VMAddon\src\test\java'

$plannerFiles = @(
  'CraftGraph.java', 'CraftPattern.java', 'CraftInput.java', 'CraftOutput.java',
  'CraftPlan.java', 'ReusableStockKey.java', 'ReusableStockRouteKey.java',
  'ReusableStockSource.java', 'ReusableStockUsageKey.java', 'Sat.java',
  'PlanningCancellation.java'
)
$refFiles = @(
  'ReferenceCapability.java', 'ReferenceMaterialMode.java', 'ReferenceRunResult.java',
  'ReferenceSupportStatus.java', 'ReferencePlanner.java', 'ReferenceScenario.java',
  'ReferenceCapabilityRunner.java', 'ThunderboltReferenceScenarios.java'
)

$plannerDst = Join-Path $dst 'com\moakiee\thunderbolt\core\planner'
$refDst = Join-Path $plannerDst 'reference'
New-Item -ItemType Directory -Force -Path $plannerDst, $refDst | Out-Null

foreach ($f in $plannerFiles) {
  Copy-Item -Path (Join-Path "$src\main\java\com\moakiee\thunderbolt\core\planner" $f) -Destination $plannerDst -Force
}
foreach ($f in $refFiles) {
  Copy-Item -Path (Join-Path "$src\test\java\com\moakiee\thunderbolt\core\planner\reference" $f) -Destination $refDst -Force
}

$count = (Get-ChildItem $plannerDst -Recurse -Filter *.java).Count
Write-Host "COPIED $count java files"
