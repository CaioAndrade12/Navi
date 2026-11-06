"""Resolução e taxa de atualização do monitor principal (só ctypes)."""

import ctypes
import sys

IS_WIN = sys.platform == "win32"

if IS_WIN:
    from ctypes import wintypes

    class DEVMODEW(ctypes.Structure):
        _fields_ = [
            ("dmDeviceName", wintypes.WCHAR * 32), ("dmSpecVersion", wintypes.WORD),
            ("dmDriverVersion", wintypes.WORD), ("dmSize", wintypes.WORD), ("dmDriverExtra", wintypes.WORD),
            ("dmFields", wintypes.DWORD),
            # parte "tela" da união (posição, orientação, saída fixa)
            ("dmPositionX", wintypes.LONG), ("dmPositionY", wintypes.LONG),
            ("dmDisplayOrientation", wintypes.DWORD), ("dmDisplayFixedOutput", wintypes.DWORD),
            ("dmColor", ctypes.c_short), ("dmDuplex", ctypes.c_short), ("dmYResolution", ctypes.c_short),
            ("dmTTOption", ctypes.c_short), ("dmCollate", ctypes.c_short),
            ("dmFormName", wintypes.WCHAR * 32), ("dmLogPixels", wintypes.WORD), ("dmBitsPerPel", wintypes.DWORD),
            ("dmPelsWidth", wintypes.DWORD), ("dmPelsHeight", wintypes.DWORD), ("dmDisplayFlags", wintypes.DWORD),
            ("dmDisplayFrequency", wintypes.DWORD), ("dmICMMethod", wintypes.DWORD), ("dmICMIntent", wintypes.DWORD),
            ("dmMediaType", wintypes.DWORD), ("dmDitherType", wintypes.DWORD), ("dmReserved1", wintypes.DWORD),
            ("dmReserved2", wintypes.DWORD), ("dmPanningWidth", wintypes.DWORD), ("dmPanningHeight", wintypes.DWORD),
        ]

    _user32 = ctypes.WinDLL("user32")
    _user32.EnumDisplaySettingsW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, ctypes.POINTER(DEVMODEW)]
    _user32.EnumDisplaySettingsW.restype = wintypes.BOOL
    _user32.ChangeDisplaySettingsExW.argtypes = [wintypes.LPCWSTR, ctypes.POINTER(DEVMODEW), wintypes.HWND,
                                                 wintypes.DWORD, ctypes.c_void_p]
    _user32.ChangeDisplaySettingsExW.restype = wintypes.LONG

ENUM_CURRENT_SETTINGS = 0xFFFFFFFF
DM_PELSWIDTH, DM_PELSHEIGHT, DM_DISPLAYFREQUENCY = 0x00080000, 0x00100000, 0x00400000
CDS_UPDATEREGISTRY = 0x00000001
ERROS = {-1: "o Windows precisa reiniciar para aplicar", -2: "modo não suportado", -3: "falhou",
         -4: "erro no registro", -5: "parâmetros inválidos", -6: "sem permissão"}


def _novo():
    dm = DEVMODEW()
    dm.dmSize = ctypes.sizeof(DEVMODEW)
    return dm


def atual():
    """(largura, altura, hz) da tela principal."""
    dm = _novo()
    if not _user32.EnumDisplaySettingsW(None, ENUM_CURRENT_SETTINGS, ctypes.byref(dm)):
        raise OSError("Não consegui ler a tela.")
    return dm.dmPelsWidth, dm.dmPelsHeight, dm.dmDisplayFrequency


def modos():
    """Modos suportados (largura, altura, hz), do maior para o menor."""
    vistos = set()
    i = 0
    while i < 2000:
        dm = _novo()
        if not _user32.EnumDisplaySettingsW(None, i, ctypes.byref(dm)):
            break
        if dm.dmBitsPerPel >= 24 and dm.dmPelsWidth >= 640:
            vistos.add((dm.dmPelsWidth, dm.dmPelsHeight, dm.dmDisplayFrequency))
        i += 1
    return sorted(vistos, reverse=True)


def mudar(largura=None, altura=None, hz=None):
    """Muda para o modo suportado mais próximo do pedido. Devolve (largura, altura, hz)."""
    w0, h0, f0 = atual()
    w, h = (largura or w0), (altura or h0)
    candidatos = [m for m in modos() if m[0] == w and m[1] == h]
    if not candidatos:
        raise ValueError(f"A tela não aceita {w}x{h}.")
    if hz:
        escolhido = min(candidatos, key=lambda m: abs(m[2] - hz))
    else:
        mesmos = [m for m in candidatos if m[2] == f0]
        escolhido = mesmos[0] if mesmos else max(candidatos, key=lambda m: m[2])
    dm = _novo()
    _user32.EnumDisplaySettingsW(None, ENUM_CURRENT_SETTINGS, ctypes.byref(dm))
    dm.dmPelsWidth, dm.dmPelsHeight, dm.dmDisplayFrequency = escolhido
    dm.dmFields = DM_PELSWIDTH | DM_PELSHEIGHT | DM_DISPLAYFREQUENCY
    anterior = (w0, h0, f0)
    r = _user32.ChangeDisplaySettingsExW(None, ctypes.byref(dm), None, CDS_UPDATEREGISTRY, None)
    if r != 0:
        raise OSError("O Windows recusou: " + ERROS.get(r, f"código {r}"))
    if escolhido != anterior:
        _historico["anterior"] = anterior
    return escolhido


_historico = {"anterior": None}


def voltar():
    """Volta para o modo de antes da última mudança."""
    ant = _historico["anterior"]
    if not ant:
        raise ValueError("Não mudei a resolução antes, então não tenho para onde voltar.")
    return mudar(*ant)


# ---------------------------------------------------------------- várias telas (TV no HDMI, segundo monitor)

SDC_APPLY = 0x80
TOPOLOGIA = {"so_pc": 0x1, "duplicar": 0x2, "estender": 0x4, "so_segunda_tela": 0x8}

if IS_WIN:
    _user32.SetDisplayConfig.argtypes = [ctypes.c_uint32, ctypes.c_void_p, ctypes.c_uint32, ctypes.c_void_p,
                                         ctypes.c_uint32]
    _user32.SetDisplayConfig.restype = ctypes.c_long
    _user32.GetSystemMetrics.argtypes = [ctypes.c_int]
    _user32.GetSystemMetrics.restype = ctypes.c_int


def qtd_telas():
    return _user32.GetSystemMetrics(80)  # SM_CMONITORS: telas ligadas na área de trabalho


def projetar(modo):
    """Igual a Win+P: só a tela do PC, duplicar, estender ou só a segunda tela."""
    r = _user32.SetDisplayConfig(0, None, 0, None, SDC_APPLY | TOPOLOGIA[modo])
    if r != 0:
        if r in (31, 1610):  # ERROR_GEN_FAILURE, ERROR_BAD_CONFIGURATION
            raise OSError("O Windows não achou uma segunda tela ligada (TV ou monitor) para esse modo.")
        raise OSError(f"O Windows recusou o modo de projeção (código {r}).")
