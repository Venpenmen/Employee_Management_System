$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
. "$PSScriptRoot/runtime-common.ps1" -Mode server
. "$PSScriptRoot/network-policy.ps1"
$form=New-Object Windows.Forms.Form
$form.Text='EMS 完整候选 · 网络模式';$form.Width=660;$form.Height=450;$form.StartPosition='CenterScreen'
$form.Font=New-Object Drawing.Font('Microsoft YaHei UI',10)
$labels=@('模式：local / lan / proxy','LAN IPv4（明确选择本机地址）','PKCS12 路径（相对本安装目录）','信任证书 PEM 路径（相对本安装目录）','代理入口 Origin（例如 https://example.test）')
$controls=@()
for($i=0;$i -lt 5;$i++){
 $label=New-Object Windows.Forms.Label;$label.Text=$labels[$i];$label.Location=New-Object Drawing.Point(15,(20+55*$i));$label.Size=New-Object Drawing.Size(610,23);$form.Controls.Add($label)
 if($i -eq 0){$input=New-Object Windows.Forms.ComboBox;$input.DropDownStyle='DropDownList';$input.Items.AddRange(@('local','lan','proxy'));$input.SelectedIndex=0}else{$input=New-Object Windows.Forms.TextBox}
 $input.Location=New-Object Drawing.Point(15,(43+55*$i));$input.Size=New-Object Drawing.Size(610,25);$form.Controls.Add($input);$controls+=,$input
}
$config=Join-Path $PSScriptRoot 'network-mode.json'
if(Test-Path -LiteralPath $config){$existing=Get-Content -LiteralPath $config -Raw -Encoding UTF8|ConvertFrom-Json;if($existing.mode -in @('local','lan','proxy')){$controls[0].SelectedItem=$existing.mode};$controls[1].Text=[string]$existing.address;$controls[2].Text=[string]$existing.keyStore;$controls[3].Text=[string]$existing.trustCertificate;$controls[4].Text=[string]$existing.publicOrigin}
$info=New-Object Windows.Forms.Label;$info.Text='proxy 仅监听本机且必须使用真实 TLS。口令只通过 EMS_TLS_STORE_PASSWORD 提供。此工具不会调整 FRP、防火墙或系统证书。';$info.Location=New-Object Drawing.Point(15,320);$info.Size=New-Object Drawing.Size(610,45);$form.Controls.Add($info)
$save=New-Object Windows.Forms.Button;$save.Text='保存下次启动设置';$save.Location=New-Object Drawing.Point(15,375);$save.Width=180
$save.Add_Click({try{
 $s=Read-EmsState;if($s -and (Get-OwnedProcess $s.app)){throw '请先使用本候选的停止入口正常停止，再修改网络模式；不会自动停止任何实例'}
 $value=[pscustomobject]@{mode=[string]$controls[0].SelectedItem;address=$controls[1].Text.Trim();keyStore=$controls[2].Text.Trim();trustCertificate=$controls[3].Text.Trim();publicOrigin=$controls[4].Text.Trim()}
 $policy=Resolve-CompleteNetwork $value 'server' ''
 if($policy.tls){foreach($name in @('keyStore','trustCertificate')){$file=Assert-ArtifactPath $script:DataDir (Join-Path $script:EmsRoot $value.$name);if(-not(Test-Path -LiteralPath $file -PathType Leaf)){throw '请先在本候选 data/complete/server 下准备有效 TLS 文件'}}}
 Write-Utf8 ($config+'.tmp') ($value|ConvertTo-Json);Move-Item -LiteralPath ($config+'.tmp') -Destination $config -Force
 [Windows.Forms.MessageBox]::Show('设置已保存；下次启动生效。未修改系统网络或既有服务。','EMS');$form.Close()
}catch{[Windows.Forms.MessageBox]::Show($_.Exception.Message,'未保存')}})
$form.Controls.Add($save);[void]$form.ShowDialog()
