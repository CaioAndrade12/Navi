"""Jogos instalados na Steam e na Epic Games, para a Navi abrir pelo nome."""

import json
import os
import re
import sys
from pathlib import Path

IS_WIN = sys.platform == "win32"

# Redistribuíveis e ferramentas da Steam que não são jogos.
IGNORAR_STEAM = {"228980", "1070560", "1391110", "1628350", "1493710", "2180100", "250820", "1826330"}


def _pasta_steam():
    if not IS_WIN:
        return None
    import winreg
    locais = [
        (winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam", "SteamPath"),
        (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\Valve\Steam", "InstallPath"),
        (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Valve\Steam", "InstallPath"),
    ]
    for raiz, chave, valor in locais:
        try:
            with winreg.OpenKey(raiz, chave) as k:
                p = Path(winreg.QueryValueEx(k, valor)[0])
                if p.exists():
                    return p
        except OSError:
            continue
    return None


def steam():
    """Lista (nome, endereço steam://) dos jogos instalados em todas as bibliotecas da Steam."""
    base = _pasta_steam()
    if not base:
        return []
    bibliotecas = {base}
    vdf = base / "steamapps" / "libraryfolders.vdf"
    try:
        texto = vdf.read_text(encoding="utf-8", errors="replace")
        for m in re.finditer(r'"path"\s+"([^"]+)"', texto):
            bibliotecas.add(Path(m.group(1).replace("\\\\", "\\")))
    except OSError:
        pass
    jogos = []
    for lib in bibliotecas:
        for acf in (lib / "steamapps").glob("appmanifest_*.acf"):
            try:
                txt = acf.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            appid = re.search(r'"appid"\s+"(\d+)"', txt)
            nome = re.search(r'"name"\s+"([^"]+)"', txt)
            if not appid or not nome or appid.group(1) in IGNORAR_STEAM:
                continue
            n = nome.group(1)
            if any(p in n.lower() for p in ("redistributable", "proton", "steam linux runtime", "steamvr")):
                continue
            jogos.append((n, "steam://rungameid/" + appid.group(1)))
    return jogos


def epic():
    """Lista (nome, endereço da Epic) dos jogos instalados pela Epic Games."""
    pasta = Path(os.environ.get("PROGRAMDATA", r"C:\ProgramData")) / "Epic" / "EpicGamesLauncher" / "Data" / "Manifests"
    jogos = []
    if not pasta.exists():
        return jogos
    for item in pasta.glob("*.item"):
        try:
            d = json.loads(item.read_text(encoding="utf-8", errors="replace"))
        except Exception:
            continue
        nome, app = d.get("DisplayName"), d.get("AppName")
        if not nome or not app or str(app).startswith("UE_"):
            continue
        ns, cid = d.get("CatalogNamespace"), d.get("CatalogItemId")
        if ns and cid:
            uri = f"com.epicgames.launcher://apps/{ns}%3A{cid}%3A{app}?action=launch&silent=true"
        else:
            uri = f"com.epicgames.launcher://apps/{app}?action=launch&silent=true"
        jogos.append((nome, uri))
    return jogos


def todos():
    """Lista (nome, endereço, loja)."""
    out = []
    for loja, fonte in (("Steam", steam), ("Epic Games", epic)):
        try:
            out += [(n, u, loja) for n, u in fonte()]
        except Exception:
            pass
    return out


# ---------------------------------------------------------------- achar pelo nome falado

ROMANOS = {"ii": "2", "iii": "3", "iv": "4", "v": "5", "vi": "6", "vii": "7", "viii": "8", "ix": "9", "x": "10",
           "xi": "11", "xii": "12", "xiii": "13", "xiv": "14", "xv": "15", "xvi": "16"}
NUMEROS = {"um": "1", "dois": "2", "tres": "3", "quatro": "4", "cinco": "5", "seis": "6", "sete": "7", "oito": "8",
           "nove": "9", "dez": "10", "one": "1", "two": "2", "three": "3", "four": "4", "five": "5"}
# Como as pessoas chamam os jogos (e alguns programas) no dia a dia.
APELIDOS = {
    "gta": "grand theft auto", "cs": "counter strike", "csgo": "counter strike global offensive",
    "cod": "call of duty", "rdr": "red dead redemption", "lol": "league of legends", "r6": "rainbow six siege",
    "re": "resident evil", "ac": "assassins creed", "bf": "battlefield", "tf2": "team fortress 2",
    "dbd": "dead by daylight", "ets2": "euro truck simulator 2", "ets": "euro truck simulator",
    "ats": "american truck simulator", "mk": "mortal kombat", "nfs": "need for speed", "gow": "god of war",
    "fh": "forza horizon", "poe": "path of exile", "wow": "world of warcraft", "rl": "rocket league",
    "bg3": "baldurs gate 3", "cp2077": "cyberpunk 2077", "rdr2": "red dead redemption 2", "fifa": "ea sports fc",
    "pes": "efootball", "mw": "modern warfare", "bo": "black ops", "hk": "hollow knight", "pubg": "pubg battlegrounds",
    "dota": "dota 2", "tlou": "the last of us", "fnaf": "five nights at freddys", "mhw": "monster hunter world",
    "sf6": "street fighter 6", "lethal": "lethal company", "vs code": "visual studio code", "vscode": "visual studio code",
    "zap": "whatsapp", "insta": "instagram", "face": "facebook", "obs": "obs studio",
}
BASICAS = {"o", "a", "os", "as", "abre", "abrir", "abra", "meu", "minha", "no", "na", "pc", "computador", "pra",
           "pro", "para", "por", "favor", "ai", "aí"}
PALAVRAS_VAZIAS = BASICAS | {"jogo", "game", "app", "aplicativo", "programa", "de", "do", "da", "the"}
LOJA = re.compile(r"\b(na|da|pela|pelo|no|do) (steam|epic( games)?|xbox|loja)\b")


def _norm(s):
    import unicodedata
    s = re.sub(r"['’]s\b", "s", str(s).lower())  # "Baldur's Gate" -> "baldurs gate"
    s = unicodedata.normalize("NFD", s)
    s = "".join(ch for ch in s if unicodedata.category(ch) != "Mn")
    s = re.sub(r"(\d)([a-z])|([a-z])(\d)", lambda m: " ".join(x for x in m.groups() if x), s)  # "gta5" -> "gta 5"
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]", " ", s)).strip()


def _numeros(palavras, primeira_tambem=False):
    out = []
    for i, w in enumerate(palavras):
        if w in ROMANOS and (i > 0 or primeira_tambem):
            w = ROMANOS[w]
        out.append(NUMEROS.get(w, w))
    return out


def _variantes_nome(nome):
    """Formas de falar o nome de um jogo: 'Grand Theft Auto V' -> 'grand theft auto 5', 'gta 5', 'gta5'..."""
    n = _norm(nome)
    sem_sub = _norm(re.split(r":|\s[-–—]\s|\s\(|®|™", nome)[0])  # "Halo: The Master Chief Collection" -> "halo"
    v = {n, sem_sub}
    for base in list(v):
        palavras = _numeros(base.split())
        v.add(" ".join(palavras))
        letras = [w for w in palavras if not w.isdigit() and w not in ("the", "of", "de", "do", "da", "and")]
        nums = [w for w in palavras if w.isdigit()]
        if len(letras) >= 2:
            sigla = "".join(w[0] for w in letras)
            v.add(" ".join([sigla] + nums))
    return {x for x in v if x}


_apelidos_cache = []


def _apelidos():
    if not _apelidos_cache:  # chaves normalizadas ("r6" vira "r 6"), as mais longas primeiro
        _apelidos_cache.extend(sorted(((_norm(k), _norm(v)) for k, v in APELIDOS.items()),
                                      key=lambda kv: -len(kv[0])))
    return _apelidos_cache


def _variantes_pedido(pedido):
    texto = LOJA.sub(" ", _norm(pedido))
    palavras = texto.split()
    q = ([w for w in palavras if w not in PALAVRAS_VAZIAS] or [w for w in palavras if w not in BASICAS]
         or palavras)
    q = " ".join(_numeros(q))
    v = {q}
    expandido = " " + q + " "
    for k, val in _apelidos():
        if (" " + k + " ") in expandido:
            expandido = expandido.replace(" " + k + " ", " " + val + " ")
    v.add(" ".join(_numeros(expandido.split())))
    return {x for x in v if x}


def pontuar(pedido, nome):
    """De 0 a 100: o quanto `nome` parece ser o que a pessoa pediu."""
    import difflib
    melhor = 0
    for a in _variantes_pedido(pedido):
        ca = a.replace(" ", "")
        for b in _variantes_nome(nome):
            cb = b.replace(" ", "")
            if a == b:
                s = 100
            elif ca == cb:
                s = 95
            elif b.startswith(a + " "):
                s = 82
            elif len(a) >= 3 and (" " + a + " ") in (" " + b + " "):
                s = 75
            elif len(ca) >= 4 and ca in cb:
                s = 68
            elif set(a.split()) <= set(b.split()):
                s = 65
            elif len(cb) >= 4 and (" " + b + " ") in (" " + a + " "):
                s = 60  # "abre o spotify agora" contém "spotify"
            else:
                r = difflib.SequenceMatcher(None, ca, cb).ratio()
                s = int(r * 60) if r >= 0.8 else 0
            melhor = max(melhor, s)
            if melhor == 100:
                return 100
    return melhor


def melhor(pedido, nomes, minimo=45):
    """(pontos, nome) do mais parecido, ou (0, None)."""
    resultado = (0, None)
    for n in nomes:
        s = pontuar(pedido, n)
        # empate: prefere o nome mais curto ("Steam" em vez de "Steam VR")
        if s > resultado[0] or (s == resultado[0] and resultado[1] and s and len(n) < len(resultado[1])):
            resultado = (s, n)
    return resultado if resultado[0] >= minimo else (0, None)
