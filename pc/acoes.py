"""Ações que a Navi pode fazer no Windows.

Para ensinar algo novo ao PC, crie uma função com o decorador @tool.
O celular busca esta lista sozinho (GET /tools), sem precisar recompilar o app.
"""

import base64
import ctypes
import datetime
import html as htmllib
import json
import os
import re
import subprocess
import sys
import threading
import time
import unicodedata
import urllib.parse
import urllib.request
from pathlib import Path

import janelas
import jogos
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


SINONIMOS_SOM = {
    "fone": ["fone", "headphone", "headset", "earphone", "auricular", "airpods", "buds", "hands free", "fones"],
    "headset": ["headset", "headphone", "fone", "earphone"],
    "caixa": ["alto falante", "alto falantes", "speaker", "speakers", "caixa", "realtek"],
    "alto falante": ["alto falante", "alto falantes", "speaker", "speakers", "realtek"],
    "tv": ["tv", "samsung", "lg", "philco", "tcl", "hdmi", "display audio", "nvidia high definition",
           "amd high definition", "intel r display"],
    "televisao": ["tv", "samsung", "hdmi", "display audio", "nvidia high definition", "amd high definition"],
    "monitor": ["monitor", "display audio", "hdmi", "displayport", "nvidia high definition", "amd high definition"],
    "bluetooth": ["bluetooth", "hands free", "stereo", "a2dp"],
    "webcam": ["webcam", "camera", "cam"],
    "microfone": ["microfone", "microphone", "mic"],
}


def _escolher_dispositivo(pedido, lista):
    nomes = [d["nome"] for d in lista]
    pontos, nome = jogos.melhor(pedido, nomes, minimo=60)
    if nome:
        return next(d for d in lista if d["nome"] == nome)
    p = " " + norm(pedido) + " "
    for chave, termos in SINONIMOS_SOM.items():
        if (" " + chave + " ") in p:
            for d in lista:
                n = " " + norm(d["nome"]) + " "
                if any((" " + t + " ") in n for t in termos):
                    return d
    pontos, nome = jogos.melhor(pedido, nomes, minimo=45)
    return next((d for d in lista if d["nome"] == nome), None)


@tool("pc_saida_som", "Lista ou troca por onde sai o som do PC (fone, headset, caixa de som, TV/monitor no HDMI, "
      "Bluetooth) ou qual microfone ele usa. Troca para tudo: jogos, música e chamadas.",
      {"acao": S("o que fazer", ["listar", "usar"]),
       "dispositivo": S("para usar: nome ou tipo do dispositivo (ex.: fone, caixa, TV, headset HyperX)"),
       "tipo": S("saída de som ou microfone (padrão: saída)", ["saida", "microfone"])}, ["acao"])
def pc_saida_som(acao, dispositivo=None, tipo="saida"):
    import audio_win
    fluxo = audio_win.MICROFONE if tipo == "microfone" else audio_win.SAIDA
    lista = audio_win.dispositivos(fluxo)
    oque = "microfone" if fluxo else "saída de som"
    if not lista:
        return erro(f"Não achei nenhum {oque} ligado no PC.")
    if acao == "listar" or not dispositivo:
        atual = next((d["nome"] for d in lista if d["padrao"]), None)
        return ok(f"{'Microfones' if fluxo else 'Saídas de som'}: " + "; ".join(d["nome"] for d in lista) +
                  (f". Em uso agora: {atual}." if atual else "."),
                  dispositivos=[d["nome"] for d in lista], em_uso=atual)
    d = _escolher_dispositivo(dispositivo, lista)
    if not d:
        return erro(f"Não achei {oque} parecido com \"{dispositivo}\". Opções: " + "; ".join(x["nome"] for x in lista))
    if d["padrao"]:
        return ok(f"O PC já está usando {d['nome']}.")
    audio_win.usar(d["id"])
    return ok(f"Pronto, o {'microfone' if fluxo else 'som'} do PC agora é {d['nome']}.")


def _caminho_processo(pid):
    if not pid:
        return ""
    k32 = janelas.kernel32  # já com os tipos certos (handles de 64 bits)
    h = k32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
    if not h:
        return ""
    try:
        buf = ctypes.create_unicode_buffer(1024)
        tam = ctypes.c_ulong(1024)
        return buf.value.lower() if k32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(tam)) else ""
    finally:
        k32.CloseHandle(h)


PASTAS_DE_JOGOS = ("steamapps\\common", "epic games\\", "xboxgames", "riot games", "ea games", "ubisoft game",
                   "gog galaxy\\games", "battle.net", "\\games\\")
PALAVRAS_NAVEGADOR = {"navegador", "browser", "youtube", "site", "internet", "netflix", "twitch", "video", "videos"}


def _escolhedor(app):
    """Função (exe, pid) -> bool que diz se um programa com som é o que a pessoa pediu."""
    pedido = norm(app)
    titulos = {}
    for j in janelas.listar():
        titulos.setdefault(j.pid, j.titulo)
    if pedido in ("tudo", "todos", "todos os apps", "todos os programas"):
        return lambda exe, pid: exe != "sons do sistema"
    if pedido in ("jogo", "o jogo", "jogos", "game"):
        return lambda exe, pid: any(p in _caminho_processo(pid) for p in PASTAS_DE_JOGOS)
    if set(pedido.split()) & PALAVRAS_NAVEGADOR:
        return lambda exe, pid: (exe + ".exe") in NAVEGADORES
    if pedido in ("sistema", "sons do sistema", "windows"):
        return lambda exe, pid: exe == "sons do sistema"

    def escolher(exe, pid):
        nomes = [exe] + ([titulos[pid]] if pid in titulos else [])
        return any(jogos.pontuar(app, n) >= 60 for n in nomes)
    return escolher


@tool("pc_volume_app", "Volume de cada programa no PC, como no Mixer de Volume: lista quem está com som, ou muda o "
      "volume/mudo de um programa (Spotify, Discord, Chrome, o jogo...). Use app='navegador' para YouTube/sites e "
      "app='jogo' para o jogo aberto.",
      {"acao": S("o que fazer", ["listar", "consultar", "definir", "aumentar", "diminuir", "mudo", "tirar_mudo"]),
       "app": S("programa (ex.: spotify, discord, navegador, jogo, tudo)"),
       "nivel": I("para definir: 0 a 100; para aumentar/diminuir: quantos pontos (padrão 10)")}, ["acao"])
def pc_volume_app(acao, app=None, nivel=None):
    import audio_win
    if acao == "listar" or not app:
        tudo = audio_win.apps()
        if not tudo:
            return ok("Nenhum programa está usando o som agora.", apps={})
        texto = "; ".join(f"{n}: {v['volume']}%" + (" (mudo)" if v["mudo"] else "") for n, v in tudo.items())
        return ok("Programas com som: " + texto + ".", apps={n: v["volume"] for n, v in tudo.items()})
    escolher = _escolhedor(app)
    if acao == "consultar":
        achados = {n: v for n, v in audio_win.apps().items() if escolher(n, v["pid"])}
        if not achados:
            return erro(f"\"{app}\" não está usando o som agora (o programa precisa estar aberto e com som).")
        return ok("; ".join(f"{n}: {v['volume']}%" + (" (mudo)" if v["mudo"] else "") for n, v in achados.items()))
    kwargs = {}
    if acao == "definir":
        kwargs["nivel"] = max(0, min(100, int(nivel if nivel is not None else 50)))
    elif acao in ("aumentar", "diminuir"):
        passo = 10 if nivel is None else int(nivel)
        kwargs["passo"] = passo if acao == "aumentar" else -passo
    else:
        kwargs["mudo"] = acao == "mudo"
    mudou = audio_win.app_volume(escolher, **kwargs)
    if not mudou:
        abertos = ", ".join(audio_win.apps().keys())
        return erro(f"\"{app}\" não está usando o som agora." + (f" Com som: {abertos}." if abertos else ""))
    return ok("; ".join(f"{n}: " + ("mudo" if v["mudo"] else f"{v['volume']}%") for n, v in mudou.items()) + ".",
              apps=mudou)


@tool("pc_microfone", "Consulta ou muda o microfone do PC: mudo, tirar do mudo ou volume (sensibilidade) de 0 a 100.",
      {"acao": S("o que fazer", ["consultar", "mudo", "tirar_mudo", "definir"]),
       "nivel": I("para definir: 0 a 100")}, ["acao"])
def pc_microfone(acao, nivel=None):
    import audio_win
    with audio_win.Volume(audio_win.MICROFONE) as v:
        if acao == "consultar":
            return ok(f"Microfone em {v.nivel()}%" + (", no mudo." if v.mudo() else ", ligado."),
                      volume=v.nivel(), mudo=v.mudo())
        if acao in ("mudo", "tirar_mudo"):
            v.definir_mudo(acao == "mudo")
            return ok("Microfone do PC no mudo." if acao == "mudo" else "Microfone do PC ligado.")
        v.definir(max(0, min(100, int(nivel if nivel is not None else 80))))
        return ok(f"Microfone em {v.nivel()}%.")


# ---------------------------------------------------------------- tela

def _alvo(acao, atual, nivel, passo_padrao=10):
    passo = passo_padrao if nivel is None else int(nivel)
    if acao == "definir":
        return max(0, min(100, passo))
    return max(0, min(100, atual + (passo if acao == "aumentar" else -passo)))


def _brilho_wmi():
    """Brilho da tela do notebook (WMI). None se não houver."""
    d = ps_json("Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightness | "
                "Select-Object -First 1 CurrentBrightness")
    if d and d[0].get("CurrentBrightness") is not None:
        return int(d[0]["CurrentBrightness"])
    return None


def _brilho_wmi_definir(n):
    rc, out = ps(f"(Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightnessMethods)"
                 f" | Invoke-CimMethod -MethodName WmiSetBrightness -Arguments @{{Timeout=1;Brightness={n}}}")
    return rc == 0 and "error" not in out.lower() and "erro" not in out.lower()


@tool("pc_brilho", "Consulta ou muda o brilho da tela do PC: tela do notebook ou monitor de mesa (pelo DDC/CI). "
      "Pode definir um nível exato, aumentar ou diminuir.",
      {"acao": S("o que fazer", ["consultar", "definir", "aumentar", "diminuir"]),
       "nivel": I("para definir: 0 a 100; para aumentar/diminuir: quantos pontos (padrão 10)")})
def pc_brilho(acao=None, nivel=None):
    acao = acao or ("definir" if nivel is not None else "consultar")
    import monitor
    resultados = []
    with monitor.Monitores() as ms:
        for m in ms.lista:
            atual = monitor.porcentagem(m, "brilho")
            if atual is None:
                continue
            if acao == "consultar":
                resultados.append((monitor.nome(m), atual))
                continue
            alvo = _alvo(acao, atual, nivel)
            if monitor.definir_porcentagem(m, "brilho", alvo):
                resultados.append((monitor.nome(m), alvo))
    if resultados:
        texto = ", ".join(f"{n}: {v}%" for n, v in resultados) if len(resultados) > 1 else f"{resultados[0][1]}%"
        return ok(("Brilho do monitor: " if acao == "consultar" else "Brilho do monitor em ") + texto + ".",
                  brilho=resultados[0][1])
    atual = _brilho_wmi()
    if atual is not None:
        if acao == "consultar":
            return ok(f"Brilho da tela em {atual}%.", brilho=atual)
        alvo = _alvo(acao, atual, nivel)
        if _brilho_wmi_definir(alvo):
            return ok(f"Brilho da tela em {alvo}%.", brilho=alvo)
    try:
        import screen_brightness_control as sbc
        atual = int(sbc.get_brightness(display=0)[0])
        if acao == "consultar":
            return ok(f"Brilho da tela em {atual}%.", brilho=atual)
        alvo = _alvo(acao, atual, nivel)
        sbc.set_brightness(alvo)
        return ok(f"Brilho da tela em {alvo}%.", brilho=alvo)
    except Exception:
        pass
    return erro("Este monitor não deixou mudar o brilho pelo PC. Em monitores de mesa, ligue a opção DDC/CI no menu "
                "do próprio monitor (botões dele) e tente de novo.")


@tool("pc_monitor", "Ajusta o contraste do monitor de mesa (pelo DDC/CI) ou mostra brilho, contraste e entrada atuais.",
      {"acao": S("o que fazer", ["consultar", "definir_contraste", "aumentar_contraste", "diminuir_contraste"]),
       "nivel": I("contraste de 0 a 100 (ou quantos pontos aumentar/diminuir)")}, ["acao"])
def pc_monitor(acao, nivel=None):
    import monitor
    info = []
    with monitor.Monitores() as ms:
        if not ms.lista:
            return erro("Não achei monitor que aceite ajustes pelo PC (DDC/CI). Ligue o DDC/CI no menu do monitor.")
        for m in ms.lista:
            nome = monitor.nome(m)
            contraste = monitor.porcentagem(m, "contraste")
            if acao == "consultar":
                brilho = monitor.porcentagem(m, "brilho")
                ent = monitor.Monitores.ler(m, "entrada")
                info.append({"monitor": nome, "brilho": brilho, "contraste": contraste,
                             "entrada": monitor.NOMES_ENTRADA.get(ent[0] & 0xFF, str(ent[0])) if ent else None})
                continue
            if contraste is None:
                continue
            alvo = _alvo(acao.split("_")[0], contraste, nivel)
            if monitor.definir_porcentagem(m, "contraste", alvo):
                info.append({"monitor": nome, "contraste": alvo})
    if not info:
        return erro("O monitor não respondeu. Veja se a opção DDC/CI está ligada no menu dele.")
    if acao == "consultar":
        return {"status": "ok", "monitores": info}
    return ok("Contraste em " + ", ".join(f"{i['contraste']}%" for i in info) + ".", monitores=info)


@tool("pc_monitor_entrada", "Troca a entrada de vídeo do monitor de mesa (HDMI, DisplayPort...), por exemplo para "
      "ver o videogame. A tela do PC some até voltar para a entrada dele.",
      {"entrada": S("entrada", ["hdmi", "hdmi 2", "displayport", "displayport 2", "usb c", "dvi", "vga"])},
      ["entrada"],
      confirm="Vou trocar o monitor para a entrada {entrada}. A tela do PC some até você voltar. Confirma?")
def pc_monitor_entrada(entrada):
    import monitor
    codigo = monitor.ENTRADAS.get(norm(entrada).replace("usbc", "usb c"))
    if codigo is None:
        return erro(f"Entrada desconhecida: {entrada}.")
    with monitor.Monitores() as ms:
        for m in ms.lista:
            if monitor.Monitores.definir(m, "entrada", codigo):
                return ok(f"Monitor trocado para {monitor.NOMES_ENTRADA.get(codigo, entrada)}.")
    return erro("O monitor não aceitou trocar a entrada pelo PC (precisa do DDC/CI ligado no menu dele).")


@tool("pc_tela", "Consulta ou muda a resolução e a taxa de atualização (Hz) do monitor principal do PC, lista os modos "
      "que ele aceita ou volta para o modo anterior.",
      {"acao": S("o que fazer", ["consultar", "listar", "mudar", "voltar"]),
       "resolucao": S("para mudar: largura x altura, ex.: 1920x1080 (vazio = manter)"),
       "hz": I("para mudar: taxa de atualização desejada, ex.: 144 (vazio = manter)")}, ["acao"])
def pc_tela(acao, resolucao=None, hz=None):
    import tela
    w, h, f = tela.atual()
    if acao == "consultar":
        return ok(f"A tela está em {w}x{h} a {f} Hz.", largura=w, altura=h, hz=f, telas=tela.qtd_telas())
    if acao == "listar":
        modos = tela.modos()
        res = list(dict.fromkeys(f"{a}x{b}" for a, b, _ in modos))[:12]
        taxas = sorted({c for a, b, c in modos if a == w and b == h}, reverse=True)
        return ok(f"Resoluções: {', '.join(res)}. Taxas em {w}x{h}: {', '.join(f'{t} Hz' for t in taxas)}.")
    if acao == "voltar":
        nw, nh, nf = tela.voltar()
        return ok(f"Voltei a tela para {nw}x{nh} a {nf} Hz.")
    largura = altura = None
    if resolucao:
        nums = re.findall(r"\d+", resolucao)
        atalhos = {"4k": (3840, 2160), "2k": (2560, 1440), "fullhd": (1920, 1080), "hd": (1280, 720),
                   "1440p": (2560, 1440), "1080p": (1920, 1080), "720p": (1280, 720)}
        chave = norm(resolucao).replace(" ", "")
        if chave in atalhos:
            largura, altura = atalhos[chave]
        elif len(nums) >= 2:
            largura, altura = int(nums[0]), int(nums[1])
        else:
            return erro("Diga a resolução como 1920x1080.")
    if not largura and not hz:
        return erro("Diga a resolução (ex.: 1920x1080) ou a taxa (ex.: 144 Hz).")
    try:
        nw, nh, nf = tela.mudar(largura, altura, int(hz) if hz else None)
    except ValueError as e:
        res = list(dict.fromkeys(f"{a}x{b}" for a, b, _ in tela.modos()))[:10]
        return erro(f"{e} Resoluções possíveis: {', '.join(res)}.")
    aviso = f" (o mais perto de {hz} Hz que este monitor aceita)" if hz and int(hz) != nf else ""
    return ok(f"Tela em {nw}x{nh} a {nf} Hz{aviso}. Se ficar ruim, é só pedir para voltar.")


@tool("pc_projetar", "Escolhe como usar várias telas (TV no HDMI, segundo monitor), igual ao Win+P: só a tela do PC, "
      "duplicar, estender ou só a segunda tela.",
      {"modo": S("modo", ["so_pc", "duplicar", "estender", "so_segunda_tela"])}, ["modo"])
def pc_projetar(modo):
    import tela
    tela.projetar(modo)
    nomes = {"so_pc": "só na tela do PC", "duplicar": "duplicando a tela", "estender": "estendendo a tela",
             "so_segunda_tela": "só na segunda tela"}
    return ok(f"Pronto, {nomes[modo]}.")


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
    # Atalhos de internet no menu Iniciar (a Steam cria um .url para cada jogo).
    for csidl in (0x0002, 0x0017):  # Programas do usuário e de todos
        pasta = _pasta_especial(csidl)
        if pasta and pasta.exists():
            for f in pasta.rglob("*.url"):
                lista.append((f.stem, "arquivo", str(f)))
    if lista:
        _progs.update(t=time.time(), lista=lista)
    return lista


_jogos = {"t": 0.0, "lista": []}


def lista_jogos():
    """[(nome, endereço, loja)] dos jogos da Steam e da Epic (atualiza a cada 5 minutos)."""
    if time.time() - _jogos["t"] > 300:
        _jogos.update(t=time.time(), lista=jogos.todos())
    return _jogos["lista"]


def best_match(query, names, minimo=45):
    """Nome mais parecido com o pedido (entende apelidos, siglas, números romanos e erros pequenos)."""
    return jogos.melhor(query, names, minimo)[1]


# ---------------------------------------------------------------- sites e links falados

TLDS = r"(com|net|org|br|io|gov|edu|tv|me|app|dev|gg|co|ai|info|xyz|site|online|store|tech|us|uk|pt|es|de|fr|it|jp|ly|to|fm|so|cc|link|games|live|news|blog)"


def parece_site(alvo):
    a = alvo.strip().lower()
    return a.startswith(("http://", "https://", "www.")) or bool(
        re.fullmatch(r"[a-z0-9-]+(\.[a-z0-9-]+)*\." + TLDS + r"(\.[a-z]{2})?(:\d+)?([/?#]\S*)?", a)
        or re.fullmatch(r"\d{1,3}(\.\d{1,3}){3}(:\d+)?([/?#]\S*)?", a))


def _sem_acento(s):
    s = unicodedata.normalize("NFD", s)
    return "".join(ch for ch in s if unicodedata.category(ch) != "Mn")


def link_falado(texto):
    """'g1 ponto globo ponto com barra economia' -> 'g1.globo.com/economia'. Se não parecer link, devolve o texto."""
    t = _sem_acento(texto.strip().lower())
    t = re.sub(r"^(abr[ae]\s+)?((o|a)\s+)?(site|pagina|endereco|link|url)\s+((do|da|de|o|a)\s+)?", "", t)
    original = t
    trocas = [
        (r"\s*\bponto\s+com\s+ponto\s+br\b\s*", ".com.br"), (r"\s*\bponto\b\s*", "."),
        (r"\s*\b(barra|barrinha)\b\s*", "/"), (r"\s*\bdois\s+pontos\b\s*", ":"),
        (r"\s*\b(traco|tracinho|hifen)\b\s*", "-"), (r"\s*\b(underline|underscore)\b\s*", "_"),
        (r"\bw\s+w\s+w\b", "www"), (r"\bh\s*t\s*t\s*p\s*s\b", "https"),
    ]
    for de, para in trocas:
        t = re.sub(de, para, t)
    t = re.sub(r"^(https?):?/+", r"\1://", t)
    esquema = ""
    if "://" in t:
        esquema, t = t.split("://", 1)
        esquema += "://"
    dominio, barra, caminho = t.partition("/")
    dominio = dominio.replace(" ", "")
    caminho = caminho.replace(" ", "")
    junto = esquema + dominio + (barra + caminho if barra else "")
    return junto if parece_site(junto) else original


# Sites conhecidos pelo nome falado (para não depender de busca).
SITES = {
    "youtube": "https://www.youtube.com", "netflix": "https://www.netflix.com", "gmail": "https://mail.google.com",
    "email": "https://mail.google.com", "e mail": "https://mail.google.com", "google": "https://www.google.com",
    "google drive": "https://drive.google.com", "drive": "https://drive.google.com",
    "whatsapp": "https://web.whatsapp.com", "whatsapp web": "https://web.whatsapp.com", "zap": "https://web.whatsapp.com",
    "instagram": "https://www.instagram.com", "insta": "https://www.instagram.com",
    "facebook": "https://www.facebook.com", "twitter": "https://x.com", "x": "https://x.com",
    "tiktok": "https://www.tiktok.com", "tik tok": "https://www.tiktok.com", "twitch": "https://www.twitch.tv",
    "spotify": "https://open.spotify.com", "chatgpt": "https://chatgpt.com", "chat gpt": "https://chatgpt.com",
    "gemini": "https://gemini.google.com", "claude": "https://claude.ai", "amazon": "https://www.amazon.com.br",
    "mercado livre": "https://www.mercadolivre.com.br", "shopee": "https://shopee.com.br",
    "kabum": "https://www.kabum.com.br", "magalu": "https://www.magazineluiza.com.br",
    "magazine luiza": "https://www.magazineluiza.com.br", "aliexpress": "https://pt.aliexpress.com",
    "olx": "https://www.olx.com.br", "ifood": "https://www.ifood.com.br", "prime video": "https://www.primevideo.com",
    "amazon prime": "https://www.primevideo.com", "disney": "https://www.disneyplus.com",
    "disney plus": "https://www.disneyplus.com", "max": "https://www.max.com", "hbo max": "https://www.max.com",
    "globoplay": "https://globoplay.globo.com", "crunchyroll": "https://www.crunchyroll.com",
    "github": "https://github.com", "linkedin": "https://www.linkedin.com", "reddit": "https://www.reddit.com",
    "wikipedia": "https://pt.wikipedia.org", "google maps": "https://www.google.com/maps",
    "maps": "https://www.google.com/maps", "mapa": "https://www.google.com/maps",
    "tradutor": "https://translate.google.com", "google tradutor": "https://translate.google.com",
    "outlook": "https://outlook.live.com", "hotmail": "https://outlook.live.com",
    "loja da steam": "https://store.steampowered.com", "steam store": "https://store.steampowered.com",
    "loja da epic": "https://store.epicgames.com", "discord": "https://discord.com/app",
    "g1": "https://g1.globo.com", "globo esporte": "https://ge.globo.com", "ge": "https://ge.globo.com",
    "uol": "https://www.uol.com.br", "pinterest": "https://br.pinterest.com", "canva": "https://www.canva.com",
    "google agenda": "https://calendar.google.com", "agenda": "https://calendar.google.com",
    "google fotos": "https://photos.google.com", "fotos do google": "https://photos.google.com",
    "youtube music": "https://music.youtube.com", "deezer": "https://www.deezer.com", "notion": "https://www.notion.so",
    "figma": "https://www.figma.com", "roblox": "https://www.roblox.com", "google docs": "https://docs.google.com",
    "docs": "https://docs.google.com", "planilhas": "https://sheets.google.com",
    "google planilhas": "https://sheets.google.com", "speedtest": "https://www.speedtest.net",
    "teste de velocidade": "https://www.speedtest.net", "nubank": "https://nubank.com.br",
    "duolingo": "https://www.duolingo.com", "letterboxd": "https://letterboxd.com", "steamdb": "https://steamdb.info",
    "hltv": "https://www.hltv.org", "op gg": "https://www.op.gg", "chess": "https://www.chess.com",
    "xadrez": "https://www.chess.com", "receita federal": "https://www.gov.br/receitafederal",
    "gov br": "https://www.gov.br", "detran": "https://www.gov.br/pt-br/servicos-estaduais",
    "previsao do tempo": "https://www.climatempo.com.br", "climatempo": "https://www.climatempo.com.br",
}
VAZIAS_SITE = {"o", "a", "site", "do", "da", "de", "pagina", "abre", "abrir", "abra", "no", "na", "navegador",
               "pc", "computador", "pra", "pro", "por", "favor", "oficial", "entra", "entrar", "vai", "ir"}


def _chave_site(nome):
    palavras = [w for w in norm(nome).split() if w not in VAZIAS_SITE]
    return " ".join(palavras)


SITES_CHAVE = {_chave_site(k) or k: v for k, v in SITES.items()}  # "loja da steam" -> "loja steam"


UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/124.0 Safari/537.36")


def primeiro_resultado(busca):
    """Endereço do primeiro resultado (sem anúncios) de uma busca, ou None."""
    url = "https://html.duckduckgo.com/html/?kl=br-pt&q=" + urllib.parse.quote_plus(busca)
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "pt-BR,pt;q=0.9"})
    pagina = urllib.request.urlopen(req, timeout=8).read().decode("utf-8", "replace")
    for tag in re.findall(r"<a\b[^>]*\bresult__a\b[^>]*>", pagina):
        m = re.search(r'href="([^"]+)"', tag)
        if not m:
            continue
        href = htmllib.unescape(m.group(1))
        if href.startswith("//"):
            href = "https:" + href
        partes = urllib.parse.urlparse(href)
        if "duckduckgo.com" in partes.netloc:
            if partes.path.startswith("/y.js"):
                continue  # anúncio
            href = urllib.parse.parse_qs(partes.query).get("uddg", [""])[0]
        if href.startswith(("http://", "https://")):
            return href
    return None


def _dominio(url):
    d = urllib.parse.urlparse(url).netloc.lower()
    return d[4:] if d.startswith("www.") else d


def site_por_nome(pedido):
    """(endereço, como_achou) para um site falado pelo nome ou pelo endereço."""
    link = link_falado(pedido)
    if parece_site(link):
        return (link if link.startswith(("http://", "https://")) else "https://" + link), "endereco"
    chave = _chave_site(pedido)
    if chave in SITES_CHAVE:
        return SITES_CHAVE[chave], "conhecido"
    if chave:
        pontos, nome = jogos.melhor(chave, list(SITES_CHAVE.keys()), minimo=82)
        if nome:
            return SITES_CHAVE[nome], "conhecido"
    busca = chave or pedido
    try:
        achado = primeiro_resultado(busca + " site oficial" if len(busca.split()) <= 3 else busca)
        if achado:
            return achado, "busca"
    except Exception:
        pass
    # Sem internet no PC para buscar aqui: o próprio DuckDuckGo vai direto para o 1º resultado.
    return "https://duckduckgo.com/?q=" + urllib.parse.quote_plus("\\" + busca), "direto"


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


def lancar(nome, tipo, destino, loja=None):
    """Abre e traz a janela para a frente. Devolve o texto do resultado."""
    antes = {j.hwnd for j in janelas.listar()}
    janelas.liberar_frente()
    silencio = dict(stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if tipo == "appid":
        subprocess.Popen(["explorer.exe", "shell:AppsFolder\\" + destino], **silencio)
    elif tipo == "exe":
        subprocess.Popen([destino], **silencio)
    else:  # arquivo, atalho ou endereço de jogo (steam://, com.epicgames.launcher://)
        os.startfile(destino)
    pista = nome if tipo != "exe" else os.path.splitext(destino)[0]
    if tipo == "jogo":
        # Jogos demoram: a loja abre primeiro, depois o jogo. Espera em segundo plano por até 2 minutos.
        j = janelas.esperar_nova(antes, pista=pista, segundos=4)
        if j:
            janelas.frente(j.hwnd)
            return ok(f"Abri {nome}.", janela=j.titulo)
        _continuar_esperando(antes, pista, None, 120)
        return ok(f"Abrindo {nome} pela {loja or 'loja'}. Jogos levam um tempinho para abrir.")
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


def _candidatos():
    """Tudo que dá para abrir pelo nome: [(nome, tipo, destino, loja)]."""
    lista = [(n, t, d, None) for n, t, d in programas()]
    lista += [(n, "jogo", u, loja) for n, u, loja in lista_jogos()]
    return lista


@tool("pc_abrir", "Abre no PC um programa ou jogo pelo nome (ex.: Chrome, Spotify, Discord, Steam, GTA 5, CS2, "
      "Fortnite, Bloco de Notas, Calculadora, Configurações, Downloads), um site (ex.: youtube.com, "
      "'g1 ponto globo ponto com') ou um arquivo/pasta, e traz a janela para a frente. Entende apelidos e siglas "
      "de jogos (GTA, CS, RDR2, LoL).",
      {"alvo": S("nome do programa ou jogo, endereço do site, pasta ou caminho")}, ["alvo"])
def pc_abrir(alvo):
    alvo = alvo.strip().strip(".")
    if not alvo:
        return erro("Diga o que abrir.")
    link = link_falado(alvo)
    if parece_site(link):
        url = link if link.lower().startswith(("http://", "https://")) else "https://" + link
        return abrir_site(url)
    sistema = _sistema()
    chave = norm(alvo)
    if chave in sistema:
        tipo, destino = sistema[chave]
        return lancar(alvo, tipo, destino)
    tudo = _candidatos()
    pontos, nome = jogos.melhor(alvo, [c[0] for c in tudo])
    if nome and pontos >= 60:
        _, tipo, destino, loja = next(c for c in tudo if c[0] == nome)
        return lancar(nome, tipo, destino, loja)
    chave_site = _chave_site(alvo)
    if chave_site in SITES_CHAVE:  # "abre o YouTube" sem app instalado = o site
        return abrir_site(SITES_CHAVE[chave_site])
    if nome:
        _, tipo, destino, loja = next(c for c in tudo if c[0] == nome)
        return lancar(nome, tipo, destino, loja)
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
    parecidos = [c[0] for c in tudo if q[:4] and q[:4] in norm(c[0])][:8]
    return erro(f"Não achei o programa ou jogo \"{alvo}\" no PC." +
                (f" Parecidos: {', '.join(parecidos)}." if parecidos else "") +
                " Se a pessoa queria um site, use pc_site; para ver os jogos instalados, pc_jogos.")


@tool("pc_site", "Abre um site no navegador do PC pelo nome ou pelo endereço falado: 'site da Kabum', 'Netflix', "
      "'g1 ponto globo ponto com', 'github ponto com barra fulano'. Se não conhecer o site, procura e abre o "
      "primeiro resultado (o site oficial).",
      {"site": S("nome do site ou o endereço do jeito que a pessoa falou")}, ["site"])
def pc_site(site):
    url, como = site_por_nome(site)
    r = abrir_site(url)
    if como == "direto":
        r["resultado"] = f"Procurei \"{site}\" e abri o primeiro resultado no navegador do PC."
    else:
        r["resultado"] = f"Abri {_dominio(url)} no navegador do PC."
    r["endereco"] = url
    return r


@tool("pc_jogos", "Lista os jogos instalados no PC pela Steam e pela Epic Games.")
def pc_jogos():
    lista = lista_jogos()
    if not lista:
        return ok("Não achei jogos da Steam nem da Epic neste PC. Outros jogos (Xbox, Riot, EA...) abrem pelo "
                  "nome com pc_abrir se estiverem no menu Iniciar.", jogos=[])
    nomes = sorted({f"{n} ({loja})" for n, _, loja in lista}, key=str.lower)
    return ok(f"{len(nomes)} jogos instalados.", jogos=nomes[:80])


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
    "mixer_de_volume": "ms-settings:apps-volume", "som_classico": "mmsys.cpl", "hdr": "ms-settings:display-hdr",
    "graficos": "ms-settings:display-advancedgraphics", "modo_jogo": "ms-settings:gaming-gamemode",
    "temas": "ms-settings:themes", "barra_de_tarefas": "ms-settings:taskbar", "tela_de_bloqueio": "ms-settings:lockscreen",
    "area_de_transferencia": "ms-settings:clipboard", "nao_perturbe": "ms-settings:quiethours",
    "vpn": "ms-settings:network-vpn", "dispositivos": "ms-settings:connecteddevices", "projetar": "ms-settings:project",
    "microfone": "ms-settings:privacy-microphone", "camera": "ms-settings:privacy-webcam",
    "energia_avancada": "powercfg.cpl", "rede_classica": "ncpa.cpl",
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
    if uri.endswith(".cpl"):  # telas clássicas do Painel de Controle
        subprocess.Popen(["control.exe", uri], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL)
        exes = {"rundll32.exe", "control.exe", "explorer.exe"}
    else:
        os.startfile(uri)
        exes = {"systemsettings.exe", "applicationframehost.exe"}
    j = janelas.esperar_nova(antes, exes=exes, segundos=6)
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


@tool("pc_tempo_tela", "Define em quantos minutos parado o PC desliga a tela ou entra em suspensão (0 = nunca).",
      {"minutos": I("minutos (0 = nunca)"),
       "o_que": S("o que muda (padrão: tela)", ["tela", "suspender"])}, ["minutos"])
def pc_tempo_tela(minutos, o_que="tela"):
    m = max(0, int(minutos))
    chave = "standby-timeout" if o_que == "suspender" else "monitor-timeout"
    rc, out, err = cmd(["powercfg", "/change", chave + "-ac", str(m)])
    cmd(["powercfg", "/change", chave + "-dc", str(m)])
    if rc != 0:
        return erro(f"O Windows não aceitou ({err or out}).")
    if o_que == "suspender":
        return ok("O PC não vai mais suspender sozinho." if m == 0 else f"O PC suspende depois de {m} minutos parado.")
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
