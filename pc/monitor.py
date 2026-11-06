"""Ajustes do próprio monitor pelo cabo (DDC/CI): brilho, contraste e entrada de vídeo.

Funciona na maioria dos monitores de PC de mesa (precisa da opção DDC/CI ligada no menu do monitor).
Só ctypes, sem dependências.
"""

import ctypes
import sys
import time

IS_WIN = sys.platform == "win32"

VCP = {"brilho": 0x10, "contraste": 0x12, "entrada": 0x60}
# Códigos do padrão MCCS para a entrada de vídeo.
ENTRADAS = {"vga": 0x01, "vga 2": 0x02, "dvi": 0x03, "dvi 2": 0x04, "displayport": 0x0F, "displayport 2": 0x10,
            "hdmi": 0x11, "hdmi 2": 0x12, "usb c": 0x1B}
NOMES_ENTRADA = {v: k.upper().replace("DISPLAYPORT", "DisplayPort").replace("USB C", "USB-C")
                 for k, v in ENTRADAS.items()}

if IS_WIN:
    from ctypes import wintypes

    class PHYSICAL_MONITOR(ctypes.Structure):
        _fields_ = [("handle", wintypes.HANDLE), ("descricao", wintypes.WCHAR * 128)]

    MONITORENUMPROC = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HANDLE, wintypes.HDC,
                                         ctypes.POINTER(wintypes.RECT), wintypes.LPARAM)
    _user32 = ctypes.WinDLL("user32")
    _user32.EnumDisplayMonitors.argtypes = [wintypes.HDC, ctypes.POINTER(wintypes.RECT), MONITORENUMPROC,
                                            wintypes.LPARAM]
    _user32.EnumDisplayMonitors.restype = wintypes.BOOL
    try:
        _dxva2 = ctypes.WinDLL("dxva2")
        _dxva2.GetNumberOfPhysicalMonitorsFromHMONITOR.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
        _dxva2.GetNumberOfPhysicalMonitorsFromHMONITOR.restype = wintypes.BOOL
        _dxva2.GetPhysicalMonitorsFromHMONITOR.argtypes = [wintypes.HANDLE, wintypes.DWORD,
                                                           ctypes.POINTER(PHYSICAL_MONITOR)]
        _dxva2.GetPhysicalMonitorsFromHMONITOR.restype = wintypes.BOOL
        _dxva2.GetVCPFeatureAndVCPFeatureReply.argtypes = [wintypes.HANDLE, wintypes.BYTE, ctypes.c_void_p,
                                                           ctypes.POINTER(wintypes.DWORD),
                                                           ctypes.POINTER(wintypes.DWORD)]
        _dxva2.GetVCPFeatureAndVCPFeatureReply.restype = wintypes.BOOL
        _dxva2.SetVCPFeature.argtypes = [wintypes.HANDLE, wintypes.BYTE, wintypes.DWORD]
        _dxva2.SetVCPFeature.restype = wintypes.BOOL
        _dxva2.DestroyPhysicalMonitor.argtypes = [wintypes.HANDLE]
        _dxva2.DestroyPhysicalMonitor.restype = wintypes.BOOL
    except (OSError, AttributeError):
        _dxva2 = None


class Monitores:
    """Uso: with Monitores() as ms: for m in ms.lista: ms.ler(m, "brilho")"""

    def __enter__(self):
        self.lista = []
        self._arrays = []
        if not IS_WIN or _dxva2 is None:
            return self
        hmons = []

        def cb(h, _hdc, _rect, _lp):
            hmons.append(h)
            return True

        proc = MONITORENUMPROC(cb)  # guardar a referência até o fim da chamada
        _user32.EnumDisplayMonitors(None, None, proc, 0)
        for h in hmons:
            n = wintypes.DWORD()
            if not _dxva2.GetNumberOfPhysicalMonitorsFromHMONITOR(h, ctypes.byref(n)) or not n.value:
                continue
            arr = (PHYSICAL_MONITOR * n.value)()
            if _dxva2.GetPhysicalMonitorsFromHMONITOR(h, n.value, arr):
                self._arrays.append(arr)
                self.lista.extend(arr)
        return self

    def __exit__(self, *_):
        for m in self.lista:
            try:
                _dxva2.DestroyPhysicalMonitor(m.handle)
            except Exception:
                pass
        self.lista = []

    @staticmethod
    def ler(m, ajuste):
        """(atual, máximo) ou None se o monitor não respondeu."""
        atual, maximo = wintypes.DWORD(), wintypes.DWORD()
        for tentativa in range(3):
            if _dxva2.GetVCPFeatureAndVCPFeatureReply(m.handle, VCP[ajuste], None, ctypes.byref(atual),
                                                      ctypes.byref(maximo)):
                return atual.value, maximo.value
            time.sleep(0.05 * (tentativa + 1))
        return None

    @staticmethod
    def definir(m, ajuste, valor):
        for tentativa in range(3):
            if _dxva2.SetVCPFeature(m.handle, VCP[ajuste], int(valor)):
                return True
            time.sleep(0.05 * (tentativa + 1))
        return False


def porcentagem(m, ajuste):
    """Valor atual de 0 a 100 (ou None se o monitor não respondeu)."""
    r = Monitores.ler(m, ajuste)
    if not r:
        return None
    atual, maximo = r
    return int(round(atual * 100 / maximo)) if maximo else atual


def definir_porcentagem(m, ajuste, pct):
    r = Monitores.ler(m, ajuste)
    maximo = r[1] if r and r[1] else 100
    return Monitores.definir(m, ajuste, round(max(0, min(100, pct)) * maximo / 100))


def nome(m):
    return (m.descricao or "").strip() or "Monitor"
