"""Ações que a Navi pode fazer no Windows.

Para ensinar algo novo ao PC, crie uma função com o decorador @tool.
O celular busca esta lista sozinho (GET /tools), sem precisar recompilar o app.
"""

import ctypes
import datetime
import json
import os
import re
import subprocess
import sys
import threading
import time
import unicodedata
import urllib.parse
import webbrowser
from pathlib import Path

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


def norm(s):
    s = unicodedata.normalize("NFD", str(s).lower())
    s = "".join(ch for ch in s if unicodedata.category(ch) != "Mn")
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]", " ", s)).strip()


def ps(command, timeout=40):
    """Roda um comando do PowerShell e devolve (código, saída)."""
    r = subprocess.run(
        ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command],
        capture_output=True, text=True, timeout=timeout,
        creationflags=CREATE_NO_WINDOW if IS_WIN else 0,
        encoding="utf-8", errors="replace",
    )
    out = (r.stdout or "") + (("\n" + r.stderr) if r.stderr and r.stderr.strip() else "")
    return r.returncode, out.strip()


def later(seconds, fn):
    """Executa depois de responder ao celular (ex.: suspender o PC)."""
    threading.Timer(seconds, fn).start()


# ---------------------------------------------------------------- teclado

user32 = ctypes.windll.user32 if IS_WIN else None

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


def key_tap(vk, times=1):
    for _ in range(times):
        user32.keybd_event(vk, 0, 0, 0)
        user32.keybd_event(vk, 0, 2, 0)
        time.sleep(0.02)


def hotkey(names):
    codes = [VK[n] for n in names]
    for c in codes:
        user32.keybd_event(c, 0, 0, 0)
        time.sleep(0.02)
    for c in reversed(codes):
        user32.keybd_event(c, 0, 2, 0)
        time.sleep(0.02)


if IS_WIN:
    ULONG_PTR = ctypes.c_size_t

    class _KEYBDINPUT(ctypes.Structure):
        _fields_ = [("wVk", ctypes.c_ushort), ("wScan", ctypes.c_ushort), ("dwFlags", ctypes.c_ulong),
                    ("time", ctypes.c_ulong), ("dwExtraInfo", ULONG_PTR)]

    class _MOUSEINPUT(ctypes.Structure):
        _fields_ = [("dx", ctypes.c_long), ("dy", ctypes.c_long), ("mouseData", ctypes.c_ulong),
                    ("dwFlags", ctypes.c_ulong), ("time", ctypes.c_ulong), ("dwExtraInfo", ULONG_PTR)]

    class _HARDWAREINPUT(ctypes.Structure):
        _fields_ = [("uMsg", ctypes.c_ulong), ("wParamL", ctypes.c_ushort), ("wParamH", ctypes.c_ushort)]

    class _UNION(ctypes.Union):
        _fields_ = [("ki", _KEYBDINPUT), ("mi", _MOUSEINPUT), ("hi", _HARDWAREINPUT)]

    class _INPUT(ctypes.Structure):
        _fields_ = [("type", ctypes.c_ulong), ("u", _UNION)]


def type_text(text):
    """Digita qualquer texto (com acentos) na janela que estiver em foco."""
    data = text.encode("utf-16-le")
    units = [int.from_bytes(data[i:i + 2], "little") for i in range(0, len(data), 2)]
    for u in units:
        if u == 10:  # quebra de linha vira Enter
            key_tap(0x0D)
            continue
        down = _INPUT(type=1, u=_UNION(ki=_KEYBDINPUT(0, u, 0x0004, 0, 0)))
        up = _INPUT(type=1, u=_UNION(ki=_KEYBDINPUT(0, u, 0x0004 | 0x0002, 0, 0)))
        arr = (_INPUT * 2)(down, up)
        user32.SendInput(2, arr, ctypes.sizeof(_INPUT))
        time.sleep(0.004)


# ---------------------------------------------------------------- áudio

def _com_init():
    try:
        import comtypes  # noqa
        comtypes.CoInitialize()
        return True
    except Exception:
        return False


def _com_done():
    try:
        import comtypes  # noqa
        comtypes.CoUninitialize()
    except Exception:
        pass


def _endpoint():
    from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
    dev = AudioUtilities.GetSpeakers()
    ev = getattr(dev, "EndpointVolume", None)
    if ev is not None:
        return ev
    from comtypes import CLSCTX_ALL
    from ctypes import POINTER, cast
    iface = dev.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
    return cast(iface, POINTER(IAudioEndpointVolume))


@tool("pc_volume", "Muda o volume do PC: nível exato (0 a 100), aumentar, diminuir, mudo ou tirar do mudo.",
      {"acao": S("o que fazer", ["nivel", "aumentar", "diminuir", "mudo", "tirar_mudo"]),
       "nivel": I("0 a 100, quando acao=nivel; ou quanto aumentar/diminuir (padrão 10)")},
      ["acao"])
def pc_volume(acao, nivel=None):
    com = _com_init()
    try:
        try:
            v = _endpoint()
            atual = round(v.GetMasterVolumeLevelScalar() * 100)
            if acao == "mudo":
                v.SetMute(1, None)
                return ok("PC no mudo.")
            if acao == "tirar_mudo":
                v.SetMute(0, None)
                return ok(f"Som do PC ligado ({atual}%).")
            passo = int(nivel) if nivel is not None else 10
            alvo = passo if acao == "nivel" else atual + passo if acao == "aumentar" else atual - passo
            alvo = max(0, min(100, int(alvo)))
            v.SetMute(0, None)
            v.SetMasterVolumeLevelScalar(alvo / 100.0, None)
            return ok(f"Volume do PC em {alvo}%.")
        except ImportError:
            pass  # sem pycaw: usa as teclas de volume (cada toque = 2%)
        if acao in ("mudo", "tirar_mudo"):
            key_tap(VK["mute"])
            return ok("Alternei o mudo do PC.")
        passo = int(nivel) if nivel is not None else 10
        if acao == "nivel":
            key_tap(VK["volumedown"], 50)
            key_tap(VK["volumeup"], max(0, min(100, passo)) // 2)
            return ok(f"Volume do PC em cerca de {passo}%.")
        key_tap(VK["volumeup" if acao == "aumentar" else "volumedown"], max(1, passo // 2))
        return ok("Volume ajustado.")
    finally:
        if com:
            _com_done()


@tool("pc_midia", "Controla a música/vídeo que está tocando no PC.",
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
    except Exception as e:  # monitor sem suporte
        return erro(f"Este monitor não aceita mudar o brilho por software ({e}).")
    rc, out = ps(f"(Get-WmiObject -Namespace root/WMI -Class WmiMonitorBrightnessMethods).WmiSetBrightness(1,{n})")
    return ok(f"Brilho em {n}%.") if rc == 0 else erro("Este monitor não aceita mudar o brilho por software.")


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
    user32.SendMessageTimeoutW(0xFFFF, 0x001A, 0, "ImmersiveColorSet", 0x0002, 3000, ctypes.byref(res))
    return ok("Modo escuro ligado." if ligar else "Modo claro ligado.")


@tool("pc_desligar_tela", "Desliga o monitor do PC (volta ao mexer no mouse).")
def pc_desligar_tela():
    later(1, lambda: user32.PostMessageW(0xFFFF, 0x0112, 0xF170, 2))
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


# ---------------------------------------------------------------- apps e sites

_apps_cache = {"t": 0, "apps": []}


def start_apps():
    if time.time() - _apps_cache["t"] < 600 and _apps_cache["apps"]:
        return _apps_cache["apps"]
    rc, out = ps("Get-StartApps | Select-Object Name,AppID | ConvertTo-Json -Compress")
    apps = []
    try:
        data = json.loads(out)
        if isinstance(data, dict):
            data = [data]
        apps = [(a["Name"], a["AppID"]) for a in data if a.get("Name") and a.get("AppID")]
    except Exception:
        pass
    _apps_cache.update(t=time.time(), apps=apps)
    return apps


def best_match(query, names):
    q = norm(query)
    if not q:
        return None
    scored = []
    for n in names:
        m = norm(n)
        if m == q:
            s = 100
        elif m.startswith(q):
            s = 80
        elif q in m.split():
            s = 70
        elif q in m:
            s = 50
        elif all(w in m for w in q.split()):
            s = 40
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


@tool("pc_abrir", "Abre um programa (pelo nome, ex.: Chrome, Spotify, Bloco de Notas, Steam, Configurações), "
      "um site (ex.: youtube.com) ou um arquivo/pasta no PC.",
      {"alvo": S("nome do programa, endereço do site ou caminho")}, ["alvo"])
def pc_abrir(alvo):
    alvo = alvo.strip()
    if parece_site(alvo):
        url = alvo if alvo.lower().startswith(("http://", "https://")) else "https://" + alvo
        webbrowser.open(url)
        return ok(f"Abri {url} no navegador.")
    apps = start_apps()
    nome = best_match(alvo, [a[0] for a in apps])
    if nome:
        appid = dict(apps)[nome]
        subprocess.Popen(["explorer.exe", f"shell:AppsFolder\\{appid}"])
        return ok(f"Abri {nome}.")
    seguro, motivo = seguro_para_abrir(alvo)
    if not seguro:
        return erro(f"Por segurança não abro isso ({motivo}). Peça um programa pelo nome ou um site.")
    if not Path(alvo).exists():
        return erro(f"Não achei \"{alvo}\" no PC.")
    try:
        os.startfile(alvo)
        return ok(f"Abri {alvo}.")
    except OSError:
        parecidos = [a[0] for a in apps if norm(alvo)[:3] in norm(a[0])][:8]
        return erro(f"Não achei \"{alvo}\" no PC." + (f" Parecidos: {', '.join(parecidos)}." if parecidos else ""))


@tool("pc_pesquisar", "Pesquisa algo no Google ou no YouTube, no navegador do PC.",
      {"busca": S("o que pesquisar"), "onde": S("site", ["google", "youtube"])}, ["busca"])
def pc_pesquisar(busca, onde="google"):
    q = urllib.parse.quote_plus(busca)
    url = f"https://www.youtube.com/results?search_query={q}" if onde == "youtube" else f"https://www.google.com/search?q={q}"
    webbrowser.open(url)
    return ok(f"Pesquisei \"{busca}\" no {onde}.")


def processos():
    r = subprocess.run(["tasklist", "/FO", "CSV", "/NH"], capture_output=True, text=True,
                       creationflags=CREATE_NO_WINDOW, errors="replace")
    nomes = set()
    for linha in r.stdout.splitlines():
        partes = [p.strip('"') for p in linha.split('","')]
        if partes and partes[0].lower().endswith(".exe"):
            nomes.add(partes[0])
    return sorted(nomes)


@tool("pc_fechar", "Fecha um programa aberto no PC (pelo nome).", {"app": S("nome do programa")}, ["app"],
      confirm="Vou fechar {app} no PC. Se tiver algo sem salvar, pode perder. Confirma?")
def pc_fechar(app):
    procs = processos()
    alvo = best_match(app, [p[:-4] for p in procs])
    if not alvo:
        return erro(f"Não achei \"{app}\" aberto no PC.")
    subprocess.run(["taskkill", "/IM", alvo + ".exe"], capture_output=True, creationflags=CREATE_NO_WINDOW)
    return ok(f"Pedi para fechar {alvo}.")


# ---------------------------------------------------------------- teclado e avisos

@tool("pc_digitar", "Digita um texto na janela que estiver em foco no PC.",
      {"texto": S("texto a digitar"), "enter": B("apertar Enter no final")}, ["texto"])
def pc_digitar(texto, enter=False):
    type_text(texto)
    if enter:
        key_tap(0x0D)
    return ok("Digitado.")


@tool("pc_atalho", "Aperta um atalho de teclado no PC, ex.: ctrl+c, alt+tab, win+d, alt+f4, ctrl+shift+esc.",
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
    threading.Thread(target=lambda: user32.MessageBoxW(0, texto, "Navi", 0x40 | 0x1000 | 0x10000), daemon=True).start()
    return ok("Mensagem mostrada no PC.")


# ---------------------------------------------------------------- sistema

@tool("pc_bloquear", "Bloqueia o PC (tela de senha).")
def pc_bloquear():
    user32.LockWorkStation()
    return ok("PC bloqueado.")


@tool("pc_energia", "Desliga, reinicia, suspende ou hiberna o PC, ou cancela um desligamento agendado.",
      {"acao": S("ação", ["desligar", "reiniciar", "suspender", "hibernar", "cancelar"]),
       "minutos": I("daqui a quantos minutos (padrão 0)")},
      ["acao"], confirm="Vou {acao} o PC. Confirma?", confirm_always=True)
def pc_energia(acao, minutos=0):
    seg = max(0, int(minutos or 0)) * 60
    if acao == "cancelar":
        subprocess.run(["shutdown", "/a"], capture_output=True, creationflags=CREATE_NO_WINDOW)
        return ok("Desligamento cancelado.")
    if acao in ("desligar", "reiniciar"):
        flag = "/s" if acao == "desligar" else "/r"
        subprocess.run(["shutdown", flag, "/t", str(max(seg, 5))], capture_output=True, creationflags=CREATE_NO_WINDOW)
        quando = f"em {minutos} minutos" if seg else "em 5 segundos"
        return ok(f"O PC vai {acao} {quando}.")
    if acao == "hibernar":
        later(max(seg, 2), lambda: subprocess.run(["shutdown", "/h"], creationflags=CREATE_NO_WINDOW))
        return ok("Hibernando o PC.")
    later(max(seg, 2), lambda: ctypes.windll.powrprof.SetSuspendState(0, 1, 0))
    return ok("Suspendendo o PC.")


class _POWER(ctypes.Structure):
    _fields_ = [("ACLineStatus", ctypes.c_byte), ("BatteryFlag", ctypes.c_byte), ("BatteryLifePercent", ctypes.c_byte),
                ("SystemStatusFlag", ctypes.c_byte), ("BatteryLifeTime", ctypes.c_ulong),
                ("BatteryFullLifeTime", ctypes.c_ulong)]


@tool("pc_status", "Mostra como está o PC: uso de CPU e memória, bateria, tempo ligado e programas que mais gastam.")
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
        rc, out = ps("$o=Get-CimInstance Win32_OperatingSystem; $c=(Get-CimInstance Win32_Processor | "
                     "Measure-Object LoadPercentage -Average).Average; "
                     "'{0};{1};{2}' -f $c,$o.FreePhysicalMemory,$o.TotalVisibleMemorySize")
        try:
            c, livre, total = out.split(";")
            info["cpu_porcentagem"] = float(c or 0)
            info["memoria_porcentagem"] = round(100 - float(livre) * 100 / float(total), 1)
        except Exception:
            pass
    p = _POWER()
    if ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(p)) and (p.BatteryLifePercent & 0xFF) != 255:
        info["bateria_porcentagem"] = p.BatteryLifePercent & 0xFF
        info["na_tomada"] = p.ACLineStatus == 1
    ms = ctypes.windll.kernel32.GetTickCount64
    ms.restype = ctypes.c_ulonglong
    info["ligado_ha_horas"] = round(ms() / 3_600_000, 1)
    return {"status": "ok", **info}


@tool("pc_powershell", "Roda um comando do PowerShell no PC e devolve a saída. Use só quando nenhuma outra ação servir "
      "(ex.: ver IP, listar arquivos, mudar uma configuração específica do Windows).",
      {"comando": S("comando do PowerShell")}, ["comando"],
      confirm="Vou rodar no PowerShell do PC: {comando}. Confirma?", confirm_always=True)
def pc_powershell(comando):
    if not SETTINGS.get("allow_powershell", True):
        return erro("Comandos de PowerShell estão desativados no config.json do navi-pc.")
    try:
        rc, out = ps(comando, timeout=60)
    except subprocess.TimeoutExpired:
        return erro("O comando demorou mais de 60 segundos e foi interrompido.")
    return {"status": "ok" if rc == 0 else "erro", "codigo": rc, "saida": out[-3000:] or "(sem saída)"}


def run(name, args):
    t = TOOLS.get(name)
    if not t:
        return erro(f"Ação desconhecida: {name}")
    if not IS_WIN:
        return erro("O navi-pc só funciona no Windows.")
    try:
        return t["fn"](**(args or {}))
    except TypeError as e:
        return erro(f"Parâmetros errados para {name}: {e}")
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
