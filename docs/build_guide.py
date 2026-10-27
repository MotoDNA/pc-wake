# 그림 설명서(HOW-TO-USE.html) 만들기: 이미지들을 페이지 안에 넣어 파일 하나로 만듭니다.
import base64, io, sys
from PIL import Image

SRC = sys.argv[1]   # 이미지 폴더
OUT = sys.argv[2]   # 결과 html

def img(name, width):
    im = Image.open(f"{SRC}/{name}").convert("RGB")
    h = int(im.height * width / im.width)
    im = im.resize((width, h), Image.LANCZOS)
    buf = io.BytesIO(); im.save(buf, "JPEG", quality=84, optimize=True)
    return "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode()

def phone(name, cap):
    return f'<figure class="ph"><img src="{img(name, 560)}" alt="{cap}"><figcaption>{cap}</figcaption></figure>'

def pc(name, cap, w=1100, cls=""):
    return f'<figure class="pc {cls}"><img src="{img(name, w)}" alt="{cap}"><figcaption>{cap}</figcaption></figure>'

html = f"""<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>PC 원격켜기 · 사용 설명서</title>
<style>
:root{{--bg:#F4F5F2;--card:#fff;--ink:#151917;--muted:#5F6763;--faint:#9BA29E;--line:#E3E6E2;--accent:#1E6B52;--soft:#E2EFE8;--hi:#FF6B2C}}
*{{box-sizing:border-box}}
body{{margin:0;background:var(--bg);color:var(--ink);font-family:'Malgun Gothic','Apple SD Gothic Neo','Noto Sans KR',sans-serif;line-height:1.6;-webkit-font-smoothing:antialiased}}
.wrap{{max-width:920px;margin:0 auto;padding:40px 20px 80px}}
header{{text-align:center;margin-bottom:34px}}
.badge{{display:inline-block;background:var(--soft);color:var(--accent);font-weight:700;font-size:13px;padding:5px 14px;border-radius:99px}}
h1{{font-size:34px;margin:12px 0 6px;letter-spacing:-.02em}}
.lead{{color:var(--muted);margin:0}}
.need{{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin:26px 0 8px}}
.need div{{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:16px;text-align:center}}
.need b{{display:block;font-size:16px}}.need span{{color:var(--muted);font-size:13px}}
.need i{{font-style:normal;font-size:26px;display:block;margin-bottom:4px}}
section{{background:var(--card);border:1px solid var(--line);border-radius:26px;padding:28px 28px 20px;margin-top:22px}}
.sh{{display:flex;align-items:center;gap:14px;margin-bottom:6px}}
.num{{flex:none;width:44px;height:44px;border-radius:50%;background:var(--accent);color:#fff;font-weight:800;font-size:20px;display:flex;align-items:center;justify-content:center}}
h2{{margin:0;font-size:23px;letter-spacing:-.01em}}
.where{{color:var(--muted);font-size:14px;margin:0 0 18px 58px}}
ol.steps{{list-style:none;padding:0;margin:0;counter-reset:s}}
ol.steps>li{{counter-increment:s;position:relative;padding:0 0 18px 58px}}
ol.steps>li:before{{content:counter(s);position:absolute;left:8px;top:1px;width:28px;height:28px;border-radius:50%;background:var(--soft);color:var(--accent);font-weight:800;font-size:14px;display:flex;align-items:center;justify-content:center}}
ol.steps>li>p{{margin:0 0 4px;font-size:16px}}
.tip{{color:var(--muted);font-size:14px}}
.hi{{color:var(--hi);font-weight:700}}
.shots{{display:flex;flex-wrap:wrap;gap:16px;margin:12px 0 4px}}
figure{{margin:0}}
figure.ph{{width:226px}}
figure.ph img{{width:100%;border-radius:22px;border:1px solid var(--line);box-shadow:0 4px 18px rgba(0,0,0,.06);display:block}}
figure.pc{{width:100%;max-width:520px}}
figure.pc.sm{{max-width:400px}}
figure.pc img{{width:100%;border-radius:12px;display:block;box-shadow:0 4px 18px rgba(0,0,0,.08)}}
figcaption{{font-size:13px;color:var(--muted);text-align:center;margin-top:8px}}
table.bios{{border-collapse:collapse;font-size:14px;margin:8px 0 4px;width:100%;max-width:620px}}
table.bios td{{border-top:1px solid var(--line);padding:7px 10px;vertical-align:top}}
table.bios td:first-child{{font-weight:700;white-space:nowrap;width:120px}}
.faq{{margin:0;padding:0;list-style:none}}
.faq li{{padding:12px 0;border-top:1px solid var(--line)}}.faq li:first-child{{border-top:0}}
.faq b{{display:block;margin-bottom:2px}}.faq span{{color:var(--muted);font-size:15px}}
footer{{text-align:center;color:var(--faint);font-size:13px;margin-top:30px}}
@media (max-width:640px){{.need{{grid-template-columns:1fr}}figure.ph{{width:calc(50% - 8px)}}.where,ol.steps>li{{padding-left:44px;margin-left:0}}.where{{margin-left:58px}}section{{padding:22px 18px 14px}}h1{{font-size:28px}}}}
@media print{{body{{background:#fff}}section{{break-inside:avoid-page;box-shadow:none}}}}
</style></head><body><div class="wrap">

<header>
  <span class="badge">처음 한 번만 · 약 10분</span>
  <h1>PC 원격켜기 사용 설명서</h1>
  <p class="lead">휴대폰 버튼 하나로, 밖에서도 꺼진 컴퓨터를 켜요.</p>
  <div class="need">
    <div><i>🖥</i><b>켤 컴퓨터</b><span>랜선으로 공유기에 연결</span></div>
    <div><i>📱</i><b>내 휴대폰</b><span>리모컨 (안드로이드)</span></div>
    <div><i>📟</i><b>남는 폰·탭</b><span>중계기 · 밖에서 켤 때 필요</span></div>
  </div>
</header>

<section>
  <div class="sh"><div class="num">1</div><h2>컴퓨터 준비하기</h2></div>
  <p class="where">켜고 싶은 컴퓨터에서 해요</p>
  <ol class="steps">
    <li><p>이 폴더의 <span class="hi">START-PC-Setup</span> 을 더블클릭하세요.</p>
      <p class="tip">"이 앱이 디바이스를 변경하도록 허용하시겠어요?" → <b>예</b><br>파란 "Windows의 PC 보호" 창이 뜨면 → <b>추가 정보 → 실행</b></p>
      <div class="shots">{pc('pc_folder.png','압축을 푼 폴더', 900)}</div></li>
    <li><p>파란 창에서 설정이 자동으로 끝나고, <b>QR 코드 창</b>이 열려요.</p>
      <p class="tip">이 QR 창은 3단계에서 폰으로 찍을 거예요. 그대로 띄워 두세요.</p>
      <div class="shots">{pc('pc_console.png','자동 설정 화면')}{pc('pc_register.png','이 PC 등록용 QR', 800, 'sm')}</div></li>
    <li><p><b>BIOS 설정 (딱 한 번)</b> — 재시작하면서 <b>Del</b> 또는 <b>F2</b> 를 연타해 들어간 뒤, 아래 항목을 <b>Enabled</b>(켜기)로 바꾸고 <b>F10</b>으로 저장하세요.</p>
      <table class="bios">
        <tr><td>ASUS</td><td>Advanced › APM › Power On By PCI-E</td></tr>
        <tr><td>MSI</td><td>Settings › Advanced › Wake Up Event Setup › Resume By PCI-E Device</td></tr>
        <tr><td>GIGABYTE</td><td>Settings › Platform Power › Wake on LAN</td></tr>
        <tr><td>ASRock</td><td>Advanced › ACPI › PCIE Devices Power On</td></tr>
        <tr><td>완제품·노트북</td><td>"Wake on LAN" 또는 "Remote Wake Up"</td></tr>
      </table>
      <p class="tip">"ErP" 또는 "Deep Sleep" 항목이 있으면 <b>Disabled</b>(끄기)로 바꿔 주세요.</p></li>
  </ol>
</section>

<section>
  <div class="sh"><div class="num">2</div><h2>중계기 켜 두기</h2></div>
  <p class="where">PC와 같은 와이파이에 연결된 남는 폰·탭에서 해요 · 같은 와이파이에서만 쓸 거면 건너뛰어도 돼요</p>
  <ol class="steps">
    <li><p><b>PC-Wake.apk</b> 를 설치하고 앱을 열어 <span class="hi">중계기</span>를 고르세요.</p>
      <p class="tip">설치할 때 "출처를 알 수 없는 앱" 허용 화면이 나오면 <b>허용</b>을 눌러주세요.</p></li>
    <li><p><b>중계기 시작</b>을 누르면 <b>"신호 대기 중"</b>으로 바뀌어요. 체크리스트의 <b>설정·허용</b> 버튼도 눌러 주세요.</p></li>
    <li><p><b>충전기에 꽂아 둔 채로</b> 두면 끝. 아래쪽에 리모컨 연결용 <b>QR</b>이 보여요.</p>
      <div class="shots">{phone('a_choose.png','중계기 고르기')}{phone('a_relay.png','신호 대기 중')}{phone('a_relayqr.png','리모컨 연결용 QR')}</div></li>
  </ol>
</section>

<section>
  <div class="sh"><div class="num">3</div><h2>리모컨으로 켜기</h2></div>
  <p class="where">늘 들고 다니는 내 휴대폰에서 해요</p>
  <ol class="steps">
    <li><p>같은 앱을 설치하고 <b>리모컨</b>을 고른 뒤, <span class="hi">QR 스캔</span>으로 <b>중계기 화면의 QR</b>을 찍으세요.</p></li>
    <li><p><span class="hi">컴퓨터 추가</span> → <b>QR로 가져오기</b> → <b>1단계 PC 화면의 QR</b>을 찍고 저장하세요.</p></li>
    <li><p><span class="hi">전원 버튼</span>을 누르면 끝! 초록색으로 바뀌고 <b>"켜짐 확인"</b>이 나오면 성공이에요.</p>
      <div class="shots">{phone('a_scan.png','중계기 QR 찍기')}{phone('a_add.png','컴퓨터 추가')}{phone('a_power.png','전원 켜기')}</div></li>
  </ol>
</section>

<section>
  <div class="sh"><div class="num">?</div><h2>안 될 때</h2></div>
  <ul class="faq">
    <li><b>"신호는 보냈지만 PC가 대답하지 않아요"</b><span>PC가 랜선으로 연결돼 있는지, PC를 끈 상태에서 랜 포트 불빛이 켜져 있는지 확인하세요. 불빛이 꺼져 있다면 1단계의 BIOS 설정을 다시 확인해 주세요.</span></li>
    <li><b>"중계기가 대답하지 않아요"</b><span>탭이 켜져 있고, 앱에 "신호 대기 중"이 보이는지 확인하세요. 삼성 기기는 설정 › 배터리 › 백그라운드 사용 제한 › '절전 예외 앱'에 추가해야 오래 켜져 있어요.</span></li>
    <li><b>노트북도 되나요?</b><span>랜 포트가 있고 충전기가 꽂혀 있으면 대부분 돼요. 와이파이로만 연결된 컴퓨터는 꺼진 상태에서 켤 수 없어요.</span></li>
    <li><b>QR이 남에게 알려졌어요</b><span>중계기 화면에서 '새 코드 만들기'를 누르고, 리모컨에서 QR을 다시 찍으세요.</span></li>
  </ul>
</section>

<footer>PC 원격켜기 · github.com/MotoDNA/pc-wake</footer>
</div></body></html>"""
open(OUT, "w", encoding="utf-8").write(html)
print("ok", len(html)//1024, "KB")
