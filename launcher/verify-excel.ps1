param([Parameter(Mandatory=$true)][string]$Workbook,[Parameter(Mandatory=$true)][string]$ExpectedTsv,[Parameter(Mandatory=$true)][string]$OutputFile)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead($Workbook)
function Xml-Entry([string]$Name){
    $reader=New-Object IO.StreamReader($zip.GetEntry($Name).Open(),[Text.Encoding]::UTF8)
    try{return [xml]$reader.ReadToEnd()}finally{$reader.Dispose()}
}
try{
    $shared=Xml-Entry 'xl/sharedStrings.xml'
    $strings=@($shared.sst.si|ForEach-Object{$_.InnerText})
    $sheet=Xml-Entry 'xl/worksheets/sheet1.xml'
    $expected=@{}
    foreach($line in (Get-Content -LiteralPath $ExpectedTsv -Encoding UTF8)){if($line){$v=$line -split [char]9;$expected[$v[0]]=$v}}
    $rows=@($sheet.worksheet.sheetData.row|Select-Object -Skip 1)
    if($rows.Count -ne $expected.Count){throw 'Excel/database salary row count differs'}
    $checked=0
    foreach($row in $rows){
        $cells=@{};foreach($cell in $row.c){$cells[($cell.r -replace '\d','')]=$cell}
        $number=if($cells.A.t -eq 's'){$strings[[int]$cells.A.v]}else{[string]$cells.A.v}
        if(-not $expected.ContainsKey($number)){throw 'Excel contains an unexpected employee'}
        $i=1
        foreach($column in @('E','F','G','H','I','J','K','L','M','N')){
            $cell=$cells[$column]
            if(-not $cell -or $cell.t -in @('s','inlineStr')){throw 'Excel amount is no longer numeric'}
            $a=[decimal]::Parse([string]$cell.v,[Globalization.CultureInfo]::InvariantCulture)
            $b=[decimal]::Parse([string]$expected[$number][$i],[Globalization.CultureInfo]::InvariantCulture)
            if($a -ne $b){throw "Excel numeric amount changed in column $column"}
            $i++;$checked++
        }
    }
    $result="Excel: $($rows.Count) salary rows, $checked numeric amounts match database decimals"
    [IO.File]::WriteAllText($OutputFile,$result,(New-Object Text.UTF8Encoding($false)));Write-Host "PASS: $result"
}finally{$zip.Dispose()}
