Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$ErrorActionPreference='Stop'
$config=Join-Path $PSScriptRoot 'network-mode.json'
$current='secure'
if(Test-Path -LiteralPath $config){try{$m=(Get-Content $config -Raw -Encoding UTF8|ConvertFrom-Json).mode;if($m -in @('secure','compatibility')){$current=$m}}catch{}}
$form=New-Object Windows.Forms.Form
$form.Text='EMS 服务器 - 网络运行模式'
$form.Width=540;$form.Height=270;$form.StartPosition='CenterScreen'
$form.Font=New-Object Drawing.Font('Microsoft YaHei UI',10)
$label=New-Object Windows.Forms.Label
$label.Location=New-Object Drawing.Point(20,15);$label.Size=New-Object Drawing.Size(480,50)
$label.Text='选择服务器下次启动使用的网络模式。修改后需正常重启 EMS 服务才能生效。'
$form.Controls.Add($label)
$secure=New-Object Windows.Forms.RadioButton
$secure.Location=New-Object Drawing.Point(24,75);$secure.Size=New-Object Drawing.Size(475,30)
$secure.Text='安全模式（默认：公网数据库管理要求可信 HTTPS）'
$secure.Checked=($current -eq 'secure');$form.Controls.Add($secure)
$compat=New-Object Windows.Forms.RadioButton
$compat.Location=New-Object Drawing.Point(24,113);$compat.Size=New-Object Drawing.Size(475,30)
$compat.Text='内部测试兼容模式（允许当前 Frp 入口管理测试数据库）'
$compat.Checked=($current -eq 'compatibility');$form.Controls.Add($compat)
$save=New-Object Windows.Forms.Button
$save.Text='保存模式';$save.Location=New-Object Drawing.Point(20,170);$save.Size=New-Object Drawing.Size(125,35)
$save.Add_Click({
 $mode=if($compat.Checked){'compatibility'}else{'secure'}
 if($mode -eq 'compatibility'){
  $answer=[Windows.Forms.MessageBox]::Show('兼容模式将允许通过公网 HTTP 访问数据库管理。仅用于隔离测试数据，是否继续？','测试模式确认','YesNo','Warning')
  if($answer -ne 'Yes'){return}
 }
 $json=ConvertTo-Json -Compress -InputObject @{mode=$mode}
 [IO.File]::WriteAllText($config,$json,(New-Object Text.UTF8Encoding($false)))
 [Windows.Forms.MessageBox]::Show("已保存 $mode 模式。请正常重启 EMS 服务后生效。",'EMS')
 $form.Close()
})
$form.Controls.Add($save)
[void]$form.ShowDialog()
