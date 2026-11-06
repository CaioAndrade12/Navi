"""Volume do Windows pela API Core Audio, só com ctypes (sem pycaw/comtypes).

Funciona igual rodando pelo Python ou empacotado no NaviPC.exe.
"""

import ctypes
import sys
import uuid

IS_WIN = sys.platform == "win32"


class GUID(ctypes.Structure):
    _fields_ = [("Data1", ctypes.c_uint32), ("Data2", ctypes.c_uint16), ("Data3", ctypes.c_uint16),
                ("Data4", ctypes.c_ubyte * 8)]


def _guid(texto):
    u = uuid.UUID(texto)
    g = GUID()
    g.Data1, g.Data2, g.Data3 = u.fields[0], u.fields[1], u.fields[2]
    for i, b in enumerate(u.bytes[8:]):
        g.Data4[i] = b
    return g


CLSID_MMDeviceEnumerator = _guid("BCDE0395-E52F-467C-8E3D-C4579291692E")
IID_IMMDeviceEnumerator = _guid("A95664D2-9614-4F35-A746-DE8DB63617E6")
IID_IAudioEndpointVolume = _guid("5CDF2C82-841E-4546-9722-0CF74078229A")
CLSCTX_ALL = 0x17

if IS_WIN:
    from ctypes import wintypes

    ole32 = ctypes.WinDLL("ole32")
    ole32.CoInitializeEx.argtypes = [ctypes.c_void_p, wintypes.DWORD]
    ole32.CoInitializeEx.restype = ctypes.c_long
    ole32.CoUninitialize.argtypes = []
    ole32.CoUninitialize.restype = None
    ole32.CoCreateInstance.argtypes = [ctypes.POINTER(GUID), ctypes.c_void_p, wintypes.DWORD,
                                       ctypes.POINTER(GUID), ctypes.POINTER(ctypes.c_void_p)]
    ole32.CoCreateInstance.restype = ctypes.c_long


def _ok(hr, oque):
    if hr < 0:
        codigo = hr & 0xFFFFFFFF
        if codigo == 0x80070490:
            raise OSError("Nenhuma saída de som encontrada no PC.")
        raise OSError(f"{oque} falhou (0x{codigo:08X})")
    return hr


def _metodo(obj, indice, *argtypes):
    """Função número `indice` da tabela virtual de um objeto COM."""
    tabela = ctypes.cast(obj, ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p))).contents
    prototipo = ctypes.WINFUNCTYPE(ctypes.c_long, ctypes.c_void_p, *argtypes)
    funcao = prototipo(tabela[indice])
    return lambda *args: funcao(obj, *args)


def _soltar(obj):
    try:
        if obj:
            _metodo(obj, 2)()  # IUnknown::Release
    except Exception:
        pass


class Volume:
    """Uso: with Volume() as v: v.definir(30); print(v.nivel())"""

    def __enter__(self):
        if not IS_WIN:
            raise OSError("Só funciona no Windows.")
        hr = ole32.CoInitializeEx(None, 0x2)  # COINIT_APARTMENTTHREADED
        self._desinicializar = hr in (0, 1)  # S_OK ou S_FALSE
        self._v = None
        enum = ctypes.c_void_p()
        dev = ctypes.c_void_p()
        erro = None
        try:
            _ok(ole32.CoCreateInstance(ctypes.byref(CLSID_MMDeviceEnumerator), None, CLSCTX_ALL,
                                       ctypes.byref(IID_IMMDeviceEnumerator), ctypes.byref(enum)),
                "Abrir o áudio do Windows")
            # IMMDeviceEnumerator::GetDefaultAudioEndpoint(eRender=0, eMultimedia=1, &device)
            _ok(_metodo(enum, 4, ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.c_void_p))(
                0, 1, ctypes.byref(dev)), "Achar a saída de som")
            vol = ctypes.c_void_p()
            # IMMDevice::Activate(IID_IAudioEndpointVolume, CLSCTX_ALL, NULL, &vol)
            _ok(_metodo(dev, 3, ctypes.POINTER(GUID), wintypes.DWORD, ctypes.c_void_p,
                        ctypes.POINTER(ctypes.c_void_p))(
                ctypes.byref(IID_IAudioEndpointVolume), CLSCTX_ALL, None, ctypes.byref(vol)),
                "Abrir o controle de volume")
            self._v = vol
        except Exception as e:
            erro = e
        finally:
            # Solta os objetos intermediários ANTES de qualquer CoUninitialize.
            _soltar(dev)
            _soltar(enum)
        if erro is not None:
            self.__exit__(None, None, None)
            raise erro
        return self

    def __exit__(self, *_):
        _soltar(self._v)
        self._v = None
        if self._desinicializar:
            self._desinicializar = False
            ole32.CoUninitialize()

    # Ordem da tabela de IAudioEndpointVolume: 7 SetMasterVolumeLevelScalar,
    # 9 GetMasterVolumeLevelScalar, 14 SetMute, 15 GetMute.
    def nivel(self):
        f = ctypes.c_float()
        _ok(_metodo(self._v, 9, ctypes.POINTER(ctypes.c_float))(ctypes.byref(f)), "Ler o volume")
        return int(round(f.value * 100))

    def definir(self, porcentagem):
        alvo = max(0.0, min(1.0, float(porcentagem) / 100.0))
        _ok(_metodo(self._v, 7, ctypes.c_float, ctypes.c_void_p)(alvo, None), "Mudar o volume")

    def mudo(self):
        b = wintypes.BOOL()
        _ok(_metodo(self._v, 15, ctypes.POINTER(wintypes.BOOL))(ctypes.byref(b)), "Ler o mudo")
        return bool(b.value)

    def definir_mudo(self, ligado):
        _ok(_metodo(self._v, 14, wintypes.BOOL, ctypes.c_void_p)(1 if ligado else 0, None), "Mudar o mudo")
