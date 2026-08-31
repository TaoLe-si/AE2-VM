$p = 'E:\Applied Energistics 2 Acceleration\VM-GTL\ae2vm-tmp-cpu-classes\appeng\crafting\execution\CraftingCpuLogic.class'
$out = 'E:\Applied Energistics 2 Acceleration\VM-GTL\ae2vm-tmp-cpu-decomp.txt'
& 'D:\Java21\bin\javap.exe' -p -c $p | Out-File -FilePath $out -Encoding utf8
Write-Host "EXIT=$LASTEXITCODE"