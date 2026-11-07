"""Controle tela por tela (monitor 1, monitor 2, TV...): ligar, desligar, estender, duplicar e principal.

Usa a mesma API das Configurações de Tela do Windows (QueryDisplayConfig/SetDisplayConfig).
O Win+P só sabe lidar com duas telas; aqui dá para mandar, por exemplo, "duplicar a 1 com a 3".
"""

import ctypes
import json
import os
import re
import sys
from pathlib import Path

IS_WIN = sys.platform == "win32"

QDC_ALL_PATHS = 0x1
PATH_ACTIVE = 0x1
INVALID = 0xFFFFFFFF
SDC_APPLY, SDC_SUPPLIED, SDC_SAVE, SDC_ALLOW = 0x80, 0x20, 0x200, 0x400
MODE_SOURCE = 1
ERROS = {87: "parâmetro inválido", 31: "a placa de vídeo recusou", 1610: "configuração impossível",
         5: "sem permissão", 50: "não suportado por esta placa"}

if IS_WIN:
    from ctypes import wintypes

    class LUID(ctypes.Structure):
        _fields_ = [("LowPart", wintypes.DWORD), ("HighPart", wintypes.LONG)]

    class RATIONAL(ctypes.Structure):
        _fields_ = [("Numerator", ctypes.c_uint32), ("Denominator", ctypes.c_uint32)]

    class PATH_SOURCE(ctypes.Structure):
        _fields_ = [("adapterId", LUID), ("id", ctypes.c_uint32), ("modeInfoIdx", ctypes.c_uint32),
                    ("statusFlags", ctypes.c_uint32)]

    class PATH_TARGET(ctypes.Structure):
        _fields_ = [("adapterId", LUID), ("id", ctypes.c_uint32), ("modeInfoIdx", ctypes.c_uint32),
                    ("outputTechnology", ctypes.c_uint32), ("rotation", ctypes.c_uint32),
                    ("scaling", ctypes.c_uint32), ("refreshRate", RATIONAL), ("scanLineOrdering", ctypes.c_uint32),
                    ("targetAvailable", wintypes.BOOL), ("statusFlags", ctypes.c_uint32)]

    class PATH_INFO(ctypes.Structure):
        _fields_ = [("sourceInfo", PATH_SOURCE), ("targetInfo", PATH_TARGET), ("flags", ctypes.c_uint32)]

    class SOURCE_MODE(ctypes.Structure):
        _fields_ = [("width", ctypes.c_uint32), ("height", ctypes.c_uint32), ("pixelFormat", ctypes.c_uint32),
                    ("x", ctypes.c_long), ("y", ctypes.c_long)]

    class MODE_INFO(ctypes.Structure):
        _fields_ = [("infoType", ctypes.c_uint32), ("id", ctypes.c_uint32), ("adapterId", LUID),
                    ("data", ctypes.c_ubyte * 48)]

    class HEADER(ctypes.Structure):
        _fields_ = [("type", ctypes.c_uint32), ("size", ctypes.c_uint32), ("adapterId", LUID), ("id", ctypes.c_uint32)]

    class TARGET_NAME(ctypes.Structure):
        _fields_ = [("header", HEADER), ("flags", ctypes.c_uint32), ("outputTechnology", ctypes.c_uint32),
                    ("edidManufactureId", ctypes.c_uint16), ("edidProductCodeId", ctypes.c_uint16),
                    ("connectorInstance", ctypes.c_uint32), ("friendlyName", wintypes.WCHAR * 64),
                    ("devicePath", wintypes.WCHAR * 128)]

    class SOURCE_NAME(ctypes.Structure):
        _fields_ = [("header", HEADER), ("gdiName", wintypes.WCHAR * 32)]

    _u = ctypes.WinDLL("user32")
    _u.GetDisplayConfigBufferSizes.argtypes = [ctypes.c_uint32, ctypes.POINTER(ctypes.c_uint32),
                                               ctypes.POINTER(ctypes.c_uint32)]
    _u.GetDisplayConfigBufferSizes.restype = ctypes.c_long
    _u.QueryDisplayConfig.argtypes = [ctypes.c_uint32, ctypes.POINTER(ctypes.c_uint32), ctypes.POINTER(PATH_INFO),
                                      ctypes.POINTER(ctypes.c_uint32), ctypes.POINTER(MODE_INFO), ctypes.c_void_p]
    _u.QueryDisplayConfig.restype = ctypes.c_long
    _u.SetDisplayConfig.argtypes = [ctypes.c_uint32, ctypes.POINTER(PATH_INFO), ctypes.c_uint32,
                                    ctypes.POINTER(MODE_INFO), ctypes.c_uint32]
    _u.SetDisplayConfig.restype = ctypes.c_long
    _u.DisplayConfigGetDeviceInfo.argtypes = [ctypes.c_void_p]
    _u.DisplayConfigGetDeviceInfo.restype = ctypes.c_long

TAMANHOS = {"PATH_INFO": 72, "MODE_INFO": 64, "TARGET_NAME": 420, "SOURCE_NAME": 84}


def tamanhos_ok():
    return all(ctypes.sizeof(globals()[n]) == t for n, t in TAMANHOS.items())


def _consultar():
    np_, nm = ctypes.c_uint32(), ctypes.c_uint32()
    for _ in range(3):  # a configuração pode mudar entre as duas chamadas
        r = _u.GetDisplayConfigBufferSizes(QDC_ALL_PATHS, ctypes.byref(np_), ctypes.byref(nm))
        if r != 0:
            raise OSError(f"Não consegui ler as telas (código {r}).")
        paths = (PATH_INFO * np_.value)()
        modes = (MODE_INFO * nm.value)()
        r = _u.QueryDisplayConfig(QDC_ALL_PATHS, ctypes.byref(np_), paths, ctypes.byref(nm), modes, None)
        if r == 122:  # buffer pequeno: tenta de novo
            continue
        if r != 0:
            raise OSError(f"Não consegui ler as telas (código {r}).")
        return list(paths[:np_.value]), list(modes[:nm.value])
    raise OSError("As telas mudaram enquanto eu lia. Tente de novo.")


def _chave(target):
    return (target.adapterId.LowPart, target.adapterId.HighPart, target.id)


def _nome_alvo(target):
    n = TARGET_NAME()
    n.header.type, n.header.size = 2, ctypes.sizeof(TARGET_NAME)
    n.header.adapterId, n.header.id = target.adapterId, target.id
    if _u.DisplayConfigGetDeviceInfo(ctypes.byref(n)) == 0:
        return n.friendlyName or "Tela", n.outputTechnology
    return "Tela", target.outputTechnology


def _numero_fonte(source):
    n = SOURCE_NAME()
    n.header.type, n.header.size = 1, ctypes.sizeof(SOURCE_NAME)
    n.header.adapterId, n.header.id = source.adapterId, source.id
    if _u.DisplayConfigGetDeviceInfo(ctypes.byref(n)) == 0:
        m = re.search(r"DISPLAY(\d+)", n.gdiName)
        if m:
            return int(m.group(1))
    return None


# Lembra o número de cada tela mesmo depois de desligada (para "liga a tela 3" funcionar).
_MEMORIA = Path(os.environ.get("APPDATA", str(Path.home()))) / "NaviPC" / "telas.json"


def _memoria():
    try:
        return json.loads(_MEMORIA.read_text(encoding="utf-8"))
    except Exception:
        return {}


def _salvar_memoria(m):
    try:
        _MEMORIA.parent.mkdir(parents=True, exist_ok=True)
        _MEMORIA.write_text(json.dumps(m), encoding="utf-8")
    except Exception:
        pass


def listar():
    """[{numero, nome, ativa, principal, duplicada_com, chave}] de todas as telas ligadas no cabo."""
    paths, modes = _consultar()
    ativos = [p for p in paths if p.flags & PATH_ACTIVE]
    mem = _memoria()
    telas = {}
    for p in paths:
        t = p.targetInfo
        if not t.targetAvailable:
            continue
        k = _chave(t)
        if k in telas:
            continue
        nome, tecnologia = _nome_alvo(t)
        telas[k] = {"chave": k, "nome": nome, "tv": tecnologia in (5, 6, 10) or "tv" in nome.lower()
                    or "samsung" in nome.lower(), "ativa": False, "principal": False, "numero": None,
                    "fonte": None}
    for p in ativos:
        k = _chave(p.targetInfo)
        if k not in telas:
            continue
        tela = telas[k]
        tela["ativa"] = True
        tela["fonte"] = (p.sourceInfo.adapterId.LowPart, p.sourceInfo.adapterId.HighPart, p.sourceInfo.id)
        tela["numero"] = _numero_fonte(p.sourceInfo)
        i = p.sourceInfo.modeInfoIdx
        if i != INVALID and i < len(modes) and modes[i].infoType == MODE_SOURCE:
            sm = SOURCE_MODE.from_buffer_copy(bytes(modes[i].data)[:ctypes.sizeof(SOURCE_MODE)])
            tela["principal"] = sm.x == 0 and sm.y == 0
    for tela in telas.values():
        ident = f"{tela['chave'][0]}:{tela['chave'][1]}:{tela['chave'][2]}"
        if tela["numero"]:
            mem[ident] = tela["numero"]
        elif ident in mem:
            tela["numero"] = mem[ident]
    usados = {t["numero"] for t in telas.values() if t["numero"]}
    proximo = 1
    for tela in telas.values():
        if not tela["numero"]:
            while proximo in usados:
                proximo += 1
            tela["numero"] = proximo
            usados.add(proximo)
            mem[f"{tela['chave'][0]}:{tela['chave'][1]}:{tela['chave'][2]}"] = proximo
    _salvar_memoria(mem)
    por_fonte = {}
    for tela in telas.values():
        if tela["fonte"]:
            por_fonte.setdefault(tela["fonte"], []).append(tela["numero"])
    for tela in telas.values():
        irmas = [n for n in por_fonte.get(tela["fonte"], []) if n != tela["numero"]] if tela["fonte"] else []
        tela["duplicada_com"] = irmas
    return sorted(telas.values(), key=lambda t: t["numero"])


def achar(pedido, lista=None):
    """Tela pelo número ("3", "tela 3") ou pelo nome ("TV", "LG")."""
    lista = lista if lista is not None else listar()
    p = str(pedido).strip().lower()
    m = re.search(r"\d+", p)
    if m:
        n = int(m.group())
        for t in lista:
            if t["numero"] == n:
                return t
    if any(w in p for w in ("tv", "televis")):
        for t in lista:
            if t["tv"]:
                return t
    for t in lista:
        if p and (p in t["nome"].lower() or t["nome"].lower() in p):
            return t
    if "principal" in p:
        for t in lista:
            if t["principal"]:
                return t
    return None


def _aplicar(caminhos, modes):
    arr = (PATH_INFO * len(caminhos))(*caminhos)
    marr = (MODE_INFO * len(modes))(*modes) if modes else None
    r = _u.SetDisplayConfig(len(caminhos), arr, len(modes), marr, SDC_APPLY | SDC_SUPPLIED | SDC_SAVE | SDC_ALLOW)
    if r != 0:
        raise OSError("O Windows recusou: " + ERROS.get(r, f"código {r}"))


def _copiar(p):
    novo = PATH_INFO()
    ctypes.memmove(ctypes.byref(novo), ctypes.byref(p), ctypes.sizeof(PATH_INFO))
    return novo


def _caminho_livre(paths, ativos, tela):
    """Um caminho ainda não usado (fonte livre) para ligar a tela como área estendida."""
    usadas = {(a.sourceInfo.adapterId.LowPart, a.sourceInfo.adapterId.HighPart, a.sourceInfo.id) for a in ativos}
    for p in paths:
        if _chave(p.targetInfo) != tela["chave"]:
            continue
        fonte = (p.sourceInfo.adapterId.LowPart, p.sourceInfo.adapterId.HighPart, p.sourceInfo.id)
        if fonte in usadas:
            continue
        novo = _copiar(p)
        novo.flags = PATH_ACTIVE
        novo.sourceInfo.modeInfoIdx = INVALID
        novo.targetInfo.modeInfoIdx = INVALID
        return novo
    return None


def estender(pedido):
    """Liga a tela (ou separa de uma duplicação) como área estendida."""
    lista = listar()
    tela = achar(pedido, lista)
    if not tela:
        raise ValueError(f"Não achei a tela \"{pedido}\". Telas: " + ", ".join(f"{t['numero']} ({t['nome']})" for t in lista))
    paths, modes = _consultar()
    ativos = [_copiar(p) for p in paths if p.flags & PATH_ACTIVE and _chave(p.targetInfo) != tela["chave"]]
    novo = _caminho_livre(paths, ativos, tela)
    if novo is None:
        raise OSError("A placa de vídeo não tem saída livre para estender mais uma tela.")
    _aplicar(ativos + [novo], modes)
    return tela


def desligar(pedido):
    lista = listar()
    tela = achar(pedido, lista)
    if not tela:
        raise ValueError(f"Não achei a tela \"{pedido}\".")
    paths, modes = _consultar()
    ativos = [_copiar(p) for p in paths if p.flags & PATH_ACTIVE and _chave(p.targetInfo) != tela["chave"]]
    if not ativos:
        raise ValueError("Essa é a única tela ligada; não dá para desligar.")
    _aplicar(ativos, modes)
    return tela


def duplicar(pedido_a, pedido_b):
    """A tela B passa a mostrar o mesmo que a tela A (que precisa estar ligada)."""
    lista = listar()
    a, b = achar(pedido_a, lista), achar(pedido_b, lista)
    if not a or not b:
        raise ValueError("Não achei uma das telas. Telas: " + ", ".join(f"{t['numero']} ({t['nome']})" for t in lista))
    if a["chave"] == b["chave"]:
        raise ValueError("São a mesma tela.")
    if not a["ativa"]:
        estender(pedido_a)
        lista = listar()
        a = achar(pedido_a, lista)
    paths, modes = _consultar()
    fonte_a = next(p for p in paths if p.flags & PATH_ACTIVE and _chave(p.targetInfo) == a["chave"])
    alvo = None
    for p in paths:
        if (_chave(p.targetInfo) == b["chave"] and p.sourceInfo.id == fonte_a.sourceInfo.id
                and p.sourceInfo.adapterId.LowPart == fonte_a.sourceInfo.adapterId.LowPart
                and p.sourceInfo.adapterId.HighPart == fonte_a.sourceInfo.adapterId.HighPart):
            alvo = _copiar(p)
            break
    if alvo is None:
        raise OSError("Essas duas telas estão em saídas que não podem duplicar entre si (placas diferentes).")
    alvo.flags = PATH_ACTIVE
    alvo.sourceInfo.modeInfoIdx = fonte_a.sourceInfo.modeInfoIdx
    alvo.targetInfo.modeInfoIdx = INVALID
    ativos = [_copiar(p) for p in paths if p.flags & PATH_ACTIVE and _chave(p.targetInfo) != b["chave"]]
    _aplicar(ativos + [alvo], modes)
    return a, b


def principal(pedido):
    """Torna a tela a principal (onde ficam a barra de tarefas e os ícones)."""
    lista = listar()
    tela = achar(pedido, lista)
    if not tela or not tela["ativa"]:
        raise ValueError("Essa tela precisa estar ligada para virar a principal.")
    paths, modes = _consultar()
    caminho = next(p for p in paths if p.flags & PATH_ACTIVE and _chave(p.targetInfo) == tela["chave"])
    i = caminho.sourceInfo.modeInfoIdx
    if i == INVALID:
        raise OSError("Não consegui ler a posição dessa tela.")
    base = SOURCE_MODE.from_buffer_copy(bytes(modes[i].data)[:ctypes.sizeof(SOURCE_MODE)])
    dx, dy = base.x, base.y
    novos = []
    for m in modes:
        m2 = MODE_INFO()
        ctypes.memmove(ctypes.byref(m2), ctypes.byref(m), ctypes.sizeof(MODE_INFO))
        if m2.infoType == MODE_SOURCE:
            sm = SOURCE_MODE.from_buffer(m2.data)
            sm.x -= dx
            sm.y -= dy
        novos.append(m2)
    ativos = [_copiar(p) for p in paths if p.flags & PATH_ACTIVE]
    _aplicar(ativos, novos)
    return tela
