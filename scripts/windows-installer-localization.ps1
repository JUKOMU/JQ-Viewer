param(
    [Parameter(Mandatory = $true)][string]$BaseMsi,
    [Parameter(Mandatory = $true)][string]$JpackageTemp,
    [Parameter(Mandatory = $true)][string]$TransformPath
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# 复用 jpackage 的 WiX 对象，语言包和基础包始终使用同一产品标识与文件清单。
$config = Join-Path $JpackageTemp 'config'
$imageRoot = Get-ChildItem $JpackageTemp -Directory -Recurse -Filter 'win-msi.image'
$image = Join-Path $imageRoot.FullName 'JQ-Viewer'
$objects = @(Get-ChildItem (Join-Path $JpackageTemp 'wixobj') -Filter '*.wixobj' | ForEach-Object FullName)
$localizedMsi = [IO.Path]::ChangeExtension($TransformPath, '.msi')
$localization = Join-Path $config 'MsiInstallerStrings_zh_CN.wxl'
Push-Location $image
try {
    & light.exe -nologo -spdb -ext WixUtilExtension -ext WixUIExtension -sice:ICE27 -sice:ICE91 `
        -cultures:zh-cn -loc $localization -out $localizedMsi @objects
    if ($LASTEXITCODE -ne 0) { throw "Chinese MSI linking failed: $LASTEXITCODE" }
} finally {
    Pop-Location
}

# Windows Installer 的 COM 索引属性通过标准 IDispatch 接口读取。
function Read-MsiProperty($Database, [string]$Name) {
    $view = $Database.OpenView("SELECT ``Value`` FROM ``Property`` WHERE ``Property`` = '$Name'")
    $view.Execute()
    $record = $view.Fetch()
    $value = $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, @(1))
    $view.Close()
    [void][Runtime.InteropServices.Marshal]::ReleaseComObject($record)
    [void][Runtime.InteropServices.Marshal]::ReleaseComObject($view)
    return $value
}

$installer = New-Object -ComObject WindowsInstaller.Installer
$base = $installer.OpenDatabase($BaseMsi, 0)
$localized = $installer.OpenDatabase($localizedMsi, 1)
foreach ($name in @('ProductCode', 'UpgradeCode', 'ProductVersion')) {
    if ((Read-MsiProperty $base $name) -ne (Read-MsiProperty $localized $name)) {
        throw "Localized MSI changed $name"
    }
}

# JDK 模板默认将 ProductLanguage 固定为英文；同步设置中文产品及摘要语言。
$view = $localized.OpenView("UPDATE ``Property`` SET ``Value`` = '2052' WHERE ``Property`` = 'ProductLanguage'")
$view.Execute()
$view.Close()
[void][Runtime.InteropServices.Marshal]::ReleaseComObject($view)
$summary = $localized.GetType().InvokeMember('SummaryInformation', 'GetProperty', $null, $localized, @(1))
$template = $summary.GetType().InvokeMember('Property', 'GetProperty', $null, $summary, @(7))
[void]$summary.GetType().InvokeMember('Property', 'SetProperty', $null, $summary, @(7, ($template -replace ';.*$', ';2052')))
$summary.Persist()
$localized.Commit()
[void][Runtime.InteropServices.Marshal]::ReleaseComObject($summary)
[void][Runtime.InteropServices.Marshal]::ReleaseComObject($localized)
[void][Runtime.InteropServices.Marshal]::ReleaseComObject($base)

& torch.exe -nologo -t language $BaseMsi $localizedMsi -out $TransformPath
if ($LASTEXITCODE -ne 0) { throw "Language transform generation failed: $LASTEXITCODE" }

# 在构建时应用一次转换，检查语言元数据和真正的中文字符串，不启动安装界面。
$verificationMsi = [IO.Path]::ChangeExtension($TransformPath, '.verify.msi')
Copy-Item -LiteralPath $BaseMsi -Destination $verificationMsi
$verification = $installer.OpenDatabase($verificationMsi, 1)
# 英文与中文 MSI 使用不同代码页，32 是语言转换所需的标准标志。
$verification.ApplyTransform($TransformPath, 32)
if ((Read-MsiProperty $verification 'ProductLanguage') -ne '2052') {
    throw 'Language transform did not set ProductLanguage'
}
$view = $verification.OpenView("SELECT ``Title`` FROM ``Feature`` WHERE ``Feature`` = 'DefaultFeature'")
$view.Execute()
$record = $view.Fetch()
$title = $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, @(1))
[xml]$strings = Get-Content -LiteralPath $localization -Raw -Encoding UTF8
$expected = ($strings.WixLocalization.String | Where-Object Id -eq 'MainFeatureTitle').InnerText
if ($title -ne $expected) { throw 'Language transform did not localize the feature title' }
$view.Close()
foreach ($item in @($record, $view, $verification, $installer)) {
    [void][Runtime.InteropServices.Marshal]::ReleaseComObject($item)
}
Remove-Item -LiteralPath $verificationMsi
Write-Host 'Validated Chinese MSI transform and unchanged product identity.'
