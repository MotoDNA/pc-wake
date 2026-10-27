# PC 원격켜기 - PC 준비 도우미
# 이 컴퓨터가 '꺼진 상태에서 원격으로 켜질 수 있도록' 윈도우 설정을 바꾸고,
# 앱에 입력할 MAC 주소와 IP 주소를 보여줍니다.

$ErrorActionPreference = 'SilentlyContinue'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}

function Line($t, $c = 'Gray') { Write-Host $t -ForegroundColor $c }

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Line "관리자 권한이 필요합니다. 'START-PC-Setup.bat'를 더블클릭해서 실행해 주세요." Red
    Read-Host "엔터를 누르면 닫힙니다"
    exit 1
}

Clear-Host
Line "============================================================" Green
Line "   PC 원격켜기 - PC 준비 도우미" Green
Line "============================================================" Green
Line ""

# 1) 유선 랜카드 찾기 (와이파이 제외)
$wired = Get-NetAdapter -Physical | Where-Object { $_.NdisPhysicalMedium -eq 14 }
if (-not $wired) {
    Line "[!] 유선 랜카드(랜선 꽂는 곳)를 찾지 못했습니다." Red
    Line "    와이파이로만 연결된 컴퓨터는 꺼진 상태에서 원격으로 켤 수 없습니다." Yellow
    Line "    (노트북이라면 랜선 포트가 있는 모델이어야 하고, 충전기를 꽂아 두어야 합니다)" Yellow
    Read-Host "엔터를 누르면 닫힙니다"
    exit 1
}

$summary = @()
foreach ($a in $wired) {
    Line "▶ 랜카드: $($a.InterfaceDescription)" Cyan

    # 매직 패킷으로 깨우기 허용
    Set-NetAdapterPowerManagement -Name $a.Name -WakeOnMagicPacket Enabled -NoRestart
    # 제조사별 고급 설정 (있는 것만 바뀜)
    foreach ($kw in @('*WakeOnMagicPacket', 'WakeOnMagicPacket', 'S5WakeOnLan', 'EnablePME', 'WakeOnLink')) {
        $p = Get-NetAdapterAdvancedProperty -Name $a.Name -RegistryKeyword $kw
        if ($p -and $kw -ne 'WakeOnLink') {
            Set-NetAdapterAdvancedProperty -Name $a.Name -RegistryKeyword $kw -RegistryValue 1 -NoRestart
        }
    }
    # 절전용 '그린 이더넷'은 꺼진 상태에서 랜을 끊을 수 있으므로 끄기
    foreach ($kw in @('*EEE', 'EEELinkAdvertisement', 'EnableGreenEthernet', 'PowerSavingMode')) {
        if (Get-NetAdapterAdvancedProperty -Name $a.Name -RegistryKeyword $kw) {
            Set-NetAdapterAdvancedProperty -Name $a.Name -RegistryKeyword $kw -RegistryValue 0 -NoRestart
        }
    }
    # '이 장치를 사용하여 컴퓨터의 대기 모드를 종료할 수 있음' 켜기
    powercfg /deviceenablewake "$($a.InterfaceDescription)" | Out-Null
    Line "  - 매직 패킷 켜기 설정 완료" Green

    $mac = $a.MacAddress
    $ip = (Get-NetIPAddress -InterfaceIndex $a.ifIndex -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '169.254*' } | Select-Object -First 1).IPAddress
    $connected = $a.Status -eq 'Up'
    $summary += [pscustomobject]@{ Name = $a.InterfaceDescription; Mac = $mac; Ip = $ip; Up = $connected }
}

# 2) 빠른 시작 끄기 (켜져 있으면 완전히 꺼진 상태에서 랜카드가 잠들어 버림)
reg add "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Power" /v HiberbootEnabled /t REG_DWORD /d 0 /f | Out-Null
Line ""
Line "▶ 윈도우 '빠른 시작' 끄기 완료" Green

# 3) 결과 보여주기
Line ""
Line "============================================================" Green
Line "   앱에 입력할 값" Green
Line "============================================================" Green
$clip = "PC 이름: $env:COMPUTERNAME`r`n"
$best = $summary | Where-Object { $_.Up } | Select-Object -First 1
if (-not $best) { $best = $summary | Select-Object -First 1 }
foreach ($s in $summary) {
    $mark = if ($s.Up) { "(랜선 연결됨)" } else { "(랜선 안 꽂힘)" }
    Line ""
    Line "  랜카드  : $($s.Name) $mark" Gray
    Line "  MAC 주소: $($s.Mac)" White
    Line "  IP 주소 : $(if ($s.Ip) { $s.Ip } else { '없음' })" White
}
$clip += "MAC: $($best.Mac)`r`nIP: $($best.Ip)"
Set-Clipboard -Value $clip

# 폰 앱으로 찍어 바로 등록할 수 있는 QR 페이지 열기 (정보는 이 PC 안에서만 그려짐)
$pcName = ($env:COMPUTERNAME -replace '[|]', '')
$qrText = "PCWAKE-PC1|$pcName|$($best.Mac)|$($best.Ip)"
$enc = { param($t) [System.Net.WebUtility]::HtmlEncode([string]$t) }
$html = @"
<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>PC 원격켜기 - 이 PC 등록</title>
<style>body{font-family:'Malgun Gothic',sans-serif;background:#F4F5F2;color:#1B1F1D;text-align:center;padding:30px}
.card{display:inline-block;background:#fff;border-radius:20px;padding:30px 40px;box-shadow:0 2px 10px rgba(0,0,0,.08)}
h1{color:#1F6F54;margin:0 0 6px}p{color:#5F6763;margin:6px 0 20px}#qr{display:inline-block;padding:10px;background:#fff}
table{margin:20px auto 0;font-size:18px;text-align:left}td{padding:4px 10px}td:first-child{color:#5F6763}</style></head>
<body><div class="card"><h1>이 PC 등록하기</h1>
<p>휴대폰 'PC 원격켜기' 앱 › <b>+ 컴퓨터 추가</b> › <b>QR로 가져오기</b> 를 누르고 아래 QR을 비추세요.</p>
<div id="qr"></div>
<textarea id="data" style="display:none">$(& $enc $qrText)</textarea>
<table><tr><td>PC 이름</td><td>$(& $enc $pcName)</td></tr><tr><td>MAC 주소</td><td><b>$($best.Mac)</b></td></tr><tr><td>IP 주소</td><td><b>$($best.Ip)</b></td></tr></table>
</div>
<script>/*QRLIB*/</script>
<script>try{qrcode.stringToBytes=qrcode.stringToBytesFuncs['UTF-8'];var q=qrcode(0,'M');q.addData(document.getElementById('data').value,'Byte');q.make();document.getElementById('qr').innerHTML=q.createImgTag(7,14);}
catch(e){document.getElementById('qr').innerText='QR을 그리지 못했습니다. 아래 값을 직접 입력하세요.';}</script>
</body></html>
"@
# QR 그리는 도구(qrcode.js, MIT)를 페이지 안에 넣어서 인터넷 없이도 그려지게 함
$lib = Get-Content -Raw -Encoding UTF8 (Join-Path $PSScriptRoot 'qrcode.js')
$html = $html.Replace('/*QRLIB*/', $lib)
$htmlPath = Join-Path $env:TEMP "pcwake-register.html"
[System.IO.File]::WriteAllText($htmlPath, $html, (New-Object System.Text.UTF8Encoding $true))
Start-Process $htmlPath

Line ""
Line "  ※ 브라우저 창에 QR 코드가 열렸습니다. 폰 앱에서 '+ 컴퓨터 추가 › QR로 가져오기'로 찍으세요." Yellow
Line "  ※ 값은 클립보드에도 복사되어 있습니다." Yellow

if (-not ($summary | Where-Object { $_.Up })) {
    Line ""
    Line "[!] 지금 랜선이 꽂혀 있지 않습니다. 공유기와 랜선으로 연결해 주세요." Red
}

Line ""
Line "============================================================" Green
Line "   마지막으로 직접 해야 할 일 (BIOS 설정, 1번만)" Green
Line "============================================================" Green
Line "  1. 컴퓨터를 재시작하면서 Del 또는 F2 키를 연타 → BIOS 화면 진입"
Line "  2. 아래 이름 중 하나를 찾아 'Enabled(켜기)'로 바꾸기"
Line "       ASUS     : Advanced > APM > Power On By PCI-E"
Line "       MSI      : Settings > Advanced > Wake Up Event Setup > Resume By PCI-E Device"
Line "       GIGABYTE : Settings > Platform Power > Wake on LAN"
Line "       ASRock   : Advanced > ACPI > PCIE Devices Power On"
Line "       삼성/LG/HP/Dell 완제품: 'Wake on LAN' 또는 'Remote Wake Up'"
Line "  3. 'ErP' / 'Deep Sleep' 항목이 있으면 'Disabled(끄기)'"
Line "  4. F10 저장 후 재부팅"
Line ""
Line "  ※ IP가 바뀌지 않게 하려면 공유기 설정에서 이 PC에 '고정 IP(DHCP 예약)'를 해 두세요."
Line "  ※ 시험: PC를 '시스템 종료'한 뒤 앱에서 켜 보세요. 랜 포트 불빛이 켜져 있어야 정상입니다."
Line ""
Read-Host "엔터를 누르면 닫힙니다"
