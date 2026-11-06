# Gera uma chave de assinatura nova para o APK da Navi e prepara os segredos do GitHub.
# Rode no PC (PowerShell):  powershell -ExecutionPolicy Bypass -File tools\nova-chave-assinatura.ps1
# Precisa do Java (vem junto com o Android Studio).

$ErrorActionPreference = "Stop"

# Procura o keytool no PATH ou no Android Studio
$keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source
if (-not $keytool) {
    $candidatos = @(
        "$env:ProgramFiles\Android\Android Studio\jbr\bin\keytool.exe",
        "$env:ProgramFiles\Android\Android Studio\jre\bin\keytool.exe",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr\bin\keytool.exe"
    )
    $keytool = $candidatos | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $keytool) {
    Write-Host "Nao achei o keytool. Instale o Java (ou o Android Studio) e rode de novo." -ForegroundColor Red
    exit 1
}

# Senha aleatória forte
$chars = [char[]]"ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$bytes = New-Object byte[] 28
$rng.GetBytes($bytes)
$senha = -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })

$pasta = Join-Path $env:USERPROFILE "NaviChave"
New-Item -ItemType Directory -Force -Path $pasta | Out-Null
$ks = Join-Path $pasta "navi-release.keystore"
if (Test-Path $ks) {
    Write-Host "Ja existe uma chave em $ks. Apague a pasta se quiser gerar outra." -ForegroundColor Yellow
    exit 1
}

& $keytool -genkeypair -keystore $ks -storetype PKCS12 -alias navi -keyalg RSA -keysize 3072 `
    -validity 10000 -storepass $senha -keypass $senha -dname "CN=Navi, C=BR" | Out-Null

$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($ks))
Set-Content -Path (Join-Path $pasta "NAVI_KEYSTORE_B64.txt") -Value $b64 -NoNewline
Set-Content -Path (Join-Path $pasta "NAVI_KEYSTORE_PASSWORD.txt") -Value $senha -NoNewline

Write-Host ""
Write-Host "Chave criada em $pasta (guarde esta pasta num lugar seguro)." -ForegroundColor Green
Write-Host ""
Write-Host "Agora abra: https://github.com/CaioAndrade12/Navi/settings/secrets/actions"
Write-Host "e crie dois segredos (botao 'New repository secret'):"
Write-Host "  1) Nome NAVI_KEYSTORE_B64       -> cole o conteudo de NAVI_KEYSTORE_B64.txt"
Write-Host "  2) Nome NAVI_KEYSTORE_PASSWORD  -> cole o conteudo de NAVI_KEYSTORE_PASSWORD.txt"
Write-Host ""
Write-Host "O primeiro segredo ja foi copiado para a area de transferencia."
Set-Clipboard -Value $b64
Start-Process "https://github.com/CaioAndrade12/Navi/settings/secrets/actions"
Start-Process $pasta
