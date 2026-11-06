"""Ações que a Navi pode fazer no Windows.

Para ensinar algo novo ao PC, crie uma função com o decorador @tool.
O celular busca esta lista sozinho (GET /tools), sem precisar recompilar o app.
"""

import base64
import ctypes
import datetime
import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
from pathlib import Path

import janelas
from janelas import norm

IS_WIN = sys.platform == "win32"
CREATE_NO_WINDOW = 0x08000000
TOOLS = {}
SETTINGS = {"allow_powershell": True}


def tool(name, description, params=None, required=None, confirm=None, confirm_always=False):
    """Registra uma ação. `confirm` é a pergunta que o celular faz antes (pode usar {arg})."""
    def deco(fn):
        schema = None
        if params:
            schema = {"type": "object", "properties": params}
            if required:
                schema["required"] = required
        TOOLS[name] = {
            "fn": fn,
            "description": description,
            "parameters": schema,
            "confirm": confirm or "",
            "confirm_always": confirm_always,
        }
        return fn
    return deco


def S(desc, enum=None):
    d = {"type": "string", "description": desc}
    if enum:
        d["format"] = "enum"
        d["enum"] = enum
    return d


def I(desc):
    return {"type": "integer", "description": desc}


def B(desc):
    return {"type": "boolean", "description": desc}


def ok(msg="feito", **extra):
    return {"status": "ok", "resultado": msg, **extra}


def erro(msg):
    return {"status": "erro", "erro": msg}


def later(seconds, fn):
    """Executa depois de responder ao celular (ex.: suspender o PC)."""
    threading.Timer(seconds, fn).start()


# ---------------------------------------------------------------- comandos

def _texto(b):
    if not b:
        return ""
    for enc in ("utf-8", "cp850", "cp1252"):
        try:
            return b.decode(enc)
        except UnicodeDecodeError:
            continue
    return b.decode("utf-8", "replace")


def cmd(args, timeout=30):
    """Roda um programa sem abrir janela. stdin nulo: obrigatório num .exe sem console."""
    r = subprocess.run(args, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                       timeout=timeout, creationflags=CREATE_NO_WINDOW if IS_WIN else 0)
    return r.returncode, _texto(r.stdout).strip(), _texto(r.stderr).strip()


def _powershell(script, timeout):
    encoded = base64.b64encode(script.encode("utf-16-le")).decode("ascii")
    return cmd(["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", encoded], timeout)


def _b64_saida(out):
    """A saída vem em base64 (UTF-8), assim acentos nunca se perdem, seja qual for a página de código."""
    linhas = [l.strip() for l in out.splitlines() if l.strip()]
    if not linhas:
        return ""
    try:
        return base64.b64decode(linhas[-1]).decode("utf-8")
    except Exception:
        return out


def ps(script, timeout=40):
    """PowerShell com texto de saída (avisos e erros juntos). Devolve (código, texto)."""
    wrapped = ("$ProgressPreference='SilentlyContinue';"
               "$__o = & {\n" + script + "\n} *>&1 | Out-String -Width 250;"
               "[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]$__o))")
    rc, out, err = _powershell(wrapped, timeout)
    texto = _b64_saida(out).strip()
    if not texto and err:
        texto = err
    return rc, texto


def ps_json(expr, timeout=40):
    """Roda uma expressão PowerShell e devolve o resultado como lista de dicionários."""
    wrapped = ("$ProgressPreference='SilentlyContinue';$ErrorActionPreference='SilentlyContinue';"
               "$__j = (" + expr + ") | ConvertTo-Json -Compress -Depth 4;"
               "if ($__j) { [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]$__j)) }")
    rc, out, err = _powershell(wrapped, timeout)
    texto = _b64_saida(out)
    if not texto:
        return []
    try:
        data = json.loads(texto)
    except ValueError:
        return []
    if isinstance(data, dict):
        return [data]
    return data if isinstance(data, list) else []


# ---------------------------------------------------------------- teclado

user32 = janelas.user32 if IS_WIN else None

VK = {
    "ctrl": 0x11, "control": 0x11, "shift": 0x10, "alt": 0x12, "win": 0x5B, "windows": 0x5B,
    "tab": 0x09, "enter": 0x0D, "esc": 0x1B, "escape": 0x1B, "space": 0x20, "espaco": 0x20,
    "backspace": 0x08, "delete": 0x2E, "del": 0x2E, "up": 0x26, "cima": 0x26, "down": 0x28,
    "baixo": 0x28, "left": 0x25, "esquerda": 0x25, "right": 0x27, "direita": 0x27,
    "home": 0x24, "end": 0x23, "pageup": 0x21, "pagedown": 0x22, "printscreen": 0x2C,
    "volumeup": 0xAF, "volumedown": 0xAE, "mute": 0xAD, "playpause": 0xB3,
    "next": 0xB0, "prev": 0xB1, "stop": 0xB2,
}
for _i in range(1, 13):
    VK[f"f{_i}"] = 0x6F + _i
for _c in "abcdefghijklmnopqrstuvwxyz0123456789":
    VK[_c] = ord(_c.upper())


def key_tap(vk, times=1, gap=0.02):
    for _ in range(times):
        user32.keybd_event(vk, 0, 0, 0)
        user32.keybd_event(vk, 0, 2, 0)
        time.sleep(gap)


def hotkey(names):
    codes = [VK[n] for n in names]
    for c in codes:
        user32.keybd_event(c, 0, 0, 0)
        time.sleep(0.02)
    for c in reversed(codes):
        user32.keybd_event(c, 0, 2, 0)
        time.sleep(0.02)


def type_text(text):
    """Digita qualquer texto (com acentos) na janela que estiver em foco."""
    data = text.encode("utf-16-le")
    units = [int.from_bytes(data[i:i + 2], "little") for i in range(0, len(data), 2)]
    for u in units:
        if u == 10:  # quebra de linha vira Enter
            key_tap(0x0D)
            continue
        down = janelas.INPUT(type=1, u=janelas._INPUTUNION(ki=janelas.KEYBDINPUT(0, u, 0x0004, 0, 0)))
        up = janelas.INPUT(type=1, u=janelas._INPUTUNION(ki=janelas.KEYBDINPUT(0, u, 0x0004 | 0x0002, 0, 0)))
        arr = (janelas.INPUT * 2)(down, up)
        user32.SendInput(2, arr, ctypes.sizeof(janelas.INPUT))
        time.sleep(0.004)


# ---------------------------------------------------------------- áudio

@tool("pc_volume", "Consulta ou muda o volume do PC: consultar, definir um nível exato (0 a 100), aumentar, "
      "diminuir, mudo ou tirar do mudo.",
      {"acao": S("o que fazer", ["consultar", "definir", "aumentar", "diminuir", "mudo", "tirar_mudo"]),
       "nivel": I("para definir: 0 a 100; para aumentar/diminuir: quantos pontos (padrão 10)")},
      ["acao"])
def pc_volume(acao, nivel=None):
    if acao == "nivel":  # nome antigo
        acao = "definir"
    passo = 10 if nivel is None else int(nivel)
    problema = ""
    try:
        import audio_win
        with audio_win.Volume() as v:
            atual, mudo = v.nivel(), v.mudo()
            if acao == "consultar":
                return ok(f"O volume do PC está em {atual}%" + (", no mudo" if mudo else "") + ".",
                          volume=atual, mudo=mudo)
            if acao == "mudo":
                v.definir_mudo(True)
                return ok("PC no mudo.", volume=atual, mudo=True)
            if acao == "tirar_mudo":
                v.definir_mudo(False)
                return ok(f"Som do PC ligado, volume em {atual}%.", volume=atual, mudo=False)
            alvo = passo if acao == "definir" else atual + passo if acao == "aumentar" else atual - passo
            alvo = max(0, min(100, int(alvo)))
            if mudo:
                v.definir_mudo(False)
            v.definir(alvo)
            novo = v.nivel()
            return ok(f"Volume do PC em {novo}%.", volume=novo, mudo=False)
    except Exception as e:  # sem placa de som, driver estranho...
        problema = str(e)
    # Reserva: teclas de volume do teclado (cada toque muda 2%).
    if acao == "consultar":
        return erro(f"Não consegui ler o volume do PC ({problema}).")
    if acao in ("mudo", "tirar_mudo"):
        key_tap(VK["mute"])
        return ok("Alternei o mudo do PC pelas teclas de volume.")
    if acao == "definir":
        key_tap(VK["volumedown"], 50)
        key_tap(VK["volumeup"], max(0, min(100, passo)) // 2)
        return ok(f"Volume do PC em cerca de {passo}% (pelas teclas de volume).")
    key_tap(VK["volumeup" if acao == "aumentar" else "volumedown"], max(1, passo // 2))
    return ok("Volume ajustado pelas teclas de volume.")


@tool("pc_midia", "Controla a música/vídeo que está tocando no PC (Spotify, YouTube, etc.).",
      {"acao": S("ação", ["tocar_pausar", "proxima", "anterior", "parar"])}, ["acao"])
def pc_midia(acao):
    key_tap(VK[{"tocar_pausar": "playpause", "proxima": "next", "anterior": "prev", "parar": "stop"}[acao]])
    return ok("Feito.")


# ---------------------------------------------------------------- tela

@tool("pc_brilho", "Ajusta o brilho da tela do PC (0 a 100).", {"nivel": I("0 a 100")}, ["nivel"])
def pc_brilho(nivel):
    n = max(0, min(100, int(nivel)))
    try:
        import screen_brightness_control as sbc
        sbc.set_brightness(n)
        return ok(f"Brilho em {n}%.")
    except ImportError:
        pass
    except Exception:
        pass
    rc, out = ps(f"(Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightnessMethods)"
                 f" | Invoke-CimMethod -MethodName WmiSetBrightness -Arguments @{{Timeout=1;Brightness={n}}}")
    if rc == 0 and "error" not in out.lower() and "erro" not in out.lower():
        return ok(f"Brilho em {n}%.")
    return erro("Este monitor não aceita mudar o brilho por software (monitores de PC de mesa geralmente não aceitam).")


@tool("pc_modo_escuro", "Liga ou desliga o modo escuro do Windows.", {"ligar": B("true = escuro")}, ["ligar"])
def pc_modo_escuro(ligar):
    import winreg
    val = 0 if ligar else 1
    k = winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                       r"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize", 0, winreg.KEY_SET_VALUE)
    winreg.SetValueEx(k, "AppsUseLightTheme", 0, winreg.REG_DWORD, val)
    winreg.SetValueEx(k, "SystemUsesLightTheme", 0, winreg.REG_DWORD, val)
    winreg.CloseKey(k)
    res = ctypes.c_ulong()
    ctypes.windll.user32.SendMessageTimeoutW(0xFFFF, 0x001A, 0, "ImmersiveColorSet", 0x0002, 3000, ctypes.byref(res))
    return ok("Modo escuro ligado." if ligar else "Modo claro ligado.")


@tool("pc_desligar_tela", "Desliga o monitor do PC (volta ao mexer no mouse).")
def pc_desligar_tela():
    later(1, lambda: ctypes.windll.user32.PostMessageW(0xFFFF, 0x0112, 0xF170, 2))
    return ok("Desligando a tela.")


@tool("pc_print", "Tira um print da tela do PC e salva em Imagens\\Navi.")
def pc_print():
    pasta = Path.home() / "Pictures" / "Navi"
    pasta.mkdir(parents=True, exist_ok=True)
    arq = pasta / datetime.datetime.now().strftime("print_%Y%m%d_%H%M%S.png")
    try:
        from PIL import ImageGrab
        ImageGrab.grab(all_screens=True).save(arq)
    except ImportError:
        rc, out = ps(
            "Add-Type -AssemblyName System.Windows.Forms,System.Drawing;"
            "$b=[System.Windows.Forms.SystemInformation]::VirtualScreen;"
            "$bmp=New-Object System.Drawing.Bitmap $b.Width,$b.Height;"
            "$g=[System.Drawing.Graphics]::FromImage($bmp);"
            "$g.CopyFromScreen($b.Left,$b.Top,0,0,$bmp.Size);"
            f"$bmp.Save('{arq}')")
        if rc != 0:
            return erro("Não consegui tirar o print.")
    return ok(f"Print salvo em {arq}.")


# ---------------------------------------------------------------- programas, sites e janelas

NAVEGADORES = {"chrome.exe", "msedge.exe", "firefox.exe", "brave.exe", "opera.exe", "vivaldi.exe", "iexplore.exe",
               "launcher.exe", "arc.exe", "zen.exe", "floorp.exe", "chromium.exe", "yandex.exe"}


def _pasta_especial(csidl):
    buf = ctypes.create_unicode_buffer(520)
    if ctypes.windll.shell32.SHGetFolderPathW(None, csidl, None, 0, buf) == 0:
        return Path(buf.value)
    return None


def _sistema():
    """Programas do próprio Windows, pelos nomes que as pessoas falam."""
    home = Path.home()
    pastas = {
        "downloads": home / "Downloads", "documentos": home / "Documents", "imagens": home / "Pictures",
        "fotos": home / "Pictures", "musicas": home / "Music", "videos": home / "Videos",
    }
    desk = _pasta_especial(0x0010) if IS_WIN else None
    if desk:
        pastas["area de trabalho"] = desk
    s = {
        "bloco de notas": ("exe", "notepad.exe"), "notepad": ("exe", "notepad.exe"),
        "calculadora": ("exe", "calc.exe"), "paint": ("exe", "mspaint.exe"),
        "gerenciador de tarefas": ("exe", "taskmgr.exe"),
        "explorador de arquivos": ("exe", "explorer.exe"), "explorer": ("exe", "explorer.exe"),
        "arquivos": ("exe", "explorer.exe"), "meus arquivos": ("exe", "explorer.exe"),
        "prompt de comando": ("exe", "cmd.exe"), "cmd": ("exe", "cmd.exe"),
        "powershell": ("exe", "powershell.exe"), "painel de controle": ("exe", "control.exe"),
        "configuracoes": ("uri", "ms-settings:"), "configuracoes do windows": ("uri", "ms-settings:"),
        "lixeira": ("uri", "shell:RecycleBinFolder"),
    }
    for nome, p in pastas.items():
        s[nome] = ("arquivo", str(p))
        s["pasta " + nome] = ("arquivo", str(p))
    return s


_progs = {"t": 0.0, "lista": []}


def programas():
    """Lista (nome, tipo, destino) com os programas do menu Iniciar e os atalhos da área de trabalho."""
    if time.time() - _progs["t"] < 600 and _progs["lista"]:
        return _progs["lista"]
    lista = []
    for a in ps_json("Get-StartApps | Select-Object Name,AppID"):
        nome, appid = a.get("Name"), a.get("AppID")
        if nome and appid:
            lista.append((nome, "appid", appid))
    # Jogos da Steam/Epic e outros atalhos costumam ficar só na área de trabalho.
    for csidl in (0x0010, 0x0019):  # área de trabalho do usuário e pública
        pasta = _pasta_especial(csidl)
        if pasta and pasta.exists():
            for f in list(pasta.glob("*.lnk")) + list(pasta.glob("*.url")):
                lista.append((f.stem, "arquivo", str(f)))
    if lista:
        _progs.update(t=time.time(), lista=lista)
    return lista


def best_match(query, names):
    q = norm(query)
    if not q:
        return None
    scored = []
    for n in names:
        m = norm(n)
        if not m:
            continue
        if m == q:
            s = 100
        elif m.startswith(q + " ") or m.startswith(q):
            s = 80
        elif q in m.split():
            s = 70
        elif q in m:
            s = 50
        elif all(w in m for w in q.split()):
            s = 40
        elif len(m) >= 4 and m in q:
            s = 30  # "abre o spotify agora" contém "spotify"
        else:
            continue
        scored.append((s, -len(m), n))
    return max(scored)[2] if scored else None


def parece_site(alvo):
    a = alvo.strip().lower()
    return a.startswith(("http://", "https://", "www.")) or bool(re.fullmatch(r"[a-z0-9-]+(\.[a-z0-9-]+)+(/\S*)?", a))


# Tipos que executam código: a Navi não abre esses por caminho (só programas do menu Iniciar).
PERIGOSOS = {".exe", ".bat", ".cmd", ".com", ".ps1", ".psm1", ".vbs", ".vbe", ".js", ".jse", ".wsf", ".wsh",
             ".msi", ".msp", ".scr", ".hta", ".jar", ".lnk", ".url", ".reg", ".cpl", ".dll", ".pif", ".appref-ms"}


def seguro_para_abrir(alvo):
    """Bloqueia caminhos de rede, esquemas estranhos (file:, ms-...:) e arquivos executáveis."""
    a = alvo.strip().strip('"')
    if a.startswith(("\\\\", "//")):
        return False, "caminho de rede"
    if re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]+:", a) and not re.match(r"^[a-zA-Z]:[\\/]", a):
        return False, "endereço especial"
    if Path(a).suffix.lower() in PERIGOSOS:
        return False, "arquivo executável"
    return True, ""


def _continuar_esperando(antes, pista, exes, segundos):
    """Programa demorado (jogos...): continua esperando em segundo plano e traz para a frente quando abrir."""
    def esperar():
        j = janelas.esperar_nova(antes, pista=pista, exes=exes, segundos=segundos)
        if j:
            janelas.frente(j.hwnd)
    threading.Thread(target=esperar, daemon=True).start()


def lancar(nome, tipo, destino):
    """Abre e traz a janela para a frente. Devolve o texto do resultado."""
    antes = {j.hwnd for j in janelas.listar()}
    janelas.liberar_frente()
    silencio = dict(stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if tipo == "appid":
        subprocess.Popen(["explorer.exe", "shell:AppsFolder\\" + destino], **silencio)
    elif tipo == "exe":
        subprocess.Popen([destino], **silencio)
    else:
        os.startfile(destino)
    pista = nome if tipo != "exe" else os.path.splitext(destino)[0]
    j = janelas.esperar_nova(antes, pista=pista, segundos=7)
    if j:
        na_frente = janelas.frente(j.hwnd)
        return ok(f"Abri {nome}" + ("." if na_frente else ", mas o Windows não deixou colocar a janela na frente."),
                  janela=j.titulo)
    _continuar_esperando(antes, pista, None, 45)
    return ok(f"Mandei abrir {nome}. A janela ainda não apareceu (alguns programas demoram); "
              "quando aparecer eu coloco na frente.")


def abrir_site(url):
    antes = {j.hwnd for j in janelas.listar()}
    janelas.liberar_frente()
    os.startfile(url)
    j = janelas.esperar_nova(antes, exes=NAVEGADORES, segundos=6)
    if j:
        janelas.frente(j.hwnd)
    else:
        _continuar_esperando(antes, None, NAVEGADORES, 20)
    return ok(f"Abri {url} no navegador do PC.")


@tool("pc_abrir", "Abre no PC um programa ou jogo pelo nome (ex.: Chrome, Spotify, Discord, Steam, Bloco de Notas, "
      "Calculadora, Configurações, Downloads), um site (ex.: youtube.com) ou um arquivo/pasta, e traz a janela "
      "para a frente.",
      {"alvo": S("nome do programa, endereço do site, pasta ou caminho")}, ["alvo"])
def pc_abrir(alvo):
    alvo = alvo.strip().strip(".")
    if not alvo:
        return erro("Diga o que abrir.")
    if parece_site(alvo):
        url = alvo if alvo.lower().startswith(("http://", "https://")) else "https://" + alvo
        return abrir_site(url)
    sistema = _sistema()
    chave = norm(alvo)
    if chave in sistema:
        tipo, destino = sistema[chave]
        return lancar(alvo, tipo, destino)
    lista = programas()
    nome = best_match(alvo, [n for n, _, _ in lista])
    if nome:
        tipo, destino = next((t, d) for n, t, d in lista if n == nome)
        return lancar(nome, tipo, destino)
    nome_sis = best_match(alvo, list(sistema.keys()))
    if nome_sis:
        tipo, destino = sistema[nome_sis]
        return lancar(nome_sis, tipo, destino)
    seguro, motivo = seguro_para_abrir(alvo)
    if not seguro:
        return erro(f"Por segurança não abro isso ({motivo}). Peça um programa pelo nome ou um site.")
    if Path(alvo).exists():
        return lancar(Path(alvo).name, "arquivo", alvo)
    q = norm(alvo)
    parecidos = [n for n, _, _ in lista if q[:4] and q[:4] in norm(n)][:8]
    return erro(f"Não achei \"{alvo}\" no PC." + (f" Parecidos: {', '.join(parecidos)}." if parecidos else
                                                    " Ele precisa estar no menu Iniciar ou na área de trabalho."))


@tool("pc_pesquisar", "Pesquisa algo no Google ou no YouTube, no navegador do PC.",
      {"busca": S("o que pesquisar"), "onde": S("site", ["google", "youtube"])}, ["busca"])
def pc_pesquisar(busca, onde="google"):
    q = urllib.parse.quote_plus(busca)
    url = f"https://www.youtube.com/results?search_query={q}" if onde == "youtube" else f"https://www.google.com/search?q={q}"
    abrir_site(url)
    return ok(f"Pesquisei \"{busca}\" no {onde}, no navegador do PC.")


def primeiro_video(busca):
    """Primeiro resultado de uma busca no YouTube: (id, título)."""
    url = ("https://www.youtube.com/results?search_query=" + urllib.parse.quote_plus(busca) + "&hl=pt-BR&gl=BR")
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                      "Chrome/124.0 Safari/537.36",
        "Accept-Language": "pt-BR,pt;q=0.9", "Cookie": "CONSENT=YES+1"})
    html = urllib.request.urlopen(req, timeout=12).read().decode("utf-8", "replace")
    m = re.search(r'"videoRenderer":\{"videoId":"([\w-]{11})"', html)
    if not m:
        return None, None
    t = re.search(r'"title":\{"runs":\[\{"text":"(.*?)"\}', html[m.end():m.end() + 4000])
    titulo = json.loads('"' + t.group(1) + '"') if t else ""
    return m.group(1), titulo


@tool("pc_youtube", "Toca no PC o primeiro vídeo do YouTube para uma busca (música, clipe, vídeo), em tela normal.",
      {"busca": S("o que procurar no YouTube")}, ["busca"])
def pc_youtube(busca):
    try:
        vid, titulo = primeiro_video(busca)
    except Exception:
        vid, titulo = None, None
    if not vid:
        return pc_pesquisar(busca, "youtube")
    abrir_site(f"https://www.youtube.com/watch?v={vid}")
    return ok(f"Tocando no YouTube do PC: {titulo or busca}.")


@tool("pc_janelas", "Lista as janelas abertas no PC (para saber o que está aberto).")
def pc_janelas():
    lista = [{"titulo": j.titulo[:80], "programa": j.exe} for j in janelas.listar()][:25]
    return {"status": "ok", "janelas": lista}


@tool("pc_focar", "Traz para a frente uma janela que já está aberta no PC (pelo nome do programa ou título).",
      {"alvo": S("nome do programa ou parte do título")}, ["alvo"])
def pc_focar(alvo):
    j = janelas.achar(alvo)
    if not j:
        return erro(f"Não achei nenhuma janela aberta de \"{alvo}\". Use pc_abrir para abrir.")
    na_frente = janelas.frente(j.hwnd)
    return ok(f"Coloquei {j.titulo} na frente." if na_frente else "Achei a janela, mas o Windows não deixou trazer para a frente.")


def processos():
    rc, out, _ = cmd(["tasklist", "/FO", "CSV", "/NH"])
    nomes = set()
    for linha in out.splitlines():
        partes = [p.strip('"') for p in linha.split('","')]
        if partes and partes[0].lower().endswith(".exe"):
            nomes.add(partes[0])
    return sorted(nomes)


@tool("pc_fechar", "Fecha um programa aberto no PC (pelo nome).", {"app": S("nome do programa")}, ["app"],
      confirm="Vou fechar {app} no PC. Se tiver algo sem salvar, pode perder. Confirma?")
def pc_fechar(app):
    abertas = [j for j in janelas.listar() if janelas.combina(j, app)]
    if abertas:
        for j in abertas:
            janelas.fechar(j.hwnd)  # como clicar no X: o programa pode perguntar se quer salvar
        return ok(f"Pedi para fechar {len(abertas)} janela(s) de {app}.")
    procs = processos()
    alvo = best_match(app, [p[:-4] for p in procs])
    if not alvo:
        return erro(f"Não achei \"{app}\" aberto no PC.")
    cmd(["taskkill", "/IM", alvo + ".exe"])
    return ok(f"Pedi para fechar {alvo}.")


# ---------------------------------------------------------------- configurações do Windows

PAGINAS = {
    "inicio": "ms-settings:", "som": "ms-settings:sound", "tela": "ms-settings:display",
    "bluetooth": "ms-settings:bluetooth", "wifi": "ms-settings:network-wifi", "rede": "ms-settings:network-status",
    "energia": "ms-settings:powersleep", "bateria": "ms-settings:batterysaver",
    "notificacoes": "ms-settings:notifications", "aplicativos": "ms-settings:appsfeatures",
    "inicializacao": "ms-settings:startupapps", "atualizacoes": "ms-settings:windowsupdate",
    "papel_de_parede": "ms-settings:personalization-background", "cores": "ms-settings:colors",
    "luz_noturna": "ms-settings:nightlight", "mouse": "ms-settings:mousetouchpad", "teclado": "ms-settings:typing",
    "data_hora": "ms-settings:dateandtime", "idioma": "ms-settings:regionlanguage",
    "privacidade": "ms-settings:privacy", "armazenamento": "ms-settings:storagesense",
    "impressoras": "ms-settings:printers", "contas": "ms-settings:yourinfo", "jogos": "ms-settings:gaming-gamebar",
    "acessibilidade": "ms-settings:easeofaccess", "sobre": "ms-settings:about",
}


@tool("pc_configuracoes", "Abre uma tela específica das Configurações do Windows (som, tela, bluetooth, wi-fi, "
      "energia, atualizações, papel de parede, luz noturna...).",
      {"pagina": S("qual tela", list(PAGINAS.keys()))}, ["pagina"])
def pc_configuracoes(pagina):
    uri = PAGINAS.get(pagina)
    if not uri:
        return erro("Tela de configuração desconhecida.")
    antes = {j.hwnd for j in janelas.listar()}
    janelas.liberar_frente()
    os.startfile(uri)
    j = janelas.esperar_nova(antes, exes={"systemsettings.exe", "applicationframehost.exe"}, segundos=6)
    if j:
        janelas.frente(j.hwnd)
    return ok(f"Abri as configurações de {pagina.replace('_', ' ')}.")


@tool("pc_plano_energia", "Muda o plano de energia do PC.",
      {"plano": S("plano", ["economia", "equilibrado", "desempenho"])}, ["plano"])
def pc_plano_energia(plano):
    alias = {"economia": "SCHEME_MAX", "equilibrado": "SCHEME_BALANCED", "desempenho": "SCHEME_MIN"}[plano]
    rc, out, err = cmd(["powercfg", "/setactive", alias])
    if rc != 0:
        return erro(f"O Windows não aceitou o plano ({err or out}).")
    return ok(f"Plano de energia: {plano}.")


@tool("pc_tempo_tela", "Define em quantos minutos parado o PC desliga a tela (0 = nunca).",
      {"minutos": I("minutos (0 = nunca)")}, ["minutos"])
def pc_tempo_tela(minutos):
    m = max(0, int(minutos))
    cmd(["powercfg", "/change", "monitor-timeout-ac", str(m)])
    cmd(["powercfg", "/change", "monitor-timeout-dc", str(m)])
    return ok("A tela não vai mais desligar sozinha." if m == 0 else f"A tela desliga depois de {m} minutos parado.")


BLUETOOTH_PS = r"""
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
  $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
  $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Await($op, $type) { $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op)); $t.Wait(-1) | Out-Null; $t.Result }
[Windows.Devices.Radios.Radio,Windows.System.Devices,ContentType=WindowsRuntime] | Out-Null
[Windows.Devices.Radios.RadioAccessStatus,Windows.System.Devices,ContentType=WindowsRuntime] | Out-Null
[Windows.Devices.Radios.RadioState,Windows.System.Devices,ContentType=WindowsRuntime] | Out-Null
Await ([Windows.Devices.Radios.Radio]::RequestAccessAsync()) ([Windows.Devices.Radios.RadioAccessStatus]) | Out-Null
$radios = Await ([Windows.Devices.Radios.Radio]::GetRadiosAsync()) ([System.Collections.Generic.IReadOnlyList[Windows.Devices.Radios.Radio]])
$bt = $radios | Where-Object { $_.Kind -eq 'Bluetooth' } | Select-Object -First 1
if (-not $bt) { 'NAVI:SEM_BLUETOOTH' } else {
  $r = Await ($bt.SetStateAsync('__ESTADO__')) ([Windows.Devices.Radios.RadioAccessStatus])
  'NAVI:' + $r
}
"""


@tool("pc_bluetooth", "Liga ou desliga o Bluetooth do PC (para fones e caixas de som).",
      {"ligar": B("true liga, false desliga")}, ["ligar"])
def pc_bluetooth(ligar):
    try:
        rc, out = ps(BLUETOOTH_PS.replace("__ESTADO__", "On" if ligar else "Off"), timeout=40)
    except subprocess.TimeoutExpired:
        return erro("O Windows demorou demais para responder sobre o Bluetooth.")
    if "NAVI:SEM_BLUETOOTH" in out:
        return erro("Este PC não tem Bluetooth (ou ele está desativado no Gerenciador de Dispositivos).")
    if "NAVI:Allowed" in out:
        return ok("Bluetooth do PC ligado." if ligar else "Bluetooth do PC desligado.")
    return erro("O Windows não deixou mudar o Bluetooth: " + (out.strip()[-200:] or "sem detalhes"))


# ---------------------------------------------------------------- teclado e avisos

@tool("pc_digitar", "Digita um texto na janela que estiver em foco no PC.",
      {"texto": S("texto a digitar"), "enter": B("apertar Enter no final")}, ["texto"])
def pc_digitar(texto, enter=False):
    type_text(texto)
    if enter:
        key_tap(0x0D)
    return ok("Digitado.")


@tool("pc_atalho", "Aperta um atalho de teclado no PC, ex.: ctrl+c, alt+tab, win+d (mostrar a área de trabalho), "
      "alt+f4, ctrl+shift+esc, f11 (tela cheia).",
      {"teclas": S("teclas separadas por +")}, ["teclas"])
def pc_atalho(teclas):
    nomes = [norm(t).replace(" ", "") for t in teclas.split("+") if t.strip()]
    desconhecidas = [n for n in nomes if n not in VK]
    if not nomes or desconhecidas:
        return erro(f"Tecla desconhecida: {', '.join(desconhecidas) or teclas}")
    hotkey(nomes)
    return ok(f"Apertei {teclas}.")


@tool("pc_aviso", "Mostra uma mensagem na tela do PC.", {"texto": S("mensagem")}, ["texto"])
def pc_aviso(texto):
    threading.Thread(target=lambda: ctypes.windll.user32.MessageBoxW(0, texto, "Navi", 0x40 | 0x1000 | 0x10000),
                     daemon=True).start()
    return ok("Mensagem mostrada no PC.")


# ---------------------------------------------------------------- sistema

@tool("pc_bloquear", "Bloqueia o PC (tela de senha).")
def pc_bloquear():
    ctypes.windll.user32.LockWorkStation()
    return ok("PC bloqueado.")


@tool("pc_energia", "Desliga, reinicia, suspende ou hiberna o PC, ou cancela um desligamento agendado.",
      {"acao": S("ação", ["desligar", "reiniciar", "suspender", "hibernar", "cancelar"]),
       "minutos": I("daqui a quantos minutos (padrão 0)")},
      ["acao"], confirm="Vou {acao} o PC. Confirma?", confirm_always=True)
def pc_energia(acao, minutos=0):
    seg = max(0, int(minutos or 0)) * 60
    if acao == "cancelar":
        cmd(["shutdown", "/a"])
        return ok("Desligamento cancelado.")
    if acao in ("desligar", "reiniciar"):
        flag = "/s" if acao == "desligar" else "/r"
        cmd(["shutdown", flag, "/t", str(max(seg, 5))])
        quando = f"em {minutos} minutos" if seg else "em 5 segundos"
        return ok(f"O PC vai {acao} {quando}.")
    if acao == "hibernar":
        later(max(seg, 2), lambda: cmd(["shutdown", "/h"]))
        return ok("Hibernando o PC.")
    later(max(seg, 2), lambda: ctypes.windll.powrprof.SetSuspendState(0, 1, 0))
    return ok("Suspendendo o PC.")


class _POWER(ctypes.Structure):
    _fields_ = [("ACLineStatus", ctypes.c_byte), ("BatteryFlag", ctypes.c_byte), ("BatteryLifePercent", ctypes.c_byte),
                ("SystemStatusFlag", ctypes.c_byte), ("BatteryLifeTime", ctypes.c_ulong),
                ("BatteryFullLifeTime", ctypes.c_ulong)]


@tool("pc_status", "Mostra como está o PC: uso de CPU e memória, bateria, volume, tempo ligado e programas que mais gastam.")
def pc_status():
    info = {}
    try:
        import psutil
        info["cpu_porcentagem"] = psutil.cpu_percent(interval=0.6)
        mem = psutil.virtual_memory()
        info["memoria_porcentagem"] = mem.percent
        info["memoria_total_gb"] = round(mem.total / 2**30, 1)
        procs = sorted(psutil.process_iter(["name", "memory_info"]),
                       key=lambda p: p.info["memory_info"].rss if p.info.get("memory_info") else 0, reverse=True)
        info["mais_memoria"] = list(dict.fromkeys(p.info["name"] for p in procs[:12] if p.info.get("name")))[:5]
    except ImportError:
        dados = ps_json("Get-CimInstance Win32_OperatingSystem | Select-Object FreePhysicalMemory,TotalVisibleMemorySize")
        if dados:
            d = dados[0]
            try:
                info["memoria_porcentagem"] = round(100 - float(d["FreePhysicalMemory"]) * 100 /
                                                    float(d["TotalVisibleMemorySize"]), 1)
            except Exception:
                pass
    p = _POWER()
    if ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(p)) and (p.BatteryLifePercent & 0xFF) != 255:
        info["bateria_porcentagem"] = p.BatteryLifePercent & 0xFF
        info["na_tomada"] = p.ACLineStatus == 1
    ms = ctypes.windll.kernel32.GetTickCount64
    ms.restype = ctypes.c_ulonglong
    info["ligado_ha_horas"] = round(ms() / 3_600_000, 1)
    try:
        import audio_win
        with audio_win.Volume() as v:
            info["volume"] = v.nivel()
            info["mudo"] = v.mudo()
    except Exception:
        pass
    j = janelas.listar()
    if j:
        info["janela_na_frente"] = j[0].titulo[:80]
    return {"status": "ok", **info}


@tool("pc_powershell", "Roda um comando do PowerShell no PC e devolve a saída. Use só quando nenhuma outra ação servir "
      "(ex.: ver IP, listar arquivos, mudar uma configuração específica do Windows).",
      {"comando": S("comando do PowerShell")}, ["comando"],
      confirm="Vou rodar no PowerShell do PC: {comando}. Confirma?", confirm_always=True)
def pc_powershell(comando):
    if not SETTINGS.get("allow_powershell", True):
        return erro("Comandos de PowerShell estão bloqueados no NaviPC (ícone ✦ > Permitir/bloquear PowerShell).")
    try:
        rc, out = ps(comando, timeout=60)
    except subprocess.TimeoutExpired:
        return erro("O comando demorou mais de 60 segundos e foi interrompido.")
    return {"status": "ok" if rc == 0 else "erro", "codigo": rc, "saida": out[-3000:] or "(sem saída)"}


def run(name, args):
    t = TOOLS.get(name)
    if not t:
        return erro(f"Ação desconhecida: {name}. Atualize as ações do PC nos ajustes da Navi.")
    if not IS_WIN:
        return erro("O navi-pc só funciona no Windows.")
    try:
        return t["fn"](**(args or {}))
    except TypeError as e:
        return erro(f"Parâmetros errados para {name}: {e}")
    except subprocess.TimeoutExpired:
        return erro("O Windows demorou demais para responder.")
    except Exception as e:
        return erro(f"{type(e).__name__}: {e}")


def declarations():
    out = []
    for name, t in TOOLS.items():
        d = {"name": name, "description": t["description"]}
        if t["parameters"]:
            d["parameters"] = t["parameters"]
        if t["confirm"]:
            d["confirm"] = t["confirm"]
        if t["confirm_always"]:
            d["confirm_always"] = True
        out.append(d)
    return out
