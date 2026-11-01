$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
. "$PSScriptRoot/runtime-common.ps1" -Mode server
. "$PSScriptRoot/network-policy.ps1"
$config=Join-Path $PSScriptRoot 'network-mode.json';$value=$null
if(Test-Path -LiteralPath $config){$value=Get-Content -LiteralPath $config -Raw -Encoding UTF8|ConvertFrom-Json}
$value=Resolve-Rc2Network $value 'server'
$form=New-Object Windows.Forms.Form;$form.Text='EMS RC2 · 网络模式';$form.Size=New-Object Drawing.Size(650,430);$form.StartPosition='CenterScreen'
$form.Font=New-Object Drawing.Font('Microsoft YaHei UI',10)
$choice=New-Object Windows.Forms.ComboBox;$choice.DropDownStyle='DropDownList';$choice.Items.AddRange(@('安全模式（默认）','兼容模式（仅虚构／脱敏测试）'));$choice.SelectedIndex=$(if($value.mode -eq 'compat'){1}else{0});$choice.Location=New-Object Drawing.Point(20,20);$choice.Size=New-Object Drawing.Size(590,30);$form.Controls.Add($choice)
$ack=New-Object Windows.Forms.CheckBox;$ack.Text='明确确认：兼容模式仅使用虚构或脱敏数据';$ack.Location=New-Object Drawing.Point(20,70);$ack.Size=New-Object Drawing.Size(590,30);$ack.Checked=$value.compatAcknowledged;$form.Controls.Add($ack)
$edge=New-Object Windows.Forms.CheckBox;$edge.Text='边缘 TLS：由 Sakura Frp / Cloudflare 等代理在外部终结 HTTPS（只在兼容模式下生效）';$edge.Location=New-Object Drawing.Point(20,105);$edge.Size=New-Object Drawing.Size(590,48);$edge.Checked=$value.edgeTls;$form.Controls.Add($edge)
$info=New-Object Windows.Forms.Label;$info.Text='安全模式：只监听本机回环，本机 HTTP 需本机单次授权代码，远程仅原生 HTTPS。兼容模式：允许经 Sakura Frp 等代理从任意域名或端口访问，不需要预先填写外部地址；登录、权限、CSRF 与 Host/Origin 同源校验全部保留，因此只适用于虚构或脱敏数据。勾选边缘 TLS 后，同源校验只忽略 http/https 协议差异（代理侧终结 TLS 时应用看不到浏览器协议），host 与端口仍必须完全一致，转发头依旧不被信任。保存只写配置，不启停实例、不修改 FRP、防火墙或系统证书，下次启动生效。';$info.Location=New-Object Drawing.Point(20,165);$info.Size=New-Object Drawing.Size(590,140);$form.Controls.Add($info)
$save=New-Object Windows.Forms.Button;$save.Text='保存，下次启动生效';$save.Location=New-Object Drawing.Point(20,320);$save.Width=220
$save.Add_Click({try{$value.mode=$(if($choice.SelectedIndex -eq 1){'compat'}else{'safe'});$value.compatAcknowledged=[bool]$ack.Checked;$value.edgeTls=[bool]$edge.Checked;Save-Rc2Network $value;[void][Windows.Forms.MessageBox]::Show('已保存。未启停任何实例，也未开放网络入口。','EMS');$form.Close()}catch{[void][Windows.Forms.MessageBox]::Show($_.Exception.Message,'未保存')}})
$form.Controls.Add($save);[void]$form.ShowDialog()
