# Uso (PowerShell, na pasta Sprints\E1-COMINDIR05):
#   .\executar.ps1 compilar
#   .\executar.ps1 no <k> <i>            # nó i (1..3) da partição k
#   .\executar.ps1 produtor | processador
#   .\executar.ps1 matar <k> <i>         # termina o nó à força e anota o instante em falhas.txt
#   .\executar.ps1 isolar <k> <i> | reparar <k> <i>   # partição de rede simulada
#   .\executar.ps1 raft-check [falhas.txt] | causal-check
# Os ficheiros de cada ensaio ficam em $env:RAFT04_ENSAIO (por omissão ensaios\manual).
param([Parameter(Position = 0)][string]$acao = "ajuda",
      [Parameter(Position = 1, ValueFromRemainingArguments = $true)][string[]]$resto)
$ErrorActionPreference = "Stop"
$base = $PSScriptRoot
$N = if ($env:RAFT04_N) { $env:RAFT04_N } else { "3" }
$resto = @($resto | Where-Object { $_ })
$ensaio = if ($env:RAFT04_ENSAIO) { $env:RAFT04_ENSAIO } else { Join-Path $base "ensaios\manual" }
New-Item -ItemType Directory -Force -Path $ensaio | Out-Null
function Agora { [DateTimeOffset]::Now.ToUnixTimeMilliseconds() }

switch ($acao) {
    "compilar" {
        foreach ($p in "raft04-no", "raft04-produtor", "raft04-processador") {
            $out = Join-Path $base "bin\$p"
            if (Test-Path $out) { Remove-Item -Recurse -Force $out }
            javac --release 11 -encoding UTF-8 -d $out (Get-ChildItem (Join-Path $base "$p\src\raft04\*.java")).FullName
            if ($LASTEXITCODE -ne 0) { exit 1 }
        }
        Write-Host "Três projetos compilados."
        exit 0
    }
    "matar" {
        $k, $i = $resto
        "p$k;n$i;$(Agora)" | Add-Content (Join-Path $ensaio "falhas.txt")
        Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
            Where-Object { $_.CommandLine -match "raft04\.PartitionNode $k $i( |$)" } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force; Write-Host "Nó n$i de p$k terminado (PID $($_.ProcessId))" }
        exit 0
    }
    "isolar"  { $k, $i = $resto; New-Item -ItemType File -Force (Join-Path $ensaio "isolar-p$k-n$i") | Out-Null
                "p$k;n$i;$(Agora);isolar" | Add-Content (Join-Path $ensaio "isolamentos.txt"); Write-Host "n$i de p$k isolado"; exit 0 }
    "reparar" { $k, $i = $resto; Remove-Item -Force (Join-Path $ensaio "isolar-p$k-n$i") -ErrorAction SilentlyContinue
                "p$k;n$i;$(Agora);reparar" | Add-Content (Join-Path $ensaio "isolamentos.txt"); Write-Host "n$i de p$k reparado"; exit 0 }
    "no"           { $projeto = "raft04-no";          $classe = "PartitionNode";  $argumentos = @($resto) + $N }
    "produtor"     { $projeto = "raft04-produtor";    $classe = "Producer";       $argumentos = @($N) + $resto }
    "processador"  { $projeto = "raft04-processador"; $classe = "AlarmProcessor"; $argumentos = @($N) + $resto }
    "raft-check"   { $projeto = "raft04-no";          $classe = "RaftCheck";      $argumentos = @($N, ".") + $resto }
    "causal-check" { $projeto = "raft04-no";          $classe = "CausalCheck";    $argumentos = @($N, ".") }
    default { Write-Host "Uso: .\executar.ps1 compilar|no <k> <i>|produtor|processador|matar <k> <i>|isolar <k> <i>|reparar <k> <i>|raft-check [falhas.txt]|causal-check"; exit 1 }
}

$periodo = if ($env:RAFT04_PERIODO) { $env:RAFT04_PERIODO } else { "2000" }
$props = @("-Draft04.periodo=$periodo", "-Dstdout.encoding=UTF-8")
if ($env:RAFT04_ELEICAO_MIN) { $props += "-Draft04.eleicaoMin=$env:RAFT04_ELEICAO_MIN" }
if ($env:RAFT04_ELEICAO_MAX) { $props += "-Draft04.eleicaoMax=$env:RAFT04_ELEICAO_MAX" }
Push-Location $ensaio
try { & java @props -cp (Join-Path $base "bin\$projeto") "raft04.$classe" @argumentos } finally { Pop-Location }
